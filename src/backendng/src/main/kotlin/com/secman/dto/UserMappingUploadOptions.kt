package com.secman.dto

import com.secman.domain.AccountOnboardingMode
import io.micronaut.core.annotation.Introspected
import io.micronaut.http.annotation.QueryValue

/** Identical options for CSV and XLSX; mapping rows always come from the uploaded file. */
@Introspected
class UserMappingUploadOptions {
    @field:QueryValue(defaultValue = "false") var dryRun: Boolean = false
    @field:QueryValue(defaultValue = "false") var startRiskAssessment: Boolean = false
    @field:QueryValue @field:io.micronaut.core.annotation.Nullable var onboardingMode: AccountOnboardingMode? = null
    @field:QueryValue @field:io.micronaut.core.annotation.Nullable var sendWelcomeEmail: Boolean? = null
    @field:QueryValue @field:io.micronaut.core.annotation.Nullable var riskAssessmentUseCase: String? = null
    @field:QueryValue @field:io.micronaut.core.annotation.Nullable var riskAssessmentDeadlineDays: Int? = null
    @field:QueryValue @field:io.micronaut.core.annotation.Nullable var questionnaireExpiryDays: Int? = null

    fun request(rows: List<BulkUserMappingEntry>) = BulkUserMappingRequest(rows, dryRun = dryRun,
        startRiskAssessment = startRiskAssessment, onboardingMode = onboardingMode, sendWelcomeEmail = sendWelcomeEmail,
        riskAssessmentUseCase = riskAssessmentUseCase, riskAssessmentDeadlineDays = riskAssessmentDeadlineDays,
        questionnaireExpiryDays = questionnaireExpiryDays)
}

data class ParsedUserMappings(val mappings: List<BulkUserMappingEntry>, val errors: List<String>, val skipped: Int = errors.size)
