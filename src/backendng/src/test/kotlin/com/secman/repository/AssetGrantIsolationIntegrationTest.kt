package com.secman.repository

import com.secman.domain.*
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Locks down the original A/B/C/D/E/F/G example and grants for future discoveries. */
@MicronautTest(environments = ["test"], transactional = false, startApplication = false)
class AssetGrantIsolationIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var users: UserRepository
    @Inject lateinit var groups: WorkgroupRepository
    @Inject lateinit var accounts: WorkgroupAwsAccountRepository
    @Inject lateinit var domains: WorkgroupAdDomainRepository

    @Test fun `shared membership does not expose another membership or personal metadata assets`() {
        val suffix = System.nanoTime()
        val sharedGroup = groups.save(Workgroup(name = "C-$suffix"))
        val separateGroup = groups.save(Workgroup(name = "F-$suffix", parent = sharedGroup))
        val firstUser = users.save(TestDataFactory.createRegularUser("A-$suffix", "A-$suffix@test.com").apply {
            workgroups = mutableSetOf(sharedGroup, separateGroup)
        })
        val secondUser = users.save(TestDataFactory.createRegularUser("B-$suffix", "B-$suffix@test.com").apply {
            workgroups = mutableSetOf(sharedGroup)
        })
        val sharedAssets = listOf("D", "E").map { name ->
            assets.save(TestDataFactory.createAsset("$name-$suffix").apply { workgroups.add(sharedGroup) })
        }
        val separateAsset = assets.save(TestDataFactory.createAsset("G-$suffix").apply { workgroups.add(separateGroup) })
        val privateAsset = assets.save(TestDataFactory.createAsset("private-$suffix", owner = firstUser.username).apply {
            manualCreator = firstUser; scanUploader = firstUser
        })
        assertThat(assets.findAccessibleAssetIds(firstUser.id!!, firstUser.email))
            .containsExactlyInAnyOrderElementsOf(sharedAssets.map { it.id!! } + separateAsset.id!!)
        assertThat(assets.findAccessibleAssetIds(secondUser.id!!, secondUser.email))
            .containsExactlyInAnyOrderElementsOf(sharedAssets.map { it.id!! })
        assertThat(assets.findAccessibleAssets(secondUser.id!!, secondUser.email).map { it.id })
            .doesNotContain(separateAsset.id, privateAsset.id)

        accounts.save(WorkgroupAwsAccount(workgroup = sharedGroup, awsAccountId = "000000008888", createdBy = firstUser))
        domains.save(WorkgroupAdDomain(workgroup = sharedGroup, adDomain = "scope.example.test", createdBy = firstUser))
        val futureAccountAsset = assets.save(TestDataFactory.createAsset("later-account-$suffix").apply { cloudAccountId = "000000008888" })
        val futureDomainAsset = assets.save(TestDataFactory.createAsset("later-domain-$suffix").apply { adDomain = "SCOPE.EXAMPLE.TEST" })
        assertThat(assets.findAccessibleAssetIds(secondUser.id!!, secondUser.email))
            .contains(futureAccountAsset.id!!, futureDomainAsset.id!!)
        sharedGroup.enabled = false
        groups.update(sharedGroup)
        assertThat(assets.findAccessibleAssetIds(secondUser.id!!, secondUser.email)).isEmpty()
        assertThat(assets.findAccessibleAssetIds(firstUser.id!!, firstUser.email)).containsExactly(separateAsset.id!!)
    }
}
