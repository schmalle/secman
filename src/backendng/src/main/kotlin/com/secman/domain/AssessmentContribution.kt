package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import java.time.LocalDateTime

/** Durable assessment authorization and review history, identified independently of mutable user names. */
@Entity
@Table(name = "assessment_contribution", indexes = [Index(name = "idx_assessment_contribution_assessment", columnList = "assessment_id")])
@Serdeable
data class AssessmentContribution(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    var assessmentId: Long,
    var requirementId: Long,
    var actorUserId: Long? = null,
    var actorEmail: String,
    var initiatingUserId: Long? = null,
    var assignmentId: Long? = null,
    var apiKeyId: Long? = null,
    var source: String,
    var revision: Long,
    var jobId: Long? = null,
    var createdAt: LocalDateTime = LocalDateTime.now()
)
