package com.secman.controller

import com.secman.domain.Requirement
import com.secman.domain.RequirementSnapshot
import com.secman.domain.UseCase
import com.secman.domain.Norm
import com.secman.repository.RequirementRepository
import com.secman.repository.RequirementSnapshotRepository
import com.secman.repository.ReleaseRepository
import com.secman.repository.UseCaseRepository
import com.secman.repository.NormRepository
import com.secman.service.TranslationService
import com.secman.service.InputValidationService
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

/**
 * Requirement Controller
 * Feature: 025-role-based-access-control
 *
 * Access Control:
 * - ADMIN: Full access to all requirement operations
 * - REQ: Full access to all requirement operations
 * - SECCHAMPION: Full access to all requirement operations
 * - Other roles: Access denied (403 Forbidden)
 */
@Controller("/api/requirements")
@Secured("ADMIN", "REQ", "SECCHAMPION")
open class RequirementController(
    private val requirementRepository: RequirementRepository,
    private val useCaseRepository: UseCaseRepository,
    private val normRepository: NormRepository,
    private val translationService: TranslationService,
    private val inputValidationService: InputValidationService,
    private val releaseRepository: ReleaseRepository,
    private val snapshotRepository: RequirementSnapshotRepository
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
        
        // Validate short requirement
        val shortreqValidation = inputValidationService.validateDescription(request.shortreq, "Short requirement")
        if (!shortreqValidation.isValid) {
            return HttpResponse.badRequest(mapOf("error" to shortreqValidation.errorMessage))
        }
        
        // Validate details if provided
        request.details?.let {
            val detailsValidation = inputValidationService.validateDescription(it, "Details")
            if (!detailsValidation.isValid) {
                return HttpResponse.badRequest(mapOf("error" to detailsValidation.errorMessage))
            }
        }
        
        // Validate example if provided
        request.example?.let {
            val exampleValidation = inputValidationService.validateDescription(it, "Example")
            if (!exampleValidation.isValid) {
                return HttpResponse.badRequest(mapOf("error" to exampleValidation.errorMessage))
            }
        }
        
        // Validate IDs if provided
        request.usecaseIds?.let {
            val idsValidation = inputValidationService.validateIdList(it)
            if (!idsValidation.isValid) {
                return HttpResponse.badRequest(mapOf("error" to "Invalid use case IDs"))
            }
        }
        
        request.normIds?.let {
            val idsValidation = inputValidationService.validateIdList(it)
            if (!idsValidation.isValid) {
                return HttpResponse.badRequest(mapOf("error" to "Invalid norm IDs"))
            }
        }

        try {
            val requirement = Requirement(
                shortreq = inputValidationService.sanitizeForDisplay(request.shortreq),
                details = request.details?.let { inputValidationService.sanitizeForDisplay(it) },
                language = request.language,
                example = request.example?.let { inputValidationService.sanitizeForDisplay(it) },
                motivation = request.motivation?.let { inputValidationService.sanitizeForDisplay(it) },
                usecase = request.usecase?.let { inputValidationService.sanitizeForDisplay(it) },
                norm = request.norm?.let { inputValidationService.sanitizeForDisplay(it) },
                chapter = request.chapter?.let { inputValidationService.sanitizeForDisplay(it) }
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
        // Validate ID
        val idValidation = inputValidationService.validateId(id)
        if (!idValidation.isValid) {
            return HttpResponse.badRequest(mapOf("error" to idValidation.errorMessage))
        }
        
        // Validate fields if provided
        request.shortreq?.let {
            val validation = inputValidationService.validateDescription(it, "Short requirement")
            if (!validation.isValid) {
                return HttpResponse.badRequest(mapOf("error" to validation.errorMessage))
            }
        }
        
        request.details?.let {
            val validation = inputValidationService.validateDescription(it, "Details")
            if (!validation.isValid) {
                return HttpResponse.badRequest(mapOf("error" to validation.errorMessage))
            }
        }
        
        request.usecaseIds?.let {
            val validation = inputValidationService.validateIdList(it)
            if (!validation.isValid) {
                return HttpResponse.badRequest(mapOf("error" to "Invalid use case IDs"))
            }
        }
        
        request.normIds?.let {
            val validation = inputValidationService.validateIdList(it)
            if (!validation.isValid) {
                return HttpResponse.badRequest(mapOf("error" to "Invalid norm IDs"))
            }
        }
        
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
    fun exportToDocx(@Nullable @QueryValue("releaseId") releaseId: Long?): HttpResponse<*> {
        val requirements: List<Requirement>
        val filename: String
        val title: String

        if (releaseId != null) {
            // Export from release snapshot
            val releaseOpt = releaseRepository.findById(releaseId)
            if (releaseOpt.isEmpty) {
                return HttpResponse.notFound(mapOf("error" to "Release not found"))
            }

            val release = releaseOpt.get()
            val snapshots = snapshotRepository.findByReleaseId(releaseId)

            // Convert snapshots to Requirements for export
            requirements = snapshots.map { snapshotToRequirement(it) }.sortedWith(
                compareBy<Requirement> { it.chapter ?: "" }.thenBy { it.id ?: 0 }
            )

            val dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            filename = "requirements_v${release.version}_$dateStr.docx"
            title = "Requirements - Release ${release.version}"
        } else {
            // Export current requirements (default behavior)
            requirements = requirementRepository.findAll().sortedWith(
                compareBy<Requirement> { it.chapter ?: "" }.thenBy { it.id ?: 0 }
            )
            filename = "requirements_export.docx"
            title = "All Requirements"
        }

        val document = createWordDocument(requirements, title)
        val outputStream = ByteArrayOutputStream()
        document.write(outputStream)
        document.close()

        val inputStream = ByteArrayInputStream(outputStream.toByteArray())

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

    @Get("/export/xlsx")
    fun exportToExcel(@Nullable @QueryValue("releaseId") releaseId: Long?): HttpResponse<*> {
        val requirements: List<Requirement>
        val filename: String

        if (releaseId != null) {
            // Export from release snapshot
            val releaseOpt = releaseRepository.findById(releaseId)
            if (releaseOpt.isEmpty) {
                return HttpResponse.notFound(mapOf("error" to "Release not found"))
            }

            val release = releaseOpt.get()
            val snapshots = snapshotRepository.findByReleaseId(releaseId)

            // Convert snapshots to Requirements for export
            requirements = snapshots.map { snapshotToRequirement(it) }.sortedWith(
                compareBy<Requirement> { it.chapter ?: "" }.thenBy { it.id ?: 0 }
            )

            val dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            filename = "requirements_v${release.version}_$dateStr.xlsx"
        } else {
            // Export current requirements (default behavior)
            requirements = requirementRepository.findAll().sortedWith(
                compareBy<Requirement> { it.chapter ?: "" }.thenBy { it.id ?: 0 }
            )
            filename = "requirements_export.xlsx"
        }

        val workbook = createExcelWorkbook(requirements)
        val outputStream = ByteArrayOutputStream()
        workbook.write(outputStream)
        workbook.close()

        val inputStream = ByteArrayInputStream(outputStream.toByteArray())

        return HttpResponse.ok(StreamedFile(inputStream, MediaType.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")))
            .header("Content-Disposition", "attachment; filename=\"$filename\"")
    }

    @Get("/export/xlsx/usecase/{usecaseId}")
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
        
        var isFirstChapter = true
        for ((chapter, chapterRequirements) in requirementsByChapter) {
            // Page break before each chapter (first chapter already has one from TOC)
            if (!isFirstChapter) {
                document.createParagraph().createRun().addBreak(org.apache.poi.xwpf.usermodel.BreakType.PAGE)
            }
            isFirstChapter = false

            // Chapter heading with Heading 1 style for TOC
            val chapterParagraph = document.createParagraph()
            chapterParagraph.style = "Heading1"
            val chapterRun = chapterParagraph.createRun()
            chapterRun.setText(chapter)
            chapterRun.fontSize = 16
            chapterRun.isBold = true

            document.createParagraph() // Empty line
            
            for (requirement in chapterRequirements) {
                // Requirement header with light green background
                val reqHeaderParagraph = document.createParagraph()
                // Set light green background shading
                val ctp = reqHeaderParagraph.ctp
                val ppr = if (ctp.isSetPPr) ctp.pPr else ctp.addNewPPr()
                val shd = if (ppr.isSetShd) ppr.shd else ppr.addNewShd()
                shd.fill = "C1D5C0"  // Soft sage green (Scandinavian style)

                val reqHeaderRun = reqHeaderParagraph.createRun()
                reqHeaderRun.setText("Req $requirementNumber: ${requirement.shortreq}")
                reqHeaderRun.fontSize = 12
                reqHeaderRun.isBold = true
                
                // Details - no label, content follows directly after header
                requirement.details?.let { details ->
                    val detailsParagraph = document.createParagraph()
                    val detailsRun = detailsParagraph.createRun()
                    detailsRun.setText(details)
                }

                // Motivation - label bold, content regular
                requirement.motivation?.let { motivation ->
                    val motivationParagraph = document.createParagraph()
                    val motivationLabelRun = motivationParagraph.createRun()
                    motivationLabelRun.setText("Motivation: ")
                    motivationLabelRun.isBold = true
                    val motivationValueRun = motivationParagraph.createRun()
                    motivationValueRun.setText(motivation)
                }

                // Example - label bold, content regular
                requirement.example?.let { example ->
                    val exampleParagraph = document.createParagraph()
                    val exampleLabelRun = exampleParagraph.createRun()
                    exampleLabelRun.setText("Example: ")
                    exampleLabelRun.isBold = true
                    val exampleValueRun = exampleParagraph.createRun()
                    exampleValueRun.setText(example)
                }

                // Norm reference - label bold, content regular
                requirement.norm?.let { norm ->
                    val normParagraph = document.createParagraph()
                    val normLabelRun = normParagraph.createRun()
                    normLabelRun.setText("Norm Reference: ")
                    normLabelRun.isBold = true
                    val normValueRun = normParagraph.createRun()
                    normValueRun.setText(norm)
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

    @Get("/export/docx/translated/{language}")
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

    @Get("/export/docx/usecase/{usecaseId}/translated/{language}")
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

    /**
     * Batch translate all requirement fields and static labels upfront.
     * This drastically reduces API calls by deduplicating and batching translations.
     * Returns a map of original text -> translated text.
     */
    private fun batchTranslateRequirements(
        requirements: List<Requirement>,
        targetLanguage: String,
        includeHeaders: Boolean = false
    ): Map<String, String> {
        val textsToTranslate = mutableSetOf<String>()

        // Collect all translatable requirement fields
        requirements.forEach { req ->
            textsToTranslate.add(req.shortreq)
            req.details?.takeIf { it.isNotBlank() }?.let { textsToTranslate.add(it) }
            req.motivation?.takeIf { it.isNotBlank() }?.let { textsToTranslate.add(it) }
            req.example?.takeIf { it.isNotBlank() }?.let { textsToTranslate.add(it) }
        }

        // Add static labels (Word doc uses these)
        textsToTranslate.addAll(listOf("Use Cases", "Standards/Norms"))

        // Add Excel headers if requested
        if (includeHeaders) {
            textsToTranslate.addAll(listOf("Chapter", "Norm", "Short Requirement", "Details", "Motivation", "Example"))
        }

        // Filter empty strings and convert to list
        val uniqueTexts = textsToTranslate.filter { it.isNotBlank() }.toList()

        if (uniqueTexts.isEmpty()) {
            return emptyMap()
        }

        // Batch translate all texts at once
        return try {
            val translations = translationService.translateTexts(uniqueTexts, targetLanguage).get()
            uniqueTexts.zip(translations).toMap()
        } catch (e: Exception) {
            // On error, return empty map (fallback to original text)
            emptyMap()
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

        // Batch translate all texts upfront (reduces 500+ API calls to 1 batch call)
        val translationMap = batchTranslateRequirements(requirements, targetLanguage)

        // Add requirements using pre-translated content
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
            
            // Short requirement (use batch-translated value)
            val shortReqParagraph = document.createParagraph()
            val shortReqRun = shortReqParagraph.createRun()
            shortReqRun.setText("Requirement: ")
            shortReqRun.isBold = true

            val shortReqTranslated = translationMap[requirement.shortreq] ?: requirement.shortreq

            val shortReqValueRun = shortReqParagraph.createRun()
            shortReqValueRun.setText(shortReqTranslated)
            
            // Details (use batch-translated value)
            if (!requirement.details.isNullOrBlank()) {
                val detailsParagraph = document.createParagraph()
                val detailsRun = detailsParagraph.createRun()
                detailsRun.setText("Details: ")
                detailsRun.isBold = true

                val detailsTranslated = translationMap[requirement.details] ?: requirement.details!!

                val detailsValueRun = detailsParagraph.createRun()
                detailsValueRun.setText(detailsTranslated)
            }
            
            // Motivation (use batch-translated value)
            if (!requirement.motivation.isNullOrBlank()) {
                val motivationParagraph = document.createParagraph()
                val motivationRun = motivationParagraph.createRun()
                motivationRun.setText("Motivation: ")
                motivationRun.isBold = true

                val motivationTranslated = translationMap[requirement.motivation] ?: requirement.motivation!!

                val motivationValueRun = motivationParagraph.createRun()
                motivationValueRun.setText(motivationTranslated)
            }
            
            // Example (use batch-translated value)
            if (!requirement.example.isNullOrBlank()) {
                val exampleParagraph = document.createParagraph()
                val exampleRun = exampleParagraph.createRun()
                exampleRun.setText("Example: ")
                exampleRun.isBold = true

                val exampleTranslated = translationMap[requirement.example] ?: requirement.example!!

                val exampleValueRun = exampleParagraph.createRun()
                exampleValueRun.setText(exampleTranslated)
            }
            
            // Use cases (use batch-translated label)
            if (requirement.usecases.isNotEmpty()) {
                val usecaseParagraph = document.createParagraph()
                val usecaseRun = usecaseParagraph.createRun()
                val usecaseLabel = translationMap["Use Cases"] ?: "Use Cases"
                usecaseRun.setText("$usecaseLabel: ")
                usecaseRun.isBold = true

                val usecaseValueRun = usecaseParagraph.createRun()
                usecaseValueRun.setText(requirement.usecases.joinToString(", ") { it.name })
            }
            
            // Norms (use batch-translated label)
            if (requirement.norms.isNotEmpty()) {
                val normParagraph = document.createParagraph()
                val normRun = normParagraph.createRun()
                val normLabel = translationMap["Standards/Norms"] ?: "Standards/Norms"
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

    @Get("/export/xlsx/translated/{language}")
    fun exportToExcelTranslated(@PathVariable language: String): HttpResponse<*> {
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
            val workbook = createTranslatedExcelWorkbook(requirements, language)
            val outputStream = ByteArrayOutputStream()
            workbook.write(outputStream)
            workbook.close()

            val inputStream = ByteArrayInputStream(outputStream.toByteArray())
            val languageName = translationService.getSupportedLanguages()[language] ?: language
            val filename = "requirements_translated_${language}_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}.xlsx"
            
            HttpResponse.ok(StreamedFile(inputStream, MediaType.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")))
                .header("Content-Disposition", "attachment; filename=\"$filename\"")
        } catch (e: Exception) {
            HttpResponse.serverError(mapOf(
                "error" to "Translation export failed",
                "message" to e.message
            ))
        }
    }

    @Get("/export/xlsx/usecase/{usecaseId}/translated/{language}")
    fun exportToExcelTranslatedByUseCase(@PathVariable usecaseId: Long, @PathVariable language: String): HttpResponse<*> {
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
            val workbook = createTranslatedExcelWorkbook(requirements, language)
            val outputStream = ByteArrayOutputStream()
            workbook.write(outputStream)
            workbook.close()

            val inputStream = ByteArrayInputStream(outputStream.toByteArray())
            val languageName = translationService.getSupportedLanguages()[language] ?: language
            val filename = "requirements_usecase_${useCase.name.replace(" ", "_")}_translated_${language}_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}.xlsx"
            
            HttpResponse.ok(StreamedFile(inputStream, MediaType.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")))
                .header("Content-Disposition", "attachment; filename=\"$filename\"")
        } catch (e: Exception) {
            HttpResponse.serverError(mapOf(
                "error" to "Translation export failed",
                "message" to e.message
            ))
        }
    }

    private fun createTranslatedExcelWorkbook(requirements: List<Requirement>, targetLanguage: String): XSSFWorkbook {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Requirements")
        
        val languageName = translationService.getSupportedLanguages()[targetLanguage] ?: targetLanguage
        
        // Create header style
        val headerStyle = workbook.createCellStyle()
        val headerFont = workbook.createFont()
        headerFont.bold = true
        headerStyle.setFont(headerFont)
        
        // Create info row with translation info
        val infoRow = sheet.createRow(0)
        val infoCell = infoRow.createCell(0)
        infoCell.setCellValue("Translated to: $languageName - Generated on: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")
        infoCell.cellStyle = headerStyle

        // Batch translate all texts upfront (reduces 500+ API calls to 1 batch call)
        val translationMap = batchTranslateRequirements(requirements, targetLanguage, includeHeaders = true)

        // Create header row (use batch-translated headers)
        val headerRow = sheet.createRow(1)
        val headers = listOf("Chapter", "Norm", "Short Requirement", "Details", "Motivation", "Example", "Use Cases")
        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            val translatedHeader = translationMap[header] ?: header
            cell.setCellValue(translatedHeader)
            cell.cellStyle = headerStyle
        }
        
        // Add requirements data with translation
        requirements.forEachIndexed { index, requirement ->
            val row = sheet.createRow(index + 2) // +2 to account for info and header rows
            
            // Chapter (not translated)
            row.createCell(0).setCellValue(requirement.chapter ?: "")
            
            // Norm (not translated)
            row.createCell(1).setCellValue(requirement.norms.joinToString(", ") { it.name })
            
            // Short requirement (use batch-translated value)
            val shortReqTranslated = translationMap[requirement.shortreq] ?: requirement.shortreq
            row.createCell(2).setCellValue(shortReqTranslated)
            
            // Details (use batch-translated value)
            val detailsTranslated = if (!requirement.details.isNullOrBlank()) {
                translationMap[requirement.details] ?: requirement.details!!
            } else {
                ""
            }
            row.createCell(3).setCellValue(detailsTranslated)
            
            // Motivation (use batch-translated value)
            val motivationTranslated = if (!requirement.motivation.isNullOrBlank()) {
                translationMap[requirement.motivation] ?: requirement.motivation!!
            } else {
                ""
            }
            row.createCell(4).setCellValue(motivationTranslated)
            
            // Example (use batch-translated value)
            val exampleTranslated = if (!requirement.example.isNullOrBlank()) {
                translationMap[requirement.example] ?: requirement.example!!
            } else {
                ""
            }
            row.createCell(5).setCellValue(exampleTranslated)
            
            // Use Cases (not translated)
            row.createCell(6).setCellValue(requirement.usecases.joinToString(", ") { it.name })
        }
        
        // Auto-size columns
        for (i in 0 until headers.size) {
            sheet.autoSizeColumn(i)
        }

        return workbook
    }

    /**
     * Helper method to convert RequirementSnapshot to Requirement for export
     * Note: This creates a detached Requirement entity (not persisted)
     */
    private fun snapshotToRequirement(snapshot: RequirementSnapshot): Requirement {
        return Requirement(
            id = snapshot.originalRequirementId,
            shortreq = snapshot.shortreq,
            details = snapshot.details,
            language = snapshot.language,
            example = snapshot.example,
            motivation = snapshot.motivation,
            usecase = snapshot.usecase,
            norm = snapshot.norm,
            chapter = snapshot.chapter,
            usecases = mutableSetOf(),  // Snapshots store IDs as JSON, not objects
            norms = mutableSetOf()       // For export purposes, empty sets are acceptable
        )
    }
}