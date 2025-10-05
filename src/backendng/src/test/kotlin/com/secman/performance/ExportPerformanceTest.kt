package com.secman.performance

import com.secman.controller.RequirementController
import com.secman.domain.Release
import com.secman.domain.Requirement
import com.secman.domain.User
import com.secman.domain.User.Role
import com.secman.repository.*
import com.secman.service.ReleaseService
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
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
 * Performance tests for Export functionality with Release snapshots
 *
 * Tests non-functional requirements for export operations:
 * - T039: Export from release with 1000 requirements (target: < 3 seconds)
 *
 * Related to: Feature 011-i-want-to (Release-Based Requirement Version Management)
 */
@MicronautTest
class ExportPerformanceTest {

    @Inject
    lateinit var releaseService: ReleaseService

    @Inject
    lateinit var requirementController: RequirementController

    @Inject
    lateinit var requirementRepository: RequirementRepository

    @Inject
    lateinit var releaseRepository: ReleaseRepository

    @Inject
    lateinit var snapshotRepository: RequirementSnapshotRepository

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    @field:Client("/")
    lateinit var httpClient: HttpClient

    private lateinit var testUser: User
    private lateinit var testAuthentication: Authentication
    private lateinit var testRelease: Release

    @BeforeEach
    fun setUp() {
        // Clean up any existing test data
        snapshotRepository.deleteAll()
        releaseRepository.deleteAll()
        requirementRepository.deleteAll()

        // Create test user
        testUser = User(
            username = "exportperftest",
            email = "exportperftest@example.com",
            passwordHash = "hash",
            roles = mutableSetOf(Role.USER)
        )
        testUser = userRepository.save(testUser)

        // Create mock authentication
        testAuthentication = Authentication.build("exportperftest", mapOf("roles" to listOf("USER")))

        // Create 1000 requirements
        println("Creating 1000 test requirements for export performance test...")
        val setupStartTime = Instant.now()

        val requirements = (1..1000).map { index ->
            Requirement(
                shortreq = "EXPORT-REQ-${index.toString().padStart(4, '0')}",
                chapter = "Chapter ${(index % 10) + 1}",
                norm = "ISO 27001:${2013 + (index % 10)}",
                details = "Export performance test requirement $index. " +
                        "This requirement contains realistic detail length to simulate production data volume. " +
                        "Additional text to ensure we're testing with reasonable data sizes that mirror real-world usage.",
                motivation = "Performance testing motivation for requirement $index to ensure export handles realistic data volumes",
                example = "Example implementation for requirement $index with sufficient detail",
                usecase = "Performance testing use case $index"
            )
        }

        requirementRepository.saveAll(requirements)

        val setupDuration = java.time.Duration.between(setupStartTime, Instant.now())
        println("Test requirements created in ${setupDuration.toMillis()}ms")

        // Create release with all 1000 requirements snapshotted
        println("Creating release with 1000 snapshots...")
        val releaseStartTime = Instant.now()

        testRelease = releaseService.createRelease(
            version = "1.0.0",
            name = "Export Performance Test Release",
            description = "Testing export performance from release snapshots",
            authentication = testAuthentication
        )

        val releaseDuration = java.time.Duration.between(releaseStartTime, Instant.now())
        println("Release created in ${releaseDuration.toMillis()}ms")

        // Verify setup
        val snapshotCount = snapshotRepository.countByReleaseId(testRelease.id!!)
        assertEquals(1000L, snapshotCount, "Should have 1000 snapshots for export test")
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
    fun `T039 - Export from release with 1000 requirements completes in under 3 seconds`() {
        // Act: Measure export execution time
        println("Starting export from release with 1000 requirement snapshots...")

        val executionTimeMs = measureTimeMillis {
            val response = requirementController.exportToExcel(testRelease.id!!)

            assertNotNull(response, "Export response should not be null")
            assertEquals(io.micronaut.http.HttpStatus.OK, response.status, "Export should return 200 OK")
        }

        // Assert: Performance target
        println("Export from release completed in ${executionTimeMs}ms")

        val targetTimeMs = 3000L
        assertTrue(
            executionTimeMs < targetTimeMs,
            "Export from release should complete in under ${targetTimeMs}ms. " +
                    "Actual: ${executionTimeMs}ms (${executionTimeMs - targetTimeMs}ms over budget)"
        )

        println("Export performance test completed successfully:")
        println("  - 1000 snapshots exported to Excel")
        println("  - Execution time: ${executionTimeMs}ms")
        println("  - Performance budget: ${targetTimeMs}ms (${((executionTimeMs.toDouble() / targetTimeMs) * 100).toInt()}% utilized)")
        println("  - Average: ${executionTimeMs / 1000.0}ms per requirement")
    }

    @Test
    fun `Performance - Export current requirements vs export from release comparison`() {
        // Measure export from current (live) requirements
        val currentExportTime = measureTimeMillis {
            requirementController.exportToExcel(null)
        }
        println("Export from current requirements: ${currentExportTime}ms")

        // Measure export from release snapshot
        val snapshotExportTime = measureTimeMillis {
            requirementController.exportToExcel(testRelease.id!!)
        }
        println("Export from release snapshot: ${snapshotExportTime}ms")

        // Both should complete within budget
        assertTrue(currentExportTime < 3000L, "Current export should complete in < 3000ms")
        assertTrue(snapshotExportTime < 3000L, "Snapshot export should complete in < 3000ms")

        // Snapshot export might be slightly slower due to conversion overhead,
        // but should be within reasonable margin (< 50% overhead)
        val overhead = (snapshotExportTime.toDouble() / currentExportTime) - 1.0
        assertTrue(
            overhead < 0.5,
            "Snapshot export overhead should be < 50%. " +
                    "Actual overhead: ${String.format("%.1f", overhead * 100)}%"
        )

        println("Export comparison:")
        println("  Current: ${currentExportTime}ms")
        println("  Snapshot: ${snapshotExportTime}ms")
        println("  Overhead: ${String.format("%.1f", overhead * 100)}%")
    }

    @Test
    fun `Performance - Export to Word from release completes within budget`() {
        // Word export typically has similar performance to Excel
        // Target: < 3 seconds for 1000 requirements
        println("Starting Word export from release with 1000 snapshots...")

        val executionTimeMs = measureTimeMillis {
            val response = requirementController.exportToDocx(testRelease.id!!)

            assertNotNull(response, "Word export response should not be null")
            assertEquals(io.micronaut.http.HttpStatus.OK, response.status, "Export should return 200 OK")
        }

        println("Word export from release completed in ${executionTimeMs}ms")

        val targetTimeMs = 3000L
        assertTrue(
            executionTimeMs < targetTimeMs,
            "Word export from release should complete in under ${targetTimeMs}ms. " +
                    "Actual: ${executionTimeMs}ms"
        )

        println("Word export performance:")
        println("  - 1000 snapshots exported to Word")
        println("  - Execution time: ${executionTimeMs}ms")
        println("  - Performance budget: ${targetTimeMs}ms (${((executionTimeMs.toDouble() / targetTimeMs) * 100).toInt()}% utilized)")
    }

    @Test
    fun `Performance - Multiple sequential exports do not degrade`() {
        // Verify that repeated exports maintain consistent performance
        // (tests for memory leaks, caching issues, etc.)
        val executionTimes = mutableListOf<Long>()

        println("Running 5 sequential exports from release...")

        for (i in 1..5) {
            val executionTime = measureTimeMillis {
                requirementController.exportToExcel(testRelease.id!!)
            }
            executionTimes.add(executionTime)
            println("Export $i: ${executionTime}ms")
        }

        // All exports should complete within budget
        executionTimes.forEach { time ->
            assertTrue(time < 3000L, "Each export should complete in < 3000ms. Found: ${time}ms")
        }

        // Performance should not degrade (last export should be within 50% of first)
        val firstExport = executionTimes.first()
        val lastExport = executionTimes.last()
        val degradation = (lastExport.toDouble() / firstExport) - 1.0

        assertTrue(
            degradation < 0.5,
            "Performance should not degrade significantly across sequential exports. " +
                    "Degradation: ${String.format("%.1f", degradation * 100)}%"
        )

        println("Sequential export performance:")
        println("  - Average: ${executionTimes.average().toLong()}ms")
        println("  - Min: ${executionTimes.minOrNull()}ms")
        println("  - Max: ${executionTimes.maxOrNull()}ms")
        println("  - Degradation: ${String.format("%.1f", degradation * 100)}%")
    }

    @Test
    fun `Performance - Export scales linearly with snapshot count`() {
        // Test export performance with different snapshot counts
        // Clean up and create new test cases
        snapshotRepository.deleteAll()
        releaseRepository.deleteAll()
        requirementRepository.deleteAll()

        val testCases = listOf(100, 250, 500)
        val executionTimes = mutableMapOf<Int, Long>()

        for (count in testCases) {
            // Create N requirements
            val requirements = (1..count).map { index ->
                Requirement(
                    shortreq = "SCALE-EXPORT-${count}-${index.toString().padStart(3, '0')}",
                    chapter = "Chapter ${(index % 5) + 1}",
                    norm = "ISO 27001",
                    details = "Export scaling test requirement $index for count $count",
                    motivation = "Test",
                    example = "Example",
                    usecase = "Use case"
                )
            }
            requirementRepository.saveAll(requirements)

            // Create release
            val release = releaseService.createRelease(
                version = "1.0.$count",
                name = "Export Scale Test $count",
                description = "Testing export with $count snapshots",
                authentication = testAuthentication
            )

            // Measure export time
            val executionTime = measureTimeMillis {
                requirementController.exportToExcel(release.id!!)
            }

            executionTimes[count] = executionTime
            println("Export with $count snapshots: ${executionTime}ms (${executionTime.toDouble() / count}ms per snapshot)")

            // Clean up for next iteration
            snapshotRepository.deleteAll()
            releaseRepository.deleteAll()
            requirementRepository.deleteAll()
        }

        // Verify roughly linear scaling
        val timePerSnapshot = executionTimes.mapValues { (count, time) -> time.toDouble() / count }

        println("\nExport scaling analysis:")
        timePerSnapshot.forEach { (count, timePerSnap) ->
            println("  $count snapshots: ${String.format("%.2f", timePerSnap)}ms per snapshot")
        }

        // The time per snapshot should be relatively stable
        val avgTimePerSnapshot = timePerSnapshot.values.average()
        timePerSnapshot.forEach { (count, timePerSnap) ->
            val variance = timePerSnap / avgTimePerSnapshot
            assertTrue(
                variance < 2.5,
                "Export time should scale linearly. " +
                        "For $count snapshots: ${String.format("%.2f", timePerSnap)}ms/snapshot " +
                        "(${String.format("%.1f", variance)}x average)"
            )
        }
    }
}
