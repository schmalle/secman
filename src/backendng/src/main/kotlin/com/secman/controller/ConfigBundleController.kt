package com.secman.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.dto.*
import com.secman.service.ConfigBundleService
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.http.multipart.CompletedFileUpload
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.transaction.annotation.Transactional
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Controller for configuration bundle export and import operations
 */
@Controller("/api/config-bundle")
@Secured("ADMIN")
@ExecuteOn(TaskExecutors.BLOCKING)
open class ConfigBundleController(
    private val configBundleService: ConfigBundleService,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(ConfigBundleController::class.java)

    companion object {
        const val MAX_FILE_SIZE = 50 * 1024 * 1024L // 50MB
        const val JSON_CONTENT_TYPE = "application/json"
    }

    /**
     * Export configuration bundle as JSON file
     */
    @Get("/export")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional(readOnly = true)
    open fun exportBundle(authentication: Authentication): HttpResponse<*> {
        logger.info("Configuration bundle export requested by user: ${authentication.name}")

        return try {
            // Get the bundle from service
            val bundle = configBundleService.exportBundle(authentication)

            // Convert to JSON
            val jsonBytes = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(bundle)

            // Generate filename with timestamp
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val filename = "secman_config_bundle_$timestamp.json"

            logger.info("Configuration bundle export successful, size: ${jsonBytes.size} bytes")

            // Return as downloadable file
            HttpResponse.ok(jsonBytes)
                .header(HttpHeaders.CONTENT_TYPE, JSON_CONTENT_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
                .header(HttpHeaders.CONTENT_LENGTH, jsonBytes.size.toString())

        } catch (e: Exception) {
            logger.error("Error exporting configuration bundle", e)
            HttpResponse.serverError(ErrorResponse("Failed to export configuration bundle: ${e.message}"))
        }
    }

    /**
     * Import configuration bundle from uploaded JSON file
     */
    @Post("/import")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    open fun importBundle(
        @Part file: CompletedFileUpload,
        @Part(value = "options") optionsJson: String?,
        @Part(value = "secrets") secretsJson: String?,
        authentication: Authentication
    ): HttpResponse<*> {
        logger.info("Configuration bundle import requested by user: ${authentication.name}")

        // Validate file
        if (file.size > MAX_FILE_SIZE) {
            return HttpResponse.badRequest(ErrorResponse("File size exceeds maximum of 50MB"))
        }

        if (!file.filename.endsWith(".json")) {
            return HttpResponse.badRequest(ErrorResponse("File must be a JSON file"))
        }

        return try {
            // Parse the uploaded file
            val bundleJson = file.inputStream.use { it.readBytes() }
            val bundle = objectMapper.readValue(bundleJson, ConfigBundleDto::class.java)

            // Parse options if provided
            val options = optionsJson?.let {
                objectMapper.readValue(it, ImportOptions::class.java)
            } ?: ImportOptions()

            // Parse secrets if provided
            val providedSecrets = secretsJson?.let {
                objectMapper.readValue(it, Map::class.java) as Map<String, String>
            } ?: emptyMap()

            // Create import request
            val request = ImportBundleRequest(
                bundle = bundle,
                providedSecrets = providedSecrets,
                options = options
            )

            // Perform import
            val result = configBundleService.importBundle(request, authentication)

            logger.info("Configuration bundle import completed. Imported: ${result.imported.total()}, Skipped: ${result.skipped.total()}")

            if (result.success) {
                HttpResponse.ok(result)
            } else {
                HttpResponse.badRequest(result)
            }

        } catch (e: Exception) {
            logger.error("Error importing configuration bundle", e)
            HttpResponse.serverError(ErrorResponse("Failed to import configuration bundle: ${e.message}"))
        }
    }

    /**
     * Validate configuration bundle without importing
     */
    @Post("/validate")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional(readOnly = true)
    open fun validateBundle(
        @Part file: CompletedFileUpload,
        authentication: Authentication
    ): HttpResponse<*> {
        logger.info("Configuration bundle validation requested by user: ${authentication.name}")

        // Validate file
        if (file.size > MAX_FILE_SIZE) {
            return HttpResponse.badRequest(ErrorResponse("File size exceeds maximum of 50MB"))
        }

        if (!file.filename.endsWith(".json")) {
            return HttpResponse.badRequest(ErrorResponse("File must be a JSON file"))
        }

        return try {
            // Parse the uploaded file
            val bundleJson = file.inputStream.use { it.readBytes() }
            val bundle = objectMapper.readValue(bundleJson, ConfigBundleDto::class.java)

            // Validate the bundle
            val validation = configBundleService.validateBundle(bundle)

            logger.info("Configuration bundle validation completed. Valid: ${validation.isValid}, Conflicts: ${validation.conflicts.size}")

            HttpResponse.ok(validation)

        } catch (e: Exception) {
            logger.error("Error validating configuration bundle", e)
            HttpResponse.badRequest(ErrorResponse("Failed to validate configuration bundle: ${e.message}"))
        }
    }

    /**
     * Import configuration bundle with multipart JSON parts
     * Alternative endpoint for better UI integration
     */
    @Post("/import/json")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    open fun importBundleJson(
        @Body request: ImportBundleRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        logger.info("Configuration bundle import (JSON) requested by user: ${authentication.name}")

        return try {
            // Perform import
            val result = configBundleService.importBundle(request, authentication)

            logger.info("Configuration bundle import completed. Imported: ${result.imported.total()}, Skipped: ${result.skipped.total()}")

            if (result.success) {
                HttpResponse.ok(result)
            } else {
                HttpResponse.badRequest(result)
            }

        } catch (e: Exception) {
            logger.error("Error importing configuration bundle", e)
            HttpResponse.serverError(ErrorResponse("Failed to import configuration bundle: ${e.message}"))
        }
    }

    /**
     * Get bundle schema for UI validation
     */
    @Get("/schema")
    @Produces(MediaType.APPLICATION_JSON)
    open fun getBundleSchema(): HttpResponse<Map<String, Any>> {
        val schema = mapOf(
            "version" to ConfigBundleService.BUNDLE_VERSION,
            "maxFileSize" to MAX_FILE_SIZE,
            "supportedEntities" to listOf(
                "users",
                "workgroups",
                "userMappings",
                "identityProviders",
                "falconConfigs",
                "mcpApiKeys"
            ),
            "requiredPermission" to "ADMIN"
        )
        return HttpResponse.ok(schema)
    }
}

/**
 * Error response DTO
 */
@io.micronaut.serde.annotation.Serdeable
data class ErrorResponse(val error: String)