package com.secman.service

import com.secman.domain.VulnerabilityException
import com.secman.repository.projection.VulnerabilityStatsRawRow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("StatisticsExceptionMatchIndex")
class StatisticsExceptionMatchIndexTest {

    private fun row(
        vulnId: String = "CVE-2024-0001",
        product: String? = null,
        assetId: Long = 1L,
        assetIp: String? = null,
        cloudAccountId: String? = null,
        osVersion: String? = null
    ) = VulnerabilityStatsRawRow(
        vulnerabilityId = vulnId,
        cvssSeverity = "HIGH",
        vulnerableProductVersions = product,
        assetId = assetId,
        assetName = "asset-$assetId",
        assetType = "SERVER",
        assetIp = assetIp,
        cloudAccountId = cloudAccountId,
        osVersion = osVersion
    )

    private fun exception(
        subject: VulnerabilityException.Subject,
        scope: VulnerabilityException.Scope,
        subjectValue: String? = null,
        scopeValue: String? = null,
        assetId: Long? = null
    ) = VulnerabilityException(
        subject = subject,
        scope = scope,
        subjectValue = subjectValue,
        scopeValue = scopeValue,
        assetId = assetId,
        reason = "test",
        createdBy = "tester"
    )

    @Test
    fun `no exceptions means nothing is excepted`() {
        val index = StatisticsExceptionMatchIndex(emptyList())
        assertThat(index.isExcepted(row())).isFalse()
    }

    @Test
    fun `ALL_VULNS ASSET scope only excepts rows for that asset`() {
        val index = StatisticsExceptionMatchIndex(listOf(
            exception(VulnerabilityException.Subject.ALL_VULNS, VulnerabilityException.Scope.ASSET, assetId = 1L)
        ))
        assertThat(index.isExcepted(row(assetId = 1L))).isTrue()
        assertThat(index.isExcepted(row(assetId = 2L))).isFalse()
    }

    @Test
    fun `CVE subject matches comma-separated list membership regardless of spacing`() {
        val index = StatisticsExceptionMatchIndex(listOf(
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
        val index = StatisticsExceptionMatchIndex(listOf(
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
        val index = StatisticsExceptionMatchIndex(listOf(
            exception(VulnerabilityException.Subject.PRODUCT, VulnerabilityException.Scope.GLOBAL, subjectValue = "OpenSSL")
        ))
        assertThat(index.isExcepted(row(product = "openssl 1.1.1"))).isTrue()
        assertThat(index.isExcepted(row(product = "curl 7.0"))).isFalse()
        assertThat(index.isExcepted(row(vulnId = "OpenSSL", product = null))).isTrue()
    }

    @Test
    fun `AWS_ACCOUNT scope matches cloudAccountId equality`() {
        val index = StatisticsExceptionMatchIndex(listOf(
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
        val index = StatisticsExceptionMatchIndex(listOf(
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
}
