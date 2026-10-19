package com.secman.service

import com.fasterxml.jackson.annotation.JsonInclude
import com.secman.domain.CrowdStrikeRepairRequest
import com.secman.dto.CrowdStrikeIdentityReconcileRequest
import com.secman.dto.CrowdStrikeIdentityReconcileResponse
import io.micronaut.json.JsonMapper
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import java.security.MessageDigest
import java.time.LocalDateTime

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class CrowdStrikeRepairRequestStatus(val requestId: String, val assetId: Long, val dryRun: Boolean,
    val state: String, val httpStatus: Int, val errorCode: String? = null, val message: String? = null,
    val aid: String? = null, val report: CrowdStrikeIdentityReconcileResponse? = null,
    val startedAt: LocalDateTime? = null, val completedAt: LocalDateTime? = null)

data class CrowdStrikeRepairClaim(val claimed: Boolean, val status: CrowdStrikeRepairRequestStatus)

@Singleton
open class CrowdStrikeRepairRequestStore(private val em: EntityManager, private val mapper: JsonMapper) {
    @Transactional
    open fun start(request: CrowdStrikeIdentityReconcileRequest, actor: String): CrowdStrikeRepairClaim {
        val id = requireNotNull(request.requestId)
        val payload = mapper.writeValueAsString(listOf(request.assetId, request.expectedAids.sorted(), request.reason.trim(), request.dryRun, actor))
        val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val inserted = em.createNativeQuery("""
            INSERT IGNORE INTO crowdstrike_repair_request
                (request_id, asset_id, dry_run, actor, fingerprint, state, http_status, started_at)
            VALUES (:id, :assetId, :dryRun, :actor, :hash, 'RUNNING', 202, UTC_TIMESTAMP(6))
        """).setParameter("id", id).setParameter("assetId", request.assetId).setParameter("dryRun", request.dryRun)
            .setParameter("actor", actor.take(255)).setParameter("hash", hash).executeUpdate()
        val stored = requireNotNull(em.find(CrowdStrikeRepairRequest::class.java, id))
        em.refresh(stored)
        if (stored.fingerprint != hash) throw CrowdStrikeIdentityConflict("REQUEST_ID_CONFLICT",
            "Request ID was already used for different input or a different actor", request.assetId)
        return CrowdStrikeRepairClaim(inserted == 1, view(stored))
    }

    @Transactional
    open fun status(id: String): CrowdStrikeRepairRequestStatus? {
        val stored = em.find(CrowdStrikeRepairRequest::class.java, id) ?: return null
        em.refresh(stored)
        return view(stored)
    }

    /** Joins the repair writer's transaction so REPAIRED and the audit/findings commit together. */
    @Transactional
    open fun succeed(id: String, result: CrowdStrikeIdentityReconcileResponse) {
        em.createNativeQuery("""
            UPDATE crowdstrike_repair_request SET state = :state, http_status = 200, response_json = :json,
                completed_at = UTC_TIMESTAMP(6) WHERE request_id = :id AND state = 'RUNNING'
        """).setParameter("id", id).setParameter("state", if (result.dryRun) "PREVIEWED" else "REPAIRED")
            .setParameter("json", mapper.writeValueAsString(result)).executeUpdate()
    }

    @Transactional
    open fun fail(id: String, httpStatus: Int, code: String, message: String, aid: String?) {
        em.createNativeQuery("""
            UPDATE crowdstrike_repair_request SET state = :state, http_status = :http, error_code = :code,
                message = :message, aid = :aid, completed_at = UTC_TIMESTAMP(6)
            WHERE request_id = :id AND state = 'RUNNING'
        """).setParameter("id", id).setParameter("state", if (httpStatus in 400..499) "REJECTED" else "FAILED")
            .setParameter("http", httpStatus).setParameter("code", code).setParameter("message", message)
            .setParameter("aid", aid).executeUpdate()
    }

    private fun view(row: CrowdStrikeRepairRequest) = CrowdStrikeRepairRequestStatus(row.requestId, row.assetId, row.dryRun,
        row.state, row.httpStatus, row.errorCode, row.message, row.aid,
        row.responseJson?.let { mapper.readValue(it, CrowdStrikeIdentityReconcileResponse::class.java) },
        row.startedAt, row.completedAt)
}
