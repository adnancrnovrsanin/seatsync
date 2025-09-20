package com.adnan.seatsync

import com.adnan.seatsync.app.installHttp
import com.adnan.seatsync.app.installKafkaConsumer
import com.adnan.seatsync.app.installSecurity
import com.adnan.seatsync.domain.expireIfPast
import com.adnan.seatsync.infra.db.Events
import com.adnan.seatsync.infra.db.Tickets
import com.adnan.seatsync.infra.repository.EventRepo
import com.adnan.seatsync.infra.repository.TicketRepo
import com.adnan.seatsync.infra.seeder.EventSeeder
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

fun main() {
        // Start a single embedded Netty server which registers our modules and routes.
        val eventRepo = EventRepo()
        val ticketRepo = TicketRepo()

        embeddedServer(Netty, port = 8080) {
                        // Initialize Database using application config (falls back to environment
                        // variables)
                        val cfg = environment.config
                        val dbUrl =
                                cfg.propertyOrNull("db.url")?.getString()
                                        ?: System.getenv("DB_URL")
                                                ?: "jdbc:postgresql://localhost:5432/seatsync"
                        val dbDriver =
                                cfg.propertyOrNull("db.driver")?.getString()
                                        ?: System.getenv("DB_DRIVER") ?: "org.postgresql.Driver"
                        val dbUser =
                                cfg.propertyOrNull("db.user")?.getString()
                                        ?: System.getenv("DB_USER") ?: "postgres"
                        val dbPass =
                                cfg.propertyOrNull("db.password")?.getString()
                                        ?: System.getenv("DB_PASS") ?: "postgres"

                        Database.connect(
                                url = dbUrl,
                                driver = dbDriver,
                                user = dbUser,
                                password = dbPass
                        )

                        // Ensure required tables exist. This will create them if they're missing.
                        transaction { SchemaUtils.create(Events, Tickets) }

                        // Seed sample events when DB is empty
                        EventSeeder.seedIfEmpty(eventRepo)

                        // Install content negotiation so Ktor can serialize Kotlin objects to JSON.
                        install(ContentNegotiation) { json() }

                        installSecurity()
                        installHttp(eventRepo, ticketRepo)
                        installKafkaConsumer(eventRepo)

                        // Start a background job that periodically expires tickets for past events.
                        launch(Dispatchers.Default) {
                                while (isActive) {
                                        try {
                                                val active = ticketRepo.allActive()
                                                for (t in active) {
                                                        val ev = eventRepo.get(t.eventId)
                                                        if (ev != null) {
                                                                val updated = expireIfPast(ev, t)
                                                                if (updated != t) {
                                                                        ticketRepo.update(updated)
                                                                }
                                                        }
                                                }
                                        } catch (_: Exception) {
                                                // ignore and continue
                                        }
                                        delay(60_000)
                                }
                        }
                }
                .start(wait = true)
}

fun Application.module() {}
