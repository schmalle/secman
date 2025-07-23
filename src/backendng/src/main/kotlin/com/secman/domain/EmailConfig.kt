package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.time.LocalDateTime

@Entity
@Table(name = "email_config")
@Serdeable
data class EmailConfig(
    @Id
    @GeneratedValue
    var id: Long? = null,

    @Column(name = "smtp_host", nullable = false)
    @NotBlank
    var smtpHost: String,

    @Column(name = "smtp_port", nullable = false)
    @Min(1) @Max(65535)
    var smtpPort: Int = 587,

    @Column(name = "smtp_username")
    var smtpUsername: String? = null,

    @Column(name = "smtp_password", length = 512)
    var smtpPassword: String? = null,

    @Column(name = "smtp_tls", nullable = false)
    var smtpTls: Boolean = true,

    @Column(name = "smtp_ssl", nullable = false)
    var smtpSsl: Boolean = false,

    @Column(name = "from_email", nullable = false)
    @NotBlank
    @Email
    var fromEmail: String,

    @Column(name = "from_name", nullable = false)
    @NotBlank
    var fromName: String,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null
) {
    
    companion object {
        const val PASSWORD_MASK = "***HIDDEN***"
    }

    @PrePersist
    fun onCreate() {
        val now = LocalDateTime.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = LocalDateTime.now()
    }

    /**
     * Check if this configuration has authentication credentials
     */
    fun hasAuthentication(): Boolean {
        return !smtpUsername.isNullOrBlank() && !smtpPassword.isNullOrBlank()
    }

    /**
     * Create a copy with password masked for API responses
     */
    fun toSafeResponse(): EmailConfig {
        return this.copy(
            smtpPassword = if (!smtpPassword.isNullOrBlank()) PASSWORD_MASK else null
        )
    }

    /**
     * Check if password update is needed (not the masked value)
     */
    fun shouldUpdatePassword(newPassword: String?): Boolean {
        return newPassword != null && newPassword != PASSWORD_MASK
    }

    override fun toString(): String {
        return "EmailConfig(id=$id, smtpHost='$smtpHost', smtpPort=$smtpPort, fromEmail='$fromEmail', isActive=$isActive)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmailConfig) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}