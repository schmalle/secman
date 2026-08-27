package com.secman.mcp.tools

import com.secman.dto.AccountRiskAssessmentInfo
import com.secman.dto.BulkUserMappingRequest
import com.secman.dto.BulkUserMappingResponse
import com.secman.dto.NewAccountImportInfo
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.UserMappingBulkImportService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ImportUserMappingsToolTest {

    private val bulkImportService = mockk<UserMappingBulkImportService>(relaxed = true)
    private val tool = ImportUserMappingsTool(bulkImportService)

    private fun ctx(isAdmin: Boolean = true, hasDelegation: Boolean = true) =
        mockk<McpExecutionContext>().also {
            every { it.hasDelegation() } returns hasDelegation
            every { it.isAdmin } returns isAdmin
            every { it.delegatedUserRoles } returns if (isAdmin) setOf("ADMIN") else setOf("USER")
            every { it.delegatedUserEmail } returns "admin@corp.com"
            every { it.delegatedUserId } returns 9L
        }

    private fun mapping(email: String = "alice@corp.com", account: String? = "111111111111") =
        buildMap<String, Any?> {
            put("email", email)
            account?.let { put("awsAccountId", it) }
        }

    @BeforeEach
    fun setup() {
        every { bulkImportService.validate(any()) } returns null
        every { bulkImportService.execute(any(), any(), any()) } returns BulkUserMappingResponse(
            totalProcessed = 1, created = 1, createdPending = 0, skipped = 0,
            errors = emptyList(),
            newAccounts = listOf(NewAccountImportInfo("111111111111", listOf("alice@corp.com"))),
            riskAssessments = listOf(
                AccountRiskAssessmentInfo(
                    awsAccountId = "111111111111", ownerEmail = "alice@corp.com",
                    riskAssessmentId = 1000L, assessor = "champ@corp.com", endDate = "2026-08-09",
                    useCase = "Cloud Onboarding", releaseVersion = "2.3.0", requirementCount = 12
                )
            )
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun content(result: McpToolResult) = (result as McpToolResult.Success).content as Map<String, Any?>

    // --- guards ---------------------------------------------------------------

    @Test
    fun `delegation is required`() = runBlocking<Unit> {
        val result = tool.execute(mapOf("mappings" to listOf(mapping())), ctx(hasDelegation = false))

        assertThat((result as McpToolResult.Error).code).isEqualTo("DELEGATION_REQUIRED")
        verify(exactly = 0) { bulkImportService.execute(any(), any(), any()) }
    }

    @Test
    fun `non-admin is rejected`() = runBlocking<Unit> {
        val result = tool.execute(mapOf("mappings" to listOf(mapping())), ctx(isAdmin = false))

        assertThat((result as McpToolResult.Error).code).isEqualTo("ADMIN_REQUIRED")
        verify(exactly = 0) { bulkImportService.execute(any(), any(), any()) }
    }

    @Test
    fun `empty or missing mappings are rejected`() = runBlocking<Unit> {
        assertThat((tool.execute(emptyMap(), ctx()) as McpToolResult.Error).code).isEqualTo("VALIDATION_ERROR")
        assertThat(
            (tool.execute(mapOf("mappings" to emptyList<Any>()), ctx()) as McpToolResult.Error).code
        ).isEqualTo("VALIDATION_ERROR")
    }

    @Test
    fun `more than 1000 mappings are rejected`() = runBlocking<Unit> {
        val result = tool.execute(mapOf("mappings" to (1..1001).map { mapping("u$it@corp.com") }), ctx())

        assertThat((result as McpToolResult.Error).message).contains("Maximum 1000")
    }

    @Test
    fun `service validation failure is surfaced as VALIDATION_ERROR`() = runBlocking<Unit> {
        every { bulkImportService.validate(any()) } returns
            "No ACTIVE release exists to base the risk assessment on"

        val result = tool.execute(
            mapOf(
                "mappings" to listOf(mapping()),
                "startRiskAssessment" to true,
                "riskAssessmentUseCase" to "Cloud Onboarding"
            ),
            ctx()
        )

        assertThat((result as McpToolResult.Error).code).isEqualTo("VALIDATION_ERROR")
        assertThat(result.message).contains("No ACTIVE release")
        verify(exactly = 0) { bulkImportService.execute(any(), any(), any()) }
    }

    // --- delegation to the shared service -------------------------------------

    @Test
    fun `risk assessment options are passed through to the shared import service`() = runBlocking<Unit> {
        val request = slot<BulkUserMappingRequest>()
        every { bulkImportService.execute(capture(request), any(), any()) } returns BulkUserMappingResponse(
            totalProcessed = 1, created = 1, createdPending = 0, skipped = 0, errors = emptyList()
        )

        tool.execute(
            mapOf(
                "mappings" to listOf(mapping()),
                "startRiskAssessment" to true,
                "riskAssessmentUseCase" to "  Cloud Onboarding  ",
                "riskAssessmentDeadlineDays" to 14
            ),
            ctx()
        )

        assertThat(request.captured.startRiskAssessment).isTrue()
        assertThat(request.captured.riskAssessmentUseCase).isEqualTo("Cloud Onboarding")
        assertThat(request.captured.riskAssessmentDeadlineDays).isEqualTo(14)
        assertThat(request.captured.mappings.single().email).isEqualTo("alice@corp.com")
        assertThat(request.captured.mappings.single().awsAccountId).isEqualTo("111111111111")
    }

    @Test
    fun `the delegated user becomes the requestor of the assessments`() = runBlocking<Unit> {
        tool.execute(mapOf("mappings" to listOf(mapping())), ctx())

        verify { bulkImportService.execute(any(), 9L, any()) }
    }

    @Test
    fun `blank optional fields become null rather than empty strings`() = runBlocking<Unit> {
        val request = slot<BulkUserMappingRequest>()
        every { bulkImportService.execute(capture(request), any(), any()) } returns BulkUserMappingResponse(
            totalProcessed = 1, created = 0, createdPending = 0, skipped = 0, errors = emptyList()
        )

        tool.execute(
            mapOf("mappings" to listOf(mapOf("email" to "alice@corp.com", "domain" to "   "))),
            ctx()
        )

        assertThat(request.captured.mappings.single().domain).isNull()
        assertThat(request.captured.mappings.single().awsAccountId).isNull()
    }

    // --- result shape ---------------------------------------------------------

    @Test
    fun `new accounts and started assessments are reported back`() = runBlocking<Unit> {
        val result = content(tool.execute(mapOf("mappings" to listOf(mapping())), ctx()))

        assertThat(result["created"]).isEqualTo(1)
        assertThat(result["dryRun"]).isEqualTo(false)

        @Suppress("UNCHECKED_CAST")
        val newAccounts = result["newAccounts"] as List<Map<String, Any?>>
        assertThat(newAccounts.single()["awsAccountId"]).isEqualTo("111111111111")

        @Suppress("UNCHECKED_CAST")
        val assessments = result["riskAssessments"] as List<Map<String, Any?>>
        assertThat(assessments.single()["riskAssessmentId"]).isEqualTo(1000L)
        // The version of the security requirements the assessment is measured against.
        assertThat(assessments.single()["releaseVersion"]).isEqualTo("2.3.0")
        assertThat(assessments.single()["requirementCount"]).isEqualTo(12)
        assertThat(assessments.single()["useCase"]).isEqualTo("Cloud Onboarding")
    }

    @Test
    fun `unexpected failures become EXECUTION_ERROR`() = runBlocking<Unit> {
        every { bulkImportService.execute(any(), any(), any()) } throws RuntimeException("db down")

        val result = tool.execute(mapOf("mappings" to listOf(mapping())), ctx())

        assertThat((result as McpToolResult.Error).code).isEqualTo("EXECUTION_ERROR")
        assertThat(result.message).contains("db down")
    }

    // --- display name / workgroup linking -------------------------------------

    @Test
    fun `displayName is passed through to the shared import service`() = runBlocking<Unit> {
        val request = slot<BulkUserMappingRequest>()
        every { bulkImportService.execute(capture(request), any(), any()) } returns BulkUserMappingResponse(
            totalProcessed = 1, created = 1, createdPending = 0, skipped = 0, errors = emptyList()
        )

        tool.execute(
            mapOf(
                "mappings" to listOf(
                    mapOf(
                        "email" to "alice@corp.com",
                        "awsAccountId" to "111111111111",
                        "displayName" to "  DevOps-x  "
                    )
                )
            ),
            ctx()
        )

        assertThat(request.captured.mappings.single().displayName).isEqualTo("DevOps-x")
    }

    @Test
    fun `a blank displayName becomes null so nothing is linked`() = runBlocking<Unit> {
        val request = slot<BulkUserMappingRequest>()
        every { bulkImportService.execute(capture(request), any(), any()) } returns BulkUserMappingResponse(
            totalProcessed = 1, created = 1, createdPending = 0, skipped = 0, errors = emptyList()
        )

        tool.execute(
            mapOf(
                "mappings" to listOf(
                    mapOf("email" to "alice@corp.com", "awsAccountId" to "111111111111", "displayName" to "   ")
                )
            ),
            ctx()
        )

        assertThat(request.captured.mappings.single().displayName).isNull()
    }

    @Test
    fun `workgroup linking results are reported back`() = runBlocking<Unit> {
        every { bulkImportService.execute(any(), any(), any()) } returns BulkUserMappingResponse(
            totalProcessed = 1, created = 1, createdPending = 0, skipped = 0, errors = emptyList(),
            workgroupLinks = com.secman.dto.WorkgroupAccountLinkSummary(
                processed = 1, workgroupsCreated = 1, linked = 1,
                links = listOf(
                    com.secman.dto.WorkgroupAccountLinkInfo(
                        awsAccountId = "111111111111",
                        displayName = "DevOps-x",
                        workgroupName = "aws-DevOps-x",
                        workgroupId = 42L,
                        workgroupCreated = true,
                        linked = true
                    )
                )
            )
        )

        val result = content(tool.execute(mapOf("mappings" to listOf(mapping())), ctx()))

        @Suppress("UNCHECKED_CAST")
        val links = result["workgroupLinks"] as Map<String, Any?>
        assertThat(links["workgroupsCreated"]).isEqualTo(1)
        assertThat(links["linked"]).isEqualTo(1)

        @Suppress("UNCHECKED_CAST")
        val rows = links["links"] as List<Map<String, Any?>>
        assertThat(rows.single()["workgroupName"]).isEqualTo("aws-DevOps-x")
    }

    @Test
    fun `an import without display names reports no workgroupLinks at all`() = runBlocking<Unit> {
        every { bulkImportService.execute(any(), any(), any()) } returns BulkUserMappingResponse(
            totalProcessed = 1, created = 1, createdPending = 0, skipped = 0, errors = emptyList()
        )

        val result = content(tool.execute(mapOf("mappings" to listOf(mapping())), ctx()))

        assertThat(result["workgroupLinks"]).isNull()
    }
}
