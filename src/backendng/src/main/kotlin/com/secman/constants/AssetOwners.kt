package com.secman.constants

/**
 * Canonical owner literals used as `Asset.owner` values.
 *
 * Single source of truth — referenced by the import service (writer), the
 * controller's owner-candidates dropdown, and the legacy stale-cleanup
 * predicate (Feature 087, rule B). See spec FR-014.
 */
object AssetOwners {
    /**
     * Set on every asset auto-created by `CrowdStrikeVulnerabilityImportService`
     * (Feature 030, FR-012). Rule B's predicate keys off this exact string.
     */
    const val CROWDSTRIKE_IMPORT = "CrowdStrike Import"

    /**
     * Set on every repository asset auto-created by
     * `GitHubDependabotImportService` (Dependabot alert import).
     */
    const val GITHUB_IMPORT = "GitHub Dependabot"
}
