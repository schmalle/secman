package com.secman.crowdstrike.model

import java.time.Instant

/**
 * OAuth2 access token from CrowdStrike API
 */
data class AuthToken(
    val accessToken: String,
    val expiresAt: Instant,
    val tokenType: String = "bearer"
) {
    init {
        require(accessToken.isNotBlank()) { "Access token cannot be blank" }
        require(expiresAt.isAfter(Instant.now())) { "Token expiration must be in the future" }
    }

    fun isExpired(): Boolean = Instant.now().isAfter(expiresAt)

    fun isExpiringSoon(bufferSeconds: Long = 60): Boolean =
        Instant.now().plusSeconds(bufferSeconds).isAfter(expiresAt)
}
