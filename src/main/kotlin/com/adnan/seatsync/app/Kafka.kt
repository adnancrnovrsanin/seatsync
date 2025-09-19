package com.adnan.seatsync.app

import com.adnan.seatsync.domain.model.*
import com.adnan.seatsync.domain.purchase
import com.adnan.seatsync.infra.repository.*
import com.adnan.seatsync.util.fold
import io.ktor.server.application.*
import kotlinx.coroutines.*
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import java.time.Duration
import java.util.Properties
import java.util.UUID

data class PurchaseMsg(val userId: String, val eventId: String, val quantity: Int)

fun Application.installKafkaConsumer(eventRepo: EventRepo, ticketRepo: TicketRepo) {
    val enabled = (System.getenv("KAFKA_ENABLED") ?: "true").toBoolean()
    if (!enabled) return

    val props = Properties().apply {
        put("bootstrap.servers", System.getenv("KAFKA_BOOTSTRAP") ?: "kafka:29092")
        put("group.id", "ticket-purchases")
        put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
        put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
        put("auto.offset.reset", "earliest")
        put("enable.auto.commit", "false") // manual commit for at-least-once semantics
    }

    val consumer = KafkaConsumer<String, String>(props).apply {
        subscribe(listOf(System.getenv("KAFKA_TOPIC_PURCHASE") ?: "purchase-requests"))
    }

    // DLQ producer (simple) - use same bootstrap servers
    val prodProps = Properties().apply {
        put("bootstrap.servers", System.getenv("KAFKA_BOOTSTRAP") ?: "kafka:29092")
        put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    }
    val dlqTopic = System.getenv("KAFKA_TOPIC_DLQ") ?: "purchase-requests-dlq"
    val producer = KafkaProducer<String, String>(prodProps)

    environment.monitor.subscribe(ApplicationStarted) {
        val job = launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val recs = try {
                        consumer.poll(Duration.ofSeconds(1))
                    } catch (e: Exception) {
                        // log and backoff
                        delay(1000)
                        continue
                    }
                    for (r in recs) {
                        val recordKey = r.key()
                        val partition = r.partition()
                        val offset = r.offset()
                        try {
                            val msg = try { parsePurchase(r.value()) } catch (e: Exception) {
                                // malformed message -> send to DLQ and continue
                                producer.send(ProducerRecord(dlqTopic, recordKey, r.value()))
                                continue
                            }

                            val event = eventRepo.get(EventId(UUID.fromString(msg.eventId)))
                            val res = purchase(
                                event = event,
                                existingForEvent = 0,
                                req = PurchaseRequest(UserId(msg.userId), EventId(java.util.UUID.fromString(msg.eventId)), msg.quantity)
                            )

                            var processedOk = false
                            res.fold(
                                { /*domain error - move to DLQ for manual inspection*/
                                    producer.send(ProducerRecord(dlqTopic, recordKey, r.value()))
                                },
                                { tickets ->
                                    val ok = eventRepo.tryPurchaseAndInsertTickets(tickets)
                                    if (!ok) {
                                        // not enough capacity -> move to DLQ or log
                                        producer.send(ProducerRecord(dlqTopic, recordKey, r.value()))
                                    } else {
                                        processedOk = true
                                    }
                                }
                            )

                            // commit offset for the partition up to this record if processedOk
                            if (processedOk) {
                                val tp = TopicPartition(r.topic(), partition)
                                val meta = OffsetAndMetadata(offset + 1)
                                consumer.commitSync(mapOf(tp to meta))
                            }
                        } catch (e: Exception) {
                            // any unexpected error -> send to DLQ and continue
                            try { producer.send(ProducerRecord(dlqTopic, recordKey, r.value())) } catch (_: Exception) {}
                        }
                    }
                }
            } finally {
                try { consumer.close() } catch (_: Exception) {}
            }
        }

        environment.monitor.subscribe(ApplicationStopped) {
            job.cancel()
        }
    }
}

fun parsePurchase(json: String): PurchaseMsg {
    // Very small extractor to avoid adding a JSON dependency here.
    // Expects fields: "userId":"...", "eventId":"...", "quantity":<number>
    val userRe = """"userId"\s*:\s*"([^"]+)"""".toRegex()
    val eventRe = """"eventId"\s*:\s*"([^"]+)"""".toRegex()
    val qtyRe = """"quantity"\s*:\s*(\d+)""".toRegex()

    val user = userRe.find(json)?.groups?.get(1)?.value ?: throw IllegalArgumentException("missing userId")
    val eventId = eventRe.find(json)?.groups?.get(1)?.value ?: throw IllegalArgumentException("missing eventId")
    val qtyStr = qtyRe.find(json)?.groups?.get(1)?.value ?: throw IllegalArgumentException("missing quantity")
    val qty = qtyStr.toIntOrNull() ?: throw IllegalArgumentException("invalid quantity")

    // validate UUID format (basic)
    try { java.util.UUID.fromString(eventId) } catch (e: Exception) { throw IllegalArgumentException("invalid eventId") }

    if (qty <= 0) throw IllegalArgumentException("quantity must be positive")
    return PurchaseMsg(user, eventId, qty)
}
