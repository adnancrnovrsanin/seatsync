package com.adnan.seatsync.app

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking

data class AppPrincipal(val userId: String, val roles: Set<String>)

// Simple in-memory cache (thread-safe) for token -> userId
private val tokenCache = ConcurrentHashMap<String, String>()

fun Application.installAppSecurity() {
    install(Authentication) {
        bearer("auth-bearer") {
            authenticate { credentials: BearerTokenCredential ->
                val token = credentials.token

                // 1) Try local JWT verification first (if JWT_SECRET is configured)
                verifyLocalJwt(token)?.let { uid ->
                    return@authenticate UserIdPrincipal(uid)
                }

                // 2) Otherwise fall back to DummyJSON access token validation (/user/me)
                val cached = tokenCache[token]
                val userId =
                        if (cached != null) cached
                        else {
                            val http = HttpClient(CIO)
                            val resp =
                                    try {
                                        runBlocking {
                                            http.get("https://dummyjson.com/user/me") {
                                                header(HttpHeaders.Authorization, "Bearer $token")
                                            }
                                        }
                                    } catch (_: Exception) {
                                        null
                                    }

                            val uid =
                                    if (resp != null && resp.status.value in 200..299) {
                                        val text = runBlocking { resp.bodyAsText() }
                                        extractUserId(text)
                                    } else null
                            if (uid != null) tokenCache[token] = uid
                            uid
                        }
                if (userId != null) UserIdPrincipal(userId) else null
            }
        }
    }
}

private fun verifyLocalJwt(token: String): String? {
    // Match Http.kt token creation defaults so local tokens work without env vars
    val secret = System.getenv("JWT_SECRET") ?: "dev-secret"
    val issuer = System.getenv("JWT_ISSUER") ?: "seatsync"
    val algo = Algorithm.HMAC256(secret)
    return try {
        val verifier = JWT.require(algo).withIssuer(issuer).build()
        val decoded = verifier.verify(token)
        decoded.getClaim("userId")?.asString()?.takeIf { it.isNotBlank() } ?: decoded.subject
    } catch (_: Exception) {
        null
    }
}

fun extractUserId(json: String): String {
    val idRe = """"id"\s*:\s*(\n?\d+)""".toRegex()
    val usernameRe = """"username"\s*:\s*"([^"]+)""".toRegex()
    val emailRe = """"email"\s*:\s*"([^"]+)""".toRegex()
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

// Note: Route wrappers removed to avoid intercept issues; use authenticate("auth-bearer") in routes
// and call getAppPrincipal()/derivedRolesForToken() inside handlers for authorization.

private fun ApplicationCall.derivedRolesForToken(): Set<String> {
    val authHeader = request.headers[HttpHeaders.Authorization]
    val bearer = authHeader?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
    if (!bearer.isNullOrBlank()) {
        // Use same fallbacks as token generation
        val secret = System.getenv("JWT_SECRET") ?: "dev-secret"
        val issuer = System.getenv("JWT_ISSUER") ?: "seatsync"
        try {
            val algo = Algorithm.HMAC256(secret)
            val verifier = JWT.require(algo).withIssuer(issuer).build()
            val decoded = verifier.verify(bearer)
            val roles = decoded.getClaim("roles").asList(String::class.java)?.toSet() ?: emptySet()
            if (roles.isNotEmpty()) return roles
        } catch (_: Exception) {}
    }
    val pId = principal<UserIdPrincipal>()?.name
    val admins =
            System.getenv("ADMINS")
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.toSet()
                    ?: emptySet()
    return if (pId != null && pId in admins) setOf("ADMIN") else emptySet()
}

fun ApplicationCall.getAppPrincipal(): AppPrincipal? {
    val p = principal<UserIdPrincipal>() ?: return null
    val roles = derivedRolesForToken()
    return AppPrincipal(p.name, roles)
}

// No separate roles cache; roles are derived per-call from JWT or ADMINS env.
