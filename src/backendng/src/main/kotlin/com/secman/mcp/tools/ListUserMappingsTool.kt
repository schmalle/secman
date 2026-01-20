package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.UserMappingRepository
import io.micronaut.data.model.Pageable
import io.micronaut.data.model.Sort
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.format.DateTimeFormatter

/**
 * MCP tool for listing user mappings with pagination and filtering.
 * Feature: 064-mcp-cli-user-mapping
 *
 * ADMIN role is required via User Delegation.
 * Supports pagination and email filtering.
 *
 * Input parameters:
 * - email (optional): Filter by email address (partial match, case-insensitive)
 * - page (optional): Page number (0-indexed). Defaults to 0.
 * - size (optional): Page size. Defaults to 20, max 100.
 *
 * Returns:
 * - mappings: List of user mapping DTOs
 * - page: Current page number
 * - size: Page size
 * - totalElements: Total number of mappings
 * - totalPages: Total number of pages
 */
@Singleton
class ListUserMappingsTool(
    @Inject private val userMappingRepository: UserMappingRepository
) : McpTool {

    private val log = LoggerFactory.getLogger(ListUserMappingsTool::class.java)
    private val isoFormatter = DateTimeFormatter.ISO_INSTANT

    override val name = "list_user_mappings"
    override val description = "List user mappings with pagination and filtering (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "email" to mapOf(
                "type" to "string",
                "description" to "Filter by email address (partial match, case-insensitive, optional)"
            ),
            "page" to mapOf(
                "type" to "number",
                "description" to "Page number (0-indexed)",
                "default" to 0,
                "minimum" to 0
            ),
            "size" to mapOf(
                "type" to "number",
                "description" to "Page size",
                "default" to 20,
                "minimum" to 1,
                "maximum" to 100
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // Require User Delegation
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        // Require ADMIN role
        if (!context.isAdmin) {
            return McpToolResult.error(
                "ADMIN_REQUIRED",
                "ADMIN role required to list user mappings"
            )
        }

        // Extract parameters
        val emailFilter = (arguments["email"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val page = (arguments["page"] as? Number)?.toInt() ?: 0
        val size = ((arguments["size"] as? Number)?.toInt() ?: 20).coerceIn(1, 100)

        try {
            // Create pageable with sorting by createdAt descending
            val pageable = Pageable.from(page, size, Sort.of(Sort.Order.desc("createdAt")))

            // Query based on filter
            val pageResult = if (emailFilter != null) {
                userMappingRepository.findByEmailContainingIgnoreCase(emailFilter, pageable)
            } else {
                userMappingRepository.findAll(pageable)
            }

            // Map to DTOs
            val mappingDtos = pageResult.content.map { mapping ->
                mapOf(
                    "id" to mapping.id,
                    "email" to mapping.email,
                    "awsAccountId" to mapping.awsAccountId,
                    "domain" to mapping.domain,
                    "userId" to mapping.user?.id,
                    "isFutureMapping" to (mapping.user == null),
                    "appliedAt" to mapping.appliedAt?.let { isoFormatter.format(it) },
                    "createdAt" to mapping.createdAt?.let { isoFormatter.format(it) },
                    "updatedAt" to mapping.updatedAt?.let { isoFormatter.format(it) }
                )
            }

            val result = mapOf(
                "mappings" to mappingDtos,
                "page" to pageResult.pageNumber,
                "size" to pageResult.size,
                "totalElements" to pageResult.totalSize,
                "totalPages" to pageResult.totalPages
            )

            log.debug(
                "MCP list_user_mappings: page={}, size={}, filter={}, total={}, actor={}",
                page, size, emailFilter, pageResult.totalSize, context.delegatedUserEmail
            )

            return McpToolResult.success(result)

        } catch (e: Exception) {
            log.error("Failed to list user mappings", e)
            return McpToolResult.error("EXECUTION_ERROR", "Failed to list user mappings: ${e.message}")
        }
    }
}
