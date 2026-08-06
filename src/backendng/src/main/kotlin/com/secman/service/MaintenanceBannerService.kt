package com.secman.service

import com.secman.domain.MaintenanceBanner
import com.secman.domain.User
import com.secman.dto.MaintenanceBannerRequest
import com.secman.repository.MaintenanceBannerRepository
import jakarta.inject.Singleton
import org.owasp.html.PolicyFactory
import org.owasp.html.Sanitizers
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Service for maintenance banner business logic.
 *
 * Responsibilities:
 * - CRUD operations for maintenance banners
 * - XSS prevention via OWASP HTML sanitization
 * - Time range validation (endTime > startTime)
 * - Finding active banners
 *
 * Security:
 * - All user input (message) is sanitized using OWASP Java HTML Sanitizer
 * - Time range validation prevents invalid data
 */
@Singleton
open class MaintenanceBannerService(
    private val repository: MaintenanceBannerRepository
) {
    private val logger = LoggerFactory.getLogger(MaintenanceBannerService::class.java)
    /**
     * OWASP HTML sanitizer for XSS prevention.
     * Configuration: FORMATTING (bold, italic) + BLOCKS (paragraphs, lists)
     * For MVP: Plain text only, but sanitizer provides defense in depth
     */
    private val sanitizer: PolicyFactory = Sanitizers.FORMATTING.and(Sanitizers.BLOCKS)

    /**
     * Create a new maintenance banner.
     *
     * Process:
     * 1. Sanitize message text for XSS prevention
     * 2. Validate time range (endTime > startTime)
     * 3. Save to database
     *
     * @param request Banner creation request
     * @param user Admin user creating the banner
     * @return Created banner entity
     * @throws IllegalArgumentException if endTime is not after startTime
     */
    fun createBanner(request: MaintenanceBannerRequest, user: User): MaintenanceBanner {
        logger.info("Creating maintenance banner by user: ${user.username}, startTime: ${request.startTime}, endTime: ${request.endTime}")

        // Validate time range
        if (!request.validate()) {
            logger.warn("Failed to create banner: End time must be after start time. User: ${user.username}")
            throw IllegalArgumentException("End time must be after start time")
        }

        // Sanitize message for XSS prevention
        val sanitizedMessage = sanitizer.sanitize(request.message)

        // Create and save banner
        val banner = MaintenanceBanner(
            message = sanitizedMessage,
            startTime = request.startTime,
            endTime = request.endTime,
            createdBy = user
        )

        val savedBanner = repository.save(banner)
        logger.info("Successfully created maintenance banner ID: ${savedBanner.id}, active from ${savedBanner.startTime} to ${savedBanner.endTime}")
        return savedBanner
    }

    /**
     * Update an existing maintenance banner.
     *
     * Process:
     * 1. Find existing banner
     * 2. Sanitize new message text
     * 3. Validate time range
     * 4. Update and save
     *
     * @param id Banner ID
     * @param request Update request
     * @return Updated banner entity
     * @throws IllegalArgumentException if banner not found or invalid time range
     */
    fun updateBanner(id: Long, request: MaintenanceBannerRequest): MaintenanceBanner {
        logger.info("Updating maintenance banner ID: $id, startTime: ${request.startTime}, endTime: ${request.endTime}")

        // Validate time range
        if (!request.validate()) {
            logger.warn("Failed to update banner ID $id: End time must be after start time")
            throw IllegalArgumentException("End time must be after start time")
        }

        // Find existing banner
        val banner = repository.findById(id).orElseThrow {
            logger.warn("Failed to update banner: Banner with ID $id not found")
            IllegalArgumentException("Maintenance banner with ID $id not found")
        }

        // Sanitize message and update
        banner.message = sanitizer.sanitize(request.message)
        banner.startTime = request.startTime
        banner.endTime = request.endTime

        val updatedBanner = repository.update(banner)
        logger.info("Successfully updated maintenance banner ID: $id")
        return updatedBanner
    }

    /**
     * Delete a maintenance banner.
     *
     * @param id Banner ID
     * @throws IllegalArgumentException if banner not found
     */
    fun deleteBanner(id: Long) {
        logger.info("Deleting maintenance banner ID: $id")

        val banner = repository.findById(id).orElseThrow {
            logger.warn("Failed to delete banner: Banner with ID $id not found")
            IllegalArgumentException("Maintenance banner with ID $id not found")
        }

        repository.delete(banner)
        logger.info("Successfully deleted maintenance banner ID: $id")
    }

    /**
     * Get a specific maintenance banner by ID.
     *
     * @param id Banner ID
     * @return Banner entity
     * @throws IllegalArgumentException if banner not found
     */
    fun getBannerById(id: Long): MaintenanceBanner {
        return repository.findById(id).orElseThrow {
            IllegalArgumentException("Maintenance banner with ID $id not found")
        }
    }

    /**
     * Get all currently active maintenance banners.
     *
     * Active = current time is between startTime and endTime
     * Ordered by creation time (newest first)
     *
     * @return List of active banners (may be empty)
     */
    fun getActiveBanners(): List<MaintenanceBanner> {
        return repository.findActiveBanners(Instant.now())
    }

    /**
     * Get all maintenance banners (past, current, future).
     *
     * Used for admin list view.
     * Ordered by creation time (newest first)
     *
     * @return List of all banners
     */
    fun getAllBanners(): List<MaintenanceBanner> {
        return repository.findAllOrderByCreatedAtDesc()
    }
}
