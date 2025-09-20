package com.adnan.seatsync.app.dto

import kotlinx.serialization.Serializable

@Serializable
data class EventDto(
        val id: String,
        val name: String,
        val startsAt: String,
        val remaining: Int,
        val maxPerRequest: Int
)
