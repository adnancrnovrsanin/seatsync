package com.adnan.seatsync.domain.model

import java.time.Instant

data class Ticket(
    val id: TicketId,
    val eventId: EventId,
    val userId: UserId,
    val purchasedAt: Instant,
    val status: TicketStatus
)