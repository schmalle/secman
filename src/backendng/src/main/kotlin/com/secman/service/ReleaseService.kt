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

        // 3. Look up the authenticated user
        val username = authentication.name
        val user = userRepository.findByUsername(username)
            .orElseThrow { IllegalStateException("User not found: $username") }
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
     */
    fun deleteRelease(releaseId: Long) {
        logger.info("Deleting release ID=$releaseId")

        val release = releaseRepository.findById(releaseId)
            .orElseThrow { NoSuchElementException("Release with ID $releaseId not found") }

        releaseRepository.delete(release)
        logger.info("Deleted release ID=$releaseId (snapshots cascade deleted)")
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
}
