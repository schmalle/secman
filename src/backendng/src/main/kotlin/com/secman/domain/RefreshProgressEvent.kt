package com.secman.domain

import io.micronaut.serde.annotation.Serdeable

/**
 * Domain event published during materialized view refresh for SSE streaming
 *
 * Not a database entity - used for application-level eventing and SSE broadcasting.
 * Published by MaterializedViewRefreshService during refresh operations.
 * Consumed by OutdatedAssetRefreshProgressHandler for SSE clients.
 *
 * Feature: 034-outdated-assets
 * Task: T006
 * Spec reference: data-model.md, research.md (SSE pattern)
 */
@Serdeable
data class RefreshProgressEvent(
    val jobId: Long,
    val status: RefreshJobStatus,
    val progressPercentage: Int,
    val assetsProcessed: Int,
    val totalAssets: Int,
    val message: String? = null
)
