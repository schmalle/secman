package com.secman.util

import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for ExcelCellUtils
 *
 * Covers the shared cell helpers used by VulnerabilityImportService and
 * UserMappingImportService: string, numeric (integer, decimal, large),
 * date, formula and blank cells, plus header-map lookups.
 */
class ExcelCellUtilsTest {

    private val workbook = XSSFWorkbook()
    private val sheet = workbook.createSheet("test")
    private val row: Row = sheet.createRow(0)

    @AfterEach
    fun tearDown() {
        workbook.close()
    }

    @Test
    fun `string cell returns its value`() {
        val cell = row.createCell(0)
        cell.setCellValue("hostname-01")

        assertThat(ExcelCellUtils.getCellValueAsString(cell)).isEqualTo("hostname-01")
    }

    @Test
    fun `integer-valued numeric cell has no decimal point`() {
        val cell = row.createCell(0)
        cell.setCellValue(42.0)

        assertThat(ExcelCellUtils.getCellValueAsString(cell)).isEqualTo("42")
    }

    @Test
    fun `decimal numeric cell keeps its fraction`() {
        val cell = row.createCell(0)
        cell.setCellValue(3.14)

        assertThat(ExcelCellUtils.getCellValueAsString(cell)).isEqualTo("3.14")
    }

    @Test
    fun `numeric cell goes through DataFormatter General formatting`() {
        // Pins current production behavior (lifted verbatim from the import services):
        // a 12-digit numeric in a General-format cell renders the way Excel's General
        // rule does — scientific notation at this magnitude. Real-world AWS account ID
        // columns arrive as text or explicitly formatted cells, which keep full digits
        // (see `explicitly number-formatted cell keeps all digits` below).
        val cell = row.createCell(0)
        cell.setCellValue(123456789012.0)

        assertThat(ExcelCellUtils.getCellValueAsString(cell)).isEqualTo("1.23457E+11")
    }

    @Test
    fun `explicitly number-formatted cell keeps all digits`() {
        val cell = row.createCell(0)
        cell.setCellValue(123456789012.0)
        val style = workbook.createCellStyle()
        style.dataFormat = workbook.createDataFormat().getFormat("0")
        cell.cellStyle = style

        assertThat(ExcelCellUtils.getCellValueAsString(cell)).isEqualTo("123456789012")
    }

    @Test
    fun `date-formatted cell returns LocalDateTime string`() {
        val cell = row.createCell(0)
        cell.setCellValue(java.time.LocalDateTime.of(2024, 5, 15, 10, 30, 0))
        val dateStyle = workbook.createCellStyle()
        dateStyle.dataFormat = workbook.creationHelper.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss")
        cell.cellStyle = dateStyle

        assertThat(ExcelCellUtils.getCellValueAsString(cell)).isEqualTo("2024-05-15T10:30")
    }

    @Test
    fun `numeric formula cell renders as formula text (no evaluator wired)`() {
        // Pins current production behavior: the NUMERIC cached-result branch formats
        // via DataFormatter WITHOUT a FormulaEvaluator, which renders the formula
        // string itself, not the cached value. (String-result formulas DO return the
        // cached string via richStringCellValue — covered below.)
        val cell = row.createCell(0)
        cell.cellFormula = "1+2"
        workbook.creationHelper.createFormulaEvaluator().evaluateFormulaCell(cell)

        assertThat(ExcelCellUtils.getCellValueAsString(cell)).isEqualTo("1+2")
    }

    @Test
    fun `string formula cell returns cached string result`() {
        val cell = row.createCell(0)
        cell.cellFormula = "CONCATENATE(\"a\",\"b\")"
        workbook.creationHelper.createFormulaEvaluator().evaluateFormulaCell(cell)

        assertThat(ExcelCellUtils.getCellValueAsString(cell)).isEqualTo("ab")
    }

    @Test
    fun `blank cell returns empty string`() {
        val cell = row.createCell(0)

        assertThat(ExcelCellUtils.getCellValueAsString(cell)).isEqualTo("")
    }

    @Test
    fun `boolean cell returns true or false`() {
        val cell = row.createCell(0)
        cell.setCellValue(true)

        assertThat(ExcelCellUtils.getCellValueAsString(cell)).isEqualTo("true")
    }

    @Test
    fun `getCellValue resolves cell via header map`() {
        row.createCell(2).setCellValue("value-c")
        val headerMap = mapOf("Hostname" to 2)

        assertThat(ExcelCellUtils.getCellValue(row, headerMap, "Hostname")).isEqualTo("value-c")
    }

    @Test
    fun `getCellValue returns null for missing header`() {
        row.createCell(0).setCellValue("value-a")
        val headerMap = mapOf("Hostname" to 0)

        assertThat(ExcelCellUtils.getCellValue(row, headerMap, "Unknown Header")).isNull()
    }

    @Test
    fun `getCellValue returns null when mapped cell does not exist`() {
        val headerMap = mapOf("Hostname" to 5)

        assertThat(ExcelCellUtils.getCellValue(row, headerMap, "Hostname")).isNull()
    }
}
