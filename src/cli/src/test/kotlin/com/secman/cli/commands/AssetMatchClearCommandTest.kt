package com.secman.cli.commands

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.cli.service.CliHttpClient
import com.secman.cli.service.S3DownloadService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

class AssetMatchClearCommandTest {

    @TempDir
    lateinit var tempDir: Path

    private val savedSnapshot = Path.of("/tmp/asset.json")

    @AfterEach
    fun cleanup() {
        Files.deleteIfExists(savedSnapshot)
    }

    @Test
    fun `save option stores downloaded snapshot in tmp asset json before cleanup`() {
        Files.deleteIfExists(savedSnapshot)
        val downloadedSnapshot = tempDir.resolve("downloaded.json")
        val snapshotJson = """[{"accountId":"123456789012","resourceId":"i-abc123"}]"""
        Files.writeString(downloadedSnapshot, snapshotJson)

        val cliHttpClient = mockk<CliHttpClient>()
        val s3DownloadService = mockk<S3DownloadService>()
        val command = AssetMatchClearCommand().apply {
            this.cliHttpClient = cliHttpClient
            this.s3DownloadService = s3DownloadService
            this.objectMapper = ObjectMapper()
            this.bucket = "asset-bucket"
            this.key = "snapshots/latest.json"
            this.username = "admin"
            this.password = "secret"
            this.backendUrl = "https://secman.example.test"
            this.dryRun = true
            this.save = true
        }

        every {
            s3DownloadService.downloadToTempFile(any(), any(), any(), any(), any(), any(), any())
        } returns downloadedSnapshot
        every { s3DownloadService.cleanupTempFile(downloadedSnapshot) } answers {
            Files.deleteIfExists(downloadedSnapshot)
            Unit
        }
        every {
            cliHttpClient.authenticate("admin", "secret", "https://secman.example.test")
        } returns "token"
        every {
            cliHttpClient.postMapWithStatus(
                "https://secman.example.test/api/assets/match-clear-aws",
                any(),
                "token"
            )
        } returns (200 to mapOf(
            "candidateCount" to 0,
            "deletedCount" to 0,
            "skippedCount" to 0,
            "scopedAssetCount" to 1,
            "status" to "DRY_RUN",
            "safetyBrakeTripped" to false,
            "candidates" to emptyList<Map<String, Any?>>(),
            "errors" to emptyList<Map<String, Any?>>()
        ))

        command.run()

        assertTrue(Files.exists(savedSnapshot))
        assertEquals(snapshotJson, Files.readString(savedSnapshot))
        assertFalse(Files.exists(downloadedSnapshot))
    }

    @Test
    fun `picocli parses save option`() {
        val command = AssetMatchClearCommand()

        CommandLine(command).parseArgs("--save")

        assertTrue(command.save)
    }

    @Test
    fun `picocli parses strict option`() {
        val command = AssetMatchClearCommand()

        CommandLine(command).parseArgs("--strict")

        assertTrue(command.strict)
    }

    @Test
    fun `picocli parses check option`() {
        val command = AssetMatchClearCommand()

        CommandLine(command).parseArgs("--check")

        assertTrue(command.check)
    }

    @Test
    fun `picocli parses check fix option`() {
        val command = AssetMatchClearCommand()

        CommandLine(command).parseArgs("--check-fix")

        assertTrue(command.checkFix)
    }

    @Test
    fun `request body includes strict true`() {
        val downloadedSnapshot = tempDir.resolve("downloaded.json")
        Files.writeString(downloadedSnapshot, """[{"accountId":"123456789012","resourceId":"i-abc123"}]""")
        var requestBody: Map<String, Any?>? = null
        val cliHttpClient = mockk<CliHttpClient>()
        val s3DownloadService = mockk<S3DownloadService>()
        val command = AssetMatchClearCommand().apply {
            this.cliHttpClient = cliHttpClient
            this.s3DownloadService = s3DownloadService
            this.objectMapper = ObjectMapper()
            this.bucket = "asset-bucket"
            this.key = "snapshots/latest.json"
            this.username = "admin"
            this.password = "secret"
            this.backendUrl = "https://secman.example.test"
            this.dryRun = true
            this.strict = true
        }

        every {
            s3DownloadService.downloadToTempFile(any(), any(), any(), any(), any(), any(), any())
        } returns downloadedSnapshot
        every { s3DownloadService.cleanupTempFile(downloadedSnapshot) } returns Unit
        every {
            cliHttpClient.authenticate("admin", "secret", "https://secman.example.test")
        } returns "token"
        every {
            cliHttpClient.postMapWithStatus(
                "https://secman.example.test/api/assets/match-clear-aws",
                any(),
                "token"
            )
        } answers {
            requestBody = secondArg()
            200 to mapOf(
                "candidateCount" to 0,
                "deletedCount" to 0,
                "skippedCount" to 0,
                "scopedAssetCount" to 1,
                "status" to "DRY_RUN",
                "safetyBrakeTripped" to false,
                "candidates" to emptyList<Map<String, Any?>>(),
                "errors" to emptyList<Map<String, Any?>>()
            )
        }

        command.run()

        assertEquals(true, requestBody?.get("strict"))
    }

    @Test
    fun `check option reads inventory and does not call match clear endpoint`() {
        val downloadedSnapshot = tempDir.resolve("downloaded.json")
        Files.writeString(
            downloadedSnapshot,
            """[
                {"accountId":"123456789012","resourceId":"i-abc123"},
                {"accountId":"123456789012","resourceId":"i-missing"}
            ]""".trimIndent()
        )
        val cliHttpClient = mockk<CliHttpClient>()
        val s3DownloadService = mockk<S3DownloadService>()
        val command = AssetMatchClearCommand().apply {
            this.cliHttpClient = cliHttpClient
            this.s3DownloadService = s3DownloadService
            this.objectMapper = ObjectMapper()
            this.bucket = "asset-bucket"
            this.key = "snapshots/latest.json"
            this.username = "admin"
            this.password = "secret"
            this.backendUrl = "https://secman.example.test"
            this.check = true
            this.save = true
        }

        every {
            s3DownloadService.downloadToTempFile(any(), any(), any(), any(), any(), any(), any())
        } returns downloadedSnapshot
        every { s3DownloadService.cleanupTempFile(downloadedSnapshot) } returns Unit
        every {
            cliHttpClient.authenticate("admin", "secret", "https://secman.example.test")
        } returns "token"
        every {
            cliHttpClient.getList("https://secman.example.test/api/assets", "token")
        } returns listOf(
            mapOf("id" to 1, "name" to "asset-a", "cloudInstanceId" to "I-ABC123"),
            mapOf("id" to 2, "name" to "asset-b", "cloudInstanceId" to null)
        )

        command.run()

        assertFalse(Files.exists(savedSnapshot))
        verify(exactly = 1) {
            cliHttpClient.getList("https://secman.example.test/api/assets", "token")
        }
        verify(exactly = 0) {
            cliHttpClient.postMapWithStatus(any(), any(), any())
        }
    }

    @Test
    fun `check fix creates missing snapshot resources through import endpoint`() {
        val downloadedSnapshot = tempDir.resolve("downloaded.json")
        Files.writeString(
            downloadedSnapshot,
            """[
                {"accountId":"123456789012","resourceId":"i-Existing"},
                {"accountId":"123456789012","resourceId":"i-Missing"}
            ]""".trimIndent()
        )
        val importBodies = mutableListOf<Map<String, Any?>>()
        val cliHttpClient = mockk<CliHttpClient>()
        val s3DownloadService = mockk<S3DownloadService>()
        val command = AssetMatchClearCommand().apply {
            this.cliHttpClient = cliHttpClient
            this.s3DownloadService = s3DownloadService
            this.objectMapper = ObjectMapper()
            this.bucket = "asset-bucket"
            this.key = "snapshots/latest.json"
            this.username = "admin"
            this.password = "secret"
            this.backendUrl = "https://secman.example.test"
            this.checkFix = true
            this.save = true
        }

        every {
            s3DownloadService.downloadToTempFile(any(), any(), any(), any(), any(), any(), any())
        } returns downloadedSnapshot
        every { s3DownloadService.cleanupTempFile(downloadedSnapshot) } returns Unit
        every {
            cliHttpClient.authenticate("admin", "secret", "https://secman.example.test")
        } returns "token"
        every {
            cliHttpClient.getList("https://secman.example.test/api/assets", "token")
        } returns listOf(
            mapOf("id" to 1, "name" to "asset-a", "cloudInstanceId" to "i-existing")
        )
        every {
            cliHttpClient.putMapWithStatus(
                "https://secman.example.test/api/assets/import",
                any(),
                "token"
            )
        } answers {
            importBodies.add(secondArg())
            200 to mapOf("created" to true)
        }

        command.run()

        assertFalse(Files.exists(savedSnapshot))
        assertEquals(
            listOf(
                mapOf(
                    "name" to "i-Missing",
                    "type" to "SERVER",
                    "owner" to "AWS Asset Inventory",
                    "description" to "Auto-created by asset-match-clear --check-fix from AWS snapshot",
                    "cloudAccountId" to "123456789012",
                    "cloudInstanceId" to "i-Missing"
                )
            ),
            importBodies
        )
        verify(exactly = 0) {
            cliHttpClient.postMapWithStatus(any(), any(), any())
        }
    }

    @Test
    fun `check fix skips duplicate resource ids across accounts`() {
        val downloadedSnapshot = tempDir.resolve("downloaded.json")
        Files.writeString(
            downloadedSnapshot,
            """[
                {"accountId":"111111111111","resourceId":"i-Duplicate"},
                {"accountId":"222222222222","resourceId":"i-duplicate"}
            ]""".trimIndent()
        )
        val cliHttpClient = mockk<CliHttpClient>()
        val s3DownloadService = mockk<S3DownloadService>()
        val command = AssetMatchClearCommand().apply {
            this.cliHttpClient = cliHttpClient
            this.s3DownloadService = s3DownloadService
            this.objectMapper = ObjectMapper()
            this.bucket = "asset-bucket"
            this.key = "snapshots/latest.json"
            this.username = "admin"
            this.password = "secret"
            this.backendUrl = "https://secman.example.test"
            this.checkFix = true
        }

        every {
            s3DownloadService.downloadToTempFile(any(), any(), any(), any(), any(), any(), any())
        } returns downloadedSnapshot
        every { s3DownloadService.cleanupTempFile(downloadedSnapshot) } returns Unit
        every {
            cliHttpClient.authenticate("admin", "secret", "https://secman.example.test")
        } returns "token"
        every {
            cliHttpClient.getList("https://secman.example.test/api/assets", "token")
        } returns emptyList()

        command.run()

        verify(exactly = 0) {
            cliHttpClient.putMapWithStatus(any(), any(), any())
        }
        verify(exactly = 0) {
            cliHttpClient.postMapWithStatus(any(), any(), any())
        }
    }

    @Test
    fun `check fix cannot be combined with check or dry run`() {
        val withCheck = AssetMatchClearCommand().apply {
            checkFix = true
            check = true
        }
        val withDryRun = AssetMatchClearCommand().apply {
            checkFix = true
            dryRun = true
        }

        assertFalse(withCheck.validateOptions())
        assertFalse(withDryRun.validateOptions())
    }

    @Test
    fun `help text documents strict option`() {
        val output = StringWriter()

        CommandLine(AssetMatchClearCommand()).usage(PrintWriter(output))

        assertTrue(output.toString().contains("--strict"))
    }

    @Test
    fun `help text documents check option`() {
        val output = StringWriter()

        CommandLine(AssetMatchClearCommand()).usage(PrintWriter(output))

        assertTrue(output.toString().contains("--check"))
    }

    @Test
    fun `help text documents check fix option`() {
        val output = StringWriter()

        CommandLine(AssetMatchClearCommand()).usage(PrintWriter(output))

        assertTrue(output.toString().contains("--check-fix"))
    }
}
