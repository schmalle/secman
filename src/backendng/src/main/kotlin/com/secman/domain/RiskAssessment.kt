package com.secman.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import io.micronaut.serde.annotation.Serdeable
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import jakarta.persistence.*
import jakarta.validation.constraints.NotNull
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(
    name = "risk_assessment",
    indexes = [
        Index(name = "idx_risk_assessment_basis", columnList = "assessment_basis_type, assessment_basis_id"),
        Index(name = "idx_risk_assessment_assessor", columnList = "assessor_id"),
        Index(name = "idx_risk_assessment_requestor", columnList = "requestor_id"),
        Index(name = "idx_risk_assessment_status", columnList = "status"),
        Index(name = "idx_risk_assessment_dates", columnList = "start_date, end_date")
    ]
)
@Serdeable
data class RiskAssessment(
    @Id
    // IDENTITY, not the AUTO default: this table's id column is AUTO_INCREMENT, but on
    // MariaDB Hibernate maps AUTO to a native sequence (<table>_seq) that starts at 1 and
    // knows nothing about rows the database numbered. On any long-lived schema that hands
    // out ids already taken -> "Duplicate entry 'n' for key 'PRIMARY'".
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "start_date", nullable = false)
    @NotNull
    var startDate: LocalDate,

    @Column(name = "end_date", nullable = false)
    @NotNull
    var endDate: LocalDate,

    @Column(name = "status", length = 50)
    var status: String = "STARTED",

    @Column(name = "answer_revision", nullable = false)
    var answerRevision: Long = 0,

    @Column(name = "assignment_version", nullable = false)
    var assignmentVersion: Long = 0,

    @Column(name = "authorship_complete", nullable = false)
    var authorshipComplete: Boolean = true,

    @Column(name = "notes", length = 1024)
    var notes: String? = null,

    @Column(name = "is_release_locked")
    var isReleaseLocked: Boolean = false,

    @Column(name = "content_snapshot_taken")
    var contentSnapshotTaken: Boolean = false,

    @Column(name = "release_locked_at")
    var releaseLockedAt: LocalDateTime? = null,

    @Column(name = "outstanding_reminder_sent_at")
    var outstandingReminderSentAt: LocalDateTime? = null,

    // Unified approach: the basis is a demand, asset, or AWS account.
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(name = "assessment_basis_type", nullable = false)
    @NotNull
    var assessmentBasisType: AssessmentBasisType,

    @Column(name = "assessment_basis_id", nullable = false)
    @NotNull
    var assessmentBasisId: Long,

    // Legacy fields for backward compatibility during migration
    @ManyToOne
    @JoinColumn(name = "demand_id")
    @Deprecated("Use assessmentBasisType and assessmentBasisId instead. This field is kept for migration compatibility.")
    var demand: Demand? = null,

    @ManyToOne
    @JoinColumn(name = "asset_id")
    @Deprecated("Use assessmentBasisType and assessmentBasisId instead. This field is kept for migration compatibility.")
    var asset: Asset? = null,

    @ManyToOne
    @JoinColumn(name = "aws_account_id")
    var awsAccount: AwsAccount? = null,

    @ManyToOne
    @JoinColumn(name = "assessor_id", nullable = false)
    @NotNull
    var assessor: User,

    @ManyToOne
    @JoinColumn(name = "requestor_id", nullable = false)
    @NotNull
    var requestor: User,

    @ManyToOne
    @JoinColumn(name = "respondent_id")
    var respondent: User? = null,

    @ManyToOne
    @JoinColumn(name = "release_id")
    @JsonIgnore
    var lockedRelease: Release? = null,

    @ManyToMany
    @JoinTable(
        name = "risk_assessment_usecase",
        joinColumns = [JoinColumn(name = "risk_assessment_id")],
        inverseJoinColumns = [JoinColumn(name = "usecase_id")]
    )
    var useCases: MutableSet<UseCase> = mutableSetOf(),

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null
) {
    // Primary constructor for demand-based risk assessments
    constructor(
        startDate: LocalDate,
        endDate: LocalDate,
        demand: Demand,
        assessor: User,
        requestor: User
    ) : this(
        startDate = startDate,
        endDate = endDate,
        assessmentBasisType = AssessmentBasisType.DEMAND,
        assessmentBasisId = demand.id ?: throw IllegalArgumentException("Demand must have an ID"),
        assessor = assessor,
        requestor = requestor,
        status = "STARTED",
        demand = demand,
        asset = null
    )

    // Constructor for asset-based risk assessments
    constructor(
        startDate: LocalDate,
        endDate: LocalDate,
        asset: Asset,
        assessor: User,
        requestor: User
    ) : this(
        startDate = startDate,
        endDate = endDate,
        assessmentBasisType = AssessmentBasisType.ASSET,
        assessmentBasisId = asset.id ?: throw IllegalArgumentException("Asset must have an ID"),
        assessor = assessor,
        requestor = requestor,
        status = "STARTED",
        demand = null,
        asset = asset
    )

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
     * Gets the demand associated with this risk assessment if basis type is DEMAND
     */
    @Suppress("DEPRECATION")
    fun getDemandBasis(): Demand? {
        return when (assessmentBasisType) {
            AssessmentBasisType.DEMAND -> demand ?: throw IllegalStateException("Demand not loaded for DEMAND basis type")
            AssessmentBasisType.ASSET, AssessmentBasisType.AWS_ACCOUNT -> null
        }
    }

    /**
     * Gets the asset associated with this risk assessment if basis type is ASSET
     */
    @Suppress("DEPRECATION")
    fun getAssetBasis(): Asset? {
        return when (assessmentBasisType) {
            AssessmentBasisType.ASSET -> asset ?: throw IllegalStateException("Asset not loaded for ASSET basis type")
            AssessmentBasisType.DEMAND, AssessmentBasisType.AWS_ACCOUNT -> null
        }
    }

    fun getAwsAccountBasis(): AwsAccount? = when (assessmentBasisType) {
        AssessmentBasisType.AWS_ACCOUNT -> awsAccount
            ?: throw IllegalStateException("AWS account not loaded for AWS_ACCOUNT basis type")
        AssessmentBasisType.DEMAND, AssessmentBasisType.ASSET -> null
    }

    /**
     * Gets the actual asset for this risk assessment, considering both demand and direct asset basis
     */
    fun getAssociatedAsset(): Asset? {
        return when (assessmentBasisType) {
            AssessmentBasisType.DEMAND -> {
                val demandBasis = getDemandBasis()
                when (demandBasis?.demandType) {
                    DemandType.CHANGE -> demandBasis.existingAsset
                    DemandType.CREATE_NEW -> null // New assets don't exist yet
                    null -> null
                }
            }
            AssessmentBasisType.ASSET -> getAssetBasis()
            AssessmentBasisType.AWS_ACCOUNT -> null
        }
    }

    /**
     * Gets the asset name for display purposes
     */
    fun getAssetName(): String {
        return when (assessmentBasisType) {
            AssessmentBasisType.DEMAND -> getDemandBasis()?.getAssetName() ?: "Unknown Asset"
            AssessmentBasisType.ASSET -> getAssetBasis()?.name ?: "Unknown Asset"
            AssessmentBasisType.AWS_ACCOUNT -> getAwsAccountBasis()?.name
                ?: getAwsAccountBasis()?.awsAccountId
                ?: "Unknown AWS Account"
        }
    }

    /**
     * Gets the asset type for display purposes
     */
    fun getAssetType(): String {
        return when (assessmentBasisType) {
            AssessmentBasisType.DEMAND -> getDemandBasis()?.getAssetType() ?: "Unknown"
            AssessmentBasisType.ASSET -> getAssetBasis()?.type ?: "Unknown"
            AssessmentBasisType.AWS_ACCOUNT -> "AWS_ACCOUNT"
        }
    }

    /**
     * Gets the description of what is being assessed
     */
    fun getBasisDescription(): String {
        return when (assessmentBasisType) {
            AssessmentBasisType.DEMAND -> getDemandBasis()?.title ?: "Unknown Demand"
            AssessmentBasisType.ASSET -> getAssetBasis()?.name ?: "Unknown Asset"
            AssessmentBasisType.AWS_ACCOUNT -> getAwsAccountBasis()?.let { it.name ?: it.awsAccountId }
                ?: "Unknown AWS Account"
        }
    }

    /**
     * Gets the owner for display purposes
     */
    fun getAssetOwner(): String {
        return when (assessmentBasisType) {
            AssessmentBasisType.DEMAND -> getDemandBasis()?.getAssetOwner() ?: "Unknown"
            AssessmentBasisType.ASSET -> getAssetBasis()?.owner ?: "Unknown"
            AssessmentBasisType.AWS_ACCOUNT -> respondent?.email ?: "Unknown"
        }
    }

    /**
     * Validates that the assessment basis configuration is consistent
     */
    @Suppress("DEPRECATION")
    fun validateBasisConsistency(): Boolean {
        return when (assessmentBasisType) {
            AssessmentBasisType.DEMAND -> {
                demand != null &&
                demand?.id == assessmentBasisId &&
                asset == null && awsAccount == null
            }
            AssessmentBasisType.ASSET -> {
                asset != null &&
                asset?.id == assessmentBasisId &&
                demand == null && awsAccount == null
            }
            AssessmentBasisType.AWS_ACCOUNT -> {
                awsAccount != null &&
                awsAccount?.id == assessmentBasisId &&
                demand == null && asset == null
            }
        }
    }

    /**
     * Gets validation errors for the assessment basis configuration
     */
    @Suppress("DEPRECATION")
    fun getBasisValidationErrors(): List<String> {
        val errors = mutableListOf<String>()

        when (assessmentBasisType) {
            AssessmentBasisType.DEMAND -> {
                if (demand == null) {
                    errors.add("Demand entity must be loaded for DEMAND basis type")
                }
                if (demand?.id != assessmentBasisId) {
                    errors.add("Demand ID (${demand?.id}) must match assessmentBasisId ($assessmentBasisId)")
                }
                if (asset != null) {
                    errors.add("Asset must be null for DEMAND basis type")
                }
                if (awsAccount != null) errors.add("AWS account must be null for DEMAND basis type")
            }
            AssessmentBasisType.ASSET -> {
                if (asset == null) {
                    errors.add("Asset entity must be loaded for ASSET basis type")
                }
                if (asset?.id != assessmentBasisId) {
                    errors.add("Asset ID (${asset?.id}) must match assessmentBasisId ($assessmentBasisId)")
                }
                if (demand != null) {
                    errors.add("Demand must be null for ASSET basis type")
                }
                if (awsAccount != null) errors.add("AWS account must be null for ASSET basis type")
            }
            AssessmentBasisType.AWS_ACCOUNT -> {
                if (awsAccount == null) errors.add("AWS account entity must be loaded for AWS_ACCOUNT basis type")
                if (awsAccount?.id != assessmentBasisId) {
                    errors.add("AWS account ID (${awsAccount?.id}) must match assessmentBasisId ($assessmentBasisId)")
                }
                if (demand != null) errors.add("Demand must be null for AWS_ACCOUNT basis type")
                if (asset != null) errors.add("Asset must be null for AWS_ACCOUNT basis type")
            }
        }

        return errors
    }

    override fun toString(): String {
        return "RiskAssessment(id=$id, startDate=$startDate, endDate=$endDate, status='$status', basisType=$assessmentBasisType, basis=${getBasisDescription()})"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RiskAssessment) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}
