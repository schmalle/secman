package com.secman.repository

import com.secman.domain.RequirementExportTemplateUsage
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

@Repository
interface RequirementExportTemplateUsageRepository : JpaRepository<RequirementExportTemplateUsage, Long> {
    fun findByTemplateIdOrderByCreatedAtDesc(templateId: Long): List<RequirementExportTemplateUsage>
    fun countByTemplateId(templateId: Long): Long
}
