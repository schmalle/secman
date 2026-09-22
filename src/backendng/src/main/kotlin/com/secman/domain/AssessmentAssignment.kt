package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import java.time.LocalDateTime

/** Durable assessment authorization and review history, identified independently of mutable user names. */
@Entity
@Table(name = "assessment_assignment", indexes = [Index(name = "idx_assessment_assignment_assessment", columnList = "assessment_id")])
@Serdeable
data class AssessmentAssignment(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    var assessmentId: Long,
    var userId: Long? = null,
    var email: String,
    var role: String,
    @Column(columnDefinition = "TEXT") var requirementIds: String = "",
    var revoked: Boolean = false,
    var submitted: Boolean = false,
    var version: Long = 1,
    var reminderSentAt: LocalDateTime? = null,
    var createdAt: LocalDateTime = LocalDateTime.now()
)
