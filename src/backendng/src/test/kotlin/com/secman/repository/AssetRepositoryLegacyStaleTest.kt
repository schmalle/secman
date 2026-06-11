package com.secman.repository

import com.secman.constants.AssetOwners
import com.secman.domain.Asset
import com.secman.domain.User
import com.secman.testutil.BaseIntegrationTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Integration tests for the rule-B JPQL fence in
 * [AssetRepository.findLegacyCrowdStrikeStale]. Feature 087.
 *
 * Validates that the four-part fence + COALESCE staleness check correctly
 * include legacy CrowdStrike-origin rows and exclude every fence violation:
 * - manualCreator set → protected
 * - scanUploader set → protected
 * - non-CrowdStrike owner → protected
 * - non-stale (lastSeen newer than cutoff) → not selected
 * - lastSeen NULL but updatedAt old → still selected (COALESCE fall-through)
 *
 * These behaviours are NOT testable with mocked repositories — they depend
 * on JPQL semantics being honoured by Hibernate against MariaDB. Hence this
 * is an @MicronautTest against an external MariaDB (see BaseIntegrationTest).
 */
open class AssetRepositoryLegacyStaleTest : BaseIntegrationTest() {

    @Inject
    lateinit var assetRepository: AssetRepository

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var entityManager: EntityManager

    private val cutoff: LocalDateTime = LocalDateTime.of(2026, 4, 8, 0, 0)

    @AfterEach
    fun tearDown() {
        assetRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun `legacy row with all fence conditions met is selected`() {
        val saved = saveLegacy(name = "legacy-stale", lastSeen = cutoff.minusDays(10))

        val result = assetRepository.findLegacyCrowdStrikeStale(AssetOwners.CROWDSTRIKE_IMPORT, cutoff)

        assertThat(result.map { it.id }).containsExactly(saved.id)
    }

    @Test
    fun `legacy row with manualCreator set is excluded`() {
        val user = userRepository.save(User(username = "alice", email = "a@x", passwordHash = "h"))
        saveLegacy(name = "manual-claimed", lastSeen = cutoff.minusDays(10), manualCreator = user)

        val result = assetRepository.findLegacyCrowdStrikeStale(AssetOwners.CROWDSTRIKE_IMPORT, cutoff)

        assertThat(result).isEmpty()
    }

    @Test
    fun `legacy row with scanUploader set is excluded`() {
        val user = userRepository.save(User(username = "bob", email = "b@x", passwordHash = "h"))
        saveLegacy(name = "scan-claimed", lastSeen = cutoff.minusDays(10), scanUploader = user)

        val result = assetRepository.findLegacyCrowdStrikeStale(AssetOwners.CROWDSTRIKE_IMPORT, cutoff)

        assertThat(result).isEmpty()
    }

    @Test
    fun `legacy row with non-CrowdStrike owner is excluded`() {
        // Asset has the right shape (no import timestamp, no manual/scan
        // creator, stale lastSeen) but a different owner literal.
        assetRepository.save(
            Asset(
                name = "rogue-owner",
                type = "SERVER",
                owner = "alice",
                crowdStrikeLastImportedAt = null,
                lastSeen = cutoff.minusDays(10)
            )
        )

        val result = assetRepository.findLegacyCrowdStrikeStale(AssetOwners.CROWDSTRIKE_IMPORT, cutoff)

        assertThat(result).isEmpty()
    }

    @Test
    fun `legacy row that is not stale yet is excluded`() {
        saveLegacy(name = "fresh", lastSeen = cutoff.plusDays(5))

        val result = assetRepository.findLegacyCrowdStrikeStale(AssetOwners.CROWDSTRIKE_IMPORT, cutoff)

        assertThat(result).isEmpty()
    }

    @Test
    @Transactional
    open fun `COALESCE falls through to updatedAt when lastSeen is null`() {
        // Build asset with lastSeen=null. Hibernate's @PreUpdate hook would
        // reset updatedAt on any repository.update(), so we bypass it via a
        // native SQL UPDATE.
        val saved = saveLegacy(name = "lastSeen-null-updated-old", lastSeen = null)
        val oldUpdated = cutoff.minusDays(10)
        entityManager.createNativeQuery(
            "UPDATE asset SET last_seen = NULL, updated_at = :u WHERE id = :id"
        ).setParameter("u", oldUpdated)
            .setParameter("id", saved.id)
            .executeUpdate()
        entityManager.flush()
        entityManager.clear()

        val result = assetRepository.findLegacyCrowdStrikeStale(AssetOwners.CROWDSTRIKE_IMPORT, cutoff)

        assertThat(result.map { it.id }).contains(saved.id)
    }

    @Test
    fun `countLegacyCrowdStrikeTotal returns the population regardless of staleness`() {
        saveLegacy(name = "fresh", lastSeen = cutoff.plusDays(5))
        saveLegacy(name = "stale", lastSeen = cutoff.minusDays(10))
        // A row that violates the fence (manualCreator set) should not be counted.
        val user = userRepository.save(User(username = "carol", email = "c@x", passwordHash = "h"))
        saveLegacy(name = "claimed", lastSeen = cutoff.minusDays(10), manualCreator = user)

        val total = assetRepository.countLegacyCrowdStrikeTotal(AssetOwners.CROWDSTRIKE_IMPORT)

        assertThat(total).isEqualTo(2L)
    }

    private fun saveLegacy(
        name: String,
        lastSeen: LocalDateTime?,
        manualCreator: User? = null,
        scanUploader: User? = null
    ): Asset {
        return assetRepository.save(
            Asset(
                name = name,
                type = "SERVER",
                owner = AssetOwners.CROWDSTRIKE_IMPORT,
                crowdStrikeLastImportedAt = null,
                lastSeen = lastSeen,
                manualCreator = manualCreator,
                scanUploader = scanUploader
            )
        )
    }
}
