package com.secman.mcp.tools

import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.RequirementService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `get_requirements` has no asset/owner scoping — like RequirementController and
 * ExportRequirementsTool, access is gated by role alone. Regression coverage for
 * the missing-guard finding: this tool used to have no role check at all, so any
 * MCP-delegated identity (including a plain USER) could read the full requirement
 * corpus that RequirementController and export_requirements both restrict to
 * ADMIN/REQ/SECCHAMPION.
 */
class GetRequirementsToolTest {

    private val service = mockk<RequirementService>(relaxed = true)
    private val tool = GetRequirementsTool(service)

    private fun ctx(isAdmin: Boolean, roles: Set<String>) =
        mockk<McpExecutionContext>().also {
            every { it.isAdmin } returns isAdmin
            every { it.delegatedUserRoles } returns roles
        }

    @Test
    fun `plain USER role is rejected`() = runBlocking<Unit> {
        val result = tool.execute(emptyMap(), ctx(isAdmin = false, roles = setOf("USER")))

        assertThat(result.isError).isTrue()
        val error = result as McpToolResult.Error
        assertThat(error.code).isEqualTo("ROLE_REQUIRED")
    }

    @Test
    fun `REQ role is accepted`() = runBlocking {
        every { service.filterRequirements(any(), any(), any(), any(), any(), any()) } returns
            (emptyList<com.secman.domain.Requirement>() to 0)

        val result = tool.execute(emptyMap(), ctx(isAdmin = false, roles = setOf("REQ")))

        assertThat(result.isError).isFalse()
    }

    @Test
    fun `SECCHAMPION role is accepted`() = runBlocking {
        every { service.filterRequirements(any(), any(), any(), any(), any(), any()) } returns
            (emptyList<com.secman.domain.Requirement>() to 0)

        val result = tool.execute(emptyMap(), ctx(isAdmin = false, roles = setOf("SECCHAMPION")))

        assertThat(result.isError).isFalse()
    }

    @Test
    fun `admin API key bypasses the role check regardless of delegated role`() = runBlocking {
        every { service.filterRequirements(any(), any(), any(), any(), any(), any()) } returns
            (emptyList<com.secman.domain.Requirement>() to 0)

        val result = tool.execute(emptyMap(), ctx(isAdmin = true, roles = setOf("USER")))

        assertThat(result.isError).isFalse()
    }
}
