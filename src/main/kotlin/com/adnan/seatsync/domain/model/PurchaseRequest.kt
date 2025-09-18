package com.adnan.seatsync.domain.model

data class PurchaseRequest(
    val userId: UserId,
    val eventId: EventId,
    val quantity: Int
)