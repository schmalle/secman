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
import java.time.LocalDate

/**
 * One release cycle of an [EolProduct], e.g. Ubuntu `24.04` or Java `17`.
 *
 * [cycle] is the upstream cycle identifier and is what
 * [com.secman.service.EolVersionMatcher] prefix-matches an observed version
 * against ("24.04.1" -> cycle "24.04").
 *
 * [eolDate] null with [eolUnknown] true means upstream reports no EOL date yet
 * (release still maintained, or date not published). [eolDate] null with
 * [eolUnknown] false and [alreadyEol] true means upstream flagged the cycle as
 * EOL without giving a date — treated as EOL *now* for reporting, but never
 * used to compute a "within N months" horizon.
 */
@Entity
@Table(
    name = "eol_release",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_eol_release_product_cycle", columnNames = ["eol_product_id", "cycle"])
    ],
    indexes = [
        Index(name = "idx_eol_release_product", columnList = "eol_product_id"),
        Index(name = "idx_eol_release_eol_date", columnList = "eol_date")
    ]
)
@Serdeable
data class EolRelease(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "eol_product_id", nullable = false)
    var eolProductId: Long = 0,

    /** Upstream cycle identifier, e.g. `24.04`, `17`, `2019`. */
    @Column(name = "cycle", nullable = false, length = 100)
    var cycle: String = "",

    /** Upstream display label for the cycle, e.g. `24.04 'Noble Numbat'`. */
    @Column(name = "label", length = 255)
    var label: String? = null,

    @Column(name = "release_date")
    var releaseDate: LocalDate? = null,

    /** Date support ends. Null when unknown or upstream only flagged the state. */
    @Column(name = "eol_date")
    var eolDate: LocalDate? = null,

    /** End of active support ("EOAS"), when upstream distinguishes it from EOL. */
    @Column(name = "support_end_date")
    var supportEndDate: LocalDate? = null,

    /** Upstream flagged the cycle as already end-of-life. */
    @Column(name = "already_eol", nullable = false)
    var alreadyEol: Boolean = false,

    /** Upstream gave neither a date nor a boolean state. */
    @Column(name = "eol_unknown", nullable = false)
    var eolUnknown: Boolean = false,

    @Column(name = "lts", nullable = false)
    var lts: Boolean = false,

    /** Newest patch release within this cycle, e.g. `24.04.1`. */
    @Column(name = "latest_version", length = 100)
    var latestVersion: String? = null,

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
}
