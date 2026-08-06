package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * Traffic-light status answering "does this asset need manual intervention?".
 *
 * Deliberately independent of severity: an asset whose only Critical finding is covered by an
 * active exception is GREEN, because there is nothing for its owner to do. Severity is still
 * reported separately in the same row.
 *
 * RED is defined to coincide exactly with the Outdated Assets view: at least one non-excepted
 * vulnerability whose SLA anchor is older than the configured reminder threshold. Keeping the
 * two definitions identical is what makes the lamp trustworthy — a RED asset is on the Outdated
 * Assets list and its owner is receiving reminder mail.
 */
@Serdeable
enum class AssetInterventionStatus {
    /** No vulnerabilities, or every vulnerability is covered by an active exception. */
    GREEN,

    /** Non-excepted vulnerabilities exist, but all are still inside the threshold window. */
    YELLOW,

    /** At least one non-excepted vulnerability is older than the threshold. */
    RED;

    companion object {
        /**
         * Roll a set of asset statuses up to their parent (account, workgroup, global).
         *
         * The parent takes the worst status of its children, so a single RED asset makes the
         * whole account RED. An empty group is GREEN — nothing to act on.
         */
        fun worstOf(statuses: Collection<AssetInterventionStatus>): AssetInterventionStatus =
            statuses.maxByOrNull { it.ordinal } ?: GREEN
    }
}
