package com.secman.service

import com.secman.domain.McpApiKey
import com.secman.domain.McpPermission
import com.secman.domain.User
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit tests for McpDelegationTicketPermission; no database, collaborators are mocked. */
class McpDelegationTicketPermissionTest {
    private val service = McpDelegationService()
    private val fullKey = mockk<McpApiKey>().also { every { it.getPermissionSet() } returns McpPermission.entries.toSet() }

    private fun user(vararg roles: User.Role) =
        User(username = "u", email = "u@example.test", passwordHash = "x", roles = roles.toMutableSet())

    @Test
    fun `CYBERDEF implies ticket submission and nothing from the inventory`() {
        assertThat(service.getUserImpliedPermissions(user(User.Role.CYBERDEF))).containsExactly(McpPermission.TICKETS_SUBMIT)
        assertThat(service.computeEffectivePermissions(user(User.Role.CYBERDEF), fullKey)).containsExactlyInAnyOrder(
            McpPermission.TICKETS_SUBMIT, McpPermission.TICKETS_RESPOND, McpPermission.PERSONAL_INVENTORY_READ)
    }

    @Test
    fun `every delegated user may answer tickets but only CYBERDEF and ADMIN may submit`() {
        for (role in User.Role.entries) {
            val effective = service.computeEffectivePermissions(user(role), fullKey)
            assertThat(effective).describedAs(role.name).contains(McpPermission.TICKETS_RESPOND)
            assertThat(McpPermission.TICKETS_SUBMIT in effective).describedAs(role.name)
                .isEqualTo(role == User.Role.CYBERDEF || role == User.Role.ADMIN)
        }
    }

    @Test
    fun `the API key still caps ticket permissions`() {
        val readOnlyKey = mockk<McpApiKey>().also { every { it.getPermissionSet() } returns setOf(McpPermission.ASSETS_READ) }
        assertThat(service.computeEffectivePermissions(user(User.Role.CYBERDEF), readOnlyKey)).isEmpty()
    }
}
