package com.secman.mcp.tools

import com.secman.domain.Requirement
import com.secman.domain.UseCase
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.InputValidationService
import com.secman.service.McpRequirementManagementService
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RequirementManagementToolsTest {
    private val service = mockk<McpRequirementManagementService>(relaxed = true)

    private fun ctx(roles: Set<String> = setOf("REQ")) = mockk<McpExecutionContext>().also {
        every { it.isAdmin } returns false
        every { it.delegatedUserRoles } returns roles
        every { it.delegatedUserId } returns 12L
        every { it.hasDelegation() } returns true
    }

    @Test
    fun `update can clear optional content and replace relationships`() = runBlocking {
        val saved = Requirement(id = 5L, internalId = "REQ-005", shortreq = "Updated")
        every { service.updateRequirement(eq(5L), any(), 12L) } returns saved
        val tool = UpdateRequirementTool(service, InputValidationService())

        val result = tool.execute(
            mapOf(
                "requirementId" to 5,
                "shortreq" to "Updated",
                "clearFields" to listOf("details", "chapter"),
                "useCaseIds" to emptyList<Int>()
            ),
            ctx()
        )

        assertThat(result.isError).isFalse()
        verify {
            service.updateRequirement(
                5L,
                match { it.shortreq == "Updated" && it.clearFields == setOf("details", "chapter") && it.useCaseIds == emptyList<Long>() },
                12L
            )
        }
    }

    @Test
    fun `update rejects supplying and clearing the same field`() = runBlocking<Unit> {
        val tool = UpdateRequirementTool(service, InputValidationService())
        val result = tool.execute(
            mapOf("requirementId" to 5, "details" to "new", "clearFields" to listOf("details")),
            ctx()
        )
        assertThat((result as McpToolResult.Error).code).isEqualTo("VALIDATION_ERROR")
    }

    @Test
    fun `assignment tool accepts an empty replacement set`() = runBlocking {
        every { service.replaceRequirementUseCases(5L, emptyList(), 12L) } returns
            Requirement(id = 5L, internalId = "REQ-005", shortreq = "Requirement")
        val result = SetRequirementUseCasesTool(service).execute(
            mapOf("requirementId" to 5, "useCaseIds" to emptyList<Int>()),
            ctx()
        )
        assertThat(result.isError).isFalse()
        verify { service.replaceRequirementUseCases(5L, emptyList(), 12L) }
    }

    @Test
    fun `requirement delete requires confirmation`() = runBlocking<Unit> {
        val result = DeleteRequirementTool(service).execute(
            mapOf("requirementId" to 5, "confirm" to false),
            ctx()
        )
        assertThat((result as McpToolResult.Error).code).isEqualTo("CONFIRMATION_REQUIRED")
        verify(exactly = 0) { service.deleteRequirement(any(), any()) }
    }

    @Test
    fun `use case listing is bounded`() = runBlocking {
        val useCase = UseCase(id = 3L, name = "Cloud")
        every { service.listUseCases("cloud", 0, 100) } returns Page.of(listOf(useCase), Pageable.from(0, 100), 1)
        val result = ListUseCasesTool(service).execute(
            mapOf("search" to "cloud", "pageSize" to 100),
            ctx()
        )
        assertThat(result.isError).isFalse()
    }

    @Test
    fun `use case listing rejects an excessive page`() = runBlocking {
        val result = ListUseCasesTool(service).execute(
            mapOf("page" to ListUseCasesTool.MAX_PAGE + 1),
            ctx()
        )

        assertThat((result as McpToolResult.Error).code).isEqualTo("VALIDATION_ERROR")
        verify(exactly = 0) { service.listUseCases(any(), any(), any()) }
    }

    @Test
    fun `use case delete uses the dedicated delete path`() = runBlocking {
        val result = DeleteUseCaseTool(service).execute(
            mapOf("useCaseId" to 3, "confirm" to true),
            ctx()
        )
        assertThat(result.isError).isFalse()
        verify { service.deleteUseCase(3L, 12L) }
    }
}
