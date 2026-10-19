package com.secman.mcp.tools

import com.secman.domain.McpPermission
import com.secman.dto.*
import com.secman.dto.mcp.McpExecutionContext
import com.secman.mcp.McpToolPermissions
import com.secman.mcp.McpToolRegistry
import com.secman.service.UserAccessOverviewService
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GetUserAccessOverviewToolTest {
    private val service = mockk<UserAccessOverviewService>()
    private val tool = GetUserAccessOverviewTool(service)
    private fun context(roles: Set<String> = setOf("ADMIN")) = McpExecutionContext.forDelegatedUser(
        1, "test", 2, "admin@example.com", "admin", roles, setOf(McpPermission.USER_ACTIVITY), true, null, null
    )

    @Test
    fun `requires delegation and the delegated user's own ADMIN role before lookup`() = runBlocking<Unit> {
        val contexts = listOf(context().copy(delegatedUserId = null), context(setOf("USER")), context(setOf("SECCHAMPION")))
        for (ctx in contexts) {
            assertThat(tool.execute(mapOf("email" to "admin@example.com"), ctx).isError).isTrue()
        }
        verify(exactly = 0) { service.overview(any(), any(), any()) }
    }

    @Test
    fun `rejects missing email and fractional overflowing or wrongly typed pagination`() = runBlocking<Unit> {
        val invalid = listOf(emptyMap(), mapOf("email" to 1), mapOf("email" to "a@example.com", "page" to 0.5),
            mapOf("email" to "a@example.com", "page" to Long.MAX_VALUE), mapOf("email" to "a@example.com", "size" to "10"))
        for (args in invalid) {
            assertThat((tool.execute(args, context()) as McpToolResult.Error).code).isEqualTo("INVALID_ARGUMENT")
        }
        verify(exactly = 0) { service.overview(any(), any(), any()) }
    }

    @Test
    fun `returns the shared report with defaults and requested pagination`() = runBlocking<Unit> {
        val report = UserAccessOverview(AccessOverviewUser(3, "target", "target@example.com", false, listOf("USER")),
            "2026-10-19T12:00:00Z", false, false, 0, emptyList(), emptyList(), emptyList(), 0, 100, 0)
        every { service.overview("target@example.com", 0, 100) } returns report
        assertThat((tool.execute(mapOf("email" to "target@example.com"), context()) as McpToolResult.Success).content).isEqualTo(report)
        every { service.overview("target@example.com", 2, 10) } returns report.copy(page = 2, size = 10)
        assertThat((tool.execute(mapOf("email" to "target@example.com", "page" to 2, "size" to 10), context()) as McpToolResult.Success).content)
            .isEqualTo(report.copy(page = 2, size = 10))
    }

    @Test
    fun `maps validation and not-found errors without leaking internal failures`() = runBlocking<Unit> {
        for ((status, code) in listOf(HttpStatus.BAD_REQUEST to "INVALID_ARGUMENT", HttpStatus.NOT_FOUND to "NOT_FOUND", HttpStatus.INTERNAL_SERVER_ERROR to "INTERNAL_ERROR")) {
            every { service.overview(any(), any(), any()) } throws HttpStatusException(status, "private database detail")
            val result = tool.execute(mapOf("email" to "target@example.com"), context()) as McpToolResult.Error
            assertThat(result.code).isEqualTo(code)
            assertThat(result.message).doesNotContain("private database detail")
        }
        every { service.overview(any(), any(), any()) } throws IllegalStateException("private SQL detail")
        assertThat((tool.execute(mapOf("email" to "target@example.com"), context()) as McpToolResult.Error).message).doesNotContain("private SQL detail")
    }

    @Test
    fun `discovery and invocation both require USER_ACTIVITY`() {
        val registry = McpToolRegistry(listOf(tool))
        assertThat(registry.getAuthorizedTools(setOf(McpPermission.USER_ACTIVITY))).containsKey(tool.name)
        assertThat(registry.getAuthorizedTools(setOf(McpPermission.ASSETS_READ))).doesNotContainKey(tool.name)
        for (table in listOf(McpToolPermissions.LISTING, McpToolPermissions.CALLING)) {
            assertThat(table[tool.name]).isEqualTo(setOf(McpPermission.USER_ACTIVITY))
            assertThat(McpToolPermissions.allows(table, tool.name, emptySet())).isFalse()
        }
    }
}
