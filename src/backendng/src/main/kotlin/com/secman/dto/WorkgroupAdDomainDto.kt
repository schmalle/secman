package com.secman.dto

import com.secman.domain.WorkgroupAdDomain
import io.micronaut.serde.annotation.Serdeable
import java.time.Instant

@Serdeable
data class WorkgroupAdDomainDto(
    val id: Long,
    val workgroupId: Long,
    val adDomain: String,
    val createdByUsername: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?
) {
    companion object {
        fun from(entity: WorkgroupAdDomain): WorkgroupAdDomainDto {
            return WorkgroupAdDomainDto(
                id = entity.id ?: error("Entity must be persisted before mapping to DTO"),
                workgroupId = entity.workgroup.id ?: error("Workgroup must be persisted"),
                adDomain = entity.adDomain,
                createdByUsername = entity.createdBy?.username,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt
            )
        }
    }
}
