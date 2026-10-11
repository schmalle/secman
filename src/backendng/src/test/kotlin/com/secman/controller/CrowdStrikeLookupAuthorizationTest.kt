package com.secman.controller

import com.secman.domain.Asset
import com.secman.domain.CrowdStrikeAssetIdentity
import com.secman.dto.CrowdStrikeQueryResponse
import com.secman.repository.AssetRepository
import com.secman.repository.CrowdStrikeAssetIdentityRepository
import com.secman.service.AssetFilterService
import io.micronaut.http.HttpResponse
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class CrowdStrikeLookupAuthorizationTest {
    private val assets = mockk<AssetRepository>()
    private val access = mockk<AssetFilterService>()
    private val identities = mockk<CrowdStrikeAssetIdentityRepository>()
    private val asset = Asset(id = 7, name = "server.example.test", type = "SERVER", owner = "Human owner")
    private val viewer = Authentication.build("viewer", listOf("VULN"))
    private var queries = 0

    private fun controller(aid: String, instance: String? = null, account: String? = null, source: String = "LIVE_API", returnedAssetId: Long? = null, refreshedAssetId: Long? = null): CrowdStrikeController {
        every { access.canAccessAsset(7, viewer) } returns true
        every { assets.findById(7) } returns Optional.of(asset)
        every { identities.findByAssetIdIn(listOf(7L)) } returns listOf(
            CrowdStrikeAssetIdentity(asset = asset, crowdStrikeAid = "aid-granted", sourceHostname = asset.name))
        return object : CrowdStrikeController(mockk(), mockk(relaxed = true), mockk(), mockk(), mockk(), assets, access, identities) {
            override fun queryVulnerabilities(hostname: String?, severity: String?, product: String?, limit: Int?, page: Int?, force: Boolean): HttpResponse<*> {
                queries++
                return HttpResponse.ok(CrowdStrikeQueryResponse(asset.name, instanceId = instance, cloudAccountId = account,
                    vulnerabilities = emptyList(), totalCount = 0, queriedAt = "2026-10-10T00:00:00Z", dataSource = source, selectedAid = aid, assetId = if (queries > 1) refreshedAssetId ?: returnedAssetId else returnedAssetId))
            }
        }
    }

    @Test
    fun `reused hostname cannot disclose a different live enrollment to a scoped user`() {
        val result = controller("aid-unrelated").queryAuthorizedVulnerabilities(asset.name, force = true, assetId = 7, authentication = viewer)
        assertThat(result.code()).isEqualTo(404)
    }

    @Test
    fun `current bound enrollment remains available to a scoped user`() {
        val result = controller("aid-granted").queryAuthorizedVulnerabilities(asset.name, force = true, assetId = 7, authentication = viewer)
        assertThat(result.code()).isEqualTo(200)
    }

    @Test
    fun `same instance and account allow its newly enrolled device`() {
        asset.cloudInstanceId = "i-0123456789abcdef0"
        asset.cloudAccountId = "123456789012"
        val result = controller("aid-new", asset.cloudInstanceId, asset.cloudAccountId)
            .queryAuthorizedVulnerabilities(asset.name, force = true, assetId = 7, authentication = viewer)
        assertThat(result.code()).isEqualTo(200)
    }

    @Test
    fun `database resolution cannot substitute a different asset behind the same name`() {
        val result = controller("aid-granted", source = "DATABASE", returnedAssetId = 8)
            .queryAuthorizedVulnerabilities(asset.name, assetId = 7, authentication = viewer)
        assertThat(result.code()).isEqualTo(404)
    }

    @Test
    fun `stale cached asset identity is refreshed once before granting access`() {
        val result = controller("aid-granted", source = "DATABASE", returnedAssetId = 8, refreshedAssetId = 7)
            .queryAuthorizedVulnerabilities(asset.name, assetId = 7, authentication = viewer)
        assertThat(result.code()).isEqualTo(200)
        assertThat(queries).isEqualTo(2)
    }

    @Test
    fun `inaccessible asset cannot trigger a lookup`() {
        val controller = controller("aid-granted")
        every { access.canAccessAsset(7, viewer) } returns false
        assertThat(controller.queryAuthorizedVulnerabilities(asset.name, assetId = 7, authentication = viewer).code()).isEqualTo(404)
        assertThat(queries).isZero()
    }
}
