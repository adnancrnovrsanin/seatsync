package com.adnan.seatsync.app

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class Principal(val userId: String, val roles: Set<String>)

// Simple in-memory cache for token -> userId mapping to avoid hitting DummyJSON on every request.
private val tokenCache = mutableMapOf<String, String>()
private val tokenCacheMutex = Mutex()

fun Application.installSecurity() {
    install(Authentication) {
        bearer("auth-bearer") {
            authenticate { tokenCredential ->
                val token = tokenCredential.token

                // Try cache first
                val cached = tokenCacheMutex.withLock { tokenCache[token] }
                val userId =
                        if (cached != null) cached
                        else {
                            // Perform external auth call to DummyJSON
                            val http = HttpClient(CIO)
                            val resp =
                                    try {
                                        http.get("https://dummyjson.com/auth/me") {
                                            header(HttpHeaders.Authorization, "Bearer $token")
                                        }
                                    } catch (e: Exception) {
                                        null
                                    }

                            val uid =
                                    if (resp != null && resp.status.isSuccess()) {
                                        extractUserId(resp.bodyAsText())
                                    } else null

                            // cache successful lookups
                            if (uid != null) tokenCacheMutex.withLock { tokenCache[token] = uid }
                            uid
                        }

                if (userId != null) UserIdPrincipal(userId) else null
            }
        }
    }
}

suspend fun extractUserId(json: String): String {
    // Try to extract common fields returned by DummyJSON (/auth/me)
    val idRe = """"id"\s*:\s*(\d+)"""".toRegex()
    val usernameRe = """"username"\s*:\s*"([^"]+)"""".toRegex()
    val emailRe = """"email"\s*:\s*"([^"]+)"""".toRegex()

    idRe.find(json)?.groups?.get(1)?.value?.let {
        return it
    }
    usernameRe.find(json)?.groups?.get(1)?.value?.let {
        return it
    }
    emailRe.find(json)?.groups?.get(1)?.value?.let {
        return it
    }

    return json.hashCode().toString()
}

fun Route.Authenticated(block: Route.(Principal) -> Unit) {
    authenticate("auth-bearer") {
        route("") {
            handle {
                val pId = call.authentication.principal<UserIdPrincipal>()
                if (pId == null) {
                    call.respond(HttpStatusCode.Unauthorized)
                    return@handle
                }
                val admins = System.getenv("ADMINS")?.split(",")?.toSet() ?: emptySet()
                val roles = if (pId.name in admins) setOf("ADMIN") else emptySet()
                block(Principal(pId.name, roles))
            }
        }
    }
}

fun Route.AdminOnly(block: Route.() -> Unit) {
    authenticate("auth-bearer") {
        route("") {
            handle {
                val pId = call.authentication.principal<UserIdPrincipal>()
                if (pId == null) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@handle
                }
                val admins = System.getenv("ADMINS")?.split(",")?.toSet() ?: emptySet()
                if (pId.name !in admins) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@handle
                }
                block()
            }
        }
    }
}
