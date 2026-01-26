package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

/**
 * Entity for storing application-wide settings.
 * Feature: 068-requirements-alignment-process
 *
 * Stores configuration settings that can be managed by ADMIN users,
 * such as the application base URL used in email notifications.
 */
@Entity
@Table(name = "app_settings")
@Serdeable
data class AppSettings(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /**
     * Base URL of the application frontend.
     * Used for generating links in email notifications.
     * Example: https://secman.example.com
     */
    @Column(nullable = false, length = 500, name = "base_url")
    var baseUrl: String = "http://localhost:4321",

    /**
     * Username of ADMIN who last updated these settings
     */
    @Column(nullable = true, length = 100, name = "updated_by")
    var updatedBy: String? = null,

    @CreationTimestamp
    @Column(nullable = false, updatable = false, name = "created_at")
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(nullable = false, name = "updated_at")
    var updatedAt: LocalDateTime? = null
) {
    companion object {
        /**
         * Create default settings
         */
        fun createDefault(): AppSettings {
            return AppSettings(
                baseUrl = "http://localhost:4321",
                updatedBy = "system"
            )
        }
    }

    /**
     * Validate the base URL format
     */
    fun validateBaseUrl(): List<String> {
        val errors = mutableListOf<String>()

        if (baseUrl.isBlank()) {
            errors.add("Base URL cannot be empty")
        } else {
            // Check for valid URL format (must start with http:// or https://)
            if (!baseUrl.matches(Regex("^https?://[a-zA-Z0-9][-a-zA-Z0-9.]*[a-zA-Z0-9](:[0-9]+)?(/.*)?$"))) {
                errors.add("Invalid base URL format. Must start with http:// or https://")
            }
            // Check for trailing slash and warn (we'll strip it)
            if (baseUrl.endsWith("/")) {
                // Not an error, but we'll normalize it
            }
        }

        return errors
    }

    /**
     * Get normalized base URL (without trailing slash)
     */
    fun getNormalizedBaseUrl(): String {
        return baseUrl.trimEnd('/')
    }
}
