package com.secman.mcp

import com.secman.domain.McpPermission
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in the declarative permission tables that replaced the two hand-written
 * `when` blocks. These are authorization gates, so the assertions are about
 * exact permissions rather than "roughly the right shape".
 */
class McpToolPermissionsTest {

    @Test
    fun `allows requires at least one of the mapped permissions`() {
        val table = McpToolPermissions.LISTING

        assertThat(McpToolPermissions.allows(table, "get_assets", setOf(McpPermission.ASSETS_READ))).isTrue
        assertThat(McpToolPermissions.allows(table, "get_assets", setOf(McpPermission.SCANS_READ))).isFalse
        assertThat(McpToolPermissions.allows(table, "get_assets", emptySet())).isFalse
    }

    @Test
    fun `allows denies tools that are absent from the table`() {
        assertThat(
            McpToolPermissions.allows(
                McpToolPermissions.LISTING,
                "no_such_tool",
                McpPermission.entries.toSet()
            )
        ).isFalse
    }

    @Test
    fun `either mapped permission is enough for multi-permission tools`() {
        val table = McpToolPermissions.LISTING

        assertThat(McpToolPermissions.allows(table, "get_overdue_assets", setOf(McpPermission.ASSETS_READ))).isTrue
        assertThat(McpToolPermissions.allows(table, "get_overdue_assets", setOf(McpPermission.VULNERABILITIES_READ))).isTrue
        assertThat(McpToolPermissions.allows(table, "get_overdue_assets", setOf(McpPermission.SCANS_READ))).isFalse
    }

    @Test
    fun `category defaults win over explicit entries in the calling table`() {
        // list_users is both an ADMIN_TOOLS member and an explicit USER_ACTIVITY
        // entry; the category must win, as it did in the original `when` order.
        assertThat(McpToolPermissions.CALLING["list_users"])
            .isEqualTo(setOf(McpPermission.SYSTEM_INFO, McpPermission.USER_ACTIVITY))

        // add_user is not in any category, so its explicit entry stands.
        assertThat(McpToolPermissions.CALLING["add_user"]).isEqualTo(setOf(McpPermission.USER_ACTIVITY))
    }

    @Test
    fun `every tool category is represented in the calling table`() {
        val categorised = ToolCategories.READ_ONLY_TOOLS + ToolCategories.WRITE_TOOLS + ToolCategories.ADMIN_TOOLS
        assertThat(McpToolPermissions.CALLING.keys).containsAll(categorised)
    }

    @Test
    fun `no tool is mapped to an empty permission set`() {
        val tables = mapOf("LISTING" to McpToolPermissions.LISTING, "CALLING" to McpToolPermissions.CALLING)
        tables.forEach { (name, table) ->
            val empty = table.filterValues { it.isEmpty() }.keys
            assertThat(empty).describedAs("$name entries with no permissions").isEmpty()
        }
    }
}
