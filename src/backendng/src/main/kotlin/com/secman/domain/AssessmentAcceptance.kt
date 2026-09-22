package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import java.time.LocalDateTime

/** Durable assessment authorization and review history, identified independently of mutable user names. */
@Entity
@Table(name = "assessment_acceptance", indexes = [Index(name = "idx_assessment_acceptance_assessment", columnList = "assessment_id")])
@Serdeable
data class AssessmentAcceptance(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    var assessmentId: Long,
    var reviewerUserId: Long,
    var answerRevision: Long,
    @Column(columnDefinition = "TEXT") var rationale: String,
    var invalidated: Boolean = false,
    var createdAt: LocalDateTime = LocalDateTime.now()
)
