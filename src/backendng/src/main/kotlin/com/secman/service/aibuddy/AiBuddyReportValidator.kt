package com.secman.service.aibuddy

import com.secman.dto.*
import jakarta.inject.Singleton

/** Validate the final assembled contract, including references and evidence states, before publication. */
@Singleton
class AiBuddyReportValidator {
    fun validate(report: AiBuddyReport, snapshot: AiBuddySnapshot, candidates: List<RemediationCandidate>) {
        fun requireValid(condition: Boolean) { if (!condition) throw AiBuddyFailure("REPORT_VALIDATION_FAILED") }
        val findings = snapshot.findings.associateBy { it.ref }
        val evidence = report.evidence.associateBy { it.ref }
        val actions = report.actions.associateBy { it.id }
        val graph = candidates.associateBy { it.id }
        requireValid(report.schemaVersion == 1 && report.system == snapshot.system && report.snapshot.fingerprint == snapshot.fingerprint)
        requireValid(report.findings.size == findings.size && report.findings.map { it.ref }.toSet() == findings.keys)
        requireValid(actions.size == report.actions.size && actions.keys == graph.keys && evidence.size == report.evidence.size)
        requireValid(report.scope.retainedFindingCount == findings.size && report.scope.consideredFindingCount == findings.values.count { !it.excluded })
        requireValid(report.scope.uniqueCveCount == findings.values.filterNot { it.excluded }.mapNotNull { it.cve }.distinct().size)
        for (a in report.actions) {
            val c = graph[a.id] ?: throw AiBuddyFailure("REPORT_VALIDATION_FAILED")
            requireValid(a.patchIds == c.patchIds && a.targetVersion == c.targetVersion && a.evidenceState == c.state)
            requireValid(a.coveredFindingRefs.toSet() == c.findingRefs && a.coveredFindingRefs.all { findings[it]?.excluded == false })
            requireValid(a.coveredCves.toSet() == (if (c.state == AiBuddyEvidenceState.INSUFFICIENT_INFORMATION) emptySet() else c.findingRefs.mapNotNull { findings[it]?.cve }.toSet()))
            requireValid(a.evidenceRefs.toSet() == c.evidence.map { it.ref }.toSet() && a.evidenceRefs.all { it in evidence })
            requireValid(a.dependsOnActionIds.all { id -> actions[id]?.order?.let { it < a.order } == true })
            if (a.evidenceState == AiBuddyEvidenceState.VERIFIED) {
                requireValid(c.relationshipVerified && c.evidence.isNotEmpty() && (a.applicability + a.prerequisites).all { !it.required || it.satisfied == true })
            }
        }
        for (f in report.findings) {
            val original = findings[f.ref]!!
            requireValid(f.identifier == original.identifier && f.product == original.product && f.excepted == original.excepted && f.source == original.source)
            requireValid(f.actionRefs.toSet() == report.actions.filter { f.ref in it.coveredFindingRefs }.map { it.id }.toSet())
        }
        requireValid(report.analysis.verifiedCoveredFindingCount == report.findings.count { it.disposition == "VERIFIED_COVERAGE" })
        requireValid(report.analysis.conditionalFindingCount == report.findings.count { it.disposition == "CONDITIONAL_COVERAGE" })
        requireValid(report.analysis.noVerifiedRemediationCount == report.findings.count { it.disposition == "NO_VERIFIED_REMEDIATION" })
        requireValid(report.analysis.verifiedCoveredFindingCount + report.analysis.conditionalFindingCount + report.analysis.noVerifiedRemediationCount == report.scope.consideredFindingCount)
        requireValid(report.remediationObservations.all { it.installationState == "UNKNOWN" && it.vulnerabilityState == "STILL_RECORDED" })
    }
}
