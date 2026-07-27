package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Unit tests for SendAccountFindingAgeReportCommand (Task 6).
 *
 * Exit-code-2 assertions and the error-message-surfacing fix (Finding 1 from code review)
 * exercise run() end-to-end via the `exitAction` test seam instead of the real System.exit:
 * JDK 25 has permanently disabled SecurityManager (JEP 486), so intercepting a genuine
 * System.exit call from within the test JVM is not possible; no existing CLI command test
 * in this codebase exercises a System.exit path for the same reason. `exitAction` is a
 * production no-op change (defaults to System.exit) that only tests override.
 */
class SendAccountFindingAgeReportCommandTest {

    private fun successResult(accountCount: Int = 3, status: String = "SUCCESS") = mapOf(
        "status" to status,
        "recipientCount" to 2,
        "emailsSent" to 2,
        "emailsFailed" to 0,
        "recipients" to listOf("admin1@example.test", "admin2@example.test"),
        "failedRecipients" to emptyList<String>(),
        "accountCount" to accountCount
    )

    private fun command(cliHttpClient: CliHttpClient): SendAccountFindingAgeReportCommand {
        val cmd = SendAccountFindingAgeReportCommand()
        cmd.cliHttpClient = cliHttpClient
        cmd.username = "admin"
        cmd.password = "secret"
        cmd.backendUrl = "https://secman.example.test"
        return cmd
    }

    /**
     * Captures both stdout and stderr into one string. The command prints its normal
     * summary via println (stdout) but error messages via System.err.println, so tests
     * that assert on error text need both streams merged.
     */
    private fun captureStdout(block: () -> Unit): String {
        val originalOut = System.out
        val originalErr = System.err
        val output = ByteArrayOutputStream()
        val combined = PrintStream(output)
        System.setOut(combined)
        System.setErr(combined)
        try {
            block()
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        return output.toString()
    }

    // --- (a) outgoing request body shape ---

    @Test
    fun `posts limit dryRun and verbose to the cli endpoint`() {
        val cliHttpClient = mockk<CliHttpClient>()
        val command = command(cliHttpClient).apply {
            limit = 25
            dryRun = true
            verbose = true
        }

        every { cliHttpClient.authenticate("admin", "secret", "https://secman.example.test") } returns "token"
        val bodySlot = slot<Map<String, Any>>()
        every {
            cliHttpClient.postMapWithStatus(
                "https://secman.example.test/api/cli/account-finding-age-report/send",
                capture(bodySlot),
                "token"
            )
        } returns (200 to successResult(status = "DRY_RUN"))

        command.run()

        verify(exactly = 1) {
            cliHttpClient.postMapWithStatus(
                "https://secman.example.test/api/cli/account-finding-age-report/send",
                any(),
                "token"
            )
        }
        assertEquals(25, bodySlot.captured["limit"])
        assertEquals(true, bodySlot.captured["dryRun"])
        assertEquals(true, bodySlot.captured["verbose"])
    }

    @Test
    fun `limit defaults to 10 when not set`() {
        val cliHttpClient = mockk<CliHttpClient>()
        val command = command(cliHttpClient)

        every { cliHttpClient.authenticate(any(), any(), any()) } returns "token"
        val bodySlot = slot<Map<String, Any>>()
        every { cliHttpClient.postMapWithStatus(any(), capture(bodySlot), any()) } returns (200 to successResult())

        command.run()

        assertEquals(10, bodySlot.captured["limit"])
        assertEquals(false, bodySlot.captured["dryRun"])
        assertEquals(false, bodySlot.captured["verbose"])
    }

    // --- (b) exit-code-2 mapping for FAILURE and PARTIAL_FAILURE ---
    // Highest-value case: this command deliberately diverges from the sibling
    // SendAdminSummaryCommand's exit code 1. determineExitCode is the single source of
    // truth for that mapping; asserting it directly guards against a future copy-paste
    // refactor silently reverting it.

    @Test
    fun `determineExitCode maps FAILURE to 2`() {
        val command = SendAccountFindingAgeReportCommand()
        assertEquals(2, command.determineExitCode("FAILURE"))
    }

    @Test
    fun `determineExitCode maps PARTIAL_FAILURE to 2`() {
        val command = SendAccountFindingAgeReportCommand()
        assertEquals(2, command.determineExitCode("PARTIAL_FAILURE"))
    }

    @Test
    fun `determineExitCode does not exit for SUCCESS or DRY_RUN`() {
        val command = SendAccountFindingAgeReportCommand()
        assertNull(command.determineExitCode("SUCCESS"))
        assertNull(command.determineExitCode("DRY_RUN"))
    }

    @Test
    fun `run invokes exitAction with 2 on backend FAILURE status`() {
        val cliHttpClient = mockk<CliHttpClient>()
        val command = command(cliHttpClient)
        var capturedExitCode: Int? = null
        command.exitAction = { code -> capturedExitCode = code }

        every { cliHttpClient.authenticate(any(), any(), any()) } returns "token"
        every { cliHttpClient.postMapWithStatus(any(), any(), any()) } returns
            (200 to successResult(accountCount = 3, status = "FAILURE"))

        captureStdout { command.run() }

        assertEquals(2, capturedExitCode)
    }

    @Test
    fun `run invokes exitAction with 2 on backend PARTIAL_FAILURE status`() {
        val cliHttpClient = mockk<CliHttpClient>()
        val command = command(cliHttpClient)
        var capturedExitCode: Int? = null
        command.exitAction = { code -> capturedExitCode = code }

        every { cliHttpClient.authenticate(any(), any(), any()) } returns "token"
        every { cliHttpClient.postMapWithStatus(any(), any(), any()) } returns
            (200 to successResult(accountCount = 3, status = "PARTIAL_FAILURE"))

        captureStdout { command.run() }

        assertEquals(2, capturedExitCode)
    }

    @Test
    fun `run does not invoke exitAction on SUCCESS`() {
        val cliHttpClient = mockk<CliHttpClient>()
        val command = command(cliHttpClient)
        var capturedExitCode: Int? = null
        command.exitAction = { code -> capturedExitCode = code }

        every { cliHttpClient.authenticate(any(), any(), any()) } returns "token"
        every { cliHttpClient.postMapWithStatus(any(), any(), any()) } returns (200 to successResult())

        captureStdout { command.run() }

        assertNull(capturedExitCode)
    }

    // --- (c) accountCount == 0 prints the explicit "no accounts" line ---

    @Test
    fun `prints explicit message when accountCount is zero`() {
        val cliHttpClient = mockk<CliHttpClient>()
        val command = command(cliHttpClient)

        every { cliHttpClient.authenticate(any(), any(), any()) } returns "token"
        every { cliHttpClient.postMapWithStatus(any(), any(), any()) } returns
            (200 to successResult(accountCount = 0))

        val output = captureStdout { command.run() }

        assertTrue(output.contains("Accounts in report: 0"))
        assertTrue(output.contains("No accounts with open findings - nothing to send"))
    }

    @Test
    fun `does not print the empty-report line when accounts exist`() {
        val cliHttpClient = mockk<CliHttpClient>()
        val command = command(cliHttpClient)

        every { cliHttpClient.authenticate(any(), any(), any()) } returns "token"
        every { cliHttpClient.postMapWithStatus(any(), any(), any()) } returns
            (200 to successResult(accountCount = 3))

        val output = captureStdout { command.run() }

        assertTrue(output.contains("Accounts in report: 3"))
        assertTrue(!output.contains("No accounts with open findings - nothing to send"))
    }

    // --- (d) Finding 1: a 400 with a message body surfaces that message, not
    // "no response from server" ---

    @Test
    fun `describeError surfaces the body message field for a validation 400`() {
        val command = SendAccountFindingAgeReportCommand()
        val message = command.describeError(
            400,
            mapOf("message" to "limit must be between 1 and 50, was 999"),
            "https://secman.example.test"
        )
        assertEquals("limit must be between 1 and 50, was 999", message)
    }

    @Test
    fun `describeError distinguishes a genuine connection failure`() {
        val command = SendAccountFindingAgeReportCommand()
        val message = command.describeError(-1, null, "https://secman.example.test")
        assertTrue(message.contains("connection error"))
        assertTrue(!message.contains("no response from server"))
    }

    @Test
    fun `run prints the backend validation message for an invalid limit and exits 2`() {
        val cliHttpClient = mockk<CliHttpClient>()
        val command = command(cliHttpClient).apply { limit = 999 }
        var capturedExitCode: Int? = null
        command.exitAction = { code -> capturedExitCode = code }

        every { cliHttpClient.authenticate(any(), any(), any()) } returns "token"
        every { cliHttpClient.postMapWithStatus(any(), any(), any()) } returns
            (400 to mapOf("message" to "limit must be between 1 and 50, was 999"))

        val output = captureStdout { command.run() }

        assertTrue(output.contains("limit must be between 1 and 50, was 999"))
        assertTrue(!output.contains("no response from server"))
        assertEquals(2, capturedExitCode)
    }

    @Test
    fun `run reports a genuine connection failure distinctly on a real network error`() {
        val cliHttpClient = mockk<CliHttpClient>()
        val command = command(cliHttpClient)
        var capturedExitCode: Int? = null
        command.exitAction = { code -> capturedExitCode = code }

        every { cliHttpClient.authenticate(any(), any(), any()) } returns "token"
        every { cliHttpClient.postMapWithStatus(any(), any(), any()) } returns (-1 to null)

        val output = captureStdout { command.run() }

        assertTrue(output.contains("connection error"))
        assertTrue(!output.contains("no response from server"))
        assertEquals(2, capturedExitCode)
    }
}
