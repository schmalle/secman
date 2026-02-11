package com.secman.service

import com.secman.domain.RequirementSnapshot
import com.secman.dto.*
import com.secman.repository.ReleaseRepository
import com.secman.repository.RequirementSnapshotRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

@Singleton
class RequirementComparisonService(
    private val releaseRepository: ReleaseRepository,
    private val snapshotRepository: RequirementSnapshotRepository
) {
    private val logger = LoggerFactory.getLogger(RequirementComparisonService::class.java)

    /**
     * Compare two releases and return differences
     *
     * @param fromReleaseId Baseline release ID
     * @param toReleaseId Comparison release ID
     * @return ComparisonResult with added, deleted, modified, and unchanged counts
     * @throws IllegalArgumentException if release IDs are the same
     * @throws NoSuchElementException if either release not found
     */
    fun compare(fromReleaseId: Long, toReleaseId: Long): ComparisonResult {
        logger.info("Comparing releases: fromReleaseId=$fromReleaseId, toReleaseId=$toReleaseId")

        // Validate IDs are different
        if (fromReleaseId == toReleaseId) {
            throw IllegalArgumentException("fromReleaseId and toReleaseId must be different")
        }

        // Load releases
        val fromRelease = releaseRepository.findById(fromReleaseId)
            .orElseThrow { NoSuchElementException("Release with ID $fromReleaseId not found") }

        val toRelease = releaseRepository.findById(toReleaseId)
            .orElseThrow { NoSuchElementException("Release with ID $toReleaseId not found") }

        // Load snapshots
        val fromSnapshots = snapshotRepository.findByReleaseId(fromReleaseId)
        val toSnapshots = snapshotRepository.findByReleaseId(toReleaseId)

        logger.debug("Loaded ${fromSnapshots.size} snapshots from release $fromReleaseId")
        logger.debug("Loaded ${toSnapshots.size} snapshots from release $toReleaseId")

        // Build maps by originalRequirementId for efficient lookup
        val fromMap = fromSnapshots.associateBy { it.originalRequirementId }
        val toMap = toSnapshots.associateBy { it.originalRequirementId }

        // Find added (in toMap but not in fromMap)
        val added = mutableListOf<RequirementSnapshotSummary>()
        // Find deleted (in fromMap but not in toMap)
        val deleted = mutableListOf<RequirementSnapshotSummary>()
        // Find modified (in both but with different fields)
        val modified = mutableListOf<RequirementDiff>()
        // Count unchanged
        var unchangedCount = 0

        // Check fromMap for deleted items
        for ((reqId, fromSnapshot) in fromMap) {
            if (!toMap.containsKey(reqId)) {
                deleted.add(toSnapshotSummary(fromSnapshot))
            }
        }

        // Check toMap for added/modified/unchanged items
        for ((reqId, toSnapshot) in toMap) {
            val fromSnapshot = fromMap[reqId]

            if (fromSnapshot == null) {
                // Added: in toMap but not in fromMap
                added.add(toSnapshotSummary(toSnapshot))
            } else {
                // Compare fields
                val changes = compareSnapshots(fromSnapshot, toSnapshot)

                if (changes.isEmpty()) {
                    unchangedCount++
                } else {
                    modified.add(
                        RequirementDiff(
                            id = toSnapshot.originalRequirementId,
                            originalRequirementId = toSnapshot.originalRequirementId,
                            internalId = toSnapshot.internalId,
                            oldRevision = fromSnapshot.revision,
                            newRevision = toSnapshot.revision,
                            shortreq = toSnapshot.shortreq,
                            chapter = toSnapshot.chapter,
                            norm = toSnapshot.norm,
                            changes = changes
                        )
                    )
                }
            }
        }

        logger.info(
            "Comparison complete: added=${added.size}, deleted=${deleted.size}, " +
                "modified=${modified.size}, unchanged=$unchangedCount"
        )

        return ComparisonResult(
            fromRelease = ReleaseInfo(
                id = fromRelease.id!!,
                version = fromRelease.version,
                name = fromRelease.name,
                createdAt = fromRelease.createdAt!!
            ),
            toRelease = ReleaseInfo(
                id = toRelease.id!!,
                version = toRelease.version,
                name = toRelease.name,
                createdAt = toRelease.createdAt!!
            ),
            added = added,
            deleted = deleted,
            modified = modified,
            unchanged = unchangedCount
        )
    }

    /**
     * Convert snapshot to summary DTO
     */
    private fun toSnapshotSummary(snapshot: RequirementSnapshot): RequirementSnapshotSummary {
        return RequirementSnapshotSummary(
            id = snapshot.id!!,
            originalRequirementId = snapshot.originalRequirementId,
            internalId = snapshot.internalId,
            revision = snapshot.revision,
            idRevision = snapshot.idRevision,
            shortreq = snapshot.shortreq,
            chapter = snapshot.chapter,
            norm = snapshot.norm,
            details = snapshot.details,
            motivation = snapshot.motivation,
            example = snapshot.example,
            usecase = snapshot.usecase,
            language = snapshot.language
        )
    }

    /**
     * Compare two snapshots and return field-level changes
     *
     * @return List of FieldChange objects (empty if identical)
     */
    private fun compareSnapshots(
        fromSnapshot: RequirementSnapshot,
        toSnapshot: RequirementSnapshot
    ): List<FieldChange> {
        val changes = mutableListOf<FieldChange>()

        // Compare each field
        compareField("shortreq", fromSnapshot.shortreq, toSnapshot.shortreq, changes)
        compareField("details", fromSnapshot.details, toSnapshot.details, changes)
        compareField("language", fromSnapshot.language, toSnapshot.language, changes)
        compareField("example", fromSnapshot.example, toSnapshot.example, changes)
        compareField("motivation", fromSnapshot.motivation, toSnapshot.motivation, changes)
        compareField("usecase", fromSnapshot.usecase, toSnapshot.usecase, changes)
        compareField("norm", fromSnapshot.norm, toSnapshot.norm, changes)
        compareField("chapter", fromSnapshot.chapter, toSnapshot.chapter, changes)

        return changes
    }

    /**
     * Compare a single field and add to changes if different
     */
    private fun compareField(
        fieldName: String,
        oldValue: String?,
        newValue: String?,
        changes: MutableList<FieldChange>
    ) {
        if (oldValue != newValue) {
            changes.add(
                FieldChange(
                    fieldName = fieldName,
                    oldValue = oldValue,
                    newValue = newValue
                )
            )
        }
    }
}
