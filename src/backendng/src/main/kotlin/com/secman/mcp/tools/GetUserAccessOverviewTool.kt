package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.UserAccessOverviewService
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

@Singleton
class GetUserAccessOverviewTool(private val service: UserAccessOverviewService) : McpTool {
    private val log = LoggerFactory.getLogger(javaClass)
    override val name = "get_user_access_overview"
    override val description = "Explain a target user's effective SecMan asset, AWS account and AD domain access by exact email (ADMIN delegation required)"
    override val operation = McpOperation.READ
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "email" to mapOf("type" to "string", "description" to "Exact target email; trimmed and case-insensitive"),
            "page" to mapOf("type" to "integer", "minimum" to 0, "default" to 0),
            "size" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 500, "default" to 100)
        ),
        "required" to listOf("email")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        val denial = requireDelegation(context) ?: requireAnyUserRole(
            context, "ADMIN", message = "ADMIN delegated user required to inspect user access"
        )
        if (denial != null) {
            log.warn("MCP access overview actorId={} targetId=unresolved outcome=denied code={}", context.delegatedUserId, denial.code)
            return denial
        }
        return try {
            val email = arguments["email"] as? String ?: return McpToolResult.error("INVALID_ARGUMENT", "email is required")
            val page = integer(arguments, "page", 0)
            val size = integer(arguments, "size", 100)
            val report = service.overview(email, page, size)
            log.info("MCP access overview actorId={} targetId={} page={} outcome=success", context.delegatedUserId, report.user.id, page)
            McpToolResult.success(report)
        } catch (e: IllegalArgumentException) {
            McpToolResult.error("INVALID_ARGUMENT", "page and size must be integers within the supported range")
        } catch (e: HttpStatusException) {
            when (e.status) {
                HttpStatus.BAD_REQUEST -> McpToolResult.error("INVALID_ARGUMENT", "Invalid email or pagination; page >= 0 and size 1..500 required")
                HttpStatus.NOT_FOUND -> McpToolResult.error("NOT_FOUND", "User not found")
                else -> failure(context, e)
            }
        } catch (e: Exception) {
            failure(context, e)
        }
    }

    private fun integer(arguments: Map<String, Any>, key: String, default: Int): Int {
        if (key !in arguments) return default
        val number = arguments[key] as? Number ?: throw IllegalArgumentException()
        val value = number.toDouble()
        require(value.isFinite() && value >= 0 && value <= Int.MAX_VALUE && value == value.toInt().toDouble())
        return value.toInt()
    }

    private fun failure(context: McpExecutionContext, error: Exception): McpToolResult.Error {
        log.error("MCP access overview actorId={} targetId=unresolved outcome=failed", context.delegatedUserId, error)
        return McpToolResult.error("INTERNAL_ERROR", "Failed to retrieve user access overview")
    }
}
