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
    fun `add_requirement is in BOTH tables`() {
        // Regression: add_requirement was present in LISTING (REQUIREMENTS_WRITE) but absent
        // from CALLING entirely, so tools/call unconditionally denied it for every caller
        // regardless of role — fail-closed, but still a bug (§A01, see McpToolPermissions.kt).
        assertThat(McpToolPermissions.LISTING).describedAs("LISTING/add_requirement").containsKey("add_requirement")
        assertThat(McpToolPermissions.CALLING).describedAs("CALLING/add_requirement").containsKey("add_requirement")
        assertThat(McpToolPermissions.CALLING["add_requirement"]).isEqualTo(setOf(McpPermission.REQUIREMENTS_WRITE))
    }

    @Test
    fun `no tool is mapped to an empty permission set`() {
        val tables = mapOf("LISTING" to McpToolPermissions.LISTING, "CALLING" to McpToolPermissions.CALLING)
        tables.forEach { (name, table) ->
            val empty = table.filterValues { it.isEmpty() }.keys
            assertThat(empty).describedAs("$name entries with no permissions").isEmpty()
        }
    }

    @Test
    fun `the workgroup AWS account tools are in BOTH tables`() {
        // A missing CALLING entry fails closed: the tool is listed by tools/list and then
        // refused by tools/call, which reads as a broken tool rather than as a permission
        // decision. These six were in exactly that state until link_workgroup_aws_accounts
        // was added beside them.
        for (tool in listOf(
            "list_workgroup_aws_accounts",
            "add_workgroup_aws_account",
            "remove_workgroup_aws_account",
            "list_workgroup_ad_domains",
            "add_workgroup_ad_domain",
            "remove_workgroup_ad_domain",
            "link_workgroup_aws_accounts"
        )) {
            assertThat(McpToolPermissions.LISTING).containsKey(tool)
            assertThat(McpToolPermissions.CALLING).containsKey(tool)
        }
    }

    @Test
    fun `link_workgroup_aws_accounts requires the same permission as assigning an account by hand`() {
        // It has the same effect — a workgroup gains access to an account's assets
        // (unified asset access rule #9) — so it must not be reachable with less.
        assertThat(McpToolPermissions.CALLING["link_workgroup_aws_accounts"])
            .isEqualTo(McpToolPermissions.CALLING["add_workgroup_aws_account"])
        assertThat(McpToolPermissions.CALLING["link_workgroup_aws_accounts"])
            .isEqualTo(setOf(McpPermission.WORKGROUPS_WRITE))
    }

    @Test
    fun `the release, alignment, exception-detail and reminder tools are in BOTH tables`() {
        // Same fail-closed shape as add_requirement and the workgroup tools above: these 28
        // tools were declared in LISTING (so tools/list showed them) but never added to
        // CALLING at all (so tools/call denied every one of them unconditionally). Found by
        // ./scripts/owasp-check.sh --all (§A01 A01-mcp-perms). application_register is
        // deliberately excluded — LISTING's own doc comment says it "has never been mapped".
        for (tool in listOf(
            "asset_match_clear", "delete_asset_not_seen",
            "delete_all_requirements",
            "list_releases", "get_release", "compare_releases",
            "create_release", "delete_release", "set_release_status",
            "start_alignment", "submit_review", "get_alignment_status", "finalize_alignment",
            "get_exception_request", "get_my_exception_request_summary",
            "get_exception_request_statistics", "delete_exception_request",
            "reconcile_exception_requests",
            "get_vulnerability_heatmap", "refresh_vulnerability_heatmap",
            "get_top_accounts_by_finding_age", "get_crowdstrike_last_import",
            "deduplicate_vulnerabilities",
            "notify_new_accounts", "send_application_register_reminders",
            "send_exception_expiry_reminders", "send_outdated_notifications",
            "send_vulnerability_notifications",
        )) {
            assertThat(McpToolPermissions.LISTING).describedAs("LISTING/%s", tool).containsKey(tool)
            assertThat(McpToolPermissions.CALLING).describedAs("CALLING/%s", tool).containsKey(tool)
        }
    }
}
