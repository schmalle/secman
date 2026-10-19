package com.secman.service.aibuddy

import com.secman.dto.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class AiBuddyReportValidatorTest {
    @Test fun `invalid coverage references fabricated patches and promoted evidence fail validation`() {
        val now = Instant.now()
        val finding = RecordedFinding(1, "CVE-2025-1111", "Windows Server 2022", "High", "fixture", now, now, now, null, null, false, "INSTALLED")
        val snapshot = AiBuddySnapshot(AiBuddySystem(2, "local-only", null), now, now, now, listOf(finding), emptyList(), emptyList(), emptyList(), emptyList(), "snapshot")
        val candidate = RemediationCandidate("c1", "Windows Server 2022", AiBuddyActionType.OS_UPDATE, listOf("KB1111111"), findingRefs = setOf("f1"), evidence = emptyList(), applicability = listOf(AiBuddyCheck("OS_BUILD")))
        val report = RemediationPlanner().report("job", now.plusSeconds(100), snapshot, listOf(candidate), "fixture", emptySet(), false, 7)
        val validator = AiBuddyReportValidator()
        validator.validate(report, snapshot, listOf(candidate))
        for (bad in listOf(report.copy(findings = emptyList()), report.copy(scope = report.scope.copy(uniqueCveCount = 150)),
            report.copy(actions = listOf(report.actions.single().copy(patchIds = listOf("KB9999999")))),
            report.copy(actions = listOf(report.actions.single().copy(evidenceState = AiBuddyEvidenceState.VERIFIED))),
            report.copy(actions = listOf(report.actions.single().copy(coveredFindingRefs = listOf("f-other-asset")))))) {
            assertThrows<AiBuddyFailure> { validator.validate(bad, snapshot, listOf(candidate)) }
        }
        // A provider's installation claim cannot resolve a vulnerability or create trusted installation evidence.
        val claimed = candidate.copy(installedAt = now)
        val observation = RemediationPlanner().report("job", now.plusSeconds(100), snapshot, listOf(claimed), null, emptySet(), false, 7).remediationObservations.single()
        org.assertj.core.api.Assertions.assertThat(observation.installationState).isEqualTo("UNKNOWN")
        org.assertj.core.api.Assertions.assertThat(observation.vulnerabilityState).isEqualTo("STILL_RECORDED")
    }
}
