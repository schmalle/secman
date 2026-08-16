package com.secman.mcp

import com.secman.domain.McpPermission

/**
 * Standard tool categories for common permission groupings.
 *
 * Holding **any** permission listed for a category unlocks every tool in it.
 * [CATEGORY_PERMISSIONS] is the single definition of that mapping — the tool
 * registry, the basic (non-delegated) permission check and the bulk
 * grant/authorize helpers all read it rather than restating the pairing.
 */
object ToolCategories {
    val READ_ONLY_TOOLS = setOf(
        "get_requirements", "search_requirements", "get_assessments",
        "search_assessments", "get_tags", "search_all", "export_requirements"
    )
    val WRITE_TOOLS = setOf(
        "create_requirement", "update_requirement", "delete_requirement",
        "create_assessment", "update_assessment", "delete_assessment"
    )
    val ADMIN_TOOLS = setOf(
        "get_system_info", "get_user_activity", "list_users", "send_admin_summary",
        "send_patch_notifications"
    )

    /**
     * Category -> permissions that unlock it, in resolution order. A tool that
     * appears in more than one category resolves against the first match.
     */
    val CATEGORY_PERMISSIONS: List<Pair<Set<String>, Set<McpPermission>>> = listOf(
        READ_ONLY_TOOLS to setOf(
            McpPermission.REQUIREMENTS_READ, McpPermission.ASSESSMENTS_READ, McpPermission.TAGS_READ
        ),
        WRITE_TOOLS to setOf(
            McpPermission.REQUIREMENTS_WRITE, McpPermission.ASSESSMENTS_WRITE
        ),
        ADMIN_TOOLS to setOf(
            McpPermission.SYSTEM_INFO, McpPermission.USER_ACTIVITY
        )
    )
}
