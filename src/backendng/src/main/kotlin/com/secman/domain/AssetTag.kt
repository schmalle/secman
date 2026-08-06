package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/**
 * Flexible key-value tagging for assets.
 * Allows arbitrary classification (e.g., environment=production, role=web-server, team=platform).
 */
@Entity
@Table(
    name = "asset_tag",
    indexes = [
        Index(name = "idx_asset_tag_asset", columnList = "asset_id"),
        Index(name = "idx_asset_tag_key", columnList = "tag_key"),
        Index(name = "idx_asset_tag_key_value", columnList = "tag_key,tag_value")
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uk_asset_tag_asset_key_value", columnNames = ["asset_id", "tag_key", "tag_value"])
    ]
)
@Serdeable
data class AssetTag(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", nullable = false)
    var asset: Asset,

    @Column(name = "tag_key", nullable = false, length = 100)
    @NotBlank
    @Size(max = 100)
    var key: String,

    @Column(name = "tag_value", nullable = false, length = 255)
    @NotBlank
    @Size(max = 255)
    var value: String,

    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime? = null
) {
    @PrePersist
    fun onCreate() {
        createdAt = LocalDateTime.now()
    }

    override fun toString(): String {
        return "AssetTag(id=$id, key='$key', value='$value')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AssetTag) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}
