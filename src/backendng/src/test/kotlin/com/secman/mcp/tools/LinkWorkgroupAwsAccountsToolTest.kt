package com.secman.mcp.tools

import com.secman.dto.WorkgroupAccountLinkInfo
import com.secman.dto.WorkgroupAccountLinkSummary
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.WorkgroupAccountLinkService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The tool is a write verb that grants workgroup members access to an account's assets,
 * so the guards matter as much as the happy path: delegation, then ADMIN, then work.
 */
class LinkWorkgroupAwsAccountsToolTest {

    private val linkService = mockk<WorkgroupAccountLinkService>(relaxed = true)
    private val tool = LinkWorkgroupAwsAccountsTool(linkService)

    private fun ctx(isAdmin: Boolean = true, hasDelegation: Boolean = true) =
        mockk<McpExecutionContext>().also {
            every { it.hasDelegation() } returns hasDelegation
            every { it.isAdmin } returns isAdmin
            every { it.delegatedUserRoles } returns if (isAdmin) setOf("ADMIN") else setOf("USER")
            every { it.delegatedUserEmail } returns "admin@corp.com"
            every { it.delegatedUserId } returns 9L
        }

    @Suppress("UNCHECKED_CAST")
    private fun content(result: McpToolResult) = (result as McpToolResult.Success).content as Map<String, Any?>

    @BeforeEach
    fun setup() {
        every { linkService.linkFromStoredMappings(any(), any()) } returns WorkgroupAccountLinkSummary(
            processed = 2, workgroupsCreated = 1, linked = 1, alreadyLinked = 1,
            links = listOf(
                WorkgroupAccountLinkInfo(
                    awsAccountId = "111111111111",
                    displayName = "DevOps-x",
                    workgroupName = "aws-DevOps-x",
                    workgroupId = 42L,
                    workgroupCreated = true,
                    linked = true
                )
            )
        )
    }

    @Test
    fun `delegation is required`() = runBlocking<Unit> {
        val result = tool.execute(emptyMap(), ctx(hasDelegation = false))

        assertThat((result as McpToolResult.Error).code).isEqualTo("DELEGATION_REQUIRED")
        verify(exactly = 0) { linkService.linkFromStoredMappings(any(), any()) }
    }

    @Test
    fun `a non-admin is refused`() = runBlocking<Unit> {
        val result = tool.execute(emptyMap(), ctx(isAdmin = false))

        assertThat((result as McpToolResult.Error).code).isEqualTo("ADMIN_REQUIRED")
        verify(exactly = 0) { linkService.linkFromStoredMappings(any(), any()) }
    }

    @Test
    fun `the delegated user is recorded as the actor`() = runBlocking<Unit> {
        tool.execute(emptyMap(), ctx())

        verify { linkService.linkFromStoredMappings(9L, false) }
    }

    @Test
    fun `dryRun is passed through`() = runBlocking<Unit> {
        tool.execute(mapOf("dryRun" to true), ctx())

        verify { linkService.linkFromStoredMappings(9L, true) }
    }

    @Test
    fun `the summary and per-account rows are reported back`() = runBlocking<Unit> {
        val result = content(tool.execute(emptyMap(), ctx()))

        assertThat(result["processed"]).isEqualTo(2)
        assertThat(result["workgroupsCreated"]).isEqualTo(1)
        assertThat(result["linked"]).isEqualTo(1)
        // Kept in its own field so an agent reading this does not report an idempotent
        // no-op as a failure.
        assertThat(result["alreadyLinked"]).isEqualTo(1)
        assertThat(result["failed"]).isEqualTo(0)

        @Suppress("UNCHECKED_CAST")
        val rows = result["links"] as List<Map<String, Any?>>
        assertThat(rows.single()["workgroupName"]).isEqualTo("aws-DevOps-x")
        assertThat(rows.single()["workgroupCreated"]).isEqualTo(true)
    }

    @Test
    fun `a service failure becomes an execution error, not a crash`() = runBlocking<Unit> {
        every { linkService.linkFromStoredMappings(any(), any()) } throws RuntimeException("db down")

        val result = tool.execute(emptyMap(), ctx())

        assertThat((result as McpToolResult.Error).code).isEqualTo("EXECUTION_ERROR")
    }
}
