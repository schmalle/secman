package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

@Entity
@Table(name = "demand")
@Serdeable
data class Demand(
    @Id
    @GeneratedValue
    var id: Long? = null,

    @Column(nullable = false)
    @NotBlank
    @Size(max = 255)
    var title: String,

    @Column(length = 1024)
    var description: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "demand_type", nullable = false)
    @NotNull
    var demandType: DemandType,

    @ManyToOne
    @JoinColumn(name = "existing_asset_id")
    var existingAsset: Asset? = null,

    @Column(name = "new_asset_name")
    @Size(max = 255)
    var newAssetName: String? = null,

    @Column(name = "new_asset_type")
    @Size(max = 100)
    var newAssetType: String? = null,

    @Column(name = "new_asset_ip")
    @Size(max = 45)
    var newAssetIp: String? = null,

    @Column(name = "new_asset_owner")
    @Size(max = 255)
    var newAssetOwner: String? = null,

    @Column(name = "new_asset_description", length = 1024)
    var newAssetDescription: String? = null,

    @Column(name = "business_justification", length = 2048)
    var businessJustification: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var priority: Priority = Priority.MEDIUM,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: DemandStatus = DemandStatus.PENDING,

    @ManyToOne
    @JoinColumn(name = "requestor_id", nullable = false)
    @NotNull
    var requestor: User,

    @ManyToOne
    @JoinColumn(name = "approver_id")
    var approver: User? = null,

    @Column(name = "requested_date", nullable = false)
    var requestedDate: LocalDateTime = LocalDateTime.now(),

    @Column(name = "approved_date")
    var approvedDate: LocalDateTime? = null,

    @Column(name = "rejection_reason", length = 1024)
    var rejectionReason: String? = null,

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
        if (requestedDate == LocalDateTime.MIN) {
            requestedDate = now
        }
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = LocalDateTime.now()
    }

    /**
     * Validates that the demand has proper asset information based on type
     */
    fun validateAssetInformation(): Boolean {
        return when (demandType) {
            DemandType.CHANGE -> existingAsset != null
            DemandType.CREATE_NEW -> {
                !newAssetName.isNullOrBlank() &&
                !newAssetType.isNullOrBlank() &&
                !newAssetOwner.isNullOrBlank()
            }
        }
    }

    /**
     * Gets the asset name regardless of demand type
     */
    fun getAssetName(): String {
        return when (demandType) {
            DemandType.CHANGE -> existingAsset?.name ?: "Unknown Asset"
            DemandType.CREATE_NEW -> newAssetName ?: "Unnamed Asset"
        }
    }

    /**
     * Gets the asset type regardless of demand type
     */
    fun getAssetType(): String {
        return when (demandType) {
            DemandType.CHANGE -> existingAsset?.type ?: "Unknown"
            DemandType.CREATE_NEW -> newAssetType ?: "Unknown"
        }
    }

    /**
     * Gets the asset owner regardless of demand type
     */
    fun getAssetOwner(): String {
        return when (demandType) {
            DemandType.CHANGE -> existingAsset?.owner ?: "Unknown"
            DemandType.CREATE_NEW -> newAssetOwner ?: "Unknown"
        }
    }

    override fun toString(): String {
        return "Demand(id=$id, title='$title', type=$demandType, status=$status)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Demand) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}

@Serdeable
enum class DemandType {
    CHANGE,     // Modifying an existing asset
    CREATE_NEW  // Creating a new asset
}

@Serdeable
enum class Priority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

@Serdeable
enum class DemandStatus {
    PENDING,      // Waiting for approval
    APPROVED,     // Approved and ready for risk assessment
    REJECTED,     // Rejected by approver
    IN_PROGRESS,  // Risk assessment in progress
    COMPLETED     // Risk assessment completed
}