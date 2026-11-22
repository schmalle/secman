package com.secman.service

import com.secman.constants.AssetOwners
import com.secman.domain.Asset
import com.secman.domain.CrowdStrikeAssetIdentity
import com.secman.repository.CrowdStrikeAssetIdentityRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class ImportContactScopeTest {
    @Test
    fun `fresh contact protects a zero finding device and only uniquely bound stale devices qualify`() {
        val cutoff = Instant.parse("2026-11-01T00:00:00Z")
        val identities = mockk<CrowdStrikeAssetIdentityRepository>()
        val scope = ImportContactScope(cutoff, mapOf("a" to cutoff.minusSeconds(1), "b" to cutoff,
            "c" to cutoff.plusSeconds(1)), identities)
        val asset = Asset(id = 1, name = "server", type = "SERVER", owner = AssetOwners.CROWDSTRIKE_IMPORT,
            crowdStrikeManaged = true)
        fun exclusion(vararg aids: String): String? {
            every { identities.findByAssetIdIn(listOf(1L)) } returns aids.map {
                CrowdStrikeAssetIdentity(asset = asset, crowdStrikeAid = it, sourceHostname = asset.name)
            }
            return scope.exclusion(asset, scope.contactsFor(listOf(1L)))
        }
        assertThat(exclusion("a")).isNull()
        assertThat(exclusion("b")).isEqualTo("RECENT_OR_FUTURE_SENSOR_CONTACT")
        assertThat(exclusion("c")).isEqualTo("RECENT_OR_FUTURE_SENSOR_CONTACT")
        assertThat(exclusion("unknown")).isEqualTo("OUTSIDE_IMPORT_SCOPE")
        assertThat(exclusion("a", "c")).isEqualTo("OUTSIDE_IMPORT_SCOPE")
    }
}
