package com.secman.cli.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.http.SdkHttpResponse
import software.amazon.awssdk.services.s3.model.S3Exception

class S3DownloadServiceTest {

    @Test
    fun `generic head 400 falls back to get and explains expired credentials`() {
        val client = mockk<S3Client>(relaxed = true)
        every { client.headObject(any<HeadObjectRequest>()) } throws
            (S3Exception.builder().statusCode(400).message("Bad Request").build() as S3Exception)
        every { client.getObject(any<GetObjectRequest>()) } throws
            (S3Exception.builder().statusCode(400).requestId("expired-request")
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("ExpiredToken")
                    .errorMessage("The provided token has expired").build()).build() as S3Exception)
        val error = assertThrows(S3DownloadException::class.java) {
            S3DownloadService().downloadToTempFile(client, "test-bucket", "owners.csv")
        }
        assertTrue(error.message!!.contains("AWS session credentials have expired"))
        assertTrue(error.message!!.contains("access key, secret key, and session token together"))
        assertTrue(error.message!!.contains("S3 GetObject request failed"))
        assertTrue(error.message!!.contains("ExpiredToken"))
        assertTrue(error.message!!.contains("expired-request"))
        verify(exactly = 1) { client.getObject(any<GetObjectRequest>()); client.close() }
    }

    @Test
    fun `specific head errors are preserved without a fallback request`() {
        val client = mockk<S3Client>(relaxed = true)
        every { client.headObject(any<HeadObjectRequest>()) } throws
            (S3Exception.builder().statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AuthorizationHeaderMalformed")
                    .errorMessage("Wrong region").build()).build() as S3Exception)
        val error = assertThrows(S3DownloadException::class.java) {
            S3DownloadService().downloadToTempFile(client, "test-bucket", "owners.csv")
        }
        assertTrue(error.message!!.contains("S3 HeadObject request failed"))
        assertTrue(error.message!!.contains("AuthorizationHeaderMalformed"))
        verify(exactly = 0) { client.getObject(any<GetObjectRequest>()) }
        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `stream accepts the exact size limit and stops after the first excess byte`() {
        val service = S3DownloadService()
        val limit = S3DownloadService.MAX_FILE_SIZE_BYTES.toInt()
        assertEquals(limit.toLong(), service.copyWithSizeLimit(
            java.io.ByteArrayInputStream(ByteArray(limit)), java.io.OutputStream.nullOutputStream()))
        val input = java.io.ByteArrayInputStream(ByteArray(limit + 100))
        val output = java.io.ByteArrayOutputStream()
        assertThrows(S3DownloadException::class.java) { service.copyWithSizeLimit(input, output) }
        assertEquals(99, input.available())
        assertTrue(output.size() <= limit)
    }

    @Test
    fun `access denied retains AWS reason without guessing the denied permission`() {
        val exception = S3Exception.builder().statusCode(403).requestId("request-denied")
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied")
                .errorMessage("No resource-based policy allows s3:ListBucket").build())
            .build() as S3Exception
        for (operation in listOf("HeadObject", "GetObject")) {
            val message = S3DownloadService().formatDownloadAccessDenied(exception, operation, "test-bucket", "owners.csv")
            assertTrue(message.contains("S3 $operation access denied"))
            assertTrue(message.contains("No resource-based policy allows s3:ListBucket"))
            assertTrue(message.contains("missing key can return 403"))
            assertTrue(message.contains("request-denied"))
            assertFalse(message.contains("Required permission:"))
            assertFalse(message.contains("s3:HeadObject"))
            assertFalse(message.contains("not a URL or ARN"))
        }
    }

    @Test
    fun `explicit credentials win over a profile and preserve the session token`() {
        val key = "ASIA" + java.util.UUID.randomUUID().toString()
        val secret = java.util.UUID.randomUUID().toString()
        val token = java.util.UUID.randomUUID().toString()
        val credentials = S3DownloadService().credentialsProvider("unused-profile", key, secret, token).resolveCredentials()
        assertEquals(key, credentials.accessKeyId())
        assertEquals(secret, credentials.secretAccessKey())
        assertEquals(token, (credentials as software.amazon.awssdk.auth.credentials.AwsSessionCredentials).sessionToken())
    }

    @Test
    fun `profile and default chain are selected without resolving environment into explicit credentials`() {
        val service = S3DownloadService()
        val profile = service.credentialsProvider("test-profile", null, null, null)
        val defaults = service.credentialsProvider(null, null, null, null)
        try {
            assertTrue(profile is software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider)
            assertTrue(defaults is software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider)
        } finally {
            (profile as AutoCloseable).close()
            (defaults as AutoCloseable).close()
        }
    }

    @Test
    fun `incomplete or blank credential flags are rejected instead of falling back`() {
        val service = S3DownloadService()
        val value = java.util.UUID.randomUUID().toString()
        for (parts in listOf(
            listOf(value, null, null), listOf(null, value, null), listOf(null, null, value),
            listOf("ASIA$value", value, null), listOf(value, "", null), listOf(" ", value, null)
        )) {
            assertThrows(S3DownloadException::class.java) {
                service.credentialsProvider("test-profile", parts[0], parts[1], parts[2])
            }
        }
    }

    @Test
    fun `S3 diagnostics include safe request metadata`() {
        val httpResponse = SdkHttpResponse.builder()
            .statusCode(400)
            .putHeader("x-amz-bucket-region", "eu-central-1")
            .build()
        val details = AwsErrorDetails.builder()
            .errorCode("AuthorizationHeaderMalformed")
            .errorMessage("Wrong signing region\r\nfor request")
            .serviceName("S3")
            .sdkHttpResponse(httpResponse)
            .build()
        val exception = S3Exception.builder()
            .statusCode(400)
            .requestId("request-123")
            .awsErrorDetails(details)
            .build() as S3Exception

        val diagnostics = S3DownloadService().formatS3Diagnostics(exception)

        assertTrue(diagnostics.contains("HTTP status: 400"))
        assertTrue(diagnostics.contains("AWS error code: AuthorizationHeaderMalformed"))
        assertTrue(diagnostics.contains("AWS message: Wrong signing region  for request"))
        assertTrue(diagnostics.contains("Request ID: request-123"))
        assertTrue(diagnostics.contains("x-amz-bucket-region: eu-central-1"))
        assertFalse(diagnostics.contains('\r'))
    }

    @Test
    fun `S3 diagnostics identify missing response metadata`() {
        val exception = S3Exception.builder()
            .statusCode(500)
            .build() as S3Exception

        val diagnostics = S3DownloadService().formatS3Diagnostics(exception)

        assertTrue(diagnostics.contains("AWS error code: <not provided>"))
        assertTrue(diagnostics.contains("AWS message: <not provided>"))
        assertTrue(diagnostics.contains("Request ID: <not provided>"))
        assertTrue(diagnostics.contains("x-amz-bucket-region: <not provided>"))
    }
}
