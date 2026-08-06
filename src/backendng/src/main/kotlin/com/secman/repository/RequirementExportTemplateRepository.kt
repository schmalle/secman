package com.secman.repository

import com.secman.domain.RequirementExportTemplate
import com.secman.domain.RequirementExportTemplateStatus
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.Optional

@Repository
interface RequirementExportTemplateRepository : JpaRepository<RequirementExportTemplate, Long> {
    fun findByStatusOrderByCreatedAtDesc(status: RequirementExportTemplateStatus): List<RequirementExportTemplate>
    fun findFirstByStatusOrderByCreatedAtDesc(status: RequirementExportTemplateStatus): Optional<RequirementExportTemplate>
}
