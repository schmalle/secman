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
    NEW_VULNERABILITY
}
