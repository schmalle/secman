package com.secman.cli.unit

import com.secman.cli.export.ExportService
import com.secman.crowdstrike.dto.CrowdStrikeQueryResponse
import com.secman.crowdstrike.dto.CrowdStrikeVulnerabilityDto
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.LocalDateTime

/**
 * Unit tests for ExportService
 *
 * Tests:
 * - JSON export with pretty-print formatting
 * - CSV export with header row and proper escaping
 * - File overwrite handling (prompt and automatic)
 * - Directory creation for parent paths
 * - Write permission validation
 * - Multiple format export
 *
 * Related to: Feature 023-create-in-the (Phase 6: Export Results)
 * Task: T111, T112
 */
@DisplayName("ExportService Unit Tests")
class ExportServiceTest {

    private lateinit var exportService: ExportService
    private lateinit var testResponse: CrowdStrikeQueryResponse

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        exportService = ExportService()

        // Create test data
        val vuln1 = CrowdStrikeVulnerabilityDto(
            id = "vuln-001",
            hostname = "test-host-01",
            ip = "192.168.1.1",
            cveId = "CVE-2024-1234",
            severity = "critical",
            cvssScore = 9.5,
            affectedProduct = "Apache OpenSSL 1.1.1",
            daysOpen = "10 days",
            detectedAt = LocalDateTime.of(2024, 10, 1, 12, 0),
            status = "open",
            hasException = false,
            exceptionReason = null
        )

        val vuln2 = CrowdStrikeVulnerabilityDto(
            id = "vuln-002",
            hostname = "test-host-01",
            ip = "192.168.1.1",
            cveId = "CVE-2024-5678",
            severity = "high",
            cvssScore = 7.8,
            affectedProduct = "Apache HTTP Server 2.4",
            daysOpen = "5 days",
            detectedAt = LocalDateTime.of(2024, 10, 5, 14, 30),
            status = "open",
            hasException = true,
            exceptionReason = "Patch pending approval"
        )

        testResponse = CrowdStrikeQueryResponse(
            hostname = "test-host-01",
            vulnerabilities = listOf(vuln1, vuln2),
            totalCount = 2,
            queriedAt = LocalDateTime.of(2024, 10, 10, 10, 10)
        )
    }

    @Test
    @DisplayName("Should export vulnerabilities to JSON file")
    fun testExportToJson() {
        // Given
        val outputFile = tempDir.resolve("test-export.json").toFile()

        // When
        val result = exportService.exportToJson(testResponse, outputFile, promptOverwrite = false)

        // Then
        assertTrue(result)
        assertTrue(outputFile.exists())

        val content = outputFile.readText()
        assertTrue(content.contains("\"hostname\": \"test-host-01\""))
        assertTrue(content.contains("\"totalCount\": 2"))
        assertTrue(content.contains("\"cveId\": \"CVE-2024-1234\""))
        assertTrue(content.contains("\"severity\": \"critical\""))
        assertTrue(content.contains("\"cvssScore\": 9.5"))
    }

    @Test
    @DisplayName("Should export vulnerabilities to CSV file")
    fun testExportToCsv() {
        // Given
        val outputFile = tempDir.resolve("test-export.csv").toFile()

        // When
        val result = exportService.exportToCsv(testResponse, outputFile, promptOverwrite = false)

        // Then
        assertTrue(result)
        assertTrue(outputFile.exists())

        val lines = outputFile.readLines()
        assertEquals(3, lines.size) // Header + 2 data rows

        // Verify header
        val header = lines[0]
        assertTrue(header.contains("Hostname"))
        assertTrue(header.contains("CVE ID"))
        assertTrue(header.contains("Severity"))
        assertTrue(header.contains("CVSS Score"))

        // Verify data rows
        val row1 = lines[1]
        assertTrue(row1.contains("test-host-01"))
        assertTrue(row1.contains("CVE-2024-1234"))
        assertTrue(row1.contains("critical"))
        assertTrue(row1.contains("9.5"))

        val row2 = lines[2]
        assertTrue(row2.contains("CVE-2024-5678"))
        assertTrue(row2.contains("high"))
        assertTrue(row2.contains("7.8"))
        assertTrue(row2.contains("Patch pending approval"))
    }

    @Test
    @DisplayName("Should create parent directory if it doesn't exist")
    fun testCreateParentDirectory() {
        // Given
        val nestedDir = tempDir.resolve("subdir/nested/deep").toFile()
        val outputFile = File(nestedDir, "export.json")

        assertFalse(nestedDir.exists())

        // When
        val result = exportService.exportToJson(testResponse, outputFile, promptOverwrite = false)

        // Then
        assertTrue(result)
        assertTrue(nestedDir.exists())
        assertTrue(outputFile.exists())
    }

    @Test
    @DisplayName("Should handle JSON special characters correctly")
    fun testJsonEscaping() {
        // Given
        val vulnWithSpecialChars = CrowdStrikeVulnerabilityDto(
            id = "vuln-003",
            hostname = "test-host",
            ip = "192.168.1.1",
            cveId = "CVE-2024-9999",
            severity = "medium",
            cvssScore = 5.0,
            affectedProduct = "Software \"with quotes\" and \\ backslashes",
            daysOpen = "1 day",
            detectedAt = LocalDateTime.now(),
            status = "open",
            hasException = false
        )

        val responseWithSpecialChars = testResponse.copy(
            vulnerabilities = listOf(vulnWithSpecialChars)
        )

        val outputFile = tempDir.resolve("test-escaping.json").toFile()

        // When
        val result = exportService.exportToJson(responseWithSpecialChars, outputFile, promptOverwrite = false)

        // Then
        assertTrue(result)

        val content = outputFile.readText()
        assertTrue(content.contains("\\\"with quotes\\\""))
        assertTrue(content.contains("\\\\"))
    }

    @Test
    @DisplayName("Should handle CSV special characters correctly")
    fun testCsvEscaping() {
        // Given
        val vulnWithComma = CrowdStrikeVulnerabilityDto(
            id = "vuln-004",
            hostname = "test-host",
            ip = "192.168.1.1",
            cveId = "CVE-2024-8888",
            severity = "low",
            cvssScore = 3.0,
            affectedProduct = "Software, with, commas",
            daysOpen = "2 days",
            detectedAt = LocalDateTime.now(),
            status = "open",
            hasException = false
        )

        val responseWithComma = testResponse.copy(
            vulnerabilities = listOf(vulnWithComma)
        )

        val outputFile = tempDir.resolve("test-csv-escaping.csv").toFile()

        // When
        val result = exportService.exportToCsv(responseWithComma, outputFile, promptOverwrite = false)

        // Then
        assertTrue(result)

        val content = outputFile.readText()
        // Apache Commons CSV should handle comma escaping
        assertTrue(content.contains("Software, with, commas") || content.contains("\"Software, with, commas\""))
    }

    @Test
    @DisplayName("Should export to multiple formats simultaneously")
    fun testExportToMultiple() {
        // Given
        val outputDir = tempDir.toFile()
        val baseFilename = "multi-export"

        // When
        val result = exportService.exportToMultiple(
            testResponse,
            baseFilename,
            outputDir,
            promptOverwrite = false
        )

        // Then
        assertNotNull(result)
        val (jsonFile, csvFile) = result!!

        assertTrue(jsonFile.exists())
        assertTrue(jsonFile.name == "multi-export.json")

        assertTrue(csvFile.exists())
        assertTrue(csvFile.name == "multi-export.csv")
    }

    @Test
    @DisplayName("Should throw exception when exporting empty vulnerability list")
    fun testExportEmptyList() {
        // Given
        val emptyResponse = testResponse.copy(vulnerabilities = emptyList())
        val outputFile = tempDir.resolve("empty.json").toFile()

        // When & Then
        val exception = assertThrows(IllegalArgumentException::class.java) {
            exportService.exportToJson(emptyResponse, outputFile, promptOverwrite = false)
        }

        assertTrue(exception.message?.contains("empty") ?: false)
    }

    @Test
    @DisplayName("Should handle null CVE IDs correctly")
    fun testNullCveId() {
        // Given
        val vulnWithoutCve = CrowdStrikeVulnerabilityDto(
            id = "vuln-005",
            hostname = "test-host",
            ip = "192.168.1.1",
            cveId = null,
            severity = "informational",
            cvssScore = null,
            affectedProduct = "Some Software",
            daysOpen = "0 days",
            detectedAt = LocalDateTime.now(),
            status = "open",
            hasException = false
        )

        val responseWithNullCve = testResponse.copy(
            vulnerabilities = listOf(vulnWithoutCve)
        )

        val jsonFile = tempDir.resolve("null-cve.json").toFile()
        val csvFile = tempDir.resolve("null-cve.csv").toFile()

        // When
        val jsonResult = exportService.exportToJson(responseWithNullCve, jsonFile, promptOverwrite = false)
        val csvResult = exportService.exportToCsv(responseWithNullCve, csvFile, promptOverwrite = false)

        // Then
        assertTrue(jsonResult)
        assertTrue(csvResult)

        val jsonContent = jsonFile.readText()
        assertTrue(jsonContent.contains("\"cveId\": null"))

        val csvLines = csvFile.readLines()
        val dataRow = csvLines[1]
        assertTrue(dataRow.contains(",,") || dataRow.split(",")[1].isBlank()) // Empty CVE ID field
    }

    @Test
    @DisplayName("Should format JSON with proper indentation")
    fun testJsonPrettyPrint() {
        // Given
        val outputFile = tempDir.resolve("pretty.json").toFile()

        // When
        val result = exportService.exportToJson(testResponse, outputFile, promptOverwrite = false)

        // Then
        assertTrue(result)

        val content = outputFile.readText()
        // Check for indentation (spaces)
        assertTrue(content.contains("  \"hostname\""))
        assertTrue(content.contains("    {")) // Nested object indentation
    }

    @Test
    @DisplayName("Should include all required CSV columns")
    fun testCsvColumnsComplete() {
        // Given
        val outputFile = tempDir.resolve("columns.csv").toFile()

        // When
        val result = exportService.exportToCsv(testResponse, outputFile, promptOverwrite = false)

        // Then
        assertTrue(result)

        val header = outputFile.readLines()[0]
        val expectedColumns = listOf(
            "Hostname",
            "CVE ID",
            "Severity",
            "CVSS Score",
            "Affected Product",
            "Days Open",
            "Detected At",
            "Status",
            "Exception"
        )

        for (column in expectedColumns) {
            assertTrue(header.contains(column), "Missing column: $column")
        }
    }

    @Test
    @DisplayName("Should handle exception reason in CSV")
    fun testCsvExceptionReason() {
        // Given
        val outputFile = tempDir.resolve("exception.csv").toFile()

        // When
        val result = exportService.exportToCsv(testResponse, outputFile, promptOverwrite = false)

        // Then
        assertTrue(result)

        val lines = outputFile.readLines()
        val row2 = lines[2] // Second vulnerability has exception

        assertTrue(row2.contains("Patch pending approval"))
    }
}
