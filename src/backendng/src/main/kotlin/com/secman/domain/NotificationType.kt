package com.secman.domain

/**
 * Enum representing different types of notifications that can be sent
 * Feature 035: Outdated Asset Notification System
 */
enum class NotificationType {
    /**
     * First-level reminder for outdated assets (professional tone)
     */
    OUTDATED_LEVEL1,

    /**
     * Second-level reminder for outdated assets (urgent tone, sent after 7 days)
     */
    OUTDATED_LEVEL2,

    /**
     * Notification about new vulnerabilities discovered on user's assets
     */
    NEW_VULNERABILITY,

    /**
     * Per-recipient overdue digest from `notify-users-overdue` (workgroup paths only).
     * Rendered by [com.secman.service.WorkgroupOverdueNotificationService], not by the
     * outdated-asset templates; its log rows also back the once-per-UTC-day send guard.
     */
    OVERDUE_WORKGROUP_DIGEST
}
