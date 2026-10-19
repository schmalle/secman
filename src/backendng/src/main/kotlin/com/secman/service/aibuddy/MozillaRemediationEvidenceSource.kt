package com.secman.service.aibuddy

import com.secman.config.AiBuddyConfig
import com.secman.dto.*
import jakarta.inject.Singleton
import org.jsoup.Jsoup
import java.net.URI
import java.time.Instant

internal data class MozillaAdvisory(val path: String, val version: String, val channel: String, val cves: Set<String>)

/** Only Mozilla's fixed-version statements establish CVE coverage; the release catalogue supplies targets. */
@Singleton
open class MozillaRemediationEvidenceSource(
    private val config: AiBuddyConfig, private val http: AiBuddyHttpTransport, private val json: AiBuddyJson
) : RemediationEvidenceSource {
    override fun fetch(snapshot: AiBuddySnapshot, budget: EvidenceBudget): RemediationEvidenceResult {
        val findings = snapshot.findings.filter { !it.excluded && it.cve != null && AiBuddyProducts.canonical(it.product) == "mozilla firefox" }
        if (findings.isEmpty()) return RemediationEvidenceResult(emptyList())
        val limitations = mutableSetOf<String>()
        try {
            fun get(host: String, path: String, maxBytes: Int): ByteArray {
                budget.beforeRequest()
                val response = http.request(URI.create("https://$host$path"), setOf(host), 15, maxBytes)
                budget.accept(response.body.size)
                if (response.status != 200) throw AiBuddyFailure("MOZILLA_EVIDENCE_UNAVAILABLE")
                return response.body
            }
            val now = Instant.now()
            val versions = json.parse(get("product-details.mozilla.org", "/1.0/firefox_versions.json", 128 * 1024))
            val products = snapshot.products.filter { it.productClass != "INSTALLER_ARTIFACT" && AiBuddyProducts.canonical(it.name) == "mozilla firefox" }
            fun channel(product: RecordedProduct): String? = when {
                product.name.contains("ESR", true) || product.version?.endsWith("esr", true) == true -> "ESR"
                product.name.contains("Release", true) -> "RELEASE"
                else -> null
            }
            val channels = products.mapNotNull(::channel).distinct()
            val installedChannel = channels.singleOrNull()?.takeIf { products.all { p -> channel(p) == it } }
            val installed = products.mapNotNull { AiBuddyProducts.version(it.version?.removeSuffix("esr")) }.distinct().singleOrNull()
            val release = AiBuddyProducts.version(versions.path("LATEST_FIREFOX_VERSION").asText())
            val esrVersions = listOf("FIREFOX_ESR", "FIREFOX_ESR_NEXT", "FIREFOX_ESR115")
                .mapNotNull { AiBuddyProducts.version(versions.path(it).asText().removeSuffix("esr")) }.distinct()
            val target = if (installedChannel == "RELEASE") release else if (installedChannel == "ESR" && installed != null)
                esrVersions.firstOrNull { it.substringBefore('.') == installed.substringBefore('.') } else null
            val catalogue = AiBuddyEvidence("e" + digest("mozilla:versions:${versions}".toByteArray()).take(20), "MOZILLA",
                "Mozilla published Firefox versions", "https://product-details.mozilla.org/1.0/firefox_versions.json", now, null,
                digest(versions.toString().toByteArray()), listOf("PUBLISHED_RELEASE_VERSION"))
            if (installedChannel == null || target == null || installed == null) {
                return RemediationEvidenceResult(listOf(RemediationCandidate(
                    "c" + digest("mozilla:inventory".toByteArray()).take(20), "mozilla firefox", AiBuddyActionType.INVESTIGATION,
                    findingRefs = findings.map { it.ref }.toSet(), evidence = listOf(catalogue), relationshipVerified = false,
                    applicability = listOf(AiBuddyCheck("FIREFOX_CHANNEL_AND_VERSION", "Record Help → About Firefox version and Release/ESR channel; refresh installed-product inventory"))
                )), setOf("FIREFOX_CHANNEL_AND_VERSION_REQUIRED"))
            }
            val indexPath = "/en-US/security/known-vulnerabilities/" + if (installedChannel == "ESR") "firefox-esr/" else "firefox/"
            val index = Jsoup.parse(get("www.mozilla.org", indexPath, 3 * 1024 * 1024).toString(Charsets.UTF_8))
            val paths = index.select("a[href]").map { it.attr("href") }
                .filter { Regex("/en-US/security/advisories/mfsa[0-9]{4}-[0-9]+/").matches(it) }.distinct()
            if (paths.isEmpty()) throw AiBuddyFailure("MOZILLA_EVIDENCE_UNAVAILABLE")
            if (paths.size > config.maxEvidenceDocuments) limitations += "MOZILLA_DOCUMENT_SEARCH_BOUNDED"
            val evidence = mutableListOf<AiBuddyEvidence>(catalogue)
            val covered = mutableSetOf<String>()
            for (path in paths.take(config.maxEvidenceDocuments)) {
                budget.checkpoint()
                val bytes = get("www.mozilla.org", path, 1024 * 1024)
                for (advisory in parseAdvisories(path, bytes.toString(Charsets.UTF_8))) {
                    val sameBranch = installedChannel == "RELEASE" || advisory.version.substringBefore('.') == installed.substringBefore('.')
                    if (advisory.channel != installedChannel || !sameBranch || compareVersions(target, advisory.version) < 0) continue
                    val matches = findings.filter { it.cve in advisory.cves && compareVersions(installed, advisory.version) < 0 }
                    if (matches.isEmpty()) continue
                    covered += matches.map { it.ref }
                    evidence += AiBuddyEvidence("e" + digest(path.toByteArray()).take(20), "MOZILLA",
                        "Firefox ${advisory.channel}: fixed in ${advisory.version}", "https://www.mozilla.org$path", now, null,
                        digest(bytes), listOf("PATCH_CVE_RELATIONSHIP", "PRODUCT_SCOPE", "FIXED_VERSION"))
                }
            }
            if (covered.isEmpty()) return RemediationEvidenceResult(emptyList(), limitations + "MOZILLA_NO_MATCHING_ADVISORY")
            val candidate = RemediationCandidate("c" + digest("mozilla:$installedChannel:$target".toByteArray()).take(20),
                "mozilla firefox", AiBuddyActionType.APPLICATION_UPDATE, targetVersion = target, findingRefs = covered,
                evidence = evidence.distinctBy { it.ref }, reboot = AiBuddyReboot.NOT_REQUIRED,
                applicability = listOf(AiBuddyCheck("FIREFOX_CHANNEL", installedChannel, satisfied = true),
                    AiBuddyCheck("INSTALLED_VERSION", installed, satisfied = true), AiBuddyCheck("FIREFOX_PLATFORM_SUPPORT"),
                    AiBuddyCheck("CONFIRM_CURRENT_INVENTORY")))
            return RemediationEvidenceResult(listOf(candidate), limitations)
        } catch (e: AiBuddyFailure) {
            if (e.code == "CANCELLED") throw e
            return RemediationEvidenceResult(emptyList(), limitations + "MOZILLA_EVIDENCE_UNAVAILABLE")
        }
    }

    internal fun parseAdvisories(path: String, html: String): List<MozillaAdvisory> {
        val page = Jsoup.parse(html)
        val cves = page.select("section.cve h4[id]").map { it.id().uppercase() }.filter { RecordedFinding.CVE.matches(it) }.toSet()
        if (cves.isEmpty()) return emptyList()
        val fixed = page.select("dt").firstOrNull { it.text() == "Fixed in" }?.nextElementSibling() ?: return emptyList()
        // Multi-product/branch pages need per-CVE applicability that this HTML contract does not provide.
        val products = page.select("dt").firstOrNull { it.text() == "Products" }?.nextElementSibling()?.text()
        if (products !in setOf("Firefox", "Firefox ESR") || fixed.select("li").size != 1) return emptyList()
        return fixed.select("li").mapNotNull { item ->
            val match = Regex("Firefox (ESR )?([0-9]+(?:\\.[0-9]+){0,5})").matchEntire(item.text()) ?: return@mapNotNull null
            MozillaAdvisory(path, match.groupValues[2], if (match.groupValues[1].isEmpty()) "RELEASE" else "ESR", cves)
        }
    }

    internal fun compareVersions(a: String, b: String): Int {
        val left = a.split('.').map { it.toLongOrNull() ?: throw AiBuddyFailure("MOZILLA_EVIDENCE_UNAVAILABLE") }
        val right = b.split('.').map { it.toLongOrNull() ?: throw AiBuddyFailure("MOZILLA_EVIDENCE_UNAVAILABLE") }
        for (i in 0 until maxOf(left.size, right.size)) {
            val difference = (left.getOrNull(i) ?: 0).compareTo(right.getOrNull(i) ?: 0)
            if (difference != 0) return difference
        }
        return 0
    }
}
