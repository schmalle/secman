package com.secman.cli.commands

import com.secman.cli.service.S3DownloadService
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DownloadS3CommandTest {

    @Test
    fun `explicit region takes priority over environment`() {
        val command = DownloadS3Command(mockk<S3DownloadService>()).apply {
            awsRegion = "eu-west-1"
        }

        assertEquals(
            "eu-west-1",
            command.resolveAwsRegion(mapOf("AWS_REGION" to "eu-central-1"))
        )
    }

    @Test
    fun `region resolves from standard AWS environment variables`() {
        val command = DownloadS3Command(mockk<S3DownloadService>())

        assertEquals(
            "eu-central-1",
            command.resolveAwsRegion(
                mapOf(
                    "AWS_REGION" to "eu-central-1",
                    "AWS_DEFAULT_REGION" to "us-east-1"
                )
            )
        )
        assertEquals(
            "us-east-1",
            command.resolveAwsRegion(mapOf("AWS_DEFAULT_REGION" to "us-east-1"))
        )
    }

    @Test
    fun `region rejects characters that could forge terminal output`() {
        val command = DownloadS3Command(mockk<S3DownloadService>())

        assertThrows(IllegalArgumentException::class.java) {
            command.resolveAwsRegion(mapOf("AWS_REGION" to "eu-central-1\nforged"))
        }
    }
}
