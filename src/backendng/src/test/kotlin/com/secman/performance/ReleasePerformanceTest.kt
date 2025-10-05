package com.secman.performance

import com.secman.domain.Release
import com.secman.domain.Requirement
import com.secman.domain.User
import com.secman.domain.User.Role
import com.secman.repository.*
import com.secman.service.ReleaseService
import io.micronaut.security.authentication.Authentication
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant
import kotlin.system.measureTimeMillis

/**
 * Performance tests for Release Management feature
 *
 * Tests non-functional requirements for release operations:
 * - T038: Release creation with 1000 requirements (target: < 2 seconds)
 *
 * Related to: Feature 011-i-want-to (Release-Based Requirement Version Management)
 */
@MicronautTest
class ReleasePerformanceTest {

    @Inject
    lateinit var releaseService: ReleaseService

    @Inject
    lateinit var requirementRepository: RequirementRepository

    @Inject
    lateinit var releaseRepository: ReleaseRepository

    @Inject
    lateinit var snapshotRepository: RequirementSnapshotRepository

    @Inject
    lateinit var userRepository: UserRepository

    private lateinit var testUser: User
    private lateinit var testAuthentication: Authentication

    @BeforeEach
    fun setUp() {
        // Clean up any existing test data
        snapshotRepository.deleteAll()
        releaseRepository.deleteAll()
        requirementRepository.deleteAll()

        // Create test user
        testUser = User(
            username = "perftest",
            email = "perftest@example.com",
            passwordHash = "hash",
            roles = mutableSetOf(Role.RELEASE_MANAGER)
        )
        testUser = userRepository.save(testUser)

        // Create mock authentication
        testAuthentication = Authentication.build("perftest", mapOf("roles" to listOf("RELEASE_MANAGER")))
    }

    @AfterEach
    fun tearDown() {
        // Clean up test data
        snapshotRepository.deleteAll()
        releaseRepository.deleteAll()
        requirementRepository.deleteAll()
        userRepository.deleteById(testUser.id!!)
    }

    @Test
    fun `T038 - Release creation with 1000 requirements completes in under 2 seconds`() {
        // Arrange: Create 1000 requirements in database
        println("Creating 1000 test requirements...")
        val setupStartTime = Instant.now()

        val requirements = (1..1000).map { index ->
            Requirement(
                shortreq = "PERF-REQ-${index.toString().padStart(4, '0')}",
                chapter = "Chapter ${(index % 10) + 1}",
                norm = "ISO 27001",
                details = "Performance test requirement $index for snapshot stress testing. " +
                        "This requirement contains enough detail to simulate real-world data volume.",
                motivation = "Test motivation for requirement $index",
                example = "Example implementation for requirement $index",
                usecase = "Performance testing use case $index"
            )
        }

        requirementRepository.saveAll(requirements)

        val setupDuration = java.time.Duration.between(setupStartTime, Instant.now())
        println("Test data setup completed in ${setupDuration.toMillis()}ms")

        // Verify 1000 requirements were created
        val requirementCount = requirementRepository.count()
        assertEquals(1000L, requirementCount, "Should have exactly 1000 requirements for performance test")

        // Act: Measure release creation time (includes snapshotting all 1000 requirements)
        println("Starting release creation with 1000 requirement snapshots...")

        val executionTimeMs = measureTimeMillis {
            val release = releaseService.createRelease(
                version = "1.0.0",
                name = "Performance Test Release",
                description = "Testing release creation performance with 1000 requirements",
                authentication = testAuthentication
            )

            assertNotNull(release.id, "Release should be created with ID")
        }

        // Assert: Performance target
        println("Release creation completed in ${executionTimeMs}ms")

        val targetTimeMs = 2000L
        assertTrue(
            executionTimeMs < targetTimeMs,
            "Release creation should complete in under ${targetTimeMs}ms. " +
                    "Actual: ${executionTimeMs}ms (${executionTimeMs - targetTimeMs}ms over budget)"
        )

        // Verify: All 1000 requirements were snapshotted
        val release = releaseRepository.findByVersion("1.0.0").orElseThrow()
        val snapshotCount = snapshotRepository.countByReleaseId(release.id!!)

        assertEquals(1000L, snapshotCount, "All 1000 requirements should be snapshotted in release")

        // Additional verification: Sample check of snapshot data integrity
        val snapshots = snapshotRepository.findByReleaseId(release.id!!)
        val firstSnapshot = snapshots.first()

        assertTrue(firstSnapshot.shortreq.startsWith("PERF-REQ-"))
        assertNotNull(firstSnapshot.details)
        assertTrue(firstSnapshot.details?.isNotEmpty() == true)
        assertEquals(release.id, firstSnapshot.release.id)

        println("Performance test completed successfully:")
        println("  - 1000 requirements created")
        println("  - 1000 snapshots generated in ${executionTimeMs}ms")
        println("  - Average: ${executionTimeMs / 1000.0}ms per snapshot")
        println("  - Performance budget: ${targetTimeMs}ms (${((executionTimeMs.toDouble() / targetTimeMs) * 100).toInt()}% utilized)")
    }

    @Test
    fun `Performance - Multiple releases can be created sequentially within budget`() {
        // Create 100 requirements (smaller dataset for multiple release test)
        val requirements = (1..100).map { index ->
            Requirement(
                shortreq = "SEQ-REQ-${index.toString().padStart(3, '0')}",
                chapter = "Chapter ${(index % 5) + 1}",
                norm = "ISO 27001",
                details = "Sequential test requirement $index",
                motivation = "Test motivation",
                example = "Example",
                usecase = "Use case"
            )
        }
        requirementRepository.saveAll(requirements)

        // Create 5 releases sequentially
        val releaseExecutionTimes = mutableListOf<Long>()

        for (i in 1..5) {
            val executionTime = measureTimeMillis {
                releaseService.createRelease(
                    version = "1.0.$i",
                    name = "Sequential Release $i",
                    description = "Testing sequential release creation",
                    authentication = testAuthentication
                )
            }
            releaseExecutionTimes.add(executionTime)
            println("Release 1.0.$i created in ${executionTime}ms")
        }

        // Each release should be reasonably fast (< 500ms for 100 requirements)
        val targetPerRelease = 500L
        releaseExecutionTimes.forEach { executionTime ->
            assertTrue(
                executionTime < targetPerRelease,
                "Each release creation should complete in under ${targetPerRelease}ms. Found: ${executionTime}ms"
            )
        }

        // Verify all releases and snapshots were created
        assertEquals(5L, releaseRepository.count())
        assertEquals(500L, snapshotRepository.count()) // 5 releases * 100 requirements each

        println("Sequential release creation test completed:")
        println("  - 5 releases created")
        println("  - Average time: ${releaseExecutionTimes.average().toLong()}ms")
        println("  - Min time: ${releaseExecutionTimes.minOrNull()}ms")
        println("  - Max time: ${releaseExecutionTimes.maxOrNull()}ms")
    }

    @Test
    fun `Performance - Release creation scales linearly with requirement count`() {
        // Test with different requirement counts to verify linear scaling
        val testCases = listOf(10, 50, 100, 250)
        val executionTimes = mutableMapOf<Int, Long>()

        for (count in testCases) {
            // Clean up between test cases
            snapshotRepository.deleteAll()
            releaseRepository.deleteAll()
            requirementRepository.deleteAll()

            // Create N requirements
            val requirements = (1..count).map { index ->
                Requirement(
                    shortreq = "SCALE-REQ-${count}-${index.toString().padStart(3, '0')}",
                    chapter = "Chapter ${(index % 5) + 1}",
                    norm = "ISO 27001",
                    details = "Scaling test requirement $index for count $count",
                    motivation = "Test",
                    example = "Example",
                    usecase = "Use case"
                )
            }
            requirementRepository.saveAll(requirements)

            // Measure release creation time
            val executionTime = measureTimeMillis {
                releaseService.createRelease(
                    version = "1.0.0",
                    name = "Scale Test $count",
                    description = "Testing with $count requirements",
                    authentication = testAuthentication
                )
            }

            executionTimes[count] = executionTime
            println("Release with $count requirements created in ${executionTime}ms (${executionTime.toDouble() / count}ms per requirement)")
        }

        // Verify roughly linear scaling (time per requirement should be relatively constant)
        val timePerRequirement = executionTimes.mapValues { (count, time) -> time.toDouble() / count }

        println("\nScaling analysis:")
        timePerRequirement.forEach { (count, timePerReq) ->
            println("  $count requirements: ${String.format("%.2f", timePerReq)}ms per requirement")
        }

        // The time per requirement should be relatively stable (within 3x variance)
        val avgTimePerReq = timePerRequirement.values.average()
        timePerRequirement.forEach { (count, timePerReq) ->
            val variance = timePerReq / avgTimePerReq
            assertTrue(
                variance < 3.0,
                "Time per requirement should scale linearly. " +
                        "For $count requirements: ${String.format("%.2f", timePerReq)}ms/req " +
                        "(${String.format("%.1f", variance)}x average)"
            )
        }
    }
}
