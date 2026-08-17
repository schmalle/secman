package com.secman.controller

import com.secman.domain.RequirementExportScope
import com.secman.domain.RequirementExportTemplate
import com.secman.domain.RequirementExportTemplateMode
import com.secman.domain.RequirementExportTemplateStatus
import com.secman.repository.RequirementExportTemplateRepository
import com.secman.repository.RequirementExportTemplateUsageRepository
import com.secman.service.ExampleRequirementExportTemplateBuilder
import com.secman.service.RequirementExportTemplateValidationService
import io.micronaut.context.annotation.Value
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
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.time.Instant

@Controller("/api/requirement-export-templates")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class RequirementExportTemplateController(
    private val templateRepository: RequirementExportTemplateRepository,
    private val usageRepository: RequirementExportTemplateUsageRepository,
    private val validationService: RequirementExportTemplateValidationService,
    private val exampleBuilder: ExampleRequirementExportTemplateBuilder,
    @Value("\${secman.requirement-export-templates.max-file-size-bytes:5242880}")
    private val maxFileSizeBytes: Long
) {
    private val log = LoggerFactory.getLogger(RequirementExportTemplateController::class.java)

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

    /**
     * One export-usage row as the API returns it.
     *
     * The entity is deliberately not serialized directly. Its `template` is a LAZY `@ManyToOne`,
     * and by the time the response is written the session is gone, so Jackson hit
     * "could not initialize proxy … no session" and the endpoint returned 500. Initializing it
     * instead would be worse: the template carries the `.docx` bytes in a LONGBLOB `content`
     * column, which would then be inlined into every usage response.
     */
    @Serdeable
    data class UsageEntry(
        val id: Long?,
        val templateId: Long,
        val templateSha256: String?,
        val exportedBy: String,
        val exportScope: RequirementExportScope,
        val releaseId: Long?,
        val usecaseId: Long?,
        val language: String?,
        val templateMode: RequirementExportTemplateMode,
        val createdAt: Instant
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
        rejectOversized(templateFile)?.let { return it }
        val report = validationService.validate(
            bytes = templateFile.bytes,
            filename = templateFile.filename,
            contentType = templateFile.contentType.map { it.toString() }.orElse(null),
            requireRequirementsPlaceholder = requireRequirementsPlaceholder ?: true
        )
        return if (report.valid) HttpResponse.ok(report) else HttpResponse.badRequest(report)
    }

    /**
     * Rejects an oversized upload on the declared part size, **before** `.bytes` materialises it.
     *
     * The validator's own size check runs on the byte array, which means the array already exists;
     * `micronaut.server.multipart.max-file-size` is 100 MB (it stays large for the XLSX importers),
     * so without this the effective per-request heap bound is 100 MB rather than the 5 MB this
     * feature configures.
     */
    private fun rejectOversized(file: CompletedFileUpload): HttpResponse<*>? {
        if (file.size > maxFileSizeBytes) {
            log.warn(
                "Rejected requirement export template upload of {} bytes, above the {} byte limit",
                file.size, maxFileSizeBytes
            )
            return HttpResponse.badRequest(ErrorResponse("Template exceeds the maximum allowed file size."))
        }
        return null
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
        rejectOversized(templateFile)?.let { return it }
        val bytes = templateFile.bytes
        val report = validationService.validate(
            bytes = bytes,
            filename = templateFile.filename,
            contentType = templateFile.contentType.map { it.toString() }.orElse(null),
            requireRequirementsPlaceholder = requireRequirementsPlaceholder ?: true
        )
        if (!report.valid) {
            log.warn(
                "Rejected requirement export template upload by={} filename={} sha256={} reasons={}",
                authentication.name, sanitizeForLog(templateFile.filename), report.sha256,
                report.errors.joinToString("; ")
            )
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
        log.info(
            "Requirement export template uploaded by={} id={} name={} sha256={} status={} outcome=created",
            authentication.name, saved.id, sanitizeForLog(saved.name), saved.sha256, saved.status
        )
        return HttpResponse.created(saved.toSummary())
    }

    /**
     * The example company template shipped with secman, for rebranding.
     *
     * Served from the shipped bytes rather than from the seeded row, so it stays available and
     * pristine after an admin has replaced, deactivated or deleted the seeded copy.
     */
    @Get("/example")
    @Secured("ADMIN", "REQADMIN")
    open fun example(): HttpResponse<*> {
        val bytes = exampleBuilder.loadOrBuild()
        return HttpResponse.ok(StreamedFile(ByteArrayInputStream(bytes), MediaType.of(RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE)))
            .header("Content-Disposition", "attachment; filename=\"${ExampleRequirementExportTemplateBuilder.FILENAME}\"")
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
    open fun activate(@PathVariable id: Long, authentication: Authentication): HttpResponse<*> {
        val template = templateRepository.findById(id)
        if (template.isEmpty) {
            return HttpResponse.notFound(ErrorResponse("Template not found"))
        }
        val selected = template.get()
        selected.status = RequirementExportTemplateStatus.ACTIVE
        selected.activatedAt = Instant.now()
        selected.deactivatedAt = null
        // Activation changes the design and content of every subsequent export, including the
        // anonymously reachable one. That is an admin action and belongs in the log (A09).
        log.info(
            "Requirement export template activated by={} id={} name={} sha256={} outcome=active",
            authentication.name, selected.id, sanitizeForLog(selected.name), selected.sha256
        )
        return HttpResponse.ok(templateRepository.update(selected).toSummary())
    }

    @Post("/{id}/deactivate")
    @Secured("ADMIN", "REQADMIN")
    @Transactional
    open fun deactivate(@PathVariable id: Long, authentication: Authentication): HttpResponse<*> {
        val template = templateRepository.findById(id)
        if (template.isEmpty) {
            return HttpResponse.notFound(ErrorResponse("Template not found"))
        }
        val selected = template.get()
        selected.status = RequirementExportTemplateStatus.INACTIVE
        selected.deactivatedAt = Instant.now()
        log.info(
            "Requirement export template deactivated by={} id={} name={} outcome=inactive",
            authentication.name, selected.id, sanitizeForLog(selected.name)
        )
        return HttpResponse.ok(templateRepository.update(selected).toSummary())
    }

    /**
     * Deletes a template, detaching its export usage history first.
     *
     * A template that has ever been used is still deleted. This previously degraded to a status
     * change (RETIRED) whenever a usage row existed, which made deletion unreachable in practice:
     * the usage count only ever grows, so a retired template's Delete button re-ran the same branch
     * forever and the row never left the list.
     *
     * The usage rows are the audit record of who exported what, so they are detached rather than
     * cascade-deleted — each keeps its `templateSha256`, `exportedBy`, `exportScope` and timestamp
     * and stays readable after the template itself is gone (A09).
     */
    @Delete("/{id}")
    @Secured("ADMIN", "REQADMIN")
    @Transactional
    open fun delete(@PathVariable id: Long, authentication: Authentication): HttpResponse<*> {
        val template = templateRepository.findById(id)
        if (template.isEmpty) {
            return HttpResponse.notFound(ErrorResponse("Template not found"))
        }
        val detachedUsageRows = usageRepository.nullifyTemplateForTemplate(id)
        templateRepository.deleteById(id)
        log.info(
            "Requirement export template deleted by={} id={} name={} detachedUsageRows={} outcome=deleted",
            authentication.name, id, sanitizeForLog(template.get().name), detachedUsageRows
        )
        return HttpResponse.noContent<Any>()
    }

    @Get("/{id}/usage")
    @Secured("ADMIN", "REQADMIN")
    open fun usage(@PathVariable id: Long): HttpResponse<*> {
        if (!templateRepository.existsById(id)) {
            return HttpResponse.notFound(ErrorResponse("Template not found"))
        }
        // templateId comes from the path, not from usage.template: the query already filtered on it,
        // and reading it off the lazy proxy is what broke this endpoint in the first place.
        val entries = usageRepository.findByTemplateIdOrderByCreatedAtDesc(id).map { usage ->
            UsageEntry(
                id = usage.id,
                templateId = id,
                templateSha256 = usage.templateSha256,
                exportedBy = usage.exportedBy,
                exportScope = usage.exportScope,
                releaseId = usage.releaseId,
                usecaseId = usage.usecaseId,
                language = usage.language,
                templateMode = usage.templateMode,
                createdAt = usage.createdAt
            )
        }
        return HttpResponse.ok(entries)
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

    /**
     * Strips line breaks and bounds the length of a value going into a log line (log forging).
     * `\p{Cntrl}` is ASCII-only in Java, so the Unicode line separators are named explicitly.
     */
    private fun sanitizeForLog(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value.replace(Regex("[\\p{Cntrl}\\u0085\\u2028\\u2029]"), " ").trim().take(200)
    }

    private fun sanitizeFilename(filename: String): String = filename
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[\\r\\n\"]"), "")
        .ifBlank { "requirement-template.docx" }
}
