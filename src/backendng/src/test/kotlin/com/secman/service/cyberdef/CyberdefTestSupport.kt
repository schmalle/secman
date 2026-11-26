package com.secman.service.cyberdef

/** Builds [CyberdefTicketSettings] with the production defaults for unit tests. */
internal fun testSettings(
    maxRecipients: Int = 50,
    includeSummary: Boolean = false,
    notifySubmitter: Boolean = false,
    maxPerHour: Long = 600
) = CyberdefTicketSettings(
    criticalDays = 2, highDays = 7, mediumDays = 30, lowDays = 90, infoDays = 0,
    mailIncludesSummary = includeSummary, notifySubmitter = notifySubmitter,
    maxRecipients = maxRecipients, maxTicketsPerHour = maxPerHour,
    frontendBaseUrl = "https://secman.example.test/"
)

internal val defender = CyberdefTicketActor(1, "soc", "soc@example.test", setOf("CYBERDEF"))
internal val owner = CyberdefTicketActor(2, "owner", "owner@example.test", setOf("USER"))
internal val champion = CyberdefTicketActor(3, "champ", "champ@example.test", setOf("USER", "SECCHAMPION"))
