package com.adnan.seatsync

import com.adnan.seatsync.app.parsePurchase
import com.adnan.seatsync.domain.model.PurchaseRequest
import com.adnan.seatsync.domain.model.UserId
import com.adnan.seatsync.domain.purchase
import com.adnan.seatsync.infra.db.Events
import com.adnan.seatsync.infra.db.Tickets
import com.adnan.seatsync.infra.repository.EventRepo
import com.adnan.seatsync.infra.repository.TicketRepo
import com.adnan.seatsync.util.fold
import java.time.Instant
import java.util.*
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName

class KafkaPurchaseIntegrationTest {
        private val logger = LoggerFactory.getLogger(KafkaPurchaseIntegrationTest::class.java)
        companion object {
                private val postgres =
                        PostgreSQLContainer("postgres:16").apply {
                                withDatabaseName("seatsync")
                                withUsername("postgres")
                                withPassword("postgres")
                                start()
                        }

                private val kafka =
                        KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0")).apply {
                                start()
                        }

                @BeforeAll
                @JvmStatic
                fun setup() {
                        Database.connect(
                                url = postgres.jdbcUrl,
                                driver = "org.postgresql.Driver",
                                user = postgres.username,
                                password = postgres.password
                        )
                        transaction { SchemaUtils.create(Events, Tickets) }
                }

                @AfterAll
                @JvmStatic
                fun teardown() {
                        try {
                                kafka.stop()
                        } catch (_: Exception) {}
                        try {
                                postgres.stop()
                        } catch (_: Exception) {}
                }
        }

        @Test
        fun `kafka purchase message should create tickets`() {
                val eventRepo = EventRepo()
                val ticketRepo = TicketRepo()

                // create sample event
                val eventId = UUID.fromString("11111111-1111-1111-1111-111111111111")
                val now = Instant.now()
                val e =
                        com.adnan.seatsync.domain.model.Event(
                                id = com.adnan.seatsync.domain.model.EventId(eventId),
                                name = "Test Event",
                                startsAt = now.plusSeconds(3600),
                                capacity = 100,
                                purchased = 0,
                                maxPerRequest = 10
                        )
                eventRepo.create(e)
                logger.info("Seeded event {}", eventId)

                // ensure topic exists (some images disable auto-create)
                val adminProps =
                        Properties().apply {
                                put(
                                        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                        kafka.bootstrapServers
                                )
                        }
                AdminClient.create(adminProps).use { admin ->
                        admin.createTopics(listOf(NewTopic("purchase-requests", 1, 1.toShort())))
                                .all()
                                .get()
                }
                logger.info("Ensured topic purchase-requests exists")

                // produce purchase message to kafka
                val props =
                        Properties().apply {
                                put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
                                put(
                                        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                                        StringSerializer::class.java.name
                                )
                                put(
                                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                                        StringSerializer::class.java.name
                                )
                                put(ProducerConfig.ACKS_CONFIG, "all")
                        }
                KafkaProducer<String, String>(props).use { prod ->
                        val msg = "{\"userId\":\"user-1\",\"eventId\":\"$eventId\",\"quantity\":2}"
                        prod.send(ProducerRecord("purchase-requests", null, msg)).get()
                }
                logger.info("Produced purchase request message")

                // create a simple consumer to read and process one message similarly to production
                val cprops =
                        Properties().apply {
                                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
                                put(ConsumerConfig.GROUP_ID_CONFIG, "test-group")
                                put(
                                        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                                        StringDeserializer::class.java.name
                                )
                                put(
                                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                                        StringDeserializer::class.java.name
                                )
                                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                        }

                KafkaConsumer<String, String>(cprops).use { cons ->
                        cons.subscribe(listOf("purchase-requests"))
                        val recs = cons.poll(java.time.Duration.ofSeconds(5))
                        assert(!recs.isEmpty)
                        val r = recs.first()
                        val parsed = parsePurchase(r.value())

                        // mimic consumer processing
                        val event =
                                eventRepo.get(
                                        com.adnan.seatsync.domain.model.EventId(
                                                UUID.fromString(parsed.eventId)
                                        )
                                )
                        val res =
                                purchase(
                                        event,
                                        0,
                                        PurchaseRequest(
                                                UserId(parsed.userId),
                                                com.adnan.seatsync.domain.model.EventId(
                                                        UUID.fromString(parsed.eventId)
                                                ),
                                                parsed.quantity
                                        )
                                )
                        res.fold(
                                { err -> throw RuntimeException("domain error: $err") },
                                { tickets ->
                                        val ok = eventRepo.tryPurchaseAndInsertTickets(tickets)
                                        if (!ok) throw RuntimeException("not enough capacity")
                                        logger.info("Persisted {} tickets", tickets.size)
                                }
                        )
                }

                // assert tickets in DB
                val tickets = ticketRepo.forUser(com.adnan.seatsync.domain.model.UserId("user-1"))
                assertEquals(2, tickets.size)
                val ev = eventRepo.get(com.adnan.seatsync.domain.model.EventId(eventId))
                assertEquals(2, ev?.purchased)
                logger.info(
                        "Assertion passed: 2 tickets purchased, event purchased={}",
                        ev?.purchased
                )
        }
}
