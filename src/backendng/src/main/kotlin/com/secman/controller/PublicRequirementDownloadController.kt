package com.secman.controller

import com.secman.domain.Requirement
import com.secman.repository.RequirementRepository
import com.secman.repository.UseCaseRepository
import com.secman.service.RequirementWordContentRenderer
import com.secman.service.WordExportStyle
import io.micronaut.core.annotation.Nullable
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.http.server.types.files.StreamedFile
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import org.apache.poi.xwpf.usermodel.BreakType
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Controller("/api/reqdl")
@Secured(SecurityRule.IS_ANONYMOUS)
open class PublicRequirementDownloadController(
    private val requirementRepository: RequirementRepository,
    private val useCaseRepository: UseCaseRepository
) {

    @Serdeable
    data class UseCaseDto(val id: Long, val name: String)

    @Get("/usecases")
    fun listUseCases(): HttpResponse<List<UseCaseDto>> {
        val useCases = useCaseRepository.findAll()
            .map { UseCaseDto(it.id!!, it.name) }
            .sortedBy { it.name }
        return HttpResponse.ok(useCases)
    }

    @Get("/export/docx")
    fun exportToDocx(@Nullable @QueryValue("usecaseIds") usecaseIds: String?): HttpResponse<*> {
        val requirements: List<Requirement>
        val title: String
        val filenameSuffix: String

        if (!usecaseIds.isNullOrBlank()) {
            val ids = usecaseIds.split(",").mapNotNull { it.trim().toLongOrNull() }
            if (ids.isEmpty()) {
                return HttpResponse.badRequest(mapOf("error" to "Invalid use case IDs"))
            }

            val useCases = ids.mapNotNull { id -> useCaseRepository.findById(id).orElse(null) }
            if (useCases.isEmpty()) {
                return HttpResponse.notFound(mapOf("error" to "No valid use cases found"))
            }

            val useCaseNames = useCases.map { it.name }

            // Get requirements that have at least one of the selected use cases
            val reqSet = mutableSetOf<Long>()
            val allReqs = mutableListOf<Requirement>()
            for (id in ids) {
                for (req in requirementRepository.findByUsecaseId(id)) {
                    if (reqSet.add(req.id!!)) {
                        allReqs.add(req)
                    }
                }
            }

            requirements = allReqs.sortedWith(
                compareBy<Requirement> { it.chapter ?: "" }.thenBy { it.id ?: 0 }
            )
            title = "Requirements - ${useCaseNames.joinToString(", ")}"
            filenameSuffix = "_${useCaseNames.joinToString("_") { it.replace(" ", "") }}"
        } else {
            requirements = requirementRepository.findAll().sortedWith(
                compareBy<Requirement> { it.chapter ?: "" }.thenBy { it.id ?: 0 }
            )
            title = "All Requirements"
            filenameSuffix = ""
        }

        if (requirements.isEmpty()) {
            return HttpResponse.ok(mapOf("message" to "No requirements found"))
        }

        val document = createWordDocument(requirements, title)
        val outputStream = ByteArrayOutputStream()
        document.write(outputStream)
        document.close()

        val dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        // Security: Sanitize filename to prevent Content-Disposition header injection
        // Allow only alphanumeric, dash, underscore, and dot characters
        val safeSuffix = filenameSuffix.replace(Regex("[^a-zA-Z0-9_-]"), "")
        val filename = "requirements${safeSuffix}_$dateStr.docx"
            .take(200) // Limit filename length
        val inputStream = ByteArrayInputStream(outputStream.toByteArray())

        return HttpResponse.ok(StreamedFile(inputStream, MediaType.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")))
            .header("Content-Disposition", "attachment; filename=\"$filename\"")
    }

    private fun createWordDocument(requirements: List<Requirement>, title: String): XWPFDocument {
        val document = XWPFDocument()
        WordExportStyle.setStandardMargins(document)
        WordExportStyle.addStandardHeaderFooter(document, title)

        // Title
        val kicker = document.createParagraph()
        kicker.alignment = ParagraphAlignment.CENTER
        WordExportStyle.run(kicker, "SECURITY REQUIREMENTS", size = WordExportStyle.SIZE_KICKER, bold = true, color = WordExportStyle.COLOR_ACCENT)

        val titleParagraph = document.createParagraph()
        titleParagraph.alignment = ParagraphAlignment.CENTER
        titleParagraph.spacingAfter = 120
        WordExportStyle.run(titleParagraph, title, size = WordExportStyle.SIZE_DOCUMENT_TITLE, bold = true)

        // Generation date
        val dateParagraph = document.createParagraph()
        dateParagraph.alignment = ParagraphAlignment.CENTER
        WordExportStyle.run(
            dateParagraph,
            "Generated on ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}",
            color = WordExportStyle.COLOR_SECONDARY
        )

        document.createParagraph()

        // Table of Contents placeholder
        val tocParagraph = document.createParagraph()
        WordExportStyle.run(tocParagraph, "Table of Contents", size = WordExportStyle.SIZE_SECTION_HEADING, bold = true)

        val tocFieldParagraph = document.createParagraph()
        WordExportStyle.run(
            tocFieldParagraph,
            "(Please update this field manually in Word: right-click → Update Field)",
            size = WordExportStyle.SIZE_META, italic = true, color = WordExportStyle.COLOR_SECONDARY
        )

        // Page break
        document.createParagraph().createRun().addBreak(BreakType.PAGE)

        RequirementWordContentRenderer.append(document, requirements)

        return document
    }
}
