package com.secman.domain

/**
 * Enumeration of MCP (Model Context Protocol) operations.
 * Used for role-based access control to define what operations are allowed on resources.
 */
enum class McpOperation {
    /**
     * Read-only access to resources (GET operations)
     */
    READ,

    /**
     * Create and update operations (POST, PUT operations)
     */
    WRITE,

    /**
     * Delete operations (DELETE operations)
     */
    DELETE,

    /**
     * Execute operations like running assessments, processing data
     */
    EXECUTE;

    /**
     * Get display name for UI
     */
    fun getDisplayName(): String {
        return when (this) {
            READ -> "Read"
            WRITE -> "Write"
            DELETE -> "Delete"
            EXECUTE -> "Execute"
        }
    }

    /**
     * Get description for UI and documentation
     */
    fun getDescription(): String {
        return when (this) {
            READ -> "View and query resources without modification"
            WRITE -> "Create new resources or update existing ones"
            DELETE -> "Remove resources permanently"
            EXECUTE -> "Run operations like assessments or data processing"
        }
    }

    /**
     * Get corresponding HTTP methods
     */
    fun getHttpMethods(): List<String> {
        return when (this) {
            READ -> listOf("GET")
            WRITE -> listOf("POST", "PUT", "PATCH")
            DELETE -> listOf("DELETE")
            EXECUTE -> listOf("POST") // Execute operations typically use POST
        }
    }

    /**
     * Check if operation modifies data
     */
    fun isModifying(): Boolean {
        return when (this) {
            READ -> false
            WRITE, DELETE, EXECUTE -> true
        }
    }

    /**
     * Check if operation requires elevated permissions
     */
    fun requiresElevatedPermissions(): Boolean {
        return when (this) {
            READ -> false
            WRITE -> false
            DELETE, EXECUTE -> true
        }
    }

    /**
     * Get risk level for audit logging
     */
    fun getRiskLevel(): String {
        return when (this) {
            READ -> "LOW"
            WRITE -> "MEDIUM"
            EXECUTE -> "MEDIUM"
            DELETE -> "HIGH"
        }
    }
}