package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.McpRequirementManagementService
import jakarta.inject.Singleton

@Singleton
class ListUseCasesTool(private val managementService: McpRequirementManagementService) : McpTool {
    override val name = "list_use_cases"
    override val description = "List requirement use cases with bounded pagination and optional name search"
    override val operation = McpOperation.READ
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "search" to mapOf("type" to "string", "maxLength" to MAX_USE_CASE_NAME_LENGTH, "description" to "Case-insensitive name search"),
            "page" to mapOf("type" to "number", "minimum" to 0, "maximum" to MAX_PAGE, "default" to 0),
            "pageSize" to mapOf("type" to "number", "minimum" to 1, "maximum" to MAX_PAGE_SIZE, "default" to 50)
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyRole(
            context,
            "ADMIN",
            "REQ",
            "SECCHAMPION",
            code = "ROLE_REQUIRED",
            message = "ADMIN, REQ or SECCHAMPION role required to list use cases"
        )?.let { return it }
        val page = (arguments["page"] as? Number)?.toInt() ?: 0
        val pageSize = (arguments["pageSize"] as? Number)?.toInt() ?: 50
        val search = (arguments["search"] as? String)?.trim().orEmpty()
        if (page !in 0..MAX_PAGE || pageSize !in 1..MAX_PAGE_SIZE) {
            return McpToolResult.error(
                "VALIDATION_ERROR",
                "page must be 0..$MAX_PAGE and pageSize must be 1..$MAX_PAGE_SIZE"
            )
        }
        if (search.length > MAX_USE_CASE_NAME_LENGTH) {
            return McpToolResult.error("VALIDATION_ERROR", "search must not exceed $MAX_USE_CASE_NAME_LENGTH characters")
        }
        val result = managementService.listUseCases(search, page, pageSize)
        return McpToolResult.success(
            mapOf(
                "useCases" to result.content.map(::useCaseResult),
                "page" to result.pageNumber,
                "pageSize" to result.size,
                "total" to result.totalSize,
                "totalPages" to result.totalPages,
                "hasNext" to result.hasNext()
            )
        )
    }

    companion object {
        const val MAX_PAGE = 10_000
        const val MAX_PAGE_SIZE = 100
    }
}
