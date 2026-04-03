package com.secman.controller

import com.secman.domain.Requirement
import com.secman.repository.RequirementRepository
import com.secman.repository.UseCaseRepository
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

        // Title
        val titleParagraph = document.createParagraph()
        titleParagraph.alignment = ParagraphAlignment.CENTER
        val titleRun = titleParagraph.createRun()
        titleRun.setText(title)
        titleRun.fontSize = 18
        titleRun.isBold = true

        // Generation date
        val dateParagraph = document.createParagraph()
        dateParagraph.alignment = ParagraphAlignment.CENTER
        val dateRun = dateParagraph.createRun()
        dateRun.setText("Generated on: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")

        document.createParagraph()

        // Table of Contents placeholder
        val tocParagraph = document.createParagraph()
        val tocRun = tocParagraph.createRun()
        tocRun.setText("Table of Contents")
        tocRun.fontSize = 14
        tocRun.isBold = true

        val tocFieldParagraph = document.createParagraph()
        val tocFieldRun = tocFieldParagraph.createRun()
        tocFieldRun.setText("(Please update this field manually in Word: right-click → Update Field)")

        // Page break
        document.createParagraph().createRun().addBreak(BreakType.PAGE)

        // Group requirements by chapter
        val requirementsByChapter = requirements.groupBy { it.chapter ?: "No Chapter" }

        var isFirstChapter = true
        for ((chapter, chapterRequirements) in requirementsByChapter) {
            if (!isFirstChapter) {
                document.createParagraph().createRun().addBreak(BreakType.PAGE)
            }
            isFirstChapter = false

            // Chapter heading
            val chapterParagraph = document.createParagraph()
            chapterParagraph.style = "Heading1"
            val chapterRun = chapterParagraph.createRun()
            chapterRun.setText(chapter)
            chapterRun.fontSize = 16
            chapterRun.isBold = true

            document.createParagraph()

            for (requirement in chapterRequirements) {
                // Requirement header with light green background
                val reqHeaderParagraph = document.createParagraph()
                val ctp = reqHeaderParagraph.ctp
                val ppr = if (ctp.isSetPPr) ctp.pPr else ctp.addNewPPr()
                val shd = if (ppr.isSetShd) ppr.shd else ppr.addNewShd()
                shd.fill = "C1D5C0"

                val reqHeaderRun = reqHeaderParagraph.createRun()
                reqHeaderRun.setText("${requirement.internalId}: ${requirement.shortreq}")
                reqHeaderRun.fontSize = 12
                reqHeaderRun.isBold = true

                // Details
                requirement.details?.let { details ->
                    val detailsParagraph = document.createParagraph()
                    val detailsRun = detailsParagraph.createRun()
                    detailsRun.setText(details)
                }

                // Motivation
                requirement.motivation?.let { motivation ->
                    val motivationParagraph = document.createParagraph()
                    val motivationLabelRun = motivationParagraph.createRun()
                    motivationLabelRun.setText("Motivation: ")
                    motivationLabelRun.isBold = true
                    val motivationValueRun = motivationParagraph.createRun()
                    motivationValueRun.setText(motivation)
                }

                // Example
                requirement.example?.let { example ->
                    val exampleParagraph = document.createParagraph()
                    val exampleLabelRun = exampleParagraph.createRun()
                    exampleLabelRun.setText("Example: ")
                    exampleLabelRun.isBold = true
                    val exampleValueRun = exampleParagraph.createRun()
                    exampleValueRun.setText(example)
                }

                // Norm reference
                requirement.norm?.let { norm ->
                    val normParagraph = document.createParagraph()
                    val normLabelRun = normParagraph.createRun()
                    normLabelRun.setText("Norm Reference: ")
                    normLabelRun.isBold = true
                    val normValueRun = normParagraph.createRun()
                    normValueRun.setText(norm)
                }

                // Internal ID with use cases
                val canonicalUseCases = setOf("IT", "OT", "NT")
                val idSuffix = buildString {
                    append(requirement.internalId.removePrefix("REQ-"))
                    append(".")
                    append(requirement.versionNumber)
                    val usecaseNames = requirement.usecases
                        .map { it.name }
                        .filter { it in canonicalUseCases }
                        .sorted()
                    for (uc in usecaseNames) {
                        append(".")
                        append(uc)
                    }
                }
                val idParagraph = document.createParagraph()
                idParagraph.alignment = ParagraphAlignment.LEFT
                val idRun = idParagraph.createRun()
                idRun.setText("ID $idSuffix")
                idRun.fontSize = 8
                idRun.color = "999999"

                document.createParagraph()
            }
        }

        return document
    }
}
