package com.secman.security

import com.secman.domain.User
import io.micronaut.security.token.generator.TokenGenerator
import jakarta.inject.Singleton
import java.util.Optional

/**
 * The one place a session JWT's claims are assembled. Password login, token refresh, passkey
 * login and OAuth callback all issue through here, so a claim change cannot reach one login path
 * and miss the others. Callers still decide where the token goes (only ever the HttpOnly
 * `secman_auth` cookie) and how a failure is reported.
 */
@Singleton
class SecmanTokenIssuer(private val tokenGenerator: TokenGenerator) {

    /** Empty when the generator refuses, mirroring [TokenGenerator.generateToken]. */
    fun issue(user: User): Optional<String> = tokenGenerator.generateToken(claimsFor(user))

    internal fun claimsFor(user: User): Map<String, Any> = mapOf(
        "sub" to user.username,
        "username" to user.username,
        "email" to user.email,
        "roles" to user.roles.map { it.name },
        "iss" to ISSUER,
        "userId" to user.id.toString()
    )

    companion object {
        const val ISSUER = "secman-backend-ng"
    }
}
