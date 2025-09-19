package com.adnan.seatsync.app

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*

data class Principal(val userId: String, val roles: Set<String>)

fun Application.installSecurity() {
    install(Authentication) {
        bearer("auth-bearer") {
            authenticate { tokenCredential ->
                val token = tokenCredential.token
                // PROVERA PREKO DummyJSON (simplifikovano; dodajte cache i error-handling)
                val http = HttpClient()
                val resp = http.get("https://dummyjson.com/auth/me") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                if (resp.status.isSuccess()) {
                    // izvucite userId iz JSON-a; ovde ćemo ga premostiti kao raw token za primer
                    val userId = extractUserId(resp.bodyAsText()) // TODO: parse JSON
                    val admins = System.getenv("ADMINS")?.split(",")?.toSet() ?: emptySet()
                    val roles = if (userId in admins) setOf("ADMIN") else emptySet()
                    UserIdPrincipal(userId).let { Principal(userId, roles) }
                } else null
            }
        }
    }
}

suspend fun extractUserId(json: String): String = /* parse "id" ili "email" */ json.hashCode().toString()

fun Route.Authenticated(block: Route.(Principal) -> Unit) {
    authenticate("auth-bearer") {
        route("") {
            handle {
                val p = call.authentication.principal<Principal>()
                if (p == null) call.respond(HttpStatusCode.Unauthorized) else block(p)
            }
        }
    }
}

fun Route.AdminOnly(block: Route.() -> Unit) {
    authenticate("auth-bearer") {
        route("") {
            handle {
                val p = call.authentication.principal<Principal>()
                if (p == null || "ADMIN" !in p.roles) call.respond(HttpStatusCode.Forbidden) else block()
            }
        }
    }
}
