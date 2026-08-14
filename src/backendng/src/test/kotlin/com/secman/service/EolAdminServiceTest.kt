package com.secman.service

import com.secman.domain.EolSyncRun
import com.secman.dto.EolSyncRequest
import com.secman.repository.EolSyncRunRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

/**
 * A full EOL sync takes minutes — measured ~150s of catalogue download plus
 * ~50s of scan against a 2,000-system estate. Served synchronously it outlived
 * the 60s read timeout that Apache 2.4 and nginx both apply by default, so the
 * caller got a 504 from the proxy while the work ran to completion unseen.
 *
 * [EolAdminService] therefore records the run, returns its handle, and does the
 * work on a background thread. That trade buys a new hazard: the HTTP request
 * no longer serializes runs, so the single-run guard and the stale-run reclaim
 * are load-bearing rather than cosmetic, and they are pinned here.
 *
 * ID prefix: EAS-*
 */
class EolAdminServiceTest {

    private val catalogSyncService: EolCatalogSyncService = mockk(relaxed = true)
    private val scanService: EolScanService = mockk(relaxed = true)
    private val repository: EolSyncRunRepository = mockk(relaxed = true)

    /**
     * The self-provider is field-injected so `@Async` applies through the AOP
     * proxy (same reflective plant as [MaterializedViewRefreshServiceTest]).
     *
     * [asyncWorker] matters for correctness of the test, not just convenience:
     * with the service pointed at itself, `selfProvider.get().executeSyncAsync`
     * runs *inline*, because there is no proxy in a unit test. The row would
     * then already be terminal by the time `startSync` returned and the test
     * would assert on a state production never exposes. Planting a separate
     * mock as the worker models the real async hop — dispatch happens, the work
     * does not run on this thread.
     */
    private fun buildService(asyncWorker: EolAdminService? = null): EolAdminService {
        val built = EolAdminService(catalogSyncService, scanService, repository)
        val worker = asyncWorker ?: built
        EolAdminService::class.java.getDeclaredField("selfProvider").apply {
            isAccessible = true
            set(built, jakarta.inject.Provider { worker })
        }
        return built
    }

    /**
     * `JpaRepository.update` is generic (`<S : E> S update(S)`), so its return
     * type is erased to Object. A relaxed mock hands back a bare Object and the
     * Kotlin call site's checkcast to [EolSyncRun] then fails. Echo the argument
     * back, as the real repository does.
     */
    @BeforeEach
    fun stubUpdate() {
        every { repository.update(any<EolSyncRun>()) } answers { firstArg() }
    }

    private fun noRunning() {
        every { repository.findByStatusOrderByIdAsc(EolAdminService.STATUS_RUNNING) } returns emptyList()
    }

    private fun catalogResult(
        requested: Int = 2,
        synced: Int = 2,
        failed: List<String> = emptyList(),
        error: String? = null
    ) = EolCatalogSyncService.CatalogSyncResult(
        sourceKey = "endoflife.date",
        productsRequested = requested,
        productsSynced = synced,
        releasesSynced = 40,
        productsFailed = failed,
        errorSummary = error
    )

    private fun scanResult(error: String? = null) = EolScanService.ScanResult(
        scanRunId = "scan-1",
        horizonMonths = 12,
        catalogProducts = 2,
        assetsScanned = 7,
        installedProductsScanned = 900,
        repositoriesScanned = 3,
        repositoryComponentsScanned = 30,
        findingsWritten = 11,
        eolFindings = 8,
        approachingFindings = 3,
        findingsRemoved = 11,
        errorSummary = error
    )

    private fun runningRow(runId: String) = EolSyncRun(
        id = 1L,
        runId = runId,
        triggeredBy = "admin",
        status = EolAdminService.STATUS_RUNNING
    )

    // ------------------------------------------------------------- dispatch

    @Test
    @DisplayName("EAS-001: startSync records a RUNNING run, dispatches the work, and returns the handle")
    fun startSyncReturnsRunningHandle() {
        noRunning()
        val saved = slot<EolSyncRun>()
        every { repository.save(capture(saved)) } answers { saved.captured.also { it.id = 1L } }
        val worker: EolAdminService = mockk(relaxed = true)

        val response = buildService(worker).startSync(EolSyncRequest(products = listOf("ubuntu")), "admin")

        assertThat(response.runId).isNotBlank()
        assertThat(response.status).isEqualTo(EolAdminService.STATUS_RUNNING)
        assertThat(saved.captured.status).isEqualTo(EolAdminService.STATUS_RUNNING)
        assertThat(saved.captured.triggeredBy).isEqualTo("admin")
        assertThat(saved.captured.productsRequested).isEqualTo(1)
        // The handle the caller polls is the row that was persisted.
        assertThat(saved.captured.runId).isEqualTo(response.runId)
        verify(exactly = 1) { worker.executeSyncAsync(response.runId, any()) }
        // The request thread does none of the work — that is the entire point.
        verify(exactly = 0) { catalogSyncService.sync(any()) }
    }

    @Test
    @DisplayName("EAS-002: CR/LF in the actor is stripped before it reaches the audit row")
    fun actorIsSanitized() {
        noRunning()
        val saved = slot<EolSyncRun>()
        every { repository.save(capture(saved)) } answers { saved.captured.also { it.id = 1L } }

        buildService(mockk(relaxed = true)).startSync(EolSyncRequest(), "admin\r\nINJECTED")

        assertThat(saved.captured.triggeredBy).doesNotContain("\r").doesNotContain("\n")
    }

    // ---------------------------------------------------------- concurrency

    @Test
    @DisplayName("EAS-003: a trigger arriving while a run is live defers to it and starts nothing new")
    fun concurrentTriggerDefersToLiveRun() {
        val live = EolSyncRun(
            id = 42L,
            runId = "11111111-1111-1111-1111-111111111111",
            triggeredBy = "someone-else",
            status = EolAdminService.STATUS_RUNNING,
            startedAt = Instant.now()
        )
        every { repository.findByStatusOrderByIdAsc(EolAdminService.STATUS_RUNNING) } returns listOf(live)
        val worker: EolAdminService = mockk(relaxed = true)

        val response = buildService(worker).startSync(EolSyncRequest(), "admin")

        assertThat(response.runId).isEqualTo(live.runId)
        assertThat(response.status).isEqualTo(EolAdminService.STATUS_RUNNING)
        // Nothing new was persisted or dispatched. Without this guard, going
        // async lets an admin stack N full-inventory rescans with N requests —
        // the synchronous version was self-limiting only because the caller had
        // to wait out the whole run.
        verify(exactly = 0) { repository.save(any<EolSyncRun>()) }
        verify(exactly = 0) { worker.executeSyncAsync(any(), any()) }
    }

    @Test
    @DisplayName("EAS-004: the loser of a concurrent insert retires itself and defers to the winner")
    fun raceLoserRetiresItself() {
        val winner = EolSyncRun(
            id = 5L,
            runId = "99999999-9999-9999-9999-999999999999",
            triggeredBy = "first",
            status = EolAdminService.STATUS_RUNNING
        )
        val saved = slot<EolSyncRun>()
        every { repository.save(capture(saved)) } answers { saved.captured.also { it.id = 9L } }
        // startSync reads the RUNNING set three times: the stale reclaim, the
        // pre-check, then the post-insert re-check. Empty for the first two, so
        // this trigger genuinely inserts; the winner appears only on the third —
        // the exact interleaving the re-check exists to catch, since the read
        // and the save are not atomic with each other.
        every {
            repository.findByStatusOrderByIdAsc(EolAdminService.STATUS_RUNNING)
        } returnsMany listOf(emptyList(), emptyList(), listOf(winner))
        val worker: EolAdminService = mockk(relaxed = true)

        val response = buildService(worker).startSync(EolSyncRequest(), "admin")

        assertThat(response.runId).isEqualTo(winner.runId)
        assertThat(saved.captured.status).isEqualTo(EolAdminService.STATUS_FAILED)
        assertThat(saved.captured.finishedAt).isNotNull()
        verify(exactly = 0) { worker.executeSyncAsync(any(), any()) }
    }

    @Test
    @DisplayName("EAS-005: a RUNNING row whose worker is gone is reclaimed so syncing is not wedged forever")
    fun staleRunningRowIsReclaimed() {
        val stale = EolSyncRun(
            id = 7L,
            runId = "22222222-2222-2222-2222-222222222222",
            triggeredBy = "admin",
            status = EolAdminService.STATUS_RUNNING,
            startedAt = Instant.now().minus(EolAdminService.STALE_AFTER).minusSeconds(60)
        )
        // The stale row on the reclaim pass, then nothing running.
        every {
            repository.findByStatusOrderByIdAsc(EolAdminService.STATUS_RUNNING)
        } returnsMany listOf(listOf(stale), emptyList(), emptyList())
        val saved = slot<EolSyncRun>()
        every { repository.save(capture(saved)) } answers { saved.captured.also { it.id = 8L } }

        val response = buildService(mockk(relaxed = true)).startSync(EolSyncRequest(), "admin")

        assertThat(stale.status).isEqualTo(EolAdminService.STATUS_FAILED)
        assertThat(stale.finishedAt).isNotNull()
        // A fresh run got through rather than being blocked by the orphan.
        assertThat(response.runId).isNotEqualTo(stale.runId)
        assertThat(response.status).isEqualTo(EolAdminService.STATUS_RUNNING)
    }

    @Test
    @DisplayName("EAS-006: a RUNNING row still inside the stale window is left alone")
    fun freshRunningRowIsNotReclaimed() {
        val live = EolSyncRun(
            id = 7L,
            runId = "88888888-8888-8888-8888-888888888888",
            triggeredBy = "admin",
            status = EolAdminService.STATUS_RUNNING,
            startedAt = Instant.now().minusSeconds(120)
        )
        every { repository.findByStatusOrderByIdAsc(EolAdminService.STATUS_RUNNING) } returns listOf(live)

        val response = buildService(mockk(relaxed = true)).startSync(EolSyncRequest(), "admin")

        // A four-minute run must never be mistaken for an orphan.
        assertThat(live.status).isEqualTo(EolAdminService.STATUS_RUNNING)
        assertThat(response.runId).isEqualTo(live.runId)
    }

    // -------------------------------------------------------------- worker

    @Test
    @DisplayName("EAS-007: the async worker writes a terminal status and the full counts")
    fun workerWritesTerminalStatusAndCounts() {
        val run = runningRow("33333333-3333-3333-3333-333333333333")
        every { repository.findByRunId(run.runId) } returns Optional.of(run)
        every { catalogSyncService.sync(any()) } returns catalogResult()
        every { scanService.scan(any()) } returns scanResult()

        buildService().executeSyncAsync(run.runId, EolSyncRequest())

        assertThat(run.status).isEqualTo(EolAdminService.STATUS_SUCCESS)
        assertThat(run.finishedAt).isNotNull()
        assertThat(run.productsSynced).isEqualTo(2)
        assertThat(run.findingsWritten).isEqualTo(11)
        // eolFindings / approachingFindings are persisted rather than derived: a
        // polling client asks after the worker's in-memory result is gone.
        assertThat(run.eolFindings).isEqualTo(8)
        assertThat(run.approachingFindings).isEqualTo(3)
    }

    @Test
    @DisplayName("EAS-008: when both stages fail the run ends FAILED, never left RUNNING")
    fun bothStagesFailingEndsFailed() {
        val run = runningRow("44444444-4444-4444-4444-444444444444")
        every { repository.findByRunId(run.runId) } returns Optional.of(run)
        every { catalogSyncService.sync(any()) } throws IllegalStateException("upstream down")
        every { scanService.scan(any()) } throws IllegalStateException("db gone")

        buildService().executeSyncAsync(run.runId, EolSyncRequest())

        assertThat(run.status).isEqualTo(EolAdminService.STATUS_FAILED)
        assertThat(run.finishedAt).isNotNull()
        // Generic summary only — no upstream body or stack trace (§A05).
        assertThat(run.errorSummary).doesNotContain("upstream down").doesNotContain("db gone")
    }

    @Test
    @DisplayName("EAS-009: an unexpected failure inside the worker still terminates the run")
    fun unexpectedFailureStillTerminates() {
        val run = runningRow("55555555-5555-5555-5555-555555555555")
        every { repository.findByRunId(run.runId) } returns Optional.of(run)
        every { catalogSyncService.sync(any()) } returns catalogResult()
        every { scanService.scan(any()) } returns scanResult()
        // The first update — the one execute() performs — blows up. Without the
        // outer guard the row would stay RUNNING and block every later sync
        // until the stale window elapsed.
        every { repository.update(any<EolSyncRun>()) } throws RuntimeException("write failed") andThen run

        buildService().executeSyncAsync(run.runId, EolSyncRequest())

        assertThat(run.status).isEqualTo(EolAdminService.STATUS_FAILED)
        assertThat(run.finishedAt).isNotNull()
    }

    @Test
    @DisplayName("EAS-010: a run that vanished before execution is logged, not thrown out of the worker")
    fun missingRunDoesNotThrow() {
        every { repository.findByRunId(any()) } returns Optional.empty()

        buildService().executeSyncAsync("66666666-6666-6666-6666-666666666666", EolSyncRequest())

        verify(exactly = 0) { catalogSyncService.sync(any()) }
    }

    // --------------------------------------------------------------- polling

    @Test
    @DisplayName("EAS-011: an unknown run handle resolves to null so the controller can 404 generically")
    fun unknownRunIdResolvesToNull() {
        every { repository.findByRunId(any()) } returns Optional.empty()

        assertThat(buildService().findRun("77777777-7777-7777-7777-777777777777")).isNull()
    }

    @Test
    @DisplayName("EAS-012: failed product keys round-trip through the delimited column")
    fun failedProductsRoundTrip() {
        val run = runningRow("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        every { repository.findByRunId(run.runId) } returns Optional.of(run)
        every { catalogSyncService.sync(any()) } returns
            catalogResult(failed = listOf("ubuntu", "rhel"), error = "2 products failed")
        every { scanService.scan(any()) } returns scanResult()

        val service = buildService()
        service.executeSyncAsync(run.runId, EolSyncRequest())

        assertThat(run.productsFailed).isEqualTo("ubuntu,rhel")
        // A partial download is PARTIAL, not FAILED — the scan still ran.
        assertThat(run.status).isEqualTo(EolAdminService.STATUS_PARTIAL)
        assertThat(service.findRun(run.runId)?.productsFailed).containsExactly("ubuntu", "rhel")
    }

    @Test
    @DisplayName("EAS-013: no failed products stays an empty list, not a list holding one blank string")
    fun emptyFailedProductsStaysEmpty() {
        val run = runningRow("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        every { repository.findByRunId(run.runId) } returns Optional.of(run)
        every { catalogSyncService.sync(any()) } returns catalogResult()
        every { scanService.scan(any()) } returns scanResult()

        val service = buildService()
        service.executeSyncAsync(run.runId, EolSyncRequest())

        assertThat(run.productsFailed).isNull()
        assertThat(service.findRun(run.runId)?.productsFailed).isEmpty()
    }
}
