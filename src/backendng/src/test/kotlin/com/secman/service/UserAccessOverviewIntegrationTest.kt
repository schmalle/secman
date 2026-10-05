package com.secman.service

import com.secman.domain.*
import com.secman.repository.*
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class UserAccessOverviewIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var service: UserAccessOverviewService
    @Inject lateinit var filter: AssetFilterService
    @Inject lateinit var users: UserRepository
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var groups: WorkgroupRepository
    @Inject lateinit var mappings: UserMappingRepository
    @Inject lateinit var accountGrants: WorkgroupAwsAccountRepository
    @Inject lateinit var domainGrants: WorkgroupAdDomainRepository
    @Inject lateinit var sharing: AwsAccountSharingRepository
    @Inject lateinit var entityManager: EntityManager

    private fun user(label: String, roles: MutableSet<User.Role> = mutableSetOf(User.Role.USER, User.Role.VULN)): User {
        val name = "access-$label-${System.nanoTime()}"
        return users.save(TestDataFactory.createRegularUser(name, "$name@example.com").apply { this.roles = roles })
    }

    private fun mapping(user: User, account: String? = null, domain: String? = null) =
        mappings.save(UserMapping(email = user.email, user = user, awsAccountId = account, domain = domain, awsAccountName = account?.let { "Account $it" }))

    private fun asset(name: String, account: String? = null, domain: String? = null, group: Workgroup? = null) =
        assets.save(TestDataFactory.createAsset(name = "access-$name-${System.nanoTime()}").apply {
            cloudAccountId = account
            adDomain = domain
            if (group != null) workgroups.add(group)
        })

    @Test
    fun `report matches live target policy with all reasons and does not inflate partial account access`() {
        val target = user("target")
        val source = user("source")
        val downstream = user("downstream")
        val group = groups.save(Workgroup(name = "access-group-${System.nanoTime()}"))
        val disabled = groups.save(Workgroup(name = "access-disabled-${System.nanoTime()}", enabled = false))
        target.workgroups.addAll(listOf(group, disabled))
        users.update(target)
        mapping(target, "111111111111", "personal.example")
        mapping(target, "999999999999") // grant without an asset
        mapping(source, "333333333333")
        mapping(source, "444444444444")
        accountGrants.save(WorkgroupAwsAccount(workgroup = group, awsAccountId = "111111111111", createdBy = null))
        accountGrants.save(WorkgroupAwsAccount(workgroup = group, awsAccountId = "222222222222", createdBy = null))
        domainGrants.save(WorkgroupAdDomain(workgroup = group, adDomain = "group.example", createdBy = null))
        sharing.save(AwsAccountSharing(sourceUser = source, targetUser = target, createdBy = source,
            selectedAwsAccountIds = mutableSetOf("333333333333")))
        sharing.save(AwsAccountSharing(sourceUser = target, targetUser = downstream, createdBy = source))

        val overlap = asset("overlap", "111111111111", "PERSONAL.EXAMPLE", group)
        val groupAccount = asset("group-account", "222222222222")
        val groupDomain = asset("group-domain", domain = "GROUP.EXAMPLE")
        val shared = asset("shared", "333333333333")
        val partial = asset("partial", "555555555555", group = group)
        val disabledAsset = asset("disabled", group = disabled)
        val unselected = asset("unselected", "444444444444")
        val metadataOnly = asset("metadata", "555555555555").apply { owner = target.username; manualCreator = target }
        assets.update(metadataOnly)
        entityManager.flush() // Native policy queries must see the fixture's join-table writes.

        val authentication = Authentication.build(target.username, target.roles.map { it.name },
            mapOf("userId" to target.id!!, "email" to target.email))
        val report = service.overview("  ${target.email.uppercase()}  ", 0, 2)
        val all = (0 until report.totalPages).flatMap { service.overview(target.email, it, 2).assets }
        assertThat(all.map { it.id }.toSet()).isEqualTo(filter.getAccessibleAssetIds(authentication))
        assertThat(all.map { it.id }).containsExactlyInAnyOrder(overlap.id, groupAccount.id, groupDomain.id, shared.id, partial.id)
        assertThat(all.map { it.id }).doesNotContain(disabledAsset.id, unselected.id, metadataOnly.id)
        assertThat(all.map { it.id }).isSorted()
        assertThat(all.single { it.id == overlap.id }.reasons.map { it.type })
            .contains("WORKGROUP_ASSET", "PERSONAL_MAPPING", "WORKGROUP_AWS")
        assertThat(all.single { it.id == shared.id }.reasons.single().sourceName).isEqualTo(source.email)
        val partialScope = report.awsAccounts.single { it.value == "555555555555" }
        assertThat(partialScope.wholeScopeAccess).isFalse()
        assertThat(partialScope.visibleAssetCount).isEqualTo(1)
        assertThat(report.awsAccounts.single { it.value == "999999999999" }.visibleAssetCount).isZero()
        assertThat(report.awsAccounts.single { it.value == "111111111111" }.displayName).isEqualTo("Account 111111111111")
        assertThat(report.adDomains.map { it.value }).contains("personal.example", "group.example")
        assertThat(report.totalAssets).isEqualTo(5)
        assertThat(report.vulnerabilityAccess).isTrue()
        assertThat(service.overview(downstream.email, 0, 100).assets.map { it.id }).doesNotContain(shared.id)
        assertThat(service.overview(source.email, 0, 100).assets.map { it.id }).doesNotContain(overlap.id)

        group.enabled = false
        groups.update(group)
        entityManager.flush()
        val revoked = service.overview(target.email, 0, 100)
        assertThat(revoked.assets.map { it.id }).doesNotContain(groupAccount.id, groupDomain.id, partial.id)
        assertThat(revoked.assets.single { it.id == overlap.id }.reasons.map { it.type }).containsExactly("PERSONAL_MAPPING")
        mappings.deleteById(requireNotNull(mappings.findByEmail(target.email).first { it.awsAccountId == "111111111111" }.id))
        entityManager.flush()
        assertThat(service.overview(target.email, 0, 100).assets.map { it.id }).doesNotContain(overlap.id)
    }

    @Test
    fun `global roles disabled users feature permissions and empty scope are explicit`() {
        val visible = asset("global")
        for (role in listOf(User.Role.ADMIN, User.Role.SECCHAMPION)) {
            val target = user(role.name, mutableSetOf(role))
            val report = service.overview(target.email, 0, 500)
            assertThat(report.globalAccess).isTrue()
            assertThat(report.assets.map { it.id }).contains(visible.id)
            assertThat(report.assets.first().reasons.map { it.type }).contains("GLOBAL_ROLE")
            target.enabled = false
            users.update(target)
            val disabled = service.overview(target.email, 0, 500)
            assertThat(disabled.user.enabled).isFalse()
            assertThat(disabled.totalAssets).isZero()
            assertThat(disabled.vulnerabilityAccess).isFalse()
        }
        val plain = user("plain", mutableSetOf(User.Role.USER))
        val group = groups.save(Workgroup(name = "plain-group-${System.nanoTime()}"))
        plain.workgroups.add(group)
        users.update(plain)
        val scoped = asset("plain", group = group)
        entityManager.flush()
        val report = service.overview(plain.email, 0, 100)
        assertThat(report.assets.map { it.id }).containsExactly(scoped.id)
        assertThat(report.vulnerabilityAccess).isFalse()
        assertThat(service.overview(user("empty").email, 0, 100).totalAssets).isZero()
    }

    @Test
    fun `invalid identity and pagination are rejected before querying scope`() {
        for ((email, page, size) in listOf(Triple("invalid", 0, 100), Triple("valid@example.com", -1, 100), Triple("valid@example.com", 0, 501))) {
            assertThat(assertThrows(HttpStatusException::class.java) { service.overview(email, page, size) }.status).isEqualTo(HttpStatus.BAD_REQUEST)
        }
        assertThat(assertThrows(HttpStatusException::class.java) { service.overview("missing@example.com", 0, 100) }.status).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
