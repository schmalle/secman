package com.secman.service

import com.secman.domain.RequirementIdSequence
import com.secman.repository.RequirementIdSequenceRepository
import com.secman.repository.RequirementRepository
import io.micronaut.context.event.StartupEvent
import io.micronaut.runtime.event.annotation.EventListener
import io.micronaut.scheduling.annotation.Async
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Migration service that assigns unique internalId to existing requirements with blank IDs.
 *
 * This service runs on application startup and ensures all requirements have proper
 * ID.Revision format (e.g., REQ-001.1) for Excel exports.
 *
 * Related to: Fix for requirements Excel export showing only revision numbers.
 */
@Singleton
open class RequirementIdMigrationService(
    @Inject private val requirementRepository: RequirementRepository,
    @Inject private val requirementIdService: RequirementIdService,
    @Inject private val sequenceRepository: RequirementIdSequenceRepository
) {
    private val log = LoggerFactory.getLogger(RequirementIdMigrationService::class.java)

    @EventListener
    @Async
    @Transactional
    open fun onStartup(event: StartupEvent) {
        try {
            initializeSequenceIfEmpty()
            migrateBlankInternalIds()
        } catch (e: Exception) {
            log.error("Failed to migrate requirement IDs on startup: {}", e.message, e)
        }
    }

    /**
     * Initialize the sequence table if it's empty.
     * Calculates the next value based on existing requirements with internalId.
     */
    @Transactional
    open fun initializeSequenceIfEmpty() {
        if (sequenceRepository.existsById(1L)) {
            log.debug("Requirement ID sequence already initialized")
            return
        }

        // Calculate the next value based on existing requirements
        val existingIds = requirementRepository.findAll()
            .filter { it.internalId.isNotBlank() }
            .mapNotNull { extractIdNumber(it.internalId) }

        val nextValue = if (existingIds.isEmpty()) 1 else existingIds.max() + 1

        val sequence = RequirementIdSequence(
            id = 1L,
            nextValue = nextValue,
            updatedAt = Instant.now()
        )
        sequenceRepository.save(sequence)
        log.info("Initialized requirement ID sequence with next_value={}", nextValue)
    }

    /**
     * Extract the numeric part from an internal ID like "REQ-001" -> 1
     */
    private fun extractIdNumber(internalId: String): Int? {
        return try {
            // Handle formats like "REQ-001" or "REQ-1234"
            val numPart = internalId.removePrefix("REQ-")
            numPart.toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    @Transactional
    open fun migrateBlankInternalIds(): Int {
        val requirements = requirementRepository.findAll()
            .filter { it.internalId.isBlank() }

        if (requirements.isEmpty()) {
            log.info("No requirements with blank internalId found")
            return 0
        }

        log.info("Migrating {} requirements with blank internalId", requirements.size)

        requirements.forEach { requirement ->
            requirement.internalId = requirementIdService.getNextId()
            requirementRepository.update(requirement)
            log.debug("Assigned internalId {} to requirement '{}'", requirement.internalId, requirement.shortreq)
        }

        log.info("Successfully migrated {} requirements with unique IDs", requirements.size)
        return requirements.size
    }
}
