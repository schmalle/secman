package com.secman.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*

@MappedSuperclass
@Serdeable
abstract class VersionedEntity(
    @ManyToOne
    @JoinColumn(name = "release_id")
    @JsonIgnore
    var release: Release? = null,

    @Column(name = "version_number")
    var versionNumber: Int = 1,

    @Column(name = "is_current")
    var isCurrent: Boolean = true
) {

    fun incrementVersion() {
        versionNumber++
    }

    fun markAsHistorical() {
        isCurrent = false
    }

    fun markAsCurrent() {
        isCurrent = true
    }

    fun isCurrentVersion(): Boolean = isCurrent

    fun belongsToRelease(release: Release): Boolean = this.release?.id == release.id
}