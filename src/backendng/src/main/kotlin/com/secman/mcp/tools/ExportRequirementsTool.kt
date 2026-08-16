package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.util.ExcelSanitizer
import com.secman.domain.Requirement
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.RequirementRepository
import com.secman.service.RequirementWordContentRenderer
import com.secman.service.WordExportStyle
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * MCP tool for exporting security requirements to Excel or Word format.
 * Feature: 057-cli-mcp-requirements
 *
 * Returns base64-encoded file content with metadata for AI assistant processing.
 */
@Singleton
class ExportRequirementsTool(
    @Inject private val requirementRepository: RequirementRepository
) : McpTool {

    override val name = "export_requirements"
    override val description = "Export all requirements to Excel (xlsx) or Word (docx) format"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "format" to mapOf(
                "type" to "string",
                "enum" to listOf("xlsx", "docx"),
                "description" to "Export format: xlsx for Excel, docx for Word"
            )
        ),
        "required" to listOf("format")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // Mirrors RequirementController's own @Secured("ADMIN", "REQ", "SECCHAMPION") boundary —
        // the requirement corpus is not asset/owner-scoped, so a role gate is the right control
        // here rather than a row-scope check.
        requireAnyRole(
            context, "ADMIN", "REQ", "SECCHAMPION",
            code = "ROLE_REQUIRED",
            message = "ADMIN, REQ or SECCHAMPION role required to export requirements"
        )?.let { return it }

        val format = arguments["format"] as? String
            ?: return McpToolResult.error("VALIDATION_ERROR", "Format parameter is required")

        if (format !in listOf("xlsx", "docx")) {
            return McpToolResult.error("VALIDATION_ERROR", "Format must be 'xlsx' or 'docx'")
        }

        try {
            // Get all requirements sorted by chapter and ID
            val requirements = requirementRepository.findAll().sortedWith(
                compareBy<Requirement> { it.chapter ?: "" }.thenBy { it.id ?: 0 }
            )

            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val (bytes, contentType, filename) = when (format) {
                "xlsx" -> {
                    val workbook = createExcelWorkbook(requirements)
                    val outputStream = ByteArrayOutputStream()
                    workbook.write(outputStream)
                    workbook.close()
                    Triple(
                        outputStream.toByteArray(),
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "requirements_export_$timestamp.xlsx"
                    )
                }
                "docx" -> {
                    val document = createWordDocument(requirements)
                    val outputStream = ByteArrayOutputStream()
                    document.write(outputStream)
                    document.close()
                    Triple(
                        outputStream.toByteArray(),
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "requirements_export_$timestamp.docx"
                    )
                }
                else -> return McpToolResult.error("VALIDATION_ERROR", "Invalid format")
            }

            val base64Content = Base64.getEncoder().encodeToString(bytes)

            val result = mapOf(
                "data" to base64Content,
                "filename" to filename,
                "format" to format,
                "contentType" to contentType,
                "requirementCount" to requirements.size,
                "fileSizeBytes" to bytes.size
            )

            return McpToolResult.success(result)

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to export requirements: ${e.message}")
        }
    }

    /**
     * Create Excel workbook with requirements data.
     * Uses same format as RequirementController for consistency.
     */
    private fun createExcelWorkbook(requirements: List<Requirement>): XSSFWorkbook {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Reqs")

        // Header row
        val headerRow = sheet.createRow(0)
        val headers = arrayOf("Chapter", "Norm", "Short req", "DetailsEN", "MotivationEN", "ExampleEN", "UseCase")

        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)

            val headerStyle = workbook.createCellStyle()
            val headerFont = workbook.createFont()
            headerFont.bold = true
            headerStyle.setFont(headerFont)
            cell.cellStyle = headerStyle
        }

        // Data rows
        requirements.forEachIndexed { index, requirement ->
            val row = sheet.createRow(index + 1)

            row.createCell(0).setCellValue(ExcelSanitizer.sanitize(requirement.chapter))

            val normString = if (requirement.norms.isNotEmpty()) {
                requirement.norms.joinToString("; ") { norm ->
                    if (norm.version.isNotEmpty()) {
                        "${norm.name.substringBefore(':')}: ${norm.version}: ${norm.name.substringAfter(':', norm.name)}"
                    } else {
                        norm.name
                    }
                }
            } else {
                requirement.norm ?: ""
            }
            row.createCell(1).setCellValue(ExcelSanitizer.sanitize(normString))

            row.createCell(2).setCellValue(ExcelSanitizer.sanitize(requirement.shortreq))
            row.createCell(3).setCellValue(ExcelSanitizer.sanitize(requirement.details))
            row.createCell(4).setCellValue(ExcelSanitizer.sanitize(requirement.motivation))
            row.createCell(5).setCellValue(ExcelSanitizer.sanitize(requirement.example))

            val useCaseString = if (requirement.usecases.isNotEmpty()) {
                requirement.usecases.joinToString(", ") { it.name }
            } else {
                requirement.usecase ?: ""
            }
            row.createCell(6).setCellValue(ExcelSanitizer.sanitize(useCaseString))
        }

        // Auto-size columns
        for (i in headers.indices) {
            sheet.autoSizeColumn(i)
            if (sheet.getColumnWidth(i) < 2000) {
                sheet.setColumnWidth(i, 2000)
            }
        }

        return workbook
    }

    /**
     * Create Word document with requirements data, styled with [WordExportStyle] and rendered
     * through [RequirementWordContentRenderer] — the same design and body-rendering logic as
     * every other requirement export, so a document fetched via MCP looks identical to one
     * downloaded through the UI.
     */
    private fun createWordDocument(requirements: List<Requirement>): XWPFDocument {
        val document = XWPFDocument()
        val title = "Security Requirements Export"
        WordExportStyle.setStandardMargins(document)
        WordExportStyle.addStandardHeaderFooter(document, title)

        val kicker = document.createParagraph()
        kicker.alignment = ParagraphAlignment.CENTER
        WordExportStyle.run(kicker, "SECURITY REQUIREMENTS", size = WordExportStyle.SIZE_KICKER, bold = true, color = WordExportStyle.COLOR_ACCENT)

        val titleParagraph = document.createParagraph()
        titleParagraph.alignment = ParagraphAlignment.CENTER
        titleParagraph.spacingAfter = 120
        WordExportStyle.run(titleParagraph, title, size = WordExportStyle.SIZE_DOCUMENT_TITLE, bold = true)

        document.createParagraph()

        RequirementWordContentRenderer.append(document, requirements)

        return document
    }
}
