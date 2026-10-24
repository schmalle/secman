package com.secman.security

import com.secman.repository.UserRepository
import io.micronaut.http.HttpRequest
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecuredAnnotationRule
import io.micronaut.security.rules.SecurityRule
import io.micronaut.security.rules.SecurityRuleResult
import jakarta.inject.Singleton
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/** Reject stale signed identities; AI buddy's guarded namespace reloads roles locally. */
@Singleton
class CurrentIdentityRule(private val users: UserRepository) : SecurityRule<HttpRequest<*>> {
    override fun getOrder(): Int = SecuredAnnotationRule.ORDER - 100

    override fun check(request: HttpRequest<*>?, authentication: Authentication?): Publisher<SecurityRuleResult> {
        if (authentication == null) return Mono.just(SecurityRuleResult.UNKNOWN)
        return Mono.fromCallable {
            // AI buddy authorizes every operation with freshly loaded roles and asset scope.
            // Keep stale-claim rejection everywhere else, including similarly named prefixes.
            val liveRoleRoute = request?.path?.startsWith("/api/ai-buddy/") == true
            val id = authentication.attributes["userId"]?.toString()?.toLongOrNull()
            val current = id?.let { identityOf(users.findIdentityRows(it)) }
            if (current == null || !current.enabled || current.username != authentication.name ||
                (!liveRoleRoute && current.roles != authentication.roles.toSet()) ||
                authentication.attributes["email"]?.toString()?.let { it != current.email } == true) {
                SecurityRuleResult.REJECTED
            } else SecurityRuleResult.UNKNOWN
        }.subscribeOn(Schedulers.boundedElastic()).onErrorReturn(SecurityRuleResult.REJECTED)
    }

    /** The live account state a signed identity is compared against. */
    private class Identity(val enabled: Boolean, val username: String, val email: String, val roles: Set<String>)

    /** Rows are (enabled, username, email, role); a user without roles yields one row with a null role. */
    private fun identityOf(rows: List<Array<Any?>>): Identity? {
        val first = rows.firstOrNull() ?: return null
        return Identity(
            enabled = first[0] as Boolean,
            username = first[1] as String,
            email = first[2] as String,
            roles = rows.mapNotNull { (it[3] as? Enum<*>)?.name }.toSet()
        )
    }
}
