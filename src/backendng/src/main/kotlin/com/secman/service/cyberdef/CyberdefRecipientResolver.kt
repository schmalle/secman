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
    /** Result of [resolveOverride]: the routing to use, or the addresses SecMan may not mail. */
    sealed class Override {
        /** Every address may be mailed. */
        data class Accepted(val routing: CyberdefRouting) : Override()

        /** Some addresses are neither known to SecMan nor on an allowed domain. */
        data class Refused(val addresses: List<String>) : Override()
    }

    /**
     * Routing for an explicit recipient list instead of the account's owners.
     *
     * SecMan's mail server must not become a relay to arbitrary inboxes, so each address has
     * to be a known SecMan user or mapping, or sit on `recipient-override.allowed-domains`.
     * The account name still comes from the account's mappings.
     */
    open fun resolveOverride(awsAccountId: String, addresses: List<String>): Override {
        val refused = addresses.filterNot(::mayOverride)
        if (refused.isNotEmpty()) return Override.Refused(refused)
        val accountName = userMappings.findRecipientRowsByAwsAccountId(awsAccountId)
            .firstNotNullOfOrNull { (it[2] as? String)?.takeIf(String::isNotBlank) }
        val recipients = addresses.map { email ->
            CyberdefRecipient(email, users.findByEmailIgnoreCase(email).map { it.id }.orElse(null), SOURCE_OVERRIDE)
        }
        return Override.Accepted(CyberdefRouting(recipients, accountName, truncated = false))
    }

    private fun mayOverride(email: String): Boolean {
        if (!EmailAddressValidator.isValidRecipient(email)) return false
        val domain = email.substringAfterLast('@').lowercase(Locale.ROOT)
        return domain in settings.overrideAllowedDomains ||
            users.findByEmailIgnoreCase(email).isPresent || userMappings.existsByEmail(email)
    }

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
