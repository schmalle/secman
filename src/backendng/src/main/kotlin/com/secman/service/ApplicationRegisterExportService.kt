package com.secman.service

import com.secman.domain.ApplicationRegister
import com.secman.repository.ApplicationRegisterRepository
import com.secman.util.ExcelSanitizer
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter

@Singleton
open class ApplicationRegisterExportService(
    private val repository: ApplicationRegisterRepository
) {

    private val log = LoggerFactory.getLogger(ApplicationRegisterExportService::class.java)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @Transactional
    open fun exportApplications(): List<ApplicationRegister> {
        val applications = repository.findAllWithAssets()
        log.info("Found {} application register entries for export", applications.size)
        return applications
    }

    open fun writeToExcel(applications: List<ApplicationRegister>): ByteArrayOutputStream {
        log.info("Writing {} application register entries to Excel format", applications.size)

        val workbook = SXSSFWorkbook(100)
        workbook.setCompressTempFiles(true)

        try {
            val sheet = workbook.createSheet("Applications")
            val styles = createStyles(workbook)

            createHeaderRow(sheet, styles.header)
            applications.forEachIndexed { index, application ->
                createApplicationRow(sheet, index + 1, application, styles)
            }
            setFixedColumnWidths(sheet)

            val outputStream = ByteArrayOutputStream()
            workbook.write(outputStream)
            return outputStream
        } finally {
            workbook.close()
        }
    }

    private fun createStyles(workbook: Workbook): ApplicationRegisterStyles {
        val headerStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            val font = workbook.createFont()
            font.bold = true
            setFont(font)
        }
        val textStyle = workbook.createCellStyle()
        val wrapTextStyle = workbook.createCellStyle().apply {
            wrapText = true
        }

        return ApplicationRegisterStyles(headerStyle, textStyle, wrapTextStyle)
    }

    private fun createHeaderRow(sheet: Sheet, headerStyle: CellStyle) {
        val headers = listOf(
            "CAR ID",
            "Name",
            "Criticality",
            "Operational Status",
            "Business Owner",
            "Application Manager",
            "Application Technology",
            "Application Architecture",
            "Last Quality Check",
            "Information Classification",
            "Processing of Personal Data",
            "ICS Relevant",
            "Export Control Relevant",
            "Operation Model",
            "Production Operating Hours",
            "Service Operating Hours",
            "Backup Recovery URL",
            "Incident Assignment Group",
            "CMDB Workspace URL",
            "Assigned Assets",
            "Notes",
            "Created At",
            "Updated At"
        )

        val row = sheet.createRow(0)
        headers.forEachIndexed { index, header ->
            row.createCell(index).apply {
                setCellValue(header)
                cellStyle = headerStyle
            }
        }
    }

    private fun createApplicationRow(
        sheet: Sheet,
        rowNum: Int,
        application: ApplicationRegister,
        styles: ApplicationRegisterStyles
    ) {
        val row = sheet.createRow(rowNum)
        val values = listOf(
            application.carId,
            application.name,
            application.criticality,
            application.operationalStatus,
            application.businessOwner,
            application.applicationManager,
            application.applicationTechnology,
            application.applicationArchitecture,
            application.lastQualityCheck?.format(dateFormatter),
            application.informationClassification,
            application.processingOfPersonalData,
            application.icsRelevant,
            application.applicationExportControlRelevant,
            application.operationModel,
            application.productionOperatingHours,
            application.serviceOperatingHours,
            application.backupRecoveryUrl,
            application.incidentAssignmentGroup,
            application.cmdbWorkspaceUrl,
            application.assets.sortedBy { it.name }.joinToString(", ") { it.name },
            application.notes,
            application.createdAt?.format(dateTimeFormatter),
            application.updatedAt?.format(dateTimeFormatter)
        )

        values.forEachIndexed { index, value ->
            row.createCell(index).apply {
                setCellValue(ExcelSanitizer.sanitize(value))
                cellStyle = if (index in WRAPPED_COLUMNS) styles.wrapText else styles.text
            }
        }
    }

    private fun setFixedColumnWidths(sheet: Sheet) {
        val widths = listOf(
            3000, 7000, 4000, 5000, 6000, 6000, 7000, 7000, 4500, 6000, 7000, 4000,
            5000, 5000, 6000, 6000, 8000, 7000, 8000, 9000, 9000, 5000, 5000
        )
        widths.forEachIndexed { index, width -> sheet.setColumnWidth(index, width) }
    }

    private data class ApplicationRegisterStyles(
        val header: CellStyle,
        val text: CellStyle,
        val wrapText: CellStyle
    )

    private companion object {
        val WRAPPED_COLUMNS = setOf(6, 7, 10, 14, 15, 16, 17, 18, 19, 20)
    }
}
