package com.secman.domain

/**
 * Enumeration of MCP (Model Context Protocol) permissions.
 * Defines what operations an MCP client is authorized to perform.
 */
enum class McpPermission {
    /**
     * Read security requirements - allows get_requirements tool
     */
    REQUIREMENTS_READ,

    /**
     * Create/update security requirements - allows create_requirement, update_requirement tools
     */
    REQUIREMENTS_WRITE,

    /**
     * Delete security requirements - allows requirement deletion operations
     */
    REQUIREMENTS_DELETE,

    /**
     * Read risk assessments - allows get_risk_assessments tool
     */
    ASSESSMENTS_READ,

    /**
     * Execute new risk assessments - allows execute_risk_assessment tool
     */
    ASSESSMENTS_EXECUTE,

    /**
     * Create/update risk assessments - allows create/update assessment operations
     */
    ASSESSMENTS_WRITE,

    /**
     * Read/download requirement files - allows get_requirement_files, download_file tools
     */
    FILES_READ,

    /**
     * Read tags and categories - allows get_tags tool
     */
    TAGS_READ,

    /**
     * Read system information - allows system info tools
     */
    SYSTEM_INFO,

    /**
     * Read user activity - allows user activity monitoring tools
     */
    USER_ACTIVITY,

    /**
     * Use translation services - allows translate_requirement tool
     */
    TRANSLATION_USE,

    /**
     * Read audit logs (admin only) - allows get_audit_log tool
     */
    AUDIT_READ,

    /**
     * Read asset inventory - allows get_assets, get_asset_profile tools
     */
    ASSETS_READ,

    /**
     * Read scan data - allows get_scans, get_scan_results, search_products tools
     */
    SCANS_READ,

    /**
     * Read vulnerability data - allows get_vulnerabilities tool
     */
    VULNERABILITIES_READ,

    /**
     * Manage workgroups - allows create_workgroup, delete_workgroup, assign_assets_to_workgroup,
     * assign_users_to_workgroup MCP tools (admin only)
     */
    WORKGROUPS_WRITE,

    /**
     * Send notifications - allows send_admin_summary tool (admin only)
     */
    NOTIFICATIONS_SEND;

    /**
     * Get display name for UI
     */
    fun getDisplayName(): String {
        return when (this) {
            REQUIREMENTS_READ -> "Read Requirements"
            REQUIREMENTS_WRITE -> "Write Requirements"
            REQUIREMENTS_DELETE -> "Delete Requirements"
            ASSESSMENTS_READ -> "Read Assessments"
            ASSESSMENTS_EXECUTE -> "Execute Assessments"
            ASSESSMENTS_WRITE -> "Write Assessments"
            FILES_READ -> "Read Files"
            TAGS_READ -> "Read Tags"
            SYSTEM_INFO -> "System Information"
            USER_ACTIVITY -> "User Activity"
            TRANSLATION_USE -> "Use Translations"
            AUDIT_READ -> "Read Audit Logs"
            ASSETS_READ -> "Read Assets"
            SCANS_READ -> "Read Scans"
            VULNERABILITIES_READ -> "Read Vulnerabilities"
            WORKGROUPS_WRITE -> "Manage Workgroups"
            NOTIFICATIONS_SEND -> "Send Notifications"
        }
    }

    /**
     * Get description for UI
     */
    fun getDescription(): String {
        return when (this) {
            REQUIREMENTS_READ -> "View and search security requirements"
            REQUIREMENTS_WRITE -> "Create and update security requirements"
            REQUIREMENTS_DELETE -> "Delete security requirements"
            ASSESSMENTS_READ -> "View risk assessments and results"
            ASSESSMENTS_EXECUTE -> "Run new risk assessments"
            ASSESSMENTS_WRITE -> "Create and update risk assessments"
            FILES_READ -> "Download requirement files and attachments"
            TAGS_READ -> "View tags and categories"
            SYSTEM_INFO -> "Access system information and statistics"
            USER_ACTIVITY -> "Monitor user activity and sessions"
            TRANSLATION_USE -> "Translate requirements to different languages"
            AUDIT_READ -> "View MCP server audit logs (admin only)"
            ASSETS_READ -> "View and search asset inventory"
            SCANS_READ -> "View scan data and results"
            VULNERABILITIES_READ -> "View vulnerability information"
            WORKGROUPS_WRITE -> "Create, delete, and manage workgroup memberships (admin only)"
            NOTIFICATIONS_SEND -> "Send admin summary emails and trigger notifications (admin only)"
        }
    }

    /**
     * Check if permission requires admin role
     */
    fun requiresAdmin(): Boolean {
        return when (this) {
            AUDIT_READ, USER_ACTIVITY, SYSTEM_INFO, WORKGROUPS_WRITE, NOTIFICATIONS_SEND -> true
            else -> false
        }
    }
}