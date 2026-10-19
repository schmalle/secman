package com.secman.service.aibuddy

import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.config.AiBuddyConfig
import com.secman.dto.*
import jakarta.inject.Singleton
import java.time.Instant

/** Only normalized public product vocabulary can cross the model boundary. */
object AiBuddyProducts {
    private val windows = Regex("(?i)^(?:Microsoft )?Windows Server (2016|2019|2022|2025)(?: (Datacenter|Standard))?(?: (?:\\(Server Core installation\\)|Server Core))?(?: (?:10\\.0\\.)?[0-9]{5}(?:\\.[0-9]{1,6})?)?(?: (?:x64|64-bit))?$")
    private val known = Regex("(?i)^(Google Chrome|Mozilla Firefox|Microsoft Edge|Adobe Acrobat|Adobe Reader|Microsoft Office|Microsoft \\.NET|OpenSSL|Apache HTTP Server|Apache Tomcat|nginx|Ubuntu|Red Hat Enterprise Linux|Debian)(?: ([0-9]+(?:\\.[0-9]+){0,5}(?:[-+][a-zA-Z0-9.]+)?))?$")
    fun canonical(raw: String?): String? {
        if (raw == null || raw.length > 120) return null
        val text = raw.trim()
        if (Regex("(?i)^(?:Microsoft )?Windows Server 2019(?: (?:Standard|Datacenter))? 1809(?: (?:x64|64-bit))?$").matches(text)) return "Windows Server 2019"
        if (Regex("(?i)^(?:Mozilla )?Firefox(?: (?:ESR|Release))?(?: [0-9]+(?:\\.[0-9]+){0,5}(?:esr)?)?$").matches(text)) return "mozilla firefox"
        windows.matchEntire(text)?.let { return "Windows Server ${it.groupValues[1]}" }
        known.matchEntire(text)?.let { return it.groupValues[1].lowercase() }
        return null
    }
    fun version(raw: String?): String? = raw?.takeIf { it.length <= 60 && Regex("[0-9]+(?:\\.[0-9]+){0,5}(?:[-+][a-zA-Z0-9.]+)?").matches(it) }
}

data class RemediationCandidate(
    val id: String, val product: String, val type: AiBuddyActionType,
    val patchIds: List<String> = emptyList(), val targetVersion: String? = null,
    val findingRefs: Set<String>, val evidence: List<AiBuddyEvidence>,
    val applicability: List<AiBuddyCheck>, val prerequisites: List<AiBuddyCheck> = emptyList(),
    val reboot: AiBuddyReboot = AiBuddyReboot.UNKNOWN, val supersedes: Set<String> = emptySet(),
    val dependsOn: Set<String> = emptySet(), val relationshipVerified: Boolean = true,
    val installedAt: Instant? = null
) {
    val state: AiBuddyEvidenceState get() = when {
        !relationshipVerified -> AiBuddyEvidenceState.INSUFFICIENT_INFORMATION
        (applicability + prerequisites).any { it.required && it.satisfied != true } -> AiBuddyEvidenceState.REQUIRES_APPLICABILITY_CHECK
        else -> AiBuddyEvidenceState.VERIFIED
    }
}
data class RemediationEvidenceResult(val candidates: List<RemediationCandidate>, val limitations: Set<String> = emptySet())
interface RemediationEvidenceSource {
    fun fetch(snapshot: AiBuddySnapshot, budget: EvidenceBudget): RemediationEvidenceResult
}
class EvidenceBudget(val config: AiBuddyConfig, private val check: () -> Unit) {
    private var requests = 0
    private var bytes = 0L
    fun beforeRequest() {
        check()
        if (++requests > config.maxEvidenceRequests) throw AiBuddyFailure("BUDGET_EXHAUSTED")
    }
    fun accept(size: Int) {
        bytes += size
        if (bytes > config.maxEvidenceBytes) throw AiBuddyFailure("BUDGET_EXHAUSTED")
    }
    fun checkpoint() = check()
}

@Singleton
class AiBuddyJson(mapper: ObjectMapper) {
    val mapper: ObjectMapper = mapper.copy().also {
        it.factory.setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(30).maxStringLength(100_000).build())
        it.factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        it.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    }
    fun parse(bytes: ByteArray): JsonNode = try {
        mapper.readTree(bytes) ?: throw AiBuddyFailure("OUTPUT_INVALID")
    } catch (e: AiBuddyFailure) { throw e } catch (e: Exception) { throw AiBuddyFailure("OUTPUT_INVALID") }
}
