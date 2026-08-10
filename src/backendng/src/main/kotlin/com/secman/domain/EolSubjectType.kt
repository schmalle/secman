package com.secman.domain

import io.micronaut.serde.annotation.Serdeable

/**
 * What a given [EolFinding] was matched from.
 *
 * The subject decides which access boundary applies:
 * [ASSET_OS] and [ASSET_PRODUCT] are asset-scoped and resolve through
 * `AssetFilterService`; [REPOSITORY_COMPONENT] is repository-scoped and is
 * ADMIN/SECCHAMPION-only, mirroring `GithubRepositoryController`.
 */
@Serdeable
enum class EolSubjectType {
    /** Matched from `Asset.osVersion`. */
    ASSET_OS,

    /** Matched from an `InstalledProduct` row on an asset. */
    ASSET_PRODUCT,

    /** Matched from a GitHub repository dependency (Dependabot alert package). */
    REPOSITORY_COMPONENT
}
