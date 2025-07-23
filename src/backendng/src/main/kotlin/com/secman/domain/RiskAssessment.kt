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
    @JoinColumn(name = "asset_id", nullable = false)
    @NotNull
    var asset: Asset,

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
    constructor(
        startDate: LocalDate,
        endDate: LocalDate,
        asset: Asset,
        assessor: User,
        requestor: User
    ) : this(
        startDate = startDate,
        endDate = endDate,
        asset = asset,
        assessor = assessor,
        requestor = requestor,
        status = "STARTED"
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

    override fun toString(): String {
        return "RiskAssessment(id=$id, startDate=$startDate, endDate=$endDate, status='$status', asset=${asset.name})"
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