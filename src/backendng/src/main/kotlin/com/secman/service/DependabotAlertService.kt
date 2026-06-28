package com.secman.service

import com.secman.domain.DependabotAlert
import com.secman.repository.DependabotAlertRepository
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Upserts ingested GitHub Dependabot alerts.
 *
 * Idempotent on the natural key `(repository, alertNumber)`: an alert that is
 * imported again updates the existing row in place (state transitions, new
 * patched version, etc.) rather than creating a duplicate. This mirrors the
 * query-time, re-import-to-remediate model used by the CrowdStrike importer.
 */
@Singleton
open class DependabotAlertService(
    private val repository: DependabotAlertRepository
) {
    private val log = LoggerFactory.getLogger(DependabotAlertService::class.java)

    @Serdeable
    data class ImportRequest(
        val repository: String,
        val alertNumber: Int,
        val state: String? = null,
        val packageName: String? = null,
        val ecosystem: String? = null,
        val manifestPath: String? = null,
        val severity: String? = null,
        val ghsaId: String? = null,
        val cveId: String? = null,
        val summary: String? = null,
        val vulnerableVersionRange: String? = null,
        val firstPatchedVersion: String? = null,
        val htmlUrl: String? = null,
        val alertCreatedAt: String? = null,
        val alertUpdatedAt: String? = null,
        val dismissedAt: String? = null,
        val fixedAt: String? = null
    )

    @Serdeable
    data class ImportResult(
        val received: Int,
        val created: Int,
        val updated: Int,
        val skipped: Int,
        val errors: List<String> = emptyList()
    )

    @Transactional
    open fun importAlerts(alerts: List<ImportRequest>): ImportResult {
        var created = 0
        var updated = 0
        var skipped = 0
        val errors = mutableListOf<String>()

        for (dto in alerts) {
            if (dto.repository.isBlank()) {
                skipped++
                errors.add("Skipped alert with blank repository (number=${dto.alertNumber})")
                continue
            }
            try {
                val existing = repository.findByRepositoryAndAlertNumber(dto.repository, dto.alertNumber)
                if (existing.isPresent) {
                    applyTo(existing.get(), dto)
                    repository.update(existing.get())
                    updated++
                } else {
                    repository.save(applyTo(DependabotAlert(), dto))
                    created++
                }
            } catch (e: Exception) {
                errors.add("Failed ${dto.repository}#${dto.alertNumber}: ${e.message}")
                log.warn("Failed to import Dependabot alert {}#{}", dto.repository, dto.alertNumber, e)
            }
        }

        log.info(
            "Dependabot import: received={}, created={}, updated={}, skipped={}, errors={}",
            alerts.size, created, updated, skipped, errors.size
        )
        return ImportResult(alerts.size, created, updated, skipped, errors)
    }

    /** Copy DTO fields onto the entity, parsing timestamps leniently. */
    private fun applyTo(entity: DependabotAlert, dto: ImportRequest): DependabotAlert {
        entity.repository = dto.repository
        entity.alertNumber = dto.alertNumber
        entity.state = dto.state?.takeIf { it.isNotBlank() } ?: entity.state
        entity.packageName = dto.packageName ?: entity.packageName
        entity.ecosystem = dto.ecosystem ?: entity.ecosystem
        entity.manifestPath = dto.manifestPath
        entity.severity = dto.severity?.takeIf { it.isNotBlank() } ?: entity.severity
        entity.ghsaId = dto.ghsaId
        entity.cveId = dto.cveId
        entity.summary = dto.summary
        entity.vulnerableVersionRange = dto.vulnerableVersionRange
        entity.firstPatchedVersion = dto.firstPatchedVersion
        entity.htmlUrl = dto.htmlUrl
        entity.alertCreatedAt = parseInstant(dto.alertCreatedAt)
        entity.alertUpdatedAt = parseInstant(dto.alertUpdatedAt)
        entity.dismissedAt = parseInstant(dto.dismissedAt)
        entity.fixedAt = parseInstant(dto.fixedAt)
        entity.importedAt = Instant.now()
        return entity
    }

    private fun parseInstant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            Instant.parse(value)
        } catch (e: DateTimeParseException) {
            log.debug("Unparseable timestamp '{}' — storing null", value)
            null
        }
    }

    fun listAll(): List<DependabotAlert> =
        repository.findAll().sortedWith(compareBy({ it.repository }, { -it.alertNumber }))
}
