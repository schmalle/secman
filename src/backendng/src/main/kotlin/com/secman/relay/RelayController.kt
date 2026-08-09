package com.secman.relay

import com.fasterxml.jackson.databind.ObjectMapper
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import org.slf4j.LoggerFactory

/**
 * Administration of the mobile relay.
 *
 * Every endpoint is ADMIN-only. That is not a default reached for convenience:
 * these routes issue device credentials, revoke them, and expose the relay's
 * device inventory. There is no read-only role for them because there is
 * nothing here that is only a read.
 *
 * There is no endpoint that lets a caller name the relay's URL, token or key.
 * Those come from the environment (pass-cli), which is what keeps this
 * controller from being an SSRF surface: the target of every outbound call is
 * fixed configuration, and the request body only ever influences the *payload*.
 */
@Controller("/api/relay")
@ExecuteOn(TaskExecutors.BLOCKING)
@Secured("ADMIN")
open class RelayController(
    private val properties: RelayProperties,
    private val publisher: RelayPublisher,
    private val enrollmentService: RelayEnrollmentService,
    private val client: RelayClient,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(RelayController::class.java)

    /**
     * GET /api/relay/status
     *
     * Local publisher state, plus the relay's own report when it is reachable.
     * The relay half is best-effort: an unreachable relay is itself the answer
     * an admin is looking for, so it becomes `relayError` rather than a 5xx.
     */
    @Get("/status")
    open fun status(): HttpResponse<RelayStatusResponse> {
        val local = publisher.status()
        if (!properties.enabled) {
            return HttpResponse.ok(local)
        }

        val result = client.fetchStatus()
        if (!result.success) {
            return HttpResponse.ok(local.copy(relayError = result.error))
        }
        val parsed = try {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(result.body ?: "{}", Map::class.java) as Map<String, Any>
        } catch (e: Exception) {
            return HttpResponse.ok(local.copy(relayError = "Relay status response was not valid JSON"))
        }
        return HttpResponse.ok(local.copy(relay = parsed))
    }

    /**
     * POST /api/relay/publish
     *
     * Pushes a snapshot immediately instead of waiting for the next tick.
     * Useful after a configuration change and as a connectivity check.
     */
    @Post("/publish")
    open fun publishNow(authentication: Authentication): HttpResponse<Map<String, Any>> {
        val error = publisher.publish(trigger = "manual:${authentication.name}")
        return if (error == null) {
            logger.info("Relay snapshot published manually: actor={} outcome=published", sanitizeForLog(authentication.name))
            HttpResponse.ok(mapOf("published" to true))
        } else {
            logger.warn(
                "Manual relay publish failed: actor={} outcome=failed reason={}",
                sanitizeForLog(authentication.name), sanitizeForLog(error)
            )
            HttpResponse.serverError(mapOf("published" to false, "error" to error))
        }
    }

    /**
     * POST /api/relay/enrollments
     *
     * Issues a single-use enrollment code and pushes its digest to the relay.
     * The plaintext code is in the response and nowhere else — it is not
     * persisted and cannot be retrieved again.
     */
    @Post("/enrollments")
    open fun createEnrollment(
        @Body request: CreateRelayEnrollmentRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        if (!properties.enabled) {
            return HttpResponse.badRequest(mapOf("error" to "Relay publishing is disabled (set SECMAN_RELAY_ENABLED=true)"))
        }
        return try {
            HttpResponse.created(enrollmentService.createEnrollment(request, authentication.name))
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(mapOf("error" to (e.message ?: "Invalid enrollment request")))
        } catch (e: IllegalStateException) {
            // The detail here is the relay's own error text, which an ADMIN
            // needs in order to fix the deployment.
            HttpResponse.serverError(mapOf("error" to (e.message ?: "The relay did not accept the enrollment")))
        }
    }

    /**
     * POST /api/relay/revocations
     *
     * Revokes one device, or every device (`revokeAll`). Takes effect on the
     * relay's very next request from that device, not when its token expires.
     */
    @Post("/revocations")
    open fun revoke(
        @Body request: CreateRelayRevocationRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        if (!properties.enabled) {
            return HttpResponse.badRequest(mapOf("error" to "Relay publishing is disabled (set SECMAN_RELAY_ENABLED=true)"))
        }
        return try {
            enrollmentService.revoke(request, authentication.name)
            HttpResponse.ok(mapOf("revoked" to true))
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(mapOf("error" to (e.message ?: "Invalid revocation request")))
        } catch (e: IllegalStateException) {
            HttpResponse.serverError(mapOf("error" to (e.message ?: "The relay did not accept the revocation")))
        }
    }

    /**
     * GET /api/relay/devices
     *
     * The relay's device inventory. secman does not keep its own copy: the
     * relay is the registry of record for devices, and this endpoint reads it
     * back over the same authenticated ingest channel used for pushes.
     */
    @Get("/devices")
    open fun devices(): HttpResponse<*> {
        if (!properties.enabled) {
            return HttpResponse.badRequest(mapOf("error" to "Relay publishing is disabled (set SECMAN_RELAY_ENABLED=true)"))
        }
        val result = client.fetchDevices()
        if (!result.success) {
            return HttpResponse.serverError(mapOf("error" to (result.error ?: "The relay could not be reached")))
        }
        return try {
            @Suppress("UNCHECKED_CAST")
            val parsed = objectMapper.readValue(result.body ?: "{}", Map::class.java) as Map<String, Any>
            HttpResponse.ok(parsed)
        } catch (e: Exception) {
            HttpResponse.serverError(mapOf("error" to "The relay device listing was not valid JSON"))
        }
    }

    /**
     * GET /api/relay/sections
     *
     * The section names this build can publish, so an admin composing a scope
     * does not have to read the source to find out what is valid.
     */
    @Get("/sections")
    open fun sections(): HttpResponse<Map<String, Any>> = HttpResponse.ok(
        mapOf(
            "available" to RelaySnapshotBuilder.ALL_SECTIONS,
            "published" to properties.sections
        )
    )
}
