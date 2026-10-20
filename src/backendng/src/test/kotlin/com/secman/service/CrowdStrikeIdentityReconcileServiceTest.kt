package com.secman.service

import com.secman.crowdstrike.client.CrowdStrikeApiClient
import com.secman.crowdstrike.client.QueriedHost
import com.secman.crowdstrike.dto.*
import com.secman.domain.Asset
import com.secman.domain.CrowdStrikeAssetIdentity
import com.secman.dto.CrowdStrikeIdentityReconcileRequest
import com.secman.dto.CrowdStrikeIdentityReconcileResponse
import com.secman.repository.AssetRepository
import com.secman.repository.CrowdStrikeAssetIdentityRepository
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.util.Optional

class CrowdStrikeIdentityReconcileServiceTest {
    private val client = mockk<CrowdStrikeApiClient>()
    private val queries = mockk<CrowdStrikeQueryService>(relaxed = true)
    private val assets = mockk<AssetRepository>()
    private val identities = mockk<CrowdStrikeAssetIdentityRepository>()
    private val access = mockk<AssetFilterService>()
    private val writer = mockk<CrowdStrikeIdentityRepairWriter>()
    private val refresh = mockk<MaterializedViewRefreshService>(relaxed = true)
    private val service = CrowdStrikeIdentityReconcileService(client, queries, assets, identities, access, writer,
        CrowdStrikeImportRunLease(), CrowdStrikeRetentionPolicy(), refresh)
    private val request = CrowdStrikeIdentityReconcileRequest(1, setOf("aid-a", "aid-b"), "Verified independent instances")
    private val admin = Authentication.build("admin", listOf("ADMIN"))
    private val asset = Asset(id = 1L, name = "ARCGIS", owner = "Human owner", type = "SERVER",
        cloudAccountId = "123456789012", cloudInstanceId = "i-0123456789abcdef0")
    private val a = CrowdStrikeDeviceRecord("aid-a", "ARCGIS", asset.cloudInstanceId, asset.cloudAccountId,
        firstSeen = Instant.parse("2026-01-01T00:00:00Z"), lastSeen = Instant.now().minusSeconds(30))
    private val b = a.copy(aid = "aid-b", instanceId = "i-0123456789abcdef1")

    private fun response(device: CrowdStrikeDeviceRecord) = CrowdStrikeQueryResponse("ARCGIS",
        devices = setOf(QueriedHost("ARCGIS", device.instanceId, device.aid, device.cloudAccountId,
            deviceSelection = CrowdStrikeDeviceSelection(device))), vulnerabilities = emptyList(), totalCount = 0, queriedAt = LocalDateTime.now())

    private fun stub() {
        every { access.canAccessAsset(1, admin) } returns true
        every { assets.findById(1) } returns Optional.of(asset)
        every { identities.findByAssetIdIn(listOf(1L)) } returns listOf(a, b).map {
            CrowdStrikeAssetIdentity(asset = asset, crowdStrikeAid = it.aid, sourceHostname = "ARCGIS")
        }
        every { client.queryDeviceVulnerabilities(null, "aid-a", false, any()) } returns response(a)
        every { client.queryDeviceVulnerabilities(null, "aid-b", false, any()) } returns response(b)
        every { client.resolveDeviceRecords(request.expectedAids, any()) } returns listOf(a, b)
        every { writer.preview(any(), any(), any()) } returns CrowdStrikeIdentityReconcileResponse(1, true, emptyList())
    }

    @Test
    fun `legacy sources with 26 and 100 bindings can be previewed without downloading findings`() {
        stub()
        for (size in listOf(26, 100)) {
            val records = (0 until size).map { index -> a.copy(aid = "aid-$index",
                instanceId = if (index == 0) a.instanceId else "i-${index.toString(16).padStart(17, '0')}") }
            val large = request.copy(expectedAids = records.map { it.aid }.toSet())
            every { identities.findByAssetIdIn(listOf(1L)) } returns records.map {
                CrowdStrikeAssetIdentity(asset = asset, crowdStrikeAid = it.aid, sourceHostname = it.hostname)
            }
            every { client.resolveDeviceRecords(large.expectedAids, any()) } returns records
            every { writer.preview(large, any(), any()) } answers {
                assertThat(secondArg<List<com.secman.dto.CrowdStrikeVulnerabilityBatchDto>>()).hasSize(size)
                CrowdStrikeIdentityReconcileResponse(1, true, emptyList())
            }
            assertThat(service.reconcile(large, admin).dryRun).isTrue()
        }
        verify(exactly = 0) { writer.repair(any(), any(), any(), any()) }
        verify(exactly = 0) { client.queryDeviceVulnerabilities(any(), any(), any(), any()) }
    }

    @Test
    fun `more than 100 bindings fail before provider or persistence access`() {
        assertThatThrownBy { service.reconcile(request.copy(expectedAids = (0..100).map { "aid-$it" }.toSet()), admin) }
            .isInstanceOf(IllegalArgumentException::class.java)
        verify { client wasNot Called }
        verify { writer wasNot Called }
        verify { assets wasNot Called }
    }

    @Test
    fun `ADMIN and asset authorization are enforced before provider access`() {
        assertThatThrownBy { service.reconcile(request, Authentication.build("viewer", listOf("VULN"))) }
            .isInstanceOf(HttpStatusException::class.java)
        stub()
        every { access.canAccessAsset(1, admin) } returns false
        assertThatThrownBy { service.reconcile(request, admin) }.isInstanceOf(HttpStatusException::class.java)
        verify { client wasNot Called }
        verify { writer wasNot Called }
    }

    @Test
    fun `preview is default and never invokes repair or refresh`() {
        stub()
        every { writer.preview(any(), any(), any()) } answers {
            assertThat(secondArg<List<com.secman.dto.CrowdStrikeVulnerabilityBatchDto>>()).hasSize(2)
            CrowdStrikeIdentityReconcileResponse(1, true, emptyList())
        }
        assertThat(service.reconcile(request, admin).dryRun).isTrue()
        verify(exactly = 0) { writer.repair(any(), any(), any(), any()) }
        verify(exactly = 0) { refresh.requestDeferredRefresh(any()) }
        verify(exactly = 0) { client.queryDeviceVulnerabilities(any(), any(), any(), any()) }
    }

    @Test
    fun `incomplete provider fetch and cross account evidence preserve all data`() {
        stub()
        every { client.queryDeviceVulnerabilities(null, "aid-b", false, any()) } returns response(b).copy(failedAids = setOf("aid-b"))
        assertThatThrownBy { service.reconcile(request.copy(dryRun = false), admin) }.isInstanceOf(IllegalArgumentException::class.java)
        every { client.queryDeviceVulnerabilities(null, "aid-b", false, any()) } returns response(b.copy(cloudAccountId = "999999999999"))
        assertThatThrownBy { service.reconcile(request.copy(dryRun = false), admin) }.isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 0) { writer.repair(any(), any(), any(), any()) }
    }

    @Test
    fun `same instance missing enrollment and future contact cannot authorize a split`() {
        stub()
        listOf(b.copy(instanceId = a.instanceId), b.copy(firstSeen = null), b.copy(lastSeen = Instant.now().plusSeconds(600))).forEach { bad ->
            every { client.resolveDeviceRecords(request.expectedAids, any()) } returns listOf(a, bad)
            assertThatThrownBy { service.reconcile(request, admin) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        verify { writer wasNot Called }
        verify(exactly = 0) { client.queryDeviceVulnerabilities(any(), any(), any(), any()) }
    }

    @Test
    fun `expected bindings and verified legacy state are mandatory`() {
        stub()
        assertThatThrownBy { service.reconcile(request.copy(expectedAids = setOf("aid-a", "other")), admin) }
            .isInstanceOf(IllegalArgumentException::class.java)
        every { identities.findByAssetIdIn(listOf(1L)) } returns listOf(a, b).map {
            CrowdStrikeAssetIdentity(asset = asset, crowdStrikeAid = it.aid, sourceHostname = "ARCGIS", falconFirstSeenAt = LocalDateTime.now())
        }
        assertThatThrownBy { service.reconcile(request, admin) }.isInstanceOf(IllegalArgumentException::class.java)
        verify { client wasNot Called }
    }

    @Test
    fun `commit invokes atomic writer then invalidates derived data`() {
        stub()
        every { writer.repair(any(), any(), any(), "admin") } returns CrowdStrikeIdentityReconcileResponse(1, false, emptyList())
        assertThat(service.reconcile(request.copy(dryRun = false), admin).dryRun).isFalse()
        verifyOrder {
            writer.repair(any(), any(), any(), "admin")
            queries.invalidateAllCachedQueries()
            refresh.requestDeferredRefresh(any())
        }
    }

    @Test
    fun `concurrently resolved source is reported without a provider fetch`() {
        stub()
        every { identities.findByAssetIdIn(listOf(1L)) } returns listOf(CrowdStrikeAssetIdentity(asset = asset,
            crowdStrikeAid = a.aid, sourceHostname = "ARCGIS", falconFirstSeenAt = LocalDateTime.now(),
            falconLastSeenAt = LocalDateTime.now(), identityObservedAt = LocalDateTime.now()))
        assertThatThrownBy { service.reconcile(request, admin) }.isInstanceOfSatisfying(CrowdStrikeIdentityConflict::class.java) {
            assertThat(it.code).isEqualTo("ALREADY_RESOLVED")
        }
        verify { client wasNot Called }
        verify { writer wasNot Called }
    }

    @Test
    fun `metadata failures expose specific AID and reason before any findings download`() {
        stub()
        val cases = listOf(
            b.copy(instanceId = null) to "DEVICE_INSTANCE_MISSING",
            b.copy(cloudAccountId = null) to "DEVICE_ACCOUNT_MISSING",
            b.copy(cloudAccountId = "999999999999") to "CLOUD_ACCOUNT_CONFLICT",
            b.copy(instanceId = a.instanceId) to "SAME_INSTANCE_ENROLLMENTS",
            b.copy(firstSeen = null) to "ENROLLMENT_TIMESTAMP_MISSING",
            b.copy(lastSeen = null) to "CONTACT_TIMESTAMP_INVALID")
        cases.forEach { (device, code) ->
            every { client.resolveDeviceRecords(request.expectedAids, any()) } returns listOf(a, device)
            assertThatThrownBy { service.reconcile(request, admin) }.isInstanceOfSatisfying(CrowdStrikeIdentityConflict::class.java) {
                assertThat(it.code).isEqualTo(code)
                if (code != "SAME_INSTANCE_ENROLLMENTS") assertThat(it.aid).isEqualTo("aid-b")
            }
        }
        verify(exactly = 0) { client.queryDeviceVulnerabilities(any(), any(), any(), any()) }
        verify { writer wasNot Called }
    }

    @Test
    fun `preview marks finding counts unknown and never fabricates a clean snapshot`() {
        stub()
        every { writer.preview(any(), any(), any()) } returns CrowdStrikeIdentityReconcileResponse(1, true, listOf(
            com.secman.dto.CrowdStrikeReconciledDevice("aid-a", a.instanceId!!, a.cloudAccountId!!, 1, 0)))
        val result = service.reconcile(request, admin)
        assertThat(result.findingsFetched).isFalse()
        assertThat(result.devices.single().findings).isNull()
        verify(exactly = 0) { client.queryDeviceVulnerabilities(any(), any(), any(), any()) }
    }

    @Test
    fun `apply refuses identities changed after metadata classification`() {
        stub()
        every { client.queryDeviceVulnerabilities(null, "aid-b", false, any()) } returns response(b.copy(firstSeen = b.firstSeen!!.plusSeconds(1)))
        assertThatThrownBy { service.reconcile(request.copy(dryRun = false), admin) }
            .isInstanceOfSatisfying(CrowdStrikeIdentityConflict::class.java) { assertThat(it.code).isEqualTo("IDENTITY_CHANGED_DURING_FETCH") }
        verify(exactly = 0) { writer.repair(any(), any(), any(), any()) }
    }
}
