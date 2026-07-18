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
