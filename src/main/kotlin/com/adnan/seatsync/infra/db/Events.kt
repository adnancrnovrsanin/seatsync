package com.adnan.seatsync.infra.db

import org.jetbrains.exposed.sql.Table

object Events : Table("events") {
    val id = uuid("id").uniqueIndex()
    val name = varchar("name", 200)
    val startsAt = long("starts_at_epoch_sec")
    val capacity = integer("capacity")
    val purchased = integer("purchased")
    val maxPerRequest = integer("max_per_request")
    override val primaryKey = PrimaryKey(id)
}