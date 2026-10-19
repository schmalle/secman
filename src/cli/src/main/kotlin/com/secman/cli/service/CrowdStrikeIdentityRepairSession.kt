package com.secman.cli.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.micronaut.http.HttpRequest
import io.micronaut.http.MediaType
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.exceptions.HttpClientResponseException

/** One authenticated session; each repair remains a separate server transaction. */
internal class CrowdStrikeIdentityRepairSession(
    private val http: BlockingHttpClient,
    private val mapper: ObjectMapper,
    private val token: String,
    private val apply: Boolean,
    private val reason: String
) {
    private val endpoint = "/api/crowdstrike/identity-reconcile"

    fun repair(assetId: Long, aids: Set<String>): JsonNode {
        val body = mapOf("assetId" to assetId, "expectedAids" to aids, "reason" to reason.trim(), "dryRun" to !apply)
        val response = http.retrieve(HttpRequest.POST(endpoint, body).bearerAuth(token).accept(MediaType.APPLICATION_JSON), String::class.java)
        val report = mapper.readTree(response)
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
                    val status = try {
                        report = repair(id, aids)
                        if (apply) "REPAIRED" else "PREVIEWED"
                    } catch (e: HttpClientResponseException) {
                        message = "HTTP ${e.status.code}; review identity evidence and backend audit records"
                        when (e.status.code) {
                            409 -> if (alreadyResolved(e)) "ALREADY_RESOLVED" else "REJECTED"
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
                candidate.path("hostname").isTextual && aids.isArray && aids.size() in 2..20 &&
                aids.all { it.isTextual && it.asText().matches(Regex("[A-Za-z0-9_-]{1,64}")) } &&
                aids.map { it.asText() }.distinct().size == aids.size()) { "Invalid candidate identity" }
            prior = id.asLong()
        }
        check(page.path("nextAfterId").asLong() <= page.path("throughId").asLong() ||
            (page.path("candidates").isEmpty && !page.path("hasMore").asBoolean() &&
                page.path("nextAfterId").asLong() == afterId)) { "Invalid candidate cursor" }
        return page
    }

    private fun alreadyResolved(error: HttpClientResponseException): Boolean = try {
        error.response.getBody(String::class.java).orElse(null)?.let {
            mapper.readTree(it).path("errorCode").asText() == "ALREADY_RESOLVED"
        } == true
    } catch (e: Exception) {
        false // An unparseable rejection must never be counted as a successful no-op.
    }
}
