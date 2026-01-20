package com.secman.cli.service

import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Service for downloading files from AWS S3 (Feature 065)
 *
 * Provides S3 download functionality with:
 * - Standard AWS credential chain support
 * - Named profile support
 * - Region configuration
 * - File size validation (10MB limit)
 * - User-friendly error messages
 * - Automatic temp file cleanup
 */
@Singleton
class S3DownloadService {

    private val log = LoggerFactory.getLogger(S3DownloadService::class.java)

    companion object {
        const val MAX_FILE_SIZE_BYTES: Long = 10 * 1024 * 1024 // 10MB
        const val MAX_FILE_SIZE_MB = 10
    }

    /**
     * Download an S3 object to a temporary file
     *
     * @param bucket S3 bucket name
     * @param key S3 object key (path)
     * @param region AWS region (null = use SDK default resolution)
     * @param profile AWS credential profile name (null = use default credential chain)
     * @return Path to downloaded temporary file (caller must delete when done)
     * @throws S3DownloadException on S3 or authentication errors
     */
    fun downloadToTempFile(
        bucket: String,
        key: String,
        region: String? = null,
        profile: String? = null
    ): Path {
        validateBucketName(bucket)
        validateObjectKey(key)

        val s3Client = buildS3Client(region, profile)

        try {
            // Check file size before downloading
            val objectSize = getObjectSize(s3Client, bucket, key)
            if (objectSize > MAX_FILE_SIZE_BYTES) {
                throw S3DownloadException(
                    "File 's3://$bucket/$key' exceeds ${MAX_FILE_SIZE_MB}MB limit (actual: ${objectSize / 1024 / 1024}MB)"
                )
            }

            // Create temp file with appropriate extension
            val extension = getFileExtension(key)
            val tempFile = Files.createTempFile("s3-import-", extension)

            log.info("Downloading s3://$bucket/$key to temp file: $tempFile")

            try {
                s3Client.getObject(
                    GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build(),
                    tempFile
                )

                log.info("Downloaded ${objectSize / 1024}KB from S3")
                return tempFile
            } catch (e: Exception) {
                // Clean up temp file on download failure
                Files.deleteIfExists(tempFile)
                throw e
            }

        } catch (e: NoSuchBucketException) {
            throw S3DownloadException("Bucket '$bucket' does not exist or is not accessible")
        } catch (e: NoSuchKeyException) {
            throw S3DownloadException("File 's3://$bucket/$key' not found")
        } catch (e: S3Exception) {
            if (e.statusCode() == 403) {
                throw S3DownloadException(
                    "Access denied. Check IAM permissions for bucket '$bucket'. " +
                    "Required permissions: s3:GetObject, s3:HeadObject"
                )
            }
            throw S3DownloadException("S3 error: ${e.awsErrorDetails()?.errorMessage() ?: e.message}")
        } catch (e: SdkClientException) {
            val message = e.message ?: "Unknown error"
            when {
                message.contains("credentials", ignoreCase = true) ||
                message.contains("Unable to load", ignoreCase = true) -> {
                    throw S3DownloadException(
                        "AWS credentials not found. Configure via:\n" +
                        "  - Environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)\n" +
                        "  - AWS credentials file (~/.aws/credentials)\n" +
                        "  - IAM role (for EC2/ECS deployments)\n" +
                        "  - Use --aws-profile to specify a named profile"
                    )
                }
                message.contains("network", ignoreCase = true) ||
                message.contains("connect", ignoreCase = true) ||
                message.contains("timeout", ignoreCase = true) -> {
                    throw S3DownloadException(
                        "Network error connecting to S3. Check:\n" +
                        "  - Internet connectivity\n" +
                        "  - AWS region (use --aws-region if bucket is in a different region)\n" +
                        "  - Firewall/proxy settings"
                    )
                }
                else -> throw S3DownloadException("S3 client error: $message")
            }
        } catch (e: S3DownloadException) {
            throw e
        } catch (e: Exception) {
            log.error("Unexpected error downloading from S3", e)
            throw S3DownloadException("Unexpected error: ${e.message}")
        } finally {
            s3Client.close()
        }
    }

    /**
     * Build S3Client with appropriate credentials and region
     */
    private fun buildS3Client(region: String?, profile: String?): S3Client {
        val builder = S3Client.builder()

        // Configure credentials provider
        if (profile != null) {
            log.debug("Using AWS profile: $profile")
            builder.credentialsProvider(ProfileCredentialsProvider.create(profile))
        } else {
            log.debug("Using default AWS credential chain")
            builder.credentialsProvider(DefaultCredentialsProvider.create())
        }

        // Configure region
        if (region != null) {
            log.debug("Using explicit AWS region: $region")
            builder.region(Region.of(region))
        }
        // If region is null, SDK will resolve from:
        // 1. AWS_REGION environment variable
        // 2. AWS config file (~/.aws/config)
        // 3. EC2 instance metadata

        return builder.build()
    }

    /**
     * Get object size via HeadObject request
     */
    private fun getObjectSize(s3Client: S3Client, bucket: String, key: String): Long {
        val headResponse = s3Client.headObject(
            HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build()
        )
        return headResponse.contentLength()
    }

    /**
     * Validate bucket name format
     * AWS bucket names: 3-63 chars, lowercase letters, numbers, hyphens, periods
     */
    private fun validateBucketName(bucket: String) {
        if (bucket.length < 3 || bucket.length > 63) {
            throw S3DownloadException("Invalid bucket name: must be 3-63 characters")
        }
        if (!bucket.matches(Regex("^[a-z0-9][a-z0-9.-]*[a-z0-9]$"))) {
            throw S3DownloadException(
                "Invalid bucket name: must start and end with lowercase letter or number, " +
                "contain only lowercase letters, numbers, hyphens, and periods"
            )
        }
    }

    /**
     * Validate object key
     * AWS keys can be up to 1024 bytes, should not contain null bytes
     */
    private fun validateObjectKey(key: String) {
        if (key.isEmpty()) {
            throw S3DownloadException("Invalid object key: cannot be empty")
        }
        if (key.length > 1024) {
            throw S3DownloadException("Invalid object key: exceeds 1024 character limit")
        }
        if (key.contains('\u0000')) {
            throw S3DownloadException("Invalid object key: cannot contain null characters")
        }
    }

    /**
     * Extract file extension from S3 key
     */
    private fun getFileExtension(key: String): String {
        val lastDot = key.lastIndexOf('.')
        return if (lastDot > 0 && lastDot < key.length - 1) {
            ".${key.substring(lastDot + 1)}"
        } else {
            ".tmp"
        }
    }

    /**
     * Clean up a temporary file
     * Call this in a finally block after processing
     */
    fun cleanupTempFile(tempFile: Path?) {
        if (tempFile != null) {
            try {
                if (Files.deleteIfExists(tempFile)) {
                    log.debug("Cleaned up temp file: $tempFile")
                }
            } catch (e: Exception) {
                log.warn("Failed to delete temp file: $tempFile", e)
            }
        }
    }
}

/**
 * Exception for S3 download errors with user-friendly messages
 */
class S3DownloadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
