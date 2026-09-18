package com.secman.service

import com.secman.domain.WorkgroupAccessChangedEvent
import com.secman.repository.AppSettingsRepository
import com.secman.repository.WorkgroupRepository
import io.micronaut.context.event.ApplicationEventPublisher
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory

/** Enforces the fail-closed user-count limit for catch-all workgroups. */
@Singleton
open class CatchAllWorkgroupSafetyService(
    private val appSettingsRepository: AppSettingsRepository,
    private val workgroupRepository: WorkgroupRepository,
    private val workgroupAccessChangedPublisher: ApplicationEventPublisher<WorkgroupAccessChangedEvent>
) {
    private val logger = LoggerFactory.getLogger(CatchAllWorkgroupSafetyService::class.java)

    companion object {
        const val DEFAULT_THRESHOLD = 100
        const val MIN_THRESHOLD = 1
        const val MAX_THRESHOLD = 1_000_000
    }

    @Transactional
    open fun enforceAffected(workgroupIds: Collection<Long>, actor: String = "system"): Set<Long> {
        if (workgroupIds.isEmpty()) return emptySet()
        val threshold = currentThreshold()
        validateThreshold(threshold)
        val counts = workgroupIds.toSet().associateWith(workgroupRepository::countUsersByWorkgroupId)
        return disableQualifying(counts, threshold, actor)
    }

    @Transactional
    open fun enforceAll(threshold: Int = currentThreshold(), actor: String = "system"): Set<Long> {
        validateThreshold(threshold)
        val counts = workgroupRepository.countUsersPerWorkgroup().associate { row ->
            (row[0] as Number).toLong() to (row[1] as Number).toLong()
        }
        return disableQualifying(counts, threshold, actor)
    }

    open fun validateThreshold(threshold: Int) {
        require(threshold in MIN_THRESHOLD..MAX_THRESHOLD) {
            "Catch-all workgroup user threshold must be between $MIN_THRESHOLD and $MAX_THRESHOLD"
        }
    }

    private fun currentThreshold(): Int =
        appSettingsRepository.findFirstSettings().orElse(null)?.catchAllWorkgroupUserThreshold ?: DEFAULT_THRESHOLD

    /** Shared by import previews and writes; never bypass the configured safety limit. */
    open fun exceedsMembershipLimit(userCount: Long): Boolean {
        val threshold = currentThreshold()
        validateThreshold(threshold)
        return userCount >= threshold
    }

    private fun disableQualifying(userCounts: Map<Long, Long>, threshold: Int, actor: String): Set<Long> {
        val disabledIds = userCounts.mapNotNullTo(mutableSetOf()) { (id, count) ->
            if (count < threshold) return@mapNotNullTo null

            val workgroup = workgroupRepository.findById(id).orElse(null) ?: return@mapNotNullTo null
            if (!workgroup.enabled) return@mapNotNullTo null

            workgroup.enabled = false
            workgroupRepository.update(workgroup)
            logger.warn(
                "Catch-all workgroup safety applied: actor={} targetWorkgroupId={} userCount={} threshold={} outcome=disabled",
                actor,
                id,
                count,
                threshold
            )
            id
        }

        if (disabledIds.isNotEmpty()) {
            workgroupAccessChangedPublisher.publishEvent(WorkgroupAccessChangedEvent(disabledIds))
        }
        return disabledIds
    }
}
