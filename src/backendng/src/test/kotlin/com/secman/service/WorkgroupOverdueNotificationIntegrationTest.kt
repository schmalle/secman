package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.AwsAccountSharing
import com.secman.domain.User
import com.secman.domain.UserMapping
import com.secman.domain.Workgroup
import com.secman.domain.WorkgroupAwsAccount
import com.secman.dto.NotifiedUserStatus
import com.secman.dto.OverdueMatchType
import com.secman.dto.OverdueRecipient
import com.secman.repository.AssetRepository
import com.secman.repository.AwsAccountSharingRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.repository.WorkgroupAwsAccountRepository
import com.secman.repository.WorkgroupRepository
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Recipient rule of notify-users-overdue against real SQL: direct workgroup assignment and
 * AWS-account workgroup grants only, one entry per (user, asset), current assets only.
 */
class WorkgroupOverdueNotificationIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var service: WorkgroupOverdueNotificationService
    @Inject lateinit var query: WorkgroupOverdueRecipientQuery
    @Inject lateinit var accountResolver: AwsAccountRecipientResolver
    @Inject lateinit var users: UserRepository
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var vulnerabilities: VulnerabilityRepository
    @Inject lateinit var groups: WorkgroupRepository
    @Inject lateinit var accountGrants: WorkgroupAwsAccountRepository
    @Inject lateinit var mappings: UserMappingRepository
    @Inject lateinit var sharing: AwsAccountSharingRepository
    @Inject lateinit var entityManager: EntityManager

    private val suffix = System.nanoTime()

    private fun user(label: String, enabled: Boolean = true): User {
        val name = "overdue-$label-$suffix"
        return users.save(TestDataFactory.createRegularUser(name, "$name@example.com").apply { this.enabled = enabled })
    }

    private fun group(label: String, enabled: Boolean = true, parent: Workgroup? = null): Workgroup =
        groups.save(Workgroup(name = "$label-$suffix", enabled = enabled).apply { this.parent = parent })

    private fun join(user: User, vararg workgroups: Workgroup) {
        user.workgroups.addAll(workgroups)
        users.update(user)
    }

    private fun asset(label: String, account: String? = null, group: Workgroup? = null,
                      observedAt: LocalDateTime? = LocalDateTime.now(ZoneOffset.UTC)): Asset =
        assets.save(TestDataFactory.createAsset(name = "overdue-$label-$suffix").apply {
            cloudAccountId = account
            verifiedObservedAt = observedAt
            if (group != null) workgroups.add(group)
        })

    private fun overdue(asset: Asset, cve: String, severity: String = "High", ageDays: Long = 60, excepted: Boolean = false) =
        vulnerabilities.save(TestDataFactory.createVulnerabilityWithTimestamp(
            asset, cve, severity, LocalDateTime.now().minusDays(ageDays)
        ).apply { this.excepted = excepted })

    private fun recipients(vararg of: User): Map<String, OverdueRecipient> {
        entityManager.flush() // native queries must see the fixture's join-table writes
        val emails = of.map { it.email.lowercase() }.toSet()
        return query.findRecipients(service.window(30)).filter { it.email in emails }.associateBy { it.email }
    }

    @Test
    fun `reference workgroup with no own assets notifies all four members about its AWS account`() {
        val account = "0${suffix.toString().takeLast(11).padStart(11, '0')}" // leading zero kept as text
        val root = group("root")
        val devOps = group("aws-DevOps-uuorndojrd", parent = root)
        accountGrants.save(WorkgroupAwsAccount(workgroup = devOps, awsAccountId = account, createdBy = null))
        val members = (1..4).map { user("member$it").also { u -> join(u, devOps) } }
        val rootOnly = user("root-only").also { join(it, root) }

        val first = asset("ec2-a", account).also { overdue(it, "CVE-A", "Critical") }
        val second = asset("ec2-b", account).also { overdue(it, "CVE-B") }
        asset("ec2-clean", account).also { overdue(it, "CVE-young", ageDays = 2) }

        val result = recipients(*members.toTypedArray(), rootOnly)

        assertThat(result.keys).containsExactlyInAnyOrderElementsOf(members.map { it.email.lowercase() })
        for (member in members) {
            val entries = result.getValue(member.email.lowercase()).assets
            assertThat(entries.map { it.assetId }).containsExactlyInAnyOrder(first.id, second.id)
            assertThat(entries.flatMap { it.reasons }.map { it.type }.toSet()).containsExactly(OverdueMatchType.AWS_WORKGROUP)
            assertThat(entries.flatMap { it.reasons }.map { it.awsAccountId }.toSet()).containsExactly(account)
        }
        assertThat(result).doesNotContainKey(rootOnly.email.lowercase()) // hierarchy does not propagate

        val overview = service.overview(" ${members[0].email.uppercase()} ", 30)
        assertThat(overview.status).isEqualTo(NotifiedUserStatus.WOULD_BE_NOTIFIED)
        assertThat(overview.workgroups.single().assignedAssetCount).isZero()
        assertThat(overview.workgroups.single().awsAccountIds).containsExactly(account)
        assertThat(overview.assets.map { it.assetId }).containsExactlyInAnyOrder(first.id, second.id)
        assertThat(overview.totalCritical).isEqualTo(1)
    }

    @Test
    fun `user matching through assignment, two workgroups and an account gets one entry per asset`() {
        val account = "1${suffix.toString().takeLast(11).padStart(11, '0')}"
        val assigned = group("assigned")
        val accountGroup = group("account")
        val secondAccountGroup = group("account-two")
        listOf(accountGroup, secondAccountGroup).forEach {
            accountGrants.save(WorkgroupAwsAccount(workgroup = it, awsAccountId = account, createdBy = null))
        }
        val target = user("dedup").also { join(it, assigned, accountGroup, secondAccountGroup) }
        val shared = asset("both-paths", account, assigned).also { overdue(it, "CVE-1"); overdue(it, "CVE-2", "Critical") }

        val entry = recipients(target).getValue(target.email.lowercase()).assets.single()

        assertThat(entry.assetId).isEqualTo(shared.id)
        assertThat(entry.overdueCount).isEqualTo(2) // counts are not multiplied by the number of paths
        assertThat(entry.reasons.map { it.type to it.workgroupName }).containsExactlyInAnyOrder(
            OverdueMatchType.DIRECT_ASSIGNMENT to assigned.name,
            OverdueMatchType.AWS_WORKGROUP to accountGroup.name,
            OverdueMatchType.AWS_WORKGROUP to secondAccountGroup.name
        )
    }

    @Test
    fun `owner mappings, sharing, disabled groups and users, stale assets and metadata grant nothing`() {
        val account = "2${suffix.toString().takeLast(11).padStart(11, '0')}"
        val owner = user("owner")
        val shareTarget = user("share-target")
        val disabledMember = user("disabled-user", enabled = false)
        val disabledGroupMember = user("disabled-group")
        val metadataOwner = user("metadata")
        mappings.save(UserMapping(email = owner.email, user = owner, awsAccountId = account, domain = null))
        sharing.save(AwsAccountSharing(sourceUser = owner, targetUser = shareTarget, createdBy = owner))
        val enabledGroup = group("enabled")
        val disabledGroup = group("disabled", enabled = false)
        accountGrants.save(WorkgroupAwsAccount(workgroup = enabledGroup, awsAccountId = account, createdBy = null))
        accountGrants.save(WorkgroupAwsAccount(workgroup = disabledGroup, awsAccountId = account, createdBy = null))
        join(disabledMember, enabledGroup)
        join(disabledGroupMember, disabledGroup)
        val member = user("member").also { join(it, enabledGroup) }

        val current = asset("current", account).also { overdue(it, "CVE-current") }
        asset("stale", account, observedAt = LocalDateTime.now(ZoneOffset.UTC).minusHours(73)).also { overdue(it, "CVE-stale") }
        asset("excepted", account).also { overdue(it, "CVE-excepted", excepted = true) }
        asset("blank-account", "").also { overdue(it, "CVE-blank") }
        asset("metadata", null).apply { manualCreator = metadataOwner; this.owner = metadataOwner.username }
            .also { assets.update(it); overdue(it, "CVE-metadata") }

        val result = recipients(owner, shareTarget, disabledMember, disabledGroupMember, metadataOwner, member)

        assertThat(result.keys).containsExactly(member.email.lowercase())
        assertThat(result.getValue(member.email.lowercase()).assets.map { it.assetId }).containsExactly(current.id)
        assertThat(service.overview(owner.email, 30).status).isEqualTo(NotifiedUserStatus.NO_PATHS)
        assertThat(service.overview(disabledMember.email, 30).status).isEqualTo(NotifiedUserStatus.USER_DISABLED)
        assertThat(service.overview(disabledGroupMember.email, 30).status).isEqualTo(NotifiedUserStatus.NO_PATHS)
        assertThat(service.overview("nobody-$suffix@example.com", 30).status).isEqualTo(NotifiedUserStatus.USER_NOT_FOUND)

        // The existing account-centric resolver used by send-notification-users is unchanged.
        assertThat(accountResolver.resolveAwsAccountRecipients(account))
            .contains(owner.email.lowercase(), shareTarget.email.lowercase(), member.email.lowercase())
    }

    @Test
    fun `member with paths but only young or excepted findings has no overdue assets`() {
        val account = "3${suffix.toString().takeLast(11).padStart(11, '0')}"
        val quietGroup = group("quiet")
        accountGrants.save(WorkgroupAwsAccount(workgroup = quietGroup, awsAccountId = account, createdBy = null))
        val member = user("quiet").also { join(it, quietGroup) }
        asset("young", account).also { overdue(it, "CVE-young", ageDays = 5) }
        asset("excepted", account).also { overdue(it, "CVE-exc", excepted = true) }
        entityManager.flush()

        assertThat(service.overview(member.email, 30).status).isEqualTo(NotifiedUserStatus.NO_OVERDUE_ASSETS)
        assertThat(service.run(30, dryRun = true, notificationUser = member.email, actor = "test").recipientsResolved).isZero()
    }
}
