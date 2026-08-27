package com.secman.service

import com.secman.repository.VulnerabilityRepository
import io.micronaut.data.model.Pageable
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.inject.Provider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The full `excepted` recompute used to be one `UPDATE` with no `WHERE`, holding exclusive locks on
 * every `vulnerability` row plus read locks on `vulnerability_exception` for 53–180s. Concurrent
 * writers (bulk exception delete/import, CrowdStrike imports) blocked until lock-wait-timeout;
 * `DeadlockRetry` absorbed the failure but the other operation still paid the timeout.
 *
 * It is now driven as bounded keyset chunks in independent short transactions. Chunking is only
 * worth anything if it covers **every row exactly once** — a gap silently leaves rows with a stale
 * `excepted` flag, which is a wrong-visibility bug, and an overlap wastes the work the chunking
 * exists to bound. These tests pin the traversal, not the SQL.
 *
 * ID prefix: AER-*
 */
class AsyncExceptionRecomputeChunkingTest {

    private val repository = mockk<VulnerabilityRepository>()

    private fun serviceOver(ids: List<Long>): Pair<AsyncExceptionRecompute, MutableList<Pair<Long, Long>>> {
        val service = AsyncExceptionRecompute(repository)

        // The bean self-injects a Provider so each chunk crosses the AOP proxy; in a unit test the
        // proxy is irrelevant, so hand it back itself.
        val providerField = AsyncExceptionRecompute::class.java.getDeclaredField("selfProvider")
        providerField.isAccessible = true
        providerField.set(service, Provider { service })

        // Emulate keyset paging over a sorted id set.
        val afterSlot = slot<Long>()
        val pageableSlot = slot<Pageable>()
        every { repository.findIdsAfter(capture(afterSlot), capture(pageableSlot)) } answers {
            ids.filter { it > afterSlot.captured }.take(pageableSlot.captured.size)
        }

        val ranges = mutableListOf<Pair<Long, Long>>()
        val fromSlot = slot<Long>()
        val toSlot = slot<Long>()
        every { repository.recomputeExceptedForIdRange(capture(fromSlot), capture(toSlot)) } answers {
            val from = fromSlot.captured
            val to = toSlot.captured
            ranges += from to to
            ids.count { it > from && it <= to }.toLong()
        }

        return service to ranges
    }

    /** Reconstruct which ids the emitted ranges actually covered, counting duplicates. */
    private fun covered(ranges: List<Pair<Long, Long>>, ids: List<Long>): List<Long> =
        ranges.flatMap { (from, to) -> ids.filter { it > from && it <= to } }

    @Test
    @DisplayName("AER-001: sparse ids are covered exactly once, with no gap and no overlap")
    fun sparseIdsCoveredExactlyOnce() {
        // Deliberately sparse and irregular: the CrowdStrike import is delete-then-insert per
        // asset, so real id sets look nothing like a dense 1..N range.
        val ids = listOf(3L, 7L, 8L, 100L, 101L, 5_000L, 999_999L, 1_000_000L)
        val (service, ranges) = serviceOver(ids)

        val updated = service.recomputeAllChunked("test")

        assertThat(covered(ranges, ids)).containsExactlyElementsOf(ids)
        assertThat(updated).isEqualTo(ids.size.toLong())
    }

    @Test
    @DisplayName("AER-002: more rows than one chunk produces several bounded ranges, still exact")
    fun multipleChunksRemainExact() {
        val ids = (1L..(AsyncExceptionRecompute.CHUNK_SIZE * 2L + 137L)).toList()
        val (service, ranges) = serviceOver(ids)

        val updated = service.recomputeAllChunked("test")

        assertThat(ranges).hasSize(3)
        assertThat(ranges.map { it.second - it.first }).allSatisfy {
            assertThat(it).isLessThanOrEqualTo(AsyncExceptionRecompute.CHUNK_SIZE.toLong())
        }
        assertThat(covered(ranges, ids)).containsExactlyElementsOf(ids)
        assertThat(updated).isEqualTo(ids.size.toLong())
    }

    /**
     * The boundary is half-open on the low side (`id > afterId`). If it were inclusive, every chunk
     * after the first would redo its predecessor's last row — cheap here, but it would mean the
     * traversal was not the exact partition the whole design depends on.
     */
    @Test
    @DisplayName("AER-003: chunk boundaries abut without re-processing the previous chunk's last row")
    fun boundariesAbutExactly() {
        val ids = (1L..(AsyncExceptionRecompute.CHUNK_SIZE * 2L)).toList()
        val (service, ranges) = serviceOver(ids)

        service.recomputeAllChunked("test")

        assertThat(ranges.first().first).isZero()
        ranges.zipWithNext().forEach { (prev, next) ->
            assertThat(next.first)
                .describedAs("chunk must resume exactly at the previous boundary")
                .isEqualTo(prev.second)
        }
    }

    @Test
    @DisplayName("AER-004: an empty table issues no update at all")
    fun emptyTableDoesNothing() {
        val (service, ranges) = serviceOver(emptyList())

        val updated = service.recomputeAllChunked("test")

        assertThat(ranges).isEmpty()
        assertThat(updated).isZero()
    }

    /**
     * The whole point of the change: no single transaction may span the table. A regression to the
     * unbounded statement would show up here as one range covering everything.
     */
    @Test
    @DisplayName("AER-005: no single range covers the whole table")
    fun noSingleRangeSpansTheTable() {
        val ids = (1L..(AsyncExceptionRecompute.CHUNK_SIZE * 3L)).toList()
        val (service, ranges) = serviceOver(ids)

        service.recomputeAllChunked("test")

        assertThat(ranges.size).isGreaterThan(1)
        assertThat(ranges).noneSatisfy { (from, to) ->
            assertThat(ids.count { it > from && it <= to }).isEqualTo(ids.size)
        }
    }
}
