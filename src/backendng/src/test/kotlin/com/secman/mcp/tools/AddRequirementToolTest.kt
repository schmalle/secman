package com.secman.mcp.tools

import com.secman.domain.Requirement
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.RequirementService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `add_requirement` has no asset/owner scoping — like RequirementController and
 * ExportRequirementsTool, access must be gated by role alone. Regression coverage for
 * the missing-guard finding: this tool used to have no role check at all, so any
 * MCP-delegated identity holding only the coarse REQUIREMENTS_WRITE permission
 * (including a plain USER) could create requirements, bypassing RequirementController's
 * `@Secured("ADMIN", "REQ", "SECCHAMPION")` boundary.
 */
class AddRequirementToolTest {

    private val service = mockk<RequirementService>(relaxed = true)
    private val tool = AddRequirementTool(service)

    private fun ctx(isAdmin: Boolean, roles: Set<String>) =
        mockk<McpExecutionContext>().also {
            every { it.isAdmin } returns isAdmin
            every { it.delegatedUserRoles } returns roles
        }

    private fun args(shortreq: String = "Some requirement") = mapOf("shortreq" to shortreq)

    @Test
    fun `plain USER role is rejected`() = runBlocking<Unit> {
        val result = tool.execute(args(), ctx(isAdmin = false, roles = setOf("USER")))

        assertThat(result.isError).isTrue()
        val error = result as McpToolResult.Error
        assertThat(error.code).isEqualTo("ROLE_REQUIRED")
    }

    @Test
    fun `REQ role is accepted`() = runBlocking {
        every { service.createRequirement(any()) } returns Requirement(shortreq = "Some requirement").apply { id = 1L }

        val result = tool.execute(args(), ctx(isAdmin = false, roles = setOf("REQ")))

        assertThat(result.isError).isFalse()
    }

    @Test
    fun `SECCHAMPION role is accepted`() = runBlocking {
        every { service.createRequirement(any()) } returns Requirement(shortreq = "Some requirement").apply { id = 1L }

        val result = tool.execute(args(), ctx(isAdmin = false, roles = setOf("SECCHAMPION")))

        assertThat(result.isError).isFalse()
    }

    @Test
    fun `admin API key bypasses the role check regardless of delegated role`() = runBlocking {
        every { service.createRequirement(any()) } returns Requirement(shortreq = "Some requirement").apply { id = 1L }

        val result = tool.execute(args(), ctx(isAdmin = true, roles = setOf("USER")))

        assertThat(result.isError).isFalse()
    }
}
