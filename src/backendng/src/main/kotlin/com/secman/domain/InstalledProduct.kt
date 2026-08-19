package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GenerationType
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(
    name = "installed_product",
    indexes = [
        Index(name = "idx_installed_product_asset", columnList = "asset_id"),
        Index(name = "idx_installed_product_name", columnList = "name"),
        Index(name = "idx_installed_product_vendor", columnList = "vendor"),
        Index(name = "idx_installed_product_external", columnList = "external_id"),
        Index(name = "idx_installed_product_run", columnList = "asset_id, import_run_id")
    ]
)
@Serdeable
data class InstalledProduct(
    @Id
    // IDENTITY, not the AUTO default: this table's id column is AUTO_INCREMENT, but on
    // MariaDB Hibernate maps AUTO to a native sequence (<table>_seq) that starts at 1 and
    // knows nothing about rows the database numbered. On any long-lived schema that hands
    // out ids already taken -> "Duplicate entry 'n' for key 'PRIMARY'".
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    var asset: Asset,

    @Column(name = "external_id", length = 255)
    var externalId: String? = null,

    @Column(name = "crowdstrike_aid", length = 64)
    var crowdStrikeAid: String? = null,

    @Column(nullable = false, length = 512)
    var name: String,

    @Column(length = 255)
    var vendor: String? = null,

    @Column(length = 255)
    var version: String? = null,

    @Column(length = 255)
    var category: String? = null,

    @Column(name = "installation_path", length = 1024)
    var installationPath: String? = null,

    /**
     * Whether this row describes deployed software or an installer payload.
     * Maintained by [com.secman.service.ProductClassificationService]; never set by the import.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "product_class", nullable = false, length = 20)
    var productClass: ProductClass = ProductClass.UNKNOWN,

    @Column(name = "installed_at")
    var installedAt: LocalDateTime? = null,

    @Column(name = "last_used_at")
    var lastUsedAt: LocalDateTime? = null,

    @Column(name = "last_updated_at")
    var lastUpdatedAt: LocalDateTime? = null,

    @Column(name = "imported_at", nullable = false)
    var importedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "import_run_id", length = 64)
    var importRunId: String? = null,

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
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = LocalDateTime.now()
    }
}
