package com.secman.config

import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.serde.annotation.Serdeable

/**
 * Configuration for admin/secchampion notifications about new pending exception requests.
 *
 * Replaces the per-request email blast (one email per reviewer, per request) with a
 * pooled, scheduled digest. When a user creates 100 exception requests in an hour, each
 * reviewer receives a single consolidated digest instead of 100 emails.
 *
 * Environment variables:
 * - EXCEPTION_NOTIFICATIONS_MODE (default: "digest"; "immediate" = legacy per-request)
 * - EXCEPTION_NOTIFICATIONS_DIGEST_INTERVAL (default: "1h"; Micronaut duration, e.g. "15m", "24h")
 *
 * The interval is also referenced directly by the scheduler's @Scheduled(fixedDelay)
 * annotation via property placeholder, since annotation values must be constants.
 */
@ConfigurationProperties("secman.exception-notifications")
@Serdeable
data class ExceptionNotificationConfig(
    /**
     * Delivery mode for new-pending-request notifications:
     * - "digest"    (default): pool into a scheduled per-reviewer digest.
     * - "immediate"          : legacy behaviour — one email per reviewer, per request.
     *                          Retained as a rollback path.
     */
    var mode: String = "digest"
) {
    fun isDigestMode(): Boolean = !mode.equals("immediate", ignoreCase = true)
}
