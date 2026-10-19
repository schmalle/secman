package com.secman.service.aibuddy

import com.fasterxml.jackson.databind.JsonNode
import com.github.benmanes.caffeine.cache.Caffeine
import com.secman.config.AiBuddyConfig
import com.secman.dto.*
import jakarta.inject.Singleton
import java.net.URI
import java.time.Duration
import java.time.Instant

internal data class MicrosoftRemedy(
    val cve: String, val product: String, val productIds: Set<String>, val kb: String,
    val fixedBuild: String?, val supersedes: Set<String>, val reboot: AiBuddyReboot, val url: String?
)
internal data class MicrosoftDocument(
    val id: String, val revision: String, val publishedAt: Instant?, val retrievedAt: Instant,
    val remedies: List<MicrosoftRemedy>, val bytes: Int
)

@Singleton
open class MicrosoftRemediationEvidenceSource(
    private val config: AiBuddyConfig,
    private val http: AiBuddyHttpTransport,
    private val json: AiBuddyJson
) : RemediationEvidenceSource {
    private val cache = Caffeine.newBuilder().maximumWeight(100L * 1024 * 1024)
        .weigher<String, MicrosoftDocument> { _, v -> v.bytes }
        .expireAfterWrite(Duration.ofHours(24)).build<String, MicrosoftDocument>()
    private val host = "api.msrc.microsoft.com"
    private val base = "https://$host/cvrf/v3.0"

    override fun fetch(snapshot: AiBuddySnapshot, budget: EvidenceBudget): RemediationEvidenceResult {
        if (!budget.config.microsoftEvidenceEnabled) return RemediationEvidenceResult(emptyList(), setOf("MICROSOFT_EVIDENCE_DISABLED"))
        val windowsFindings = snapshot.findings.filter { !it.excluded && it.cve != null &&
            AiBuddyProducts.canonical(it.product)?.startsWith("Windows Server ") == true }
        if (windowsFindings.isEmpty()) return RemediationEvidenceResult(emptyList())
        val candidates = mutableListOf<RemediationCandidate>()
        val limitations = mutableSetOf<String>()
        try {
            val index = get("/updates", budget, 3 * 1024 * 1024)
            val entries = index.path("value")
            if (!entries.isArray) throw AiBuddyFailure("EVIDENCE_UNAVAILABLE")
            val releases = entries.sortedByDescending { it.path("CurrentReleaseDate").asText() }
            if (releases.size > config.maxEvidenceDocuments) limitations.add("VENDOR_DOCUMENT_SEARCH_BOUNDED")
            for (entry in releases.take(config.maxEvidenceDocuments)) {
                budget.checkpoint()
                val id = entry.path("ID").asText()
                if (!Regex("[0-9]{4}-(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)").matches(id)) continue
                val document = cache.getIfPresent(id) ?: parseDocument(id, get("/cvrf/$id", budget, 10 * 1024 * 1024), Instant.now()).also {
                    cache.put(id, it)
                }
                val osFamily = AiBuddyProducts.canonical(snapshot.system.osNameVersion)
                candidates.addAll(candidates(document, windowsFindings).map { c ->
                    if (osFamily != null && osFamily != c.product) limitations += "CONFLICTING_PLATFORM_INVENTORY"
                    c.copy(applicability = c.applicability + AiBuddyCheck("MATCH_RECORDED_OS_FAMILY", satisfied = osFamily?.let { it == c.product }))
                })
            }
        } catch (e: AiBuddyFailure) {
            if (e.code == "CANCELLED") throw e
            limitations.add(e.code)
        }
        return RemediationEvidenceResult(candidates, limitations)
    }

    private fun get(path: String, budget: EvidenceBudget, maxBytes: Int): JsonNode {
        budget.beforeRequest()
        val response = http.request(URI.create(base + path), setOf(host), 15, maxBytes)
        budget.accept(response.body.size)
        if (response.status !in 200..299) throw AiBuddyFailure("EVIDENCE_UNAVAILABLE")
        return json.parse(response.body)
    }

    internal fun parseDocument(id: String, root: JsonNode, now: Instant): MicrosoftDocument {
        val tree = root.path("ProductTree")
        val vulnerabilities = root.path("Vulnerability")
        if (!tree.isObject || !vulnerabilities.isArray) throw AiBuddyFailure("EVIDENCE_UNAVAILABLE")
        val products = mutableMapOf<String, String>()
        fun walk(node: JsonNode) {
            if (node.isObject) {
                val productId = node.path("ProductID")
                if (productId.isTextual && node.path("Value").isTextual) products[productId.asText()] = node.path("Value").asText()
                node.elements().forEachRemaining { walk(it) }
            } else if (node.isArray) node.forEach(::walk)
        }
        walk(tree)
        val remedies = mutableListOf<MicrosoftRemedy>()
        for (v in vulnerabilities) {
            val cve = v.path("CVE").asText().uppercase()
            if (!RecordedFinding.CVE.matches(cve)) continue
            val affected = v.path("ProductStatuses").filter { it.path("Type").asInt(-1) == 3 }
                .flatMap { it.path("ProductID").map(JsonNode::asText) }.toSet()
            for (r in v.path("Remediations")) {
                if (r.path("Type").asInt(-1) != 2) continue
                val kb = r.path("Description").path("Value").asText().removePrefix("KB")
                if (!Regex("[0-9]{6,8}").matches(kb)) continue
                val ids = r.path("ProductID").map(JsonNode::asText).filter { it in affected }.toSet()
                val families = ids.mapNotNull { AiBuddyProducts.canonical(products[it]) }.toSet()
                for (family in families) {
                    val matchedIds = ids.filter { AiBuddyProducts.canonical(products[it]) == family }.toSet()
                    val old = r.path("Supercedence").asText().split(',', ';').map { it.trim().removePrefix("KB") }
                        .filter { Regex("[0-9]{6,8}").matches(it) }.map { "KB$it" }.toSet()
                    val reboot = when (r.path("RestartRequired").path("Value").asText().lowercase()) {
                        "yes" -> AiBuddyReboot.REQUIRED
                        "no" -> AiBuddyReboot.NOT_REQUIRED
                        else -> AiBuddyReboot.UNKNOWN
                    }
                    remedies += MicrosoftRemedy(cve, family, matchedIds, "KB$kb",
                        AiBuddyProducts.version(r.path("FixedBuild").asText()), old, reboot,
                        safeMicrosoftLink(r.path("URL").asText()))
                }
            }
        }
        val published = parseInstant(root.path("DocumentTracking").path("CurrentReleaseDate").asText())
        val revision = digest(json.mapper.writeValueAsBytes(listOf(products.toSortedMap(), remedies)))
        return MicrosoftDocument(id, revision, published, now, remedies, json.mapper.writeValueAsBytes(root).size)
    }

    internal fun candidates(document: MicrosoftDocument, findings: List<RecordedFinding>): List<RemediationCandidate> {
        val groups = document.remedies.groupBy { Triple(it.product, it.productIds.sorted().joinToString(","), it.kb) }
        return groups.mapNotNull { (key, remedies) ->
            val cves = remedies.map { it.cve }.toSet()
            val matched = findings.filter { it.cve in cves && AiBuddyProducts.canonical(it.product) == key.first }
            if (matched.isEmpty()) return@mapNotNull null
            val ref = "e" + digest("msrc:${document.id}:${document.revision}:$key".toByteArray()).take(20)
            val evidence = AiBuddyEvidence(ref, "MICROSOFT", "MSRC ${document.id}: ${key.third}",
                "$base/cvrf/${document.id}", document.retrievedAt, document.publishedAt, document.revision,
                listOf("PATCH_CVE_RELATIONSHIP", "PRODUCT_SCOPE", "SUPERSEDENCE"))
            val vendorLinks = remedies.mapNotNull { it.url }.distinct().mapIndexed { index, url ->
                AiBuddyEvidence("${ref}v$index", "MICROSOFT", "Vendor update details: ${key.third}", url,
                    document.retrievedAt, document.publishedAt, document.revision, listOf("VENDOR_UPDATE_DETAILS"))
            }
            val fixedBuilds = remedies.mapNotNull { it.fixedBuild }.distinct()
            val reboot = remedies.map { it.reboot }.distinct().singleOrNull() ?: AiBuddyReboot.UNKNOWN
            RemediationCandidate("c" + digest(key.toString().toByteArray()).take(20), key.first, AiBuddyActionType.OS_UPDATE,
                listOf(key.third), fixedBuilds.singleOrNull(), matched.map { it.ref }.toSet(), listOf(evidence) + vendorLinks,
                listOf(AiBuddyCheck("PRODUCT_VARIANT", key.second, listOf(ref)), AiBuddyCheck("OS_BUILD"), AiBuddyCheck("ARCHITECTURE"), AiBuddyCheck("INSTALLED_UPDATES")),
                listOf(AiBuddyCheck("SERVICING_PREREQUISITES", evidenceRefs = listOf(ref))), reboot,
                remedies.flatMap { it.supersedes }.toSet())
        }
    }

    internal fun evidenceCurrent(evidence: AiBuddyEvidence, now: Instant): Boolean {
        if (Duration.between(evidence.retrievedAt, now) >= Duration.ofHours(24)) return false
        val id = evidence.title.substringAfter("MSRC ").substringBefore(':')
        return cache.getIfPresent(id)?.revision?.let { it == evidence.revision } ?: true
    }

    companion object {
        fun parseInstant(value: String): Instant? = try {
            Instant.parse(if (value.endsWith('Z')) value else "${value}Z")
        } catch (e: Exception) { null }
        fun safeMicrosoftLink(value: String): String? = try {
            val uri = URI(value)
            val safePath = when (uri.host) {
                "api.msrc.microsoft.com" -> Regex("/cvrf/v3\\.0/cvrf/[0-9]{4}-(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)").matches(uri.path) && uri.query == null
                "support.microsoft.com" -> Regex("/(help|kb)/[0-9]{6,8}/?").matches(uri.path) && uri.query == null
                "catalog.update.microsoft.com", "www.catalog.update.microsoft.com" ->
                    uri.path in setOf("/Search.aspx", "/v7/site/Search.aspx") && Regex("q=KB[0-9]{6,8}").matches(uri.query ?: "")
                "msrc.microsoft.com" -> Regex("/update-guide/(?:en-US/)?vulnerability/CVE-[0-9]{4}-[0-9]{4,}").matches(uri.path) && uri.query == null
                else -> false
            }
            value.takeIf { uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) && uri.fragment == null && safePath }
        } catch (e: Exception) { null }

    }
}
