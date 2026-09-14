package com.secman.service

import com.secman.domain.McpPermission
import com.secman.domain.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class McpDelegationAssessmentPermissionTest {
    @Test
    fun `security champion can coordinate assessment creation and reminders`() {
        val username = "champion"
        val champion = User(
            username = username,
            email = "champion@example.com",
            passwordHash = username,
            roles = mutableSetOf(User.Role.SECCHAMPION)
        )

        assertThat(McpDelegationService().getUserImpliedPermissions(champion))
            .contains(
                McpPermission.ASSESSMENTS_READ,
                McpPermission.ASSESSMENTS_WRITE,
                McpPermission.NOTIFICATIONS_SEND
            )
            .doesNotContain(McpPermission.ASSESSMENTS_EXECUTE)
    }
}
