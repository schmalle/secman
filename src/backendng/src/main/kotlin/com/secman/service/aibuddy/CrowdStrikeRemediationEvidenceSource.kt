package com.secman.service.aibuddy

import com.secman.config.AiBuddyConfig
import com.secman.crowdstrike.client.CrowdStrikeApiClient
import com.secman.crowdstrike.dto.FalconConfigDto
import com.secman.dto.*
import com.secman.repository.FalconConfigRepository
import jakarta.inject.Singleton
import java.net.URI
import java.net.URLEncoder
import java.time.Instant

/** Read-only enrichment. Spotlight advice is not proof that a particular patch applies. */
@Singleton
open class CrowdStrikeRemediationEvidenceSource(
    private val config: AiBuddyConfig, private val configs: FalconConfigRepository,
    private val client: CrowdStrikeApiClient, private val http: AiBuddyHttpTransport, private val json: AiBuddyJson
) : RemediationEvidenceSource {
    override fun fetch(snapshot: AiBuddySnapshot, budget: EvidenceBudget): RemediationEvidenceResult {
        if (!budget.config.crowdStrikeEvidenceEnabled) return RemediationEvidenceResult(emptyList(), setOf("CROWDSTRIKE_EVIDENCE_DISABLED"))
        if (snapshot.aids.isEmpty()) return RemediationEvidenceResult(emptyList(), setOf("PROVIDER_IDENTITY_MISSING"))
        val falcon = configs.findActiveConfig().orElse(null) ?: return RemediationEvidenceResult(emptyList(), setOf("CROWDSTRIKE_NOT_CONFIGURED"))
        val host = when (falcon.cloudRegion) {
            "us-1" -> "api.crowdstrike.com"
            "us-2" -> "api.us-2.crowdstrike.com"
            "eu-1" -> "api.eu-1.crowdstrike.com"
            "us-gov-1" -> "api.us-gov-1.crowdstrike.com"
            "us-gov-2" -> "api.us-gov-2.crowdstrike.com"
            else -> return RemediationEvidenceResult(emptyList(), setOf("PROVIDER_REGION_UNSUPPORTED"))
        }
        val findingIndex = snapshot.findings.filter { !it.excluded && it.cve != null }.groupBy { Triple(AiBuddyProducts.canonical(it.product), it.cve, it.product?.trim()?.lowercase(java.util.Locale.ROOT)) }
        val candidates = mutableListOf<RemediationCandidate>()
        val limitations = mutableSetOf<String>()
        try {
            budget.beforeRequest()
            val token = client.getAuthToken(FalconConfigDto(falcon.clientId, falcon.clientSecret, "https://$host")).accessToken
            for (aid in snapshot.aids) {
                if (!Regex("[a-fA-F0-9]{32}").matches(aid)) { limitations += "PROVIDER_IDENTITY_INVALID"; continue }
                // Keep a device's evidence private until its entire cursor traversal validates.
                val deviceCandidates = mutableListOf<RemediationCandidate>()
                val remediationIds = mutableMapOf<String, MutableSet<String>>()
                val seenCursors = mutableSetOf<String>()
                val seenIds = mutableSetOf<String>()
                var after: String? = null
                var expected: Int? = null
                do {
                    budget.beforeRequest()
                    val filter = URLEncoder.encode("aid:'$aid'+status:!'closed'", Charsets.UTF_8)
                    val cursor = after?.let { "&after=${URLEncoder.encode(it, Charsets.UTF_8)}" } ?: ""
                    val response = http.request(URI.create("https://$host/spotlight/combined/vulnerabilities/v1?filter=$filter&limit=500&facet=cve&facet=host_info&facet=remediation$cursor"),
                        setOf(host), 15, 5 * 1024 * 1024, headers = mapOf("Authorization" to "Bearer $token"))
                    budget.accept(response.body.size)
                    if (response.status != 200) throw AiBuddyFailure("CROWDSTRIKE_EVIDENCE_UNAVAILABLE")
                    val root = json.parse(response.body)
                    val rows = root.path("resources")
                    val total = root.path("meta").path("pagination").path("total")
                    if (!rows.isArray || !total.isIntegralNumber || total.asInt() !in 0..config.maxFindings ||
                        root.path("errors").let { !it.isMissingNode && (!it.isArray || !it.isEmpty) }) throw AiBuddyFailure("PROVIDER_EVIDENCE_INCOMPLETE")
                    if (expected != null && expected != total.asInt()) throw AiBuddyFailure("PROVIDER_EVIDENCE_INCOMPLETE")
                    expected = total.asInt()
                    for (row in rows) {
                        val id = row.path("id").asText()
                        if (id.isBlank() || !seenIds.add(id) || row.path("aid").asText() != aid ||
                            row.path("host_info").path("aid").let { !it.isMissingNode && it.asText() != aid }) throw AiBuddyFailure("PROVIDER_EVIDENCE_INCOMPLETE")
                        val cve = row.path("cve").path("id").asText().uppercase()
                        if (!RecordedFinding.CVE.matches(cve)) continue
                        for (app in row.path("apps")) {
                            val product = AiBuddyProducts.canonical(app.path("product_name_version").asText()) ?: continue
                            val matched = findingIndex[Triple(product, cve, app.path("product_name_version").asText().trim().lowercase(java.util.Locale.ROOT))].orEmpty()
                            if (matched.isEmpty()) continue
                            val ref = "e" + digest("crowdstrike:$cve:$product".toByteArray()).take(20)
                            // Do not invent exact identifiers from free-text action/title/reference fields.
                            val evidence = AiBuddyEvidence(ref, "CROWDSTRIKE", "Provider finding: $cve", null,
                                Instant.now(), null, digest(row.path("remediation").toString().toByteArray()), listOf("PROVIDER_FINDING_CONTEXT"))
                            val candidateId = "c" + digest("crowdstrike:$product:$cve".toByteArray()).take(20)
                            for (remId in app.path("remediation").path("ids")) {
                                val key = remId.asText()
                                if (Regex("[A-Za-z0-9_-]{1,128}").matches(key)) remediationIds.getOrPut(key) { mutableSetOf() }.add(candidateId)
                            }
                            deviceCandidates += RemediationCandidate(candidateId, product,
                                AiBuddyActionType.INVESTIGATION, findingRefs = matched.map { it.ref }.toSet(), evidence = listOf(evidence),
                                applicability = listOf(AiBuddyCheck("VERIFY_PROVIDER_REMEDIATION")), relationshipVerified = false)
                        }
                    }
                    after = root.path("meta").path("pagination").path("after").asText().takeIf { it.isNotBlank() }
                    if (after != null && !seenCursors.add(after)) throw AiBuddyFailure("PROVIDER_EVIDENCE_INCOMPLETE")
                } while (after != null && seenIds.size < expected)
                if (seenIds.size != expected) throw AiBuddyFailure("PROVIDER_EVIDENCE_INCOMPLETE")
                val advice = readAdvice(host, token, remediationIds.keys.toList(), budget)
                candidates += deviceCandidates.map { c ->
                    c.copy(evidence = c.evidence + advice.filterKeys { key -> c.id in remediationIds[key].orEmpty() }.values)
                }
            }
        } catch (e: AiBuddyFailure) {
            if (e.code == "CANCELLED") throw e
            limitations += e.code
        } catch (e: Exception) {
            limitations += "CROWDSTRIKE_EVIDENCE_UNAVAILABLE"
        }
        return RemediationEvidenceResult(candidates, limitations + "PROVIDER_TEXT_NOT_VERIFIED_PATCH_APPLICABILITY")
    }

    private fun readAdvice(host: String, token: String, ids: List<String>, budget: EvidenceBudget): Map<String, AiBuddyEvidence> {
        val result = mutableMapOf<String, AiBuddyEvidence>()
        for (batch in ids.chunked(100)) {
            budget.beforeRequest()
            val query = batch.joinToString("&") { "ids=${URLEncoder.encode(it, Charsets.UTF_8)}" }
            val response = http.request(URI.create("https://$host/spotlight/entities/remediations/v2?$query"), setOf(host), 15,
                1024 * 1024, headers = mapOf("Authorization" to "Bearer $token"))
            budget.accept(response.body.size)
            if (response.status != 200) throw AiBuddyFailure("CROWDSTRIKE_EVIDENCE_UNAVAILABLE")
            val root = json.parse(response.body)
            val rows = root.path("resources")
            if (!rows.isArray || root.path("errors").let { !it.isMissingNode && (!it.isArray || !it.isEmpty) }) throw AiBuddyFailure("PROVIDER_EVIDENCE_INCOMPLETE")
            val received = mutableSetOf<String>()
            for (row in rows) {
                val id = row.path("id").asText()
                if (id !in batch || !received.add(id)) throw AiBuddyFailure("PROVIDER_EVIDENCE_INCOMPLETE")
                val action = row.path("action").takeIf { it.isTextual }?.asText()?.take(2000)
                val safeUrl = MicrosoftRemediationEvidenceSource.safeMicrosoftLink(row.path("vendor_url").asText())
                result[id] = AiBuddyEvidence("e" + digest("crowdstrike-remedy:$id".toByteArray()).take(20), "CROWDSTRIKE",
                    "Provider remediation advice (requires verification)", safeUrl, Instant.now(),
                    MicrosoftRemediationEvidenceSource.parseInstant(row.path("patch_publication_date").asText()),
                    digest(row.toString().toByteArray()), listOf("PROVIDER_REMEDIATION_ADVICE"), action)
            }
            if (received != batch.toSet()) throw AiBuddyFailure("PROVIDER_EVIDENCE_INCOMPLETE")
        }
        return result
    }

}
