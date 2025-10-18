package com.secman.mcp

import com.secman.mcp.tools.*
import com.secman.domain.McpPermission
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Central registry for all MCP tools.
 * Manages tool discovery, registration, and permission mapping.
 */
@Singleton
class McpToolRegistry(
    @Inject private val getRequirementsTool: GetRequirementsTool,
    // Feature 006: MCP Tools for Asset Inventory, Scans, Vulnerabilities, and Products
    @Inject private val getAssetsTool: GetAssetsTool,
    @Inject private val getScansTool: GetScansTool,
    @Inject private val getVulnerabilitiesTool: GetVulnerabilitiesTool,
    @Inject private val searchProductsTool: SearchProductsTool,
    @Inject private val getAssetProfileTool: GetAssetProfileTool,
    // Feature 009: Enhanced MCP Tools with Authentication and Rate Limiting
    @Inject private val getAllAssetsDetailTool: GetAllAssetsDetailTool,
    @Inject private val getAssetScanResultsTool: GetAssetScanResultsTool,
    @Inject private val getAllVulnerabilitiesDetailTool: GetAllVulnerabilitiesDetailTool,
    @Inject private val getAssetCompleteProfileTool: GetAssetCompleteProfileTool
) {
    private val logger = LoggerFactory.getLogger(McpToolRegistry::class.java)

    private val tools: Map<String, McpTool> by lazy {
        val toolMap = mutableMapOf<String, McpTool>()

        // Register all tools
        listOf(
            getRequirementsTool,
            // Feature 006: MCP Tools for Asset Inventory, Scans, Vulnerabilities, and Products
            getAssetsTool,
            getScansTool,
            getVulnerabilitiesTool,
            searchProductsTool,
            getAssetProfileTool,
            // Feature 009: Enhanced MCP Tools with Authentication and Rate Limiting
            getAllAssetsDetailTool,
            getAssetScanResultsTool,
            getAllVulnerabilitiesDetailTool,
            getAssetCompleteProfileTool
        ).forEach { tool ->
            toolMap[tool.name] = tool
            logger.debug("Registered MCP tool: {}", tool.name)
        }

        toolMap.toMap()
    }

    /**
     * Get all available tools.
     */
    fun getAllTools(): Map<String, McpTool> = tools

    /**
     * Get a specific tool by name.
     */
    fun getTool(name: String): McpTool? = tools[name]

    /**
     * Get tools that a set of permissions allows access to.
     */
    fun getAuthorizedTools(permissions: Set<McpPermission>): Map<String, McpTool> {
        return tools.filter { (toolName, tool) ->
            isToolAuthorized(toolName, tool, permissions)
        }
    }

    /**
     * Get tool capabilities for MCP protocol response.
     */
    fun getToolCapabilities(permissions: Set<McpPermission>): Map<String, Any> {
        val authorizedTools = getAuthorizedTools(permissions)

        return mapOf(
            "tools" to authorizedTools.map { (name, tool) ->
                mapOf(
                    "name" to name,
                    "description" to tool.description,
                    "inputSchema" to tool.inputSchema
                )
            }
        )
    }

    /**
     * Check if a tool is authorized for the given permissions.
     */
    fun isToolAuthorized(toolName: String, permissions: Set<McpPermission>): Boolean {
        val tool = getTool(toolName) ?: return false
        return isToolAuthorized(toolName, tool, permissions)
    }

    private fun isToolAuthorized(toolName: String, tool: McpTool, permissions: Set<McpPermission>): Boolean {
        return when (toolName) {
            // Requirements tools
            "get_requirements", "search_requirements" -> {
                permissions.contains(McpPermission.REQUIREMENTS_READ)
            }
            "create_requirement", "update_requirement" -> {
                permissions.contains(McpPermission.REQUIREMENTS_WRITE)
            }
            "delete_requirement" -> {
                permissions.contains(McpPermission.REQUIREMENTS_WRITE) // Or could require separate DELETE permission
            }

            // Assessment tools
            "get_assessments", "search_assessments" -> {
                permissions.contains(McpPermission.ASSESSMENTS_READ)
            }
            "create_assessment", "update_assessment" -> {
                permissions.contains(McpPermission.ASSESSMENTS_WRITE)
            }
            "delete_assessment" -> {
                permissions.contains(McpPermission.ASSESSMENTS_WRITE)
            }

            // Tag tools
            "get_tags" -> {
                permissions.contains(McpPermission.TAGS_READ) ||
                permissions.contains(McpPermission.REQUIREMENTS_READ) ||
                permissions.contains(McpPermission.ASSESSMENTS_READ)
            }

            // Universal search
            "search_all" -> {
                permissions.any { it in listOf(
                    McpPermission.REQUIREMENTS_READ,
                    McpPermission.ASSESSMENTS_READ,
                    McpPermission.TAGS_READ
                )}
            }

            // Admin tools
            "get_system_info" -> {
                permissions.contains(McpPermission.SYSTEM_INFO)
            }
            "get_user_activity" -> {
                permissions.contains(McpPermission.USER_ACTIVITY)
            }

            // Feature 006: Asset Inventory, Scans, Vulnerabilities, and Products
            "get_assets" -> {
                permissions.contains(McpPermission.ASSETS_READ)
            }
            "get_scans" -> {
                permissions.contains(McpPermission.SCANS_READ)
            }
            "get_vulnerabilities" -> {
                permissions.contains(McpPermission.VULNERABILITIES_READ)
            }
            "search_products" -> {
                permissions.contains(McpPermission.SCANS_READ)
            }
            "get_asset_profile" -> {
                permissions.contains(McpPermission.ASSETS_READ)
            }

            // Feature 009: Enhanced MCP Tools with Authentication and Rate Limiting
            "get_all_assets_detail" -> {
                permissions.contains(McpPermission.ASSETS_READ)
            }
            "get_asset_scan_results" -> {
                permissions.contains(McpPermission.SCANS_READ)
            }
            "get_all_vulnerabilities_detail" -> {
                permissions.contains(McpPermission.VULNERABILITIES_READ)
            }
            "get_asset_complete_profile" -> {
                permissions.contains(McpPermission.ASSETS_READ)
            }

            else -> false
        }
    }

    /**
     * Get tool statistics for monitoring.
     */
    fun getToolStatistics(): Map<String, Any> {
        return mapOf(
            "totalTools" to tools.size,
            "toolsByOperation" to tools.values.groupBy { it.operation }.mapValues { it.value.size },
            "availableTools" to tools.keys.sorted()
        )
    }

    /**
     * Validate tool arguments against the tool's input schema.
     */
    @Suppress("UNCHECKED_CAST")
    fun validateArguments(toolName: String, arguments: Map<String, Any>): ValidationResult {
        val tool = getTool(toolName)
            ?: return ValidationResult(false, "Tool '$toolName' not found")

        try {
            // Basic validation - in a full implementation, this would use JSON Schema validation
            val schema = tool.inputSchema
            val properties = schema["properties"] as? Map<String, Any> ?: emptyMap()
            val required = schema["required"] as? List<String> ?: emptyList()

            // Check required parameters
            for (requiredParam in required) {
                if (!arguments.containsKey(requiredParam)) {
                    return ValidationResult(false, "Required parameter '$requiredParam' is missing")
                }
            }

            // Basic type checking
            for ((paramName, paramValue) in arguments) {
                val paramSchema = properties[paramName] as? Map<String, Any>
                if (paramSchema != null) {
                    val expectedType = paramSchema["type"] as? String
                    if (expectedType != null && !isValidType(paramValue, expectedType)) {
                        return ValidationResult(false, "Parameter '$paramName' has invalid type. Expected: $expectedType")
                    }

                    // Check limits
                    if (expectedType == "number") {
                        val numValue = (paramValue as? Number)?.toInt()
                        val maximum = (paramSchema["maximum"] as? Number)?.toInt()
                        val minimum = (paramSchema["minimum"] as? Number)?.toInt()

                        if (maximum != null && numValue != null && numValue > maximum) {
                            return ValidationResult(false, "Parameter '$paramName' exceeds maximum value of $maximum")
                        }
                        if (minimum != null && numValue != null && numValue < minimum) {
                            return ValidationResult(false, "Parameter '$paramName' is below minimum value of $minimum")
                        }
                    }
                }
            }

            return ValidationResult(true, null)

        } catch (e: Exception) {
            logger.error("Argument validation failed for tool: {}", toolName, e)
            return ValidationResult(false, "Validation error: ${e.message}")
        }
    }

    private fun isValidType(value: Any?, expectedType: String): Boolean {
        return when (expectedType) {
            "string" -> value is String
            "number" -> value is Number
            "boolean" -> value is Boolean
            "array" -> value is List<*>
            "object" -> value is Map<*, *>
            else -> true
        }
    }
}

/**
 * Result of argument validation.
 */
data class ValidationResult(
    val isValid: Boolean,
    val errorMessage: String?
)