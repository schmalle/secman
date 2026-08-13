package com.secman.mcp.tools

import com.secman.domain.Asset
import com.secman.domain.Scan
import com.secman.domain.ScanPort
import com.secman.domain.ScanResult
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.ScanPortRepository
import com.secman.repository.ScanRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Regression tests for the missing access-control filter on the three scan-backed MCP tools.
 *
 * get_asset_scan_results, search_products and get_scans read the ScanPort / Scan tables with no
 * reference to the delegated user's accessible assets, so each returned the whole estate to any
 * caller holding the coarse permission. get_asset_scan_results was the worst of the three: it sat
 * under ASSETS_READ in McpToolPermissions.CALLING, which a bare USER role holds, and its response
 * carries asset name, IP, open port, service and service version — a full reconnaissance map.
 *
 * These tests follow the trap pattern used by GetVulnerabilitiesToolTest: the *unscoped*
 * repository reads are stubbed too, so code that still calls them produces a plausible-looking
 * result instead of a NullPointerException. What fails is the verify — the assertion is that the
 * unscoped read is never reached, not merely that the output happens to look right. Asserting only
 * on the returned rows would pass against the vulnerable code whenever the mock returned rows the
 * user could legitimately see.
 */
class ScanToolsAccessControlTest {

    private val scanPortRepository = mockk<ScanPortRepository>()
    private val scanRepository = mockk<ScanRepository>()

    private val scanResultsTool = GetAssetScanResultsTool(scanPortRepository)
    private val searchProductsTool = SearchProductsTool(scanPortRepository)
    private val getScansTool = GetScansTool(scanRepository)

    /** The asset the delegated user may see. */
    private val accessibleAsset = Asset(id = 1L, name = "web-01", type = "SERVER", ip = "10.0.0.1", owner = "alice")

    /** Another tenant's asset. Must never appear in a scoped caller's response. */
    private val foreignAsset = Asset(id = 2L, name = "db-secret-01", type = "SERVER", ip = "10.9.9.9", owner = "bob")

    private val scan = Scan(
        id = 1L,
        scanType = "nmap",
        filename = "internal-sweep.xml",
        scanDate = LocalDateTime.now(),
        uploadedBy = "bob",
        hostCount = 2
    )

    private fun portOn(asset: Asset, id: Long) = ScanPort(
        id = id,
        scanResult = ScanResult(
            id = id,
            scan = scan,
            asset = asset,
            ipAddress = asset.ip ?: "0.0.0.0",
            discoveredAt = LocalDateTime.now()
        ),
        portNumber = 443,
        protocol = "tcp",
        state = "open",
        service = "https",
        version = "nginx 1.18.0"
    )

    private fun ctx(accessibleIds: Set<Long>?) = mockk<McpExecutionContext>().also {
        every { it.getFilterableAssetIds() } returns accessibleIds
    }

    private fun <T : Any> pageOf(vararg items: T): Page<T> =
        Page.of(items.toList(), Pageable.from(0, 100), items.size.toLong())

    /**
     * Stub the unscoped reads so vulnerable code still returns something. The leak has to be
     * caught by the verify below, not by an accidental crash.
     */
    private fun stubUnscopedPortReads() {
        every { scanPortRepository.findAll(any<Pageable>()) } returns
            pageOf(portOn(accessibleAsset, 1L), portOn(foreignAsset, 2L))
        every { scanPortRepository.findByServiceContainingIgnoreCase(any(), any()) } returns
            pageOf(portOn(accessibleAsset, 1L), portOn(foreignAsset, 2L))
        every { scanPortRepository.findByStateAndServiceNotNull(any(), any()) } returns
            pageOf(portOn(accessibleAsset, 1L), portOn(foreignAsset, 2L))
    }

    private fun assertNoUnscopedPortRead() {
        verify(exactly = 0) { scanPortRepository.findAll(any<Pageable>()) }
        verify(exactly = 0) { scanPortRepository.findByServiceContainingIgnoreCase(any(), any()) }
        verify(exactly = 0) { scanPortRepository.findByStateAndServiceNotNull(any(), any()) }
    }

    private fun stubUnscopedScanReads() {
        every { scanRepository.findAllOrderByScanDateDesc(any()) } returns pageOf(scan)
        every { scanRepository.findByScanType(any(), any()) } returns pageOf(scan)
        every { scanRepository.findByUploadedByOrderByScanDateDesc(any(), any()) } returns pageOf(scan)
        every { scanRepository.findByScanDateBetween(any(), any(), any()) } returns pageOf(scan)
    }

    private fun assertNoUnscopedScanRead() {
        verify(exactly = 0) { scanRepository.findAllOrderByScanDateDesc(any()) }
        verify(exactly = 0) { scanRepository.findByScanType(any(), any()) }
        verify(exactly = 0) { scanRepository.findByUploadedByOrderByScanDateDesc(any(), any()) }
        verify(exactly = 0) { scanRepository.findByScanDateBetween(any(), any(), any()) }
    }

    // ---------------------------------------------------------------- get_asset_scan_results

    @Test
    fun `get_asset_scan_results binds the accessible asset ids instead of reading every port`(): Unit = runBlocking {
        stubUnscopedPortReads()
        every { scanPortRepository.findByScanResultAssetIdIn(setOf(1L), any()) } returns
            pageOf(portOn(accessibleAsset, 1L))

        scanResultsTool.execute(emptyMap(), ctx(setOf(1L)))

        verify(exactly = 1) { scanPortRepository.findByScanResultAssetIdIn(setOf(1L), any()) }
        assertNoUnscopedPortRead()
    }

    @Test
    fun `get_asset_scan_results scopes the service-filtered branch too`(): Unit = runBlocking {
        stubUnscopedPortReads()
        every {
            scanPortRepository.findByScanResultAssetIdInAndServiceContainingIgnoreCase(setOf(1L), "https", any())
        } returns pageOf(portOn(accessibleAsset, 1L))

        scanResultsTool.execute(mapOf("service" to "https"), ctx(setOf(1L)))

        verify(exactly = 1) {
            scanPortRepository.findByScanResultAssetIdInAndServiceContainingIgnoreCase(setOf(1L), "https", any())
        }
        assertNoUnscopedPortRead()
    }

    @Test
    fun `get_asset_scan_results never exposes an asset outside the caller's scope`(): Unit = runBlocking {
        stubUnscopedPortReads()
        every { scanPortRepository.findByScanResultAssetIdIn(setOf(1L), any()) } returns
            pageOf(portOn(accessibleAsset, 1L))

        val result = scanResultsTool.execute(emptyMap(), ctx(setOf(1L)))

        // The foreign asset's name, IP and service version must not appear anywhere in the payload.
        assertThat(result.toString())
            .doesNotContain(foreignAsset.name)
            .doesNotContain(foreignAsset.ip)
    }

    @Test
    fun `get_asset_scan_results returns nothing for a user with no accessible assets`(): Unit = runBlocking {
        stubUnscopedPortReads()

        val result = scanResultsTool.execute(emptyMap(), ctx(emptySet()))

        // An empty accessible set means "no assets", never "no filter".
        assertNoUnscopedPortRead()
        assertThat(result.toString()).doesNotContain(accessibleAsset.name).doesNotContain(foreignAsset.name)
    }

    @Test
    fun `get_asset_scan_results still reads unscoped for an admin`(): Unit = runBlocking {
        stubUnscopedPortReads()

        scanResultsTool.execute(emptyMap(), ctx(null))

        // null accessible ids == ADMIN, which legitimately sees the whole estate.
        verify(exactly = 1) { scanPortRepository.findAll(any<Pageable>()) }
    }

    // ---------------------------------------------------------------------- search_products

    @Test
    fun `search_products scopes the service branch`(): Unit = runBlocking {
        stubUnscopedPortReads()
        every {
            scanPortRepository.findByScanResultAssetIdInAndServiceContainingIgnoreCase(setOf(1L), "https", any())
        } returns pageOf(portOn(accessibleAsset, 1L))

        val result = searchProductsTool.execute(mapOf("service" to "https"), ctx(setOf(1L)))

        assertNoUnscopedPortRead()
        assertThat(result.toString()).doesNotContain(foreignAsset.name)
    }

    @Test
    fun `search_products scopes the state-only branch`(): Unit = runBlocking {
        stubUnscopedPortReads()
        every {
            scanPortRepository.findByScanResultAssetIdInAndStateAndServiceNotNull(setOf(1L), "open", any())
        } returns pageOf(portOn(accessibleAsset, 1L))

        searchProductsTool.execute(mapOf("stateFilter" to "open"), ctx(setOf(1L)))

        verify(exactly = 1) {
            scanPortRepository.findByScanResultAssetIdInAndStateAndServiceNotNull(setOf(1L), "open", any())
        }
        assertNoUnscopedPortRead()
    }

    // --------------------------------------------------------------------------- get_scans

    @Test
    fun `get_scans binds the accessible asset ids instead of listing every scan`(): Unit = runBlocking {
        stubUnscopedScanReads()
        every { scanRepository.findAccessibleScans(setOf(1L), any()) } returns pageOf(scan)

        getScansTool.execute(emptyMap(), ctx(setOf(1L)))

        verify(exactly = 1) { scanRepository.findAccessibleScans(setOf(1L), any()) }
        assertNoUnscopedScanRead()
    }

    @Test
    fun `get_scans scopes the uploadedBy branch`(): Unit = runBlocking {
        stubUnscopedScanReads()
        every { scanRepository.findAccessibleScansByUploadedBy(setOf(1L), "bob", any()) } returns pageOf(scan)

        getScansTool.execute(mapOf("uploadedBy" to "bob"), ctx(setOf(1L)))

        verify(exactly = 1) { scanRepository.findAccessibleScansByUploadedBy(setOf(1L), "bob", any()) }
        assertNoUnscopedScanRead()
    }

    @Test
    fun `get_scans returns nothing for a user with no accessible assets`(): Unit = runBlocking {
        stubUnscopedScanReads()

        val result = getScansTool.execute(emptyMap(), ctx(emptySet()))

        assertNoUnscopedScanRead()
        assertThat(result.toString()).doesNotContain(scan.filename)
    }
}
