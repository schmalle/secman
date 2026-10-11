package com.secman.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/** Stable lock rows serialize identity creation across backend processes. They contain no grants. */
@Entity
@Table(name = "crowdstrike_identity_lock")
class CrowdStrikeIdentityLock(
    @Id @Column(name = "identity_key", length = 64)
    var identityKey: String = ""
)
