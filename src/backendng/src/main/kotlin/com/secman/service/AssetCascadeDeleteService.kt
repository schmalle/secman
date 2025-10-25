package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.domain.Asset
import com.secman.domain.AssetDeletionAuditLog
import com.secman.domain.DeletionOperationType
import com.secman.domain.VulnerabilityException
import com.secman.dto.CascadeDeleteSummaryDto
import com.secman.dto.CascadeDeletionResultDto
import com.secman.dto.DeletionErrorDto
import com.secman.dto.DeletionErrorType
import com.secman.repository.*
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.PessimisticLockException
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Service for cascade deletion of assets with all related data
 * Feature: 033-cascade-asset-deletion
 *
 * Related Requirements:
 * - FR-001: System MUST cascade delete vulnerabilities when asset is deleted
 * - FR-002: System MUST cascade delete ASSET-type exceptions
 * - FR-003: System MUST cascade delete vulnerability exception requests
 * - FR-011: Use pessimistic row-level locking
 * - FR-012: Perform pre-flight count check with timeout estimation
 * - FR-013: Provide detailed structured error messages
 *
 * Deletion Order:
 * 1. VulnerabilityExceptionRequests (references vulnerabilities)
 * 2. VulnerabilityExceptions (ASSET-type only)
 * 3. Vulnerabilities (references asset)
 * 4. Asset
 *
 * Pessimistic Locking:
 * - Uses LockModeType.PESSIMISTIC_WRITE to prevent concurrent deletion
 * - Lock held throughout transaction
 * - Throws PessimisticLockException if asset already locked
 */
@Singleton
open class AssetCascadeDeleteService(
    private val entityManager: EntityManager,
    private val assetRepository: AssetRepository,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val exceptionRepository: VulnerabilityExceptionRepository,
    private val requestRepository: VulnerabilityExceptionRequestRepository,
    private val auditLogRepository: AssetDeletionAuditLogRepository,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(AssetCascadeDeleteService::class.java)

    companion object {
        // Benchmarked deletion rate: 100 records per second
        const val RECORDS_PER_SECOND = 100
        const val TIMEOUT_THRESHOLD_SECONDS = 60
    }

    /**
     * Exception for asset not found
     */
    class AssetNotFoundException(assetId: Long) : RuntimeException("Asset with ID $assetId not found")

    /**
     * Exception for timeout warning
     */
    class TimeoutWarningException(
        val assetId: Long,
        val assetName: String,
        val estimatedSeconds: Int
    ) : RuntimeException("Estimated deletion time ($estimatedSeconds seconds) exceeds timeout (${TIMEOUT_THRESHOLD_SECONDS} seconds)")

    /**
     * Get cascade deletion summary for pre-flight validation
     * FR-012: Pre-flight count check with timeout estimation
     *
     * @param assetId Asset ID to check
     * @return Summary with counts and timeout warning
     * @throws AssetNotFoundException if asset doesn't exist
     */
    fun getCascadeSummary(assetId: Long): CascadeDeleteSummaryDto {
        log.debug("Getting cascade summary for asset $assetId")

        val asset = assetRepository.findById(assetId).orElseThrow {
            AssetNotFoundException(assetId)
        }

        val vulnCount = vulnerabilityRepository.countByAssetId(assetId).toInt()
        val exceptionCount = exceptionRepository.findByExceptionTypeAndAssetId(
            VulnerabilityException.ExceptionType.ASSET,
            assetId
        ).size

        // Count exception requests by querying requests that reference this asset's vulnerabilities
        val requestCount = countExceptionRequestsByAssetId(assetId)

        val totalRecords = vulnCount + exceptionCount + requestCount
        val estimatedSeconds = (totalRecords / RECORDS_PER_SECOND) + 1

        log.info("Cascade summary for asset $assetId: $vulnCount vulns, $exceptionCount exceptions, $requestCount requests, estimated ${estimatedSeconds}s")

        return CascadeDeleteSummaryDto(
            assetId = assetId,
            assetName = asset.name,
            vulnerabilitiesCount = vulnCount,
            assetExceptionsCount = exceptionCount,
            exceptionRequestsCount = requestCount,
            estimatedDurationSeconds = estimatedSeconds,
            exceedsTimeout = estimatedSeconds > TIMEOUT_THRESHOLD_SECONDS
        )
    }

    /**
     * Delete asset with cascade deletion of all related data
     * FR-001, FR-002, FR-003, FR-011: Cascade delete with pessimistic locking
     *
     * @param assetId Asset ID to delete
     * @param username Username performing deletion
     * @param forceTimeout Whether to force deletion even if timeout warning
     * @param bulkOperationId Optional UUID for bulk operations
     * @return Result with counts and audit log ID
     * @throws AssetNotFoundException if asset doesn't exist
     * @throws PessimisticLockException if asset is locked by another transaction
     * @throws TimeoutWarningException if estimated time exceeds threshold and not forced
     */
    @Transactional
    open fun deleteAsset(
        assetId: Long,
        username: String,
        forceTimeout: Boolean = false,
        bulkOperationId: String? = null
    ): CascadeDeletionResultDto {
        log.info("Starting cascade deletion for asset $assetId by user $username (forceTimeout=$forceTimeout)")

        try {
            // Step 0: Pre-flight check for timeout warning (if not forced)
            if (!forceTimeout) {
                val summary = getCascadeSummary(assetId)
                if (summary.exceedsTimeout) {
                    log.warn("Deletion of asset $assetId would exceed timeout (${summary.estimatedDurationSeconds}s > ${TIMEOUT_THRESHOLD_SECONDS}s)")
                    throw TimeoutWarningException(assetId, summary.assetName, summary.estimatedDurationSeconds)
                }
            }

            // Step 1: Acquire pessimistic lock on asset
            // This blocks concurrent deletions and throws PessimisticLockException if already locked
            val asset = entityManager.find(
                Asset::class.java,
                assetId,
                LockModeType.PESSIMISTIC_WRITE
            ) ?: throw AssetNotFoundException(assetId)

            log.debug("Acquired pessimistic lock on asset $assetId")

            val assetName = asset.name

            // Step 2: Collect IDs before deletion (for audit log)
            val vulnIds = getVulnerabilityIdsByAssetId(assetId)
            val exceptionIds = getAssetExceptionIdsByAssetId(assetId)
            val requestIds = getExceptionRequestIdsByAssetId(assetId)

            log.debug("Collected IDs: ${vulnIds.size} vulns, ${exceptionIds.size} exceptions, ${requestIds.size} requests")

            // Step 3: Delete in dependency order

            // 3a: Delete exception requests
            if (requestIds.isNotEmpty()) {
                deleteExceptionRequestsByIds(requestIds)
                log.info("Deleted ${requestIds.size} exception requests for asset $assetId")
            }

            // 3b: Delete ASSET-type exceptions
            if (exceptionIds.isNotEmpty()) {
                deleteExceptionsByIds(exceptionIds)
                log.info("Deleted ${exceptionIds.size} ASSET-type exceptions for asset $assetId")
            }

            // 3c: Delete vulnerabilities (existing cascade from Asset should handle this, but explicit deletion for clarity)
            if (vulnIds.isNotEmpty()) {
                vulnerabilityRepository.deleteByAssetId(assetId)
                log.info("Deleted ${vulnIds.size} vulnerabilities for asset $assetId")
            }

            // 3d: Clear workgroup associations (ManyToMany join table must be cleared before asset deletion)
            // This removes entries from asset_workgroups join table to prevent FK constraint violations
            if (asset.workgroups.isNotEmpty()) {
                log.debug("Clearing ${asset.workgroups.size} workgroup associations for asset $assetId")
                asset.workgroups.clear()
                entityManager.flush() // Force immediate execution of join table DELETE statements
            }

            // 3e: Delete asset
            assetRepository.delete(asset)
            entityManager.flush() // Force immediate execution of DELETE to database
            log.info("Deleted asset $assetId")

            // Step 4: Create audit log
            val auditLog = createAuditLog(
                assetId = assetId,
                assetName = assetName,
                deletedByUser = username,
                vulnIds = vulnIds,
                exceptionIds = exceptionIds,
                requestIds = requestIds,
                operationType = if (bulkOperationId != null) DeletionOperationType.BULK else DeletionOperationType.SINGLE,
                bulkOperationId = bulkOperationId
            )

            val auditLogId = auditLog.id ?: throw IllegalStateException("Audit log ID is null after save")

            log.info("Created audit log $auditLogId for asset $assetId deletion")

            // Note: entityManager.clear() was removed - it was causing the deletion to be lost
            // The transaction will commit all changes when the method completes successfully

            return CascadeDeletionResultDto.success(
                assetId = assetId,
                assetName = assetName,
                vulnCount = vulnIds.size,
                exceptionCount = exceptionIds.size,
                requestCount = requestIds.size,
                auditId = auditLogId
            )

        } catch (e: PessimisticLockException) {
            log.error("Asset $assetId is locked by another transaction", e)
            throw e
        } catch (e: AssetNotFoundException) {
            log.error("Asset $assetId not found", e)
            throw e
        } catch (e: TimeoutWarningException) {
            log.warn("Asset $assetId deletion would exceed timeout", e)
            throw e
        } catch (e: Exception) {
            log.error("Failed to delete asset $assetId", e)
            throw e
        }
    }

    /**
     * Count exception requests for an asset (via vulnerability references)
     */
    private fun countExceptionRequestsByAssetId(assetId: Long): Int {
        // Use native query to count exception requests that reference this asset's vulnerabilities
        val count = entityManager.createQuery(
            """
            SELECT COUNT(r) FROM VulnerabilityExceptionRequest r
            WHERE r.vulnerability.asset.id = :assetId
            """, Long::class.java
        )
            .setParameter("assetId", assetId)
            .singleResult

        return count.toInt()
    }

    /**
     * Get vulnerability IDs for an asset
     */
    private fun getVulnerabilityIdsByAssetId(assetId: Long): List<Long> {
        return entityManager.createQuery(
            "SELECT v.id FROM Vulnerability v WHERE v.asset.id = :assetId",
            Long::class.java
        )
            .setParameter("assetId", assetId)
            .resultList
    }

    /**
     * Get ASSET-type exception IDs for an asset
     */
    private fun getAssetExceptionIdsByAssetId(assetId: Long): List<Long> {
        return exceptionRepository.findByExceptionTypeAndAssetId(
            VulnerabilityException.ExceptionType.ASSET,
            assetId
        ).mapNotNull { it.id }
    }

    /**
     * Get exception request IDs for an asset (via vulnerability references)
     */
    private fun getExceptionRequestIdsByAssetId(assetId: Long): List<Long> {
        return entityManager.createQuery(
            """
            SELECT r.id FROM VulnerabilityExceptionRequest r
            WHERE r.vulnerability.asset.id = :assetId
            """, Long::class.java
        )
            .setParameter("assetId", assetId)
            .resultList
    }

    /**
     * Delete exception requests by IDs
     */
    private fun deleteExceptionRequestsByIds(ids: List<Long>) {
        entityManager.createQuery(
            "DELETE FROM VulnerabilityExceptionRequest r WHERE r.id IN :ids"
        )
            .setParameter("ids", ids)
            .executeUpdate()
    }

    /**
     * Delete exceptions by IDs
     */
    private fun deleteExceptionsByIds(ids: List<Long>) {
        entityManager.createQuery(
            "DELETE FROM VulnerabilityException e WHERE e.id IN :ids"
        )
            .setParameter("ids", ids)
            .executeUpdate()
    }

    /**
     * Create audit log for deletion
     */
    private fun createAuditLog(
        assetId: Long,
        assetName: String,
        deletedByUser: String,
        vulnIds: List<Long>,
        exceptionIds: List<Long>,
        requestIds: List<Long>,
        operationType: DeletionOperationType,
        bulkOperationId: String?
    ): AssetDeletionAuditLog {
        val auditLog = AssetDeletionAuditLog(
            assetId = assetId,
            assetName = assetName,
            deletedByUser = deletedByUser,
            deletionTimestamp = LocalDateTime.now(),
            vulnerabilitiesCount = vulnIds.size,
            assetExceptionsCount = exceptionIds.size,
            exceptionRequestsCount = requestIds.size,
            deletedVulnerabilityIds = objectMapper.writeValueAsString(vulnIds),
            deletedExceptionIds = objectMapper.writeValueAsString(exceptionIds),
            deletedRequestIds = objectMapper.writeValueAsString(requestIds),
            operationType = operationType,
            bulkOperationId = bulkOperationId
        )

        return auditLogRepository.save(auditLog)
    }

    /**
     * Build error DTO for locked asset
     */
    fun buildLockedErrorDto(assetId: Long, assetName: String, cause: String): DeletionErrorDto {
        return DeletionErrorDto(
            errorType = DeletionErrorType.LOCKED,
            assetId = assetId,
            assetName = assetName,
            cause = cause,
            suggestedAction = "Wait a few moments and try again. The asset is currently being deleted by another user.",
            technicalDetails = "PessimisticLockException: Row locked by another transaction"
        )
    }

    /**
     * Build error DTO for timeout warning
     */
    fun buildTimeoutWarningDto(assetId: Long, assetName: String, estimatedSeconds: Int): DeletionErrorDto {
        return DeletionErrorDto(
            errorType = DeletionErrorType.TIMEOUT_WARNING,
            assetId = assetId,
            assetName = assetName,
            cause = "Estimated deletion time ($estimatedSeconds seconds) exceeds transaction timeout ($TIMEOUT_THRESHOLD_SECONDS seconds)",
            suggestedAction = "Contact system administrator to delete in smaller batches or increase timeout. Alternatively, use forceTimeout=true to attempt deletion anyway.",
            technicalDetails = "Estimated records: ${estimatedSeconds * RECORDS_PER_SECOND}, benchmark: $RECORDS_PER_SECOND records/sec"
        )
    }

    /**
     * Build error DTO for internal error
     */
    fun buildInternalErrorDto(assetId: Long, assetName: String, cause: String, technicalDetails: String?): DeletionErrorDto {
        return DeletionErrorDto(
            errorType = DeletionErrorType.INTERNAL_ERROR,
            assetId = assetId,
            assetName = assetName,
            cause = cause,
            suggestedAction = "Contact system administrator. Check application logs for details.",
            technicalDetails = technicalDetails
        )
    }
}
