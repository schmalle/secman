package com.secman.dto

import com.secman.domain.CrowdStrikeImportHistory
import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDateTime

/**
 * DTO exposing the latest CrowdStrike import execution metadata.
 *
 * Enables clients to understand how fresh the CrowdStrike-backed datasets are.
 */
@Serdeable
data class CrowdStrikeImportStatusDto(
    val importedAt: LocalDateTime,
    val importedBy: String?,
    val serversProcessed: Int,
    val serversCreated: Int,
    val serversUpdated: Int,
    val vulnerabilitiesImported: Int,
    val vulnerabilitiesSkipped: Int,
    val vulnerabilitiesWithPatchDate: Int,
    val errorCount: Int
) {
    companion object {
        fun fromEntity(entity: CrowdStrikeImportHistory): CrowdStrikeImportStatusDto {
            return CrowdStrikeImportStatusDto(
                importedAt = entity.importedAt ?: LocalDateTime.now(),
                importedBy = entity.importedBy,
                serversProcessed = entity.serversProcessed,
                serversCreated = entity.serversCreated,
                serversUpdated = entity.serversUpdated,
                vulnerabilitiesImported = entity.vulnerabilitiesImported,
                vulnerabilitiesSkipped = entity.vulnerabilitiesSkipped,
                vulnerabilitiesWithPatchDate = entity.vulnerabilitiesWithPatchDate,
                errorCount = entity.errorCount
            )
        }
    }
}
