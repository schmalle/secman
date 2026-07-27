package com.secman.mcp.tools

import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.AccountFindingAgeService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class GetTopAccountsByFindingAgeToolTest {

    private val service = mockk<AccountFindingAgeService>()
    private val tool = GetTopAccountsByFindingAgeTool(service)

    private fun ctx(isAdmin: Boolean = true, hasDelegation: Boolean = true) =
        mockk<McpExecutionContext>().also {
            every { it.hasDelegation() } returns hasDelegation
            every { it.isAdmin } returns isAdmin
            every { it.delegatedUserRoles } returns if (isAdmin) setOf("ADMIN") else setOf("USER")
        }

    private fun row(accountId: String, name: String) = AccountFindingAgeService.AccountFindingAge(
        awsAccountId = accountId,
        accountName = name,
        oldestFindingFirstSeenAt = LocalDateTime.now().minusDays(120),
        oldestFindingDaysOpen = 120,
        oldestFindingCve = "CVE-2023-9999",
        oldestFindingSeverity = "Critical",
        oldestFindingAssetName = "web-01",
        oldestFindingAssetInstanceId = "i-0abc",
        openFindingCount = 42,
        affectedAssetCount = 7
    )

    @Test
    fun `returns accounts including the account name for an admin`() = runBlocking<Unit> {
        every { service.getTopAccountsByOldestFinding(10) } returns listOf(row("111111111111", "Platform Prod"))

        val result = tool.execute(emptyMap(), ctx())

        assertThat(result.isError).isFalse()
        @Suppress("UNCHECKED_CAST")
        val accounts = ((result as McpToolResult.Success).content as Map<String, Any>)["accounts"] as List<Map<String, Any?>>
        assertThat(accounts).hasSize(1)
        assertThat(accounts[0]["accountName"]).isEqualTo("Platform Prod")
        assertThat(accounts[0]["awsAccountId"]).isEqualTo("111111111111")
    }

    @Test
    fun `rejects a non-admin caller`() = runBlocking<Unit> {
        val result = tool.execute(emptyMap(), ctx(isAdmin = false))

        assertThat(result.isError).isTrue()
        assertThat((result as McpToolResult.Error).code).isEqualTo("ADMIN_REQUIRED")
    }

    @Test
    fun `rejects an out-of-range limit`() = runBlocking<Unit> {
        every { service.getTopAccountsByOldestFinding(999) } throws IllegalArgumentException("limit must be between 1 and 50")

        val result = tool.execute(mapOf("limit" to 999), ctx())

        assertThat(result.isError).isTrue()
        assertThat((result as McpToolResult.Error).code).isEqualTo("INVALID_ARGUMENT")
    }

    @Test
    fun `rejects a call without delegation`() = runBlocking<Unit> {
        val result = tool.execute(emptyMap(), ctx(hasDelegation = false))

        assertThat(result.isError).isTrue()
        assertThat((result as McpToolResult.Error).code).isEqualTo("DELEGATION_REQUIRED")
    }
}
