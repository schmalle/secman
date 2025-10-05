package com.secman.performance

import com.secman.domain.Release
import com.secman.domain.Requirement
import com.secman.domain.User
import com.secman.domain.User.Role
import com.secman.repository.*
import com.secman.service.ReleaseService
import com.secman.service.RequirementComparisonService
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
 * Performance tests for Release Comparison functionality
 *
 * Tests non-functional requirements for comparison operations:
 * - T040: Compare 1000 vs 1000 requirements (target: < 1 second)
 *
 * Related to: Feature 011-i-want-to (Release-Based Requirement Version Management)
 */
@MicronautTest
class ComparisonPerformanceTest {

    @Inject
    lateinit var comparisonService: RequirementComparisonService

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
    private lateinit var release1: Release
    private lateinit var release2: Release

    @BeforeEach
    fun setUp() {
        // Clean up any existing test data
        snapshotRepository.deleteAll()
        releaseRepository.deleteAll()
        requirementRepository.deleteAll()

        // Create test user
        testUser = User(
            username = "comparisonperftest",
            email = "comparisonperftest@example.com",
            passwordHash = "hash",
            roles = mutableSetOf(Role.USER)
        )
        testUser = userRepository.save(testUser)

        // Create mock authentication
        testAuthentication = Authentication.build("comparisonperftest", mapOf("roles" to listOf("USER")))

        // Create 1000 requirements for first release
        println("Creating 1000 test requirements for comparison performance test...")
        val setupStartTime = Instant.now()

        val requirements1 = (1..1000).map { index ->
            Requirement(
                shortreq = "COMP-REQ-${index.toString().padStart(4, '0')}",
                chapter = "Chapter ${(index % 10) + 1}",
                norm = "ISO 27001:${2013 + (index % 10)}",
                details = "Comparison performance test requirement $index. " +
                        "This requirement contains realistic detail length to simulate production comparison workloads.",
                motivation = "Performance testing motivation for requirement $index",
                example = "Example implementation for requirement $index",
                usecase = "Performance testing use case $index"
            )
        }

        requirementRepository.saveAll(requirements1)

        val setupDuration = java.time.Duration.between(setupStartTime, Instant.now())
        println("Test requirements created in ${setupDuration.toMillis()}ms")

        // Create first release (1000 snapshots)
        println("Creating release 1.0.0 with 1000 snapshots...")
        val release1StartTime = Instant.now()

        release1 = releaseService.createRelease(
            version = "1.0.0",
            name = "Comparison Performance Test Release 1",
            description = "First release for comparison performance testing",
            authentication = testAuthentication
        )

        val release1Duration = java.time.Duration.between(release1StartTime, Instant.now())
        println("Release 1.0.0 created in ${release1Duration.toMillis()}ms")

        // Modify 30% of requirements, delete 5%, add 5% new ones
        println("Modifying requirements for second release...")
        val existingRequirements = requirementRepository.findAll().toMutableList()

        // Modify 300 requirements (30%)
        existingRequirements.take(300).forEach { req ->
            req.details = "${req.details} [MODIFIED for release 2.0.0]"
            req.motivation = "${req.motivation} [UPDATED]"
        }
        requirementRepository.updateAll(existingRequirements.take(300))

        // Delete 50 requirements (5%)
        val toDelete = existingRequirements.takeLast(50)
        requirementRepository.deleteAll(toDelete)

        // Add 50 new requirements (5%)
        val newRequirements = (1001..1050).map { index ->
            Requirement(
                shortreq = "COMP-REQ-NEW-${index.toString().padStart(4, '0')}",
                chapter = "Chapter ${(index % 10) + 1}",
                norm = "ISO 27001:2022",
                details = "New requirement added in release 2.0.0 - requirement $index",
                motivation = "New requirement motivation",
                example = "New example",
                usecase = "New use case"
            )
        }
        requirementRepository.saveAll(newRequirements)

        // Create second release (1000 snapshots with changes)
        println("Creating release 2.0.0 with 1000 modified snapshots...")
        val release2StartTime = Instant.now()

        release2 = releaseService.createRelease(
            version = "2.0.0",
            name = "Comparison Performance Test Release 2",
            description = "Second release for comparison performance testing",
            authentication = testAuthentication
        )

        val release2Duration = java.time.Duration.between(release2StartTime, Instant.now())
        println("Release 2.0.0 created in ${release2Duration.toMillis()}ms")

        // Verify setup
        val snapshot1Count = snapshotRepository.countByReleaseId(release1.id!!)
        val snapshot2Count = snapshotRepository.countByReleaseId(release2.id!!)

        println("Setup complete:")
        println("  - Release 1.0.0: $snapshot1Count snapshots")
        println("  - Release 2.0.0: $snapshot2Count snapshots")
        println("  - Expected changes: ~300 modified, ~50 deleted, ~50 added")
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
    fun `T040 - Compare 1000 vs 1000 requirements completes in under 1 second`() {
        // Act: Measure comparison execution time
        println("Starting comparison of 1000 vs 1000 requirement snapshots...")

        val executionTimeMs = measureTimeMillis {
            val result = comparisonService.compare(release1.id!!, release2.id!!)

            assertNotNull(result, "Comparison result should not be null")
            assertTrue(result.added.isNotEmpty() || result.deleted.isNotEmpty() || result.modified.isNotEmpty(),
                "Comparison should detect changes between releases")
        }

        // Assert: Performance target
        println("Comparison completed in ${executionTimeMs}ms")

        val targetTimeMs = 1000L
        assertTrue(
            executionTimeMs < targetTimeMs,
            "Comparison should complete in under ${targetTimeMs}ms. " +
                    "Actual: ${executionTimeMs}ms (${executionTimeMs - targetTimeMs}ms over budget)"
        )

        // Verify comparison results are correct
        val result = comparisonService.compare(release1.id!!, release2.id!!)

        println("Comparison performance test completed successfully:")
        println("  - 1000 vs 1000 requirements compared in ${executionTimeMs}ms")
        println("  - Performance budget: ${targetTimeMs}ms (${((executionTimeMs.toDouble() / targetTimeMs) * 100).toInt()}% utilized)")
        println("  - Added: ${result.added.size}")
        println("  - Deleted: ${result.deleted.size}")
        println("  - Modified: ${result.modified.size}")
        println("  - Unchanged: ${result.unchanged}")
        println("  - Average: ${executionTimeMs / 1000.0}ms per requirement pair")
    }

    @Test
    fun `Performance - Comparison with no changes is faster`() {
        // Create identical release to test comparison with 100% unchanged
        val release3 = releaseService.createRelease(
            version = "2.0.1",
            name = "Identical Release",
            description = "Identical to 2.0.0 for performance testing",
            authentication = testAuthentication
        )

        println("Comparing identical releases (100% unchanged)...")

        val executionTimeMs = measureTimeMillis {
            val result = comparisonService.compare(release2.id!!, release3.id!!)

            assertEquals(0, result.added.size, "No additions expected")
            assertEquals(0, result.deleted.size, "No deletions expected")
            assertEquals(0, result.modified.size, "No modifications expected")
            assertTrue(result.unchanged > 0, "Should have unchanged requirements")
        }

        println("Identical release comparison: ${executionTimeMs}ms")

        // Should be faster than target (< 1 second easily)
        assertTrue(executionTimeMs < 1000L, "Comparison with no changes should complete in < 1000ms")

        // Should be significantly faster than worst case (comparing with many changes)
        // Typically should be < 500ms for identical releases
        assertTrue(
            executionTimeMs < 500L,
            "Comparison with 100% unchanged should be very fast (< 500ms). Found: ${executionTimeMs}ms"
        )

        println("No-change comparison optimization verified: ${executionTimeMs}ms")
    }

    @Test
    fun `Performance - Multiple sequential comparisons maintain consistent performance`() {
        // Verify that repeated comparisons don't degrade (cache effects, memory leaks, etc.)
        val executionTimes = mutableListOf<Long>()

        println("Running 5 sequential comparisons...")

        for (i in 1..5) {
            val executionTime = measureTimeMillis {
                comparisonService.compare(release1.id!!, release2.id!!)
            }
            executionTimes.add(executionTime)
            println("Comparison $i: ${executionTime}ms")
        }

        // All comparisons should complete within budget
        executionTimes.forEach { time ->
            assertTrue(time < 1000L, "Each comparison should complete in < 1000ms. Found: ${time}ms")
        }

        // Performance should not degrade significantly
        val firstComparison = executionTimes.first()
        val lastComparison = executionTimes.last()
        val degradation = (lastComparison.toDouble() / firstComparison) - 1.0

        assertTrue(
            Math.abs(degradation) < 0.5,
            "Performance should remain consistent across sequential comparisons. " +
                    "Variation: ${String.format("%.1f", degradation * 100)}%"
        )

        println("Sequential comparison performance:")
        println("  - Average: ${executionTimes.average().toLong()}ms")
        println("  - Min: ${executionTimes.minOrNull()}ms")
        println("  - Max: ${executionTimes.maxOrNull()}ms")
        println("  - Variation: ${String.format("%.1f", degradation * 100)}%")
    }

    @Test
    fun `Performance - Comparison scales linearly with requirement count`() {
        // Test comparison performance with different requirement counts
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
                    shortreq = "SCALE-COMP-${count}-${index.toString().padStart(3, '0')}",
                    chapter = "Chapter ${(index % 5) + 1}",
                    norm = "ISO 27001",
                    details = "Comparison scaling test requirement $index for count $count",
                    motivation = "Test",
                    example = "Example",
                    usecase = "Use case"
                )
            }
            requirementRepository.saveAll(requirements)

            // Create release 1
            val rel1 = releaseService.createRelease(
                version = "1.0.$count",
                name = "Comparison Scale Test 1",
                description = "Testing with $count snapshots",
                authentication = testAuthentication
            )

            // Modify 30% of requirements
            val existingReqs = requirementRepository.findAll().toMutableList()
            val modifyCount = (count * 0.3).toInt()
            existingReqs.take(modifyCount).forEach { req ->
                req.details = "${req.details} [MODIFIED]"
            }
            requirementRepository.updateAll(existingReqs.take(modifyCount))

            // Create release 2
            val rel2 = releaseService.createRelease(
                version = "2.0.$count",
                name = "Comparison Scale Test 2",
                description = "Testing with $count modified snapshots",
                authentication = testAuthentication
            )

            // Measure comparison time
            val executionTime = measureTimeMillis {
                comparisonService.compare(rel1.id!!, rel2.id!!)
            }

            executionTimes[count] = executionTime
            println("Comparison with $count vs $count: ${executionTime}ms (${executionTime.toDouble() / count}ms per requirement)")

            // Clean up for next iteration
            snapshotRepository.deleteAll()
            releaseRepository.deleteAll()
            requirementRepository.deleteAll()
        }

        // Verify roughly linear scaling
        val timePerRequirement = executionTimes.mapValues { (count, time) -> time.toDouble() / count }

        println("\nComparison scaling analysis:")
        timePerRequirement.forEach { (count, timePerReq) ->
            println("  $count vs $count: ${String.format("%.3f", timePerReq)}ms per requirement")
        }

        // The time per requirement should be relatively stable (within 2x variance)
        val avgTimePerReq = timePerRequirement.values.average()
        timePerRequirement.forEach { (count, timePerReq) ->
            val variance = timePerReq / avgTimePerReq
            assertTrue(
                variance < 2.0,
                "Comparison time should scale linearly. " +
                        "For $count requirements: ${String.format("%.3f", timePerReq)}ms/req " +
                        "(${String.format("%.1f", variance)}x average)"
            )
        }
    }

    @Test
    fun `Performance - Field-level diff computation is efficient`() {
        // Test the overhead of field-level comparison
        // Compare releases with 100% modified requirements (worst case for field diff)

        // Clean up and create test case
        snapshotRepository.deleteAll()
        releaseRepository.deleteAll()
        requirementRepository.deleteAll()

        // Create 500 requirements
        val requirements = (1..500).map { index ->
            Requirement(
                shortreq = "FIELD-DIFF-${index.toString().padStart(3, '0')}",
                chapter = "Chapter $index",
                norm = "ISO 27001",
                details = "Original details for requirement $index",
                motivation = "Original motivation $index",
                example = "Original example $index",
                usecase = "Original use case $index"
            )
        }
        requirementRepository.saveAll(requirements)

        // Create release 1
        val rel1 = releaseService.createRelease(
            version = "1.0.0",
            name = "Field Diff Test 1",
            description = "First release",
            authentication = testAuthentication
        )

        // Modify ALL fields of ALL requirements (worst case)
        val allReqs = requirementRepository.findAll().toMutableList()
        allReqs.forEach { req ->
            req.chapter = "${req.chapter} [MODIFIED]"
            req.details = "${req.details} [MODIFIED]"
            req.motivation = "${req.motivation} [MODIFIED]"
            req.example = "${req.example} [MODIFIED]"
            req.usecase = "${req.usecase} [MODIFIED]"
        }
        requirementRepository.updateAll(allReqs)

        // Create release 2
        val rel2 = releaseService.createRelease(
            version = "2.0.0",
            name = "Field Diff Test 2",
            description = "Second release with all fields modified",
            authentication = testAuthentication
        )

        // Measure comparison time for worst case (all requirements modified, all fields changed)
        println("Comparing 500 vs 500 with 100% field modifications...")

        val executionTimeMs = measureTimeMillis {
            val result = comparisonService.compare(rel1.id!!, rel2.id!!)

            // Verify all requirements are marked as modified
            assertEquals(500, result.modified.size, "All 500 requirements should be marked as modified")

            // Verify field changes are captured
            result.modified.forEach { diff ->
                assertTrue(diff.changes.isNotEmpty(), "Field changes should be captured for each modified requirement")
            }
        }

        println("Field-level diff computation: ${executionTimeMs}ms for 500 requirements")
        println("Average: ${executionTimeMs / 500.0}ms per requirement")

        // Should still complete within budget even for worst case
        assertTrue(executionTimeMs < 1000L, "Field-level diff should complete in < 1000ms even for worst case")

        val result = comparisonService.compare(rel1.id!!, rel2.id!!)
        val totalFieldChanges = result.modified.sumOf { it.changes.size }

        println("Field diff performance:")
        println("  - Total requirements compared: 500")
        println("  - Total modified: ${result.modified.size}")
        println("  - Total field changes detected: $totalFieldChanges")
        println("  - Average field changes per requirement: ${totalFieldChanges.toDouble() / result.modified.size}")
        println("  - Execution time: ${executionTimeMs}ms")
    }
}
