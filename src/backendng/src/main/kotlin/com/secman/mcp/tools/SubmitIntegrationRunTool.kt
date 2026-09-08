package com.secman.mcp.tools

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.domain.McpOperation
import com.secman.domain.McpPermission
import com.secman.dto.IntegrationRunRequest
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.UserRepository
import com.secman.service.IntegrationScanService
import com.secman.service.IntegrationHealthNotifier
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton

@Singleton
class SubmitIntegrationRunTool(
    private val service: IntegrationScanService,
    private val mapper: ObjectMapper,
    private val users: UserRepository,
    private val health: IntegrationHealthNotifier
) : McpTool {
    override val name = "submit_integration_run"
    override val description = "Submit one atomic terminal snapshot for a registered subject assigned to the delegated service user."
    override val operation = McpOperation.WRITE
    override val inputSchema: Map<String, Any> = IntegrationToolSchema.run

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        if (!context.hasPermission(McpPermission.INTEGRATIONS_WRITE)) return McpToolResult.error("FORBIDDEN", "Integration write permission required")
        val user = users.findById(context.delegatedUserId!!).orElse(null)
            ?: return McpToolResult.error("FORBIDDEN", "Delegated user unavailable")
        val body = try { mapper.convertValue(arguments, IntegrationRunRequest::class.java) }
        catch (_: IllegalArgumentException) { return McpToolResult.error("VALIDATION_ERROR", "Invalid integration run") }
        return try {
            val auth = Authentication.build(user.username, user.roles.map { it.name },
                mapOf("userId" to user.id!!, "email" to user.email))
            McpToolResult.success(service.submit(body, auth).also(health::completed))
        } catch (e: HttpStatusException) {
            McpToolResult.error(e.status.name, e.message ?: "Integration submission rejected")
        }
    }
}

internal object IntegrationToolSchema {
    private fun text() = mapOf("type" to listOf("string", "null"))
    val run: Map<String, Any> = mapOf(
        "type" to "object",
        "required" to listOf("scannerId", "subjectId", "runKey", "status", "completeCoverage", "startedAt", "completedAt"),
        "properties" to mapOf(
            "scannerId" to mapOf("type" to "integer"), "subjectId" to mapOf("type" to "integer"),
            "runKey" to mapOf("type" to "string"), "status" to mapOf("type" to "string", "enum" to listOf("SUCCESS", "PARTIAL", "FAILED", "SKIPPED")),
            "completeCoverage" to mapOf("type" to "boolean"), "startedAt" to mapOf("type" to "string", "format" to "date-time"),
            "completedAt" to mapOf("type" to "string", "format" to "date-time"), "metadataJson" to mapOf("type" to "string"),
            "findings" to mapOf("type" to "array", "maxItems" to 500, "items" to mapOf(
                "type" to "object", "required" to listOf("externalId", "severity", "title"),
                "properties" to (listOf("externalId", "title", "description", "recommendation", "evidence", "filePath", "lineRange", "url",
                    "engine", "model", "commitSha", "issueUrl", "fixPrUrl").associateWith { text() } + mapOf(
                    "severity" to mapOf("type" to "string", "enum" to listOf("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO")),
                    "confidence" to mapOf("type" to listOf("number", "null"), "minimum" to 0, "maximum" to 1),
                    "legacyIds" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "attachments" to mapOf("type" to "array", "maxItems" to 10, "items" to mapOf(
                        "type" to "object", "required" to listOf("fileName", "contentType", "base64"),
                        "properties" to listOf("fileName", "contentType", "base64").associateWith { mapOf("type" to "string") }
                    ))
                ))
            ))
        )
    )
}
