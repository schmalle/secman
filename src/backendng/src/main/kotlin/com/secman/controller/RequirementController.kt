package com.secman.controller

import com.secman.domain.MissingPlaceholderBehavior
import com.secman.domain.Requirement
import com.secman.domain.RequirementExportScope
import com.secman.domain.RequirementExportTemplate
import com.secman.domain.RequirementExportTemplateMode
import com.secman.domain.RequirementExportTemplateStatus
import com.secman.domain.RequirementExportTemplateUsage
import com.secman.domain.RequirementSnapshot
import com.secman.util.ExcelSanitizer
import com.secman.domain.UseCase
import com.secman.domain.Norm
import com.secman.repository.RequirementExportTemplateRepository
import com.secman.repository.RequirementExportTemplateUsageRepository
import com.secman.repository.RequirementRepository
import com.secman.repository.RequirementSnapshotRepository
import com.secman.repository.ReleaseRepository
import com.secman.repository.UseCaseRepository
import com.secman.repository.NormRepository
import com.secman.service.TranslationService
import com.secman.service.InputValidationService
import com.secman.service.RequirementService
import com.secman.service.RequirementIdService
import com.secman.service.RequirementExportTemplateValidationService
import io.micronaut.core.annotation.Nullable
import io.micronaut.data.model.Pageable
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.http.multipart.CompletedFileUpload
import io.micronaut.http.server.types.files.StreamedFile
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.transaction.Transactional
import jakarta.validation.Valid
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable
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
    private val snapshotRepository: RequirementSnapshotRepository,
    private val requirementService: RequirementService,
    private val requirementIdService: RequirementIdService,
    private val exportTemplateRepository: RequirementExportTemplateRepository,
    private val exportTemplateUsageRepository: RequirementExportTemplateUsageRepository,
    private val exportTemplateValidationService: RequirementExportTemplateValidationService
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
        val internalId: String,
        val revision: Int,
        val idRevision: String,
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
                    internalId = requirement.internalId,
                    revision = requirement.versionNumber,
                    idRevision = requirement.idRevision,
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
    open fun create(@Valid @Body request: RequirementCreateRequest): HttpResponse<*> {
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

            val savedRequirement = requirementService.createRequirement(requirement)
            return HttpResponse.ok(RequirementResponse.from(savedRequirement))
        } catch (e: Exception) {
            return HttpResponse.serverError<Any>().body(mapOf("error" to "An internal error occurred"))
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
    open fun update(@PathVariable id: Long, @Valid @Body request: RequirementUpdateRequest): HttpResponse<*> {
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
            // Check if content change requires revision increment (before modifying)
            val shouldIncrement = requirementService.shouldIncrementRevision(
                existing = requirement,
                newShortreq = request.shortreq,
                newDetails = request.details,
                newExample = request.example,
                newMotivation = request.motivation,
                newUsecase = request.usecase,
                newNorm = request.norm,
                newChapter = request.chapter
            )

            // Increment revision if content changed
            if (shouldIncrement) {
                requirement.incrementVersion()
            }

            // Update fields if provided
            request.shortreq?.let { if (it.isNotBlank()) requirement.shortreq = it }
            request.details?.let { requirement.details = it }
            request.language?.let { requirement.language = it }
            request.example?.let { requirement.example = it }
            request.motivation?.let { requirement.motivation = it }
            request.usecase?.let { requirement.usecase = it }
            request.norm?.let { requirement.norm = it }
            request.chapter?.let { requirement.chapter = it }

            // Handle use case relationships if provided (does NOT increment revision)
            request.usecaseIds?.let { ids ->
                val useCases = mutableSetOf<UseCase>()
                ids.forEach { id ->
                    useCaseRepository.findById(id).ifPresent { useCases.add(it) }
                }
                requirement.usecases = useCases
            }

            // Handle norm relationships if provided (does NOT increment revision)
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
            return HttpResponse.serverError<Any>().body(mapOf("error" to "An internal error occurred"))
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
            return HttpResponse.serverError<Any>().body(mapOf("error" to "An internal error occurred"))
        }
    }

    @Delete("/all")
    @Transactional
    @Secured("ADMIN")
    open fun deleteAllRequirements(): HttpResponse<*> {
        try {
            // Delete all requirements (cascade will handle relationships)
            requirementRepository.deleteAll()
            // Reset the REQ-NNN sequence so the next imported requirement starts at REQ-001
            requirementIdService.resetSequence()
            return HttpResponse.ok(mapOf("message" to "All requirements deleted successfully"))
        } catch (e: Exception) {
            return HttpResponse.serverError<Any>().body(mapOf("error" to "An internal error occurred"))
        }
    }

    @Get("/export/docx")
    fun exportToDocx(
        @Nullable @QueryValue("releaseId") releaseId: Long?,
        @QueryValue(defaultValue = "LATEST") templateMode: String,
        @Nullable @QueryValue("templateId") templateId: Long?,
        @QueryValue(defaultValue = "REJECT") missingPlaceholderBehavior: String,
        @Nullable @QueryValue("classification") classification: String?,
        authentication: Authentication
    ): HttpResponse<*> {
        val exportData = resolveRequirementExportData(releaseId)
            ?: return HttpResponse.notFound(mapOf("error" to "Release not found"))

        return exportWordDocument(
            requirements = exportData.requirements,
            title = exportData.title,
            filename = exportData.filename,
            templateMode = templateMode,
            templateId = templateId,
            adHocTemplate = null,
            missingPlaceholderBehavior = missingPlaceholderBehavior,
            classification = classification,
            language = null,
            scope = if (releaseId != null) RequirementExportScope.RELEASE else RequirementExportScope.ALL,
            releaseId = releaseId,
            usecaseId = null,
            authentication = authentication
        )
    }

    @Post("/export/docx")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    fun exportToDocxWithAdHocTemplate(
        @Nullable @QueryValue("releaseId") releaseId: Long?,
        @Part templateFile: CompletedFileUpload,
        @Nullable @Part templateMode: String?,
        @Nullable @Part missingPlaceholderBehavior: String?,
        @Nullable @Part classification: String?,
        authentication: Authentication
    ): HttpResponse<*> {
        val exportData = resolveRequirementExportData(releaseId)
            ?: return HttpResponse.notFound(mapOf("error" to "Release not found"))

        return exportWordDocument(
            requirements = exportData.requirements,
            title = exportData.title,
            filename = exportData.filename,
            templateMode = templateMode ?: "ADHOC",
            templateId = null,
            adHocTemplate = templateFile,
            missingPlaceholderBehavior = missingPlaceholderBehavior ?: "REJECT",
            classification = classification,
            language = null,
            scope = if (releaseId != null) RequirementExportScope.RELEASE else RequirementExportScope.ALL,
            releaseId = releaseId,
            usecaseId = null,
            authentication = authentication
        )
    }

    @Get("/export/docx/usecase/{usecaseId}")
    fun exportToDocxByUseCase(
        @PathVariable usecaseId: Long,
        @Nullable @QueryValue("releaseId") releaseId: Long?,
        @QueryValue(defaultValue = "LATEST") templateMode: String,
        @Nullable @QueryValue("templateId") templateId: Long?,
        @QueryValue(defaultValue = "REJECT") missingPlaceholderBehavior: String,
        @Nullable @QueryValue("classification") classification: String?,
        authentication: Authentication
    ): HttpResponse<*> {
        val useCaseOptional = useCaseRepository.findById(usecaseId)

        if (useCaseOptional.isEmpty) {
            return HttpResponse.notFound<Any>().body(mapOf("error" to "UseCase not found"))
        }

        val useCase = useCaseOptional.get()
        val requirements = getRequirementsByUseCase(usecaseId, releaseId)

        if (requirements.isEmpty()) {
            return HttpResponse.ok(mapOf("message" to "No requirements found for this use case"))
        }

        val filename = "requirements_usecase_${useCase.name.replace(" ", "_")}.docx"
            .replace("\"", "").replace("\r", "").replace("\n", "")

        return exportWordDocument(
            requirements = requirements,
            title = "Requirements for UseCase: ${useCase.name}",
            filename = filename,
            templateMode = templateMode,
            templateId = templateId,
            adHocTemplate = null,
            missingPlaceholderBehavior = missingPlaceholderBehavior,
            classification = classification,
            language = null,
            scope = RequirementExportScope.USE_CASE,
            releaseId = releaseId,
            usecaseId = usecaseId,
            authentication = authentication
        )
    }

    @Post("/export/docx/usecase/{usecaseId}")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    fun exportToDocxByUseCaseWithAdHocTemplate(
        @PathVariable usecaseId: Long,
        @Nullable @QueryValue("releaseId") releaseId: Long?,
        @Part templateFile: CompletedFileUpload,
        @Nullable @Part templateMode: String?,
        @Nullable @Part missingPlaceholderBehavior: String?,
        @Nullable @Part classification: String?,
        authentication: Authentication
    ): HttpResponse<*> {
        val useCaseOptional = useCaseRepository.findById(usecaseId)
        if (useCaseOptional.isEmpty) {
            return HttpResponse.notFound<Any>().body(mapOf("error" to "UseCase not found"))
        }
        val useCase = useCaseOptional.get()
        val requirements = getRequirementsByUseCase(usecaseId, releaseId)
        if (requirements.isEmpty()) {
            return HttpResponse.ok(mapOf("message" to "No requirements found for this use case"))
        }
        val filename = "requirements_usecase_${useCase.name.replace(" ", "_")}.docx"
            .replace("\"", "").replace("\r", "").replace("\n", "")

        return exportWordDocument(
            requirements = requirements,
            title = "Requirements for UseCase: ${useCase.name}",
            filename = filename,
            templateMode = templateMode ?: "ADHOC",
            templateId = null,
            adHocTemplate = templateFile,
            missingPlaceholderBehavior = missingPlaceholderBehavior ?: "REJECT",
            classification = classification,
            language = null,
            scope = RequirementExportScope.USE_CASE,
            releaseId = releaseId,
            usecaseId = usecaseId,
            authentication = authentication
        )
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
    fun exportToExcelByUseCase(@PathVariable usecaseId: Long, @Nullable @QueryValue("releaseId") releaseId: Long?): HttpResponse<*> {
        val useCaseOptional = useCaseRepository.findById(usecaseId)

        if (useCaseOptional.isEmpty) {
            return HttpResponse.notFound<Any>().body(mapOf("error" to "UseCase not found"))
        }

        val useCase = useCaseOptional.get()
        val requirements = getRequirementsByUseCase(usecaseId, releaseId)

        if (requirements.isEmpty()) {
            return HttpResponse.ok(mapOf("message" to "No requirements found for this use case"))
        }

        val workbook = createExcelWorkbook(requirements)
        val outputStream = ByteArrayOutputStream()
        workbook.write(outputStream)
        workbook.close()

        val inputStream = ByteArrayInputStream(outputStream.toByteArray())
        val filename = "requirements_usecase_${useCase.name.replace(" ", "_")}.xlsx"
            .replace("\"", "").replace("\r", "").replace("\n", "")
        
        return HttpResponse.ok(StreamedFile(inputStream, MediaType.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")))
            .header("Content-Disposition", "attachment; filename=\"$filename\"")
    }


    private data class RequirementExportData(
        val requirements: List<Requirement>,
        val filename: String,
        val title: String
    )

    private data class ResolvedTemplate(
        val mode: RequirementExportTemplateMode,
        val savedTemplate: RequirementExportTemplate?,
        val bytes: ByteArray?,
        val sha256: String?
    )

    private fun resolveRequirementExportData(releaseId: Long?): RequirementExportData? {
        return if (releaseId != null) {
            val releaseOpt = releaseRepository.findById(releaseId)
            if (releaseOpt.isEmpty) {
                null
            } else {
                val release = releaseOpt.get()
                val snapshots = snapshotRepository.findByReleaseId(releaseId)
                val requirements = snapshots.map { snapshotToRequirement(it) }.sortedWith(
                    compareBy<Requirement> { it.chapter ?: "" }.thenBy { it.id ?: 0 }
                )
                val dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                RequirementExportData(
                    requirements = requirements,
                    filename = "requirements_v${release.version}_$dateStr.docx",
                    title = "Requirements - Release ${release.version}"
                )
            }
        } else {
            RequirementExportData(
                requirements = requirementRepository.findAll().sortedWith(
                    compareBy<Requirement> { it.chapter ?: "" }.thenBy { it.id ?: 0 }
                ),
                filename = "requirements_export.docx",
                title = "All Requirements"
            )
        }
    }

    private fun exportWordDocument(
        requirements: List<Requirement>,
        title: String,
        filename: String,
        templateMode: String,
        templateId: Long?,
        adHocTemplate: CompletedFileUpload?,
        missingPlaceholderBehavior: String,
        classification: String?,
        language: String?,
        scope: RequirementExportScope,
        releaseId: Long?,
        usecaseId: Long?,
        authentication: Authentication
    ): HttpResponse<*> {
        val behavior = parseMissingPlaceholderBehavior(missingPlaceholderBehavior)
        val resolvedTemplate = try {
            resolveTemplate(templateMode, templateId, adHocTemplate, behavior)
                ?: return HttpResponse.badRequest(mapOf("error" to "Selected template was not found or is inactive"))
        } catch (e: IllegalArgumentException) {
            return HttpResponse.badRequest(mapOf("error" to (e.message ?: "Template validation failed")))
        }

        val document = if (resolvedTemplate.bytes != null) {
            createTemplatedWordDocument(
                templateBytes = resolvedTemplate.bytes,
                requirements = requirements,
                title = title,
                exportedBy = authentication.name,
                language = language ?: "english",
                classification = classification ?: "Internal"
            )
        } else {
            createWordDocument(requirements, title)
        }

        exportTemplateUsageRepository.save(
            RequirementExportTemplateUsage(
                template = resolvedTemplate.savedTemplate,
                templateSha256 = resolvedTemplate.sha256,
                exportedBy = authentication.name,
                exportScope = scope,
                releaseId = releaseId,
                usecaseId = usecaseId,
                language = language,
                templateMode = resolvedTemplate.mode
            )
        )
        resolvedTemplate.savedTemplate?.let { template ->
            template.lastUsedAt = Instant.now()
            exportTemplateRepository.update(template)
        }

        return streamWordDocument(document, filename)
    }

    private fun resolveTemplate(
        templateMode: String,
        templateId: Long?,
        adHocTemplate: CompletedFileUpload?,
        missingPlaceholderBehavior: MissingPlaceholderBehavior
    ): ResolvedTemplate? {
        val mode = parseTemplateMode(templateMode)
        return when (mode) {
            RequirementExportTemplateMode.NONE -> ResolvedTemplate(mode, null, null, null)
            RequirementExportTemplateMode.LATEST -> {
                val latest = exportTemplateRepository.findFirstByStatusOrderByCreatedAtDesc(RequirementExportTemplateStatus.ACTIVE)
                if (latest.isPresent) {
                    val template = latest.get()
                    ResolvedTemplate(mode, template, template.content, template.sha256)
                } else {
                    ResolvedTemplate(RequirementExportTemplateMode.NONE, null, null, null)
                }
            }
            RequirementExportTemplateMode.SAVED -> {
                if (templateId == null) return null
                val template = exportTemplateRepository.findById(templateId)
                if (template.isEmpty || template.get().status != RequirementExportTemplateStatus.ACTIVE) return null
                ResolvedTemplate(mode, template.get(), template.get().content, template.get().sha256)
            }
            RequirementExportTemplateMode.ADHOC -> {
                if (adHocTemplate == null) return null
                val bytes = adHocTemplate.bytes
                val report = exportTemplateValidationService.validate(
                    bytes = bytes,
                    filename = adHocTemplate.filename,
                    contentType = adHocTemplate.contentType.map { it.toString() }.orElse(null),
                    requireRequirementsPlaceholder = missingPlaceholderBehavior == MissingPlaceholderBehavior.REJECT
                )
                if (!report.valid) {
                    throw IllegalArgumentException(report.errors.joinToString("; "))
                }
                ResolvedTemplate(mode, null, bytes, report.sha256)
            }
        }
    }


    private fun parseTemplateMode(templateMode: String): RequirementExportTemplateMode = try {
        RequirementExportTemplateMode.valueOf(templateMode.trim().uppercase(Locale.ROOT))
    } catch (e: Exception) {
        RequirementExportTemplateMode.LATEST
    }

    private fun parseMissingPlaceholderBehavior(value: String): MissingPlaceholderBehavior = try {
        MissingPlaceholderBehavior.valueOf(value.trim().uppercase(Locale.ROOT))
    } catch (e: Exception) {
        MissingPlaceholderBehavior.REJECT
    }

    private fun createTemplatedWordDocument(
        templateBytes: ByteArray,
        requirements: List<Requirement>,
        title: String,
        exportedBy: String,
        language: String,
        classification: String
    ): XWPFDocument {
        val document = XWPFDocument(ByteArrayInputStream(templateBytes))
        val replacements = mapOf(
            "requirements" to "",
            "documentTitle" to title,
            "exportDate" to LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            "releaseVersion" to title.substringAfter("Release ", ""),
            "releaseStatus" to "",
            "useCaseName" to title.substringAfter("UseCase: ", ""),
            "exportedBy" to exportedBy,
            "language" to language,
            "requirementCount" to requirements.size.toString(),
            "classification" to classification
        )
        replacePlaceholders(document, replacements)
        document.createParagraph().createRun().addBreak(org.apache.poi.xwpf.usermodel.BreakType.PAGE)
        appendRequirementContent(document, requirements)
        return document
    }

    private fun replacePlaceholders(document: XWPFDocument, replacements: Map<String, String>) {
        document.paragraphs.forEach { replacePlaceholdersInParagraph(it, replacements) }
        document.tables.forEach { replacePlaceholdersInTable(it, replacements) }
        document.headerList.forEach { header ->
            header.paragraphs.forEach { replacePlaceholdersInParagraph(it, replacements) }
            header.tables.forEach { replacePlaceholdersInTable(it, replacements) }
        }
        document.footerList.forEach { footer ->
            footer.paragraphs.forEach { replacePlaceholdersInParagraph(it, replacements) }
            footer.tables.forEach { replacePlaceholdersInTable(it, replacements) }
        }
    }

    private fun replacePlaceholdersInTable(table: XWPFTable, replacements: Map<String, String>) {
        table.rows.forEach { row ->
            row.tableCells.forEach { cell ->
                cell.paragraphs.forEach { replacePlaceholdersInParagraph(it, replacements) }
                cell.tables.forEach { replacePlaceholdersInTable(it, replacements) }
            }
        }
    }

    private fun replacePlaceholdersInParagraph(paragraph: XWPFParagraph, replacements: Map<String, String>) {
        if (paragraph.runs.isEmpty()) return
        val fullText = paragraph.runs.joinToString(separator = "") { it.text() ?: "" }
        var replaced = fullText
        replacements.forEach { (key, value) ->
            replaced = replaced.replace("${'$'}{$key}", value)
        }
        if (replaced == fullText) return
        paragraph.runs.drop(1).forEach { paragraph.removeRun(1) }
        paragraph.runs.firstOrNull()?.setText(replaced, 0)
    }

    private fun streamWordDocument(document: XWPFDocument, filename: String): HttpResponse<*> {
        val outputStream = ByteArrayOutputStream()
        document.write(outputStream)
        document.close()
        val inputStream = ByteArrayInputStream(outputStream.toByteArray())
        return HttpResponse.ok(StreamedFile(inputStream, MediaType.of(RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE)))
            .header("Content-Disposition", "attachment; filename=\"${filename.replace("\"", "").replace("\r", "").replace("\n", "")}\"")
    }

    private fun appendRequirementContent(document: XWPFDocument, requirements: List<Requirement>) {
        val requirementsByChapter = requirements.groupBy { it.chapter ?: "No Chapter" }
        var requirementNumber = 1
        var isFirstChapter = true
        for ((chapter, chapterRequirements) in requirementsByChapter) {
            if (!isFirstChapter) {
                document.createParagraph().createRun().addBreak(org.apache.poi.xwpf.usermodel.BreakType.PAGE)
            }
            isFirstChapter = false
            val chapterParagraph = document.createParagraph()
            chapterParagraph.style = "Heading1"
            val chapterRun = chapterParagraph.createRun()
            chapterRun.setText(chapter)
            chapterRun.fontSize = 16
            chapterRun.isBold = true
            document.createParagraph()

            for (requirement in chapterRequirements) {
                val reqHeaderParagraph = document.createParagraph()
                val ctp = reqHeaderParagraph.ctp
                val ppr = if (ctp.isSetPPr) ctp.pPr else ctp.addNewPPr()
                val shd = if (ppr.isSetShd) ppr.shd else ppr.addNewShd()
                shd.fill = "C1D5C0"
                val reqHeaderRun = reqHeaderParagraph.createRun()
                reqHeaderRun.setText("REQ-$requirementNumber: ${requirement.shortreq}")
                reqHeaderRun.fontSize = 12
                reqHeaderRun.isBold = true
                requirement.details?.let { document.createParagraph().createRun().setText(it) }
                requirement.motivation?.let {
                    val paragraph = document.createParagraph()
                    paragraph.createRun().apply { setText("Motivation: "); isBold = true }
                    paragraph.createRun().setText(it)
                }
                requirement.example?.let {
                    val paragraph = document.createParagraph()
                    paragraph.createRun().apply { setText("Example: "); isBold = true }
                    paragraph.createRun().setText(it)
                }
                requirement.norm?.let {
                    val paragraph = document.createParagraph()
                    paragraph.createRun().apply { setText("Norm Reference: "); isBold = true }
                    paragraph.createRun().setText(it)
                }
                val canonicalUseCases = setOf("IT", "OT", "NT")
                val idSuffix = buildString {
                    append(requirementNumber)
                    append(".")
                    append(requirement.versionNumber)
                    requirement.usecases.map { it.name }.filter { it in canonicalUseCases }.sorted().forEach {
                        append(".")
                        append(it)
                    }
                }
                val idParagraph = document.createParagraph()
                idParagraph.alignment = org.apache.poi.xwpf.usermodel.ParagraphAlignment.LEFT
                val idRun = idParagraph.createRun()
                idRun.setText("ID $idSuffix")
                idRun.fontSize = 8
                idRun.color = "999999"
                document.createParagraph()
                requirementNumber++
            }
        }
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
                reqHeaderRun.setText("REQ-$requirementNumber: ${requirement.shortreq}")
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

                // Internal ID with use cases - small, non-dominant font
                // Only include the canonical use case IDs: IT, OT, NT
                val canonicalUseCases = setOf("IT", "OT", "NT")
                val idSuffix = buildString {
                    append(requirementNumber)
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
                idParagraph.alignment = org.apache.poi.xwpf.usermodel.ParagraphAlignment.LEFT
                val idRun = idParagraph.createRun()
                idRun.setText("ID $idSuffix")
                idRun.fontSize = 8
                idRun.color = "999999"

                document.createParagraph() // Empty line between requirements
                requirementNumber++
            }
        }
        
        return document
    }

    private fun createExcelWorkbook(requirements: List<Requirement>): XSSFWorkbook {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Reqs") // Use "Reqs" to match import format

        // Header row - ID.Revision as first column
        val headerRow = sheet.createRow(0)
        val headers = arrayOf("ID.Revision", "Chapter", "Norm", "Short req", "DetailsEN", "MotivationEN", "ExampleEN", "UseCase")

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

            // ID.Revision - first column
            row.createCell(0).setCellValue(ExcelSanitizer.sanitize(requirement.idRevision))

            // Chapter
            row.createCell(1).setCellValue(ExcelSanitizer.sanitize(requirement.chapter))

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
            row.createCell(2).setCellValue(ExcelSanitizer.sanitize(normString))

            // Short req
            row.createCell(3).setCellValue(ExcelSanitizer.sanitize(requirement.shortreq))

            // DetailsEN
            row.createCell(4).setCellValue(ExcelSanitizer.sanitize(requirement.details))

            // MotivationEN
            row.createCell(5).setCellValue(ExcelSanitizer.sanitize(requirement.motivation))

            // ExampleEN
            row.createCell(6).setCellValue(ExcelSanitizer.sanitize(requirement.example))

            // UseCase - combine all use case names
            val useCaseString = if (requirement.usecases.isNotEmpty()) {
                requirement.usecases.joinToString(", ") { it.name }
            } else {
                requirement.usecase ?: "" // Fallback to original usecase string
            }
            row.createCell(7).setCellValue(ExcelSanitizer.sanitize(useCaseString))
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
    fun exportToDocxTranslated(
        @PathVariable language: String,
        @QueryValue(defaultValue = "NONE") templateMode: String,
        @Nullable @QueryValue("templateId") templateId: Long?,
        @QueryValue(defaultValue = "REJECT") missingPlaceholderBehavior: String,
        @Nullable @QueryValue("classification") classification: String?,
        authentication: Authentication
    ): HttpResponse<*> {
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
            val filename = "requirements_translated_${language}_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}.docx"
            if (parseTemplateMode(templateMode) == RequirementExportTemplateMode.NONE) {
                streamWordDocument(createTranslatedWordDocument(requirements, "Translated Requirements", language), filename)
            } else {
                exportWordDocument(
                    requirements = requirements,
                    title = "Translated Requirements",
                    filename = filename,
                    templateMode = templateMode,
                    templateId = templateId,
                    adHocTemplate = null,
                    missingPlaceholderBehavior = missingPlaceholderBehavior,
                    classification = classification,
                    language = language,
                    scope = RequirementExportScope.TRANSLATED,
                    releaseId = null,
                    usecaseId = null,
                    authentication = authentication
                )
            }
        } catch (e: Exception) {
            HttpResponse.serverError(mapOf(
                "error" to "Translation export failed",
                "message" to "An internal error occurred"
            ))
        }
    }

    @Get("/export/docx/usecase/{usecaseId}/translated/{language}")
    fun exportToDocxTranslatedByUseCase(
        @PathVariable usecaseId: Long,
        @PathVariable language: String,
        @Nullable @QueryValue("releaseId") releaseId: Long?,
        @QueryValue(defaultValue = "NONE") templateMode: String,
        @Nullable @QueryValue("templateId") templateId: Long?,
        @QueryValue(defaultValue = "REJECT") missingPlaceholderBehavior: String,
        @Nullable @QueryValue("classification") classification: String?,
        authentication: Authentication
    ): HttpResponse<*> {
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
        val requirements = getRequirementsByUseCase(usecaseId, releaseId)

        if (requirements.isEmpty()) {
            return HttpResponse.ok(mapOf("message" to "No requirements found for this use case"))
        }

        return try {
            val filename = "requirements_usecase_${useCase.name.replace(" ", "_")}_translated_${language}_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}.docx"
                .replace("\"", "").replace("\r", "").replace("\n", "")
            if (parseTemplateMode(templateMode) == RequirementExportTemplateMode.NONE) {
                streamWordDocument(createTranslatedWordDocument(requirements, "Translated Requirements for UseCase: ${useCase.name}", language), filename)
            } else {
                exportWordDocument(
                    requirements = requirements,
                    title = "Translated Requirements for UseCase: ${useCase.name}",
                    filename = filename,
                    templateMode = templateMode,
                    templateId = templateId,
                    adHocTemplate = null,
                    missingPlaceholderBehavior = missingPlaceholderBehavior,
                    classification = classification,
                    language = language,
                    scope = RequirementExportScope.TRANSLATED,
                    releaseId = releaseId,
                    usecaseId = usecaseId,
                    authentication = authentication
                )
            }
        } catch (e: Exception) {
            HttpResponse.serverError(mapOf(
                "error" to "Translation export failed",
                "message" to "An internal error occurred"
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
        val requirementsByChapter = requirements.groupBy { requirement ->
            requirement.chapter?.takeIf { it.isNotBlank() }
        }
        var requirementNumber = 1

        for ((chapter, chapterRequirements) in requirementsByChapter) {
            if (chapter != null) {
                val chapterParagraph = document.createParagraph()
                val chapterRun = chapterParagraph.createRun()
                chapterRun.setText("Chapter: $chapter")
                chapterRun.fontSize = 14
                chapterRun.isBold = true
                document.createParagraph()
            }

            for (requirement in chapterRequirements) {
                // Short requirement (use batch-translated value)
                val shortReqTranslated = translationMap[requirement.shortreq] ?: requirement.shortreq

                val shortReqParagraph = document.createParagraph()
                val ctp = shortReqParagraph.ctp
                val ppr = if (ctp.isSetPPr) ctp.pPr else ctp.addNewPPr()
                val shd = if (ppr.isSetShd) ppr.shd else ppr.addNewShd()
                shd.fill = "C1D5C0"

                val shortReqRun = shortReqParagraph.createRun()
                shortReqRun.setText("REQ-$requirementNumber: $shortReqTranslated")
                shortReqRun.fontSize = 12
                shortReqRun.isBold = true

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

                // Internal ID with use cases - small, non-dominant font
                val idSuffix = buildString {
                    append(requirementNumber)
                    append(".")
                    append(requirement.versionNumber)
                }
                val idParagraph = document.createParagraph()
                idParagraph.alignment = org.apache.poi.xwpf.usermodel.ParagraphAlignment.LEFT
                val idRun = idParagraph.createRun()
                idRun.setText("ID $idSuffix")
                idRun.fontSize = 8
                idRun.color = "999999"

                // Add space between requirements
                document.createParagraph()
                document.createParagraph()
                requirementNumber++
            }
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
                "message" to "An internal error occurred"
            ))
        }
    }

    @Get("/export/xlsx/usecase/{usecaseId}/translated/{language}")
    fun exportToExcelTranslatedByUseCase(@PathVariable usecaseId: Long, @PathVariable language: String, @Nullable @QueryValue("releaseId") releaseId: Long?): HttpResponse<*> {
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
        val requirements = getRequirementsByUseCase(usecaseId, releaseId)

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
                .replace("\"", "").replace("\r", "").replace("\n", "")
            
            HttpResponse.ok(StreamedFile(inputStream, MediaType.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")))
                .header("Content-Disposition", "attachment; filename=\"$filename\"")
        } catch (e: Exception) {
            HttpResponse.serverError(mapOf(
                "error" to "Translation export failed",
                "message" to "An internal error occurred"
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
            row.createCell(0).setCellValue(ExcelSanitizer.sanitize(requirement.chapter))

            // Norm (not translated)
            row.createCell(1).setCellValue(ExcelSanitizer.sanitize(requirement.norms.joinToString(", ") { it.name }))

            // Short requirement (use batch-translated value)
            val shortReqTranslated = translationMap[requirement.shortreq] ?: requirement.shortreq
            row.createCell(2).setCellValue(ExcelSanitizer.sanitize(shortReqTranslated))

            // Details (use batch-translated value)
            val detailsTranslated = if (!requirement.details.isNullOrBlank()) {
                translationMap[requirement.details] ?: requirement.details!!
            } else {
                ""
            }
            row.createCell(3).setCellValue(ExcelSanitizer.sanitize(detailsTranslated))

            // Motivation (use batch-translated value)
            val motivationTranslated = if (!requirement.motivation.isNullOrBlank()) {
                translationMap[requirement.motivation] ?: requirement.motivation!!
            } else {
                ""
            }
            row.createCell(4).setCellValue(ExcelSanitizer.sanitize(motivationTranslated))

            // Example (use batch-translated value)
            val exampleTranslated = if (!requirement.example.isNullOrBlank()) {
                translationMap[requirement.example] ?: requirement.example!!
            } else {
                ""
            }
            row.createCell(5).setCellValue(ExcelSanitizer.sanitize(exampleTranslated))

            // Use Cases (not translated)
            row.createCell(6).setCellValue(ExcelSanitizer.sanitize(requirement.usecases.joinToString(", ") { it.name }))
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
     * The internalId and versionNumber are set from the snapshot for correct ID.Revision display
     */
    /**
     * Get requirements for a use case, optionally from a release snapshot.
     * When releaseId is provided, filters snapshots by usecaseIdsSnapshot JSON field.
     */
    private fun getRequirementsByUseCase(usecaseId: Long, releaseId: Long?): List<Requirement> {
        return if (releaseId != null) {
            val snapshots = snapshotRepository.findByReleaseId(releaseId)
            snapshots.filter { snapshot ->
                val usecaseIds = snapshot.usecaseIdsSnapshot
                usecaseIds != null && usecaseIds.contains(usecaseId.toString())
            }.map { snapshotToRequirement(it) }.sortedWith(
                compareBy<Requirement> { it.chapter ?: "" }.thenBy { it.id ?: 0 }
            )
        } else {
            requirementRepository.findByUsecaseId(usecaseId).sortedWith(
                compareBy<Requirement> { it.chapter ?: "" }.thenBy { it.id ?: 0 }
            )
        }
    }

    private fun snapshotToRequirement(snapshot: RequirementSnapshot): Requirement {
        val requirement = Requirement(
            id = snapshot.originalRequirementId,
            internalId = snapshot.internalId,
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
        // Set versionNumber from snapshot's revision for correct idRevision display
        requirement.versionNumber = snapshot.revision
        return requirement
    }
}
