package com.adnan.seatsync.app

import UsersResponse
import com.adnan.seatsync.app.dto.EventDto
import com.adnan.seatsync.app.dto.LoginBody
import com.adnan.seatsync.app.dto.LoginResponse
import com.adnan.seatsync.app.dto.LoginResult
import com.adnan.seatsync.app.dto.LoginUser
import com.adnan.seatsync.app.dto.TicketDto
import com.adnan.seatsync.domain.cancelTicket
import com.adnan.seatsync.domain.model.*
import com.adnan.seatsync.domain.purchase
import com.adnan.seatsync.infra.repository.EventRepo
import com.adnan.seatsync.infra.repository.TicketRepo
import com.adnan.seatsync.util.Either
import com.adnan.seatsync.util.fold
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class CreateEventBody(
        val name: String,
        val startsAtEpochSec: Long,
        val capacity: Int,
        val maxPerRequest: Int
)

@Serializable data class PurchaseBody(val eventId: String, val quantity: Int)

private val log = LoggerFactory.getLogger("com.adnan.seatsync.app.Http")

fun Application.installHttpEndpoints(eventRepo: EventRepo, ticketRepo: TicketRepo) {
        routing {
                // Proxy to DummyJSON /users and parse using UsersResponse DTO
                get("/public/dummy/users") {
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 30
                        val skip = call.request.queryParameters["skip"]?.toIntOrNull() ?: 0
                        log.debug("GET /public/dummy/users limit={} skip={}", limit, skip)
                        val http =
                                HttpClient(CIO) {
                                        install(ContentNegotiation) {
                                                json(
                                                        Json {
                                                                ignoreUnknownKeys = true
                                                                isLenient = true
                                                                explicitNulls = false
                                                        }
                                                )
                                        }
                                }
                        val resp =
                                try {
                                        val start = System.nanoTime()
                                        val r =
                                                http.get("https://dummyjson.com/users") {
                                                        parameter("limit", limit)
                                                        parameter("skip", skip)
                                                }
                                        val elapsed = (System.nanoTime() - start) / 1_000_000
                                        log.info(
                                                "DummyJSON /users responded status={} in {}ms",
                                                r.status.value,
                                                elapsed
                                        )
                                        r
                                } catch (e: Exception) {
                                        log.error("Failed to reach DummyJSON /users", e)
                                        call.respond(
                                                HttpStatusCode.BadGateway,
                                                mapOf("error" to "dummyjson unreachable")
                                        )
                                        http.close()
                                        return@get
                                }

                        if (!resp.status.isSuccess()) {
                                log.warn(
                                        "DummyJSON /users non-success status={}",
                                        resp.status.value
                                )
                                call.respond(
                                        HttpStatusCode.BadGateway,
                                        mapOf(
                                                "error" to "failed to fetch users",
                                                "status" to resp.status.value
                                        )
                                )
                                http.close()
                                return@get
                        }

                        val users: UsersResponse =
                                try {
                                        resp.body()
                                } catch (e: Exception) {
                                        // Fall back to text to help troubleshooting
                                        val raw = runCatching { resp.bodyAsText() }.getOrNull()
                                        log.error(
                                                "Failed to parse DummyJSON /users response: {}... ({} bytes)",
                                                raw?.take(200),
                                                raw?.length ?: 0,
                                                e
                                        )
                                        call.respond(
                                                HttpStatusCode.InternalServerError,
                                                mapOf(
                                                        "error" to "failed to parse users response",
                                                        "details" to (e.message ?: "parse error"),
                                                        "raw" to (raw ?: "")
                                                )
                                        )
                                        http.close()
                                        return@get
                                }

                        log.info(
                                "Returning {} users (total={} skip={} limit={})",
                                users.users.size,
                                users.total,
                                users.skip,
                                users.limit
                        )
                        http.close()
                        call.respond(users)
                }

                // Dummy login endpoint that proxies to DummyJSON and then issues our local JWT
                post("/auth/login") {
                        val body =
                                try {
                                        call.receive<LoginBody>()
                                } catch (e: Exception) {
                                        log.warn("/auth/login invalid request body: {}", e.message)
                                        call.respond(
                                                HttpStatusCode.BadRequest,
                                                mapOf("error" to "invalid request body")
                                        )
                                        return@post
                                }

                        log.info(
                                "Login attempt username='{}' expiresInMins={}",
                                body.username,
                                body.expiresInMins
                        )
                        val http =
                                HttpClient(CIO) {
                                        // Configure JSON in case we later parse provider responses
                                        install(ContentNegotiation) {
                                                json(
                                                        Json {
                                                                ignoreUnknownKeys = true
                                                                isLenient = true
                                                                explicitNulls = false
                                                        }
                                                )
                                        }
                                }
                        val resp =
                                try {
                                        val start = System.nanoTime()
                                        val r =
                                                http.post("https://dummyjson.com/user/login") {
                                                        contentType(ContentType.Application.Json)
                                                        setBody(body)
                                                }
                                        val elapsed = (System.nanoTime() - start) / 1_000_000
                                        log.info(
                                                "Provider /user/login responded status={} in {}ms",
                                                r.status.value,
                                                elapsed
                                        )
                                        r
                                } catch (e: Exception) {
                                        log.error("Auth provider unreachable", e)
                                        call.respond(
                                                HttpStatusCode.BadGateway,
                                                mapOf("error" to "auth provider unreachable")
                                        )
                                        http.close()
                                        return@post
                                }

                        if (!resp.status.isSuccess()) {
                                val payload = resp.bodyAsText()
                                log.warn(
                                        "Invalid credentials for username='{}' status={} body='{}'",
                                        body.username,
                                        resp.status.value,
                                        payload.take(200)
                                )
                                call.respond(
                                        HttpStatusCode.Unauthorized,
                                        mapOf(
                                                "error" to "invalid credentials",
                                                "provider" to payload
                                        )
                                )
                                http.close()
                                return@post
                        }

                        val provider: LoginResponse =
                                try {
                                        resp.body()
                                } catch (e: Exception) {
                                        val payload = resp.bodyAsText()
                                        log.error(
                                                "Failed to parse provider login response: {}...",
                                                payload.take(200),
                                                e
                                        )
                                        call.respond(
                                                HttpStatusCode.InternalServerError,
                                                mapOf(
                                                        "error" to
                                                                "failed to parse provider response",
                                                        "details" to (e.message ?: "parse error"),
                                                        "raw" to payload
                                                )
                                        )
                                        http.close()
                                        return@post
                                }

                        val localUserId =
                                provider.id.toString().ifBlank { provider.username }.ifBlank {
                                        provider.accessToken ?: UUID.randomUUID().toString()
                                }

                        // Issue local JWT so our auth can validate without external call
                        // DummyJSON may return role in other endpoints; for login we default to
                        // none
                        val roles = emptyList<String>()
                        val secret = System.getenv("JWT_SECRET") ?: "dev-secret"
                        val issuer = System.getenv("JWT_ISSUER") ?: "seatsync"
                        val algo = Algorithm.HMAC256(secret)
                        val expMs = ((body.expiresInMins ?: 60) * 60L * 1000L)
                        val tokenBuilder =
                                JWT.create()
                                        .withIssuer(issuer)
                                        .withClaim("userId", localUserId)
                                        .withSubject(provider.username)
                                        .withExpiresAt(Date(System.currentTimeMillis() + expMs))
                        val token =
                                (if (roles.isNotEmpty())
                                                tokenBuilder.withArrayClaim(
                                                        "roles",
                                                        roles.toTypedArray()
                                                )
                                        else tokenBuilder)
                                        .sign(algo)

                        log.info(
                                "Login success username='{}' localUserId='{}' providerId={}",
                                provider.username,
                                localUserId,
                                provider.id
                        )

                        val result =
                                LoginResult(
                                        user =
                                                LoginUser(
                                                        id = provider.id,
                                                        username = provider.username,
                                                        email = provider.email,
                                                        firstName = provider.firstName,
                                                        lastName = provider.lastName,
                                                        image = provider.image
                                                ),
                                        token = token,
                                        providerAccessToken = provider.accessToken,
                                        providerRefreshToken = provider.refreshToken
                                )
                        http.close()
                        call.respond(HttpStatusCode.OK, result)
                }

                get("/public/events") {
                        call.respond(
                                eventRepo.listPublic().map {
                                        EventDto(
                                                id = it.id.value.toString(),
                                                name = it.name,
                                                startsAt = it.startsAt.toString(),
                                                remaining = it.remaining,
                                                maxPerRequest = it.maxPerRequest
                                        )
                                }
                        )
                }

                route("/admin") {
                        authenticate("auth-bearer") {
                                post("/events") {
                                        val principal = call.getAppPrincipal()
                                        if (principal == null || "ADMIN" !in principal.roles) {
                                                call.respond(HttpStatusCode.Forbidden)
                                                return@post
                                        }
                                        val body = call.receive<CreateEventBody>()
                                        log.info(
                                                "Admin {} creating event name='{}' startsAt={} capacity={} maxPerRequest={}",
                                                principal.userId,
                                                body.name,
                                                body.startsAtEpochSec,
                                                body.capacity,
                                                body.maxPerRequest
                                        )
                                        val e =
                                                Event(
                                                        id = EventId(UUID.randomUUID()),
                                                        name = body.name,
                                                        startsAt =
                                                                Instant.ofEpochSecond(
                                                                        body.startsAtEpochSec
                                                                ),
                                                        capacity = body.capacity,
                                                        purchased = 0,
                                                        maxPerRequest = body.maxPerRequest
                                                )
                                        eventRepo.create(e)
                                        log.info("Event created id={}", e.id.value)
                                        call.respond(
                                                HttpStatusCode.Created,
                                                mapOf("id" to e.id.value.toString())
                                        )
                                }
                        }
                }

                route("/user") {
                        authenticate("auth-bearer") {
                                post("/purchase") {
                                        val principal = call.getAppPrincipal()
                                        if (principal == null) {
                                                call.respond(HttpStatusCode.Unauthorized)
                                                return@post
                                        }
                                        val body =
                                                try {
                                                        call.receive<PurchaseBody>()
                                                } catch (e: Exception) {
                                                        log.warn(
                                                                "Invalid purchase body for user {}: {}",
                                                                principal.userId,
                                                                e.message
                                                        )
                                                        call.respond(
                                                                HttpStatusCode.BadRequest,
                                                                mapOf(
                                                                        "error" to
                                                                                "invalid request body"
                                                                )
                                                        )
                                                        return@post
                                                }

                                        val eventId =
                                                try {
                                                        EventId(
                                                                java.util.UUID.fromString(
                                                                        body.eventId
                                                                )
                                                        )
                                                } catch (e: Exception) {
                                                        log.warn(
                                                                "Invalid event id '{}' for user {}",
                                                                body.eventId,
                                                                principal.userId
                                                        )
                                                        call.respond(
                                                                HttpStatusCode.BadRequest,
                                                                mapOf("error" to "invalid event id")
                                                        )
                                                        return@post
                                                }

                                        val event = eventRepo.get(eventId)
                                        log.debug(
                                                "User {} purchasing {} tickets for event {} (exists={})",
                                                principal.userId,
                                                body.quantity,
                                                eventId.value,
                                                event != null
                                        )

                                        val req =
                                                PurchaseRequest(
                                                        userId = UserId(principal.userId),
                                                        eventId = event?.id ?: eventId,
                                                        quantity = body.quantity
                                                )

                                        val res =
                                                purchase(
                                                        event = event,
                                                        existingForEvent =
                                                                0, // (primer: ako želite per-user
                                                        // limit, ovde biste ga
                                                        // računali)
                                                        req = req
                                                )

                                        res.fold(
                                                { err ->
                                                        log.info(
                                                                "Purchase rejected for user {} event {}: {}",
                                                                principal.userId,
                                                                eventId.value,
                                                                err
                                                        )
                                                        call.respond(
                                                                HttpStatusCode.BadRequest,
                                                                mapOf("error" to err.toString())
                                                        )
                                                },
                                                { tickets ->
                                                        // Try to atomically reserve capacity and
                                                        // insert tickets in DB
                                                        val ok =
                                                                eventRepo
                                                                        .tryPurchaseAndInsertTickets(
                                                                                tickets
                                                                        )
                                                        if (!ok) {
                                                                log.info(
                                                                        "Purchase conflict (capacity) for user {} event {}",
                                                                        principal.userId,
                                                                        eventId.value
                                                                )
                                                                call.respond(
                                                                        HttpStatusCode.Conflict,
                                                                        mapOf(
                                                                                "error" to
                                                                                        "not enough capacity"
                                                                        )
                                                                )
                                                                return@post
                                                        }
                                                        log.info(
                                                                "Purchase success user {} event {} tickets={}",
                                                                principal.userId,
                                                                eventId.value,
                                                                tickets.map { it.id.value }
                                                        )
                                                        call.respond(
                                                                HttpStatusCode.OK,
                                                                mapOf(
                                                                        "tickets" to
                                                                                tickets.map {
                                                                                        it.id.value
                                                                                                .toString()
                                                                                }
                                                                )
                                                        )
                                                }
                                        )
                                }

                                get("/tickets") {
                                        val principal = call.getAppPrincipal()
                                        if (principal == null) {
                                                call.respond(HttpStatusCode.Unauthorized)
                                                return@get
                                        }
                                        val ts = ticketRepo.forUser(UserId(principal.userId))
                                        log.debug(
                                                "User {} listing tickets count={}",
                                                principal.userId,
                                                ts.size
                                        )
                                        call.respond(
                                                ts.map {
                                                        TicketDto(
                                                                id = it.id.value.toString(),
                                                                eventId =
                                                                        it.eventId.value.toString(),
                                                                userId = it.userId.value.toString(),
                                                                purchasedAt =
                                                                        it.purchasedAt.toString(),
                                                                status = it.status.name
                                                        )
                                                }
                                        )
                                }

                                post("/tickets/{id}/cancel") {
                                        val principal = call.getAppPrincipal()
                                        if (principal == null) {
                                                call.respond(HttpStatusCode.Unauthorized)
                                                return@post
                                        }
                                        val tid = java.util.UUID.fromString(call.parameters["id"])
                                        val t = ticketRepo.get(TicketId(tid))
                                        if (t == null) {
                                                log.warn(
                                                        "Cancel ticket not found id={} user {}",
                                                        tid,
                                                        principal.userId
                                                )
                                                call.respond(HttpStatusCode.NotFound)
                                                return@post
                                        }
                                        when (val r = cancelTicket(t, UserId(principal.userId))) {
                                                is Either.Left -> {
                                                        log.info(
                                                                "Cancel rejected user {} ticket {}: {}",
                                                                principal.userId,
                                                                tid,
                                                                r.value
                                                        )
                                                        call.respond(
                                                                HttpStatusCode.BadRequest,
                                                                mapOf("error" to r.value.toString())
                                                        )
                                                }
                                                is Either.Right -> {
                                                        ticketRepo.update(r.value)
                                                        // Decrement purchased count for the event
                                                        // since the ticket is returned
                                                        try {
                                                                eventRepo.decPurchased(t.eventId, 1)
                                                        } catch (_: Exception) {
                                                                // best-effort: if decrement fails,
                                                                // continue — DB will be eventually
                                                                // consistent
                                                        }
                                                        log.info(
                                                                "Cancel success user {} ticket {}",
                                                                principal.userId,
                                                                tid
                                                        )
                                                        call.respond(HttpStatusCode.OK)
                                                }
                                        }
                                }
                        }
                }
        }
}
