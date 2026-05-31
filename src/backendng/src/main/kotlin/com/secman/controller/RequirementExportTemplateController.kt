package com.secman.controller

import com.secman.domain.RequirementExportTemplate
import com.secman.domain.RequirementExportTemplateStatus
import com.secman.repository.RequirementExportTemplateRepository
import com.secman.repository.RequirementExportTemplateUsageRepository
import com.secman.service.RequirementExportTemplateValidationService
import io.micronaut.core.annotation.Nullable
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.http.multipart.CompletedFileUpload
import io.micronaut.http.server.types.files.StreamedFile
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.transaction.Transactional
import java.io.ByteArrayInputStream
import java.time.Instant

@Controller("/api/requirement-export-templates")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class RequirementExportTemplateController(
    private val templateRepository: RequirementExportTemplateRepository,
    private val usageRepository: RequirementExportTemplateUsageRepository,
    private val validationService: RequirementExportTemplateValidationService
) {
    @Serdeable
    data class TemplateSummary(
        val id: Long,
        val name: String,
        val description: String?,
        val versionLabel: String?,
        val status: RequirementExportTemplateStatus,
        val originalFilename: String,
        val fileSizeBytes: Long,
        val sha256: String,
        val uploadedBy: String,
        val createdAt: Instant,
        val activatedAt: Instant?,
        val deactivatedAt: Instant?,
        val lastUsedAt: Instant?,
        val usageCount: Long? = null
    )

    @Serdeable
    data class TemplateDetail(
        val summary: TemplateSummary,
        val validationReportJson: String?
    )

    @Serdeable
    data class ErrorResponse(val error: String)

    @Get
    open fun list(@QueryValue(defaultValue = "false") includeInactive: Boolean): List<TemplateSummary> {
        val templates = if (includeInactive) {
            templateRepository.findAll().sortedWith(compareByDescending<RequirementExportTemplate> { it.status == RequirementExportTemplateStatus.ACTIVE }.thenByDescending { it.createdAt })
        } else {
            templateRepository.findByStatusOrderByCreatedAtDesc(RequirementExportTemplateStatus.ACTIVE)
        }
        return templates.map { it.toSummary() }
    }

    @Get("/latest")
    open fun latest(): HttpResponse<*> {
        val latest = templateRepository.findFirstByStatusOrderByCreatedAtDesc(RequirementExportTemplateStatus.ACTIVE)
        return if (latest.isPresent) {
            HttpResponse.ok(latest.get().toSummary())
        } else {
            HttpResponse.noContent<Any>()
        }
    }

    @Post("/validate")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Secured("ADMIN", "REQADMIN")
    open fun validate(
        @Part templateFile: CompletedFileUpload,
        @Nullable @Part requireRequirementsPlaceholder: Boolean?
    ): HttpResponse<*> {
        val report = validationService.validate(
            bytes = templateFile.bytes,
            filename = templateFile.filename,
            contentType = templateFile.contentType.map { it.toString() }.orElse(null),
            requireRequirementsPlaceholder = requireRequirementsPlaceholder ?: true
        )
        return if (report.valid) HttpResponse.ok(report) else HttpResponse.badRequest(report)
    }

    @Post
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Secured("ADMIN", "REQADMIN")
    @Transactional
    open fun upload(
        @Part templateFile: CompletedFileUpload,
        @Part name: String?,
        @Part description: String?,
        @Part versionLabel: String?,
        @Nullable @Part activate: Boolean?,
        @Nullable @Part requireRequirementsPlaceholder: Boolean?,
        authentication: Authentication
    ): HttpResponse<*> {
        val bytes = templateFile.bytes
        val report = validationService.validate(
            bytes = bytes,
            filename = templateFile.filename,
            contentType = templateFile.contentType.map { it.toString() }.orElse(null),
            requireRequirementsPlaceholder = requireRequirementsPlaceholder ?: true
        )
        if (!report.valid) {
            return HttpResponse.badRequest(report)
        }

        val now = Instant.now()
        val template = RequirementExportTemplate(
            name = name?.takeIf { it.isNotBlank() }?.trim() ?: templateFile.filename.substringBeforeLast('.').ifBlank { "Requirement export template" },
            description = description?.takeIf { it.isNotBlank() }?.trim(),
            versionLabel = versionLabel?.takeIf { it.isNotBlank() }?.trim(),
            status = if (activate != false) RequirementExportTemplateStatus.ACTIVE else RequirementExportTemplateStatus.INACTIVE,
            originalFilename = sanitizeFilename(templateFile.filename),
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE,
            fileSizeBytes = bytes.size.toLong(),
            sha256 = report.sha256,
            content = bytes,
            validationReportJson = validationService.toJson(report),
            uploadedBy = authentication.name,
            createdAt = now,
            activatedAt = if (activate != false) now else null
        )
        val saved = templateRepository.save(template)
        return HttpResponse.created(saved.toSummary())
    }

    @Get("/{id}")
    @Secured("ADMIN", "REQADMIN")
    open fun detail(@PathVariable id: Long): HttpResponse<*> {
        val template = templateRepository.findById(id)
        return if (template.isPresent) {
            HttpResponse.ok(TemplateDetail(template.get().toSummary(usageRepository.countByTemplateId(id)), template.get().validationReportJson))
        } else {
            HttpResponse.notFound(ErrorResponse("Template not found"))
        }
    }

    @Get("/{id}/download")
    @Secured("ADMIN", "REQADMIN")
    open fun download(@PathVariable id: Long): HttpResponse<*> {
        val template = templateRepository.findById(id)
        if (template.isEmpty) {
            return HttpResponse.notFound(ErrorResponse("Template not found"))
        }
        val selected = template.get()
        return HttpResponse.ok(StreamedFile(ByteArrayInputStream(selected.content), MediaType.of(RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE)))
            .header("Content-Disposition", "attachment; filename=\"${sanitizeFilename(selected.originalFilename)}\"")
    }

    @Post("/{id}/activate")
    @Secured("ADMIN", "REQADMIN")
    @Transactional
    open fun activate(@PathVariable id: Long): HttpResponse<*> {
        val template = templateRepository.findById(id)
        if (template.isEmpty) {
            return HttpResponse.notFound(ErrorResponse("Template not found"))
        }
        val selected = template.get()
        selected.status = RequirementExportTemplateStatus.ACTIVE
        selected.activatedAt = Instant.now()
        selected.deactivatedAt = null
        return HttpResponse.ok(templateRepository.update(selected).toSummary())
    }

    @Post("/{id}/deactivate")
    @Secured("ADMIN", "REQADMIN")
    @Transactional
    open fun deactivate(@PathVariable id: Long): HttpResponse<*> {
        val template = templateRepository.findById(id)
        if (template.isEmpty) {
            return HttpResponse.notFound(ErrorResponse("Template not found"))
        }
        val selected = template.get()
        selected.status = RequirementExportTemplateStatus.INACTIVE
        selected.deactivatedAt = Instant.now()
        return HttpResponse.ok(templateRepository.update(selected).toSummary())
    }

    @Delete("/{id}")
    @Secured("ADMIN", "REQADMIN")
    @Transactional
    open fun delete(@PathVariable id: Long): HttpResponse<*> {
        if (!templateRepository.existsById(id)) {
            return HttpResponse.notFound(ErrorResponse("Template not found"))
        }
        val usageCount = usageRepository.countByTemplateId(id)
        if (usageCount > 0) {
            val template = templateRepository.findById(id).get()
            template.status = RequirementExportTemplateStatus.RETIRED
            template.deactivatedAt = Instant.now()
            templateRepository.update(template)
            return HttpResponse.ok(mapOf("message" to "Template has been retired because it was already used by exports."))
        }
        templateRepository.deleteById(id)
        return HttpResponse.noContent<Any>()
    }

    @Get("/{id}/usage")
    @Secured("ADMIN", "REQADMIN")
    open fun usage(@PathVariable id: Long): HttpResponse<*> {
        if (!templateRepository.existsById(id)) {
            return HttpResponse.notFound(ErrorResponse("Template not found"))
        }
        return HttpResponse.ok(usageRepository.findByTemplateIdOrderByCreatedAtDesc(id))
    }

    private fun RequirementExportTemplate.toSummary(usageCount: Long? = null): TemplateSummary = TemplateSummary(
        id = id ?: 0,
        name = name,
        description = description,
        versionLabel = versionLabel,
        status = status,
        originalFilename = originalFilename,
        fileSizeBytes = fileSizeBytes,
        sha256 = sha256,
        uploadedBy = uploadedBy,
        createdAt = createdAt,
        activatedAt = activatedAt,
        deactivatedAt = deactivatedAt,
        lastUsedAt = lastUsedAt,
        usageCount = usageCount
    )

    private fun sanitizeFilename(filename: String): String = filename
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[\\r\\n\"]"), "")
        .ifBlank { "requirement-template.docx" }
}
