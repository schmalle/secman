package com.secman.contract

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.client.multipart.MultipartBody
import io.micronaut.security.authentication.UsernamePasswordCredentials
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * Contract test for POST /api/import/upload-user-mappings-csv
 *
 * Related to: Feature 016-i-want-to (CSV-Based User Mapping Upload)
 *
 * Tests the API contract defined in contracts/csv-upload.yaml:
 * - Multipart file upload with CSV
 * - Authentication required (ADMIN role only)
 * - Response format (ImportResult with structured errors)
 * - Error handling (400, 401, 403, 413)
 *
 * Expected to FAIL until ImportController.uploadUserMappingsCSV() is implemented (TDD red phase).
 */
@MicronautTest
class CSVUploadContractTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    /**
     * Contract: POST /api/import/upload-user-mappings-csv with valid CSV file
     * Expected Response: 200 OK with ImportResult containing:
     * - message (String)
     * - imported (Int)
     * - skipped (Int)
     * - errors (List<ImportError>)
     */
    @Test
    fun `testUploadValidCSV - Returns 200 with correct import counts`() {
        // Arrange
        val token = authenticateAsAdmin()
        val csvFile = File.createTempFile("valid", ".csv")
        csvFile.writeText("""
            account_id,owner_email,domain
            123456789012,user1@example.com,example.com
            234567890123,user2@example.com,example.com
        """.trimIndent())

        val body = MultipartBody.builder()
            .addPart("csvFile", csvFile.name, MediaType.TEXT_PLAIN_TYPE, csvFile)
            .build()

        // Act
        val request = HttpRequest.POST("/api/import/upload-user-mappings-csv", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val response = client.toBlocking().exchange(request, ImportResult::class.java)

        // Assert - Contract validation
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())

        val importResult = response.body()!!
        assertNotNull(importResult.message, "message must be present")
        assertTrue(importResult.imported >= 0, "imported must be non-negative")
        assertTrue(importResult.skipped >= 0, "skipped must be non-negative")
        assertNotNull(importResult.errors, "errors list must be present")

        // Verify at least some mappings were imported
        assertTrue(importResult.imported > 0, "Should import at least one mapping")

        // Cleanup
        csvFile.delete()
    }

    /**
     * Contract: POST /api/import/upload-user-mappings-csv with missing required headers
     * Expected Response: 400 BAD REQUEST with error about missing columns
     */
    @Test
    fun `testUploadCSVWithMissingHeaders - Returns 400 with missing columns error`() {
        // Arrange
        val token = authenticateAsAdmin()
        val csvFile = File.createTempFile("missing_headers", ".csv")
        csvFile.writeText("""
            email,domain
            user@example.com,example.com
        """.trimIndent())

        val body = MultipartBody.builder()
            .addPart("csvFile", csvFile.name, MediaType.TEXT_PLAIN_TYPE, csvFile)
            .build()

        // Act & Assert
        val request = HttpRequest.POST("/api/import/upload-user-mappings-csv", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
        assertTrue(exception.message!!.contains("Missing") || exception.message!!.contains("required"),
                   "Error message should mention missing required columns")

        // Cleanup
        csvFile.delete()
    }

    /**
     * Contract: POST /api/import/upload-user-mappings-csv with empty file
     * Expected Response: 400 BAD REQUEST with error about empty file
     */
    @Test
    fun `testUploadEmptyCSV - Returns 400 with empty file error`() {
        // Arrange
        val token = authenticateAsAdmin()
        val csvFile = File.createTempFile("empty", ".csv")
        csvFile.writeText("")

        val body = MultipartBody.builder()
            .addPart("csvFile", csvFile.name, MediaType.TEXT_PLAIN_TYPE, csvFile)
            .build()

        // Act & Assert
        val request = HttpRequest.POST("/api/import/upload-user-mappings-csv", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
        assertTrue(exception.message!!.contains("empty") || exception.message!!.contains("Empty"),
                   "Error message should mention empty file")

        // Cleanup
        csvFile.delete()
    }

    /**
     * Contract: POST /api/import/upload-user-mappings-csv with .xlsx file (wrong extension)
     * Expected Response: 400 BAD REQUEST with error about invalid file type
     */
    @Test
    fun `testUploadInvalidExtension - Returns 400 for non-csv file`() {
        // Arrange
        val token = authenticateAsAdmin()
        val xlsxFile = File.createTempFile("test", ".xlsx")
        xlsxFile.writeText("not a csv")

        val body = MultipartBody.builder()
            .addPart("csvFile", xlsxFile.name, MediaType.TEXT_PLAIN_TYPE, xlsxFile)
            .build()

        // Act & Assert
        val request = HttpRequest.POST("/api/import/upload-user-mappings-csv", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
        assertTrue(exception.message!!.contains("csv") || exception.message!!.contains("CSV") ||
                   exception.message!!.contains("extension") || exception.message!!.contains("type"),
                   "Error message should mention CSV or extension requirement")

        // Cleanup
        xlsxFile.delete()
    }

    /**
     * Contract: POST /api/import/upload-user-mappings-csv without authentication
     * Expected Response: 401 UNAUTHORIZED
     */
    @Test
    fun `testUploadWithoutAuth - Returns 401 unauthorized`() {
        // Arrange
        val csvFile = File.createTempFile("valid", ".csv")
        csvFile.writeText("""
            account_id,owner_email
            123456789012,user@example.com
        """.trimIndent())

        val body = MultipartBody.builder()
            .addPart("csvFile", csvFile.name, MediaType.TEXT_PLAIN_TYPE, csvFile)
            .build()

        // Act & Assert
        val request = HttpRequest.POST("/api/import/upload-user-mappings-csv", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
        // No bearerAuth() - unauthenticated

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)

        // Cleanup
        csvFile.delete()
    }

    /**
     * Contract: POST /api/import/upload-user-mappings-csv with non-ADMIN user
     * Expected Response: 403 FORBIDDEN
     */
    @Test
    fun `testUploadWithNonAdminUser - Returns 403 forbidden`() {
        // Arrange
        val token = authenticateAsUser() // Regular user, not ADMIN
        val csvFile = File.createTempFile("valid", ".csv")
        csvFile.writeText("""
            account_id,owner_email
            123456789012,user@example.com
        """.trimIndent())

        val body = MultipartBody.builder()
            .addPart("csvFile", csvFile.name, MediaType.TEXT_PLAIN_TYPE, csvFile)
            .build()

        // Act & Assert
        val request = HttpRequest.POST("/api/import/upload-user-mappings-csv", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)

        // Cleanup
        csvFile.delete()
    }

    /**
     * Contract: POST /api/import/upload-user-mappings-csv with oversized file (>10MB)
     * Expected Response: 413 REQUEST ENTITY TOO LARGE or 400 BAD REQUEST with size error
     *
     * Note: Micronaut may return 413 at HTTP server level or 400 at application level
     */
    @Test
    fun `testUploadOversizedFile - Returns 413 or 400 for file exceeding 10MB`() {
        // Arrange
        val token = authenticateAsAdmin()
        // Create a file larger than 10MB (11MB of dummy data)
        val largeFile = File.createTempFile("large", ".csv")
        largeFile.writeText("account_id,owner_email\n" +
                           "123456789012,user@example.com\n" +
                           "x".repeat(11 * 1024 * 1024))

        val body = MultipartBody.builder()
            .addPart("csvFile", largeFile.name, MediaType.TEXT_PLAIN_TYPE, largeFile)
            .build()

        // Act & Assert
        val request = HttpRequest.POST("/api/import/upload-user-mappings-csv", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        // Accept either 413 (server level) or 400 (application level)
        assertTrue(exception.status == HttpStatus.REQUEST_ENTITY_TOO_LARGE ||
                   exception.status == HttpStatus.BAD_REQUEST,
                   "Should return 413 or 400 for oversized file")

        if (exception.status == HttpStatus.BAD_REQUEST) {
            assertTrue(exception.message!!.contains("size") || exception.message!!.contains("10MB") ||
                       exception.message!!.contains("large"),
                       "Error message should mention file size limit")
        }

        // Cleanup
        largeFile.delete()
    }

    /**
     * Contract: Verify response structure matches schema
     * All required fields present and correct types
     */
    @Test
    fun `testResponseStructure - Verify JSON schema compliance`() {
        // Arrange
        val token = authenticateAsAdmin()
        val csvFile = File.createTempFile("valid", ".csv")
        csvFile.writeText("""
            account_id,owner_email
            123456789012,user@example.com
        """.trimIndent())

        val body = MultipartBody.builder()
            .addPart("csvFile", csvFile.name, MediaType.TEXT_PLAIN_TYPE, csvFile)
            .build()

        // Act
        val request = HttpRequest.POST("/api/import/upload-user-mappings-csv", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val response = client.toBlocking().exchange(request, Map::class.java)

        // Assert - Verify all required fields exist
        val bodyMap = response.body()!!
        assertTrue(bodyMap.containsKey("message"), "Response must have 'message' field")
        assertTrue(bodyMap.containsKey("imported"), "Response must have 'imported' field")
        assertTrue(bodyMap.containsKey("skipped"), "Response must have 'skipped' field")
        assertTrue(bodyMap.containsKey("errors"), "Response must have 'errors' field")

        // Verify types
        assertTrue(bodyMap["message"] is String, "message must be String")
        assertTrue(bodyMap["imported"] is Number, "imported must be Number")
        assertTrue(bodyMap["skipped"] is Number, "skipped must be Number")
        assertTrue(bodyMap["errors"] is List<*>, "errors must be List")

        // Cleanup
        csvFile.delete()
    }

    /**
     * Contract: Test CSV with scientific notation account IDs (Excel export format)
     * Expected Response: 200 OK with correctly parsed account IDs
     */
    @Test
    fun `testUploadCSVWithScientificNotation - Returns 200 and parses correctly`() {
        // Arrange
        val token = authenticateAsAdmin()
        val csvFile = File.createTempFile("scientific", ".csv")
        csvFile.writeText("""
            account_id,owner_email
            9.98987E+11,user1@example.com
            1.23457E+11,user2@example.com
        """.trimIndent())

        val body = MultipartBody.builder()
            .addPart("csvFile", csvFile.name, MediaType.TEXT_PLAIN_TYPE, csvFile)
            .build()

        // Act
        val request = HttpRequest.POST("/api/import/upload-user-mappings-csv", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val response = client.toBlocking().exchange(request, ImportResult::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        val importResult = response.body()!!
        assertTrue(importResult.imported > 0, "Should successfully parse scientific notation")

        // Cleanup
        csvFile.delete()
    }

    /**
     * Contract: Test CSV with reversed column order (owner_email, account_id)
     * Expected Response: 200 OK - parser should handle any column order
     */
    @Test
    fun `testUploadCSVWithReversedColumns - Returns 200 and imports correctly`() {
        // Arrange
        val token = authenticateAsAdmin()
        val csvFile = File.createTempFile("reversed", ".csv")
        csvFile.writeText("""
            owner_email,account_id
            user1@example.com,123456789012
            user2@example.com,234567890123
        """.trimIndent())

        val body = MultipartBody.builder()
            .addPart("csvFile", csvFile.name, MediaType.TEXT_PLAIN_TYPE, csvFile)
            .build()

        // Act
        val request = HttpRequest.POST("/api/import/upload-user-mappings-csv", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val response = client.toBlocking().exchange(request, ImportResult::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        val importResult = response.body()!!
        assertEquals(2, importResult.imported, "Should handle reversed column order")

        // Cleanup
        csvFile.delete()
    }

    /**
     * Contract: Test CSV with case variations in headers (Account_ID, Owner_Email)
     * Expected Response: 200 OK - parser should be case-insensitive
     */
    @Test
    fun `testUploadCSVWithCaseVariations - Returns 200 and imports correctly`() {
        // Arrange
        val token = authenticateAsAdmin()
        val csvFile = File.createTempFile("case_headers", ".csv")
        csvFile.writeText("""
            Account_ID,Owner_Email
            123456789012,user@example.com
        """.trimIndent())

        val body = MultipartBody.builder()
            .addPart("csvFile", csvFile.name, MediaType.TEXT_PLAIN_TYPE, csvFile)
            .build()

        // Act
        val request = HttpRequest.POST("/api/import/upload-user-mappings-csv", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val response = client.toBlocking().exchange(request, ImportResult::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        assertEquals(1, response.body()!!.imported, "Should handle case variations")

        // Cleanup
        csvFile.delete()
    }

    /**
     * Contract: Test CSV with many extra columns (10+ columns, only 2 required extracted)
     * Expected Response: 200 OK - parser should ignore extra columns
     */
    @Test
    fun `testUploadCSVWithExtraColumns - Returns 200 and extracts only required`() {
        // Arrange
        val token = authenticateAsAdmin()
        val csvFile = File.createTempFile("extra_cols", ".csv")
        csvFile.writeText("""
            account_id,display_name,owner_email,status,region,tags,notes,created,updated,type
            123456789012,Test Account,user@example.com,ACTIVE,us-east-1,prod,ignore,2024-01-01,2024-01-02,standard
        """.trimIndent())

        val body = MultipartBody.builder()
            .addPart("csvFile", csvFile.name, MediaType.TEXT_PLAIN_TYPE, csvFile)
            .build()

        // Act
        val request = HttpRequest.POST("/api/import/upload-user-mappings-csv", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val response = client.toBlocking().exchange(request, ImportResult::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        assertEquals(1, response.body()!!.imported, "Should extract only required columns")

        // Cleanup
        csvFile.delete()
    }

    /**
     * Contract: Test CSV with partial success (mixed valid and invalid rows)
     * Expected Response: 200 OK with imported and skipped counts
     */
    @Test
    fun `testUploadCSVWithPartialSuccess - Returns 200 with detailed error list`() {
        // Arrange
        val token = authenticateAsAdmin()
        val csvFile = File.createTempFile("partial", ".csv")
        csvFile.writeText("""
            account_id,owner_email
            123456789012,user1@example.com
            invalid,not-an-email
            234567890123,user2@example.com
        """.trimIndent())

        val body = MultipartBody.builder()
            .addPart("csvFile", csvFile.name, MediaType.TEXT_PLAIN_TYPE, csvFile)
            .build()

        // Act
        val request = HttpRequest.POST("/api/import/upload-user-mappings-csv", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val response = client.toBlocking().exchange(request, ImportResult::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        val importResult = response.body()!!
        assertTrue(importResult.imported > 0, "Should import valid rows")
        assertTrue(importResult.skipped > 0, "Should skip invalid rows")
        assertTrue(importResult.errors.isNotEmpty(), "Should report errors for skipped rows")

        // Verify error structure
        val firstError = importResult.errors[0]
        assertNotNull(firstError.line, "Error should have line number")
        assertNotNull(firstError.reason, "Error should have reason")

        // Cleanup
        csvFile.delete()
    }

    /**
     * T020: Contract tests for CSV template download endpoint
     * GET /api/import/user-mapping-template-csv
     *
     * Related to: Feature 016 - User Story 3 (Download CSV Template)
     */

    /**
     * Contract: GET /api/import/user-mapping-template-csv with ADMIN user
     * Expected Response: 200 OK with CSV template content
     */
    @Test
    fun `testDownloadCSVTemplate - Returns 200 with template content`() {
        // Arrange
        val token = authenticateAsAdmin()

        // Act
        val request = HttpRequest.GET<String>("/api/import/user-mapping-template-csv")
            .bearerAuth(token)

        val response = client.toBlocking().exchange(request, String::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body(), "Response body must not be null")

        val templateContent = response.body()!!
        assertTrue(templateContent.isNotEmpty(), "Template must not be empty")

        // Verify CSV headers are present
        assertTrue(templateContent.contains("account_id"), "Template must contain account_id header")
        assertTrue(templateContent.contains("owner_email"), "Template must contain owner_email header")

        // Verify example row is present
        assertTrue(templateContent.contains("123456789012"), "Template must contain example account ID")
        assertTrue(templateContent.contains("user@example.com"), "Template must contain example email")
    }

    /**
     * Contract: Verify Content-Type header is text/csv or text/plain
     * Expected: Response includes proper content-type
     */
    @Test
    fun `testDownloadCSVTemplate - Returns text csv content type`() {
        // Arrange
        val token = authenticateAsAdmin()

        // Act
        val request = HttpRequest.GET<String>("/api/import/user-mapping-template-csv")
            .bearerAuth(token)

        val response = client.toBlocking().exchange(request, String::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        val contentType = response.contentType.orElse(null)
        assertNotNull(contentType, "Content-Type header must be present")

        // Accept either text/csv or text/plain
        assertTrue(
            contentType.toString().contains("text/csv") ||
            contentType.toString().contains("text/plain"),
            "Content-Type must be text/csv or text/plain, got: $contentType"
        )
    }

    /**
     * Contract: Verify Content-Disposition header for download
     * Expected: Response includes Content-Disposition with filename
     */
    @Test
    fun `testDownloadCSVTemplate - Returns Content-Disposition header with filename`() {
        // Arrange
        val token = authenticateAsAdmin()

        // Act
        val request = HttpRequest.GET<String>("/api/import/user-mapping-template-csv")
            .bearerAuth(token)

        val response = client.toBlocking().exchange(request, String::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        val contentDisposition = response.headers.get("Content-Disposition")
        assertNotNull(contentDisposition, "Content-Disposition header must be present")

        assertTrue(contentDisposition!!.contains("attachment"), "Content-Disposition must be attachment")
        assertTrue(contentDisposition.contains("filename"), "Content-Disposition must include filename")
        assertTrue(contentDisposition.contains("user-mapping-template.csv"),
            "Filename must be user-mapping-template.csv")
    }

    /**
     * Contract: GET /api/import/user-mapping-template-csv without authentication
     * Expected Response: 401 UNAUTHORIZED
     */
    @Test
    fun `testDownloadCSVTemplateWithoutAuth - Returns 401 unauthorized`() {
        // Act & Assert
        val request = HttpRequest.GET<String>("/api/import/user-mapping-template-csv")
        // No bearerAuth() - unauthenticated

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    /**
     * Contract: GET /api/import/user-mapping-template-csv with non-ADMIN user
     * Expected Response: 403 FORBIDDEN
     */
    @Test
    fun `testDownloadCSVTemplateWithNonAdminUser - Returns 403 forbidden`() {
        // Arrange
        val token = authenticateAsUser() // Regular user, not ADMIN

        // Act & Assert
        val request = HttpRequest.GET<String>("/api/import/user-mapping-template-csv")
            .bearerAuth(token)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    /**
     * Contract: Verify template content structure (valid CSV format)
     * Expected: Template is valid CSV with proper headers and example data
     */
    @Test
    fun `testDownloadCSVTemplate - Template is valid CSV format`() {
        // Arrange
        val token = authenticateAsAdmin()

        // Act
        val request = HttpRequest.GET<String>("/api/import/user-mapping-template-csv")
            .bearerAuth(token)

        val response = client.toBlocking().exchange(request, String::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        val templateContent = response.body()!!

        // Parse CSV to verify it's valid
        val lines = templateContent.trim().split("\n")
        assertTrue(lines.size >= 2, "Template must have at least header + 1 data row")

        val headers = lines[0].split(",")
        assertTrue(headers.contains("account_id"), "Header must contain account_id")
        assertTrue(headers.contains("owner_email"), "Header must contain owner_email")

        // Verify data row has same number of columns
        val dataRow = lines[1].split(",")
        assertEquals(headers.size, dataRow.size, "Data row must match header column count")
    }

    // Helper methods for authentication
    private fun authenticateAsAdmin(): String {
        val credentials = UsernamePasswordCredentials("admin", "admin")
        val request = HttpRequest.POST("/login", credentials)
        val response = client.toBlocking().exchange(request, Map::class.java)
        return response.body()!!["access_token"] as String
    }

    private fun authenticateAsUser(): String {
        val credentials = UsernamePasswordCredentials("user", "user")
        val request = HttpRequest.POST("/login", credentials)
        val response = client.toBlocking().exchange(request, Map::class.java)
        return response.body()!!["access_token"] as String
    }

    // DTO classes matching contract specification
    data class ImportResult(
        val message: String,
        val imported: Int,
        val skipped: Int,
        val errors: List<ImportError>
    )

    data class ImportError(
        val line: Int?,
        val field: String?,
        val reason: String?,
        val value: String?
    )
}
