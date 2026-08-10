package com.secman.domain

import io.micronaut.serde.annotation.Serdeable

/**
 * Lifecycle state of one matched component relative to "now".
 *
 * Only [EOL] and [APPROACHING_EOL] are persisted as [EolFinding] rows —
 * [SUPPORTED] would mean storing a row per installed product per asset, which
 * is a seven-figure table on this dataset for no reporting value.
 */
@Serdeable
enum class EolStatus {
    /** Support has already ended (dated in the past, or upstream flagged it EOL). */
    EOL,

    /** Support ends within the configured horizon (default 12 months). */
    APPROACHING_EOL,

    /** Support ends after the horizon, or upstream reports the cycle maintained. */
    SUPPORTED
}
