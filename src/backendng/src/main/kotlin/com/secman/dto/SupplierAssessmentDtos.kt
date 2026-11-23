package com.secman.dto

import com.secman.domain.AnswerType
import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

const val SUPPLIER_MAX_QUESTIONS = 200
const val SUPPLIER_MAX_COMMENT = 4000

@Serdeable
data class CreateSupplierInvitation(
    @field:NotBlank @field:Email @field:Size(max = 255) val recipientEmail: String,
    @field:NotBlank @field:Size(max = 255) val requesterLabel: String,
    @field:NotBlank @field:Size(max = 1000) val serviceScope: String,
    val deadline: Instant,
    @field:Valid @field:Size(min = 1, max = SUPPLIER_MAX_QUESTIONS)
    val questions: List<ApprovedSupplierQuestion>
)

@Serdeable
data class ApprovedSupplierQuestion(
    val requirementId: Long,
    @field:NotBlank @field:Size(max = SUPPLIER_MAX_COMMENT) val text: String
)

@Serdeable
data class SupplierQuestion(val key: String, val text: String)

/** The external projection contains only fields approved for publication. */
@Serdeable
data class SupplierQuestionnaire(
    val schemaVersion: Int,
    val invitationId: String,
    val generation: Long,
    val requesterLabel: String,
    val serviceScope: String,
    val deadline: Instant,
    val snapshotHash: String,
    val questions: List<SupplierQuestion>
)

@Serdeable
data class SupplierInvitationStatus(
    val questionnaire: SupplierQuestionnaire,
    val recipientEmail: String,
    val state: String,
    val submissionId: String?,
    val submittedAt: Instant?,
    val importedAt: Instant?,
    val importedRevision: Long?,
    val deliveryState: String?,
    val linkExpiresAt: Instant?
)

@Serdeable
data class SupplierAnswer(val questionKey: String, val answer: AnswerType, val comment: String? = null)

/** Only the outbound poller may pass this DTO to the internal import service. */
@Serdeable
data class SupplierSubmission(
    val schemaVersion: Int,
    val invitationId: String,
    val generation: Long,
    val snapshotHash: String,
    val submissionId: String,
    val submittedAt: Instant,
    val answers: List<SupplierAnswer>
)

enum class SupplierImportOutcome { IMPORTED, ALREADY_IMPORTED, HELD, CONFLICT, REJECTED }

@Serdeable
data class SupplierCollectionStatus(
    val invitationId: String,
    val serviceScope: String,
    val invitationState: String,
    val deliveryState: String?,
    val pollState: String?,
    val checkedAt: Instant?,
    val nextCheckAt: Instant?,
    val observedAt: Instant?,
    val importedAt: Instant?,
    val importedRevision: Long?,
    val integrityConflict: Boolean,
    val incidentHeldAt: Instant?,
    val canRetry: Boolean
)
