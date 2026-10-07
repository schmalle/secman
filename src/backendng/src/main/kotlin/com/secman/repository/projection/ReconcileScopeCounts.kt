package com.secman.repository.projection

import io.micronaut.core.annotation.Introspected

@Introspected
data class ReconcileScopeCounts(
    val staleCandidates: Long,
    val total: Long
)
