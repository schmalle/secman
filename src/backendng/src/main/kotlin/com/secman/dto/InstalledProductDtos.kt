package com.secman.dto

import com.secman.crowdstrike.dto.InstalledProductDto
import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.Valid
import java.time.LocalDateTime

@Serdeable
data class InstalledProductImportRequest(
    @field:Valid
    val products: List<InstalledProductDto>,
    val dryRun: Boolean = false,
    /**
     * Identifier shared across all batches of one CLI import run. When present, each server's
     * existing products are replaced (rows not from this run are deleted) for a clean snapshot.
     */
    val importRunId: String? = null
)

@Serdeable
data class InstalledProductImportResponse(
    val productsProcessed: Int,
    val productsImported: Int,
    val productsUpdated: Int,
    val productsSkipped: Int,
    val productsDeleted: Int,
    val unknownSystems: Int,
    val dryRun: Boolean,
    val errors: List<String> = emptyList()
)

@Serdeable
data class InstalledProductResponse(
    val id: Long,
    val assetId: Long,
    val hostname: String,
    val cloudAccountId: String?,
    val name: String,
    val vendor: String?,
    val version: String?,
    val category: String?,
    val installationPath: String?,
    val installedAt: LocalDateTime?,
    val lastUsedAt: LocalDateTime?,
    val lastUpdatedAt: LocalDateTime?,
    val importedAt: LocalDateTime
)

@Serdeable
data class InstalledProductListResponse(
    val products: List<InstalledProductResponse>,
    val totalProducts: Int,
    val totalSystems: Long
)
