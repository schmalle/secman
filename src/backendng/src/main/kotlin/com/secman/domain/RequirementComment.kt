package com.secman.domain

import jakarta.persistence.*
import java.time.Instant

/** Discussion is independent of alignment votes and never changes requirement content. */
@Entity
@Table(name = "requirement_comment")
class RequirementComment(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "requirement_id")
    var requirementId: Long? = null,
    @Column(name = "release_id")
    var releaseId: Long? = null,
    @Column(name = "snapshot_id")
    var snapshotId: Long? = null,
    @Column(name = "requirement_revision")
    var requirementRevision: Int? = null,
    @Column(name = "author_id")
    var authorId: Long? = null,
    @Column(name = "author_name", nullable = false)
    var authorName: String,
    @Column(nullable = false, length = 2000)
    var body: String,
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
)
