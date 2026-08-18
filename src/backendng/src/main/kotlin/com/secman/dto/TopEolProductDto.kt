package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * One product in the "Most Often EOL Products" ranking on the vulnerability
 * statistics page.
 *
 * Counts are **distinct assets**, not findings: a system running two end-of-life
 * cycles of the same product contributes 1 here, which is what makes the number
 * comparable with the neighbouring "Affected Assets" column.
 *
 * Scope is whatever the caller can see, narrowed further by the page's domain and
 * AWS-hosted filters — see `VulnerabilityStatisticsService.getTopEolProducts`.
 */
@Serdeable
data class TopEolProductDto(
    /** Component name as observed by the EOL scan, e.g. `Internet Explorer`. */
    val product: String,

    /** Distinct accessible assets on which this product is past end-of-life. */
    val affectedAssets: Long,

    /**
     * Distinct accessible assets on which it reaches end-of-life inside the
     * configured horizon. Reported for context; it never affects the ranking.
     */
    val approachingAssets: Long,

    /**
     * How many distinct release cycles of this product are past end-of-life.
     * Explains rank: a product can lead because one cycle is everywhere, or
     * because several cycles are each somewhere.
     */
    val eolVersions: Long
)
