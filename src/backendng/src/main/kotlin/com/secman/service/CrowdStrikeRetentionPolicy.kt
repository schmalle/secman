package com.secman.service

import com.secman.constants.AssetOwners
import com.secman.domain.Asset
import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton
import java.time.Instant
import java.time.LocalDateTime

/** Import time measures ingestion; Falcon contact measures sensor activity. */
enum class CrowdStrikeCleanupBasis(val value: String) {
    IMPORT_INACTIVITY("import-inactivity"),
    SENSOR_CONTACT("sensor-contact");

    companion object {
        fun parse(value: String): CrowdStrikeCleanupBasis = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("basis must be import-inactivity or sensor-contact")
    }
}

@Singleton
class CrowdStrikeRetentionPolicy(
    @Value("\${secman.crowdstrike.sensor-retention-days:0}") val sensorRetentionDays: Int = 0
) {
    init { require(sensorRetentionDays in 0..36500) { "sensor-retention-days must be between 0 and 36500" } }

    fun acceptsImport(contact: Instant?, now: Instant = Instant.now()): Boolean =
        sensorRetentionDays == 0 || (contact != null && !contact.isAfter(now) &&
            !contact.isBefore(now.minusSeconds(sensorRetentionDays.toLong() * 86400)))

    /** Also used after acquiring the deletion lock, to avoid deleting a refreshed asset. */
    fun exclusion(asset: Asset, basis: CrowdStrikeCleanupBasis, includeLegacy: Boolean,
                  cutoff: LocalDateTime, agentCutoff: LocalDateTime): String? {
        if (asset.owner != AssetOwners.CROWDSTRIKE_IMPORT || asset.manualCreator != null || asset.scanUploader != null)
            return "PROTECTED_INVENTORY"
        val contact = asset.crowdStrikeAgentSeenAt
        if (basis == CrowdStrikeCleanupBasis.SENSOR_CONTACT) {
            if (contact == null) return "UNKNOWN_SENSOR_CONTACT"
            return if (contact < agentCutoff) null else "RECENT_OR_FUTURE_SENSOR_CONTACT"
        }
        if (contact != null && contact >= agentCutoff) return "RECENT_OR_FUTURE_SENSOR_CONTACT"
        val imported = asset.crowdStrikeLastImportedAt
        if (imported != null) return if (imported < cutoff) null else "RECENT_IMPORT"
        if (!includeLegacy) return "LEGACY_DISABLED"
        return if ((asset.lastSeen ?: asset.updatedAt ?: asset.createdAt)?.let { it < cutoff } == true)
            null else "RECENT_LEGACY_INVENTORY"
    }
}
