package com.adnan.seatsync.infra.repository

import com.adnan.seatsync.domain.model.Event
import com.adnan.seatsync.domain.model.EventId
import com.adnan.seatsync.domain.model.Ticket
import com.adnan.seatsync.infra.db.Events
import com.adnan.seatsync.infra.db.Tickets
import java.time.Instant
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

class EventRepo {
    private val logger = LoggerFactory.getLogger(EventRepo::class.java)

    fun get(id: EventId): Event? = transaction {
        Events.selectAll()
                .where { Events.id eq id.value }
                .singleOrNull()
                ?.let {
                    Event(
                            id = EventId(it[Events.id]),
                            name = it[Events.name],
                            startsAt = Instant.ofEpochSecond(it[Events.startsAt]),
                            capacity = it[Events.capacity],
                            purchased = it[Events.purchased],
                            maxPerRequest = it[Events.maxPerRequest]
                    )
                }
                .also { ev ->
                    if (ev == null) logger.warn("Event {} not found", id.value)
                    else
                            logger.debug(
                                    "Loaded event {} (remaining={}, purchased={})",
                                    ev.id.value,
                                    ev.remaining,
                                    ev.purchased
                            )
                }
    }

    fun incPurchased(id: EventId, by: Int) = transaction {
        val rows =
                Events.update({ Events.id eq id.value }) {
                    with(SqlExpressionBuilder) {
                        it.update(Events.purchased, Events.purchased + by)
                    }
                }
        logger.info("Increased purchased for event {} by {} (rows={})", id.value, by, rows)
    }

    fun decPurchased(id: EventId, by: Int) = transaction {
        val rows =
                Events.update({ Events.id eq id.value and (Events.purchased greaterEq by) }) {
                    with(SqlExpressionBuilder) {
                        it.update(Events.purchased, Events.purchased - by)
                    }
                }
        logger.info("Decreased purchased for event {} by {} (rows={})", id.value, by, rows)
    }

    fun create(e: Event) = transaction {
        Events.insert {
            it[id] = e.id.value
            it[name] = e.name
            it[startsAt] = e.startsAt.epochSecond
            it[capacity] = e.capacity
            it[purchased] = e.purchased
            it[maxPerRequest] = e.maxPerRequest
        }
                .also {
                    logger.info(
                            "Created event {} name={} capacity={} maxPerRequest={}",
                            e.id.value,
                            e.name,
                            e.capacity,
                            e.maxPerRequest
                    )
                }
    }

    fun listPublic(): List<Event> = transaction {
        Events.selectAll().map {
            Event(
                    EventId(it[Events.id]),
                    it[Events.name],
                    Instant.ofEpochSecond(it[Events.startsAt]),
                    it[Events.capacity],
                    it[Events.purchased],
                    it[Events.maxPerRequest]
            )
        }
    }

    /**
     * Atomically reserve capacity and insert tickets in a single transaction. Returns true when the
     * reservation+insert succeeded, false when there was not enough remaining capacity.
     */
    fun tryPurchaseAndInsertTickets(tickets: List<Ticket>): Boolean = transaction {
        if (tickets.isEmpty()) return@transaction false
        val eventId = tickets.first().eventId.value

        // Try to atomically increment purchased only when enough capacity remains
        logger.info("Attempting to reserve {} tickets for event {}", tickets.size, eventId)
        val updated =
                Events.update({
                    Events.id eq
                            eventId and
                            (Events.capacity - Events.purchased greaterEq tickets.size)
                }) {
                    with(SqlExpressionBuilder) {
                        it.update(Events.purchased, Events.purchased + tickets.size)
                    }
                }

        if (updated == 0) {
            // not enough capacity
            logger.warn(
                    "Not enough capacity to reserve {} tickets for event {}",
                    tickets.size,
                    eventId
            )
            return@transaction false
        }

        // insert tickets in the same transaction
        Tickets.batchInsert(tickets) { t ->
            this[Tickets.id] = t.id.value
            this[Tickets.eventId] = t.eventId.value
            this[Tickets.userId] = t.userId.value
            this[Tickets.purchasedAt] = t.purchasedAt.epochSecond
            this[Tickets.status] = t.status.name
        }

        logger.info(
                "Inserted {} tickets and incremented purchased for event {}",
                tickets.size,
                eventId
        )
        return@transaction true
    }
}
