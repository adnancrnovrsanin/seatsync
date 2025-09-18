package com.adnan.seatsync.domain.model

import java.util.UUID

@JvmInline value class UserId(val value: String)
@JvmInline value class EventId(val value: UUID)
@JvmInline value class TicketId(val value: UUID)

