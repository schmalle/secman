package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.ExceptionKind
import com.secman.domain.ExceptionMatchable
import com.secman.domain.Vulnerability
import com.secman.domain.VulnerabilityException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

@DisplayName("ExceptionMatchIndex")
class ExceptionMatchIndexTest {

    /**
     * Minimal ExceptionMatchable fixture. Previously this reused
     * VulnerabilityStatsRawRow, which was deleted along with the unbounded full-table
     * statistics query it served — the index itself never needed that shape, only these
     * six fields.
     */
    private data class TestRow(
        override val vulnerabilityId: String?,
        override val vulnerableProductVersions: String?,
        override val assetId: Long?,
        override val assetIp: String?,
        override val cloudAccountId: String?,
        override val osVersion: String?
    ) : ExceptionMatchable

    private fun row(
        vulnId: String = "CVE-2024-0001",
        product: String? = null,
        assetId: Long = 1L,
        assetIp: String? = null,
        cloudAccountId: String? = null,
        osVersion: String? = null
    ) = TestRow(
        vulnerabilityId = vulnId,
        vulnerableProductVersions = product,
        assetId = assetId,
        assetIp = assetIp,
        cloudAccountId = cloudAccountId,
        osVersion = osVersion
    )

    private fun exception(
        subject: VulnerabilityException.Subject,
        scope: VulnerabilityException.Scope,
        subjectValue: String? = null,
        scopeValue: String? = null,
        assetId: Long? = null,
        kind: ExceptionKind = ExceptionKind.VULNERABILITY
    ) = VulnerabilityException(
        subject = subject,
        scope = scope,
        kind = kind,
        subjectValue = subjectValue,
        scopeValue = scopeValue,
        assetId = assetId,
        reason = "test",
        createdBy = "tester"
    )

    @Test
    fun `no exceptions means nothing is excepted`() {
        val index = ExceptionMatchIndex(emptyList())
        assertThat(index.isExcepted(row())).isFalse()
    }

    @Test
    fun `ALL_VULNS ASSET scope only excepts rows for that asset`() {
        val index = ExceptionMatchIndex(listOf(
            exception(VulnerabilityException.Subject.ALL_VULNS, VulnerabilityException.Scope.ASSET, assetId = 1L)
        ))
        assertThat(index.isExcepted(row(assetId = 1L))).isTrue()
        assertThat(index.isExcepted(row(assetId = 2L))).isFalse()
    }

    @Test
    fun `CVE subject matches comma-separated list membership regardless of spacing`() {
        val index = ExceptionMatchIndex(listOf(
            exception(
                VulnerabilityException.Subject.CVE, VulnerabilityException.Scope.GLOBAL,
                subjectValue = "CVE-2024-0001, CVE-2024-0002"
            )
        ))
        assertThat(index.isExcepted(row(vulnId = "CVE-2024-0001"))).isTrue()
        assertThat(index.isExcepted(row(vulnId = "CVE-2024-0002"))).isTrue()
        assertThat(index.isExcepted(row(vulnId = "CVE-2024-9999"))).isFalse()
    }

    @Test
    fun `CVE subject respects non-GLOBAL scope`() {
        val index = ExceptionMatchIndex(listOf(
            exception(
                VulnerabilityException.Subject.CVE, VulnerabilityException.Scope.IP,
                subjectValue = "CVE-2024-0001", scopeValue = "10.0.0.5"
            )
        ))
        assertThat(index.isExcepted(row(vulnId = "CVE-2024-0001", assetIp = "10.0.0.5"))).isTrue()
        assertThat(index.isExcepted(row(vulnId = "CVE-2024-0001", assetIp = "10.0.0.6"))).isFalse()
    }

    @Test
    fun `PRODUCT subject matches by CVE-id equality or case-insensitive substring`() {
        val index = ExceptionMatchIndex(listOf(
            exception(VulnerabilityException.Subject.PRODUCT, VulnerabilityException.Scope.GLOBAL, subjectValue = "OpenSSL")
        ))
        assertThat(index.isExcepted(row(product = "openssl 1.1.1"))).isTrue()
        assertThat(index.isExcepted(row(product = "curl 7.0"))).isFalse()
        assertThat(index.isExcepted(row(vulnId = "OpenSSL", product = null))).isTrue()
    }

    @Test
    fun `AWS_ACCOUNT scope matches cloudAccountId equality`() {
        val index = ExceptionMatchIndex(listOf(
            exception(
                VulnerabilityException.Subject.ALL_VULNS, VulnerabilityException.Scope.AWS_ACCOUNT,
                scopeValue = "111122223333"
            )
        ))
        assertThat(index.isExcepted(row(cloudAccountId = "111122223333"))).isTrue()
        assertThat(index.isExcepted(row(cloudAccountId = "999988887777"))).isFalse()
        assertThat(index.isExcepted(row(cloudAccountId = null))).isFalse()
    }

    @Test
    fun `OS scope matches case-insensitive substring of osVersion`() {
        val index = ExceptionMatchIndex(listOf(
            exception(
                VulnerabilityException.Subject.ALL_VULNS, VulnerabilityException.Scope.OS,
                scopeValue = "Windows Server 2019"
            )
        ))
        assertThat(index.isExcepted(row(osVersion = "Windows Server 2019 Datacenter"))).isTrue()
        assertThat(index.isExcepted(row(osVersion = "windows server 2019 standard"))).isTrue()
        assertThat(index.isExcepted(row(osVersion = "Ubuntu 22.04"))).isFalse()
        assertThat(index.isExcepted(row(osVersion = null))).isFalse()
    }

    @Test
    fun `firstMatch prefers ALL_VULNS over PRODUCT and CVE when several exceptions match`() {
        val allVulns = exception(VulnerabilityException.Subject.ALL_VULNS, VulnerabilityException.Scope.GLOBAL)
        val product = exception(VulnerabilityException.Subject.PRODUCT, VulnerabilityException.Scope.GLOBAL, subjectValue = "OpenSSL")
        val cve = exception(VulnerabilityException.Subject.CVE, VulnerabilityException.Scope.GLOBAL, subjectValue = "CVE-2024-0001")
        // Insertion order deliberately puts ALL_VULNS last — bucket priority must win.
        val index = ExceptionMatchIndex(listOf(cve, product, allVulns))

        val matched = index.firstMatch(row(vulnId = "CVE-2024-0001", product = "OpenSSL 1.1.1"))

        assertThat(matched).isSameAs(allVulns)
    }

    /**
     * A NO_EDR exception is an asset-capability record, not a suppression rule. It must
     * never match anything, whatever its subject and scope say — including the
     * ALL_VULNS × ASSET shape it is actually stored as, which for kind=VULNERABILITY
     * would suppress every finding on the asset.
     *
     * This is the load-bearing guarantee of the whole feature: approving "this box cannot
     * run an EDR agent" must not silently hide its vulnerabilities.
     */
    @Test
    fun `NO_EDR exceptions never match, not even in their stored ALL_VULNS ASSET shape`() {
        val noEdr = exception(
            VulnerabilityException.Subject.ALL_VULNS,
            VulnerabilityException.Scope.ASSET,
            assetId = 1L,
            kind = ExceptionKind.NO_EDR
        )
        val index = ExceptionMatchIndex(listOf(noEdr))

        assertThat(index.isExcepted(row(assetId = 1L))).isFalse()
        assertThat(index.firstMatch(row(assetId = 1L))).isNull()

        // ...and the equivalent VULNERABILITY row DOES match, proving the fixture would
        // otherwise have been suppressed and the assertion above is not vacuous.
        val suppressing = exception(
            VulnerabilityException.Subject.ALL_VULNS,
            VulnerabilityException.Scope.ASSET,
            assetId = 1L
        )
        assertThat(ExceptionMatchIndex(listOf(suppressing)).isExcepted(row(assetId = 1L))).isTrue()
    }

    @Test
    fun `a NO_EDR exception does not mask a genuine suppression alongside it`() {
        val index = ExceptionMatchIndex(listOf(
            exception(
                VulnerabilityException.Subject.ALL_VULNS, VulnerabilityException.Scope.ASSET,
                assetId = 1L, kind = ExceptionKind.NO_EDR
            ),
            exception(
                VulnerabilityException.Subject.CVE, VulnerabilityException.Scope.ASSET,
                subjectValue = "CVE-2024-0001", assetId = 1L
            )
        ))
        val match = index.firstMatch(row(vulnId = "CVE-2024-0001", assetId = 1L))
        assertThat(match).isNotNull()
        assertThat(match!!.kind).isEqualTo(ExceptionKind.VULNERABILITY)
        assertThat(index.isExcepted(row(vulnId = "CVE-2024-9999", assetId = 1L))).isFalse()
    }

    /**
     * Anti-drift lock: the shared index and the canonical entity predicate
     * [VulnerabilityException.matches] must agree for every kind×subject×scope combination.
     * (The index intentionally skips the isActive() check — all fixtures here are active.)
     */
    @Test
    fun `entity-pair overload agrees with VulnerabilityException matches across the subject-scope matrix`() {
        val matchingAsset = Asset(
            id = 1L, name = "DCBRUS0001", ip = "10.0.0.5", type = "SERVER", owner = "ops",
            cloudAccountId = "111122223333"
        ).apply { osVersion = "Windows Server 2019 Datacenter" }
        val otherAsset = Asset(
            id = 2L, name = "other", ip = "10.9.9.9", type = "SERVER", owner = "ops",
            cloudAccountId = "999988887777"
        ).apply { osVersion = "Ubuntu 22.04" }

        fun vuln(asset: Asset, id: String, product: String?) = Vulnerability(
            asset = asset,
            vulnerabilityId = id,
            cvssSeverity = "HIGH",
            vulnerableProductVersions = product,
            scanTimestamp = LocalDateTime.now()
        )

        val pairs = listOf(
            vuln(matchingAsset, "CVE-2024-0001", "OpenSSL 1.1.1") to matchingAsset,
            vuln(matchingAsset, "CVE-2024-9999", "curl 7.0") to matchingAsset,
            vuln(otherAsset, "CVE-2024-0001", "OpenSSL 1.1.1") to otherAsset,
            vuln(otherAsset, "CVE-2024-9999", null) to otherAsset
        )

        val subjects = listOf(
            VulnerabilityException.Subject.ALL_VULNS to null,
            VulnerabilityException.Subject.PRODUCT to "OpenSSL",
            VulnerabilityException.Subject.CVE to "CVE-2024-0001, CVE-2024-0002"
        )
        val scopes = listOf(
            Triple(VulnerabilityException.Scope.GLOBAL, null, null),
            Triple(VulnerabilityException.Scope.IP, "10.0.0.5", null),
            Triple(VulnerabilityException.Scope.ASSET, null, 1L),
            Triple(VulnerabilityException.Scope.AWS_ACCOUNT, "111122223333", null),
            Triple(VulnerabilityException.Scope.OS, "Windows Server 2019", null)
        )

        for (kind in ExceptionKind.entries) {
            for ((subject, subjectValue) in subjects) {
                for ((scope, scopeValue, exAssetId) in scopes) {
                    val ex = exception(subject, scope, subjectValue, scopeValue, exAssetId, kind)
                    val index = ExceptionMatchIndex(listOf(ex))
                    for ((v, a) in pairs) {
                        val entityResult = ex.matches(v, a)
                        val indexResult = index.firstMatch(v, a) != null
                        assertThat(indexResult)
                            .withFailMessage(
                                "Index and entity disagree for kind=%s subject=%s scope=%s vuln=%s asset=%s: index=%s entity=%s",
                                kind, subject, scope, v.vulnerabilityId, a.name, indexResult, entityResult
                            )
                            .isEqualTo(entityResult)
                        if (kind == ExceptionKind.NO_EDR) {
                            assertThat(entityResult)
                                .withFailMessage(
                                    "NO_EDR must never suppress, but subject=%s scope=%s matched vuln=%s on asset=%s",
                                    subject, scope, v.vulnerabilityId, a.name
                                )
                                .isFalse()
                        }
                    }
                }
            }
        }
    }
}
