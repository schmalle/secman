package com.secman.service

import com.secman.domain.NotificationType
import io.micronaut.views.thymeleaf.ThymeleafViewsRenderer
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import org.thymeleaf.context.Context
import java.io.StringWriter

/**
 * Service for rendering email templates using Thymeleaf
 * Feature 035: Outdated Asset Notification System
 */
@Singleton
class EmailTemplateService(
    private val thymeleafRenderer: ThymeleafViewsRenderer<Map<String, Any>>
) {
    private val logger = LoggerFactory.getLogger(EmailTemplateService::class.java)

    /**
     * Data class for email context passed to templates
     */
    data class EmailContext(
        val recipientEmail: String,
        val recipientName: String?,
        val assets: List<AssetEmailData>,
        val notificationType: NotificationType,
        val reminderLevel: Int? = null,
        val totalCount: Int,
        val criticalCount: Int,
        val highCount: Int,
        val mediumCount: Int,
        val lowCount: Int,
        val dashboardUrl: String,
        val preferencesUrl: String? = null
    )

    /**
     * Feature 039: Added criticality field for asset classification display
     */
    data class AssetEmailData(
        val id: Long,
        val name: String,
        val type: String,
        val vulnerabilityCount: Int,
        val oldestVulnDays: Int,
        val oldestVulnId: String,
        val criticality: String // CRITICAL, HIGH, MEDIUM, LOW (Feature 039)
    )

    /**
     * Render email template to HTML string
     *
     * @param templateName Template name (without extension)
     * @param context Email context data
     * @return Rendered HTML string
     */
    fun renderHtml(templateName: String, context: EmailContext): String {
        try {
            val thymeleafContext = Context()
            thymeleafContext.setVariable("recipientEmail", context.recipientEmail)
            thymeleafContext.setVariable("recipientName", context.recipientName)
            thymeleafContext.setVariable("assets", context.assets)
            thymeleafContext.setVariable("notificationType", context.notificationType)
            thymeleafContext.setVariable("reminderLevel", context.reminderLevel)
            thymeleafContext.setVariable("totalCount", context.totalCount)
            thymeleafContext.setVariable("criticalCount", context.criticalCount)
            thymeleafContext.setVariable("highCount", context.highCount)
            thymeleafContext.setVariable("mediumCount", context.mediumCount)
            thymeleafContext.setVariable("lowCount", context.lowCount)
            thymeleafContext.setVariable("dashboardUrl", context.dashboardUrl)
            thymeleafContext.setVariable("preferencesUrl", context.preferencesUrl)

            val writer = StringWriter()
            thymeleafRenderer.render("email-templates/$templateName", emptySet(), thymeleafContext, writer)
            return writer.toString()
        } catch (e: Exception) {
            logger.error("Failed to render email template: $templateName", e)
            throw RuntimeException("Template rendering error: ${e.message}", e)
        }
    }

    /**
     * Get plain-text version of email (simple fallback)
     * TODO: Load from .txt template files for proper plain-text rendering
     */
    fun renderPlainText(context: EmailContext): String {
        val sb = StringBuilder()

        when (context.notificationType) {
            NotificationType.OUTDATED_LEVEL1 -> {
                sb.appendLine("ACTION REQUESTED: OUTDATED ASSETS DETECTED")
                sb.appendLine("=" .repeat(50))
                sb.appendLine()
                sb.appendLine("Hello ${context.recipientName ?: "Asset Owner"},")
                sb.appendLine()
                sb.appendLine("We have identified ${context.totalCount} asset(s) under your ownership")
                sb.appendLine("that have vulnerabilities exceeding the acceptable remediation timeframe.")
            }
            NotificationType.OUTDATED_LEVEL2 -> {
                sb.appendLine("⚠️ URGENT: IMMEDIATE ACTION REQUIRED - OUTDATED ASSETS")
                sb.appendLine("=" .repeat(50))
                sb.appendLine()
                sb.appendLine("Hello ${context.recipientName ?: "Asset Owner"},")
                sb.appendLine()
                sb.appendLine("This is an escalated reminder. These ${context.totalCount} assets have been")
                sb.appendLine("outdated for 7+ days without remediation.")
            }
            NotificationType.NEW_VULNERABILITY -> {
                sb.appendLine("NEW VULNERABILITIES DETECTED ON YOUR ASSETS")
                sb.appendLine("=" .repeat(50))
                sb.appendLine()
                sb.appendLine("Hello ${context.recipientName ?: "Asset Owner"},")
                sb.appendLine()
                sb.appendLine("${context.totalCount} new vulnerability(ies) detected on your assets.")
            }
        }

        sb.appendLine()
        sb.appendLine("SEVERITY BREAKDOWN")
        sb.appendLine("-" .repeat(20))
        if (context.criticalCount > 0) sb.appendLine("Critical: ${context.criticalCount}")
        if (context.highCount > 0) sb.appendLine("High: ${context.highCount}")
        if (context.mediumCount > 0) sb.appendLine("Medium: ${context.mediumCount}")
        if (context.lowCount > 0) sb.appendLine("Low: ${context.lowCount}")

        sb.appendLine()
        sb.appendLine("AFFECTED ASSETS (sorted by criticality)")
        sb.appendLine("-" .repeat(20))
        context.assets.take(10).forEach { asset ->
            // Feature 039: Display asset criticality
            val criticalityLabel = when(asset.criticality) {
                "CRITICAL" -> "[🔴 CRITICAL]"
                "HIGH" -> "[🟠 HIGH]"
                "MEDIUM" -> "[🔵 MEDIUM]"
                "LOW" -> "[⚪ LOW]"
                else -> ""
            }
            sb.appendLine("- $criticalityLabel ${asset.name} (${asset.type}) - ${asset.vulnerabilityCount} vulnerabilities, oldest: ${asset.oldestVulnDays} days (${asset.oldestVulnId})")
        }
        if (context.assets.size > 10) {
            sb.appendLine("... and ${context.assets.size - 10} more assets")
        }

        sb.appendLine()
        sb.appendLine("View full details: ${context.dashboardUrl}")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine("This is an automated notification from the Security Management System.")
        sb.appendLine("Do not reply to this email.")

        return sb.toString()
    }
}
