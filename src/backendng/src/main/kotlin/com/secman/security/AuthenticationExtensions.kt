package com.secman.security

import io.micronaut.security.authentication.Authentication

/**
 * Shared role-membership helpers. Use these instead of redeclaring private
 * `hasRole(...)` helpers or inlining `authentication.roles.contains("ADMIN")`
 * per class — coarse endpoint gating stays on `@Secured`, these cover the
 * fine-grained "admin sees all vs. user sees own" branching inside handlers.
 */
fun Authentication.hasRole(role: String): Boolean = roles.contains(role)

fun Authentication.hasAnyRole(vararg candidates: String): Boolean =
    candidates.any { roles.contains(it) }

fun Authentication.isAdmin(): Boolean = hasRole("ADMIN")

/**
 * The caller's user id from the `userId` JWT claim, which [SecmanTokenIssuer] writes as a string.
 * Throws [IllegalStateException] when the claim is absent and [NumberFormatException] when it is
 * not a number, the behaviour the per-controller copies this replaces had.
 */
fun Authentication.requireUserId(): Long = when (val userId = attributes["userId"]) {
    is Long -> userId
    is Int -> userId.toLong()
    is String -> userId.toLong()
    else -> throw IllegalStateException("Unable to determine user ID from authentication")
}
