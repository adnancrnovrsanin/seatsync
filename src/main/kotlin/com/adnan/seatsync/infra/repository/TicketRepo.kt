package com.adnan.seatsync.infra.repository

import com.adnan.seatsync.domain.model.EventId
import com.adnan.seatsync.domain.model.Ticket
import com.adnan.seatsync.domain.model.TicketId
import com.adnan.seatsync.domain.model.TicketStatus
import com.adnan.seatsync.domain.model.UserId
import com.adnan.seatsync.infra.db.Tickets
import java.time.Instant
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

class TicketRepo {
    private val logger = LoggerFactory.getLogger(TicketRepo::class.java)

    fun insertAll(ts: List<Ticket>) = transaction {
        Tickets.batchInsert(ts) { t ->
            this[Tickets.id] = t.id.value
            this[Tickets.eventId] = t.eventId.value
            this[Tickets.userId] = t.userId.value
            this[Tickets.purchasedAt] = t.purchasedAt.epochSecond
            this[Tickets.status] = t.status.name
        }
        logger.info("Inserted {} tickets", ts.size)
    }

    fun forUser(uid: UserId): List<Ticket> = transaction {
        val list =
                Tickets.selectAll().where { Tickets.userId eq uid.value }.map {
                    Ticket(
                            TicketId(it[Tickets.id]),
                            EventId(it[Tickets.eventId]),
                            UserId(it[Tickets.userId]),
                            Instant.ofEpochSecond(it[Tickets.purchasedAt]),
                            TicketStatus.valueOf(it[Tickets.status])
                    )
                }
        logger.debug("Loaded {} tickets for user {}", list.size, uid.value)
        list
    }

    fun allActive(): List<Ticket> = transaction {
        val list =
                Tickets.selectAll().where { Tickets.status eq TicketStatus.ACTIVE.name }.map {
                    Ticket(
                            TicketId(it[Tickets.id]),
                            EventId(it[Tickets.eventId]),
                            UserId(it[Tickets.userId]),
                            Instant.ofEpochSecond(it[Tickets.purchasedAt]),
                            TicketStatus.valueOf(it[Tickets.status])
                    )
                }
        logger.debug("Loaded {} active tickets", list.size)
        list
    }

    fun get(id: TicketId): Ticket? = transaction {
        Tickets.selectAll()
                .where { Tickets.id eq id.value }
                .singleOrNull()
                ?.let {
                    Ticket(
                            TicketId(it[Tickets.id]),
                            EventId(it[Tickets.eventId]),
                            UserId(it[Tickets.userId]),
                            Instant.ofEpochSecond(it[Tickets.purchasedAt]),
                            TicketStatus.valueOf(it[Tickets.status])
                    )
                }
                .also { t ->
                    if (t == null) logger.warn("Ticket {} not found", id.value)
                    else logger.debug("Loaded ticket {} for event {}", t.id.value, t.eventId.value)
                }
    }

    fun update(t: Ticket) = transaction {
        val rows = Tickets.update({ Tickets.id eq t.id.value }) { it[status] = t.status.name }
        logger.info("Updated ticket {} to status {} (rows={})", t.id.value, t.status, rows)
    }
}
