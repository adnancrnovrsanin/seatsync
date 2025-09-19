package com.adnan.seatsync.app

import com.adnan.seatsync.domain.cancelTicket
import com.adnan.seatsync.domain.model.*
import com.adnan.seatsync.domain.purchase
import com.adnan.seatsync.infra.repository.EventRepo
import com.adnan.seatsync.infra.repository.TicketRepo
import com.adnan.seatsync.util.Either
import com.adnan.seatsync.util.fold
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import java.util.UUID

data class CreateEventBody(
    val name: String,
    val startsAtEpochSec: Long,
    val capacity: Int,
    val maxPerRequest: Int
)
data class PurchaseBody(val eventId: String, val quantity: Int)

fun Application.installHttp(eventRepo: EventRepo, ticketRepo: TicketRepo) {
    routing {
        get("/public/events") {
            call.respond(eventRepo.listPublic().map {
                mapOf(
                    "id" to it.id.value.toString(),
                    "name" to it.name,
                    "startsAt" to it.startsAt.toString(),
                    "remaining" to it.remaining,
                    "maxPerRequest" to it.maxPerRequest
                )
            })
        }

        route("/admin") {
            AdminOnly {
                post("/events") {
                    val body = call.receive<CreateEventBody>()
                    val e = Event(
                        id = EventId(UUID.randomUUID()),
                        name = body.name,
                        startsAt = Instant.ofEpochSecond(body.startsAtEpochSec),
                        capacity = body.capacity,
                        purchased = 0,
                        maxPerRequest = body.maxPerRequest
                    )
                    eventRepo.create(e)
                    call.respond(HttpStatusCode.Created, mapOf("id" to e.id.value.toString()))
                }
            }
        }

        route("/user") {
            Authenticated { principal ->
                post("/purchase") {
                    val body = try {
                        call.receive<PurchaseBody>()
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid request body")); return@post
                    }

                    val eventId = try {
                        EventId(java.util.UUID.fromString(body.eventId))
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid event id")); return@post
                    }

                    val event = eventRepo.get(eventId)

                    val req = PurchaseRequest(
                        userId = UserId(principal.userId),
                        eventId = event?.id ?: eventId,
                        quantity = body.quantity
                    )

                    val res = purchase(
                        event = event,
                        existingForEvent = 0, // (primer: ako želite per-user limit, ovde biste ga računali)
                        req = req
                    )

                    res.fold(
                        { err -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to err.toString())) },
                        { tickets ->
                            // Try to atomically reserve capacity and insert tickets in DB
                            val ok = eventRepo.tryPurchaseAndInsertTickets(tickets)
                            if (!ok) {
                                call.respond(HttpStatusCode.Conflict, mapOf("error" to "not enough capacity")); return@post
                            }
                            call.respond(HttpStatusCode.OK, mapOf("tickets" to tickets.map { it.id.value.toString() }))
                        }
                    )
                }

                get("/tickets") {
                    val ts = ticketRepo.forUser(UserId(principal.userId))
                    call.respond(ts)
                }

                post("/tickets/{id}/cancel") {
                    val tid = java.util.UUID.fromString(call.parameters["id"])
                    val t = ticketRepo.get(TicketId(tid))
                    if (t == null) {
                        call.respond(HttpStatusCode.NotFound); return@post
                    }
                    when (val r = cancelTicket(t, UserId(principal.userId))) {
                        is Either.Left -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to r.value.toString()))
                        is Either.Right -> {
                            ticketRepo.update(r.value)
                            call.respond(HttpStatusCode.OK)
                        }
                    }
                }
            }
        }
    }
}
