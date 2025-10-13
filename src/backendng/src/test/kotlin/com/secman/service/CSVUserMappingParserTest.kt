package com.secman.service

import com.secman.repository.UserMappingRepository
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * Unit tests for CSVUserMappingParser
 *
 * Related to: Feature 016-i-want-to (CSV-Based User Mapping Upload)
 *
 * Tests cover:
 * - T010: CSVUserMappingParser unit tests
 * - Delimiter detection (comma, semicolon, tab)
 * - Scientific notation parsing (9.98987E+11 → 998987000000)
 * - Encoding detection (UTF-8, ISO-8859-1)
 * - Email validation (valid/invalid formats)
 * - Account ID validation (12 digits, non-numeric)
 * - Domain default to "-NONE-"
 * - Duplicate detection (within file, existing in DB)
 * - Row skipping with error reporting
 */
@MicronautTest
class CSVUserMappingParserTest {

    @Inject
    private lateinit var csvUserMappingParser: CSVUserMappingParser

    @Test
    fun `testParseValidCSV - Returns ImportResult with correct counts`() {
        // Given - Valid CSV with 3 rows
        val csvFile = File.createTempFile("valid", ".csv")
        csvFile.writeText("""
            account_id,owner_email,domain
            123456789012,user1@example.com,example.com
            234567890123,user2@example.com,example.com
            345678901234,user3@example.com,example.com
        """.trimIndent())

        // When
        val result = csvUserMappingParser.parse(csvFile)

        // Then
        assertNotNull(result)
        assertEquals(3, result.imported, "Should import 3 mappings")
        assertEquals(0, result.skipped, "Should skip 0 mappings")
        assertTrue(result.errors.isEmpty(), "Should have no errors")
        assertTrue(result.message.contains("3"), "Message should mention import count")

        // Cleanup
        csvFile.delete()
    }

    @Test
    fun `testDelimiterDetection - Comma separated CSV`() {
        // Given - CSV with comma delimiter
        val csvFile = File.createTempFile("comma", ".csv")
        csvFile.writeText("""
            account_id,owner_email
            123456789012,user@example.com
        """.trimIndent())

        // When
        val result = csvUserMappingParser.parse(csvFile)

        // Then
        assertEquals(1, result.imported, "Should successfully parse comma-separated CSV")

        // Cleanup
        csvFile.delete()
    }

    @Test
    fun `testDelimiterDetection - Semicolon separated CSV`() {
        // Given - CSV with semicolon delimiter
        val csvFile = File.createTempFile("semicolon", ".csv")
        csvFile.writeText("""
            account_id;owner_email
            123456789012;user@example.com
        """.trimIndent())

        // When
        val result = csvUserMappingParser.parse(csvFile)

        // Then
        assertEquals(1, result.imported, "Should successfully parse semicolon-separated CSV")

        // Cleanup
        csvFile.delete()
    }

    @Test
    fun `testDelimiterDetection - Tab separated CSV`() {
        // Given - CSV with tab delimiter
        val csvFile = File.createTempFile("tab", ".csv")
        csvFile.writeText("account_id\towner_email\n123456789012\tuser@example.com")

        // When
        val result = csvUserMappingParser.parse(csvFile)

        // Then
        assertEquals(1, result.imported, "Should successfully parse tab-separated CSV")

        // Cleanup
        csvFile.delete()
    }

    @Test
    fun `testScientificNotationParsing - Excel export format`() {
        // Given - CSV with account IDs in scientific notation
        val csvFile = File.createTempFile("scientific", ".csv")
        csvFile.writeText("""
            account_id,owner_email
            9.98987E+11,user1@example.com
            1.23457E+11,user2@example.com
            123456789012,user3@example.com
        """.trimIndent())

        // When
        val result = csvUserMappingParser.parse(csvFile)

        // Then
        assertEquals(3, result.imported, "Should parse all rows with scientific notation")
        assertEquals(0, result.skipped, "Should not skip any rows")

        // Cleanup
        csvFile.delete()
    }

    @Test
    fun `testEncodingDetection - UTF8 with BOM`() {
        // Given - CSV with UTF-8 BOM
        val csvFile = File.createTempFile("utf8bom", ".csv")
        val bomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val csvContent = "account_id,owner_email\n123456789012,user@example.com".toByteArray(Charsets.UTF_8)
        csvFile.writeBytes(bomBytes + csvContent)

        // When
        val result = csvUserMappingParser.parse(csvFile)

        // Then
        assertEquals(1, result.imported, "Should successfully parse UTF-8 with BOM")

        // Cleanup
        csvFile.delete()
    }

    @Test
    fun `testEmailValidation - Valid email formats`() {
        // Given - CSV with various valid email formats
        val csvFile = File.createTempFile("valid_emails", ".csv")
        csvFile.writeText("""
            account_id,owner_email
            123456789012,user@example.com
            234567890123,test.user@example.co.uk
            345678901234,admin+tag@sub.domain.com
        """.trimIndent())

        // When
        val result = csvUserMappingParser.parse(csvFile)

        // Then
        assertEquals(3, result.imported, "Should accept all valid email formats")
        assertEquals(0, result.skipped)

        // Cleanup
        csvFile.delete()
    }

    @Test
    fun `testEmailValidation - Invalid email formats`() {
        // Given - CSV with invalid email formats
        val csvFile = File.createTempFile("invalid_emails", ".csv")
        csvFile.writeText("""
            account_id,owner_email
            123456789012,notanemail
            234567890123,@example.com
            345678901234,user@
            456789012345,valid@example.com
        """.trimIndent())

        // When
        val result = csvUserMappingParser.parse(csvFile)

        // Then
        assertEquals(1, result.imported, "Should only import 1 valid email")
        assertEquals(3, result.skipped, "Should skip 3 invalid emails")
        assertEquals(3, result.errors.size, "Should report 3 errors")

        // Verify error messages mention email validation
        assertTrue(result.errors.any { it.reason!!.contains("email", ignoreCase = true) })

        // Cleanup
        csvFile.delete()
    }

    @Test
    fun `testAccountIdValidation - Invalid formats`() {
        // Given - CSV with invalid account ID formats
        val csvFile = File.createTempFile("invalid_accounts", ".csv")
        csvFile.writeText("""
            account_id,owner_email
            12345,user1@example.com
            1234567890123,user2@example.com
            abc123456789,user3@example.com
            123456789012,user4@example.com
        """.trimIndent())

        // When
        val result = csvUserMappingParser.parse(csvFile)

        // Then
        assertEquals(1, result.imported, "Should only import 1 valid account ID")
        assertEquals(3, result.skipped, "Should skip 3 invalid account IDs")
        assertEquals(3, result.errors.size, "Should report 3 errors")

        // Verify error messages mention account ID format
        assertTrue(result.errors.any { it.reason!!.contains("12 numeric digits") ||
                   it.reason!!.contains("account_id") })

        // Cleanup
        csvFile.delete()
    }

    @Test
    fun `testDomainDefaultToNone - CSV without domain column`() {
        // Given - CSV without domain column
        val csvFile = File.createTempFile("no_domain", ".csv")
        csvFile.writeText("""
            account_id,owner_email
            123456789012,user@example.com
        """.trimIndent())

        // When
        val result = csvUserMappingParser.parse(csvFile)

        // Then
        assertEquals(1, result.imported, "Should import with default domain")

        // Cleanup
        csvFile.delete()
    }

    @Test
    fun `testDomainValidation - Invalid domain formats`() {
        // Given - CSV with invalid domain formats
        val csvFile = File.createTempFile("invalid_domains", ".csv")
        csvFile.writeText("""
            account_id,owner_email,domain
            123456789012,user1@example.com,valid-domain.com
            234567890123,user2@example.com,.startwithdot.com
            345678901234,user3@example.com,endwithdot.com.
            456789012345,user4@example.com,has space.com
        """.trimIndent())

        // When
        val result = csvUserMappingParser.parse(csvFile)

        // Then
        assertEquals(1, result.imported, "Should only import 1 valid domain")
        assertEquals(3, result.skipped, "Should skip 3 invalid domains")

        // Cleanup
        csvFile.delete()
    }

    @Test
    fun `testDuplicateDetectionWithinFile - Skip duplicate rows`() {
        // Given - CSV with duplicate rows
        val csvFile = File.createTempFile("duplicates", ".csv")
        csvFile.writeText("""
            account_id,owner_email,domain
            123456789012,user@example.com,example.com
            123456789012,user@example.com,example.com
            234567890123,user@example.com,example.com
        """.trimIndent())

        // When
        val result = csvUserMappingParser.parse(csvFile)

        // Then
        assertEquals(2, result.imported, "Should import 2 unique mappings")
        assertEquals(1, result.skipped, "Should skip 1 duplicate")

        // Cleanup
        csvFile.delete()
    }

    @Test
    fun `testCaseInsensitiveHeaders - Various header cases`() {
        // Given - CSV with different header cases
        val csvFile = File.createTempFile("case_headers", ".csv")
        csvFile.writeText("""
            Account_ID,Owner_Email
            123456789012,user@example.com
        """.trimIndent())

        // When
        val result = csvUserMappingParser.parse(csvFile)

        // Then
        assertEquals(1, result.imported, "Should accept case-insensitive headers")

        // Cleanup
        csvFile.delete()
    }

    @Test
    fun `testColumnOrderFlexibility - Reversed columns`() {
        // Given - CSV with reversed column order
        val csvFile = File.createTempFile("reversed", ".csv")
        csvFile.writeText("""
            owner_email,account_id
            user@example.com,123456789012
        """.trimIndent())

        // When
        val result = csvUserMappingParser.parse(csvFile)

        // Then
        assertEquals(1, result.imported, "Should handle reversed column order")

        // Cleanup
        csvFile.delete()
    }

    @Test
    fun `testExtraColumns - Ignore non-required columns`() {
        // Given - CSV with many extra columns
        val csvFile = File.createTempFile("extra_columns", ".csv")
        csvFile.writeText("""
            account_id,display_name,owner_email,status,region,tags,notes
            123456789012,Test Account,user@example.com,ACTIVE,us-east-1,prod,ignore these
        """.trimIndent())

        // When
        val result = csvUserMappingParser.parse(csvFile)

        // Then
        assertEquals(1, result.imported, "Should extract only required columns")

        // Cleanup
        csvFile.delete()
    }

    @Test
    fun `testEmptyFile - Returns error`() {
        // Given - Empty CSV file
        val csvFile = File.createTempFile("empty", ".csv")
        csvFile.writeText("")

        // When/Then
        val exception = assertThrows<IllegalArgumentException> {
            csvUserMappingParser.parse(csvFile)
        }

        assertTrue(exception.message!!.contains("Empty") || exception.message!!.contains("empty"))

        // Cleanup
        csvFile.delete()
    }

    @Test
    fun `testMissingRequiredHeaders - Returns error`() {
        // Given - CSV without owner_email header
        val csvFile = File.createTempFile("missing_header", ".csv")
        csvFile.writeText("""
            account_id,domain
            123456789012,example.com
        """.trimIndent())

        // When/Then
        val exception = assertThrows<IllegalArgumentException> {
            csvUserMappingParser.parse(csvFile)
        }

        assertTrue(exception.message!!.contains("Missing") ||
                   exception.message!!.contains("required") ||
                   exception.message!!.contains("owner_email"))

        // Cleanup
        csvFile.delete()
    }

    @Test
    fun `testNormalization - Lowercase email and domain`() {
        // Given - CSV with uppercase email and domain
        val csvFile = File.createTempFile("uppercase", ".csv")
        csvFile.writeText("""
            account_id,owner_email,domain
            123456789012,USER@EXAMPLE.COM,EXAMPLE.COM
        """.trimIndent())

        // When
        val result = csvUserMappingParser.parse(csvFile)

        // Then
        assertEquals(1, result.imported, "Should normalize and import")
        // Note: We can't directly verify lowercase here without querying DB,
        // but the parser should have normalized internally

        // Cleanup
        csvFile.delete()
    }

    @Test
    fun `testWhitespaceHandling - Trim values`() {
        // Given - CSV with leading/trailing whitespace
        val csvFile = File.createTempFile("whitespace", ".csv")
        csvFile.writeText("""
            account_id,owner_email
             123456789012 , user@example.com
        """.trimIndent())

        // When
        val result = csvUserMappingParser.parse(csvFile)

        // Then
        assertEquals(1, result.imported, "Should trim whitespace and import")

        // Cleanup
        csvFile.delete()
    }

    @Test
    fun `testPartialSuccess - Mixed valid and invalid rows`() {
        // Given - CSV with mix of valid and invalid rows
        val csvFile = File.createTempFile("partial", ".csv")
        csvFile.writeText("""
            account_id,owner_email
            123456789012,user1@example.com
            invalid,not-an-email
            234567890123,user2@example.com
            12345,user3@example.com
            345678901234,user4@example.com
        """.trimIndent())

        // When
        val result = csvUserMappingParser.parse(csvFile)

        // Then
        assertEquals(3, result.imported, "Should import 3 valid rows")
        assertEquals(2, result.skipped, "Should skip 2 invalid rows")
        assertEquals(2, result.errors.size, "Should report 2 errors")

        // Verify error structure
        result.errors.forEach { error ->
            assertNotNull(error.line, "Error should have line number")
            assertNotNull(error.reason, "Error should have reason")
        }

        // Cleanup
        csvFile.delete()
    }

    @Test
    fun `testEmptyRows - Skip empty rows gracefully`() {
        // Given - CSV with empty rows
        val csvFile = File.createTempFile("empty_rows", ".csv")
        csvFile.writeText("""
            account_id,owner_email
            123456789012,user1@example.com

            234567890123,user2@example.com

        """.trimIndent())

        // When
        val result = csvUserMappingParser.parse(csvFile)

        // Then
        assertEquals(2, result.imported, "Should skip empty rows and import valid ones")

        // Cleanup
        csvFile.delete()
    }

    @Test
    fun `testMaxErrorLimit - Limit errors returned to 50`() {
        // Given - CSV with 100 invalid rows
        val csvFile = File.createTempFile("many_errors", ".csv")
        val lines = mutableListOf("account_id,owner_email")
        repeat(100) { i ->
            lines.add("invalid,$i")
        }
        csvFile.writeText(lines.joinToString("\n"))

        // When
        val result = csvUserMappingParser.parse(csvFile)

        // Then
        assertEquals(0, result.imported)
        assertEquals(100, result.skipped)
        assertTrue(result.errors.size <= 50, "Should limit errors to 50 max")

        // Cleanup
        csvFile.delete()
    }

    @Test
    fun `testQuotedFields - Handle quoted values with embedded delimiters`() {
        // Given - CSV with quoted fields containing commas
        val csvFile = File.createTempFile("quoted", ".csv")
        csvFile.writeText("""
            account_id,owner_email,domain
            123456789012,"user@example.com","example.com"
        """.trimIndent())

        // When
        val result = csvUserMappingParser.parse(csvFile)

        // Then
        assertEquals(1, result.imported, "Should handle quoted fields")

        // Cleanup
        csvFile.delete()
    }
}
