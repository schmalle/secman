package com.secman.domain

import jakarta.persistence.*
import java.time.LocalDateTime

/** Append-only evidence; deliberately survives deletion of an asset. */
@Entity
@Table(name = "crowdstrike_instance_transition", indexes = [Index(name = "idx_cs_transition_asset", columnList = "asset_id")])
data class CrowdStrikeInstanceTransition(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "asset_id", nullable = false)
    var assetId: Long,
    @Column(name = "crowdstrike_aid", nullable = false, length = 64)
    var crowdStrikeAid: String,
    @Column(name = "former_instance_id", nullable = false, length = 255)
    var formerInstanceId: String,
    @Column(name = "instance_id", nullable = false, length = 255)
    var instanceId: String,
    @Column(name = "cloud_account_id", nullable = false, length = 255)
    var cloudAccountId: String,
    @Column(name = "ad_domain", length = 255)
    var adDomain: String?,
    @Column(name = "falcon_first_seen_at", nullable = false)
    var falconFirstSeenAt: LocalDateTime,
    @Column(name = "falcon_last_seen_at")
    var falconLastSeenAt: LocalDateTime?,
    @Column(name = "observed_at", nullable = false)
    var observedAt: LocalDateTime,
    @Column(name = "committed_at", nullable = false)
    var committedAt: LocalDateTime = LocalDateTime.now(java.time.ZoneOffset.UTC),
    @Column(nullable = false, length = 255)
    var actor: String,
    @Column(nullable = false, length = 64)
    var reason: String = "SAME_ENROLLMENT_INSTANCE_CHANGE"
)
