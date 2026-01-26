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
    @Inject private val getAssetCompleteProfileTool: GetAssetCompleteProfileTool,
    // Feature 057: MCP Tools for Requirements Management
    @Inject private val exportRequirementsTool: ExportRequirementsTool,
    @Inject private val addRequirementTool: AddRequirementTool,
    @Inject private val deleteAllRequirementsTool: DeleteAllRequirementsTool,
    // Feature 060: MCP List Users Tool
    @Inject private val listUsersTool: ListUsersTool,
    // Feature: MCP Add User Tool with roles
    @Inject private val addUserTool: AddUserTool,
    // Feature: MCP Delete User Tool
    @Inject private val deleteUserTool: DeleteUserTool,
    // Feature 061: MCP List Products Tool
    @Inject private val listProductsTool: ListProductsTool,
    // MCP Tool: Get asset with most vulnerabilities
    @Inject private val getAssetMostVulnerabilitiesTool: GetAssetMostVulnerabilitiesTool,
    // Feature 062: MCP Tools for Overdue Vulnerabilities and Exception Handling
    @Inject private val getOverdueAssetsTool: GetOverdueAssetsTool,
    @Inject private val createExceptionRequestTool: CreateExceptionRequestTool,
    @Inject private val getMyExceptionRequestsTool: GetMyExceptionRequestsTool,
    @Inject private val getPendingExceptionRequestsTool: GetPendingExceptionRequestsTool,
    @Inject private val approveExceptionRequestTool: ApproveExceptionRequestTool,
    @Inject private val rejectExceptionRequestTool: RejectExceptionRequestTool,
    @Inject private val cancelExceptionRequestTool: CancelExceptionRequestTool,
    // Feature 063: MCP Tools for E2E Vulnerability Exception Workflow
    @Inject private val deleteAllAssetsTool: DeleteAllAssetsTool,
    @Inject private val addVulnerabilityTool: AddVulnerabilityTool,
    // Feature 064: MCP and CLI User Mapping Upload
    @Inject private val importUserMappingsTool: ImportUserMappingsTool,
    @Inject private val listUserMappingsTool: ListUserMappingsTool,
    // Feature: MCP Release Management
    @Inject private val listReleasesTool: ListReleasesTool,
    @Inject private val createReleaseTool: CreateReleaseTool,
    @Inject private val deleteReleaseTool: DeleteReleaseTool,
    @Inject private val setReleaseStatusTool: SetReleaseStatusTool,
    @Inject private val getReleaseTool: GetReleaseTool,
    // Feature 185: MCP Tools for Requirements Alignment Process
    @Inject private val startAlignmentTool: StartAlignmentTool,
    @Inject private val submitReviewTool: SubmitReviewTool,
    @Inject private val getAlignmentStatusTool: GetAlignmentStatusTool,
    @Inject private val finalizeAlignmentTool: FinalizeAlignmentTool
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
            getAssetCompleteProfileTool,
            // Feature 057: MCP Tools for Requirements Management
            exportRequirementsTool,
            addRequirementTool,
            deleteAllRequirementsTool,
            // Feature 060: MCP List Users Tool
            listUsersTool,
            // Feature: MCP Add User Tool with roles
            addUserTool,
            // Feature: MCP Delete User Tool
            deleteUserTool,
            // Feature 061: MCP List Products Tool
            listProductsTool,
            // MCP Tool: Get asset with most vulnerabilities
            getAssetMostVulnerabilitiesTool,
            // Feature 062: MCP Tools for Overdue Vulnerabilities and Exception Handling
            getOverdueAssetsTool,
            createExceptionRequestTool,
            getMyExceptionRequestsTool,
            getPendingExceptionRequestsTool,
            approveExceptionRequestTool,
            rejectExceptionRequestTool,
            cancelExceptionRequestTool,
            // Feature 063: MCP Tools for E2E Vulnerability Exception Workflow
            deleteAllAssetsTool,
            addVulnerabilityTool,
            // Feature 064: MCP and CLI User Mapping Upload
            importUserMappingsTool,
            listUserMappingsTool,
            // Feature: MCP Release Management
            listReleasesTool,
            createReleaseTool,
            deleteReleaseTool,
            setReleaseStatusTool,
            getReleaseTool,
            // Feature 185: MCP Tools for Requirements Alignment Process
            startAlignmentTool,
            submitReviewTool,
            getAlignmentStatusTool,
            finalizeAlignmentTool
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
            "get_requirements", "search_requirements", "export_requirements" -> {
                permissions.contains(McpPermission.REQUIREMENTS_READ)
            }
            "create_requirement", "update_requirement", "add_requirement" -> {
                permissions.contains(McpPermission.REQUIREMENTS_WRITE)
            }
            "delete_requirement", "delete_all_requirements" -> {
                permissions.contains(McpPermission.REQUIREMENTS_WRITE) // ADMIN role also checked in tool execute()
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

            // Feature 060: MCP List Users Tool (ADMIN only via User Delegation)
            "list_users" -> {
                permissions.contains(McpPermission.USER_ACTIVITY) // ADMIN role checked in tool execute()
            }

            // Feature: MCP Add User Tool (ADMIN only via User Delegation)
            "add_user" -> {
                permissions.contains(McpPermission.USER_ACTIVITY) // ADMIN role checked in tool execute()
            }

            // Feature: MCP Delete User Tool (ADMIN only via User Delegation)
            "delete_user" -> {
                permissions.contains(McpPermission.USER_ACTIVITY) // ADMIN role checked in tool execute()
            }

            // Feature 061: MCP List Products Tool (ADMIN or SECCHAMPION via User Delegation)
            "list_products" -> {
                permissions.contains(McpPermission.VULNERABILITIES_READ) // Role checked in tool execute()
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

            // MCP Tool: Get asset with most vulnerabilities
            "get_asset_most_vulnerabilities" -> {
                permissions.contains(McpPermission.VULNERABILITIES_READ) ||
                permissions.contains(McpPermission.ASSETS_READ)
            }

            // Feature 062: MCP Tools for Overdue Vulnerabilities and Exception Handling
            "get_overdue_assets" -> {
                permissions.contains(McpPermission.VULNERABILITIES_READ) ||
                permissions.contains(McpPermission.ASSETS_READ)
            }
            "create_exception_request" -> {
                permissions.contains(McpPermission.VULNERABILITIES_READ) // Any authenticated user, role check in tool
            }
            "get_my_exception_requests" -> {
                permissions.contains(McpPermission.VULNERABILITIES_READ) // Any authenticated user
            }
            "get_pending_exception_requests" -> {
                permissions.contains(McpPermission.VULNERABILITIES_READ) // Role check in tool
            }
            "approve_exception_request" -> {
                permissions.contains(McpPermission.VULNERABILITIES_READ) // Role check in tool
            }
            "reject_exception_request" -> {
                permissions.contains(McpPermission.VULNERABILITIES_READ) // Role check in tool
            }
            "cancel_exception_request" -> {
                permissions.contains(McpPermission.VULNERABILITIES_READ) // Ownership check in tool
            }

            // Feature 063: MCP Tools for E2E Vulnerability Exception Workflow
            "delete_all_assets" -> {
                permissions.contains(McpPermission.ASSETS_READ) // ADMIN role checked in tool execute()
            }
            "add_vulnerability" -> {
                permissions.contains(McpPermission.VULNERABILITIES_READ) // ADMIN/VULN role checked in tool execute()
            }

            // Feature 064: MCP and CLI User Mapping Upload
            "import_user_mappings" -> {
                permissions.contains(McpPermission.USER_ACTIVITY) // ADMIN role checked in tool execute()
            }
            "list_user_mappings" -> {
                permissions.contains(McpPermission.USER_ACTIVITY) // ADMIN role checked in tool execute()
            }

            // Feature: MCP Release Management (ADMIN or RELEASE_MANAGER via User Delegation)
            "list_releases", "get_release" -> {
                permissions.contains(McpPermission.REQUIREMENTS_READ) // ADMIN/RELEASE_MANAGER role checked in tool execute()
            }
            "create_release", "delete_release", "set_release_status" -> {
                permissions.contains(McpPermission.REQUIREMENTS_READ) // ADMIN/RELEASE_MANAGER role checked in tool execute()
            }

            // Feature 185: MCP Tools for Requirements Alignment Process
            "start_alignment", "finalize_alignment" -> {
                permissions.contains(McpPermission.REQUIREMENTS_READ) // ADMIN/RELEASE_MANAGER role checked in tool execute()
            }
            "submit_review" -> {
                permissions.contains(McpPermission.REQUIREMENTS_READ) // REQ role checked in tool execute()
            }
            "get_alignment_status" -> {
                permissions.contains(McpPermission.REQUIREMENTS_READ) // Any authenticated user with delegation
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