package com.secman.mcp

import com.secman.mcp.tools.McpTool
import com.secman.domain.McpPermission
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Central registry for all MCP tools.
 * Manages tool discovery, registration, and permission mapping.
 *
 * Every `McpTool` bean is discovered by Micronaut and registered under its own
 * `name` — adding a tool needs nothing here beyond annotating it `@Singleton`.
 */
@Singleton
class McpToolRegistry(private val allTools: List<McpTool>) {
    private val logger = LoggerFactory.getLogger(McpToolRegistry::class.java)

    private val tools: Map<String, McpTool> by lazy {
        allTools.associateBy { it.name }.also { registered ->
            logger.debug("Registered {} MCP tools: {}", registered.size, registered.keys.sorted())
        }
    }

    /**
     * Get a specific tool by name.
     */
    fun getTool(name: String): McpTool? = tools[name]

    /**
     * Get tools that a set of permissions allows access to.
     */
    fun getAuthorizedTools(permissions: Set<McpPermission>): Map<String, McpTool> =
        tools.filterKeys { McpToolPermissions.allows(McpToolPermissions.LISTING, it, permissions) }

    /**
     * Get tool capabilities for MCP protocol response.
     */
    fun getToolCapabilities(permissions: Set<McpPermission>): Map<String, Any> = mapOf(
        "tools" to getAuthorizedTools(permissions).map { (name, tool) ->
            mapOf(
                "name" to name,
                "description" to tool.description,
                "inputSchema" to tool.inputSchema
            )
        }
    )

    /**
     * Validate tool arguments against the tool's input schema.
     *
     * Basic validation only — required parameters, declared types and numeric
     * bounds. Not a full JSON Schema implementation.
     */
    @Suppress("UNCHECKED_CAST")
    fun validateArguments(toolName: String, arguments: Map<String, Any>): ValidationResult {
        val tool = getTool(toolName)
            ?: return ValidationResult(false, "Tool '$toolName' not found")

        return try {
            val properties = tool.inputSchema["properties"] as? Map<String, Any> ?: emptyMap()
            val required = tool.inputSchema["required"] as? List<String> ?: emptyList()

            required.firstOrNull { it !in arguments }?.let {
                return ValidationResult(false, "Required parameter '$it' is missing")
            }

            for ((name, value) in arguments) {
                val paramSchema = properties[name] as? Map<String, Any> ?: continue
                validateParameter(name, value, paramSchema)?.let { return it }
            }

            ValidationResult(true, null)

        } catch (e: Exception) {
            logger.error("Argument validation failed for tool: {}", toolName, e)
            ValidationResult(false, "Validation error: ${e.message}")
        }
    }

    /** Returns the failing [ValidationResult], or null when the parameter is acceptable. */
    private fun validateParameter(name: String, value: Any?, schema: Map<String, Any>): ValidationResult? {
        val expectedType = schema["type"] as? String ?: return null
        if (!isValidType(value, expectedType)) {
            return ValidationResult(false, "Parameter '$name' has invalid type. Expected: $expectedType")
        }

        if (expectedType != "number") return null
        val numValue = (value as? Number)?.toInt() ?: return null

        (schema["maximum"] as? Number)?.toInt()?.let { maximum ->
            if (numValue > maximum) return ValidationResult(false, "Parameter '$name' exceeds maximum value of $maximum")
        }
        (schema["minimum"] as? Number)?.toInt()?.let { minimum ->
            if (numValue < minimum) return ValidationResult(false, "Parameter '$name' is below minimum value of $minimum")
        }
        return null
    }

    private fun isValidType(value: Any?, expectedType: String): Boolean = when (expectedType) {
        "string" -> value is String
        "number" -> value is Number
        "boolean" -> value is Boolean
        "array" -> value is List<*>
        "object" -> value is Map<*, *>
        else -> true
    }
}

/**
 * Result of argument validation.
 */
data class ValidationResult(
    val isValid: Boolean,
    val errorMessage: String?
)
