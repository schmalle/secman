package com.secman.constants

/**
 * Canonical literals used as `Asset.type` values.
 *
 * `Asset.type` is a free-form string column, but importers and access logic
 * should reference these constants rather than inlining string literals so the
 * vocabulary stays consistent. Mirrors the [AssetOwners] / [VulnerabilitySources]
 * pattern.
 */
object AssetTypes {
    /** Hosts/servers auto-created by the CrowdStrike import (and most scan imports). */
    const val SERVER = "SERVER"

    /**
     * Source-code repository (e.g. GitHub). Set on every asset auto-created by
     * `GitHubDependabotImportService`. Such assets carry the repository URL in
     * `Asset.uri` and have no `cloudAccountId`.
     */
    const val REPOSITORY = "REPOSITORY"
}
