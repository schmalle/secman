package com.secman.mcp.tools

import com.secman.domain.Requirement
import com.secman.domain.UseCase
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.InputValidationService
import com.secman.service.McpRequirementManagementService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AddRequirementToolTest {
    private val service = mockk<McpRequirementManagementService>()
    private val tool = AddRequirementTool(service, InputValidationService())

    private fun ctx(isAdmin: Boolean, roles: Set<String>, delegatedUserId: Long? = 7L) =
        mockk<McpExecutionContext>().also {
            every { it.isAdmin } returns isAdmin
            every { it.delegatedUserRoles } returns roles
            every { it.delegatedUserId } returns delegatedUserId
            every { it.hasDelegation() } returns (delegatedUserId != null)
        }

    @Test
    fun `plain USER role is rejected`() = runBlocking<Unit> {
        val result = tool.execute(mapOf("shortreq" to "Some requirement"), ctx(false, setOf("USER")))
        assertThat((result as McpToolResult.Error).code).isEqualTo("ROLE_REQUIRED")
    }

    @Test
    fun `delegation is required`() = runBlocking<Unit> {
        val result = tool.execute(mapOf("shortreq" to "Some requirement"), ctx(true, setOf("ADMIN"), null))
        assertThat((result as McpToolResult.Error).code).isEqualTo("DELEGATION_REQUIRED")
    }

    @Test
    fun `creates every field and relationship supplied`() = runBlocking {
        val saved = Requirement(
            id = 1L,
            internalId = "REQ-001",
            shortreq = "Encrypt data",
            details = "At rest",
            language = "en",
            usecases = mutableSetOf(UseCase(id = 9L, name = "Cloud"))
        )
        every { service.createRequirement(any(), listOf(9L), listOf(4L), 7L) } returns saved

        val result = tool.execute(
            mapOf(
                "shortreq" to "Encrypt data",
                "details" to "At rest",
                "language" to "en",
                "useCaseIds" to listOf(9),
                "normIds" to listOf(4)
            ),
            ctx(false, setOf("REQ"))
        )

        assertThat(result.isError).isFalse()
        verify {
            service.createRequirement(
                match { it.shortreq == "Encrypt data" && it.details == "At rest" && it.language == "en" },
                listOf(9L),
                listOf(4L),
                7L
            )
        }
    }

    @Test
    fun `rejects duplicate relationship ids`() = runBlocking<Unit> {
        val result = tool.execute(
            mapOf("shortreq" to "Some requirement", "useCaseIds" to listOf(9, 9)),
            ctx(false, setOf("REQ"))
        )
        assertThat((result as McpToolResult.Error).code).isEqualTo("VALIDATION_ERROR")
        verify(exactly = 0) { service.createRequirement(any(), any(), any(), any()) }
    }
}
