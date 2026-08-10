package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/**
 * A product in the end-of-life (EOL) catalogue, e.g. `ubuntu`, `windows-server`,
 * `java`, `nodejs`.
 *
 * Rows are written by [com.secman.service.EolCatalogSyncService] from the
 * configured upstream source (default: endoflife.date). The catalogue is
 * reference data — it is upserted by [sourceKey] + [productKey] and never
 * deleted by the sync, so a product that temporarily vanishes upstream does not
 * silently drop the findings that reference it.
 *
 * [aliases] holds the upstream aliases plus the product label, lowercased and
 * comma-separated, so [com.secman.service.EolVersionMatcher] can resolve an
 * installed-product name such as "Ubuntu Linux" to `ubuntu` without a second
 * lookup table.
 */
@Entity
@Table(
    name = "eol_product",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_eol_product_source_key", columnNames = ["source_key", "product_key"])
    ],
    indexes = [
        Index(name = "idx_eol_product_key", columnList = "product_key"),
        Index(name = "idx_eol_product_category", columnList = "category")
    ]
)
@Serdeable
data class EolProduct(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** Upstream catalogue identifier, e.g. `endoflife.date`. */
    @Column(name = "source_key", nullable = false, length = 64)
    var sourceKey: String = DEFAULT_SOURCE_KEY,

    /** Canonical, lowercase upstream product id, e.g. `ubuntu`. */
    @Column(name = "product_key", nullable = false, length = 190)
    var productKey: String = "",

    /** Human-readable name, e.g. `Ubuntu`. */
    @Column(name = "label", nullable = false, length = 255)
    var label: String = "",

    /** Upstream category, e.g. `os`, `lang`, `db`, `server-app`. */
    @Column(name = "category", length = 64)
    var category: String? = null,

    /** Lowercased, comma-separated alias list used for name matching. */
    @Column(name = "aliases", length = 2048)
    var aliases: String? = null,

    /** Public documentation URI for the product's lifecycle page. */
    @Column(name = "uri", length = 1024)
    var uri: String? = null,

    @Column(name = "last_synced_at")
    var lastSyncedAt: Instant? = null,

    @Column(name = "created_at")
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) {
    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        createdAt = createdAt ?: now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }

    companion object {
        const val DEFAULT_SOURCE_KEY = "endoflife.date"
    }
}
