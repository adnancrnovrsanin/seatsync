package com.adnan.seatsync.domain.model

import java.time.Instant

data class Event(
    val id: EventId,
    val name: String,
    val startsAt: Instant,
    val capacity: Int,
    val purchased: Int,
    val maxPerRequest: Int
) {
    val remaining: Int get() = capacity - purchased
    val isPast: Boolean get() = Instant.now().isAfter(startsAt)
}