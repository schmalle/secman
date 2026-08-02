package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.domain.Release
import com.secman.domain.Requirement
import com.secman.domain.RequirementSnapshot
import com.secman.repository.ReleaseRepository
import com.secman.repository.RequirementSnapshotRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Resolves "the current version of the security requirements", scoped by use case tag.
 *
 * In secman a *version* of the requirement corpus is a [Release]: creating one
 * freezes every requirement into `requirement_snapshot`, and exactly one release
 * is `ACTIVE` at a time (`ReleaseService.updateReleaseStatus` archives its
 * predecessor). The ACTIVE release is therefore the standard a newly started risk
 * assessment is measured against.
 *
 * Note that [Requirement.isCurrent] is NOT usable for this: nothing in the
 * codebase ever sets it to `false`, so the `findCurrent*` queries match every row
 * ever imported, including superseded ones. Releases are the real mechanism.
 *
 * Use-case matching is on the `requirement_usecase` tag id only — the same
 * relationship [com.secman.repository.RequirementRepository.findByUsecaseId]
 * uses for live requirements — so a release-scoped questionnaire and an unpinned
 * one select requirements by the same rule.
 */
@Singleton
open class ReleaseRequirementScopeService(
    private val releaseRepository: ReleaseRepository,
    private val snapshotRepository: RequirementSnapshotRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(ReleaseRequirementScopeService::class.java)

    /**
     * The single ACTIVE release, or null when none exists (fresh install, or every
     * release still in PREPARATION/ALIGNMENT). Same idiom as
     * [com.secman.service.AlignmentService]'s baseline selection.
     */
    open fun findActiveRelease(): Release? =
        releaseRepository.findByStatus(Release.ReleaseStatus.ACTIVE).firstOrNull()

    /**
     * The requirements of release [releaseId] tagged with use case [useCaseId],
     * rehydrated from the frozen snapshots and ordered like the live equivalent.
     */
    open fun requirementsForRelease(releaseId: Long, useCaseId: Long): List<Requirement> =
        snapshotRepository.findByReleaseId(releaseId)
            .filter { parseUseCaseIds(it.usecaseIdsSnapshot).contains(useCaseId) }
            .map { snapshotToRequirement(it) }
            .sortedWith(SNAPSHOT_ORDER)

    /** The union over several use cases, de-duplicated by requirement id. */
    open fun requirementsForRelease(releaseId: Long, useCaseIds: Collection<Long>): List<Requirement> {
        if (useCaseIds.isEmpty()) return emptyList()
        val wanted = useCaseIds.toSet()
        return snapshotRepository.findByReleaseId(releaseId)
            .filter { parseUseCaseIds(it.usecaseIdsSnapshot).any { id -> id in wanted } }
            .map { snapshotToRequirement(it) }
            .distinctBy { it.id }
            .sortedWith(SNAPSHOT_ORDER)
    }

    /**
     * Rehydrate a snapshot into a detached [Requirement].
     *
     * `id` is deliberately the ORIGINAL requirement id, not the snapshot id, so
     * `Response.requirement_id` foreign keys stay valid while the content and
     * revision shown come from the frozen snapshot.
     */
    open fun snapshotToRequirement(snapshot: RequirementSnapshot): Requirement {
        val requirement = Requirement(
            id = snapshot.originalRequirementId,
            internalId = snapshot.internalId,
            shortreq = snapshot.shortreq,
            details = snapshot.details,
            language = snapshot.language,
            example = snapshot.example,
            motivation = snapshot.motivation,
            usecase = snapshot.usecase,
            norm = snapshot.norm,
            chapter = snapshot.chapter,
            // Snapshots store the relationships as JSON id arrays, not objects.
            usecases = mutableSetOf(),
            norms = mutableSetOf()
        )
        requirement.versionNumber = snapshot.revision
        return requirement
    }

    /**
     * Parse the `[1,2,3]` JSON id array. Parsed rather than substring-matched: a
     * raw `contains("1")` would let use case 1 match a snapshot tagged `[11,12]`.
     */
    private fun parseUseCaseIds(json: String?): Set<Long> {
        if (json.isNullOrBlank()) return emptySet()
        return try {
            objectMapper.readValue(json, Array<Long>::class.java).toSet()
        } catch (e: Exception) {
            log.warn("Unparseable usecaseIdsSnapshot '{}': {}", json, e.message)
            emptySet()
        }
    }

    companion object {
        private val SNAPSHOT_ORDER =
            compareBy<Requirement>({ it.chapter ?: "" }, { it.id ?: 0 })
    }
}
