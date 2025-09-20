package com.adnan.seatsync.infra.seeder

import com.adnan.seatsync.domain.model.Event
import com.adnan.seatsync.domain.model.EventId
import com.adnan.seatsync.infra.db.Events
import com.adnan.seatsync.infra.repository.EventRepo
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

object EventSeeder {
    /** Seeds sample events if the events table is empty. Safe to call on every startup. */
    fun seedIfEmpty(eventRepo: EventRepo) {
        val existing = transaction { Events.selectAll().limit(1).any() }
        if (existing) return

        val now = Instant.now()

        val samples =
                listOf(
                        Event(
                                id =
                                        EventId(
                                                UUID.fromString(
                                                        "11111111-1111-1111-1111-111111111111"
                                                )
                                        ),
                                name = "Rock Concert - The Rolling Coders",
                                startsAt = now.plusSeconds(60 * 60 * 24 * 7), // 1 week
                                capacity = 500,
                                purchased = 0,
                                maxPerRequest = 6
                        ),
                        Event(
                                id =
                                        EventId(
                                                UUID.fromString(
                                                        "22222222-2222-2222-2222-222222222222"
                                                )
                                        ),
                                name = "Tech Talk: Kotlin Coroutines",
                                startsAt = now.plusSeconds(60 * 60 * 24 * 3), // 3 days
                                capacity = 150,
                                purchased = 0,
                                maxPerRequest = 4
                        ),
                        Event(
                                id =
                                        EventId(
                                                UUID.fromString(
                                                        "33333333-3333-3333-3333-333333333333"
                                                )
                                        ),
                                name = "Local Theater: Async Adventures",
                                startsAt = now.plusSeconds(60 * 60 * 24 * 30), // 30 days
                                capacity = 80,
                                purchased = 0,
                                maxPerRequest = 2
                        )
                )

        samples.forEach { eventRepo.create(it) }
    }
}
