package com.secman.integration

import com.secman.domain.Asset
import com.secman.repository.AssetRepository
import com.secman.testutil.BaseIntegrationTest
import io.micronaut.data.model.Pageable
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The identity finders compare with plain `=` and rely on the general_ci collation for case
 * folding, so their indexes stay usable (V296). These cases pin the case-insensitive contract the
 * former LOWER() wrappers provided.
 */
class AssetIdentityLookupIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var assetRepository: AssetRepository
    @Inject lateinit var entityManager: EntityManager

    @Test
    fun `cloud instance lookups ignore case`() {
        val suffix = System.nanoTime().toString(16)
        val asset = save(Asset(name = "inst-$suffix", type = "SERVER", owner = "test", cloudInstanceId = "I-ABC$suffix"))

        assertThat(assetRepository.findByCloudInstanceIdIgnoreCase("i-abc$suffix")?.id).isEqualTo(asset.id)
        assertThat(assetRepository.findByNormalizedCloudInstanceIdIn(listOf("i-abc$suffix")).map { it.id })
            .containsExactly(asset.id)
    }

    @Test
    fun `name lookups ignore case and match fully qualified names by prefix`() {
        val suffix = System.nanoTime().toString(16)
        val asset = save(Asset(name = "Web-$suffix.corp.example", type = "SERVER", owner = "test"))

        assertThat(assetRepository.findByNameIgnoreCase("WEB-$suffix.CORP.EXAMPLE")?.id).isEqualTo(asset.id)
        assertThat(assetRepository.findPotentialDuplicates("web-$suffix").map { it.id }).containsExactly(asset.id)
    }

    @Test
    fun `crowdstrike matches prefer the source hostname and fall back to the display name`() {
        val suffix = System.nanoTime().toString(16)
        val sourced = save(Asset(name = "renamed-$suffix", type = "SERVER", owner = "test",
            crowdStrikeHostname = "Falcon-$suffix.corp"))
        val legacy = save(Asset(name = "Legacy-$suffix", type = "SERVER", owner = "test"))

        assertThat(assetRepository.findPotentialCrowdStrikeMatches("FALCON-$suffix").map { it.id })
            .containsExactly(sourced.id)
        assertThat(assetRepository.findPotentialCrowdStrikeMatches("renamed-$suffix")).isEmpty()
        assertThat(assetRepository.findCrowdStrikeReplacementCandidates("legacy-$suffix", Pageable.from(0, 10))
            .content.map { it.id }).containsExactly(legacy.id)
        assertThat(assetRepository.findStoredByExactHostnames(listOf("falcon-$suffix.corp"), Pageable.from(0, 10))
            .content.map { it.id }).containsExactly(sourced.id)
        assertThat(assetRepository.findByCrowdStrikeHostnameIgnoreCase("FALCON-$suffix.CORP")?.id).isEqualTo(sourced.id)
    }

    private fun save(asset: Asset): Asset = assetRepository.save(asset).also {
        entityManager.flush()
        entityManager.clear()
    }
}
