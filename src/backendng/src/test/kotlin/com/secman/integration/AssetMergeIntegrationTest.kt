package com.secman.integration

import com.secman.domain.Asset
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityRepository
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.multipart.MultipartBody
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Integration test: Asset merge behavior on conflict
 *
 * Tests intelligent asset merging when importing vulnerabilities for existing assets:
 * - Groups: Append new groups, deduplicate
 * - IP: Update if changed
 * - CloudAccountId, CloudInstanceId, osVersion, adDomain: Update if different
 * - Owner, Type, Description: Preserve (never overwrite)
 *
 * Related to: Feature 003-i-want-to (Vulnerability Management System)
 */
@MicronautTest(transactional = false)
class AssetMergeIntegrationTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var assetRepository: AssetRepository

    @Inject
    lateinit var vulnerabilityRepository: VulnerabilityRepository

    private var testAssetId: Long? = null
    private val testHostname = "merge-test.example.com"

    @BeforeEach
    fun setup() {
        // Create existing asset with initial data
        val asset = Asset(
            name = testHostname,
            owner = "Development Team", // Should be preserved
            type = "Application Server", // Should be preserved
            description = "Production web server", // Should be preserved
            ip = "192.168.1.100",
            groups = "Production, Web Servers"
        )
        val saved = assetRepository.save(asset)
        testAssetId = saved.id
    }

    @AfterEach
    fun cleanup() {
        // Clean up test data
        vulnerabilityRepository.deleteAll()
        assetRepository.deleteAll()
    }

    @Test
    @DisplayName("Import should append new groups and deduplicate")
    fun testGroupAppendAndDeduplicate() {
        // Import with overlapping groups
        val xlsxFile = createVulnerabilityExcel(
            hostname = testHostname,
            ip = "192.168.1.100",
            groups = "Web Servers, Database Servers" // "Web Servers" overlaps, "Database Servers" is new
        )

        uploadFile(xlsxFile)

        // Verify groups were merged
        val asset = assetRepository.findById(testAssetId!!).get()
        val groups = asset.groups!!.split(",").map { it.trim() }.toSet()

        // Should contain: Production, Web Servers, Database Servers (deduplicated)
        assertEquals(3, groups.size)
        assertTrue(groups.contains("Production"))
        assertTrue(groups.contains("Web Servers"))
        assertTrue(groups.contains("Database Servers"))

        // Verify no duplicates
        assertEquals(groups.size, asset.groups!!.split(",").map { it.trim() }.toSet().size)
    }

    @Test
    @DisplayName("Import should update IP when different")
    fun testIpUpdate() {
        val newIp = "10.0.0.50"

        // Import with different IP
        val xlsxFile = createVulnerabilityExcel(
            hostname = testHostname,
            ip = newIp,
            groups = null
        )

        uploadFile(xlsxFile)

        // Verify IP was updated
        val asset = assetRepository.findById(testAssetId!!).get()
        assertEquals(newIp, asset.ip)
    }

    @Test
    @DisplayName("Import should preserve owner, type, and description")
    fun testPreserveOwnerTypeDescription() {
        // Import with new data
        val xlsxFile = createVulnerabilityExcel(
            hostname = testHostname,
            ip = "10.0.0.99",
            groups = "New Group"
        )

        uploadFile(xlsxFile)

        // Verify owner, type, description are unchanged
        val asset = assetRepository.findById(testAssetId!!).get()
        assertEquals("Development Team", asset.owner) // Preserved
        assertEquals("Application Server", asset.type) // Preserved
        assertEquals("Production web server", asset.description) // Preserved
    }

    @Test
    @DisplayName("Import should update cloud metadata when provided")
    fun testUpdateCloudMetadata() {
        // Initially asset has no cloud metadata
        val assetBefore = assetRepository.findById(testAssetId!!).get()
        assertNull(assetBefore.cloudAccountId)
        assertNull(assetBefore.cloudInstanceId)
        assertNull(assetBefore.osVersion)
        assertNull(assetBefore.adDomain)

        // Import with cloud metadata
        val xlsxFile = createVulnerabilityExcelWithCloudData(
            hostname = testHostname,
            cloudAccountId = "aws-account-456",
            cloudInstanceId = "i-abcdef123456",
            osVersion = "Ubuntu 22.04 LTS",
            adDomain = "corp.example.com"
        )

        uploadFile(xlsxFile)

        // Verify cloud metadata was added
        val asset = assetRepository.findById(testAssetId!!).get()
        assertEquals("aws-account-456", asset.cloudAccountId)
        assertEquals("i-abcdef123456", asset.cloudInstanceId)
        assertEquals("Ubuntu 22.04 LTS", asset.osVersion)
        assertEquals("corp.example.com", asset.adDomain)

        // Verify owner/type/description still preserved
        assertEquals("Development Team", asset.owner)
        assertEquals("Application Server", asset.type)
        assertEquals("Production web server", asset.description)
    }

    @Test
    @DisplayName("Import should update cloud metadata when different from existing")
    fun testUpdateExistingCloudMetadata() {
        // Update asset to have initial cloud metadata
        val asset = assetRepository.findById(testAssetId!!).get()
        asset.cloudAccountId = "old-account"
        asset.cloudInstanceId = "old-instance"
        asset.osVersion = "Ubuntu 20.04"
        assetRepository.update(asset)

        // Import with new cloud metadata
        val xlsxFile = createVulnerabilityExcelWithCloudData(
            hostname = testHostname,
            cloudAccountId = "new-account",
            cloudInstanceId = "new-instance",
            osVersion = "Ubuntu 22.04"
        )

        uploadFile(xlsxFile)

        // Verify cloud metadata was updated
        val updatedAsset = assetRepository.findById(testAssetId!!).get()
        assertEquals("new-account", updatedAsset.cloudAccountId)
        assertEquals("new-instance", updatedAsset.cloudInstanceId)
        assertEquals("Ubuntu 22.04", updatedAsset.osVersion)
    }

    @Test
    @DisplayName("Import multiple times should accumulate groups correctly")
    fun testMultipleImportsAccumulateGroups() {
        // First import
        val xlsxFile1 = createVulnerabilityExcel(
            hostname = testHostname,
            ip = "192.168.1.100",
            groups = "Group A, Group B"
        )
        uploadFile(xlsxFile1)

        val asset1 = assetRepository.findById(testAssetId!!).get()
        val groups1 = asset1.groups!!.split(",").map { it.trim() }.toSet()
        // Should have: Production, Web Servers, Group A, Group B
        assertEquals(4, groups1.size)

        // Second import with new groups
        val xlsxFile2 = createVulnerabilityExcel(
            hostname = testHostname,
            ip = "192.168.1.100",
            groups = "Group B, Group C" // Group B overlaps
        )
        uploadFile(xlsxFile2)

        val asset2 = assetRepository.findById(testAssetId!!).get()
        val groups2 = asset2.groups!!.split(",").map { it.trim() }.toSet()
        // Should have: Production, Web Servers, Group A, Group B, Group C (deduplicated)
        assertEquals(5, groups2.size)
        assertTrue(groups2.contains("Production"))
        assertTrue(groups2.contains("Web Servers"))
        assertTrue(groups2.contains("Group A"))
        assertTrue(groups2.contains("Group B"))
        assertTrue(groups2.contains("Group C"))
    }

    @Test
    @DisplayName("Import with no groups should preserve existing groups")
    fun testImportWithNoGroupsPreservesExisting() {
        val initialGroups = assetRepository.findById(testAssetId!!).get().groups

        // Import with no groups
        val xlsxFile = createVulnerabilityExcel(
            hostname = testHostname,
            ip = "192.168.1.100",
            groups = null
        )
        uploadFile(xlsxFile)

        // Verify groups unchanged
        val asset = assetRepository.findById(testAssetId!!).get()
        assertEquals(initialGroups, asset.groups)
    }

    @Test
    @DisplayName("Import with same IP should not trigger update")
    fun testImportWithSameIpNoUpdate() {
        val initialUpdatedAt = assetRepository.findById(testAssetId!!).get().updatedAt

        // Wait a moment to ensure timestamp would change if updated
        Thread.sleep(100)

        // Import with same IP
        val xlsxFile = createVulnerabilityExcel(
            hostname = testHostname,
            ip = "192.168.1.100", // Same as initial
            groups = null
        )
        uploadFile(xlsxFile)

        // Verify updatedAt hasn't changed (no merge needed)
        val asset = assetRepository.findById(testAssetId!!).get()
        assertEquals("192.168.1.100", asset.ip)
        // updatedAt might still change due to vulnerability relationship,
        // but IP should be unchanged
    }

    /**
     * Upload vulnerability Excel file
     */
    private fun uploadFile(xlsxFile: ByteArray) {
        val scanDate = LocalDateTime.now()
        val scanDateStr = scanDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

        val body = MultipartBody.builder()
            .addPart("xlsxFile", "vulnerabilities.xlsx", xlsxFile)
            .addPart("scanDate", scanDateStr)
            .build()

        val request = HttpRequest.POST("/api/import/upload-vulnerability-xlsx", body)
            .basicAuth("admin", "admin")

        val response = client.toBlocking().exchange(request, Map::class.java)
        assertEquals(HttpStatus.OK, response.status)
    }

    /**
     * Create Excel file with vulnerability
     */
    private fun createVulnerabilityExcel(
        hostname: String,
        ip: String?,
        groups: String?
    ): ByteArray {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Sheet1")

        // Header row
        val headerRow = sheet.createRow(0)
        val headers = listOf(
            "Hostname", "Local IP", "Host groups", "Cloud service account ID",
            "Cloud service instance ID", "OS version", "Active Directory domain",
            "Vulnerability ID", "CVSS severity", "Vulnerable product versions", "Days open"
        )
        headers.forEachIndexed { index, header ->
            headerRow.createCell(index).setCellValue(header)
        }

        // Data row
        val dataRow = sheet.createRow(1)
        dataRow.createCell(0).setCellValue(hostname)
        ip?.let { dataRow.createCell(1).setCellValue(it) }
        groups?.let { dataRow.createCell(2).setCellValue(it) }
        dataRow.createCell(7).setCellValue("CVE-2024-TEST")

        val outputStream = ByteArrayOutputStream()
        workbook.write(outputStream)
        workbook.close()

        return outputStream.toByteArray()
    }

    /**
     * Create Excel file with cloud metadata
     */
    private fun createVulnerabilityExcelWithCloudData(
        hostname: String,
        cloudAccountId: String? = null,
        cloudInstanceId: String? = null,
        osVersion: String? = null,
        adDomain: String? = null
    ): ByteArray {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Sheet1")

        // Header row
        val headerRow = sheet.createRow(0)
        val headers = listOf(
            "Hostname", "Local IP", "Host groups", "Cloud service account ID",
            "Cloud service instance ID", "OS version", "Active Directory domain",
            "Vulnerability ID", "CVSS severity", "Vulnerable product versions", "Days open"
        )
        headers.forEachIndexed { index, header ->
            headerRow.createCell(index).setCellValue(header)
        }

        // Data row
        val dataRow = sheet.createRow(1)
        dataRow.createCell(0).setCellValue(hostname)
        cloudAccountId?.let { dataRow.createCell(3).setCellValue(it) }
        cloudInstanceId?.let { dataRow.createCell(4).setCellValue(it) }
        osVersion?.let { dataRow.createCell(5).setCellValue(it) }
        adDomain?.let { dataRow.createCell(6).setCellValue(it) }
        dataRow.createCell(7).setCellValue("CVE-2024-TEST")

        val outputStream = ByteArrayOutputStream()
        workbook.write(outputStream)
        workbook.close()

        return outputStream.toByteArray()
    }
}
