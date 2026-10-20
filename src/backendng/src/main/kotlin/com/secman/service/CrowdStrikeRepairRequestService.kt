package com.secman.service

import com.secman.dto.CrowdStrikeIdentityReconcileRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.util.UUID

/** Network work runs after the request claim commits, outside any database transaction. */
@Singleton
class CrowdStrikeRepairRequestService(
    private val core: CrowdStrikeIdentityReconcileService,
    private val store: CrowdStrikeRepairRequestStore,
    private val access: AssetFilterService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun execute(input: CrowdStrikeIdentityReconcileRequest, auth: Authentication): CrowdStrikeRepairRequestStatus {
        authorize(auth)
        require(input.assetId > 0 && input.expectedAids.size in 2..100 &&
            input.expectedAids.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } &&
            input.reason.trim().length in 10..255 && input.reason.none(Char::isISOControl)) { "Invalid repair request" }
        if (!access.canAccessAsset(input.assetId, auth)) throw HttpStatusException(HttpStatus.NOT_FOUND, "System not found")
        val request = input.copy(requestId = normalize(input.requestId ?: UUID.randomUUID().toString()))
        val id = requireNotNull(request.requestId)
        val claim = store.start(request, auth.name)
        if (!claim.claimed) return claim.status
        log.info("Falcon repair request actor={} assetId={} requestId={} outcome=started",
            auth.name.replace('\r', '_').replace('\n', '_'), request.assetId, id)
        try {
            store.succeed(id, core.reconcile(request, auth))
        } catch (e: Exception) {
            val conflict = e as? CrowdStrikeIdentityConflict
            val http = if (conflict != null || e is IllegalArgumentException) 409
                else (e as? HttpStatusException)?.status?.code ?: 502
            val code = conflict?.code ?: if (e is IllegalArgumentException) "IDENTITY_REJECTED" else "REPAIR_EXECUTION_FAILED"
            val message = conflict?.message ?: if (e is IllegalArgumentException)
                "Repair validation rejected this request; existing inventory was preserved" else
                "Repair did not complete; inspect backend logs using the request ID"
            log.warn("Falcon repair request actor={} assetId={} requestId={} code={} outcome=failed-or-rejected",
                auth.name.replace('\r', '_').replace('\n', '_'), request.assetId, id, code, e)
            // A post-commit failure cannot downgrade the atomic REPAIRED marker.
            store.fail(id, http, code, message.take(512), conflict?.aid)
        }
        return requireNotNull(store.status(id))
    }

    fun status(id: String, auth: Authentication): CrowdStrikeRepairRequestStatus {
        authorize(auth)
        return store.status(normalize(id)) ?: throw HttpStatusException(HttpStatus.NOT_FOUND, "Repair request not found")
    }

    private fun authorize(auth: Authentication) {
        if ("ADMIN" !in auth.roles) throw HttpStatusException(HttpStatus.FORBIDDEN, "Repair requests require ADMIN")
    }

    private fun normalize(id: String): String {
        require(id.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) { "Invalid request ID" }
        return UUID.fromString(id).toString()
    }
}
