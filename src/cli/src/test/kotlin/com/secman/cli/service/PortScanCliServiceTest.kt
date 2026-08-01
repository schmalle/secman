package com.secman.cli.service

import io.micronaut.http.client.HttpClient
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Asset.ip is free-text and not format-validated at creation time (backend
 * `AssetController`), so `runNmapScan` must itself refuse to pass a value
 * that nmap would parse as an extra flag instead of a scan target.
 */
class PortScanCliServiceTest {

    private val service = PortScanCliService(mockk<HttpClient>(), mockk<CliHttpClient>())

    @Test
    fun `refuses target that looks like an nmap flag`() {
        val result = service.runNmapScan(
            nmapPath = "/usr/bin/nmap",
            nmapArgs = emptyList(),
            ip = "--script=http-slowloris,dos",
            ports = null,
            outputFile = File.createTempFile("nmap-test", ".xml").apply { deleteOnExit() }
        )

        assertFalse(result.success)
        assertEquals(-1, result.exitCode)
    }

    @Test
    fun `refuses target with leading dash`() {
        val result = service.runNmapScan(
            nmapPath = "/usr/bin/nmap",
            nmapArgs = emptyList(),
            ip = "-oN/tmp/pwned",
            ports = null,
            outputFile = File.createTempFile("nmap-test", ".xml").apply { deleteOnExit() }
        )

        assertFalse(result.success)
    }
}
