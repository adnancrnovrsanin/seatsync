package com.adnan.seatsync.app.dto

import kotlinx.serialization.Serializable

@Serializable
data class LoginBody(val username: String, val password: String, val expiresInMins: Int? = 60)

// Mirrors https://dummyjson.com/user/login successful response
@Serializable
data class LoginResponse(
        val id: Int,
        val username: String,
        val email: String? = null,
        val firstName: String? = null,
        val lastName: String? = null,
        val gender: String? = null,
        val image: String? = null,
        val accessToken: String? = null,
        val refreshToken: String? = null
)

@Serializable
data class LoginUser(
        val id: Int,
        val username: String,
        val email: String? = null,
        val firstName: String? = null,
        val lastName: String? = null,
        val image: String? = null
)

@Serializable
data class LoginResult(
        val user: LoginUser,
        val token: String,
        val providerAccessToken: String? = null,
        val providerRefreshToken: String? = null
)
