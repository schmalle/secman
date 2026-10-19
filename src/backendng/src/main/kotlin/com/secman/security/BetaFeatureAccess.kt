package com.secman.security

import com.secman.domain.User
import com.secman.repository.UserRepository
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton

fun Set<User.Role>.isBetaEligible(): Boolean =
    any { it == User.Role.BETA || it == User.Role.ADMIN || it == User.Role.SECCHAMPION }

@Singleton
open class BetaFeatureAccess(private val users: UserRepository) {
    /** Reload roles and mapping identity so an old JWT cannot preserve a revoked grant. */
    open fun current(authentication: Authentication): User {
        val id = authentication.attributes["userId"]?.toString()?.toLongOrNull()
            ?: throw HttpStatusException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED")
        val user = users.findById(id).orElse(null)
        if (user == null || !user.enabled || user.username != authentication.name) {
            throw HttpStatusException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED")
        }
        return user
    }

    open fun requireEligible(authentication: Authentication): User = current(authentication).also {
        if (!it.roles.isBetaEligible()) throw HttpStatusException(HttpStatus.FORBIDDEN, "BETA_REQUIRED")
    }

    fun authentication(user: User): Authentication = Authentication.build(
        user.username, user.roles.map { it.name },
        mapOf("userId" to user.id.toString(), "email" to user.email)
    )
}
