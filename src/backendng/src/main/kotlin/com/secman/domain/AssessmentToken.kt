package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime
import java.util.*

@Entity
@Table(name = "assessment_token")
@Serdeable
data class AssessmentToken(
    @Id
    @GeneratedValue
    var id: Long? = null,

    @Column(name = "token", nullable = false, unique = true)
    @NotBlank
    var token: String,

    @Column(name = "email", nullable = false)
    @Email
    @NotBlank
    var email: String,

    @Column(name = "expires_at", nullable = false)
    @NotNull
    var expiresAt: LocalDateTime,

    @Column(name = "is_used")
    var isUsed: Boolean = false,

    @Column(name = "used_at")
    var usedAt: LocalDateTime? = null,

    @ManyToOne
    @JoinColumn(name = "risk_assessment_id", nullable = false)
    @NotNull
    var riskAssessment: RiskAssessment,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null
) {

    companion object {
        /**
         * Generate a secure random token string
         */
        fun generateToken(): String {
            return UUID.randomUUID().toString().replace("-", "")
        }

        /**
         * Create a new assessment token with 30-day expiration
         */
        fun create(email: String, riskAssessment: RiskAssessment): AssessmentToken {
            return AssessmentToken(
                token = generateToken(),
                email = email,
                expiresAt = LocalDateTime.now().plusDays(30),
                riskAssessment = riskAssessment
            )
        }
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
     * Check if token is valid (not expired and not used)
     */
    fun isValid(): Boolean {
        return !isUsed && LocalDateTime.now().isBefore(expiresAt)
    }

    /**
     * Mark token as used
     */
    fun markAsUsed() {
        isUsed = true
        usedAt = LocalDateTime.now()
    }

    override fun toString(): String {
        return "AssessmentToken(id=$id, email='$email', isUsed=$isUsed, expiresAt=$expiresAt)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AssessmentToken) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}