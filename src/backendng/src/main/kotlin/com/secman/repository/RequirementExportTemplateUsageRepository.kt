package com.secman.repository

import com.secman.domain.RequirementExportTemplateUsage
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

@Repository
interface RequirementExportTemplateUsageRepository : JpaRepository<RequirementExportTemplateUsage, Long> {
    fun findByTemplateIdOrderByCreatedAtDesc(templateId: Long): List<RequirementExportTemplateUsage>
    fun countByTemplateId(templateId: Long): Long

    /**
     * Nullify the template reference when a template is deleted.
     *
     * Preserves the export audit trail without blocking deletion via the
     * requirement_export_template_usage.template_id → requirement_export_template.id FK. The row
     * stays fully auditable on its own: it keeps `templateSha256`, `exportedBy`, `exportScope` and
     * `createdAt`, so "who exported what, under which template digest" outlives the template.
     *
     * V231 already declares the FK `ON DELETE SET NULL`, but that clause lives only in the Flyway
     * migration: the `test` profile builds the schema with Hibernate `create-drop` and Flyway off,
     * where the generated FK has no `ON DELETE` action and the delete fails instead. Doing it here
     * makes both schema paths behave identically.
     */
    @Query("UPDATE RequirementExportTemplateUsage u SET u.template = NULL WHERE u.template.id = :templateId")
    fun nullifyTemplateForTemplate(templateId: Long): Int
}
