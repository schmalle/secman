package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.Pattern
import java.time.Instant

/**
 * WorkgroupAdDomain entity assigns an Active Directory domain to a workgroup.
 *
 * Direct members of the workgroup can access assets whose Asset.adDomain matches
 * this value case-insensitively. The same domain may be assigned to multiple
 * workgroups; duplicates within one workgroup are rejected.
 */
@Entity
@Table(
    name = "workgroup_ad_domain",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_workgroup_ad_domain",
            columnNames = ["workgroup_id", "ad_domain"]
        )
    ],
    indexes = [
        Index(name = "idx_wg_ad_domain_workgroup", columnList = "workgroup_id"),
        Index(name = "idx_wg_ad_domain", columnList = "ad_domain")
    ]
)
@Serdeable
data class WorkgroupAdDomain(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workgroup_id", nullable = false)
    var workgroup: Workgroup,

    @Column(name = "ad_domain", nullable = false, length = 255)
    @Pattern(regexp = "^[a-zA-Z0-9.-]+$", message = "AD domain must contain only letters, numbers, dots, and hyphens")
    var adDomain: String,

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "created_by_id", nullable = true)
    var createdBy: User?,

    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) {
    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        adDomain = adDomain.trim().lowercase()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        adDomain = adDomain.trim().lowercase()
        updatedAt = Instant.now()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WorkgroupAdDomain) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0

    override fun toString(): String =
        "WorkgroupAdDomain(id=$id, workgroupId=${workgroup.id}, adDomain='$adDomain')"
}
