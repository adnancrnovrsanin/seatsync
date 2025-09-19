package com.adnan.seatsync

import com.adnan.seatsync.app.installHttp
import com.adnan.seatsync.app.installKafkaConsumer
import com.adnan.seatsync.app.installSecurity
import com.adnan.seatsync.infra.repository.EventRepo
import com.adnan.seatsync.infra.repository.TicketRepo
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.jetbrains.exposed.sql.Database

fun main() {
	Database.connect(
		url = System.getenv("DB_URL") ?: "jdbc:postgresql://postgres:5432/tickets",
		driver = "org.postgresql.Driver",
		user = System.getenv("DB_USER") ?: "postgres",
		password = System.getenv("DB_PASS") ?: "postgres"
	)

	val eventRepo = EventRepo()
	val ticketRepo = TicketRepo()

	embeddedServer(Netty, port = 8080) {
		installSecurity()
		installHttp(eventRepo, ticketRepo)
		installKafkaConsumer(eventRepo, ticketRepo)
	}.start(wait = true)
}
