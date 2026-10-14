package com.secman.service

import com.secman.constants.AssetOwners
import com.secman.domain.Asset
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Actual observation evidence, never inventory processing time. */
object AssetCurrentnessPolicy {
    fun observedAt(asset: Asset): LocalDateTime? =
        if (asset.crowdStrikeManaged || asset.crowdStrikeLastImportedAt != null || asset.crowdStrikeHostname != null ||
            asset.crowdStrikeAgentSeenAt != null || asset.owner == AssetOwners.CROWDSTRIKE_IMPORT)
            asset.crowdStrikeAgentSeenAt else asset.verifiedObservedAt

    fun isCurrent(asset: Asset, clock: Clock = Clock.systemUTC()): Boolean {
        return includes(observedAt(asset), clock)
    }

    fun includes(seenAt: LocalDateTime?, clock: Clock = Clock.systemUTC()): Boolean {
        val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val seen = seenAt ?: return false
        return !seen.isBefore(now.minusHours(72)) && !seen.isAfter(now)
    }
}
