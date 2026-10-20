package com.secman.cli.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.micronaut.http.HttpRequest
import io.micronaut.http.MediaType
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.exceptions.HttpClientResponseException
import java.util.UUID

internal class RepairRequestProblem(val requestId: String, val httpStatus: Int, val errorCode: String,
    message: String, val aid: String? = null) : RuntimeException(message)
internal class RepairRequestUnknown(val requestId: String, message: String) : RuntimeException(message)

/** One authenticated session; each repair remains a separate server transaction. */
internal class CrowdStrikeIdentityRepairSession(
    private val http: BlockingHttpClient,
    private val mapper: ObjectMapper,
    private val token: String,
    private val apply: Boolean,
    private val reason: String
) {
    private val endpoint = "/api/crowdstrike/identity-reconcile"

    fun repair(assetId: Long, aids: Set<String>, onStart: (String) -> Unit = {}): JsonNode {
        val id = UUID.randomUUID().toString()
        onStart(id)
        val body = mapOf("assetId" to assetId, "expectedAids" to aids, "reason" to reason.trim(), "dryRun" to !apply, "requestId" to id)
        return try {
            val response = http.retrieve(HttpRequest.POST(endpoint, body).bearerAuth(token).accept(MediaType.APPLICATION_JSON), String::class.java)
            validateReport(mapper.readTree(response), assetId, aids)
        } catch (e: HttpClientResponseException) {
            if (e.status.code in setOf(401, 403)) throw e
            val problem = problem(e, id)
            if (e.status.code >= 500 || e.status.code == 408 || problem.errorCode == "REQUEST_IN_PROGRESS") recover(id, assetId, aids)
            else throw problem
        } catch (e: Exception) {
            recover(id, assetId, aids)
        }
    }

    fun requestStatus(id: String): JsonNode {
        require(id.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) { "Request status requires a UUID" }
        val normalized = UUID.fromString(id).toString()
        val response = http.retrieve(HttpRequest.GET<Any>("$endpoint/requests/$normalized").bearerAuth(token)
            .accept(MediaType.APPLICATION_JSON), String::class.java)
        val status = mapper.readTree(response)
        check(status != null && status.path("requestId").asText() == normalized &&
            status.path("state").asText() in setOf("RUNNING", "PREVIEWED", "REPAIRED", "REJECTED", "FAILED") &&
            status.path("assetId").isIntegralNumber && status.path("assetId").asLong() > 0 && status.path("dryRun").isBoolean()) { "Invalid request status response" }
        return status
    }

    private fun recover(id: String, assetId: Long, aids: Set<String>): JsonNode {
        val status = try { requestStatus(id) } catch (e: Exception) {
            throw RepairRequestUnknown(id, "Request outcome is unconfirmed; query --request-status $id. No write was retried")
        }
        if (status.path("assetId").asLong() != assetId || status.path("dryRun").asBoolean() != !apply)
            throw RepairRequestUnknown(id, "Request status does not match the submitted repair; inspect backend audit")
        return when (status.path("state").asText()) {
            "PREVIEWED", "REPAIRED" -> try { validateReport(status.path("report"), assetId, aids) } catch (e: Exception) {
                throw RepairRequestUnknown(id, "Terminal request response is incomplete; inspect backend audit")
            }
            "REJECTED", "FAILED" -> throw RepairRequestProblem(id, status.path("httpStatus").asInt(502),
                safe(status.path("errorCode").asText("REPAIR_EXECUTION_FAILED")),
                safe(status.path("message").asText("Repair did not complete")), status.path("aid").takeIf { it.isTextual }?.asText())
            else -> throw RepairRequestUnknown(id, "No terminal result is recorded; the request may still be running or was interrupted. Query --request-status $id")
        }
    }

    private fun validateReport(report: JsonNode?, assetId: Long, aids: Set<String>): JsonNode {
        check(report != null && report.path("assetId").isIntegralNumber && report.path("assetId").asLong(-1) == assetId &&
            report.path("dryRun").isBoolean && report.path("dryRun").asBoolean() == !apply &&
            report.path("devices").isArray && report.path("devices").size() == aids.size &&
            report.path("devices").map { it.path("aid").asText() }.toSet() == aids &&
            (!apply || report.path("devices").all { it.path("assetId").isIntegralNumber && it.path("assetId").asLong(-1) > 0 })) {
            "Incomplete repair response"
        }
        return report
    }

    fun allCandidates(afterId: Long, size: Int, emit: (JsonNode) -> Unit): ObjectNode {
        var cursor = afterId
        var lastScanned = afterId
        var resume = afterId
        var upper: Long? = null
        var complete = false
        var blockedResume = false
        val counts = linkedMapOf("PREVIEWED" to 0L, "REPAIRED" to 0L, "REJECTED" to 0L,
            "ALREADY_RESOLVED" to 0L, "FAILED" to 0L, "UNKNOWN" to 0L)
        var failure: String? = null
        try {
            do {
                val page = page(cursor, size, upper)
                upper = page.path("throughId").asLong()
                var stop = false
                for (candidate in page.path("candidates")) {
                    val id = candidate.path("assetId").asLong()
                    lastScanned = id
                    val aids = candidate.path("expectedAids").map { it.asText() }.toSet()
                    var report: JsonNode? = null
                    var message: String? = null
                    var errorCode: String? = null
                    var aid: String? = null
                    var requestId: String? = null
                    val status = try {
                        report = repair(id, aids) { started ->
                            requestId = started
                            emit(mapper.createObjectNode().put("type", "request").put("assetId", id).put("requestId", started)
                                .put("status", "STARTED").put("resumeAfterId", resume))
                        }
                        if (apply) "REPAIRED" else "PREVIEWED"
                    } catch (e: RepairRequestProblem) {
                        message = e.message
                        requestId = e.requestId
                        errorCode = e.errorCode
                        aid = e.aid
                        if (e.errorCode == "ALREADY_RESOLVED") "ALREADY_RESOLVED" else if (e.httpStatus in 400..499) "REJECTED" else "FAILED"
                    } catch (e: RepairRequestUnknown) {
                        message = e.message
                        requestId = e.requestId
                        errorCode = "OUTCOME_UNCONFIRMED"
                        "UNKNOWN"
                    } catch (e: HttpClientResponseException) {
                        message = "HTTP ${e.status.code}; review identity evidence and backend audit records"
                        when (e.status.code) {
                            401, 403 -> { stop = true; "FAILED" }
                            in 400..499 -> "REJECTED"
                            else -> "FAILED"
                        }
                    } catch (e: Exception) {
                        message = "Repair outcome could not be confirmed; inspect audit records before retrying"
                        "UNKNOWN"
                    }
                    counts[status] = counts.getValue(status) + 1
                    if (status in setOf("REJECTED", "FAILED", "UNKNOWN")) blockedResume = true
                    if (!blockedResume) resume = id
                    val event = mapper.createObjectNode().put("type", "candidate").put("assetId", id)
                        .put("hostname", candidate.path("hostname").asText()).put("status", status).put("resumeAfterId", resume)
                    report?.let { event.set<JsonNode>("report", it) }
                    message?.let { event.put("message", it) }
                    requestId?.let { event.put("requestId", it) }
                    errorCode?.let { event.put("errorCode", it) }
                    aid?.let { event.put("aid", it) }
                    emit(event)
                    if (stop) break
                }
                if (stop) {
                    failure = "Authorization failed during processing; discovery stopped"
                    break
                }
                cursor = page.path("nextAfterId").asLong()
                lastScanned = cursor
                if (!blockedResume) resume = cursor
                complete = !page.path("hasMore").asBoolean()
            } while (!complete)
        } catch (e: Exception) {
            failure = "Candidate discovery or output failed; earlier repairs may have committed. Resume from the reported checkpoint."
        }
        val summary = mapper.createObjectNode().put("type", "summary").put("dryRun", !apply).put("complete", complete)
            .put("lastScannedAssetId", lastScanned).put("resumeAfterId", resume).put("discovered", counts.values.sum())
            .put("unresolved", listOf("REJECTED", "FAILED", "UNKNOWN").sumOf { counts.getValue(it) })
        upper?.let { summary.put("throughId", it) }
        val totals = summary.putObject("totals")
        counts.forEach { (status, count) -> totals.put(status, count) }
        failure?.let { summary.put("error", it) }
        return summary
    }

    private fun page(afterId: Long, size: Int, upper: Long?): JsonNode {
        val path = "$endpoint/candidates?afterId=$afterId&limit=$size" + (upper?.let { "&throughId=$it" } ?: "")
        val response = http.retrieve(HttpRequest.GET<Any>(path).bearerAuth(token).accept(MediaType.APPLICATION_JSON), String::class.java)
        val page = mapper.readTree(response)
        check(page != null && page.path("candidates").isArray && page.path("candidates").size() <= size &&
            page.path("nextAfterId").isIntegralNumber && page.path("nextAfterId").canConvertToLong() &&
            page.path("throughId").isIntegralNumber && page.path("throughId").canConvertToLong() &&
            page.path("throughId").asLong() >= 0 && (upper == null || upper == page.path("throughId").asLong()) &&
            page.path("hasMore").isBoolean && page.path("nextAfterId").asLong() >= afterId &&
            (!page.path("hasMore").asBoolean() || page.path("nextAfterId").asLong() > afterId)) { "Invalid candidate page" }
        var prior = afterId
        for (candidate in page.path("candidates")) {
            val aids = candidate.path("expectedAids")
            val id = candidate.path("assetId")
            check(id.isIntegralNumber && id.canConvertToLong() && id.asLong() > prior &&
                id.asLong() <= page.path("nextAfterId").asLong() && id.asLong() <= page.path("throughId").asLong() &&
                candidate.path("hostname").isTextual && aids.isArray && aids.size() in 2..100 &&
                aids.all { it.isTextual && it.asText().matches(Regex("[A-Za-z0-9_-]{1,64}")) } &&
                aids.map { it.asText() }.distinct().size == aids.size()) { "Invalid candidate identity" }
            prior = id.asLong()
        }
        check(page.path("nextAfterId").asLong() <= page.path("throughId").asLong() ||
            (page.path("candidates").isEmpty && !page.path("hasMore").asBoolean() &&
                page.path("nextAfterId").asLong() == afterId)) { "Invalid candidate cursor" }
        return page
    }

    private fun problem(error: HttpClientResponseException, requestId: String): RepairRequestProblem {
        val body = try { error.response.getBody(String::class.java).orElse(null)?.let(mapper::readTree) } catch (e: Exception) { null }
        return RepairRequestProblem(requestId, error.status.code, safe(body?.path("errorCode")?.asText() ?: "HTTP_${error.status.code}"),
            safe(body?.path("error")?.asText() ?: "Backend rejected repair (HTTP ${error.status.code})"),
            body?.path("aid")?.takeIf { it.isTextual }?.asText()?.let(::safe))
    }

    private fun safe(text: String): String = text.filterNot(Char::isISOControl).take(512)
}
