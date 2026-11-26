package com.secman.service.cyberdef

import com.secman.repository.UserMappingRepository
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
    private val settings: CyberdefTicketSettings
) {
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
    }
}
