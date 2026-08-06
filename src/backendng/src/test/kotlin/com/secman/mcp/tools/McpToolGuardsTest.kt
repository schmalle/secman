package com.secman.mcp.tools

import com.secman.dto.mcp.McpExecutionContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class McpToolGuardsTest {

    private fun context(
        delegatedUserId: Long? = null,
        delegatedUserRoles: Set<String>? = null,
        isAdmin: Boolean = false
    ) = McpExecutionContext(
        apiKeyId = 1L,
        apiKeyName = "test-key",
        delegatedUserId = delegatedUserId,
        delegatedUserEmail = delegatedUserId?.let { "user@example.com" },
        delegatedUsername = delegatedUserId?.let { "user" },
        delegatedUserRoles = delegatedUserRoles,
        effectivePermissions = emptySet(),
        isAdmin = isAdmin,
        accessibleAssetIds = null,
        accessibleWorkgroupIds = null
    )

    @Test
    fun `requireDelegation returns the exact legacy error without delegation`() {
        val error = requireDelegation(context(delegatedUserId = null))

        assertThat(error).isNotNull
        assertThat(error!!.code).isEqualTo("DELEGATION_REQUIRED")
        assertThat(error.message).isEqualTo("User Delegation must be enabled to use this tool")
    }

    @Test
    fun `requireDelegation passes when a delegated user is present`() {
        assertThat(requireDelegation(context(delegatedUserId = 42L))).isNull()
    }

    @Test
    fun `requireAnyRole passes for admin keys regardless of roles`() {
        assertThat(requireAnyRole(context(isAdmin = true), "SECCHAMPION")).isNull()
    }

    @Test
    fun `requireAnyRole passes when the delegated user has one of the roles`() {
        val ctx = context(delegatedUserId = 42L, delegatedUserRoles = setOf("SECCHAMPION"))
        assertThat(requireAnyRole(ctx, "ADMIN", "SECCHAMPION")).isNull()
    }

    @Test
    fun `requireAnyRole fails with FORBIDDEN and the given message otherwise`() {
        val ctx = context(delegatedUserId = 42L, delegatedUserRoles = setOf("USER"))
        val error = requireAnyRole(ctx, "ADMIN", "VULN", message = "Requires ADMIN or VULN role")

        assertThat(error).isNotNull
        assertThat(error!!.code).isEqualTo("FORBIDDEN")
        assertThat(error.message).isEqualTo("Requires ADMIN or VULN role")
    }

    @Test
    fun `requireAnyRole fails when roles are null and key is not admin`() {
        assertThat(requireAnyRole(context(delegatedUserId = 42L), "ADMIN")).isNotNull
    }
}
