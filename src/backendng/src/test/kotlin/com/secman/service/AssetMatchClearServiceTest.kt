package com.secman.service

import com.secman.domain.Asset
import com.secman.repository.AssetRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AssetMatchClearServiceTest {

    private lateinit var assetRepository: AssetRepository
    private lateinit var assetCascadeDeleteService: AssetCascadeDeleteService
    private lateinit var service: AssetMatchClearService

    @BeforeEach
    fun setUp() {
        assetRepository = mockk(relaxed = true)
        assetCascadeDeleteService = mockk()
        service = AssetMatchClearService(assetRepository, assetCascadeDeleteService)
    }

    @Test
    fun `rejects empty accountIds`() {
        assertThatThrownBy {
            service.clear(
                accountIds = emptyList(),
                resourceIds = listOf("i-1"),
                dryRun = true,
                username = "admin"
            )
        }.isInstanceOf(AssetMatchClearService.EmptySnapshotException::class.java)
            .hasMessageContaining("no accountIds")
    }

    @Test
    fun `rejects empty resourceIds`() {
        assertThatThrownBy {
            service.clear(
                accountIds = listOf("111"),
                resourceIds = emptyList(),
                dryRun = true,
                username = "admin"
            )
        }.isInstanceOf(AssetMatchClearService.EmptySnapshotException::class.java)
            .hasMessageContaining("no resourceIds")
    }

    @Test
    fun `dry run finds unmatched assets without deleting them`() {
        val kept = awsAsset(1L, "alive", "111", "i-aaa")
        val gone = awsAsset(2L, "dead", "111", "i-bbb")
        every { assetRepository.findAwsAssetsInAccounts(setOf("111")) } returns listOf(kept, gone)

        val result = service.clear(
            accountIds = listOf("111"),
            resourceIds = listOf("i-aaa"),
            dryRun = true,
            username = "admin"
        )

        assertThat(result.dryRun).isTrue()
        assertThat(result.candidateCount).isEqualTo(1)
        assertThat(result.deletedCount).isEqualTo(0)
        assertThat(result.candidates).extracting("name").containsExactly("dead")
        verify(exactly = 0) { assetCascadeDeleteService.deleteAsset(any(), any(), any(), any()) }
    }

    @Test
    fun `partial snapshot never deletes outside scoped accounts`() {
        // Repository only returns assets within accountIds the caller passed
        every { assetRepository.findAwsAssetsInAccounts(setOf("111")) } returns listOf(
            awsAsset(1L, "in-scope", "111", "i-aaa")
        )
        every { assetRepository.findAllAwsAssetsWithInstanceId() } returns listOf(
            awsAsset(1L, "in-scope", "111", "i-aaa"),
            awsAsset(2L, "out-of-scope", "222", "i-bbb")
        )

        val result = service.clear(
            accountIds = listOf("111"),
            resourceIds = listOf("i-zzz"),  // i-aaa not in snapshot → candidate
            dryRun = true,
            username = "admin"
        )

        // Asset in account 222 is never even fetched — proven by the mock not stubbing it.
        assertThat(result.snapshotAccountCount).isEqualTo(1)
        assertThat(result.scopedAssetCount).isEqualTo(1)
        assertThat(result.candidateCount).isEqualTo(1)
        assertThat(result.uncoveredAccountCount).isEqualTo(1)
        assertThat(result.uncoveredAssetCount).isEqualTo(1)
        verify { assetRepository.findAwsAssetsInAccounts(setOf("111")) }
    }

    @Test
    fun `strict mode marks absent-account asset missing from snapshot as candidate`() {
        val absentAccount = awsAsset(2L, "absent-account", "222", "i-missing")
        every { assetRepository.findAllAwsAssetsWithInstanceId() } returns listOf(absentAccount)

        val result = service.clear(
            accountIds = listOf("111"),
            resourceIds = listOf("i-present"),
            dryRun = true,
            username = "admin",
            strict = true
        )

        assertThat(result.scopeMode).isEqualTo("strict/global")
        assertThat(result.scopedAssetCount).isEqualTo(1)
        assertThat(result.candidates).extracting("name").containsExactly("absent-account")
        verify(exactly = 0) { assetRepository.findAwsAssetsInAccounts(any()) }
    }

    @Test
    fun `strict mode does not mark absent-account asset whose instance exists in snapshot`() {
        val absentAccount = awsAsset(2L, "absent-account", "222", "i-present")
        every { assetRepository.findAllAwsAssetsWithInstanceId() } returns listOf(absentAccount)

        val result = service.clear(
            accountIds = listOf("111"),
            resourceIds = listOf("i-present"),
            dryRun = true,
            username = "admin",
            strict = true
        )

        assertThat(result.candidateCount).isEqualTo(0)
    }

    @Test
    fun `delete mode removes unmatched assets via cascade service`() {
        val gone = awsAsset(7L, "ghost", "111", "i-bbb")
        every { assetRepository.findAwsAssetsInAccounts(setOf("111")) } returns listOf(gone)
        every { assetRepository.countAwsAssetsInAccounts(setOf("111")) } returns 1L
        every {
            assetCascadeDeleteService.deleteAsset(7L, "admin", forceTimeout = true, bulkOperationId = any())
        } returns mockk(relaxed = true)

        val result = service.clear(
            accountIds = listOf("111"),
            resourceIds = listOf("i-aaa"),
            dryRun = false,
            username = "admin",
            maxDeletePercent = 100  // disable brake — 1/1 = 100% would trip otherwise
        )

        assertThat(result.dryRun).isFalse()
        assertThat(result.candidateCount).isEqualTo(1)
        assertThat(result.deletedCount).isEqualTo(1)
        assertThat(result.errors).isEmpty()
        assertThat(result.status).isEqualTo("SUCCESS")
        verify {
            assetCascadeDeleteService.deleteAsset(7L, "admin", forceTimeout = true, bulkOperationId = any())
        }
    }

    @Test
    fun `safety brake trips when proposed deletions exceed threshold`() {
        // Snapshot has 4 scoped assets, 3 candidates — that's 75%, above 25%.
        val a1 = awsAsset(1L, "a1", "111", "i-1")
        val a2 = awsAsset(2L, "a2", "111", "i-2")
        val a3 = awsAsset(3L, "a3", "111", "i-3")
        val a4 = awsAsset(4L, "a4", "111", "i-keep")
        every { assetRepository.findAwsAssetsInAccounts(setOf("111")) } returns listOf(a1, a2, a3, a4)
        every { assetRepository.countAwsAssetsInAccounts(setOf("111")) } returns 4L

        val result = service.clear(
            accountIds = listOf("111"),
            resourceIds = listOf("i-keep"),
            dryRun = false,
            username = "admin",
            maxDeletePercent = 25
        )

        assertThat(result.safetyBrakeTripped).isTrue()
        assertThat(result.status).isEqualTo("ABORTED_SAFETY_BRAKE")
        assertThat(result.deletedCount).isEqualTo(0)
        verify(exactly = 0) { assetCascadeDeleteService.deleteAsset(any(), any(), any(), any()) }
    }

    @Test
    fun `strict mode safety brake uses all AWS assets as denominator`() {
        val a1 = awsAsset(1L, "a1", "111", "i-1")
        val a2 = awsAsset(2L, "a2", "222", "i-2")
        val a3 = awsAsset(3L, "a3", "333", "i-keep")
        every { assetRepository.findAllAwsAssetsWithInstanceId() } returns listOf(a1, a2, a3)
        every { assetRepository.countAllAwsAssetsWithInstanceId() } returns 3L

        val result = service.clear(
            accountIds = listOf("111"),
            resourceIds = listOf("i-keep"),
            dryRun = false,
            username = "admin",
            maxDeletePercent = 50,
            strict = true
        )

        assertThat(result.safetyBrakeTripped).isTrue()
        assertThat(result.status).isEqualTo("ABORTED_SAFETY_BRAKE")
        assertThat(result.scopedAssetCount).isEqualTo(3)
        verify(exactly = 0) { assetCascadeDeleteService.deleteAsset(any(), any(), any(), any()) }
        verify { assetRepository.countAllAwsAssetsWithInstanceId() }
        verify(exactly = 0) { assetRepository.countAwsAssetsInAccounts(any()) }
    }

    @Test
    fun `match is case-insensitive on cloudInstanceId`() {
        val asset = awsAsset(9L, "mixed-case", "111", "i-AbCdEf")
        every { assetRepository.findAwsAssetsInAccounts(setOf("111")) } returns listOf(asset)

        val result = service.clear(
            accountIds = listOf("111"),
            // Snapshot uses lower-case form.
            resourceIds = listOf("i-abcdef"),
            dryRun = true,
            username = "admin"
        )

        // Asset SHOULD match → no candidates → no deletion proposed.
        assertThat(result.candidateCount).isEqualTo(0)
    }

    @Test
    fun `per-asset deletion failures are reported without aborting later deletions`() {
        val blocked = awsAsset(11L, "blocked", "111", "i-x")
        val ok = awsAsset(12L, "ok", "111", "i-y")
        every { assetRepository.findAwsAssetsInAccounts(setOf("111")) } returns listOf(blocked, ok)
        every { assetRepository.countAwsAssetsInAccounts(setOf("111")) } returns 10L  // brake disabled by ratio
        every {
            assetCascadeDeleteService.deleteAsset(11L, "admin", forceTimeout = true, bulkOperationId = any())
        } throws IllegalStateException("referenced by risk")
        every {
            assetCascadeDeleteService.deleteAsset(12L, "admin", forceTimeout = true, bulkOperationId = any())
        } returns mockk(relaxed = true)

        val result = service.clear(
            accountIds = listOf("111"),
            resourceIds = listOf("i-keep"),
            dryRun = false,
            username = "admin",
            maxDeletePercent = 50
        )

        assertThat(result.candidateCount).isEqualTo(2)
        assertThat(result.deletedCount).isEqualTo(1)
        assertThat(result.errors).hasSize(1)
        assertThat(result.errors[0].assetName).isEqualTo("blocked")
        assertThat(result.status).isEqualTo("PARTIAL")
    }

    private fun awsAsset(id: Long, name: String, account: String, instance: String): Asset =
        Asset(
            id = id,
            name = name,
            type = "SERVER",
            owner = "test",
            cloudAccountId = account,
            cloudInstanceId = instance
        )
}
