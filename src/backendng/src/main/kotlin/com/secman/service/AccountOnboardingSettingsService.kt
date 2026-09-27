package com.secman.service

import com.secman.domain.AccountOnboardingMode
import com.secman.dto.AccountOnboardingSettingsDto
import com.secman.repository.AccountOnboardingSettingsRepository
import com.secman.repository.UseCaseRepository
import com.secman.util.EmailHtmlSanitizer
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import org.jsoup.Jsoup

@Singleton
open class AccountOnboardingSettingsService(
    private val repository: AccountOnboardingSettingsRepository,
    private val useCases: UseCaseRepository,
    private val renderer: EmailTemplateRenderer
) {
    @Transactional(readOnly = true)
    open fun get(): AccountOnboardingSettingsDto {
        val row = repository.findBySingletonKey(1).orElse(null) ?: return AccountOnboardingSettingsDto()
        return AccountOnboardingSettingsDto(row.mode, row.riskAssessmentUseCase,
            row.riskAssessmentDeadlineDays, row.welcomeSubject, row.welcomeBodyHtml)
    }

    @Transactional
    open fun save(request: AccountOnboardingSettingsDto): AccountOnboardingSettingsDto {
        require(request.mode in setOf(AccountOnboardingMode.WELCOME_ONLY, AccountOnboardingMode.DIRECT)) {
            "mode must be WELCOME_ONLY or DIRECT"
        }
        require(request.riskAssessmentDeadlineDays in 1..AwsAccountRiskAssessmentService.MAX_DEADLINE_DAYS) {
            "Invalid assessment deadline"
        }
        val useCase = request.riskAssessmentUseCase?.trim()?.takeIf { it.isNotEmpty() }
        require(useCase == null || useCase.length <= 255) { "Use case is too long" }
        if (request.mode == AccountOnboardingMode.DIRECT) {
            require(useCase != null && useCases.findByNameIgnoreCase(useCase).isPresent) { "Choose an existing use case" }
        }
        require(request.welcomeSubject.isNotBlank() && request.welcomeSubject.length <= 255 &&
            request.welcomeSubject.none { it == '\r' || it == '\n' }) { "A single-line subject of at most 255 characters is required" }
        require(request.welcomeBodyHtml.length <= 50000) { "Welcome body exceeds 50000 characters" }
        val html = EmailHtmlSanitizer.sanitize(request.welcomeBodyHtml)
        require(Jsoup.parse(html).text().isNotBlank()) { "Welcome body is required" }
        val existing = repository.findBySingletonKey(1).orElse(null)
        val row = existing ?: com.secman.domain.AccountOnboardingSettings()
        row.mode = request.mode
        row.riskAssessmentUseCase = useCase
        row.riskAssessmentDeadlineDays = request.riskAssessmentDeadlineDays
        row.welcomeSubject = request.welcomeSubject.trim()
        row.welcomeBodyHtml = html
        if (existing == null) repository.save(row) else repository.update(row)
        return AccountOnboardingSettingsDto(row.mode, useCase, row.riskAssessmentDeadlineDays, row.welcomeSubject, html)
    }

    data class WelcomeMail(val subject: String, val text: String, val html: String)

    fun renderWelcome(values: Map<String, String>, simulated: Boolean = false): WelcomeMail {
        val config = get()
        // Literal placeholder substitution only; never evaluate editor content as a template program.
        val subject = renderer.render(config.welcomeSubject, values, false).replace(Regex("[\r\n]"), " ")
        val body = EmailHtmlSanitizer.sanitize(renderer.render(config.welcomeBodyHtml, values, true))
        val banner = if (simulated) "<p><strong>SIMULATION</strong> — requested by ${renderer.escapeHtml(values["simulatedBy"].orEmpty())}</p>" else ""
        val html = banner + body
        return WelcomeMail(if (simulated) "[SIMULATION] $subject" else subject, Jsoup.parse(html).text(), html)
    }
}
