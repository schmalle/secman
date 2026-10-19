package com.secman.integration

import com.secman.domain.Asset
import com.secman.domain.UserMapping
import com.secman.domain.Vulnerability
import com.secman.repository.*
import com.secman.service.AssetFilterService
import com.secman.service.AssetOverviewQueryService
import com.secman.testutil.BaseIntegrationTest
import io.micronaut.data.model.Pageable
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class AssetCurrentnessIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var vulnerabilities: VulnerabilityRepository
    @Inject lateinit var mappings: UserMappingRepository
    @Inject lateinit var cache: VulnerabilityStatisticsCacheRepository
    @Inject lateinit var filter: AssetFilterService
    @Inject lateinit var overview: AssetOverviewQueryService
    @Inject lateinit var entityManager: EntityManager

    private fun now() = LocalDateTime.now(ZoneOffset.UTC)
    private fun save(name: String, seen: LocalDateTime?, falcon: Boolean = false): Asset = assets.save(
        Asset(name = name, type = "SERVER", owner = "ops", cloudAccountId = "123456789012").apply {
            if (falcon) {
                crowdStrikeHostname = name
                crowdStrikeAgentSeenAt = seen
                lastSeen = now()
                verifiedObservedAt = now()
            } else verifiedObservedAt = seen
        })
    private fun auth(role: String) = Authentication.build("reader", listOf(role),
        mapOf("userId" to 9001L, "email" to "reader@example.test"))

    @Test
    fun `current native and JPQL reads agree and raw lookup retains hidden records`() {
        val current = save("current", now().minusHours(1))
        val falcon = save("falcon", now().minusHours(2), true)
        val stale = save("stale", now().minusHours(73), true)
        save("future", now().plusHours(1))
        save("unknown", null)
        val expected = setOf(current.id, falcon.id)
        assertThat(assets.findCurrentIds().toSet()).isEqualTo(expected)
        assertThat(assets.findCurrent(Pageable.from(0, 1)).totalSize).isEqualTo(2)
        assertThat(assets.findCurrent().map { it.id }.toSet()).isEqualTo(expected)
        assertThat(assets.findById(stale.id!!)).isPresent()
        assertThat(assets.count()).isEqualTo(5)
        assertThat(assets.countCurrent()).isEqualTo(2)
        assertThat(assets.findByType("SERVER", Pageable.from(0, 10)).totalSize).isEqualTo(2)
    }

    @Test
    fun `all roles have current read scope but grants remain usable for re-observation`() {
        val current = save("current", now().minusHours(1))
        val stale = save("stale", now().minusHours(73))
        mappings.save(UserMapping(email = "reader@example.test", awsAccountId = "123456789012", domain = null))
        for (role in listOf("ADMIN", "SECCHAMPION", "USER")) {
            assertThat(filter.getAccessibleAssetIds(auth(role))).containsExactly(current.id!!)
            assertThat(filter.canAccessAsset(stale.id!!, auth(role))).isTrue()
            assertThat(filter.canReadCurrentAsset(stale.id!!, auth(role))).isFalse()
        }
        stale.recordObservation(now())
        assets.update(stale)
        entityManager.flush()
        assertThat(filter.getAccessibleAssetIds(auth("USER"))).contains(current.id!!, stale.id!!)
        assertThat(assets.count()).isEqualTo(2)
    }

    @Test
    fun `overview page count and vulnerability aggregates exclude stale assets`() {
        val current = save("current", now().minusHours(1))
        val stale = save("stale", now().minusHours(73))
        for (asset in listOf(current, stale)) vulnerabilities.save(Vulnerability(asset = asset,
            vulnerabilityId = "CVE-2026-1234", cvssSeverity = "High", scanTimestamp = now(), source = "CLI_MANUAL"))
        val page = overview.search(null, AssetOverviewQueryService.Filters(null, null, null, null, null, null), 0, 1)
        assertThat(page.total).isEqualTo(1)
        assertThat(page.assets.map { it.id }).containsExactly(current.id)
        assertThat(vulnerabilities.countCurrent()).isEqualTo(1)
        assertThat(vulnerabilities.findAll(Pageable.from(0, 10)).totalSize).isEqualTo(1)
        assertThat(vulnerabilities.findSeverityDistributionForAll().sumOf { it.count?.toLong() ?: 0L }).isEqualTo(1)
    }

    @Test
    fun `cache expires at earliest included asset expiry even without an import`() {
        val calculationTime = now()
        save("near-expiry", calculationTime.minusHours(72).plusMinutes(1))
        cache.upsertByCacheKey("currentness-test", "{}", calculationTime, 1)
        val entry = cache.findByCacheKey("currentness-test").orElseThrow()
        assertThat(entry.validUntil).isBefore(calculationTime.plusMinutes(2))
        entry.validUntil = calculationTime.minusSeconds(1)
        cache.update(entry)
        assertThat(cache.findByCacheKey("currentness-test")).isEmpty()
    }

    @Test
    fun `SQL boundary agrees with UTC inclusive policy`() {
        val at = now()
        val boundary = save("boundary", at.minusHours(72))
        val count = entityManager.createNativeQuery("SELECT COUNT(*) FROM asset WHERE id = :id AND " +
            "(${AssetCurrentnessSql.OBSERVED_AT}) BETWEEN :start AND :end")
            .setParameter("id", boundary.id).setParameter("start", at.minusHours(72))
            .setParameter("end", at).singleResult as Number
        assertThat(count.toLong()).isEqualTo(1)
    }
}
