package com.adnan.seatsync.infra.repository

import com.adnan.seatsync.domain.model.Event
import com.adnan.seatsync.domain.model.EventId
import com.adnan.seatsync.infra.db.Events
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

class EventRepo {
    fun get(id: EventId): Event? = transaction {
        Events.selectAll().where { Events.id eq id.value }.singleOrNull()?.let {
            Event(
                id = EventId(it[Events.id]),
                name = it[Events.name],
                startsAt = Instant.ofEpochSecond(it[Events.startsAt]),
                capacity = it[Events.capacity],
                purchased = it[Events.purchased],
                maxPerRequest = it[Events.maxPerRequest]
            )
        }
    }

    fun incPurchased(id: EventId, by: Int) = transaction {
        Events.update({ Events.id eq id.value }) {
            with(SqlExpressionBuilder) {
                it.update(Events.purchased, Events.purchased + by)
            }
        }
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
}