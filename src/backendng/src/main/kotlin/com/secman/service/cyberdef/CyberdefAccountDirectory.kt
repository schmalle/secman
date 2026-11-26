package com.secman.service.cyberdef

import com.secman.dto.CyberdefAccountDto
import com.secman.repository.UserMappingRepository
import com.secman.repository.WorkgroupAwsAccountRepository
import com.secman.util.EmailAddressValidator
import io.micronaut.data.model.Pageable
import jakarta.inject.Singleton
import java.util.Locale

/**
 * Account lookup for the ticket form. CYBERDEF holds no asset or account access, so this is
 * the one place it learns which accounts exist: id, display name and how many people a
 * ticket would reach. It never returns an email address.
 *
 * Four queries per lookup whatever the page size: two searches, two contact reads.
 */
@Singleton
open class CyberdefAccountDirectory(
    private val userMappings: UserMappingRepository,
    private val workgroupAccounts: WorkgroupAwsAccountRepository
) {
    /** Accounts whose id starts with, or whose name contains, [query]; at most [limit] (max 20). */
    open fun search(query: String?, limit: Int): List<CyberdefAccountDto> {
        val term = query?.trim()?.lowercase(Locale.ROOT)?.filter { it.isLetterOrDigit() || it in " ._-" }?.take(64).orEmpty()
        val page = Pageable.from(0, limit.coerceIn(1, MAX_RESULTS))
        val escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val names = userMappings.searchAwsAccounts("$escaped%", "%$escaped%", page)
            .associate { (it[0] as String) to (it[1] as? String)?.takeIf(String::isNotBlank) }
        val granted = workgroupAccounts.searchGrantedAwsAccountIds("$escaped%", page)
        val ids = (names.keys + granted).sorted().take(page.size)
        if (ids.isEmpty()) return emptyList()

        val reach = mutableMapOf<String, MutableSet<String>>()
        (userMappings.findEmailsByAwsAccountIdIn(ids) + workgroupAccounts.findMemberEmailsByAwsAccountIdIn(ids)).forEach { row ->
            val email = (row[1] as? String)?.trim()?.lowercase(Locale.ROOT)
            if (email != null && EmailAddressValidator.isValidRecipient(email)) {
                reach.getOrPut(row[0] as String) { mutableSetOf() } += email
            }
        }
        return ids.map { id ->
            val count = reach[id]?.size ?: 0
            CyberdefAccountDto(awsAccountId = id, accountName = names[id], recipientCount = count, routable = count > 0)
        }
    }

    companion object {
        const val MAX_RESULTS = 20
    }
}
