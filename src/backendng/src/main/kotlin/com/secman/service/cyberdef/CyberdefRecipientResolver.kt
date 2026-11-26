package com.secman.service.cyberdef

import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupAwsAccountRepository
import com.secman.util.EmailAddressValidator
import jakarta.inject.Singleton
import java.util.Locale

/** One address to mail, with the user it belongs to (null for a future user) and why. */
data class CyberdefRecipient(val email: String, val userId: Long?, val source: String)

/** Recipients of an account plus its display name; [truncated] when the cap cut the list. */
data class CyberdefRouting(
    val recipients: List<CyberdefRecipient>,
    val accountName: String?,
    val truncated: Boolean
)

/**
 * Who is told about a ticket for an AWS account: everyone with a personal mapping for it
 * (future users included, they get the mail before their first login) and the direct
 * members of enabled workgroups that hold a grant for it.
 *
 * Received AWS-account sharing is deliberately not a source: sharing grants a view, not
 * ownership, so those users see the ticket but are not mailed about it.
 */
@Singleton
open class CyberdefRecipientResolver(
    private val userMappings: UserMappingRepository,
    private val workgroupAccounts: WorkgroupAwsAccountRepository,
    private val users: UserRepository,
    private val settings: CyberdefTicketSettings
) {
    /** Result of [checkOverride]: the recipients to use, or the addresses SecMan may not mail. */
    sealed class Override {
        /** Every address may be mailed. */
        data class Accepted(val recipients: List<CyberdefRecipient>) : Override()

        /** Some addresses are neither known to SecMan nor on an allowed domain. */
        data class Refused(val addresses: List<String>) : Override()
    }

    /**
     * Whether SecMan may mail an explicit recipient list instead of an account's owners.
     *
     * SecMan's mail server must not become a relay to arbitrary inboxes, so each address has
     * to be a known SecMan user or mapping, or sit on `recipient-override.allowed-domains`.
     * The answer does not depend on the account, so a batch asks once: two queries for the
     * whole list, whatever its length.
     */
    open fun checkOverride(addresses: List<String>): Override {
        val normalized = addresses.map { it.trim().lowercase(Locale.ROOT) }.distinct()
        if (normalized.isEmpty()) return Override.Accepted(emptyList())
        val userIds = users.findIdsByLowerEmailIn(normalized)
            .associate { (it[1] as String) to (it[0] as? Number)?.toLong() }
        val mapped = userMappings.findKnownLowerEmails(normalized).toSet()
        val refused = normalized.filterNot { email ->
            EmailAddressValidator.isValidRecipient(email) &&
                (email.substringAfterLast('@') in settings.overrideAllowedDomains || email in userIds || email in mapped)
        }
        if (refused.isNotEmpty()) return Override.Refused(refused)
        return Override.Accepted(normalized.map { CyberdefRecipient(it, userIds[it], SOURCE_OVERRIDE) })
    }

    /** The display name of [awsAccountId] from its mappings, for mails and the ticket record. */
    open fun accountName(awsAccountId: String): String? =
        userMappings.findRecipientRowsByAwsAccountId(awsAccountId)
            .firstNotNullOfOrNull { (it[2] as? String)?.takeIf(String::isNotBlank) }

    /** Recipients for [awsAccountId], mapping sources first, capped at `secman.cyberdef.max-recipients`. */
    open fun resolve(awsAccountId: String): CyberdefRouting {
        val mappingRows = userMappings.findRecipientRowsByAwsAccountId(awsAccountId)
        val fromMappings = mappingRows.mapNotNull { row ->
            recipient(row[0] as? String, (row[1] as? Number)?.toLong(), SOURCE_MAPPING)
        }
        val fromWorkgroups = workgroupAccounts.findMemberContactsByAwsAccountId(awsAccountId).mapNotNull { row ->
            recipient(row[1] as? String, (row[0] as? Number)?.toLong(), SOURCE_WORKGROUP)
        }
        // Mapping first, so a user reachable both ways is recorded under their personal mapping.
        val unique = (fromMappings + fromWorkgroups).distinctBy { it.email }
        val accountName = mappingRows.firstNotNullOfOrNull { (it[2] as? String)?.takeIf(String::isNotBlank) }
        val cap = settings.maxRecipients.coerceAtLeast(1)
        return CyberdefRouting(unique.take(cap), accountName, unique.size > cap)
    }

    private fun recipient(email: String?, userId: Long?, source: String): CyberdefRecipient? {
        val normalized = email?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (!EmailAddressValidator.isValidRecipient(normalized)) return null
        return CyberdefRecipient(normalized, userId, source)
    }

    companion object {
        const val SOURCE_MAPPING = "MAPPING"
        const val SOURCE_WORKGROUP = "WORKGROUP"
        const val SOURCE_OVERRIDE = "OVERRIDE"
    }
}
