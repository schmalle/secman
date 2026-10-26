package com.secman.service

import com.secman.dto.OverdueMatchType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Deduplication and ordering of recipient rows, without a database. */
class WorkgroupOverdueRecipientQueryTest {

    private fun row(
        email: String, assetId: Long, type: OverdueMatchType, workgroup: String,
        grantAccount: String? = null, userId: Long = 1, oldestDays: Long = 40, overdue: Int = 3
    ) = WorkgroupOverdueRecipientQuery.MatchRow(
        userId = userId, email = email, assetId = assetId, assetName = "asset-$assetId", ipAddress = null,
        assetAccountId = grantAccount, overdueCount = overdue, criticalCount = 1, highCount = 2, mediumCount = 0,
        lowCount = 0, oldestDays = oldestDays, type = type, workgroupId = workgroup.hashCode().toLong(),
        workgroupName = workgroup, grantAccountId = grantAccount
    )

    @Test
    fun `one recipient per email regardless of case and whitespace`() {
        val recipients = WorkgroupOverdueRecipientQuery.groupByRecipient(listOf(
            row("Alice@X.com", 1, OverdueMatchType.DIRECT_ASSIGNMENT, "wg-a"),
            row("alice@x.com ", 2, OverdueMatchType.AWS_WORKGROUP, "wg-b", "000000000001"),
            row("bob@x.com", 1, OverdueMatchType.DIRECT_ASSIGNMENT, "wg-a", userId = 2)
        ))

        assertThat(recipients.map { it.email }).containsExactly("alice@x.com", "bob@x.com")
        assertThat(recipients.first().assets.map { it.assetId }).containsExactlyInAnyOrder(1L, 2L)
    }

    @Test
    fun `an asset reached through several paths appears once with every reason and unmultiplied counts`() {
        val recipients = WorkgroupOverdueRecipientQuery.groupByRecipient(listOf(
            row("u@x.com", 7, OverdueMatchType.AWS_WORKGROUP, "wg-b", "000000000001"),
            row("u@x.com", 7, OverdueMatchType.DIRECT_ASSIGNMENT, "wg-a"),
            row("u@x.com", 7, OverdueMatchType.AWS_WORKGROUP, "wg-c", "000000000001"),
            row("u@x.com", 7, OverdueMatchType.DIRECT_ASSIGNMENT, "wg-a") // exact duplicate path
        ))

        val asset = recipients.single().assets.single()
        assertThat(asset.overdueCount).isEqualTo(3)
        assertThat(asset.reasons.map { it.type to it.workgroupName }).containsExactly(
            OverdueMatchType.DIRECT_ASSIGNMENT to "wg-a",
            OverdueMatchType.AWS_WORKGROUP to "wg-b",
            OverdueMatchType.AWS_WORKGROUP to "wg-c"
        )
    }

    @Test
    fun `assets are ordered oldest first and account ids keep leading zeros`() {
        val assets = WorkgroupOverdueRecipientQuery.groupByRecipient(listOf(
            row("u@x.com", 1, OverdueMatchType.AWS_WORKGROUP, "wg", "001234567890", oldestDays = 31),
            row("u@x.com", 2, OverdueMatchType.AWS_WORKGROUP, "wg", "001234567890", oldestDays = 90)
        )).single().assets

        assertThat(assets.map { it.assetId }).containsExactly(2L, 1L)
        assertThat(assets.first().awsAccountId).isEqualTo("001234567890")
        assertThat(assets.first().reasons.single().awsAccountId).isEqualTo("001234567890")
    }

    @Test
    fun `no rows means no recipients`() {
        assertThat(WorkgroupOverdueRecipientQuery.groupByRecipient(emptyList())).isEmpty()
    }
}
