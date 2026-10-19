package com.secman.service.aibuddy

import com.secman.dto.*
import jakarta.inject.Singleton
import java.time.Duration
import java.time.Instant

@Singleton
class RemediationPlanner {
    fun groupInvestigations(input: List<RemediationCandidate>): List<RemediationCandidate> {
        val investigations = input.filter { !it.relationshipVerified && it.type == AiBuddyActionType.INVESTIGATION }
            .groupBy { it.product }.map { (product, group) ->
                group.first().copy(id = "c" + digest("investigation:$product".toByteArray()).take(20),
                    findingRefs = group.flatMap { it.findingRefs }.toSet(),
                    evidence = group.flatMap { it.evidence }.distinctBy { it.ref },
                    applicability = group.flatMap { it.applicability }.distinct())
            }
        return input.filter { it.relationshipVerified || it.type != AiBuddyActionType.INVESTIGATION } + investigations
    }

    /** Only equal product variants can share a supersedence edge. No LLM-derived edges enter this graph. */
    fun consolidate(input: List<RemediationCandidate>): List<RemediationCandidate> {
        val merged = input.groupBy { it.id }.values.mapNotNull { group ->
            val first = group.first()
            if (group.any { it.product != first.product || it.patchIds != first.patchIds || it.type != first.type } ||
                group.mapNotNull { it.targetVersion }.distinct().size > 1 || group.map { it.reboot }.distinct().size > 1) return@mapNotNull null
            first.copy(findingRefs = group.flatMap { it.findingRefs }.toSet(), evidence = group.flatMap { it.evidence }.distinctBy { it.ref },
                supersedes = group.flatMap { it.supersedes }.toSet(), relationshipVerified = group.all { it.relationshipVerified })
        }
        fun variant(c: RemediationCandidate) = c.applicability.firstOrNull { it.code == "PRODUCT_VARIANT" }?.value
        val byId = merged.associateBy { it.id }
        val edges = merged.associate { c -> c.id to if (c.supersedes.isEmpty()) emptyList() else merged.filter { old ->
            old.id != c.id && old.product == c.product && variant(old) == variant(c) &&
                old.relationshipVerified && old.patchIds.any { it in c.supersedes }
        }.map { it.id } }
        val memo = mutableMapOf<String, Set<String>>()
        fun ancestors(id: String, path: Set<String>): Set<String> {
            if (id in path || path.size > 128) throw AiBuddyFailure("CONTRADICTORY_EVIDENCE")
            memo[id]?.let { return it }
            val inherited = edges[id].orEmpty().flatMap { old -> listOf(old) + ancestors(old, path + id) }.toSet()
            memo[id] = inherited
            return inherited
        }
        val expanded = merged.map { c ->
            val inherited = ancestors(c.id, emptySet()).map { byId.getValue(it) }
            c.copy(findingRefs = c.findingRefs + inherited.flatMap { it.findingRefs },
                evidence = (c.evidence + inherited.flatMap { it.evidence }).distinctBy { it.ref })
        }
        return expanded.filter { old -> expanded.none { newer ->
            newer.id != old.id && newer.product == old.product && variant(newer) == variant(old) &&
                newer.relationshipVerified && newer.state.ordinal <= old.state.ordinal && newer.patchIds.none { it in old.supersedes } &&
                newer.supersedes.containsAll(old.patchIds) && old.patchIds.isNotEmpty() && newer.findingRefs.containsAll(old.findingRefs)
        } }

    }

    /** Greedy consolidation is evidence-supported, not a claim of mathematical minimality. */
    fun select(candidates: List<RemediationCandidate>): List<RemediationCandidate> {
        val remaining = candidates.toMutableList()
        val covered = mutableSetOf<String>()
        val selected = mutableListOf<RemediationCandidate>()
        while (remaining.isNotEmpty()) {
            val next = remaining.sortedWith(compareBy<RemediationCandidate> { it.state.ordinal }
                .thenByDescending { (it.findingRefs - covered).size }.thenBy { it.id }).first()
            remaining.remove(next)
            if ((next.findingRefs - covered).isEmpty()) continue
            selected += next
            covered += next.findingRefs
        }
        if (selected.any { c -> !selected.map { it.id }.containsAll(c.dependsOn) }) throw AiBuddyFailure("CONTRADICTORY_EVIDENCE")
        return selected
    }

    fun report(jobId: String, expires: Instant, snapshot: AiBuddySnapshot, candidates: List<RemediationCandidate>,
               model: String?, limitations: Set<String>, changed: Boolean, staleDays: Long): AiBuddyReport {
        val now = Instant.now()
        val considered = snapshot.findings.filterNot { it.excluded }
        val actions = candidates.mapIndexed { index, c -> AiBuddyAction(c.id, c.type, index + 1, c.state, c.product,
            c.patchIds, c.targetVersion, c.applicability, c.prerequisites, c.dependsOn.toList(), c.findingRefs.sorted(),
            if (c.state == AiBuddyEvidenceState.INSUFFICIENT_INFORMATION) emptyList() else considered.filter { it.ref in c.findingRefs }.mapNotNull { it.cve }.distinct().sorted(),
            when (c.state) { AiBuddyEvidenceState.VERIFIED -> "VERIFIED_COVERAGE"; AiBuddyEvidenceState.REQUIRES_APPLICABILITY_CHECK -> "CONDITIONAL_COVERAGE"; else -> "NO_VERIFIED_COVERAGE" },
            c.reboot, c.evidence.map { it.ref }, if (c.relationshipVerified) "VENDOR_SUPPORTED_REMEDIATION" else "PROVIDER_ADVICE_REQUIRES_VERIFICATION") }
        val evidence = candidates.flatMap { it.evidence }.distinctBy { it.ref }
        val ledger = snapshot.findings.map { f ->
            val matching = actions.filter { f.ref in it.coveredFindingRefs }
            val disposition = when {
                f.excluded -> "EXCLUDED_INSTALLER_ARTIFACT"
                matching.any { it.evidenceState == AiBuddyEvidenceState.VERIFIED } -> "VERIFIED_COVERAGE"
                matching.any { it.evidenceState == AiBuddyEvidenceState.REQUIRES_APPLICABILITY_CHECK } -> "CONDITIONAL_COVERAGE"
                else -> "NO_VERIFIED_REMEDIATION"
            }
            AiBuddyFinding(f.ref, f.source, f.identifier, f.product, excepted = f.excepted, disposition = disposition,
                actionRefs = matching.map { it.id }, reasonCodes = if (disposition == "NO_VERIFIED_REMEDIATION") listOf("INSUFFICIENT_EVIDENCE") else emptyList())
        }
        val verified = ledger.count { it.disposition == "VERIFIED_COVERAGE" }
        val conditional = ledger.count { it.disposition == "CONDITIONAL_COVERAGE" }
        val unknown = ledger.count { it.disposition == "NO_VERIFIED_REMEDIATION" }
        val staleReasons = buildList {
            if (snapshot.lastSeen == null) add("LAST_SEEN_MISSING")
            else if (Duration.between(snapshot.lastSeen, now).toDays() >= staleDays) add("ASSET_NOT_RECENTLY_SEEN")
            if (snapshot.sensorLastSeen != null && Duration.between(snapshot.sensorLastSeen, now).toDays() >= staleDays) add("SENSOR_NOT_RECENTLY_SEEN")
            if (considered.isNotEmpty()) {
                val imported = considered.mapNotNull { it.importAt }.maxOrNull()
                if (imported == null) add("FINDING_IMPORT_TIME_MISSING")
                else if (Duration.between(imported, now).toDays() >= staleDays) add("FINDINGS_NOT_RECENTLY_IMPORTED")
            }
            if (changed) add("SOURCE_CHANGED")
        }
        val missing = actions.flatMap { a -> (a.applicability + a.prerequisites).filter { it.required && it.satisfied != true }
            .map { AiBuddyMissingInfo(it.code, a.coveredFindingRefs, it.code) } } +
            ledger.filter { it.disposition == "NO_VERIFIED_REMEDIATION" }.map { AiBuddyMissingInfo("INSUFFICIENT_EVIDENCE", listOf(it.ref), "VERIFY_VENDOR_REMEDIATION") }
        return AiBuddyReport(jobId = jobId, generatedAt = now, expiresAt = expires, system = snapshot.system,
            snapshot = AiBuddySnapshotInfo(snapshot.capturedAt, snapshot.fingerprint, snapshot.lastSeen, snapshot.sensorLastSeen,
                snapshot.findings.mapNotNull { it.importAt }.minOrNull(), snapshot.findings.mapNotNull { it.importAt }.maxOrNull(),
                snapshot.products.mapNotNull { it.importedAt }.maxOrNull(), evidence.map { it.retrievedAt }.maxOrNull(), changed,
                staleReasons.isNotEmpty(), staleReasons),
            scope = AiBuddyScope(retainedFindingCount = snapshot.findings.size, consideredFindingCount = considered.size,
                uniqueCveCount = considered.mapNotNull { it.cve }.distinct().size, nonCveFindingCount = considered.count { it.cve == null },
                duplicateRowCount = considered.size - considered.distinctBy { Triple(it.identifier, it.product, it.source) }.size,
                exceptedFindingCount = considered.count { it.excepted }, excludedFindingCount = snapshot.findings.count { it.excluded },
                exclusions = listOf(AiBuddyCount("INSTALLER_ARTIFACT", snapshot.findings.count { it.excluded })),
                sourceCounts = snapshot.findings.groupingBy { it.source }.eachCount().map { AiBuddySourceCount(it.key, it.value) }),
            analysis = AiBuddyAnalysis(if (unknown > 0 || limitations.isNotEmpty()) "PARTIAL" else "COMPLETE_WITHIN_RECORDED_SCOPE", model,
                evidenceVersion = digest(evidence.map { it.ref }.sorted().joinToString().toByteArray()),
                verifiedCoveredFindingCount = verified, conditionalFindingCount = conditional, noVerifiedRemediationCount = unknown,
                limitations = (limitations + (if (snapshot.context.any { it.kind == "EOL_EOL" }) setOf("UNSUPPORTED_SOFTWARE_REQUIRES_VENDOR_UPGRADE_OR_MIGRATION_REVIEW") else emptySet()) + "ADVICE_ONLY" + "NO_MATHEMATICAL_MINIMALITY_CLAIM" + "RECORDED_FINDINGS_ARE_NOT_PROOF_OF_CURRENT_PRESENCE").sorted()),
            actions = actions, findings = ledger, evidence = evidence,
            remediationObservations = actions.map { AiBuddyObservation(it.id) }, missingInformation = missing,
            postRemediationChecks = listOf(AiBuddyPostCheck("VERIFY_INSTALLATION_AND_REBOOT", actions.map { it.id }),
                AiBuddyPostCheck("REFRESH_INVENTORY_AND_RESCAN", actions.map { it.id }), AiBuddyPostCheck("REVIEW_REMAINING_FINDINGS", emptyList())))
    }
}
