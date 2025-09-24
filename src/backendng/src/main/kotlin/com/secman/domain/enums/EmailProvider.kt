package com.secman.domain.enums

/**
 * Supported email providers for test accounts
 */
enum class EmailProvider {
    /**
     * Gmail provider using Google's SMTP/IMAP servers
     */
    GMAIL,

    /**
     * Microsoft Outlook/Hotmail provider
     */
    OUTLOOK,

    /**
     * Yahoo Mail provider
     */
    YAHOO,

    /**
     * Custom SMTP server configuration
     */
    SMTP_CUSTOM,

    /**
     * Custom IMAP server configuration
     */
    IMAP_CUSTOM;

    /**
     * Get default SMTP settings for this provider
     */
    fun getDefaultSmtpConfig(): SmtpConfig? {
        return when (this) {
            GMAIL -> SmtpConfig(
                host = "smtp.gmail.com",
                port = 587,
                tls = true,
                ssl = false
            )
            OUTLOOK -> SmtpConfig(
                host = "smtp-mail.outlook.com",
                port = 587,
                tls = true,
                ssl = false
            )
            YAHOO -> SmtpConfig(
                host = "smtp.mail.yahoo.com",
                port = 587,
                tls = true,
                ssl = false
            )
            SMTP_CUSTOM, IMAP_CUSTOM -> null
        }
    }

    /**
     * Get default IMAP settings for this provider
     */
    fun getDefaultImapConfig(): ImapConfig? {
        return when (this) {
            GMAIL -> ImapConfig(
                host = "imap.gmail.com",
                port = 993,
                ssl = true
            )
            OUTLOOK -> ImapConfig(
                host = "outlook.office365.com",
                port = 993,
                ssl = true
            )
            YAHOO -> ImapConfig(
                host = "imap.mail.yahoo.com",
                port = 993,
                ssl = true
            )
            SMTP_CUSTOM, IMAP_CUSTOM -> null
        }
    }

    /**
     * Check if this provider requires app-specific passwords
     */
    fun requiresAppPassword(): Boolean {
        return when (this) {
            GMAIL, YAHOO -> true
            OUTLOOK -> false
            SMTP_CUSTOM, IMAP_CUSTOM -> false
        }
    }
}

/**
 * SMTP configuration data class
 */
data class SmtpConfig(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val ssl: Boolean
)

/**
 * IMAP configuration data class
 */
data class ImapConfig(
    val host: String,
    val port: Int,
    val ssl: Boolean
)