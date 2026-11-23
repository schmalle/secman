package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.CrowdStrikeCleanupRun
import com.secman.domain.CrowdStrikeCleanupStatus
import com.secman.repository.CrowdStrikeAssetIdentityRepository
import com.secman.repository.CrowdStrikeCleanupRunRepository
import io.micronaut.context.annotation.Value
import io.micronaut.scheduling.annotation.Async
import jakarta.inject.Singleton
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** A unique current AID must match both at selection and under the cascade service's asset lock. */
class ImportContactScope(
    val cutoff: Instant,
    private val contacts: Map<String, Instant>,
    private val identities: CrowdStrikeAssetIdentityRepository
) {
    fun contactsFor(assetIds: List<Long>): Map<Long, Instant> {
        if (assetIds.isEmpty()) return emptyMap()
        return identities.findByAssetIdIn(assetIds).groupBy { requireNotNull(it.asset.id) }
            .mapNotNull { (id, bindings) ->
                bindings.singleOrNull()?.crowdStrikeAid?.let(contacts::get)?.let { id to it }
            }.toMap()
    }

    fun exclusion(asset: Asset, scopedContacts: Map<Long, Instant>): String? {
        if (!asset.crowdStrikeManaged || asset.owner != com.secman.constants.AssetOwners.CROWDSTRIKE_IMPORT ||
            asset.manualCreator != null || asset.scanUploader != null) return "PROTECTED_INVENTORY"
        val seen = scopedContacts[asset.id] ?: return "OUTSIDE_IMPORT_SCOPE"
        return if (seen < cutoff) null else "RECENT_OR_FUTURE_SENSOR_CONTACT"
    }
}

@Singleton
open class CrowdStrikePostImportCleanup(
    private val audit: CrowdStrikeCleanupAuditService,
    private val identities: CrowdStrikeAssetIdentityRepository,
    private val runs: CrowdStrikeCleanupRunRepository,
    private val lease: CrowdStrikeImportRunLease,
    @Value("\${secman.crowdstrike.cleanup.after-import:false}") val enabled: Boolean,
    @Value("\${secman.crowdstrike.cleanup.max-delete-percent:10}") private val maxDeletePercent: Int
) {
    private val days = 30

    @Async
    open fun runReserved(actor: String, contacts: Map<String, Instant>) {
        try {
            val cutoff = Instant.now().minus(Duration.ofDays(days.toLong()))
            audit.run(days, false, actor, maxDeletePercent, false, CrowdStrikeCleanupBasis.SENSOR_CONTACT,
                ImportContactScope(cutoff, contacts, identities))
        } finally {
            lease.finishCleanup()
        }
    }

    fun recordSkip(actor: String, reason: String) {
        val now = Instant.now()
        val cutoff = now.minus(Duration.ofDays(days.toLong()))
        runs.save(CrowdStrikeCleanupRun(status = CrowdStrikeCleanupStatus.ABORTED_IMPORT_HEALTH,
            triggeredBy = actor, staleDays = days, cutoff = LocalDateTime.ofInstant(cutoff, ZoneId.systemDefault()),
            cutoffUtc = cutoff, basis = CrowdStrikeCleanupBasis.SENSOR_CONTACT.value,
            completedAt = LocalDateTime.ofInstant(now, ZoneId.systemDefault()), errorMessage = reason))
    }
}
