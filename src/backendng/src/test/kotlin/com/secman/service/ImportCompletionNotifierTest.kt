package com.secman.service

import com.secman.domain.NotificationEventType
import com.secman.event.ChatNotificationEvent
import io.micronaut.context.event.ApplicationEventPublisher
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The CrowdStrike side of this notifier exists because a full CLI import arrives as ~94
 * sub-batch requests with no well-defined last batch. These tests pin the two properties
 * that matters: many batches produce exactly ONE event carrying the run's totals, and
 * nothing is published while the import is still going.
 */
class ImportCompletionNotifierTest {

    private val publisher = mockk<ApplicationEventPublisher<ChatNotificationEvent>>(relaxed = true)

    /** Quiet period 0 → the very next sweep treats the run as finished. */
    private fun notifier(quietPeriodSeconds: Long = 0) =
        ImportCompletionNotifier(publisher, quietPeriodSeconds)

    private fun recordBatch(
        n: ImportCompletionNotifier,
        servers: Int = 1,
        imported: Int = 10,
        errors: Int = 0
    ) = n.recordCrowdStrikeBatch(
        serversProcessed = servers,
        serversCreated = 0,
        serversUpdated = servers,
        vulnerabilitiesImported = imported,
        vulnerabilitiesSkipped = 0,
        errorCount = errors,
        triggeredBy = "cli"
    )

    @Test
    fun `many CrowdStrike sub-batches publish exactly one aggregated event`() {
        val n = notifier()
        repeat(94) { recordBatch(n, servers = 20, imported = 100) }

        n.sweepCrowdStrikeRun()

        val captured = slot<ChatNotificationEvent>()
        verify(exactly = 1) { publisher.publishEvent(capture(captured)) }
        assertThat(captured.captured.eventType).isEqualTo(NotificationEventType.CROWDSTRIKE_REPORT_COMPLETED)
        assertThat(captured.captured.summary).contains("1880 server(s) processed")
        assertThat(captured.captured.fields)
            .anySatisfy { assertThat(it.label).isEqualTo("Vulnerabilities imported"); assertThat(it.value).isEqualTo("9400") }
    }

    @Test
    fun `publishes nothing while the import is still running`() {
        val n = notifier(quietPeriodSeconds = 3600)
        recordBatch(n)

        n.sweepCrowdStrikeRun()

        verify(exactly = 0) { publisher.publishEvent(any()) }
    }

    @Test
    fun `publishes nothing when no import has run`() {
        notifier().sweepCrowdStrikeRun()

        verify(exactly = 0) { publisher.publishEvent(any()) }
    }

    @Test
    fun `a second import run publishes a second event with fresh totals`() {
        val n = notifier()
        recordBatch(n, servers = 5, imported = 50)
        n.sweepCrowdStrikeRun()
        recordBatch(n, servers = 2, imported = 20)
        n.sweepCrowdStrikeRun()

        val captured = mutableListOf<ChatNotificationEvent>()
        verify(exactly = 2) { publisher.publishEvent(capture(captured)) }
        // Totals reset between runs — the second event must not include the first run's counts.
        assertThat(captured[1].summary).contains("2 server(s) processed")
    }

    @Test
    fun `includes an error count only when the run had errors`() {
        val clean = notifier()
        recordBatch(clean, errors = 0)
        clean.sweepCrowdStrikeRun()

        val withErrors = notifier()
        recordBatch(withErrors, errors = 3)
        withErrors.sweepCrowdStrikeRun()

        val captured = mutableListOf<ChatNotificationEvent>()
        verify(exactly = 2) { publisher.publishEvent(capture(captured)) }
        assertThat(captured[0].fields.map { it.label }).doesNotContain("Errors")
        assertThat(captured[1].fields).anySatisfy {
            assertThat(it.label).isEqualTo("Errors")
            assertThat(it.value).isEqualTo("3")
        }
    }

    @Test
    fun `AWS account import publishes immediately with the new account ids`() {
        notifier().awsAccountImportCompleted(
            source = "CSV upload (accounts.csv)",
            triggeredBy = "admin",
            processed = 10,
            imported = 8,
            skipped = 2,
            errorCount = 0,
            newAccountIds = listOf("111111111111", "222222222222")
        )

        val captured = slot<ChatNotificationEvent>()
        verify(exactly = 1) { publisher.publishEvent(capture(captured)) }
        assertThat(captured.captured.eventType).isEqualTo(NotificationEventType.AWS_ACCOUNT_IMPORT_COMPLETED)
        assertThat(captured.captured.summary).contains("2 new AWS account(s)")
        assertThat(captured.captured.fields).anySatisfy {
            assertThat(it.label).isEqualTo("Account IDs")
            assertThat(it.value).isEqualTo("111111111111, 222222222222")
        }
    }

    @Test
    fun `AWS account import caps the inline account list`() {
        // An initial load can introduce hundreds of accounts; chat clients truncate long
        // messages, which would hide the counts rendered above the list.
        notifier().awsAccountImportCompleted(
            source = "Bulk import",
            triggeredBy = null,
            processed = 30,
            imported = 30,
            skipped = 0,
            errorCount = 0,
            newAccountIds = (1..30).map { "%012d".format(it) }
        )

        val captured = slot<ChatNotificationEvent>()
        verify(exactly = 1) { publisher.publishEvent(capture(captured)) }
        val ids = captured.captured.fields.first { it.label == "Account IDs" }.value
        assertThat(ids).contains("(+10 more)")
        assertThat(ids.split(", ")).hasSizeLessThanOrEqualTo(21)
    }

    @Test
    fun `AWS account import with no new accounts says so`() {
        notifier().awsAccountImportCompleted(
            source = "Bulk import",
            triggeredBy = null,
            processed = 5,
            imported = 5,
            skipped = 0,
            errorCount = 0
        )

        val captured = slot<ChatNotificationEvent>()
        verify(exactly = 1) { publisher.publishEvent(capture(captured)) }
        assertThat(captured.captured.summary).contains("no previously unknown AWS accounts")
        assertThat(captured.captured.fields.map { it.label }).doesNotContain("Account IDs")
    }
}
