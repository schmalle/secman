package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
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
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    var answerType: AnswerType? = null,

    @Column(name = "comment", columnDefinition = "TEXT")
    var comment: String? = null,

    @Column(name = "respondent_email")
    @Email
    var respondentEmail: String? = null,

    /**
     * Provenance of this Response. Feature 088. Default MANUAL keeps existing
     * rows correct. AI_GENERATED rows are written by AiSuggestionJobService;
     * editing one flips the value to AI_EDITED via ResponseController.
     */
    @Column(name = "source", length = 16, nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    var source: ResponseSource = ResponseSource.MANUAL,

    /**
     * Link to the AiAnswerSuggestion that produced this Response, if any.
     * Null for MANUAL rows. Kept as a raw FK (not @ManyToOne) so we don't drag
     * the suggestion into every response payload.
     */
    @Column(name = "ai_suggestion_id")
    var aiSuggestionId: Long? = null,

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