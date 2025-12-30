package com.secman.cli.service

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Service for managing requirements via CLI
 * Feature: 057-cli-mcp-requirements
 *
 * Functionality:
 * - Authenticate with backend API
 * - Export requirements to Excel/Word
 * - Add new requirements
 * - Delete all requirements
 */
@Singleton
class RequirementCliService(
    @Client("\${secman.backend.base-url:http://localhost:8080}")
    private val httpClient: HttpClient
) {
    private val log = LoggerFactory.getLogger(RequirementCliService::class.java)

    /**
     * Authenticate with backend API and get JWT token
     *
     * @param username Backend username
     * @param password Backend password
     * @param backendUrl Backend API URL
     * @return JWT token or null if authentication failed
     */
    fun authenticate(username: String, password: String, backendUrl: String): String? {
        try {
            val endpoint = "$backendUrl/api/auth/login"
            val request = HttpRequest.POST(endpoint, mapOf(
                "username" to username,
                "password" to password
            )).contentType(io.micronaut.http.MediaType.APPLICATION_JSON)

            val response: HttpResponse<Map<*, *>> = httpClient.toBlocking()
                .exchange(request, Map::class.java)

            if (response.status.code == 200) {
                val body = response.body() as? Map<*, *>
                val token = body?.get("access_token")?.toString()
                    ?: body?.get("token")?.toString()
                    ?: body?.get("accessToken")?.toString()

                if (token != null) {
                    log.info("Successfully authenticated with backend")
                    return token
                }
            }

            log.error("Authentication failed: status={}", response.status)
            return null
        } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
            log.error("Authentication error: {} - {}", e.status.code, e.message)
            return null
        } catch (e: Exception) {
            log.error("Authentication error: {}", e.message, e)
            return null
        }
    }

    /**
     * Export requirements to file format (xlsx or docx)
     *
     * @param format Export format: "xlsx" or "docx"
     * @param backendUrl Backend API URL
     * @param authToken JWT authentication token
     * @return ExportResult with file bytes or error
     */
    fun exportRequirements(
        format: String,
        backendUrl: String,
        authToken: String
    ): ExportResult {
        log.info("Exporting requirements to {} format", format)

        try {
            val endpoint = "$backendUrl/api/requirements/export/$format"
            val request = HttpRequest.GET<ByteArray>(endpoint)
                .header("Authorization", "Bearer $authToken")

            val response = httpClient.toBlocking()
                .exchange(request, ByteArray::class.java)

            if (response.status.code == 200) {
                val body = response.body() ?: ByteArray(0)
                val contentDisposition = response.header("Content-Disposition") ?: ""
                val filename = extractFilename(contentDisposition, format)

                log.info("Successfully exported requirements: {} bytes", body.size)
                return ExportResult(
                    success = true,
                    data = body,
                    filename = filename,
                    message = "Export successful",
                    format = format
                )
            } else {
                return ExportResult(
                    success = false,
                    data = null,
                    filename = null,
                    message = "Backend API returned status ${response.status.code}",
                    format = format
                )
            }
        } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
            val errorMsg = when (e.status.code) {
                401 -> "Authentication required"
                403 -> "Insufficient permissions (ADMIN, REQ, or SECCHAMPION role required)"
                else -> "Backend API error: ${e.status.code} - ${e.message}"
            }
            log.error("Export requirements error: {}", errorMsg)
            return ExportResult(
                success = false,
                data = null,
                filename = null,
                message = errorMsg,
                format = format
            )
        } catch (e: java.net.ConnectException) {
            val errorMsg = "Cannot connect to backend at $backendUrl"
            log.error(errorMsg, e)
            return ExportResult(
                success = false,
                data = null,
                filename = null,
                message = errorMsg,
                format = format,
                exitCode = 2
            )
        } catch (e: Exception) {
            val errorMsg = "Failed to export requirements: ${e.message}"
            log.error(errorMsg, e)
            return ExportResult(
                success = false,
                data = null,
                filename = null,
                message = errorMsg,
                format = format
            )
        }
    }

    /**
     * Add a new requirement via backend API
     *
     * @param shortreq Short requirement text (required)
     * @param chapter Chapter/category
     * @param details Detailed description
     * @param motivation Why this requirement exists
     * @param example Implementation example
     * @param norm Regulatory norm reference
     * @param usecase Use case description
     * @param backendUrl Backend API URL
     * @param authToken JWT authentication token
     * @return AddRequirementResult with operation result
     */
    fun addRequirement(
        shortreq: String,
        chapter: String?,
        details: String?,
        motivation: String?,
        example: String?,
        norm: String?,
        usecase: String?,
        backendUrl: String,
        authToken: String
    ): AddRequirementResult {
        log.info("Adding requirement: shortreq={}", shortreq.take(50))

        try {
            val endpoint = "$backendUrl/api/requirements"
            val requestBody = mutableMapOf<String, Any?>(
                "shortreq" to shortreq
            )
            chapter?.let { requestBody["chapter"] = it }
            details?.let { requestBody["details"] = it }
            motivation?.let { requestBody["motivation"] = it }
            example?.let { requestBody["example"] = it }
            norm?.let { requestBody["norm"] = it }
            usecase?.let { requestBody["usecase"] = it }

            val request = HttpRequest.POST(endpoint, requestBody)
                .contentType(io.micronaut.http.MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $authToken")

            val response: HttpResponse<Map<*, *>> = httpClient.toBlocking()
                .exchange(request, Map::class.java)

            if (response.status.code == 200 || response.status.code == 201) {
                val body = response.body() as? Map<*, *>
                val id = (body?.get("id") as? Number)?.toLong()
                return AddRequirementResult(
                    success = true,
                    id = id,
                    message = "Requirement created successfully",
                    operation = "CREATED"
                )
            } else {
                return AddRequirementResult(
                    success = false,
                    id = null,
                    message = "Backend API returned status ${response.status.code}",
                    operation = "FAILED"
                )
            }
        } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
            val errorMsg = when (e.status.code) {
                400 -> {
                    val body = try { e.response.getBody(Map::class.java).orElse(null) } catch (ex: Exception) { null }
                    body?.get("error")?.toString() ?: "Invalid request: ${e.message}"
                }
                401 -> "Authentication required"
                403 -> "Insufficient permissions (ADMIN, REQ, or SECCHAMPION role required)"
                else -> "Backend API error: ${e.status.code} - ${e.message}"
            }
            log.error("Add requirement error: {}", errorMsg)
            return AddRequirementResult(
                success = false,
                id = null,
                message = errorMsg,
                operation = "FAILED"
            )
        } catch (e: java.net.ConnectException) {
            val errorMsg = "Cannot connect to backend at $backendUrl"
            log.error(errorMsg, e)
            return AddRequirementResult(
                success = false,
                id = null,
                message = errorMsg,
                operation = "CONNECTION_ERROR",
                exitCode = 2
            )
        } catch (e: Exception) {
            val errorMsg = "Failed to add requirement: ${e.message}"
            log.error(errorMsg, e)
            return AddRequirementResult(
                success = false,
                id = null,
                message = errorMsg,
                operation = "FAILED"
            )
        }
    }

    /**
     * Delete all requirements via backend API
     *
     * @param backendUrl Backend API URL
     * @param authToken JWT authentication token
     * @return DeleteAllResult with operation result
     */
    fun deleteAllRequirements(
        backendUrl: String,
        authToken: String
    ): DeleteAllResult {
        log.info("Deleting all requirements")

        try {
            val endpoint = "$backendUrl/api/requirements/all"
            val request = HttpRequest.DELETE<Any>(endpoint)
                .header("Authorization", "Bearer $authToken")

            val response: HttpResponse<Map<*, *>> = httpClient.toBlocking()
                .exchange(request, Map::class.java)

            if (response.status.code == 200) {
                val body = response.body() as? Map<*, *>
                val message = body?.get("message")?.toString() ?: "All requirements deleted successfully"
                log.info("Successfully deleted all requirements")
                return DeleteAllResult(
                    success = true,
                    message = message,
                    operation = "DELETED"
                )
            } else {
                return DeleteAllResult(
                    success = false,
                    message = "Backend API returned status ${response.status.code}",
                    operation = "FAILED"
                )
            }
        } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
            val errorMsg = when (e.status.code) {
                401 -> "Authentication required"
                403 -> "Insufficient permissions (ADMIN role required)"
                else -> "Backend API error: ${e.status.code} - ${e.message}"
            }
            log.error("Delete all requirements error: {}", errorMsg)
            return DeleteAllResult(
                success = false,
                message = errorMsg,
                operation = "FAILED"
            )
        } catch (e: java.net.ConnectException) {
            val errorMsg = "Cannot connect to backend at $backendUrl"
            log.error(errorMsg, e)
            return DeleteAllResult(
                success = false,
                message = errorMsg,
                operation = "CONNECTION_ERROR",
                exitCode = 2
            )
        } catch (e: Exception) {
            val errorMsg = "Failed to delete all requirements: ${e.message}"
            log.error(errorMsg, e)
            return DeleteAllResult(
                success = false,
                message = errorMsg,
                operation = "FAILED"
            )
        }
    }

    /**
     * Extract filename from Content-Disposition header
     */
    private fun extractFilename(contentDisposition: String, format: String): String {
        val filenamePattern = """filename="?([^"]+)"?""".toRegex()
        val match = filenamePattern.find(contentDisposition)
        return match?.groupValues?.get(1) ?: "requirements_export.$format"
    }
}

/**
 * Result of export operation
 * Feature: 057-cli-mcp-requirements
 */
data class ExportResult(
    val success: Boolean,
    val data: ByteArray?,
    val filename: String?,
    val message: String,
    val format: String,
    val exitCode: Int = if (success) 0 else 1
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ExportResult
        if (success != other.success) return false
        if (data != null) {
            if (other.data == null) return false
            if (!data.contentEquals(other.data)) return false
        } else if (other.data != null) return false
        if (filename != other.filename) return false
        if (message != other.message) return false
        if (format != other.format) return false
        if (exitCode != other.exitCode) return false
        return true
    }

    override fun hashCode(): Int {
        var result = success.hashCode()
        result = 31 * result + (data?.contentHashCode() ?: 0)
        result = 31 * result + (filename?.hashCode() ?: 0)
        result = 31 * result + message.hashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + exitCode
        return result
    }
}

/**
 * Result of add requirement operation
 * Feature: 057-cli-mcp-requirements
 */
data class AddRequirementResult(
    val success: Boolean,
    val id: Long?,
    val message: String,
    val operation: String,
    val exitCode: Int = if (success) 0 else 1
)

/**
 * Result of delete all operation
 * Feature: 057-cli-mcp-requirements
 */
data class DeleteAllResult(
    val success: Boolean,
    val message: String,
    val operation: String,
    val exitCode: Int = if (success) 0 else 1
)
