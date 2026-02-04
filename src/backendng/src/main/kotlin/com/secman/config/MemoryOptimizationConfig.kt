package com.secman.config

import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.serde.annotation.Serdeable

/**
 * Configuration for memory optimization features
 * Feature: 073-memory-optimization
 *
 * Provides feature flags for stability-first rollback capability:
 * - lazyLoadingEnabled: Toggle LAZY/EAGER entity loading
 * - batchSize: Configurable batch size for large operations
 * - streamingExportsEnabled: Toggle streaming vs buffered exports
 *
 * Environment variables:
 * - MEMORY_LAZY_LOADING (default: true)
 * - MEMORY_BATCH_SIZE (default: 1000)
 * - MEMORY_STREAMING_EXPORTS (default: true)
 */
@ConfigurationProperties("secman.memory")
@Serdeable
data class MemoryOptimizationConfig(
    /**
     * Enable LAZY loading for entity relationships (default: true)
     * When false, reverts to EAGER loading behavior for rollback
     */
    var lazyLoadingEnabled: Boolean = true,

    /**
     * Batch size for large data operations (default: 1000)
     * Used for duplicate cleanup and bulk deletions
     * Valid range: 100-10000
     */
    var batchSize: Int = 1000,

    /**
     * Enable streaming exports (default: true)
     * When true, writes directly to Excel during fetch
     * When false, accumulates all records first (original behavior)
     */
    var streamingExportsEnabled: Boolean = true
)
