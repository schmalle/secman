package com.secman.domain

import com.secman.util.EncryptedStringConverter

import jakarta.persistence.*
import java.time.Instant

/** Main SecMan's invitation record. Portal-facing data is always projected into a separate DTO. */
@Entity
@Table(
    name = "supplier_assessment_invitation",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_supplier_invite_external", columnNames = ["external_id"]),
        UniqueConstraint(name = "uk_supplier_invite_generation", columnNames = ["assessment_id", "generation"])
    ]
)
class SupplierAssessmentInvitation(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(nullable = false, updatable = false, length = 36)
    val externalId: String,
    @Column(nullable = false, updatable = false)
    val assessmentId: Long,
    @Column(nullable = false, updatable = false)
    val releaseId: Long,
    @Column(nullable = false, updatable = false)
    val generation: Long,
    @Column(nullable = false, updatable = false)
    val recipientEmail: String,
    @Column(updatable = false)
    val recipientUserId: Long?,
    @Column(nullable = false, updatable = false)
    val requesterLabel: String,
    @Column(nullable = false, updatable = false, length = 1000)
    val serviceScope: String,
    @Column(nullable = false, updatable = false, columnDefinition = "LONGTEXT")
    val snapshotJson: String,
    @Column(nullable = false, updatable = false, length = 64)
    val snapshotHash: String,
    @Column(nullable = false, updatable = false)
    val createdAt: Instant,
    @Column(nullable = false, updatable = false)
    val deadline: Instant,
    var revokedAt: Instant? = null,
    @Column(length = 36)
    var submissionId: String? = null,
    @Column(length = 64)
    var submissionDigest: String? = null,
    var submittedAt: Instant? = null,
    var importedAt: Instant? = null,
    var importedRevision: Long? = null,
    @Column(length = 36, updatable = false)
    val previousExternalId: String? = null,
    @Column(nullable = false, updatable = false)
    val retainPreviousDraft: Boolean = false,
    @Column(length = 24)
    var deliveryState: String? = null,
    var deliveryDueAt: Instant? = null,
    var deliveryExpiresAt: Instant? = null,
    @Column(length = 36)
    var deliveryLease: String? = null,
    @Convert(converter = EncryptedStringConverter::class)
    @Column(columnDefinition = "TEXT")
    var deliveryCredential: String? = null,
    @Column(length = 60)
    var deliveryVerifier: String? = null,
    var pollDueAt: Instant? = null,
    @Column(length = 36)
    var pollLease: String? = null,
    @Column(length = 24)
    var pollState: String? = null,
    var pollCheckedAt: Instant? = null,
    var pollObservedAt: Instant? = null,
    @Column(length = 64)
    var pollObservedDigest: String? = null,
    @Column(nullable = false)
    var pollIntegrityConflict: Boolean = false,
    var incidentHeldAt: Instant? = null
)
