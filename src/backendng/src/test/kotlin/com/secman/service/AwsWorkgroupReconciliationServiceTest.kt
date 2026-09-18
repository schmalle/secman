package com.secman.service

import com.secman.domain.User
import com.secman.domain.Workgroup
import com.secman.domain.WorkgroupAwsAccount
import com.secman.domain.WorkgroupAccessChangedEvent
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import com.secman.repository.WorkgroupAwsAccountRepository
import io.micronaut.context.event.ApplicationEventPublisher
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Optional

class AwsWorkgroupReconciliationServiceTest {
    private val groups = mockk<WorkgroupRepository>(relaxed = true)
    private val users = mockk<UserRepository>()
    private val memberships = mockk<WorkgroupService>(relaxed = true)
    private val publisher = mockk<ApplicationEventPublisher<WorkgroupAccessChangedEvent>>(relaxed = true)
    private val accounts = mockk<WorkgroupAwsAccountRepository>()
    private val safety = mockk<CatchAllWorkgroupSafetyService>(relaxed = true)
    private val service = AwsWorkgroupReconciliationService(groups, users, memberships, publisher, accounts, safety)
    private val group = Workgroup(id = 42, name = "aws-DevOps-test", enabled = false, ownerEmail = "owner@example.com")
    private val accountId = "000000000019"

    private fun reconcile(ownerEmail: String? = null, dryRun: Boolean = false) =
        service.reconcile(42, ownerEmail, 1, dryRun, accountId)

    private fun existingGroup() {
        every { groups.findById(42) } returns Optional.of(group)
        every { groups.countAssetsByWorkgroupId(42) } returnsMany listOf(9, 0)
        every { groups.update(any<Workgroup>()) } answers { firstArg() }
        every { accounts.findByWorkgroupId(42) } returns listOf(
            WorkgroupAwsAccount(workgroup = group, awsAccountId = accountId, createdBy = null))
    }

    private fun owner(member: Boolean = false) {
        val user = mockk<User>()
        every { user.id } returns 7
        every { user.workgroups } returns if (member) mutableSetOf(group) else mutableSetOf()
        every { users.findByEmailIgnoreCase("owner@example.com") } returns Optional.of(user)
        every { users.findByIdWithWorkgroups(7) } returns Optional.of(user)
        if (member) every { groups.countUsersByWorkgroupId(42) } returns 1
        every { memberships.assignUsersToWorkgroup(42, listOf(7)) } answers {
            every { groups.countUsersByWorkgroupId(42) } returns 1
            Unit
        }
    }

    @Test
    fun `matched owner is added and group enabled after direct links are removed`() {
        existingGroup()
        owner()
        val result = reconcile("owner@example.com")
        assertThat(result).isEqualTo(AwsWorkgroupReconciliationService.Outcome("ADDED", 9, false, "ENABLED", "READY"))
        assertThat(group.enabled).isTrue()
        assertThat(group.awsAccountManaged).isTrue()
        verify { memberships.assignUsersToWorkgroup(42, listOf(7)) }
        verify { groups.removeDirectAssetLinks(42) }
        verify { publisher.publishEvent(match { it.workgroupIds == setOf(42L) }) }
    }

    @Test
    fun `existing member is not added again`() {
        existingGroup()
        owner(member = true)
        assertThat(reconcile("owner@example.com").memberOutcome).isEqualTo("ALREADY_MEMBER")
        verify(exactly = 0) { memberships.assignUsersToWorkgroup(any(), any()) }
    }

    @Test
    fun `unmatched owner disables empty group and does not create a user`() {
        existingGroup()
        group.enabled = true
        every { users.findByEmailIgnoreCase(any()) } returns Optional.empty()
        val result = reconcile("missing@example.com")
        assertThat(result.emptyMembership).isTrue()
        assertThat(group.enabled).isFalse()
        verify(exactly = 0) { users.save(any<User>()) }
        verify(exactly = 0) { memberships.assignUsersToWorkgroup(any(), any()) }
    }

    @Test
    fun `existing AD members are retained without an owner match`() {
        existingGroup()
        group.enabled = true
        every { groups.countUsersByWorkgroupId(42) } returns 2
        assertThat(reconcile().emptyMembership).isFalse()
        assertThat(group.enabled).isTrue()
        verify(exactly = 0) { memberships.removeUsersFromWorkgroup(any(), any()) }
    }

    @Test
    fun `dry run reports owner and asset changes without any mutation`() {
        existingGroup()
        owner()
        val result = reconcile("owner@example.com", true)
        assertThat(result.statusOutcome).isEqualTo("WOULD_ENABLE")
        assertThat(group.enabled).isFalse()
        assertThat(result.memberOutcome).isEqualTo("WOULD_ADD")
        assertThat(result.assetsRemoved).isEqualTo(9)
        assertThat(group.awsAccountManaged).isFalse()
        verify(exactly = 0) { groups.update(any<Workgroup>()) }
        verify(exactly = 0) { groups.removeDirectAssetLinks(any()) }
        verify(exactly = 0) { memberships.assignUsersToWorkgroup(any(), any()) }
        verify(exactly = 0) { publisher.publishEvent(any()) }
    }

    @Test
    fun `missing group dry run can resolve the owner without creating anything`() {
        owner()
        val result = service.reconcile(null, "owner@example.com", 1, true, accountId)
        assertThat(result.memberOutcome).isEqualTo("WOULD_ADD")
        assertThat(result.statusOutcome).isEqualTo("WOULD_ENABLE")
        verify(exactly = 0) { groups.update(any<Workgroup>()) }
    }

    @Test
    fun `managed workgroups reject direct assets but manual groups do not`() {
        group.requireDirectAssetAssignmentAllowed()
        group.awsAccountManaged = true
        assertThatThrownBy { group.requireDirectAssetAssignmentAllowed() }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `screenshot case enables an existing owner with two AD members and one account`() {
        existingGroup()
        every { groups.countUsersByWorkgroupId(42) } returns 2
        assertThat(reconcile().statusOutcome).isEqualTo("ENABLED")
        assertThat(reconcile().statusOutcome).isEqualTo("UNCHANGED_ENABLED")
    }

    @Test
    fun `missing persisted owner disables even when imported owner matches a member`() {
        existingGroup()
        owner(member = true)
        group.ownerEmail = null
        group.enabled = true
        assertThat(reconcile("owner@example.com").statusReason).isEqualTo("MISSING_OWNER")
        assertThat(group.enabled).isFalse()
    }

    @Test
    fun `invalid account relationships fail closed`() {
        existingGroup()
        every { groups.countUsersByWorkgroupId(42) } returns 2
        for (ids in listOf(emptyList(), listOf("000000000020"), listOf(accountId, "000000000020"))) {
            group.enabled = true
            every { accounts.findByWorkgroupId(42) } returns ids.map {
                WorkgroupAwsAccount(workgroup = group, awsAccountId = it, createdBy = null)
            }
            assertThat(reconcile().statusReason).isEqualTo("ACCOUNT_MISMATCH")
            assertThat(group.enabled).isFalse()
        }
    }

    @Test
    fun `safety threshold blocks enabling and preview uses proposed member count`() {
        existingGroup()
        owner()
        every { groups.countUsersByWorkgroupId(42) } returns 99
        every { safety.exceedsMembershipLimit(100) } returns true
        assertThat(reconcile("owner@example.com", true).statusReason).isEqualTo("MEMBERSHIP_LIMIT")
        every { safety.exceedsMembershipLimit(99) } returns true
        assertThat(reconcile().statusReason).isEqualTo("MEMBERSHIP_LIMIT")
        assertThat(group.enabled).isFalse()
    }

    @Test
    fun `dry run predicts disable without writing and cannot ignore remaining direct links on apply`() {
        existingGroup()
        group.enabled = true
        val preview = reconcile(dryRun = true)
        assertThat(preview.statusOutcome).isEqualTo("WOULD_DISABLE")
        assertThat(preview.statusReason).isEqualTo("NO_MEMBERS")
        assertThat(group.enabled).isTrue()
        verify(exactly = 0) { groups.update(any<Workgroup>()) }
        every { groups.countUsersByWorkgroupId(42) } returns 2
        every { groups.countAssetsByWorkgroupId(42) } returns 9
        assertThat(reconcile().statusReason).isEqualTo("DIRECT_ASSETS_REMAIN")
        assertThat(group.enabled).isFalse()
    }
}
