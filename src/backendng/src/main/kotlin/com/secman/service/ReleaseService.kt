package com.secman.service

import com.secman.domain.Release
import com.secman.domain.RequirementSnapshot
import com.secman.repository.ReleaseRepository
import com.secman.repository.RequirementRepository
import com.secman.repository.RequirementSnapshotRepository
import com.secman.repository.UserRepository
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

@Singleton
class ReleaseService(
    private val releaseRepository: ReleaseRepository,
    private val requirementRepository: RequirementRepository,
    private val snapshotRepository: RequirementSnapshotRepository,
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(ReleaseService::class.java)

    companion object {
        private val SEMANTIC_VERSION_REGEX = Regex("""^\d+\.\d+\.\d+$""")
    }

    /**
     * Create a new release with requirement snapshots
     *
     * @param version Semantic version (MAJOR.MINOR.PATCH)
     * @param name Human-readable release name
     * @param description Optional detailed description
     * @param authentication Current user authentication
     * @return Created release with snapshots
     * @throws IllegalArgumentException if version is invalid or already exists
     */
    fun createRelease(
        version: String,
        name: String,
        description: String?,
        authentication: Authentication
    ): Release {
        val username = authentication.name
        val user = userRepository.findByUsername(username)
            .orElseThrow { IllegalStateException("User not found: $username") }
        return createReleaseForUser(version, name, description, user.id!!)
    }

    /**
     * Create a new release with requirement snapshots (for MCP and programmatic usage)
     *
     * @param version Semantic version (MAJOR.MINOR.PATCH)
     * @param name Human-readable release name
     * @param description Optional detailed description
     * @param userId ID of the user creating the release
     * @return Created release with snapshots
     * @throws IllegalArgumentException if version is invalid or already exists
     * @throws NoSuchElementException if user not found
     */
    fun createReleaseForUser(
        version: String,
        name: String,
        description: String?,
        userId: Long
    ): Release {
        logger.info("Creating release version=$version name=$name")

        // 1. Validate version format
        if (!SEMANTIC_VERSION_REGEX.matches(version)) {
            throw IllegalArgumentException(
                "Version must follow semantic versioning format (MAJOR.MINOR.PATCH). Got: $version"
            )
        }

        // 2. Check version uniqueness
        if (releaseRepository.existsByVersion(version)) {
            throw IllegalArgumentException("Release with version $version already exists")
        }

        // 3. Look up the user
        val user = userRepository.findById(userId)
            .orElseThrow { NoSuchElementException("User not found with ID: $userId") }
        logger.debug("Creating release for user: ${user.username}")

        // 4. Create Release entity
        val release = Release(
            version = version,
            name = name,
            description = description,
            status = Release.ReleaseStatus.DRAFT,
            createdBy = user
        )

        // 5. Save release to get ID
        val savedRelease = releaseRepository.save(release)
        logger.debug("Release saved with ID=${savedRelease.id}")

        // 6. Query all current requirements
        val currentRequirements = requirementRepository.findCurrentRequirements()
        logger.info("Found ${currentRequirements.size} current requirements to snapshot")

        // 7. Create snapshots for each requirement
        val snapshots = currentRequirements.map { requirement ->
            RequirementSnapshot.fromRequirement(requirement, savedRelease)
        }

        // 8. Bulk save snapshots
        snapshotRepository.saveAll(snapshots)
        logger.info("Created ${snapshots.size} requirement snapshots for release ${savedRelease.id}")

        return savedRelease
    }

    /**
     * Delete a release and its snapshots
     *
     * @param releaseId ID of release to delete
     * @throws NoSuchElementException if release not found
     * @throws IllegalStateException if release is ACTIVE
     */
    fun deleteRelease(releaseId: Long) {
        logger.info("Deleting release ID=$releaseId")

        val release = releaseRepository.findById(releaseId)
            .orElseThrow { NoSuchElementException("Release with ID $releaseId not found") }

        // Prevent deletion of ACTIVE releases
        if (release.status == Release.ReleaseStatus.ACTIVE) {
            throw IllegalStateException("Cannot delete an ACTIVE release. Set another release as active first.")
        }

        // Explicitly delete snapshots first (FK doesn't have ON DELETE CASCADE)
        snapshotRepository.deleteByReleaseId(releaseId)
        logger.info("Deleted snapshots for release ID=$releaseId")

        // Now delete the release
        releaseRepository.delete(release)
        logger.info("Deleted release ID=$releaseId")
    }

    /**
     * Get release by ID
     */
    fun getReleaseById(releaseId: Long): Release {
        return releaseRepository.findById(releaseId)
            .orElseThrow { NoSuchElementException("Release with ID $releaseId not found") }
    }

    /**
     * List all releases with optional status filter
     */
    fun listReleases(status: Release.ReleaseStatus? = null): List<Release> {
        return if (status != null) {
            releaseRepository.findByStatus(status)
        } else {
            releaseRepository.findAllOrderByCreatedAtDesc()
        }
    }

    /**
     * Update release status with workflow validation
     * Enforces workflow: DRAFT → ACTIVE, ACTIVE → LEGACY (automatic)
     *
     * Note: Only one release can be ACTIVE at a time. When setting a release to ACTIVE,
     * the previously ACTIVE release is automatically set to LEGACY.
     *
     * @param releaseId ID of release to update
     * @param newStatus New status to transition to (only ACTIVE is allowed manually)
     * @return Updated release
     * @throws NoSuchElementException if release not found
     * @throws IllegalStateException if transition is not allowed
     */
    fun updateReleaseStatus(releaseId: Long, newStatus: Release.ReleaseStatus): Release {
        logger.info("Updating release $releaseId status to $newStatus")

        val release = releaseRepository.findById(releaseId)
            .orElseThrow { NoSuchElementException("Release with ID $releaseId not found") }

        val currentStatus = release.status

        // Validate status transition workflow
        // DRAFT → ACTIVE is allowed (direct or after alignment)
        // IN_REVIEW → ACTIVE is allowed (after alignment completes)
        // ACTIVE → LEGACY happens automatically when another release becomes ACTIVE
        val validTransition = when (currentStatus) {
            Release.ReleaseStatus.DRAFT -> newStatus == Release.ReleaseStatus.ACTIVE
            Release.ReleaseStatus.IN_REVIEW -> newStatus == Release.ReleaseStatus.ACTIVE // Allow activation after alignment
            Release.ReleaseStatus.ACTIVE -> false // Cannot manually change ACTIVE status
            Release.ReleaseStatus.LEGACY -> false // No transitions from LEGACY
            Release.ReleaseStatus.PUBLISHED -> false // Cannot manually change PUBLISHED status
        }

        if (!validTransition) {
            throw IllegalStateException(
                "Invalid status transition from $currentStatus to $newStatus. " +
                "Only DRAFT releases can be set to ACTIVE."
            )
        }

        // When setting to ACTIVE, move all other ACTIVE releases to LEGACY (only one can be active)
        if (newStatus == Release.ReleaseStatus.ACTIVE) {
            val currentlyActiveReleases = releaseRepository.findByStatus(Release.ReleaseStatus.ACTIVE)
            for (activeRelease in currentlyActiveReleases) {
                if (activeRelease.id != releaseId) {
                    logger.info("Moving release ${activeRelease.id} (${activeRelease.version}) to LEGACY")
                    activeRelease.status = Release.ReleaseStatus.LEGACY
                    releaseRepository.update(activeRelease)
                }
            }
        }

        // Update status
        release.status = newStatus
        val updatedRelease = releaseRepository.update(release)
        logger.info("Release $releaseId status updated to $newStatus")

        return updatedRelease
    }
}
