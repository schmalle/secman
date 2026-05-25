package com.secman.service

import com.secman.domain.ApplicationRegister
import com.secman.domain.Asset
import com.secman.repository.ApplicationRegisterRepository
import io.mockk.every
import io.mockk.mockk
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ApplicationRegisterExportServiceTest {

    private val repository: ApplicationRegisterRepository = mockk()
    private val service = ApplicationRegisterExportService(repository)

    @Test
    fun `exportApplications returns applications with assets from repository`() {
        val application = application()
        every { repository.findAllWithAssets() } returns listOf(application)

        val result = service.exportApplications()

        assertThat(result).containsExactly(application)
    }

    @Test
    fun `writeToExcel writes application register fields and assigned assets`() {
        val application = application().apply {
            applicationTechnology = "=Spreadsheet formula"
            lastQualityCheck = LocalDate.of(2026, 5, 24)
            createdAt = LocalDateTime.of(2026, 5, 24, 10, 30, 0)
            assets.add(Asset(name = "Asset Two", type = "Server", owner = "Owner"))
            assets.add(Asset(name = "Asset One", type = "Server", owner = "Owner"))
        }

        val output = service.writeToExcel(listOf(application))

        XSSFWorkbook(output.toByteArray().inputStream()).use { workbook ->
            val sheet = workbook.getSheet("Applications")
            assertThat(sheet.getRow(0).getCell(0).stringCellValue).isEqualTo("CAR ID")
            assertThat(sheet.getRow(1).getCell(0).stringCellValue).isEqualTo("CAR-1")
            assertThat(sheet.getRow(1).getCell(1).stringCellValue).isEqualTo("Application")
            assertThat(sheet.getRow(1).getCell(6).stringCellValue).isEqualTo("'=Spreadsheet formula")
            assertThat(sheet.getRow(1).getCell(8).stringCellValue).isEqualTo("2026-05-24")
            assertThat(sheet.getRow(1).getCell(19).stringCellValue).isEqualTo("Asset One, Asset Two")
            assertThat(sheet.getRow(1).getCell(21).stringCellValue).isEqualTo("2026-05-24 10:30:00")
        }
    }

    private fun application(): ApplicationRegister {
        return ApplicationRegister(
            carId = "CAR-1",
            name = "Application",
            criticality = "High",
            operationalStatus = "Live",
            businessOwner = "Business Owner",
            applicationManager = "Application Manager"
        )
    }
}
