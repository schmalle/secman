package com.secman.mcp.tools

import org.slf4j.LoggerFactory

private val riskAssessmentToolLog = LoggerFactory.getLogger("McpRiskAssessmentTools")

internal inline fun riskAssessmentTool(block: () -> Any): McpToolResult = try {
    McpToolResult.success(block())
} catch (e: NoSuchElementException) {
    McpToolResult.error("NOT_FOUND", e.message ?: "Risk assessment not found")
} catch (e: SecurityException) {
    McpToolResult.error("FORBIDDEN", e.message ?: "Access denied")
} catch (e: IllegalStateException) {
    McpToolResult.error("CONFLICT", e.message ?: "Invalid assessment state")
} catch (e: IllegalArgumentException) {
    McpToolResult.error("VALIDATION_ERROR", e.message ?: "Invalid input")
} catch (e: Exception) {
    riskAssessmentToolLog.error("MCP risk assessment operation failed", e)
    McpToolResult.error("EXECUTION_ERROR", "Risk assessment operation failed")
}
