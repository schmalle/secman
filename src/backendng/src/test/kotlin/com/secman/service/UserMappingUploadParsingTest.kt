package com.secman.service

import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class UserMappingUploadParsingTest {
    private val repository = mockk<com.secman.repository.UserMappingRepository>()
    private val links = mockk<WorkgroupAccountLinkService>()

    @Test fun `CSV and XLSX decode the same mapping without performing side effects`() {
        val file = Files.createTempFile("mapping-parse", ".csv")
        try {
            Files.writeString(file, "account_id,owner_email,domain\n123456789012,OWNER@example.test,example.test\n")
            val csv = CSVUserMappingParser(repository, links).readMappings(file.toFile())
            val bytes = ByteArrayOutputStream()
            XSSFWorkbook().use { workbook ->
                val sheet = workbook.createSheet()
                listOf(listOf("Email Address", "AWS Account ID", "Domain"),
                    listOf("OWNER@example.test", "123456789012", "example.test")).forEachIndexed { i, values ->
                    val row = sheet.createRow(i)
                    values.forEachIndexed { j, value -> row.createCell(j).setCellValue(value) }
                }
                workbook.write(bytes)
            }
            val excel = UserMappingImportService(repository).readMappings(ByteArrayInputStream(bytes.toByteArray()))
            assertEquals(csv.mappings, excel.mappings)
            assertTrue(csv.errors.isEmpty())
            assertTrue(excel.errors.isEmpty())
            verify { repository wasNot Called }
            verify { links wasNot Called }
        } finally { Files.deleteIfExists(file) }
    }
    @Test fun `CSV reports duplicate and invalid rows as skipped`() {
        val file = Files.createTempFile("mapping-skips", ".csv")
        try {
            Files.writeString(file, "account_id,owner_email,domain\n123456789012,owner@example.test,example.test\n123456789012,OWNER@example.test,example.test\ninvalid,owner@example.test,example.test\n")
            val parsed = CSVUserMappingParser(repository, links).readMappings(file.toFile())
            assertEquals(1, parsed.mappings.size)
            assertEquals(2, parsed.skipped)
            assertEquals(listOf("Row 4: invalid mapping"), parsed.errors)
            verify { repository wasNot Called }
        } finally { Files.deleteIfExists(file) }
    }

}
