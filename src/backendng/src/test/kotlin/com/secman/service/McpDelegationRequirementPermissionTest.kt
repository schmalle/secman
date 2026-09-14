package com.secman.service

import com.secman.domain.McpPermission
import com.secman.domain.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class McpDelegationRequirementPermissionTest {
    private val service = McpDelegationService()

    @Test
    fun `REQ and SECCHAMPION can manage the full MCP requirement lifecycle`() {
        listOf(User.Role.REQ, User.Role.SECCHAMPION).forEach { role ->
            val user = User(
                username = role.name.lowercase(),
                email = "${role.name.lowercase()}@example.test",
                passwordHash = role.name,
                roles = mutableSetOf(role)
            )

            assertThat(service.getUserImpliedPermissions(user)).contains(
                McpPermission.REQUIREMENTS_READ,
                McpPermission.REQUIREMENTS_WRITE,
                McpPermission.REQUIREMENTS_DELETE
            )
        }
    }
}
