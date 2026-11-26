package com.secman.service.cyberdef

import com.secman.domain.CyberdefSeverity
import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton
import java.time.Duration

/** Operator settings under `secman.cyberdef.*`; see docs/CYBERDEF_TICKETS.md §Configuration. */
@Singleton
class CyberdefTicketSettings(
    @Value("\${secman.cyberdef.sla.critical-days:2}") private val criticalDays: Long,
    @Value("\${secman.cyberdef.sla.high-days:7}") private val highDays: Long,
    @Value("\${secman.cyberdef.sla.medium-days:30}") private val mediumDays: Long,
    @Value("\${secman.cyberdef.sla.low-days:90}") private val lowDays: Long,
    @Value("\${secman.cyberdef.sla.info-days:0}") private val infoDays: Long,
    @Value("\${secman.cyberdef.mail.include-summary:false}") val mailIncludesSummary: Boolean,
    @Value("\${secman.cyberdef.mail.notify-submitter:false}") val notifySubmitter: Boolean,
    @Value("\${secman.cyberdef.max-recipients:50}") val maxRecipients: Int,
    @Value("\${secman.cyberdef.max-tickets-per-hour:600}") val maxTicketsPerHour: Long,
    @Value("\${app.frontend.base-url:http://localhost:4321}") private val frontendBaseUrl: String
) {
    /** Due window per severity; null means no deadline (INFO by default). */
    fun slaFor(severity: CyberdefSeverity): Duration? {
        val days = when (severity) {
            CyberdefSeverity.CRITICAL -> criticalDays
            CyberdefSeverity.HIGH -> highDays
            CyberdefSeverity.MEDIUM -> mediumDays
            CyberdefSeverity.LOW -> lowDays
            CyberdefSeverity.INFO -> infoDays
        }
        return if (days > 0) Duration.ofDays(days) else null
    }

    fun ticketUrl(ticketId: Long): String = frontendBaseUrl.trimEnd('/') + "/security-tickets/" + ticketId
}
