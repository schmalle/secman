package com.secman.service

import com.secman.domain.AwsAccount
import com.secman.repository.AwsAccountRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.repository.projection.AccountFindingAgeRankRow
import com.secman.repository.projection.OldestFindingDetailRow
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.time.LocalDateTime

class AccountFindingAgeServiceTest {

    private val vulnerabilityRepository = mockk<VulnerabilityRepository>()
    private val awsAccountRepository = mockk<AwsAccountRepository>()
    private val service = AccountFindingAgeService(vulnerabilityRepository, awsAccountRepository)

    private fun rank(accountId: String, daysAgo: Long, open: Long = 5, assets: Long = 2) =
        AccountFindingAgeRankRow(
            awsAccountId = accountId,
            oldestFirstSeen = LocalDateTime.now().minusDays(daysAgo),
            openFindingCount = BigInteger.valueOf(open),
            affectedAssetCount = BigInteger.valueOf(assets)
        )

    private fun detail(accountId: String, cve: String) =
        OldestFindingDetailRow(
            awsAccountId = accountId,
            cve = cve,
            severity = "High",
            assetName = "web-01",
            assetInstanceId = "i-0abc"
        )

    @Test
    fun `orders accounts by oldest finding first and computes days open`() {
        every { vulnerabilityRepository.findAccountsByOldestOpenFinding(10) } returns listOf(
            rank("111111111111", 400),
            rank("222222222222", 90)
        )
        every { vulnerabilityRepository.findOldestFindingDetail(any()) } answers {
            firstArg<Collection<String>>().map { detail(it, "CVE-2023-0001") }
        }
        every { awsAccountRepository.findByAwsAccountIdIn(any()) } returns emptyList()

        val result = service.getTopAccountsByOldestFinding(10)

        assertThat(result).hasSize(2)
        assertThat(result[0].awsAccountId).isEqualTo("111111111111")
        assertThat(result[0].oldestFindingDaysOpen).isEqualTo(400L)
        assertThat(result[1].oldestFindingDaysOpen).isEqualTo(90L)
    }

    @Test
    fun `account name falls back to the account id when no row exists`() {
        every { vulnerabilityRepository.findAccountsByOldestOpenFinding(10) } returns listOf(rank("333333333333", 10))
        every { vulnerabilityRepository.findOldestFindingDetail(any()) } returns
            listOf(detail("333333333333", "CVE-2024-1"))
        every { awsAccountRepository.findByAwsAccountIdIn(any()) } returns emptyList()

        val result = service.getTopAccountsByOldestFinding(10)

        assertThat(result.single().accountName).isEqualTo("333333333333")
    }

    @Test
    fun `account name falls back to the account id when the stored name is blank`() {
        every { vulnerabilityRepository.findAccountsByOldestOpenFinding(10) } returns listOf(rank("444444444444", 10))
        every { vulnerabilityRepository.findOldestFindingDetail(any()) } returns
            listOf(detail("444444444444", "CVE-2024-2"))
        every { awsAccountRepository.findByAwsAccountIdIn(any()) } returns
            listOf(AwsAccount(awsAccountId = "444444444444", name = "   "))

        val result = service.getTopAccountsByOldestFinding(10)

        assertThat(result.single().accountName).isEqualTo("444444444444")
    }

    @Test
    fun `account name uses the stored name when present`() {
        every { vulnerabilityRepository.findAccountsByOldestOpenFinding(10) } returns listOf(rank("555555555555", 10))
        every { vulnerabilityRepository.findOldestFindingDetail(any()) } returns
            listOf(detail("555555555555", "CVE-2024-3"))
        every { awsAccountRepository.findByAwsAccountIdIn(any()) } returns
            listOf(AwsAccount(awsAccountId = "555555555555", name = "Platform Prod"))

        val result = service.getTopAccountsByOldestFinding(10)

        assertThat(result.single().accountName).isEqualTo("Platform Prod")
    }

    @Test
    fun `missing detail row still yields a result with null cve`() {
        every { vulnerabilityRepository.findAccountsByOldestOpenFinding(10) } returns listOf(rank("666666666666", 30))
        every { vulnerabilityRepository.findOldestFindingDetail(any()) } returns emptyList()
        every { awsAccountRepository.findByAwsAccountIdIn(any()) } returns emptyList()

        val result = service.getTopAccountsByOldestFinding(10)

        assertThat(result.single().oldestFindingCve).isNull()
        assertThat(result.single().accountName).isEqualTo("666666666666")
    }

    @Test
    fun `empty ranking returns an empty list without hitting the name repository`() {
        every { vulnerabilityRepository.findAccountsByOldestOpenFinding(10) } returns emptyList()

        val result = service.getTopAccountsByOldestFinding(10)

        assertThat(result).isEmpty()
    }

    @Test
    fun `limit below the minimum is rejected`() {
        assertThatThrownBy { service.getTopAccountsByOldestFinding(0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("limit")
    }

    @Test
    fun `limit above the maximum is rejected`() {
        assertThatThrownBy { service.getTopAccountsByOldestFinding(51) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("limit")
    }
}
