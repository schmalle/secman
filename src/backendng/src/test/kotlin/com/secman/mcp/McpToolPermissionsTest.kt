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
    fun `the account onboarding tools resolve to the permissions they were placed under`() {
        // The trap this guards: CALLING folds in ToolCategories.CATEGORY_PERMISSIONS *last*, so
        // a tool name that also appears in a category list silently inherits that category's
        // permission instead of its explicit entry. Asserting the resolved value catches a
        // future rename into a category, which would otherwise fail open or closed silently.
        assertThat(McpToolPermissions.CALLING["simulate_account_onboarding"])
            .isEqualTo(setOf(McpPermission.USER_ACTIVITY))
        assertThat(McpToolPermissions.CALLING["list_account_onboarding_rules"])
            .isEqualTo(setOf(McpPermission.ASSESSMENTS_READ))
        assertThat(McpToolPermissions.CALLING["preview_account_onboarding_rules"])
            .isEqualTo(setOf(McpPermission.ASSESSMENTS_READ))
    }

    @Test
    fun `the account onboarding tools are in BOTH tables`() {
        // A missing CALLING entry fails closed and looks like a bug; a missing LISTING entry
        // makes the tool vanish from tools/list while still being callable. Both maps, always.
        for (tool in listOf(
            "simulate_account_onboarding",
            "list_account_onboarding_rules",
            "preview_account_onboarding_rules"
        )) {
            assertThat(McpToolPermissions.LISTING).describedAs("LISTING/%s", tool).containsKey(tool)
            assertThat(McpToolPermissions.CALLING).describedAs("CALLING/%s", tool).containsKey(tool)
        }
    }

    @Test
    fun `simulate_account_onboarding is gated exactly like import_user_mappings`() {
        // Same side effect — it onboards an account owner and sends mail — so it must not be
        // reachable with a narrower permission than the import it stands in for.
        assertThat(McpToolPermissions.CALLING["simulate_account_onboarding"])
            .isEqualTo(McpToolPermissions.CALLING["import_user_mappings"])
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
