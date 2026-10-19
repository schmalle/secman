package com.secman.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/** Evidence survives retirement of the current binding; deliberately has no asset foreign key. */
@Entity
@Table(name = "crowdstrike_enrollment_history")
class CrowdStrikeEnrollmentHistory(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "crowdstrike_aid", nullable = false, unique = true, length = 64)
    var crowdStrikeAid: String,
    @Column(name = "asset_id", nullable = false)
    var assetId: Long,
    @Column(nullable = false)
    var hostname: String,
    @Column(name = "instance_id")
    var instanceId: String? = null,
    @Column(name = "cloud_account_id")
    var cloudAccountId: String? = null,
    @Column(name = "product_type", length = 64)
    var productType: String? = null,
    @Column(name = "falcon_first_seen_at")
    var falconFirstSeenAt: LocalDateTime? = null,
    @Column(name = "falcon_last_seen_at")
    var falconLastSeenAt: LocalDateTime? = null,
    @Column(name = "superseded_by", length = 64)
    var supersededBy: String? = null,
    @Column(name = "observed_at", nullable = false)
    var observedAt: LocalDateTime = LocalDateTime.now(java.time.ZoneOffset.UTC)
)
