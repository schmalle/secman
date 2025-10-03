package com.secman.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

@Entity
@Table(name = "asset")
@Serdeable
data class Asset(
    @Id
    @GeneratedValue
    var id: Long? = null,

    @Column(nullable = false)
    @NotBlank
    @Size(max = 255)
    var name: String,

    @Column(nullable = false)
    @NotBlank
    var type: String,

    @Column
    var ip: String? = null,

    @Column(nullable = false)
    @NotBlank
    @Size(max = 255)
    var owner: String,

    @Column(length = 1024)
    var description: String? = null,

    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime? = null,

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null,

    /**
     * Timestamp when asset was last seen in a scan
     * Updated when new ScanResult is created for this asset
     * Related to: Feature 002-implement-a-parsing (Nmap Scan Import)
     */
    @Column(name = "last_seen")
    var lastSeen: LocalDateTime? = null,

    /**
     * Bidirectional relationship to ScanResult
     * One asset can have multiple scan results over time (scan history)
     * Foreign key is in scan_result table (asset_id)
     * Related to: Feature 002-implement-a-parsing, Decision 4 (point-in-time snapshots)
     *
     * Note: @JsonIgnore prevents lazy loading errors during JSON serialization.
     * Scan results should be loaded explicitly via port history endpoint.
     */
    @JsonIgnore
    @OneToMany(mappedBy = "asset", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var scanResults: MutableList<ScanResult> = mutableListOf()
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

    /**
     * Add a scan result to this asset
     * Maintains bidirectional relationship and updates lastSeen
     */
    fun addScanResult(result: ScanResult) {
        scanResults.add(result)
        result.asset = this
        lastSeen = result.discoveredAt
        updatedAt = LocalDateTime.now()
    }

    override fun toString(): String {
        return "Asset(id=$id, name='$name', type='$type', owner='$owner', ip='$ip')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Asset) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}