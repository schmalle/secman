package com.secman.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Entity
@Table(
    name = "crowdstrike_asset_identity",
    uniqueConstraints = [UniqueConstraint(name = "uk_crowdstrike_asset_identity_aid", columnNames = ["crowdstrike_aid"])],
    indexes = [Index(name = "idx_crowdstrike_asset_identity_asset", columnList = "asset_id")]
)
/** A Falcon agent identity that remains stable when either display or source hostnames change. */
data class CrowdStrikeAssetIdentity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    var asset: Asset,

    @Column(name = "crowdstrike_aid", nullable = false, length = 64)
    var crowdStrikeAid: String,

    @Column(name = "source_hostname", nullable = false, length = 255)
    var sourceHostname: String,

    @Column(name = "first_seen_at", nullable = false)
    var firstSeenAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: LocalDateTime = LocalDateTime.now()
)
