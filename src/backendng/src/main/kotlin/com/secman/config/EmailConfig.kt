package com.secman.config

import io.micronaut.context.annotation.ConfigurationProperties
import jakarta.inject.Singleton

/**
 * Email configuration for notification system
 * SMTP credentials can be provided via environment variables or database table
 * Feature 035: Outdated Asset Notification System
 */
@Singleton
@ConfigurationProperties("secman.email")
class EmailConfig {
    // SMTP server configuration (from application.yml or env vars)
    var smtpHost: String = "smtp.example.com"
    var smtpPort: Int = 587
    var username: String = "noreply@secman.example.com"
    var password: String = "changeme"
    var fromAddress: String = "noreply@secman.example.com"
    var fromName: String = "Security Management System"
    var enableTls: Boolean = true

    /**
     * Retry configuration for email delivery
     */
    var retry: RetryConfig = RetryConfig()

    /**
     * Performance settings for email delivery
     */
    var performance: PerformanceConfig = PerformanceConfig()

    /**
     * Notification-specific settings
     */
    var notifications: NotificationConfig = NotificationConfig()

    class RetryConfig {
        var maxRetries: Int = 3
        var delayBetweenRetriesMs: Long = 1000 // 1 second
    }

    class PerformanceConfig {
        var connectionTimeoutMs: Long = 10000 // 10 seconds
        var readTimeoutMs: Long = 30000 // 30 seconds
    }

    class NotificationConfig {
        var batchSize: Int = 50
        var asyncProcessing: Boolean = true
    }
}
