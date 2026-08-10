package com.secman.service

import com.secman.domain.EolProduct
import com.secman.domain.EolSyncRun
import com.secman.dto.EolSyncRequest
import com.secman.dto.EolSyncResponse
import com.secman.repository.EolSyncRunRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Orchestrates the admin-triggered "download the catalogue, then re-match the
 * inventory" run and records it as an [EolSyncRun] audit row.
 *
 * Split from [EolCatalogSyncService] / [EolScanService] so those stay
 * independently testable and so the audit record is written exactly once per
 * user-visible action, with actor and outcome (§A09).
 */
@Singleton
open class EolAdminService(
    private val catalogSyncService: EolCatalogSyncService,
    private val scanService: EolScanService,
    private val eolSyncRunRepository: EolSyncRunRepository
) {
    private val log = LoggerFactory.getLogger(EolAdminService::class.java)

    open fun runSync(request: EolSyncRequest, triggeredBy: String): EolSyncResponse {
        val runId = UUID.randomUUID().toString()
        val actor = sanitizeActor(triggeredBy)
        val run = EolSyncRun(
            runId = runId,
            sourceKey = EolProduct.DEFAULT_SOURCE_KEY,
            triggeredBy = actor,
            status = "SUCCESS",
            startedAt = Instant.now()
        )

        var catalogResult: EolCatalogSyncService.CatalogSyncResult? = null
        var scanResult: EolScanService.ScanResult? = null
        val errors = mutableListOf<String>()

        if (!request.scanOnly) {
            catalogResult = try {
                catalogSyncService.sync(request.products)
            } catch (e: Exception) {
                log.error("EOL catalogue sync failed (runId={}, actor={})", runId, actor, e)
                errors += "Catalogue download failed"
                null
            }
            catalogResult?.errorSummary?.let { errors += it }
        }

        // A scan against an empty catalogue is a no-op that reports why, so it is
        // still worth running after a failed download.
        if (request.scan || request.scanOnly) {
            scanResult = try {
                scanService.scan(request.horizonMonths)
            } catch (e: Exception) {
                log.error("EOL scan failed (runId={}, actor={})", runId, actor, e)
                errors += "Matching scan failed"
                null
            }
            scanResult?.errorSummary?.let { errors += it }
        }

        val status = when {
            errors.isEmpty() -> "SUCCESS"
            catalogResult == null && scanResult == null -> "FAILED"
            else -> "PARTIAL"
        }

        run.status = status
        run.productsSynced = catalogResult?.productsSynced ?: 0
        run.releasesSynced = catalogResult?.releasesSynced ?: 0
        run.assetsScanned = scanResult?.assetsScanned ?: 0
        run.repositoriesScanned = scanResult?.repositoriesScanned ?: 0
        run.findingsWritten = scanResult?.findingsWritten ?: 0
        run.findingsRemoved = scanResult?.findingsRemoved ?: 0
        run.errorSummary = errors.joinToString("; ").takeIf { it.isNotEmpty() }?.take(1024)
        run.finishedAt = Instant.now()
        eolSyncRunRepository.save(run)

        log.info(
            "EOL sync run {} by {}: status={} products={} releases={} findings={} removed={}",
            runId, actor, status, run.productsSynced, run.releasesSynced, run.findingsWritten, run.findingsRemoved
        )

        return EolSyncResponse(
            runId = runId,
            status = status,
            productsRequested = catalogResult?.productsRequested ?: 0,
            productsSynced = catalogResult?.productsSynced ?: 0,
            releasesSynced = catalogResult?.releasesSynced ?: 0,
            productsFailed = catalogResult?.productsFailed ?: emptyList(),
            assetsScanned = scanResult?.assetsScanned ?: 0,
            repositoriesScanned = scanResult?.repositoriesScanned ?: 0,
            findingsWritten = scanResult?.findingsWritten ?: 0,
            eolFindings = scanResult?.eolFindings ?: 0,
            approachingFindings = scanResult?.approachingFindings ?: 0,
            findingsRemoved = scanResult?.findingsRemoved ?: 0,
            errorSummary = run.errorSummary
        )
    }

    /** The actor lands in a stored audit row and a log line — strip CR/LF (§A09). */
    private fun sanitizeActor(raw: String): String =
        raw.replace(Regex("[\\r\\n\\t]"), "_").trim().take(255).ifEmpty { "unknown" }
}
