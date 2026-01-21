package com.secman.service

import com.secman.domain.MaterializedViewRefreshJob
import com.secman.domain.OutdatedAssetMaterializedView
import com.secman.domain.RefreshProgressEvent
import com.secman.repository.MaterializedViewRefreshJobRepository
import com.secman.repository.OutdatedAssetMaterializedViewRepository
import com.secman.repository.VulnerabilityRepository
import io.micronaut.context.event.ApplicationEventPublisher
import io.micronaut.data.model.Pageable
import io.micronaut.data.model.Sort
import io.micronaut.scheduling.annotation.Async
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Service for managing materialized view refresh operations
 *
 * Provides asynchronous refresh of outdated assets materialized view with:
 * - Background job execution (@Async)
 * - Progress tracking and SSE event publishing
 * - Batch processing for performance (1000 assets/chunk)
 * - Error handling and audit trail
 * - Observability metrics and structured logging
 *
 * Feature: 034-outdated-assets
 * Task: T011
 * Spec reference: FR-005, FR-007, FR-021, FR-022, research.md
 */
@Singleton
open class MaterializedViewRefreshService(
    private val refreshJobRepository: MaterializedViewRefreshJobRepository,
    private val outdatedAssetRepository: OutdatedAssetMaterializedViewRepository,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val vulnerabilityConfigService: VulnerabilityConfigService,
    private val eventPublisher: ApplicationEventPublisher<RefreshProgressEvent>,
    private val vulnerabilityExceptionService: VulnerabilityExceptionService  // For checking active exceptions
) {
    private val log = LoggerFactory.getLogger(MaterializedViewRefreshService::class.java)

    // SSE sink for broadcasting refresh progress to all connected clients
    // Many().multicast() allows multiple subscribers
    private val progressSink: Sinks.Many<RefreshProgressEvent> = Sinks.many().multicast().onBackpressureBuffer()

    companion object {
        private const val BATCH_SIZE = 1000
    }

    /**
     * Trigger asynchronous materialized view refresh with concurrency control
     *
     * Creates a refresh job and executes refresh in background.
     * Returns immediately (non-blocking).
     *
     * **Concurrency Control (Performance Optimization):**
     * If a refresh job is already running, this method returns the existing job
     * instead of starting a new one. This prevents multiple concurrent refreshes
     * from wasting resources when triggered by batch operations (e.g., CLI imports).
     *
     * Task: T011
     * Spec reference: FR-005 (async refresh)
     *
     * @param triggeredBy Description of what triggered the refresh (for audit trail)
     * @return Either the newly created job, or the existing running job (if one is in progress)
     */
    fun triggerAsyncRefresh(triggeredBy: String): MaterializedViewRefreshJob {
        log.info("Triggering async refresh: triggeredBy={}", triggeredBy)

        // Concurrency control: Check if a refresh is already running
        val runningJob = getCurrentRunningJob()
        if (runningJob != null) {
            log.info("Skipping refresh trigger - job already running: jobId={}, triggeredBy={}, progress={}%",
                runningJob.id, runningJob.triggeredBy, runningJob.progressPercentage)
            return runningJob
        }

        // Create job entity
        val job = MaterializedViewRefreshJob(
            triggeredBy = triggeredBy,
            totalAssets = 0  // Will be calculated during refresh
        )
        val savedJob = refreshJobRepository.save(job)

        // Execute refresh asynchronously
        executeRefreshAsync(savedJob.id!!)

        return savedJob
    }

    /**
     * Execute refresh in background thread
     *
     * Task: T011
     * Spec reference: FR-005, FR-007, FR-021, FR-022
     */
    @Async
    open fun executeRefreshAsync(jobId: Long) {
        val job = refreshJobRepository.findById(jobId).orElseThrow()

        try {
            log.info("Starting async refresh job: jobId={}, triggeredBy={}", jobId, job.triggeredBy)

            executeRefresh(job)

            log.info("Async refresh job completed: jobId={}, durationMs={}", jobId, job.durationMs)
        } catch (e: Exception) {
            log.error("Async refresh job failed: jobId={}, error={}", jobId, e.message, e)
            job.markFailed(e.message ?: "Unknown error")
            refreshJobRepository.update(job)

            // Publish failure event
            publishProgressEvent(job, "Refresh failed: ${e.message}")
        }
    }

    /**
     * Execute materialized view refresh with progress tracking
     *
     * **Performance Optimization (Fix 3):**
     * Uses batch query to load all overdue vulnerabilities in ONE database call,
     * eliminating the N+1 query pattern where we previously queried per-asset.
     *
     * Task: T011, T060 (progress publishing), T061 (batch processing)
     * Spec reference: FR-005, FR-007
     *
     * Note: NOT @Transactional - uses separate short transactions for each operation
     * to avoid holding database locks during long-running refresh process
     */
    open fun executeRefresh(job: MaterializedViewRefreshJob) {
        val thresholdDays = vulnerabilityConfigService.getReminderOneDays()
        log.info("Executing refresh with threshold: {} days", thresholdDays)

        // Step 1: Clear old materialized view data in separate short transaction
        log.debug("Deleting old materialized view data")
        clearMaterializedView()

        // Step 2: Load ALL overdue vulnerabilities in ONE query (Fix 3 - eliminates N+1 pattern)
        val thresholdDate = LocalDateTime.now().minusDays(thresholdDays.toLong())
        log.debug("Fetching all vulnerabilities older than {} (threshold: {} days)", thresholdDate, thresholdDays)

        val allOverdueVulnerabilities = vulnerabilityRepository.findOverdueVulnerabilitiesWithAssets(thresholdDate)
        log.info("Loaded {} overdue vulnerabilities in single query", allOverdueVulnerabilities.size)

        // Step 3: Group vulnerabilities by asset and filter exceptions
        val vulnerabilitiesByAsset = allOverdueVulnerabilities.groupBy { it.asset }

        // Filter to assets with at least one non-excepted overdue vulnerability
        val assetsWithOverdueVulns = vulnerabilitiesByAsset.mapNotNull { (asset, vulns) ->
            val nonExceptedVulns = vulns.filter { vuln ->
                !vulnerabilityExceptionService.isVulnerabilityExcepted(vuln, asset)
            }
            if (nonExceptedVulns.isNotEmpty()) {
                asset to nonExceptedVulns
            } else {
                null
            }
        }.toMap()

        job.totalAssets = assetsWithOverdueVulns.size
        updateJob(job)

        log.info("Found {} assets with overdue vulnerabilities (after exception filtering)", assetsWithOverdueVulns.size)

        if (assetsWithOverdueVulns.isEmpty()) {
            job.markCompleted()
            updateJob(job)
            publishProgressEvent(job, "Refresh completed: no outdated assets")
            return
        }

        // Step 4: Process in batches with progress updates (each batch in separate transaction)
        val assetEntries = assetsWithOverdueVulns.entries.toList()
        assetEntries.chunked(BATCH_SIZE).forEachIndexed { batchIndex, batch ->
            val materializedRecords = batch.map { (asset, overdueVulns) ->
                createMaterializedRecordFromVulns(asset, overdueVulns)
            }

            // Save batch in separate transaction
            saveMaterializedRecordsBatch(materializedRecords)

            // Update progress
            val processed = ((batchIndex + 1) * BATCH_SIZE).coerceAtMost(assetsWithOverdueVulns.size)
            job.updateProgress(processed)
            updateJob(job)

            // Publish progress event
            publishProgressEvent(job, "Processing assets...")

            log.debug("Processed batch {}: {} assets", batchIndex + 1, processed)
        }

        // Step 5: Mark job as completed
        job.markCompleted()
        updateJob(job)

        // Publish completion event
        publishProgressEvent(job, "Refresh completed successfully")

        log.info("Refresh completed: jobId={}, assetsProcessed={}, durationMs={}",
            job.id, job.assetsProcessed, job.durationMs)
    }

    /**
     * Clear materialized view data in a separate short transaction
     *
     * This ensures the DELETE doesn't hold locks during the entire refresh process
     */
    @Transactional
    open fun clearMaterializedView() {
        outdatedAssetRepository.deleteAll()
    }

    /**
     * Save batch of materialized records in a separate transaction
     *
     * Each batch commits independently to avoid long-running transactions
     */
    @Transactional
    open fun saveMaterializedRecordsBatch(records: List<OutdatedAssetMaterializedView>) {
        outdatedAssetRepository.saveAll(records)
    }

    /**
     * Update job status in a separate transaction
     */
    @Transactional
    open fun updateJob(job: MaterializedViewRefreshJob) {
        refreshJobRepository.update(job)
    }

    /**
     * Create materialized record from pre-loaded vulnerability list
     *
     * **Performance Optimization (Fix 3):**
     * Takes pre-loaded vulnerabilities instead of querying per-asset,
     * eliminating N+1 database queries.
     *
     * Task: T011
     * Spec reference: data-model.md
     *
     * @param asset The asset entity (already loaded with workgroups)
     * @param overdueVulns Pre-filtered list of overdue, non-excepted vulnerabilities for this asset
     * @return OutdatedAssetMaterializedView record ready for persistence
     */
    private fun createMaterializedRecordFromVulns(
        asset: com.secman.domain.Asset,
        overdueVulns: List<com.secman.domain.Vulnerability>
    ): OutdatedAssetMaterializedView {
        // Calculate severity counts
        val criticalCount = overdueVulns.count { it.cvssSeverity == "CRITICAL" }
        val highCount = overdueVulns.count { it.cvssSeverity == "HIGH" }
        val mediumCount = overdueVulns.count { it.cvssSeverity == "MEDIUM" }
        val lowCount = overdueVulns.count { it.cvssSeverity == "LOW" }

        // Find oldest vulnerability
        val now = LocalDateTime.now()
        val oldestVuln = overdueVulns.maxByOrNull { vuln ->
            ChronoUnit.DAYS.between(vuln.scanTimestamp, now)
        }
        val oldestVulnDays = oldestVuln?.let {
            ChronoUnit.DAYS.between(it.scanTimestamp, now).toInt()
        } ?: 0

        // Get workgroup IDs (denormalized for performance) - already loaded via JOIN FETCH
        val workgroupIds = asset.workgroups?.joinToString(",") { it.id.toString() }

        return OutdatedAssetMaterializedView(
            assetId = asset.id!!,
            assetName = asset.name,
            assetType = asset.type ?: "UNKNOWN",
            totalOverdueCount = overdueVulns.size,
            criticalCount = criticalCount,
            highCount = highCount,
            mediumCount = mediumCount,
            lowCount = lowCount,
            oldestVulnDays = oldestVulnDays,
            oldestVulnId = oldestVuln?.vulnerabilityId,
            workgroupIds = workgroupIds,
            adDomain = asset.adDomain,
            lastCalculatedAt = now
        )
    }

    /**
     * Publish progress event for SSE streaming
     *
     * Task: T011, T060
     * Spec reference: FR-007, FR-022
     */
    private fun publishProgressEvent(job: MaterializedViewRefreshJob, message: String) {
        val event = RefreshProgressEvent(
            jobId = job.id!!,
            status = job.status,
            progressPercentage = job.progressPercentage,
            assetsProcessed = job.assetsProcessed,
            totalAssets = job.totalAssets,
            message = message
        )

        eventPublisher.publishEvent(event)

        // Also emit to SSE stream for real-time updates
        progressSink.tryEmitNext(event)

        log.debug("Published progress event: jobId={}, status={}, progress={}%",
            job.id, job.status, job.progressPercentage)
    }

    /**
     * Get SSE stream of refresh progress events
     *
     * Task: T050-T053
     * User Story: US3 - Manual Refresh
     *
     * @return Flux of progress events
     */
    fun getProgressStream(): Flux<RefreshProgressEvent> {
        return progressSink.asFlux()
    }

    /**
     * Get currently running refresh job (if any)
     *
     * Task: T054-T055
     * User Story: US3 - Manual Refresh
     *
     * @return Running job or null
     */
    fun getCurrentRunningJob(): MaterializedViewRefreshJob? {
        return refreshJobRepository.findRunningJob().orElse(null)
    }

    /**
     * Get recent refresh job history
     *
     * Task: T056-T057
     * User Story: US3 - Manual Refresh
     *
     * @param limit Maximum number of jobs to return
     * @return List of recent jobs, newest first
     */
    fun getRecentJobs(limit: Int = 10): List<MaterializedViewRefreshJob> {
        val pageable = Pageable.from(0, limit, Sort.of(Sort.Order.desc("startedAt")))
        return refreshJobRepository.findAll(pageable).content
    }
}
