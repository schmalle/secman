package com.secman.dto

import com.secman.domain.AccountOnboardingMode
import io.micronaut.serde.annotation.Serdeable

@Serdeable
data class AccountOnboardingSettingsDto(
    val mode: AccountOnboardingMode = AccountOnboardingMode.WELCOME_ONLY,
    val riskAssessmentUseCase: String? = null,
    val riskAssessmentDeadlineDays: Int = 7,
    val welcomeSubject: String = "Welcome - your AWS account {awsAccountId} is registered in SecMan",
    val welcomeBodyHtml: String = "<p>Welcome to SecMan.</p><p>Your AWS account {awsAccountId} is now registered. Visit {portalUrl} to view your account.</p>"
)
