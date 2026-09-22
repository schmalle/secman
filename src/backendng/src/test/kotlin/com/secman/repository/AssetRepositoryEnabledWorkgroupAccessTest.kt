package com.secman.repository

import com.secman.domain.Asset
import com.secman.domain.Criticality
import com.secman.domain.User
import com.secman.domain.Workgroup
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

@MicronautTest(environments = ["test"], transactional = false, startApplication = false)
open class AssetRepositoryEnabledWorkgroupAccessTest : BaseIntegrationTest() {

    @Inject
    lateinit var assetRepository: AssetRepository

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var workgroupRepository: WorkgroupRepository

    private val assets = mutableListOf<Asset>()
    private var user: User? = null
    private val workgroups = mutableListOf<Workgroup>()

    @AfterEach
    fun tearDown() {
        assets.forEach { assetRepository.delete(it) }
        user?.let { userRepository.delete(it) }
        workgroups.forEach { workgroupRepository.delete(it) }
    }

    @Test
    fun `disabled membership is denied while enabled membership and personal provenance remain allowed`() {
        val suffix = System.nanoTime()
        val enabled = workgroupRepository.save(
            Workgroup(name = "enabled-access-$suffix", criticality = Criticality.MEDIUM)
        )
        val disabled = workgroupRepository.save(
            Workgroup(name = "disabled-access-$suffix", criticality = Criticality.MEDIUM, enabled = false)
        )
        workgroups += listOf(enabled, disabled)

        val member = TestDataFactory.createRegularUser(
            username = "enabled-access-user-$suffix",
            email = "enabled-access-user-$suffix@example.test"
        ).apply {
            workgroups = mutableSetOf(enabled, disabled)
        }
        user = userRepository.save(member)

        val disabledOnly = saveAsset("a-disabled-only-$suffix", disabled)
        val enabledMembership = saveAsset("b-enabled-membership-$suffix", enabled)
        val manualCreator = saveAsset("c-manual-creator-$suffix", disabled).also {
            it.manualCreator = user
            assetRepository.update(it)
        }
        val scanUploader = saveAsset("d-scan-uploader-$suffix", disabled).also {
            it.scanUploader = user
            assetRepository.update(it)
        }

        val accessible = assetRepository.findAccessibleByWorkgroupMembership(user!!.id!!)

        assertThat(accessible.map { it.id }).containsExactly(
            enabledMembership.id
        )
        assertThat(accessible.map { it.id }).doesNotContain(disabledOnly.id, manualCreator.id, scanUploader.id)
    }

    private fun saveAsset(name: String, workgroup: Workgroup): Asset {
        val asset = TestDataFactory.createAsset(name = name, owner = "import-$name").apply {
            workgroups = mutableSetOf(workgroup)
        }
        return assetRepository.save(asset).also { assets += it }
    }
}
