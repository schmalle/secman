package com.secman.cli.service

import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant

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
     * @param accessKeyId AWS access key ID (null = use profile or default credential chain)
     * @param secretAccessKey AWS secret access key (required when accessKeyId is provided)
     * @param sessionToken AWS session token for temporary credentials (optional, used with accessKeyId)
     * @return Path to downloaded temporary file (caller must delete when done)
     * @throws S3DownloadException on S3 or authentication errors
     */
    fun downloadToTempFile(
        bucket: String,
        key: String,
        region: String? = null,
        profile: String? = null,
        accessKeyId: String? = null,
        secretAccessKey: String? = null,
        sessionToken: String? = null
    ): Path {
        validateBucketName(bucket)
        validateObjectKey(key)

        val s3Client = buildS3Client(region, profile, accessKeyId, secretAccessKey, sessionToken)

        try {
            // Check file size before downloading (best-effort — requires s3:HeadObject)
            val objectSize = try {
                getObjectSize(s3Client, bucket, key)
            } catch (e: S3Exception) {
                if (e.statusCode() == 403) {
                    log.warn("HeadObject permission denied — skipping pre-download size check. " +
                        "Grant s3:HeadObject for file size validation before download.")
                    null
                } else {
                    throw e  // re-throw non-permission errors (e.g. 404 will be caught by outer handler)
                }
            }

            if (objectSize != null && objectSize > MAX_FILE_SIZE_BYTES) {
                throw S3DownloadException(
                    "File 's3://$bucket/$key' exceeds ${MAX_FILE_SIZE_MB}MB limit (actual: ${objectSize / 1024 / 1024}MB)"
                )
            }

            // Create temp file with appropriate extension
            val extension = getFileExtension(key)
            val tempFile = Files.createTempFile("s3-import-", extension)

            // Restrict temp file to owner-only access (rw-------)
            try {
                Files.setPosixFilePermissions(tempFile, setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
                ))
            } catch (e: UnsupportedOperationException) {
                log.debug("POSIX file permissions not supported on this platform")
            }

            log.info("Downloading s3://$bucket/$key to temp file: $tempFile")

            try {
                // Use streaming getObject instead of path-based overload to avoid
                // SDK FileResponseTransformer issues with pre-existing temp files
                s3Client.getObject(
                    GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build()
                ).use { responseStream ->
                    Files.copy(responseStream, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }

                // Post-download size check (catches oversized files when HeadObject was unavailable)
                val downloadedSize = Files.size(tempFile)
                if (downloadedSize > MAX_FILE_SIZE_BYTES) {
                    Files.deleteIfExists(tempFile)
                    throw S3DownloadException(
                        "Downloaded file exceeds ${MAX_FILE_SIZE_MB}MB limit (actual: ${downloadedSize / 1024 / 1024}MB). File deleted."
                    )
                }

                log.info("Downloaded ${downloadedSize / 1024}KB from S3")
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
                    "Required permission: s3:GetObject"
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
     *
     * Credential resolution priority:
     * 1. Explicit credentials (accessKeyId + secretAccessKey + optional sessionToken) - highest priority
     * 2. Named profile (--aws-profile)
     * 3. Default credential chain (env vars, ~/.aws/credentials, IAM role) - lowest priority
     */
    private fun buildS3Client(
        region: String?,
        profile: String?,
        accessKeyId: String? = null,
        secretAccessKey: String? = null,
        sessionToken: String? = null
    ): S3Client {
        val builder = S3Client.builder()

        // Configure credentials provider (explicit > profile > default chain)
        when {
            accessKeyId != null && secretAccessKey != null -> {
                log.debug("Using explicit AWS credentials (access key ID: ${accessKeyId.take(4)}...)")
                // ASIA prefix = temporary STS credentials; session token is mandatory
                if (accessKeyId.startsWith("ASIA") && sessionToken == null) {
                    throw S3DownloadException(
                        "Temporary AWS credentials detected (ASIA-prefix key) but no session token provided.\n" +
                        "  Temporary/STS credentials require all three parts:\n" +
                        "  - AWS_ACCESS_KEY_ID (or --aws-access-key-id)\n" +
                        "  - AWS_SECRET_ACCESS_KEY (or --aws-secret-access-key)\n" +
                        "  - AWS_SESSION_TOKEN (or --aws-session-token)"
                    )
                }
                val credentials = if (sessionToken != null) {
                    log.debug("Using session token for temporary credentials")
                    AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken)
                } else {
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)
                }
                builder.credentialsProvider(StaticCredentialsProvider.create(credentials))
            }
            accessKeyId != null && secretAccessKey == null -> {
                throw S3DownloadException(
                    "AWS access key ID provided but secret access key is missing.\n" +
                    "  Both --aws-access-key-id and --aws-secret-access-key are required together.\n" +
                    "  Alternatively, set AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY environment variables."
                )
            }
            profile != null -> {
                log.debug("Using AWS profile: $profile")
                builder.credentialsProvider(ProfileCredentialsProvider.create(profile))
            }
            else -> {
                log.debug("Using default AWS credential chain")
                builder.credentialsProvider(DefaultCredentialsProvider.create())
            }
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
        // Detect common mistake: passing an AWS Console URL instead of a bucket name
        if (bucket.startsWith("http://") || bucket.startsWith("https://")) {
            val extractedName = extractBucketNameFromUrl(bucket)
            if (extractedName != null) {
                throw S3DownloadException(
                    "Invalid bucket name: you passed an AWS Console URL.\n" +
                    "  The bucket name appears to be: $extractedName\n" +
                    "  Try: --bucket $extractedName"
                )
            } else {
                throw S3DownloadException(
                    "Invalid bucket name: you passed a URL instead of a bucket name.\n" +
                    "  Use just the bucket name, e.g.: --bucket my-bucket-name\n" +
                    "  You can find the bucket name in the S3 console URL path after /buckets/"
                )
            }
        }

        // Detect ARN format
        if (bucket.startsWith("arn:")) {
            throw S3DownloadException(
                "Invalid bucket name: you passed an S3 ARN.\n" +
                "  Use just the bucket name, e.g.: --bucket my-bucket-name\n" +
                "  The bucket name is the part after the last ':' or '/' in the ARN."
            )
        }

        // Detect s3:// URI format
        if (bucket.startsWith("s3://")) {
            val name = bucket.removePrefix("s3://").trimEnd('/')
            throw S3DownloadException(
                "Invalid bucket name: you passed an S3 URI.\n" +
                "  Use just the bucket name: --bucket $name"
            )
        }

        if (bucket.isBlank()) {
            throw S3DownloadException("Invalid bucket name: cannot be empty")
        }
        if (bucket.length < 3 || bucket.length > 63) {
            throw S3DownloadException(
                "Invalid bucket name '$bucket': must be 3-63 characters (got ${bucket.length})"
            )
        }
        if (!bucket.matches(Regex("^[a-z0-9][a-z0-9.-]*[a-z0-9]$"))) {
            throw S3DownloadException(
                "Invalid bucket name '$bucket': must start and end with lowercase letter or number, " +
                "contain only lowercase letters, numbers, hyphens, and periods"
            )
        }
    }

    /**
     * Try to extract a bucket name from an AWS Console URL.
     * Handles URLs like:
     *   https://s3.console.aws.amazon.com/s3/buckets/my-bucket?region=us-east-1
     *   https://123456789012.eu-central-1.console.aws.amazon.com/s3/buckets/my-bucket?region=eu-central-1
     */
    private fun extractBucketNameFromUrl(url: String): String? {
        val regex = Regex("/s3/buckets/([^?/]+)")
        return regex.find(url)?.groupValues?.get(1)
    }

    /**
     * Validate prefix for listing operations
     * Same constraints as object keys: max 1024 chars, no null bytes
     */
    private fun validatePrefix(prefix: String) {
        if (prefix.length > 1024) {
            throw S3DownloadException("Invalid prefix: exceeds 1024 character limit")
        }
        if (prefix.contains('\u0000')) {
            throw S3DownloadException("Invalid prefix: cannot contain null characters")
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
     * List objects in an S3 bucket with optional prefix filter
     *
     * @param bucket S3 bucket name
     * @param prefix Optional key prefix to filter results
     * @param region AWS region (null = use SDK default resolution)
     * @param profile AWS credential profile name (null = use default credential chain)
     * @param accessKeyId AWS access key ID (null = use profile or default credential chain)
     * @param secretAccessKey AWS secret access key (required when accessKeyId is provided)
     * @param sessionToken AWS session token for temporary credentials (optional, used with accessKeyId)
     * @return List of S3ObjectInfo for matching objects
     * @throws S3DownloadException on S3 or authentication errors
     */
    fun listObjects(
        bucket: String,
        prefix: String? = null,
        region: String? = null,
        profile: String? = null,
        accessKeyId: String? = null,
        secretAccessKey: String? = null,
        sessionToken: String? = null
    ): List<S3ObjectInfo> {
        validateBucketName(bucket)
        if (prefix != null) {
            validatePrefix(prefix)
        }

        val s3Client = buildS3Client(region, profile, accessKeyId, secretAccessKey, sessionToken)

        try {
            val requestBuilder = ListObjectsV2Request.builder()
                .bucket(bucket)
                .maxKeys(1000)
            if (prefix != null) {
                requestBuilder.prefix(prefix)
            }

            val response = s3Client.listObjectsV2(requestBuilder.build())

            if (response.isTruncated) {
                log.warn("Results truncated: bucket contains more than 1,000 objects matching the prefix. Use a more specific --prefix to narrow results.")
            }

            return response.contents().map { obj ->
                S3ObjectInfo(
                    key = obj.key(),
                    size = obj.size(),
                    lastModified = obj.lastModified()
                )
            }
        } catch (e: NoSuchBucketException) {
            throw S3DownloadException("Bucket '$bucket' does not exist or is not accessible")
        } catch (e: S3Exception) {
            if (e.statusCode() == 403) {
                throw S3DownloadException(
                    "Access denied. Check IAM permissions for bucket '$bucket'. " +
                    "Required permissions: s3:ListBucket"
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
            log.error("Unexpected error listing S3 objects", e)
            throw S3DownloadException("Unexpected error: ${e.message}")
        } finally {
            s3Client.close()
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

/**
 * Info about an S3 object returned by listObjects
 */
data class S3ObjectInfo(
    val key: String,
    val size: Long,
    val lastModified: Instant
)
