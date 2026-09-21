package com.secman.security

/** Grant administration is separate from visibility and ordinary asset editing. */
object GrantAuthority {
    fun canManage(roles: Collection<String>): Boolean =
        "ADMIN" in roles || "SECCHAMPION" in roles
}
