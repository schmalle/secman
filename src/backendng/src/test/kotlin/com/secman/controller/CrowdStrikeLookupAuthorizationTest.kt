package com.secman.controller

import com.secman.domain.Asset
import com.secman.domain.CrowdStrikeAssetIdentity
import com.secman.dto.CrowdStrikeQueryResponse
import com.secman.repository.AssetRepository
import com.secman.repository.CrowdStrikeAssetIdentityRepository
import com.secman.service.AssetFilterService
import com.secman.service.CrowdStrikeQueryService
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class CrowdStrikeLookupAuthorizationTest {
    private val assets = mockk<AssetRepository>()
    private val access = mockk<AssetFilterService>()
    private val identities = mockk<CrowdStrikeAssetIdentityRepository>()
    private val queries = mockk<CrowdStrikeQueryService>()
    private val asset = Asset(id = 7, name = "ARCGIS", type = "SERVER", owner = "Human owner")
    private val viewer = Authentication.build("viewer", listOf("VULN"))
    private val controller = CrowdStrikeController(mockk(), queries, mockk(), mockk(), mockk(), assets, access, identities)

    private fun stub(aid: String = "aid-granted", source: String = "LIVE_API", id: Long = 7,
        observed: java.time.Instant? = java.time.Instant.now().minusSeconds(60)) {
        every { access.canAccessAsset(7, any()) } returns true
        every { assets.findById(7) } returns Optional.of(asset)
        every { identities.findByAssetIdIn(listOf(7L)) } returns listOf(
            CrowdStrikeAssetIdentity(asset = asset, crowdStrikeAid = "aid-granted", sourceHostname = asset.name))
        every { queries.queryAsset(asset, "aid-granted", any(), any(), any(), any(), any()) } returns
            CrowdStrikeQueryResponse(asset.name, observedAt = observed, vulnerabilities = emptyList(), totalCount = 0,
                queriedAt = "2026-10-10T00:00:00Z", dataSource = source, selectedAid = aid, assetId = id)
    }

    @Test
    fun `asset scoped query uses exact AID and cannot disclose another live enrollment`() {
        stub("aid-unrelated")
        assertThat(controller.queryAuthorizedVulnerabilities(asset.name, force = true, assetId = 7, authentication = viewer).code()).isEqualTo(404)
        verify { queries.queryAsset(asset, "aid-granted", true, null, null, 20000, 0) }
        verify(exactly = 0) { queries.queryVulnerabilities(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `bound enrollment remains available to viewer and admin even if another hostname match is newer`() {
        for (auth in listOf(viewer, Authentication.build("admin", listOf("ADMIN")))) {
            stub()
            val result = controller.queryAuthorizedVulnerabilities(asset.name, force = true, assetId = 7, authentication = auth)
            assertThat(result.code()).isEqualTo(200)
            assertThat((result.body() as CrowdStrikeQueryResponse).assetId).isEqualTo(7)
        }
    }

    @Test
    fun `cached and live lookups reject stale unknown and future observation`() {
        for (source in listOf("DATABASE", "LIVE_API")) {
            for (observed in listOf(null, java.time.Instant.now().minusSeconds(72 * 3600 + 60L), java.time.Instant.now().plusSeconds(3600))) {
                stub(source = source, observed = observed)
                assertThat(controller.queryAuthorizedVulnerabilities(asset.name, assetId = 7, authentication = viewer).code()).isEqualTo(404)
            }
        }
    }

    @Test
    fun `inaccessible asset cannot trigger a lookup`() {
        stub()
        every { access.canAccessAsset(7, viewer) } returns false
        assertThat(controller.queryAuthorizedVulnerabilities(asset.name, assetId = 7, authentication = viewer).code()).isEqualTo(404)
        verify(exactly = 0) { queries.queryAsset(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `read only preview retains separate bound and legacy assets`() {
        val legacy = asset.copy(id = 8)
        every { assets.findCrowdStrikeReplacementCandidates("ARCGIS", any()) } returns
            io.micronaut.data.model.Page.of(listOf(asset, legacy), io.micronaut.data.model.Pageable.from(0, 102), 2)
        every { identities.findByAssetIdIn(listOf(7L, 8L)) } returns listOf(
            CrowdStrikeAssetIdentity(asset = asset, crowdStrikeAid = "aid-granted", sourceHostname = "ARCGIS",
                falconFirstSeenAt = java.time.LocalDateTime.now().minusDays(30)))
        val response = controller.previewIdentities("ARCGIS")
        assertThat(response.code()).isEqualTo(200)
        assertThat(response.body().toString()).contains("BOUND_DEVICE_PRESERVE", "LEGACY_UNBOUND_REVIEW")
        verify(exactly = 0) { identities.delete(any()) }
    }
}
