package com.secman.mcp

import com.secman.domain.McpPermission
import com.secman.domain.McpPermission.*

/**
 * Declarative tool -> permission mappings for MCP authorization.
 *
 * Both maps use "any of" semantics: a caller is authorized for a tool when its
 * effective permission set contains **at least one** of the permissions mapped to
 * that tool. A tool absent from a map is denied by that map.
 *
 * Two maps, deliberately, because the two gates have never been identical:
 *
 * - [LISTING] gates `tools/list` (via `McpToolRegistry.getAuthorizedTools`).
 * - [CALLING] gates `tools/call` (via `McpToolPermissionService.hasPermissionWithSet`).
 *
 * A tool must pass both to be usable end-to-end. They are colocated here so the
 * divergence is visible in one screen instead of spread over two 200-line `when`
 * blocks; the contents reproduce those blocks exactly.
 *
 * Coarse permission only — the fine-grained role check (ADMIN / VULN /
 * SECCHAMPION / ...) lives in each tool's `execute()`, see `McpToolGuards`.
 */
object McpToolPermissions {

    private fun table(vararg groups: Pair<Set<McpPermission>, List<String>>): Map<String, Set<McpPermission>> =
        groups.flatMap { (permissions, tools) -> tools.map { it to permissions } }.toMap()

    /**
     * Permissions required for a tool to appear in `tools/list`.
     *
     * Only names of registered tools appear here: `McpToolRegistry.isToolAuthorized`
     * resolves the tool first and denies unknown names before consulting this map,
     * so entries for tools that do not exist would be unreachable.
     *
     * `application_register` is intentionally absent — it has never been mapped.
     */
    val LISTING: Map<String, Set<McpPermission>> = table(
        setOf(REQUIREMENTS_READ) to listOf(
            "get_requirements", "export_requirements",
            // Releases and alignment — ADMIN/RELEASE_MANAGER/REQ role checked in execute()
            "list_releases", "get_release", "compare_releases",
            "create_release", "delete_release", "set_release_status",
            "start_alignment", "submit_review", "get_alignment_status", "finalize_alignment",
        ),
        setOf(REQUIREMENTS_WRITE) to listOf(
            "add_requirement",
            "delete_all_requirements", // ADMIN role also checked in execute()
        ),
        setOf(ASSESSMENTS_READ) to listOf(
            "list_aws_account_risk_assessments", // ADMIN role checked in execute()
            // Read-only views of the onboarding rule set. ADMIN/SECCHAMPION checked in execute().
            "list_account_onboarding_rules", "preview_account_onboarding_rules",
        ),
        setOf(USER_ACTIVITY) to listOf(
            // ADMIN role checked in execute() for all of these
            "list_users", "add_user", "delete_user",
            "import_user_mappings", "list_user_mappings",
            "list_aws_account_sharing", "create_aws_account_sharing", "delete_aws_account_sharing",
            // Same group as import_user_mappings because it has the same side effect: it
            // onboards an account owner, mail included. ADMIN/SECCHAMPION checked in execute().
            "simulate_account_onboarding",
        ),
        setOf(ASSETS_READ) to listOf(
            "get_assets", "get_asset_profile", "get_all_assets_detail", "get_asset_complete_profile",
            // ADMIN role checked in execute()
            "delete_all_assets", "delete_asset", "delete_asset_not_seen", "asset_match_clear",
        ),
        setOf(ASSETS_WRITE) to listOf(
            "create_asset", "update_asset",
        ),
        setOf(SCANS_READ) to listOf(
            "get_scans", "search_products", "get_asset_scan_results",
        ),
        setOf(VULNERABILITIES_READ) to listOf(
            "get_vulnerabilities", "get_all_vulnerabilities_detail", "get_all_accessible_vulnerabilities",
            "list_products",
            // Exception workflow — ownership/role checked per tool in execute()
            "create_exception_request", "get_my_exception_requests", "get_pending_exception_requests",
            "approve_exception_request", "reject_exception_request", "cancel_exception_request",
            "get_exception_request", "get_my_exception_request_summary",
            "get_exception_request_statistics", "delete_exception_request",
            "reconcile_exception_requests", "list_vulnerability_exceptions",
            "delete_all_vulnerability_exceptions",
            "add_vulnerability",
            "get_vulnerability_heatmap", "refresh_vulnerability_heatmap",
            "get_top_accounts_by_finding_age",
            "get_crowdstrike_last_import",
            "deduplicate_vulnerabilities",
            "import_github_repos",
            "list_github_owner_email_mappings",
            "create_github_owner_email_mapping", "delete_github_owner_email_mapping",
            "discover_github_owner_email_mappings",
        ),
        setOf(VULNERABILITIES_READ, ASSETS_READ) to listOf(
            "get_asset_most_vulnerabilities", "get_overdue_assets",
        ),
        setOf(WORKGROUPS_WRITE) to listOf(
            // ADMIN role checked in execute() for all of these
            "create_workgroup", "delete_workgroup",
            "assign_assets_to_workgroup", "assign_users_to_workgroup",
            "list_workgroup_aws_accounts", "add_workgroup_aws_account", "remove_workgroup_aws_account",
            "list_workgroup_ad_domains", "add_workgroup_ad_domain", "remove_workgroup_ad_domain",
            // Links AWS accounts to the workgroup named after their display name.
            // Same permission as add_workgroup_aws_account because it has the same
            // effect — it grants a workgroup access to an account's assets (rule #9).
            "link_workgroup_aws_accounts",
        ),
        setOf(NOTIFICATIONS_SEND) to listOf(
            // ADMIN role checked in execute() for all of these
            "send_admin_summary", "send_patch_notifications",
            "notify_new_accounts", "send_exception_expiry_reminders",
            "send_outdated_notifications", "send_vulnerability_notifications",
            "send_application_register_reminders",
            "send_github_repo_alerts",
        ),
    )

    /**
     * Permissions required to actually invoke a tool through `tools/call`.
     *
     * Unlike [LISTING] this map is consulted without a registry lookup, so entries
     * for tools that are not (yet) implemented are meaningful and kept.
     *
     * The [ToolCategories] defaults are written last so they win over any explicit
     * entry, and in reverse so the earliest category wins — together that reproduces
     * the top-to-bottom order of the `when` this replaced, where `list_users`
     * resolves to the ADMIN_TOOLS default rather than to USER_ACTIVITY.
     */
    val CALLING: Map<String, Set<McpPermission>> = buildMap {
        putAll(table(
            setOf(ASSETS_READ) to listOf(
                "get_assets", "get_asset_profile", "search_assets",
                "get_all_assets_detail", "get_asset_complete_profile",
                "delete_all_assets", "delete_asset",
            ),
            setOf(ASSETS_WRITE) to listOf(
                "create_asset", "update_asset",
            ),
            // Was missing from CALLING entirely, so tools/call unconditionally denied
            // add_requirement regardless of caller — a fail-closed bug (functionality,
            // not authorization) uncovered while adding the role guard this tool was
            // also missing (see requireAnyRole call in AddRequirementTool.execute()).
            setOf(REQUIREMENTS_WRITE) to listOf(
                "add_requirement",
                "delete_all_requirements", // ADMIN re-checked via context.isAdmin in execute()
            ),
            // Releases and alignment were in LISTING but absent here, so tools/call denied
            // them unconditionally — visible in tools/list yet uncallable, the same
            // fail-closed shape as the add_requirement bug noted above. Each tool
            // re-checks its own roles in execute() (ADMIN/RELEASE_MANAGER, ADMIN/REQADMIN
            // for create+delete, REQ/ADMIN for submit_review) and calls requireDelegation.
            setOf(REQUIREMENTS_READ) to listOf(
                "list_releases", "get_release", "compare_releases",
                "create_release", "delete_release", "set_release_status",
                "start_alignment", "submit_review", "get_alignment_status", "finalize_alignment",
            ),
            setOf(VULNERABILITIES_READ) to listOf(
                "get_vulnerabilities", "search_vulnerabilities",
                "get_all_vulnerabilities_detail", "get_asset_most_vulnerabilities",
                "get_all_accessible_vulnerabilities",
                "list_products",
                "get_overdue_assets", "create_exception_request", "get_my_exception_requests",
                "get_pending_exception_requests", "approve_exception_request",
                "reject_exception_request", "cancel_exception_request",
                "list_vulnerability_exceptions", "delete_all_vulnerability_exceptions",
                "add_vulnerability",
                "import_github_repos",
                "list_github_owner_email_mappings",
                "create_github_owner_email_mapping", "delete_github_owner_email_mapping",
                "discover_github_owner_email_mappings",
                // Also LISTING-only until now. refresh_ re-checks context.isAdmin;
                // get_ scopes every row through context.getFilterableAssetIds().
                "get_vulnerability_heatmap", "refresh_vulnerability_heatmap",
            ),
            // get_asset_scan_results moved here from ASSETS_READ so CALLING agrees with
            // LISTING. While they disagreed the tool was invisible in tools/list to a
            // caller holding only ASSETS_READ (a bare USER) yet fully callable by them —
            // a permission a reviewer reading tools/list would never think to check.
            setOf(SCANS_READ) to listOf(
                "get_scans", "get_scan_results", "search_products", "get_asset_scan_results",
            ),
            setOf(AUDIT_READ) to listOf(
                "get_audit_log", "search_audit_logs",
            ),
            setOf(TRANSLATION_USE) to listOf(
                "translate_requirement",
            ),
            setOf(FILES_READ) to listOf(
                "get_requirement_files", "download_file",
            ),
            setOf(USER_ACTIVITY) to listOf(
                "list_users", "add_user", "delete_user",
                "import_user_mappings", "list_user_mappings",
                "list_aws_account_sharing", "create_aws_account_sharing", "delete_aws_account_sharing",
                "simulate_account_onboarding",
            ),
            setOf(ASSESSMENTS_READ) to listOf(
                "list_aws_account_risk_assessments",
                "list_account_onboarding_rules", "preview_account_onboarding_rules",
            ),
            setOf(WORKGROUPS_WRITE) to listOf(
                "create_workgroup", "delete_workgroup",
                "assign_assets_to_workgroup", "assign_users_to_workgroup",
                // These six were in LISTING but absent here, so tools/call denied them
                // unconditionally — visible in tools/list yet uncallable. The same
                // fail-closed shape as the add_requirement bug noted above.
                "list_workgroup_aws_accounts", "add_workgroup_aws_account", "remove_workgroup_aws_account",
                "list_workgroup_ad_domains", "add_workgroup_ad_domain", "remove_workgroup_ad_domain",
                "link_workgroup_aws_accounts",
            ),
            setOf(NOTIFICATIONS_SEND) to listOf(
                "send_github_repo_alerts",
            ),
        ))

        ToolCategories.CATEGORY_PERMISSIONS.reversed().forEach { (tools, unlocking) ->
            tools.forEach { put(it, unlocking) }
        }
    }

    /** True when [permissions] satisfies the "any of" requirement recorded in [table]. */
    fun allows(table: Map<String, Set<McpPermission>>, toolName: String, permissions: Set<McpPermission>): Boolean =
        table[toolName]?.any { it in permissions } == true
}
