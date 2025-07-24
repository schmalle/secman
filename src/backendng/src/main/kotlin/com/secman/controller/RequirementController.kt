package com.secman.controller

import com.secman.domain.Requirement
import com.secman.domain.UseCase
import com.secman.domain.Norm
import com.secman.repository.RequirementRepository
import com.secman.repository.UseCaseRepository
import com.secman.repository.NormRepository
import com.secman.service.TranslationServiceSimple
import io.micronaut.core.annotation.Nullable
import io.micronaut.data.model.Pageable
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.http.server.types.files.StreamedFile
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.transaction.Transactional
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

@Controller("/api/requirements")
@Secured(SecurityRule.IS_AUTHENTICATED)
open class RequirementController(
    private val requirementRepository: RequirementRepository,
    private val useCaseRepository: UseCaseRepository,
    private val normRepository: NormRepository,
    private val translationService: TranslationServiceSimple
) {

    @Serdeable
    data class RequirementCreateRequest(
        val shortreq: String,
        val details: String? = null,
        val language: String? = null,
        val example: String? = null,
        val motivation: String? = null,
        val usecase: String? = null,
        val norm: String? = null,
        val chapter: String? = null,
        val usecaseIds: List<Long>? = null,
        val normIds: List<Long>? = null
    )

    @Serdeable
    data class RequirementUpdateRequest(
        val shortreq: String? = null,
        val details: String? = null,
        val language: String? = null,
        val example: String? = null,
        val motivation: String? = null,
        val usecase: String? = null,
        val norm: String? = null,
        val chapter: String? = null,
        val usecaseIds: List<Long>? = null,
        val normIds: List<Long>? = null
    )

    @Serdeable
    data class RequirementResponse(
        val id: Long,
        val shortreq: String,
        val details: String?,
        val language: String?,
        val example: String?,
        val motivation: String?,
        val usecase: String?,
        val norm: String?,
        val chapter: String?,
        val usecases: List<UseCaseResponse>,
        val norms: List<NormResponse>,
        val createdAt: String?,
        val updatedAt: String?
    ) {
        companion object {
            fun from(requirement: Requirement): RequirementResponse {
                return RequirementResponse(
                    id = requirement.id!!,
                    shortreq = requirement.shortreq,
                    details = requirement.details,
                    language = requirement.language,
                    example = requirement.example,
                    motivation = requirement.motivation,
                    usecase = requirement.usecase,
                    norm = requirement.norm,
                    chapter = requirement.chapter,
                    usecases = requirement.usecases.map { UseCaseResponse.from(it) },
                    norms = requirement.norms.map { NormResponse.from(it) },
                    createdAt = requirement.createdAt?.toString(),
                    updatedAt = requirement.updatedAt?.toString()
                )
            }
        }
    }

    @Serdeable
    data class UseCaseResponse(
        val id: Long,
        val name: String
    ) {
        companion object {
            fun from(useCase: UseCase): UseCaseResponse {
                return UseCaseResponse(
                    id = useCase.id!!,
                    name = useCase.name
                )
            }
        }
    }

    @Serdeable
    data class NormResponse(
        val id: Long,
        val name: String,
        val version: String
    ) {
        companion object {
            fun from(norm: Norm): NormResponse {
                return NormResponse(
                    id = norm.id!!,
                    name = norm.name,
                    version = norm.version
                )
            }
        }
    }

    @Get
    fun list(): HttpResponse<List<RequirementResponse>> {
        val requirements = requirementRepository.findAll().map { RequirementResponse.from(it) }
        return HttpResponse.ok(requirements)
    }

    @Post
    @Transactional
    open fun create(@Body request: RequirementCreateRequest): HttpResponse<*> {
        if (request.shortreq.isBlank()) {
            return HttpResponse.badRequest(mapOf("error" to "Short requirement is required"))
        }

        try {
            val requirement = Requirement(
                shortreq = request.shortreq,
                details = request.details,
                language = request.language,
                example = request.example,
                motivation = request.motivation,
                usecase = request.usecase,
                norm = request.norm,
                chapter = request.chapter
            )

            // Handle use case relationships
            request.usecaseIds?.let { ids ->
                val useCases = mutableSetOf<UseCase>()
                ids.forEach { id ->
                    useCaseRepository.findById(id).ifPresent { useCases.add(it) }
                }
                requirement.usecases = useCases
            }

            // Handle norm relationships
            request.normIds?.let { ids ->
                val norms = mutableSetOf<Norm>()
                ids.forEach { id ->
                    normRepository.findById(id).ifPresent { norms.add(it) }
                }
                requirement.norms = norms
            }

            val savedRequirement = requirementRepository.save(requirement)
            return HttpResponse.ok(RequirementResponse.from(savedRequirement))
        } catch (e: Exception) {
            return HttpResponse.serverError<Any>().body(mapOf("error" to "Failed to create requirement: ${e.message}"))
        }
    }

    @Get("/{id}")
    fun get(@PathVariable id: Long): HttpResponse<*> {
        val requirementOptional = requirementRepository.findById(id)
        
        if (requirementOptional.isEmpty) {
            return HttpResponse.notFound<Any>().body(mapOf("error" to "Requirement not found"))
        }

        return HttpResponse.ok(RequirementResponse.from(requirementOptional.get()))
    }

    @Put("/{id}")
    @Transactional
    open fun update(@PathVariable id: Long, @Body request: RequirementUpdateRequest): HttpResponse<*> {
        val requirementOptional = requirementRepository.findById(id)
        
        if (requirementOptional.isEmpty) {
            return HttpResponse.notFound<Any>().body(mapOf("error" to "Requirement not found"))
        }

        val requirement = requirementOptional.get()

        try {
            // Update fields if provided
            request.shortreq?.let { if (it.isNotBlank()) requirement.shortreq = it }
            request.details?.let { requirement.details = it }
            request.language?.let { requirement.language = it }
            request.example?.let { requirement.example = it }
            request.motivation?.let { requirement.motivation = it }
            request.usecase?.let { requirement.usecase = it }
            request.norm?.let { requirement.norm = it }
            request.chapter?.let { requirement.chapter = it }

            // Handle use case relationships if provided
            request.usecaseIds?.let { ids ->
                val useCases = mutableSetOf<UseCase>()
                ids.forEach { id ->
                    useCaseRepository.findById(id).ifPresent { useCases.add(it) }
                }
                requirement.usecases = useCases
            }

            // Handle norm relationships if provided
            request.normIds?.let { ids ->
                val norms = mutableSetOf<Norm>()
                ids.forEach { id ->
                    normRepository.findById(id).ifPresent { norms.add(it) }
                }
                requirement.norms = norms
            }

            val savedRequirement = requirementRepository.update(requirement)
            return HttpResponse.ok(RequirementResponse.from(savedRequirement))
        } catch (e: Exception) {
            return HttpResponse.serverError<Any>().body(mapOf("error" to "Failed to update requirement: ${e.message}"))
        }
    }

    @Delete("/{id}")
    @Transactional
    open fun delete(@PathVariable id: Long): HttpResponse<*> {
        val requirementOptional = requirementRepository.findById(id)
        
        if (requirementOptional.isEmpty) {
            return HttpResponse.notFound<Any>().body(mapOf("error" to "Requirement not found"))
        }

        try {
            requirementRepository.deleteById(id)
            return HttpResponse.ok(mapOf("message" to "Requirement deleted successfully"))
        } catch (e: Exception) {
            return HttpResponse.serverError<Any>().body(mapOf("error" to "Failed to delete requirement: ${e.message}"))
        }
    }

    @Delete("/all")
    @Transactional
    @Secured("ADMIN")
    open fun deleteAllRequirements(): HttpResponse<*> {
        try {
            // Delete all requirements (cascade will handle relationships)
            requirementRepository.deleteAll()
            return HttpResponse.ok(mapOf("message" to "All requirements deleted successfully"))
        } catch (e: Exception) {
            return HttpResponse.serverError<Any>().body(mapOf("error" to "Failed to delete all requirements: ${e.message}"))
        }
    }

    @Get("/export/docx")
    fun exportToDocx(): HttpResponse<StreamedFile> {
        val requirements = requirementRepository.findAll().sortedWith(
            compareBy<Requirement> { it.chapter ?: "" }.thenBy { it.id ?: 0 }
        )
        
        val document = createWordDocument(requirements, "All Requirements")
        val outputStream = ByteArrayOutputStream()
        document.write(outputStream)
        document.close()

        val inputStream = ByteArrayInputStream(outputStream.toByteArray())
        val filename = "requirements_export.docx"
        
        return HttpResponse.ok(StreamedFile(inputStream, MediaType.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")))
            .header("Content-Disposition", "attachment; filename=\"$filename\"")
    }

    @Get("/export/docx/usecase/{usecaseId}")
    fun exportToDocxByUseCase(@PathVariable usecaseId: Long): HttpResponse<*> {
        val useCaseOptional = useCaseRepository.findById(usecaseId)
        
        if (useCaseOptional.isEmpty) {
            return HttpResponse.notFound<Any>().body(mapOf("error" to "UseCase not found"))
        }

        val useCase = useCaseOptional.get()
        val requirements = requirementRepository.findByUsecaseId(usecaseId).sortedWith(
            compareBy<Requirement> { it.chapter ?: "" }.thenBy { it.id ?: 0 }
        )

        if (requirements.isEmpty()) {
            return HttpResponse.ok(mapOf("message" to "No requirements found for this use case"))
        }

        val document = createWordDocument(requirements, "Requirements for UseCase: ${useCase.name}")
        val outputStream = ByteArrayOutputStream()
        document.write(outputStream)
        document.close()

        val inputStream = ByteArrayInputStream(outputStream.toByteArray())
        val filename = "requirements_usecase_${useCase.name.replace(" ", "_")}.docx"
        
        return HttpResponse.ok(StreamedFile(inputStream, MediaType.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")))
            .header("Content-Disposition", "attachment; filename=\"$filename\"")
    }

    @Get("/export/excel")
    fun exportToExcel(): HttpResponse<StreamedFile> {
        val requirements = requirementRepository.findAll().sortedWith(
            compareBy<Requirement> { it.chapter ?: "" }.thenBy { it.id ?: 0 }
        )
        
        val workbook = createExcelWorkbook(requirements)
        val outputStream = ByteArrayOutputStream()
        workbook.write(outputStream)
        workbook.close()

        val inputStream = ByteArrayInputStream(outputStream.toByteArray())
        val filename = "requirements_export.xlsx"
        
        return HttpResponse.ok(StreamedFile(inputStream, MediaType.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")))
            .header("Content-Disposition", "attachment; filename=\"$filename\"")
    }

    @Get("/export/excel/usecase/{usecaseId}")
    fun exportToExcelByUseCase(@PathVariable usecaseId: Long): HttpResponse<*> {
        val useCaseOptional = useCaseRepository.findById(usecaseId)
        
        if (useCaseOptional.isEmpty) {
            return HttpResponse.notFound<Any>().body(mapOf("error" to "UseCase not found"))
        }

        val useCase = useCaseOptional.get()
        val requirements = requirementRepository.findByUsecaseId(usecaseId).sortedWith(
            compareBy<Requirement> { it.chapter ?: "" }.thenBy { it.id ?: 0 }
        )

        if (requirements.isEmpty()) {
            return HttpResponse.ok(mapOf("message" to "No requirements found for this use case"))
        }

        val workbook = createExcelWorkbook(requirements)
        val outputStream = ByteArrayOutputStream()
        workbook.write(outputStream)
        workbook.close()

        val inputStream = ByteArrayInputStream(outputStream.toByteArray())
        val filename = "requirements_usecase_${useCase.name.replace(" ", "_")}.xlsx"
        
        return HttpResponse.ok(StreamedFile(inputStream, MediaType.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")))
            .header("Content-Disposition", "attachment; filename=\"$filename\"")
    }

    private fun createWordDocument(requirements: List<Requirement>, title: String): XWPFDocument {
        val document = XWPFDocument()
        
        // Title page
        val titleParagraph = document.createParagraph()
        titleParagraph.alignment = org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER
        val titleRun = titleParagraph.createRun()
        titleRun.setText(title)
        titleRun.fontSize = 18
        titleRun.isBold = true
        
        // Generation date
        val dateParagraph = document.createParagraph()
        dateParagraph.alignment = org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER
        val dateRun = dateParagraph.createRun()
        dateRun.setText("Generated on: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")
        
        // Add empty line
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
        
        // Add page break
        document.createParagraph().createRun().addBreak(org.apache.poi.xwpf.usermodel.BreakType.PAGE)
        
        // Group requirements by chapter
        val requirementsByChapter = requirements.groupBy { it.chapter ?: "No Chapter" }
        var requirementNumber = 1
        
        for ((chapter, chapterRequirements) in requirementsByChapter) {
            // Chapter heading
            val chapterParagraph = document.createParagraph()
            val chapterRun = chapterParagraph.createRun()
            chapterRun.setText("Chapter: $chapter")
            chapterRun.fontSize = 16
            chapterRun.isBold = true
            
            document.createParagraph() // Empty line
            
            for (requirement in chapterRequirements) {
                // Requirement header with light blue highlighting
                val reqHeaderParagraph = document.createParagraph()
                val reqHeaderRun = reqHeaderParagraph.createRun()
                reqHeaderRun.setText("$requirementNumber. ${requirement.shortreq}")
                reqHeaderRun.fontSize = 12
                reqHeaderRun.isBold = true
                reqHeaderRun.color = "0066CC"
                
                // Details
                requirement.details?.let { details ->
                    val detailsParagraph = document.createParagraph()
                    val detailsRun = detailsParagraph.createRun()
                    detailsRun.setText("Details: $details")
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
                
                document.createParagraph() // Empty line between requirements
                requirementNumber++
            }
        }
        
        return document
    }

    private fun createExcelWorkbook(requirements: List<Requirement>): XSSFWorkbook {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Reqs") // Use "Reqs" to match import format
        
        // Header row - exact same format as import expects
        val headerRow = sheet.createRow(0)
        val headers = arrayOf("Chapter", "Norm", "Short req", "DetailsEN", "MotivationEN", "ExampleEN", "UseCase")
        
        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            
            // Style header cells
            val headerStyle = workbook.createCellStyle()
            val headerFont = workbook.createFont()
            headerFont.bold = true
            headerStyle.setFont(headerFont)
            cell.cellStyle = headerStyle
        }
        
        // Data rows
        requirements.forEachIndexed { index, requirement ->
            val row = sheet.createRow(index + 1)
            
            // Chapter
            row.createCell(0).setCellValue(requirement.chapter ?: "")
            
            // Norm - combine all parsed norms for round-trip compatibility
            val normString = if (requirement.norms.isNotEmpty()) {
                requirement.norms.joinToString("; ") { norm ->
                    if (norm.version.isNotEmpty()) {
                        "${norm.name.substringBefore(':')}: ${norm.version}: ${norm.name.substringAfter(':', norm.name)}"
                    } else {
                        norm.name
                    }
                }
            } else {
                requirement.norm ?: "" // Fallback to original norm string
            }
            row.createCell(1).setCellValue(normString)
            
            // Short req
            row.createCell(2).setCellValue(requirement.shortreq)
            
            // DetailsEN
            row.createCell(3).setCellValue(requirement.details ?: "")
            
            // MotivationEN
            row.createCell(4).setCellValue(requirement.motivation ?: "")
            
            // ExampleEN
            row.createCell(5).setCellValue(requirement.example ?: "")
            
            // UseCase - combine all use case names
            val useCaseString = if (requirement.usecases.isNotEmpty()) {
                requirement.usecases.joinToString(", ") { it.name }
            } else {
                requirement.usecase ?: "" // Fallback to original usecase string
            }
            row.createCell(6).setCellValue(useCaseString)
        }
        
        // Auto-size columns with minimum width
        for (i in 0 until headers.size) {
            sheet.autoSizeColumn(i)
            if (sheet.getColumnWidth(i) < 2000) {
                sheet.setColumnWidth(i, 2000)
            }
        }
        
        return workbook
    }

    @Get("/api/requirements/export/docx/translated/{language}")
    fun exportToDocxTranslated(@PathVariable language: String): HttpResponse<*> {
        val activeConfig = translationService.getActiveConfig()
        if (activeConfig == null) {
            return HttpResponse.badRequest(mapOf(
                "error" to "No active translation configuration found",
                "message" to "Please configure a translation service first"
            ))
        }

        val requirements = requirementRepository.findAll().sortedWith(
            compareBy<Requirement> { it.chapter ?: "" }.thenBy { it.id ?: 0 }
        )

        if (requirements.isEmpty()) {
            return HttpResponse.ok(mapOf("message" to "No requirements found"))
        }

        return try {
            val document = createTranslatedWordDocument(requirements, "Translated Requirements", language)
            val outputStream = ByteArrayOutputStream()
            document.write(outputStream)
            document.close()

            val inputStream = ByteArrayInputStream(outputStream.toByteArray())
            val languageName = translationService.getSupportedLanguages()[language] ?: language
            val filename = "requirements_translated_${language}_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}.docx"
            
            HttpResponse.ok(StreamedFile(inputStream, MediaType.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")))
                .header("Content-Disposition", "attachment; filename=\"$filename\"")
        } catch (e: Exception) {
            HttpResponse.serverError(mapOf(
                "error" to "Translation export failed",
                "message" to e.message
            ))
        }
    }

    @Get("/api/requirements/export/docx/usecase/{usecaseId}/translated/{language}")
    fun exportToDocxTranslatedByUseCase(@PathVariable usecaseId: Long, @PathVariable language: String): HttpResponse<*> {
        val activeConfig = translationService.getActiveConfig()
        if (activeConfig == null) {
            return HttpResponse.badRequest(mapOf(
                "error" to "No active translation configuration found",
                "message" to "Please configure a translation service first"
            ))
        }

        val useCaseOptional = useCaseRepository.findById(usecaseId)
        if (useCaseOptional.isEmpty) {
            return HttpResponse.notFound<Any>().body(mapOf("error" to "UseCase not found"))
        }

        val useCase = useCaseOptional.get()
        val requirements = requirementRepository.findByUsecaseId(usecaseId).sortedWith(
            compareBy<Requirement> { it.chapter ?: "" }.thenBy { it.id ?: 0 }
        )

        if (requirements.isEmpty()) {
            return HttpResponse.ok(mapOf("message" to "No requirements found for this use case"))
        }

        return try {
            val document = createTranslatedWordDocument(requirements, "Translated Requirements for UseCase: ${useCase.name}", language)
            val outputStream = ByteArrayOutputStream()
            document.write(outputStream)
            document.close()

            val inputStream = ByteArrayInputStream(outputStream.toByteArray())
            val languageName = translationService.getSupportedLanguages()[language] ?: language
            val filename = "requirements_usecase_${useCase.name.replace(" ", "_")}_translated_${language}_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}.docx"
            
            HttpResponse.ok(StreamedFile(inputStream, MediaType.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")))
                .header("Content-Disposition", "attachment; filename=\"$filename\"")
        } catch (e: Exception) {
            HttpResponse.serverError(mapOf(
                "error" to "Translation export failed",
                "message" to e.message
            ))
        }
    }

    private fun createTranslatedWordDocument(requirements: List<Requirement>, title: String, targetLanguage: String): XWPFDocument {
        val document = XWPFDocument()
        val languageName = translationService.getSupportedLanguages()[targetLanguage] ?: targetLanguage
        
        // Title page with translation info
        val titleParagraph = document.createParagraph()
        titleParagraph.alignment = org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER
        val titleRun = titleParagraph.createRun()
        titleRun.setText(title)
        titleRun.fontSize = 18
        titleRun.isBold = true
        
        // Translation info
        val translationInfoParagraph = document.createParagraph()
        translationInfoParagraph.alignment = org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER
        val translationInfoRun = translationInfoParagraph.createRun()
        translationInfoRun.setText("Translated to: $languageName")
        translationInfoRun.fontSize = 12
        translationInfoRun.isItalic = true
        
        // Generation date
        val dateParagraph = document.createParagraph()
        dateParagraph.alignment = org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER
        val dateRun = dateParagraph.createRun()
        dateRun.setText("Generated on: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")
        
        // Add empty line
        document.createParagraph()
        
        // Note about translation
        val noteParagraph = document.createParagraph()
        val noteRun = noteParagraph.createRun()
        noteRun.setText("Note: This document contains AI-translated content. Please review for accuracy and context.")
        noteRun.fontSize = 10
        noteRun.isItalic = true
        
        // Add page break
        document.createParagraph().createRun().addBreak(org.apache.poi.xwpf.usermodel.BreakType.PAGE)
        
        // Translate and add requirements
        requirements.forEach { requirement ->
            // Chapter heading
            if (!requirement.chapter.isNullOrBlank()) {
                val chapterParagraph = document.createParagraph()
                val chapterRun = chapterParagraph.createRun()
                chapterRun.setText("Chapter: ${requirement.chapter}")
                chapterRun.fontSize = 14
                chapterRun.isBold = true
                document.createParagraph()
            }
            
            // Short requirement (translate if English)
            val shortReqParagraph = document.createParagraph()
            val shortReqRun = shortReqParagraph.createRun()
            shortReqRun.setText("Requirement: ")
            shortReqRun.isBold = true
            
            val shortReqTranslated = try {
                translationService.translateText(requirement.shortreq, targetLanguage)
            } catch (e: Exception) {
                requirement.shortreq
            }
            
            val shortReqValueRun = shortReqParagraph.createRun()
            shortReqValueRun.setText(shortReqTranslated)
            
            // Details (translate if present)
            if (!requirement.details.isNullOrBlank()) {
                val detailsParagraph = document.createParagraph()
                val detailsRun = detailsParagraph.createRun()
                detailsRun.setText("Details: ")
                detailsRun.isBold = true
                
                val detailsTranslated = try {
                    translationService.translateText(requirement.details!!, targetLanguage)
                } catch (e: Exception) {
                    requirement.details!!
                }
                
                val detailsValueRun = detailsParagraph.createRun()
                detailsValueRun.setText(detailsTranslated)
            }
            
            // Motivation (translate if present)
            if (!requirement.motivation.isNullOrBlank()) {
                val motivationParagraph = document.createParagraph()
                val motivationRun = motivationParagraph.createRun()
                motivationRun.setText("Motivation: ")
                motivationRun.isBold = true
                
                val motivationTranslated = try {
                    translationService.translateText(requirement.motivation!!, targetLanguage)
                } catch (e: Exception) {
                    requirement.motivation!!
                }
                
                val motivationValueRun = motivationParagraph.createRun()
                motivationValueRun.setText(motivationTranslated)
            }
            
            // Example (translate if present)
            if (!requirement.example.isNullOrBlank()) {
                val exampleParagraph = document.createParagraph()
                val exampleRun = exampleParagraph.createRun()
                exampleRun.setText("Example: ")
                exampleRun.isBold = true
                
                val exampleTranslated = try {
                    translationService.translateText(requirement.example!!, targetLanguage)
                } catch (e: Exception) {
                    requirement.example!!
                }
                
                val exampleValueRun = exampleParagraph.createRun()
                exampleValueRun.setText(exampleTranslated)
            }
            
            // Use cases (do not translate names, just labels)
            if (requirement.usecases.isNotEmpty()) {
                val usecaseParagraph = document.createParagraph()
                val usecaseRun = usecaseParagraph.createRun()
                val usecaseLabel = try {
                    translationService.translateText("Use Cases", targetLanguage)
                } catch (e: Exception) {
                    "Use Cases"
                }
                usecaseRun.setText("$usecaseLabel: ")
                usecaseRun.isBold = true
                
                val usecaseValueRun = usecaseParagraph.createRun()
                usecaseValueRun.setText(requirement.usecases.joinToString(", ") { it.name })
            }
            
            // Norms (do not translate names, just labels)
            if (requirement.norms.isNotEmpty()) {
                val normParagraph = document.createParagraph()
                val normRun = normParagraph.createRun()
                val normLabel = try {
                    translationService.translateText("Standards/Norms", targetLanguage)
                } catch (e: Exception) {
                    "Standards/Norms"
                }
                normRun.setText("$normLabel: ")
                normRun.isBold = true
                
                val normValueRun = normParagraph.createRun()
                normValueRun.setText(requirement.norms.joinToString(", ") { it.name })
            }
            
            // Add space between requirements
            document.createParagraph()
            document.createParagraph()
        }
        
        return document
    }
}