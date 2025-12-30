package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.domain.Requirement
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.RequirementRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.apache.poi.xssf.usermodel.XSSFWorkbook
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

            row.createCell(0).setCellValue(requirement.chapter ?: "")

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
            row.createCell(1).setCellValue(normString)

            row.createCell(2).setCellValue(requirement.shortreq)
            row.createCell(3).setCellValue(requirement.details ?: "")
            row.createCell(4).setCellValue(requirement.motivation ?: "")
            row.createCell(5).setCellValue(requirement.example ?: "")

            val useCaseString = if (requirement.usecases.isNotEmpty()) {
                requirement.usecases.joinToString(", ") { it.name }
            } else {
                requirement.usecase ?: ""
            }
            row.createCell(6).setCellValue(useCaseString)
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
     * Create Word document with requirements data.
     * Uses same format as RequirementController for consistency.
     */
    private fun createWordDocument(requirements: List<Requirement>): XWPFDocument {
        val document = XWPFDocument()

        // Title
        val titleParagraph = document.createParagraph()
        val titleRun = titleParagraph.createRun()
        titleRun.setText("Security Requirements Export")
        titleRun.isBold = true
        titleRun.fontSize = 18

        document.createParagraph()

        // Group by chapter
        val groupedByChapter = requirements.groupBy { it.chapter ?: "Uncategorized" }

        for ((chapter, chapterRequirements) in groupedByChapter) {
            // Chapter header
            val chapterParagraph = document.createParagraph()
            val chapterRun = chapterParagraph.createRun()
            chapterRun.setText("Chapter: $chapter")
            chapterRun.isBold = true
            chapterRun.fontSize = 14

            document.createParagraph()

            var requirementNumber = 1
            for (requirement in chapterRequirements) {
                // Requirement header
                val reqParagraph = document.createParagraph()
                val reqRun = reqParagraph.createRun()
                reqRun.setText("${requirementNumber}. ${requirement.shortreq}")
                reqRun.isBold = true

                // Details
                requirement.details?.let { details ->
                    val detailsParagraph = document.createParagraph()
                    val detailsRun = detailsParagraph.createRun()
                    detailsRun.setText(details)
                }

                // Motivation
                requirement.motivation?.let { motivation ->
                    val motivationParagraph = document.createParagraph()
                    val motivationRun = motivationParagraph.createRun()
                    motivationRun.setText("Motivation: $motivation")
                }

                // Example
                requirement.example?.let { example ->
                    val exampleParagraph = document.createParagraph()
                    val exampleRun = exampleParagraph.createRun()
                    exampleRun.setText("Example: $example")
                }

                // Norm reference
                requirement.norm?.let { norm ->
                    val normParagraph = document.createParagraph()
                    val normRun = normParagraph.createRun()
                    normRun.setText("Norm Reference: $norm")
                }

                document.createParagraph()
                requirementNumber++
            }
        }

        return document
    }
}
