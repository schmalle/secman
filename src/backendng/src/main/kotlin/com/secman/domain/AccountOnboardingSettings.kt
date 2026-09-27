package com.secman.domain

import jakarta.persistence.*

@Entity
@Table(name = "account_onboarding_settings")
class AccountOnboardingSettings(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(nullable = false, unique = true) var singletonKey: Int = 1,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    var mode: AccountOnboardingMode = AccountOnboardingMode.WELCOME_ONLY,
    var riskAssessmentUseCase: String? = null,
    @Column(nullable = false) var riskAssessmentDeadlineDays: Int = 7,
    @Column(nullable = false, length = 255) var welcomeSubject: String = "Welcome to SecMan",
    @Column(nullable = false, columnDefinition = "TEXT") var welcomeBodyHtml: String = "<p>Your AWS account is now registered in SecMan.</p>"
)
