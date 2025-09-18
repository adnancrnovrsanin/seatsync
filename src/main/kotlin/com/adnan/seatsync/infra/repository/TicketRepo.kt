package com.adnan.seatsync.infra.repository

import com.adnan.seatsync.domain.model.EventId
import com.adnan.seatsync.domain.model.Ticket
import com.adnan.seatsync.domain.model.TicketId
import com.adnan.seatsync.domain.model.TicketStatus
import com.adnan.seatsync.domain.model.UserId
import com.adnan.seatsync.infra.db.Tickets
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

class TicketRepo {
    fun insertAll(ts: List<Ticket>) = transaction {
        Tickets.batchInsert(ts) { t ->
            this[Tickets.id] = t.id.value
            this[Tickets.eventId] = t.eventId.value
            this[Tickets.userId] = t.userId.value
            this[Tickets.purchasedAt] = t.purchasedAt.epochSecond
            this[Tickets.status] = t.status.name
        }
    }

    fun forUser(uid: UserId): List<Ticket> = transaction {
        Tickets.selectAll().where{ Tickets.userId eq uid.value }.map {
            Ticket(
                TicketId(it[Tickets.id]),
                EventId(it[Tickets.eventId]),
                UserId(it[Tickets.userId]),
                Instant.ofEpochSecond(it[Tickets.purchasedAt]),
                TicketStatus.valueOf(it[Tickets.status])
            )
        }
    }

    fun get(id: TicketId): Ticket? = transaction {
        Tickets.selectAll().where{ Tickets.id eq id.value }.singleOrNull()?.let {
            Ticket(
                TicketId(it[Tickets.id]),
                EventId(it[Tickets.eventId]),
                UserId(it[Tickets.userId]),
                Instant.ofEpochSecond(it[Tickets.purchasedAt]),
                TicketStatus.valueOf(it[Tickets.status])
            )
        }
    }

    fun update(t: Ticket) = transaction {
        Tickets.update({ Tickets.id eq t.id.value }) {
            it[status] = t.status.name
        }
    }
}