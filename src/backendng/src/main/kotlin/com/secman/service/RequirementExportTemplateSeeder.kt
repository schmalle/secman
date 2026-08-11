package com.secman.service

import com.secman.domain.RequirementExportTemplate
import com.secman.domain.RequirementExportTemplateStatus
import com.secman.repository.RequirementExportTemplateRepository
import io.micronaut.context.annotation.Requires
import io.micronaut.context.annotation.Value
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.runtime.event.ApplicationStartupEvent
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Installs the shipped example company template on first start.
 *
 * Without this, `templateMode=LATEST` — already the export default — finds no ACTIVE row and
 * silently degrades to the built-in layout, so a fresh installation never produces a
 * company-designed document until somebody happens to upload one.
 *
 * Deliberately conservative:
 *  - It seeds only when the table is **completely empty**. Keying on "no ACTIVE template" would
 *    resurrect the example every restart after an admin retired or deactivated it on purpose.
 *  - The shipped bytes go through [RequirementExportTemplateValidationService] like any upload.
 *    A resource is not a trust boundary we should treat differently from a request, and validating
 *    it here means a build that ships a broken artefact fails loudly in the log rather than at the
 *    first export.
 *  - A failure is logged and swallowed at the seam, never rethrown: a template that cannot be
 *    seeded must not stop the application from booting.
 */
@Requires(notEnv = ["cli"])
@Singleton
open class RequirementExportTemplateSeeder(
    private val templateRepository: RequirementExportTemplateRepository,
    private val validationService: RequirementExportTemplateValidationService,
    private val exampleBuilder: ExampleRequirementExportTemplateBuilder,
    @Value("\${secman.requirement-export-templates.seed-example:true}")
    private val seedExample: Boolean
) : ApplicationEventListener<ApplicationStartupEvent> {

    private val logger = LoggerFactory.getLogger(RequirementExportTemplateSeeder::class.java)

    companion object {
        /** Recorded as the uploader so the row is visibly not an admin's own upload. */
        const val SEEDED_BY = "system"
    }

    override fun onApplicationEvent(event: ApplicationStartupEvent) {
        if (!seedExample) {
            logger.debug("Requirement export template seeding disabled by configuration")
            return
        }
        try {
            seed()
        } catch (e: Exception) {
            // Never block startup on this. A missing company template degrades the export to the
            // built-in layout; a failed boot takes the whole application down.
            logger.error("Failed to seed the example requirement export template: {}", e.message, e)
        }
    }

    @Transactional
    open fun seed() {
        if (templateRepository.count() > 0L) {
            logger.debug("Requirement export templates already present, skipping example seeding")
            return
        }

        val bytes = exampleBuilder.loadOrBuild()

        val report = validationService.validate(
            bytes = bytes,
            filename = ExampleRequirementExportTemplateBuilder.FILENAME,
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE,
            requireRequirementsPlaceholder = true
        )
        if (!report.valid) {
            logger.error(
                "Shipped example requirement export template failed validation and was not seeded: {}",
                report.errors.joinToString("; ")
            )
            return
        }

        val now = Instant.now()
        val saved = templateRepository.save(
            RequirementExportTemplate(
                name = ExampleRequirementExportTemplateBuilder.TEMPLATE_NAME,
                description = ExampleRequirementExportTemplateBuilder.TEMPLATE_DESCRIPTION,
                versionLabel = ExampleRequirementExportTemplateBuilder.TEMPLATE_VERSION_LABEL,
                status = RequirementExportTemplateStatus.ACTIVE,
                originalFilename = ExampleRequirementExportTemplateBuilder.FILENAME,
                contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE,
                fileSizeBytes = bytes.size.toLong(),
                sha256 = report.sha256,
                content = bytes,
                validationReportJson = validationService.toJson(report),
                uploadedBy = SEEDED_BY,
                createdAt = now,
                activatedAt = now
            )
        )
        logger.info(
            "Seeded example requirement export template id={} sha256={} by={}",
            saved.id, saved.sha256, SEEDED_BY
        )
    }
}
