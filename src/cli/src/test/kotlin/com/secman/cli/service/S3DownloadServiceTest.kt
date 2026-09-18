package com.secman.cli.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.http.SdkHttpResponse
import software.amazon.awssdk.services.s3.model.S3Exception

class S3DownloadServiceTest {

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
