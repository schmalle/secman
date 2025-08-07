package com.secman.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotNull
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "risk_assessment")
@Serdeable
data class RiskAssessment(
    @Id
    @GeneratedValue
    var id: Long? = null,

    @Column(name = "start_date", nullable = false)
    @NotNull
    var startDate: LocalDate,

    @Column(name = "end_date", nullable = false)
    @NotNull
    var endDate: LocalDate,

    @Column(name = "status", length = 50)
    var status: String = "STARTED",

    @Column(name = "notes", length = 1024)
    var notes: String? = null,

    @Column(name = "is_release_locked")
    var isReleaseLocked: Boolean = false,

    @Column(name = "content_snapshot_taken")
    var contentSnapshotTaken: Boolean = false,

    @Column(name = "release_locked_at")
    var releaseLockedAt: LocalDateTime? = null,

    @ManyToOne
    @JoinColumn(name = "demand_id", nullable = false)
    @NotNull
    var demand: Demand,

    // Deprecated: Keep for backward compatibility during migration
    @ManyToOne
    @JoinColumn(name = "asset_id")
    @Deprecated("Use demand instead. This field is kept for migration compatibility.")
    var asset: Asset? = null,

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
    // New constructor for demand-based risk assessments
    constructor(
        startDate: LocalDate,
        endDate: LocalDate,
        demand: Demand,
        assessor: User,
        requestor: User
    ) : this(
        startDate = startDate,
        endDate = endDate,
        demand = demand,
        assessor = assessor,
        requestor = requestor,
        status = "STARTED",
        asset = null // For new assessments, asset is null as it's accessed through demand
    )

    // Deprecated constructor for backward compatibility
    @Deprecated("Use constructor with Demand instead")
    constructor(
        startDate: LocalDate,
        endDate: LocalDate,
        asset: Asset,
        assessor: User,
        requestor: User
    ) : this(
        startDate = startDate,
        endDate = endDate,
        demand = throw IllegalArgumentException("Cannot create RiskAssessment without Demand. Use demand-based constructor."),
        assessor = assessor,
        requestor = requestor,
        status = "STARTED",
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
     * Gets the asset associated with this risk assessment, whether through demand or legacy asset field
     */
    fun getAssociatedAsset(): Asset? {
        return when (demand.demandType) {
            DemandType.CHANGE -> demand.existingAsset
            DemandType.CREATE_NEW -> null // New assets don't exist yet
        } ?: asset // Fallback to legacy asset field for migration compatibility
    }

    /**
     * Gets the asset name for display purposes
     */
    fun getAssetName(): String {
        return demand.getAssetName()
    }

    /**
     * Gets the asset type for display purposes
     */
    fun getAssetType(): String {
        return demand.getAssetType()
    }

    override fun toString(): String {
        return "RiskAssessment(id=$id, startDate=$startDate, endDate=$endDate, status='$status', demand=${demand.title})"
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