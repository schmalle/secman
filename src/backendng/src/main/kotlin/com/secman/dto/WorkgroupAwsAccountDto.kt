package com.secman.dto

import com.secman.domain.WorkgroupAwsAccount
import io.micronaut.serde.annotation.Serdeable
import java.time.Instant

/**
 * Wire-format DTO for WorkgroupAwsAccount.
 *
 * Avoids serialization cycles caused by the entity's @ManyToOne references
 * to Workgroup and User. Only IDs and the account string are exposed; the
 * createdBy-username string is included for UI display ("granted by X").
 */
@Serdeable
data class WorkgroupAwsAccountDto(
    val id: Long,
    val workgroupId: Long,
    val awsAccountId: String,
    val createdByUsername: String,
    val createdAt: Instant?,
    val updatedAt: Instant?
) {
    companion object {
        fun from(entity: WorkgroupAwsAccount): WorkgroupAwsAccountDto {
            return WorkgroupAwsAccountDto(
                id = entity.id ?: error("Entity must be persisted before mapping to DTO"),
                workgroupId = entity.workgroup.id ?: error("Workgroup must be persisted"),
                awsAccountId = entity.awsAccountId,
                createdByUsername = entity.createdBy.username,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt
            )
        }
    }
}
