package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.Scan
import com.secman.domain.ScanResult
import com.secman.dto.ScanSummaryDTO
import com.secman.repository.AssetRepository
import com.secman.repository.AuditLogRepository
import com.secman.repository.ScanRepository
import com.secman.repository.ScanResultRepository
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.*

/**
 * Unit tests for ScanImportService
 *
 * Tests cover:
 * - T037: Import coordination logic
 * - Duplicate detection (Decision 2)
 * - Asset creation with naming fallback (Decision 1)
 * - Asset update (lastSeen timestamp)
 * - Audit logging (NFR-003)
 */
@MicronautTest
class ScanImportServiceTest {

    private lateinit var scanRepository: ScanRepository
    private lateinit var assetRepository: AssetRepository
    private lateinit var scanResultRepository: ScanResultRepository
    private lateinit var auditLogRepository: AuditLogRepository
    private lateinit var nmapParserService: NmapParserService
    private lateinit var scanImportService: ScanImportService

    @BeforeEach
    fun setup() {
        scanRepository = mockk()
        assetRepository = mockk()
        scanResultRepository = mockk()
        auditLogRepository = mockk()
        nmapParserService = mockk()

        scanImportService = ScanImportService(
            scanRepository,
            assetRepository,
            scanResultRepository,
            auditLogRepository,
            nmapParserService
        )
    }

    @Test
    fun `test successful nmap import creates new asset`() {
        // Given
        val xmlContent = "<nmaprun>...</nmaprun>"
        val filename = "test-scan.xml"
        val username = "adminuser"

        val mockScanData = NmapScanData(
            scanType = "nmap",
            scanDate = LocalDateTime.now(),
            duration = "10s",
            hosts = listOf(
                NmapHost(
                    ipAddress = "192.168.1.100",
                    hostname = "server.local",
                    ports = listOf(
                        NmapPort(portNumber = 22, protocol = "tcp", state = "open", service = "ssh", version = null)
                    )
                )
            )
        )

        // Mock parser
        every { nmapParserService.parseNmapXml(xmlContent) } returns mockScanData

        // Mock scan save
        val savedScan = Scan(
            scanType = "nmap",
            filename = filename,
            scanDate = mockScanData.scanDate,
            uploadedBy = username,
            hostCount = 1,
            duration = "10s"
        ).apply { id = 1L }

        every { scanRepository.save(any<Scan>()) } returns savedScan

        // Mock asset lookup - not found, so will create new
        every { assetRepository.findByIp("192.168.1.100") } returns Optional.empty()

        // Mock asset save - new asset created
        val newAsset = Asset(
            name = "server.local",
            type = "Network Host",
            ip = "192.168.1.100",
            owner = "Imported from nmap scan",
            description = "Discovered in scan: test-scan.xml"
        ).apply {
            id = 1L
            lastSeen = LocalDateTime.now()
        }

        every { assetRepository.save(any<Asset>()) } returns newAsset

        // Mock scan result save
        every { scanResultRepository.save(any<ScanResult>()) } returns mockk()

        // Mock audit log
        every { auditLogRepository.save(any()) } returns mockk()

        // When
        val summary = scanImportService.importNmapScan(xmlContent, filename, username)

        // Then
        assertNotNull(summary)
        assertEquals(1, summary.hostsDiscovered)
        assertEquals(1, summary.assetsCreated)
        assertEquals(0, summary.assetsUpdated)
        assertEquals(1, summary.totalPorts)

        // Verify asset was created with hostname as name
        verify {
            assetRepository.save(match<Asset> {
                it.name == "server.local" &&
                it.type == "Network Host" &&
                it.ip == "192.168.1.100" &&
                it.owner == "Imported from nmap scan"
            })
        }

        // Verify scan result was saved
        verify { scanResultRepository.save(any<ScanResult>()) }

        // Verify audit logs were created
        verify(atLeast = 1) { auditLogRepository.save(any()) }
    }

    @Test
    fun `test import updates existing asset`() {
        // Given
        val xmlContent = "<nmaprun>...</nmaprun>"
        val filename = "update-scan.xml"
        val username = "adminuser"

        val mockScanData = NmapScanData(
            scanType = "nmap",
            scanDate = LocalDateTime.now(),
            duration = "5s",
            hosts = listOf(
                NmapHost(
                    ipAddress = "10.0.0.50",
                    hostname = "web-server",
                    ports = listOf(
                        NmapPort(portNumber = 80, protocol = "tcp", state = "open", service = "http", version = null)
                    )
                )
            )
        )

        every { nmapParserService.parseNmapXml(xmlContent) } returns mockScanData

        val savedScan = Scan(
            scanType = "nmap",
            filename = filename,
            scanDate = mockScanData.scanDate,
            uploadedBy = username,
            hostCount = 1,
            duration = "5s"
        ).apply { id = 2L }

        every { scanRepository.save(any<Scan>()) } returns savedScan

        // Mock asset lookup - asset EXISTS
        val existingAsset = Asset(
            name = "Old Web Server",
            type = "Server",
            ip = "10.0.0.50",
            owner = "Web Team",
            description = "Production web server"
        ).apply {
            id = 10L
            lastSeen = LocalDateTime.now().minusDays(7) // Last seen 7 days ago
        }

        every { assetRepository.findByIp("10.0.0.50") } returns Optional.of(existingAsset)
        every { assetRepository.save(any<Asset>()) } answers { firstArg() }

        every { scanResultRepository.save(any<ScanResult>()) } returns mockk()
        every { auditLogRepository.save(any()) } returns mockk()

        // When
        val summary = scanImportService.importNmapScan(xmlContent, filename, username)

        // Then
        assertEquals(1, summary.hostsDiscovered)
        assertEquals(0, summary.assetsCreated)
        assertEquals(1, summary.assetsUpdated)

        // Verify asset was updated (lastSeen updated)
        verify {
            assetRepository.save(match<Asset> {
                it.id == 10L &&
                it.lastSeen != null &&
                it.lastSeen!!.isAfter(existingAsset.lastSeen!!)
            })
        }
    }

    @Test
    fun `test import with IP fallback when hostname is missing`() {
        // Given - Decision 1: Use IP as name when hostname is null
        val xmlContent = "<nmaprun>...</nmaprun>"
        val filename = "no-hostname.xml"
        val username = "adminuser"

        val mockScanData = NmapScanData(
            scanType = "nmap",
            scanDate = LocalDateTime.now(),
            duration = "3s",
            hosts = listOf(
                NmapHost(
                    ipAddress = "172.16.0.5",
                    hostname = null, // No hostname
                    ports = listOf()
                )
            )
        )

        every { nmapParserService.parseNmapXml(xmlContent) } returns mockScanData

        val savedScan = Scan(
            scanType = "nmap",
            filename = filename,
            scanDate = mockScanData.scanDate,
            uploadedBy = username,
            hostCount = 1,
            duration = "3s"
        ).apply { id = 3L }

        every { scanRepository.save(any<Scan>()) } returns savedScan
        every { assetRepository.findByIp("172.16.0.5") } returns Optional.empty()
        every { assetRepository.save(any<Asset>()) } answers { firstArg() }
        every { scanResultRepository.save(any<ScanResult>()) } returns mockk()
        every { auditLogRepository.save(any()) } returns mockk()

        // When
        val summary = scanImportService.importNmapScan(xmlContent, filename, username)

        // Then
        assertEquals(1, summary.assetsCreated)

        // Verify asset was created with IP as name (Decision 1)
        verify {
            assetRepository.save(match<Asset> {
                it.name == "172.16.0.5" && // IP used as name
                it.ip == "172.16.0.5"
            })
        }
    }

    @Test
    fun `test duplicate IP detection within same scan`() {
        // Given - Decision 2: Skip duplicate IPs within same scan
        val xmlContent = "<nmaprun>...</nmaprun>"
        val filename = "duplicate.xml"
        val username = "adminuser"

        val mockScanData = NmapScanData(
            scanType = "nmap",
            scanDate = LocalDateTime.now(),
            duration = "15s",
            hosts = listOf(
                NmapHost(
                    ipAddress = "192.168.1.1",
                    hostname = "router",
                    ports = listOf()
                ),
                NmapHost(
                    ipAddress = "192.168.1.1", // DUPLICATE IP
                    hostname = "router-duplicate",
                    ports = listOf()
                )
            )
        )

        every { nmapParserService.parseNmapXml(xmlContent) } returns mockScanData

        val savedScan = Scan(
            scanType = "nmap",
            filename = filename,
            scanDate = mockScanData.scanDate,
            uploadedBy = username,
            hostCount = 2,
            duration = "15s"
        ).apply { id = 4L }

        every { scanRepository.save(any<Scan>()) } returns savedScan
        every { assetRepository.findByIp("192.168.1.1") } returns Optional.empty()
        every { assetRepository.save(any<Asset>()) } answers { firstArg() }
        every { scanResultRepository.save(any<ScanResult>()) } returns mockk()
        every { auditLogRepository.save(any()) } returns mockk()

        // When
        val summary = scanImportService.importNmapScan(xmlContent, filename, username)

        // Then
        // Should only create 1 asset (first occurrence), second should be skipped
        assertEquals(2, summary.hostsDiscovered)
        assertEquals(1, summary.assetsCreated) // Only 1 asset created
        assertEquals(0, summary.assetsUpdated)

        // Verify asset save was only called once
        verify(exactly = 1) { assetRepository.save(any<Asset>()) }

        // Verify duplicate was logged in audit
        verify {
            auditLogRepository.save(match {
                it.action == "DUPLICATE_IP" &&
                it.level == "WARN"
            })
        }
    }

    @Test
    fun `test import with multiple hosts and ports`() {
        // Given
        val xmlContent = "<nmaprun>...</nmaprun>"
        val filename = "multi-host.xml"
        val username = "adminuser"

        val mockScanData = NmapScanData(
            scanType = "nmap",
            scanDate = LocalDateTime.now(),
            duration = "30s",
            hosts = listOf(
                NmapHost(
                    ipAddress = "10.10.10.1",
                    hostname = "host1",
                    ports = listOf(
                        NmapPort(22, "tcp", "open", "ssh", null),
                        NmapPort(80, "tcp", "open", "http", null),
                        NmapPort(443, "tcp", "open", "https", null)
                    )
                ),
                NmapHost(
                    ipAddress = "10.10.10.2",
                    hostname = "host2",
                    ports = listOf(
                        NmapPort(22, "tcp", "open", "ssh", null),
                        NmapPort(3306, "tcp", "open", "mysql", "5.7")
                    )
                )
            )
        )

        every { nmapParserService.parseNmapXml(xmlContent) } returns mockScanData

        val savedScan = Scan(
            scanType = "nmap",
            filename = filename,
            scanDate = mockScanData.scanDate,
            uploadedBy = username,
            hostCount = 2,
            duration = "30s"
        ).apply { id = 5L }

        every { scanRepository.save(any<Scan>()) } returns savedScan
        every { assetRepository.findByIp(any()) } returns Optional.empty()
        every { assetRepository.save(any<Asset>()) } answers { firstArg() }
        every { scanResultRepository.save(any<ScanResult>()) } returns mockk()
        every { auditLogRepository.save(any()) } returns mockk()

        // When
        val summary = scanImportService.importNmapScan(xmlContent, filename, username)

        // Then
        assertEquals(2, summary.hostsDiscovered)
        assertEquals(2, summary.assetsCreated)
        assertEquals(5, summary.totalPorts) // 3 + 2 ports

        // Verify 2 assets were created
        verify(exactly = 2) { assetRepository.save(any<Asset>()) }

        // Verify 2 scan results were saved
        verify(exactly = 2) { scanResultRepository.save(any<ScanResult>()) }
    }

    @Test
    fun `test default asset type is Network Host`() {
        // Given - Decision 3: Default type is "Network Host"
        val xmlContent = "<nmaprun>...</nmaprun>"
        val filename = "test.xml"
        val username = "adminuser"

        val mockScanData = NmapScanData(
            scanType = "nmap",
            scanDate = LocalDateTime.now(),
            duration = "2s",
            hosts = listOf(
                NmapHost(
                    ipAddress = "192.168.100.50",
                    hostname = "test-host",
                    ports = listOf()
                )
            )
        )

        every { nmapParserService.parseNmapXml(xmlContent) } returns mockScanData

        val savedScan = Scan(
            scanType = "nmap",
            filename = filename,
            scanDate = mockScanData.scanDate,
            uploadedBy = username,
            hostCount = 1,
            duration = "2s"
        ).apply { id = 6L }

        every { scanRepository.save(any<Scan>()) } returns savedScan
        every { assetRepository.findByIp("192.168.100.50") } returns Optional.empty()
        every { assetRepository.save(any<Asset>()) } answers { firstArg() }
        every { scanResultRepository.save(any<ScanResult>()) } returns mockk()
        every { auditLogRepository.save(any()) } returns mockk()

        // When
        scanImportService.importNmapScan(xmlContent, filename, username)

        // Then
        verify {
            assetRepository.save(match<Asset> {
                it.type == "Network Host" // Decision 3
            })
        }
    }

    @Test
    fun `test audit logging for scan import`() {
        // Given - NFR-003: Audit logging
        val xmlContent = "<nmaprun>...</nmaprun>"
        val filename = "audit-test.xml"
        val username = "audituser"

        val mockScanData = NmapScanData(
            scanType = "nmap",
            scanDate = LocalDateTime.now(),
            duration = "1s",
            hosts = listOf(
                NmapHost(
                    ipAddress = "10.20.30.40",
                    hostname = "audit-host",
                    ports = listOf()
                )
            )
        )

        every { nmapParserService.parseNmapXml(xmlContent) } returns mockScanData

        val savedScan = Scan(
            scanType = "nmap",
            filename = filename,
            scanDate = mockScanData.scanDate,
            uploadedBy = username,
            hostCount = 1,
            duration = "1s"
        ).apply { id = 7L }

        every { scanRepository.save(any<Scan>()) } returns savedScan
        every { assetRepository.findByIp("10.20.30.40") } returns Optional.empty()
        every { assetRepository.save(any<Asset>()) } answers { firstArg() }
        every { scanResultRepository.save(any<ScanResult>()) } returns mockk()
        every { auditLogRepository.save(any()) } returns mockk()

        // When
        scanImportService.importNmapScan(xmlContent, filename, username)

        // Then - Verify audit logs were created
        verify(atLeast = 2) { // At least: SCAN_IMPORT_START and SCAN_IMPORT_COMPLETE
            auditLogRepository.save(match {
                it.username == username &&
                it.entityType == "SCAN"
            })
        }

        // Verify import start was logged
        verify {
            auditLogRepository.save(match {
                it.action == "SCAN_IMPORT_START" &&
                it.level == "INFO"
            })
        }

        // Verify import complete was logged
        verify {
            auditLogRepository.save(match {
                it.action == "SCAN_IMPORT_COMPLETE" &&
                it.level == "INFO"
            })
        }
    }

    @Test
    fun `test scan summary DTO contains correct data`() {
        // Given
        val xmlContent = "<nmaprun>...</nmaprun>"
        val filename = "summary-test.xml"
        val username = "testuser"

        val scanDate = LocalDateTime.of(2025, 10, 3, 10, 30, 0)
        val mockScanData = NmapScanData(
            scanType = "nmap",
            scanDate = scanDate,
            duration = "42s",
            hosts = listOf(
                NmapHost(
                    ipAddress = "172.20.0.1",
                    hostname = "summary-host",
                    ports = listOf(
                        NmapPort(80, "tcp", "open", "http", null),
                        NmapPort(443, "tcp", "open", "https", null)
                    )
                )
            )
        )

        every { nmapParserService.parseNmapXml(xmlContent) } returns mockScanData

        val savedScan = Scan(
            scanType = "nmap",
            filename = filename,
            scanDate = scanDate,
            uploadedBy = username,
            hostCount = 1,
            duration = "42s"
        ).apply { id = 999L }

        every { scanRepository.save(any<Scan>()) } returns savedScan
        every { assetRepository.findByIp("172.20.0.1") } returns Optional.empty()
        every { assetRepository.save(any<Asset>()) } answers { firstArg() }
        every { scanResultRepository.save(any<ScanResult>()) } returns mockk()
        every { auditLogRepository.save(any()) } returns mockk()

        // When
        val summary = scanImportService.importNmapScan(xmlContent, filename, username)

        // Then
        assertEquals(999L, summary.scanId)
        assertEquals(filename, summary.filename)
        assertEquals(scanDate, summary.scanDate)
        assertEquals(1, summary.hostsDiscovered)
        assertEquals(1, summary.assetsCreated)
        assertEquals(0, summary.assetsUpdated)
        assertEquals(2, summary.totalPorts)
        assertEquals("42s", summary.duration)
    }

    @Test
    fun `test empty scan file creates scan but no assets`() {
        // Given - Valid XML but no hosts
        val xmlContent = "<nmaprun>...</nmaprun>"
        val filename = "empty-scan.xml"
        val username = "testuser"

        val mockScanData = NmapScanData(
            scanType = "nmap",
            scanDate = LocalDateTime.now(),
            duration = "1s",
            hosts = emptyList() // No hosts discovered
        )

        every { nmapParserService.parseNmapXml(xmlContent) } returns mockScanData

        val savedScan = Scan(
            scanType = "nmap",
            filename = filename,
            scanDate = mockScanData.scanDate,
            uploadedBy = username,
            hostCount = 0,
            duration = "1s"
        ).apply { id = 100L }

        every { scanRepository.save(any<Scan>()) } returns savedScan
        every { auditLogRepository.save(any()) } returns mockk()

        // When
        val summary = scanImportService.importNmapScan(xmlContent, filename, username)

        // Then
        assertEquals(0, summary.hostsDiscovered)
        assertEquals(0, summary.assetsCreated)
        assertEquals(0, summary.assetsUpdated)
        assertEquals(0, summary.totalPorts)

        // Verify scan was still saved
        verify { scanRepository.save(any<Scan>()) }

        // Verify no assets were created
        verify(exactly = 0) { assetRepository.save(any<Asset>()) }
    }
}
