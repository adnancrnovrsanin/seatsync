package com.adnan.seatsync.infra.db

import org.jetbrains.exposed.sql.*

object Tickets : Table("tickets") {
    val id = uuid("id").uniqueIndex()
    val eventId = uuid("event_id").index()
    val userId = varchar("user_id", 64).index()
    val purchasedAt = long("purchased_at_epoch_sec")
    val status = varchar("status", 16)
    override val primaryKey = PrimaryKey(id)
}