package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

@Entity
@Table(name = "response")
@Serdeable
data class Response(
    @Id
    @GeneratedValue
    var id: Long? = null,

    @Column(name = "answer_type", length = 10)
    @Enumerated(EnumType.STRING)
    var answerType: AnswerType? = null,

    @Column(name = "comment", columnDefinition = "TEXT")
    var comment: String? = null,

    @Column(name = "respondent_email")
    @Email
    var respondentEmail: String? = null,

    @ManyToOne
    @JoinColumn(name = "risk_assessment_id", nullable = false)
    @NotNull
    var riskAssessment: RiskAssessment,

    @ManyToOne
    @JoinColumn(name = "requirement_id", nullable = false)
    @NotNull
    var requirement: Requirement,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null
) {

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

    override fun toString(): String {
        return "Response(id=$id, answerType=$answerType, requirement=${requirement.id})"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Response) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}

@Serdeable
enum class AnswerType {
    YES,
    NO,
    N_A  // Not Applicable
}