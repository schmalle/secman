package com.secman.service

import com.secman.domain.Workgroup
import com.secman.repository.AssetRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import com.secman.repository.WorkgroupAwsAccountRepository
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import io.micronaut.security.authentication.Authentication
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

@MicronautTest(environments = ["test"], transactional = false, startApplication = false)
class AwsWorkgroupReconciliationIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var linking: WorkgroupAccountLinkService
    @Inject lateinit var groups: WorkgroupRepository
    @Inject lateinit var users: UserRepository
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var accounts: WorkgroupAwsAccountRepository
    @Inject lateinit var workgroupService: WorkgroupService
    @Inject lateinit var accountService: WorkgroupAwsAccountService
    @Inject lateinit var access: AssetFilterService

    @Test
    fun `reconciliation preserves explicit asset grants while adding whole-account access`() {
        val suffix = System.nanoTime()
        val owner = users.save(TestDataFactory.createRegularUser(username = "aws-owner-$suffix", email = "aws-owner-$suffix@example.test"))
        val group = groups.save(Workgroup(name = "aws-Test-$suffix", enabled = false))
        val other = groups.save(Workgroup(name = "manual-$suffix"))
        val asset = assets.save(TestDataFactory.createAsset(name = "aws-test-$suffix", owner = "unrelated").apply {
            cloudAccountId = "000000000019"
            workgroups = mutableSetOf(group, other)
        })
        try {
            val pairs = listOf(WorkgroupAccountLinkService.AccountDisplayName("000000000019", "Test-$suffix", owner.email.uppercase()))
            val preview = linking.link(pairs, null, true)
            assertThat(preview.membersAdded).isEqualTo(1)
            assertThat(preview.assetsRemoved).isZero()
            assertThat(preview.links.single().statusOutcome).isEqualTo("WOULD_ENABLE")
            assertThat(groups.findById(group.id!!).get().enabled).isFalse()
            assertThat(groups.countAssetsByWorkgroupId(group.id!!)).isEqualTo(1)
            assertThat(groups.countUsersByWorkgroupId(group.id!!)).isZero()

            val result = linking.link(pairs, null, false)
            assertThat(result.failed).isZero()
            assertThat(result.membersAdded).isEqualTo(1)
            assertThat(result.links.single().statusOutcome).isEqualTo("ENABLED")
            assertThat(groups.countAssetsByWorkgroupId(group.id!!)).isEqualTo(1)
            assertThat(groups.countAssetsByWorkgroupId(other.id!!)).isEqualTo(1)
            assertThat(assets.existsById(asset.id!!)).isTrue()
            assertThat(accounts.countByWorkgroupId(group.id!!)).isEqualTo(1)
            assertThat(groups.countUsersByWorkgroupId(group.id!!)).isEqualTo(1)
            val auth = Authentication.build(owner.username, listOf("USER"), mapOf("userId" to owner.id!!, "email" to owner.email))
            assertThat(access.getAccessibleAssetIds(auth)).contains(asset.id!!)
            workgroupService.updateWorkgroup(group.id!!, enabled = false)
            assertThat(access.getAccessibleAssetIds(auth)).doesNotContain(asset.id!!)

            val repeated = linking.link(pairs, null, false)
            assertThat(repeated.failed).isZero()
            assertThat(repeated.membersAdded).isZero()
            assertThat(repeated.assetsRemoved).isZero()
            assertThat(groups.findById(group.id!!).get().enabled).isTrue()
            assertThat(repeated.links.single().statusOutcome).isEqualTo("ENABLED")
            assertThat(access.getAccessibleAssetIds(auth)).contains(asset.id!!)
            val unchanged = linking.link(pairs, null, false)
            assertThat(unchanged.links.single().statusOutcome).isEqualTo("UNCHANGED_ENABLED")
            workgroupService.assignAssetsToWorkgroup(group.id!!, listOf(asset.id!!))
            assertThat(groups.countAssetsByWorkgroupId(group.id!!)).isEqualTo(1)
            assertThatThrownBy { accountService.add(group.id!!, "000000000020", null) }
                .isInstanceOf(IllegalArgumentException::class.java)
        } finally {
            assets.deleteById(asset.id!!)
            workgroupService.deleteWorkgroup(group.id!!)
            workgroupService.deleteWorkgroup(other.id!!)
            users.deleteById(owner.id!!)
        }
    }
}
