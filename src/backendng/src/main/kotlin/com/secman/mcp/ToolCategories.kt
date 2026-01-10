package com.secman.mcp

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
        "get_system_info", "get_user_activity", "list_users"
    )
}