package com.secman.domain

import jakarta.persistence.*
import jakarta.validation.constraints.*
import java.time.LocalDateTime
import io.micronaut.serde.annotation.Serdeable

/**
 * MCP Tool Permission entity for managing fine-grained access control to MCP tools.
 *
 * This entity defines which specific MCP tools an API key is authorized to use,
 * with optional parameter restrictions for enhanced security.
 * Enables granular permission management beyond basic read/write access.
 */
@Entity
@Table(
    name = "mcp_tool_permissions",
    indexes = [
        Index(name = "idx_mcp_tool_permissions_api_key", columnList = "apiKeyId"),
        Index(name = "idx_mcp_tool_permissions_tool", columnList = "toolName"),
        Index(name = "idx_mcp_tool_permissions_active", columnList = "isActive"),
        Index(name = "idx_mcp_tool_permissions_composite", columnList = "apiKeyId, toolName, isActive")
    ],
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_mcp_tool_permissions_api_key_tool",
            columnNames = ["apiKeyId", "toolName"]
        )
    ]
)
@Serdeable
data class McpToolPermission(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    /**
     * Foreign key reference to the McpApiKey this permission belongs to.
     */
    @Column(name = "api_key_id", nullable = false)
    @NotNull
    val apiKeyId: Long,

    /**
     * Name of the MCP tool this permission grants access to.
     * Must match exactly with registered tool names.
     */
    @Column(name = "tool_name", nullable = false, length = 100)
    @NotBlank
    @Size(min = 1, max = 100)
    val toolName: String,

    /**
     * Whether this tool permission is currently active.
     * Inactive permissions deny access to the tool.
     */
    @Column(name = "is_active", nullable = false)
    @NotNull
    var isActive: Boolean = true,

    /**
     * Optional parameter restrictions for this tool.
     * JSON string defining allowed parameter values or ranges.
     * If null, no parameter restrictions are enforced.
     */
    @Column(name = "parameter_restrictions", length = 1000)
    @Size(max = 1000)
    val parameterRestrictions: String? = null,

    /**
     * Maximum number of calls allowed per hour for this tool.
     * If null, no rate limiting is applied at the tool level.
     */
    @Column(name = "max_calls_per_hour")
    val maxCallsPerHour: Int? = null,

    /**
     * Timestamp when this permission was created.
     */
    @Column(name = "created_at", nullable = false)
    @NotNull
    val createdAt: LocalDateTime = LocalDateTime.now(),

    /**
     * Timestamp when this permission was last modified.
     */
    @Column(name = "updated_at", nullable = false)
    @NotNull
    var updatedAt: LocalDateTime = LocalDateTime.now(),

    /**
     * ID of the user who created this permission.
     */
    @Column(name = "created_by")
    val createdBy: Long? = null,

    /**
     * Optional notes about this permission (e.g., purpose, restrictions).
     */
    @Column(name = "notes", length = 500)
    @Size(max = 500)
    val notes: String? = null,

    /**
     * Priority level for this permission when multiple permissions exist.
     * Higher values take precedence. Used for conflict resolution.
     */
    @Column(name = "priority", nullable = false)
    @NotNull
    val priority: Int = 0,

    /**
     * Whether this permission allows caching of tool results.
     * Some tools with sensitive data may disable caching.
     */
    @Column(name = "allow_caching", nullable = false)
    @NotNull
    val allowCaching: Boolean = true
) {
    /**
     * Check if this tool permission allows the given parameters.
     * Returns true if no restrictions or if parameters match restrictions.
     */
    fun allowsParameters(parameters: Map<String, Any>): Boolean {
        if (parameterRestrictions.isNullOrBlank()) {
            return true // No restrictions
        }

        return try {
            val restrictions = parseParameterRestrictions(parameterRestrictions)
            validateParametersAgainstRestrictions(parameters, restrictions)
        } catch (e: Exception) {
            false // Invalid restrictions or parameters
        }
    }

    /**
     * Check if this permission is currently valid (active).
     */
    fun isValid(): Boolean {
        return isActive
    }

    /**
     * Get the effective rate limit for this tool.
     * Returns the tool-specific limit or null if no limit.
     */
    fun getEffectiveRateLimit(): Int? {
        return maxCallsPerHour
    }

    /**
     * Check if caching is allowed for this tool.
     */
    fun isCachingAllowed(): Boolean {
        return allowCaching
    }

    /**
     * Update the last modified timestamp.
     */
    fun touch() {
        updatedAt = LocalDateTime.now()
    }

    /**
     * Activate this permission.
     */
    fun activate() {
        isActive = true
        touch()
    }

    /**
     * Deactivate this permission.
     */
    fun deactivate() {
        isActive = false
        touch()
    }

    /**
     * Get a human-readable description of this permission.
     */
    fun getDescription(): String {
        val status = if (isActive) "active" else "inactive"
        val rateLimit = maxCallsPerHour?.let { " (max $it calls/hour)" } ?: ""
        val restrictions = if (parameterRestrictions != null) " with restrictions" else ""

        return "Tool '$toolName' permission ($status)$rateLimit$restrictions"
    }

    /**
     * Parse parameter restrictions JSON into a structured format.
     */
    private fun parseParameterRestrictions(restrictionsJson: String): Map<String, Any> {
        return try {
            com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(restrictionsJson, Map::class.java) as Map<String, Any>
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Validate parameters against parsed restrictions.
     */
    private fun validateParametersAgainstRestrictions(
        parameters: Map<String, Any>,
        restrictions: Map<String, Any>
    ): Boolean {
        for ((restrictionKey, restrictionValue) in restrictions) {
            when (restrictionKey) {
                "allowedParams" -> {
                    // Only specific parameters are allowed
                    val allowedParams = restrictionValue as? List<*> ?: continue
                    if (!allowedParams.containsAll(parameters.keys)) {
                        return false
                    }
                }
                "forbiddenParams" -> {
                    // Specific parameters are forbidden
                    val forbiddenParams = restrictionValue as? List<*> ?: continue
                    if (parameters.keys.any { it in forbiddenParams }) {
                        return false
                    }
                }
                "parameterValues" -> {
                    // Specific parameter value restrictions
                    val valueRestrictions = restrictionValue as? Map<*, *> ?: continue
                    for ((paramName, allowedValues) in valueRestrictions) {
                        val paramValue = parameters[paramName] ?: continue
                        val allowed = allowedValues as? List<*> ?: continue
                        if (paramValue !in allowed) {
                            return false
                        }
                    }
                }
                "maxLimit" -> {
                    // Maximum limit parameter restriction
                    val maxLimit = (restrictionValue as? Number)?.toInt() ?: continue
                    val limit = (parameters["limit"] as? Number)?.toInt()
                    if (limit != null && limit > maxLimit) {
                        return false
                    }
                }
            }
        }
        return true
    }

    companion object {
        /**
         * Standard tool categories for common permission groupings.
         */
        object ToolCategories {
            val READ_ONLY_TOOLS = setOf(
                "get_requirements", "search_requirements", "get_assessments",
                "search_assessments", "get_tags", "search_all"
            )

            val WRITE_TOOLS = setOf(
                "create_requirement", "update_requirement", "delete_requirement",
                "create_assessment", "update_assessment", "delete_assessment"
            )

            val ADMIN_TOOLS = setOf(
                "get_system_info", "get_user_activity"
            )
        }

        /**
         * Create a basic tool permission with no restrictions.
         */
        fun createBasicPermission(
            apiKeyId: Long,
            toolName: String,
            createdBy: Long? = null,
            notes: String? = null
        ): McpToolPermission {
            return McpToolPermission(
                apiKeyId = apiKeyId,
                toolName = toolName,
                createdBy = createdBy,
                notes = notes
            )
        }

        /**
         * Create a tool permission with parameter restrictions.
         */
        fun createRestrictedPermission(
            apiKeyId: Long,
            toolName: String,
            parameterRestrictions: Map<String, Any>,
            maxCallsPerHour: Int? = null,
            createdBy: Long? = null,
            notes: String? = null
        ): McpToolPermission {
            val restrictionsJson = com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(parameterRestrictions)

            return McpToolPermission(
                apiKeyId = apiKeyId,
                toolName = toolName,
                parameterRestrictions = restrictionsJson,
                maxCallsPerHour = maxCallsPerHour,
                createdBy = createdBy,
                notes = notes
            )
        }

        /**
         * Create read-only tool permissions for an API key.
         */
        fun createReadOnlyPermissions(
            apiKeyId: Long,
            createdBy: Long? = null
        ): List<McpToolPermission> {
            return ToolCategories.READ_ONLY_TOOLS.map { toolName ->
                createBasicPermission(
                    apiKeyId = apiKeyId,
                    toolName = toolName,
                    createdBy = createdBy,
                    notes = "Auto-generated read-only permission"
                )
            }
        }

        /**
         * Create all tool permissions for an API key.
         */
        fun createFullPermissions(
            apiKeyId: Long,
            includeAdminTools: Boolean = false,
            createdBy: Long? = null
        ): List<McpToolPermission> {
            val tools = ToolCategories.READ_ONLY_TOOLS + ToolCategories.WRITE_TOOLS
            val allTools = if (includeAdminTools) tools + ToolCategories.ADMIN_TOOLS else tools

            return allTools.map { toolName ->
                createBasicPermission(
                    apiKeyId = apiKeyId,
                    toolName = toolName,
                    createdBy = createdBy,
                    notes = "Auto-generated full permission"
                )
            }
        }

        /**
         * Create parameter restrictions for limiting result size.
         */
        fun createSizeRestriction(maxLimit: Int): Map<String, Any> {
            return mapOf("maxLimit" to maxLimit)
        }

        /**
         * Create parameter restrictions for specific allowed values.
         */
        fun createValueRestriction(paramName: String, allowedValues: List<Any>): Map<String, Any> {
            return mapOf("parameterValues" to mapOf(paramName to allowedValues))
        }

        /**
         * Create parameter restrictions for forbidden parameters.
         */
        fun createForbiddenParamsRestriction(forbiddenParams: List<String>): Map<String, Any> {
            return mapOf("forbiddenParams" to forbiddenParams)
        }

        /**
         * Validate a tool name against known tool categories.
         */
        fun isValidToolName(toolName: String): Boolean {
            val allKnownTools = ToolCategories.READ_ONLY_TOOLS +
                               ToolCategories.WRITE_TOOLS +
                               ToolCategories.ADMIN_TOOLS
            return toolName in allKnownTools
        }

        /**
         * Query helper for finding permissions by API key.
         */
        fun byApiKeyQuery(): String {
            return "apiKeyId = :apiKeyId AND isActive = true ORDER BY toolName"
        }

        /**
         * Query helper for finding permissions by tool.
         */
        fun byToolQuery(): String {
            return "toolName = :toolName AND isActive = true ORDER BY priority DESC"
        }
    }

    override fun toString(): String {
        return "McpToolPermission(id=$id, apiKeyId=$apiKeyId, toolName='$toolName', " +
               "isActive=$isActive, maxCallsPerHour=$maxCallsPerHour, priority=$priority)"
    }
}