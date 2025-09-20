package com.adnan.seatsync.app.dto

import kotlinx.serialization.Serializable

@Serializable
data class TicketDto(
        val id: String,
        val eventId: String,
        val userId: String,
        val purchasedAt: String,
        val status: String
)
