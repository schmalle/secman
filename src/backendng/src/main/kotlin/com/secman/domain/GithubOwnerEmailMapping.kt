package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import java.time.Instant

/**
 * Default notification address for a GitHub owner (org or user login). Used
 * to auto-fill [GithubRepository.ownerEmail] on import for repos under this
 * owner that don't already have one set — see
 * [com.secman.service.GithubRepoImportService.persistRepo].
 */
@Entity
@Table(
    name = "github_owner_email_mapping",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_ghownermap_owner", columnNames = ["owner"])
    ]
)
@Serdeable
data class GithubOwnerEmailMapping(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** GitHub owner login (org or user) this default email applies to. */
    @Column(name = "owner", nullable = false, length = 255)
    var owner: String = "",

    @Column(name = "email", nullable = false, length = 255)
    var email: String = "",

    @Column(name = "created_by", nullable = false, length = 255)
    var createdBy: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)
