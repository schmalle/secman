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

/** Reject stale signed identities before endpoint role rules can allow the request. */
@Singleton
class CurrentIdentityRule(private val users: UserRepository) : SecurityRule<HttpRequest<*>> {
    override fun getOrder(): Int = SecuredAnnotationRule.ORDER - 100

    override fun check(request: HttpRequest<*>?, authentication: Authentication?): Publisher<SecurityRuleResult> {
        if (authentication == null) return Mono.just(SecurityRuleResult.UNKNOWN)
        return Mono.fromCallable {
            val id = authentication.attributes["userId"]?.toString()?.toLongOrNull()
            val current = id?.let { users.findById(it).orElse(null) }
            if (current == null || !current.enabled || current.username != authentication.name ||
                current.roles.map { it.name }.toSet() != authentication.roles.toSet() ||
                authentication.attributes["email"]?.toString()?.let { it != current.email } == true) {
                SecurityRuleResult.REJECTED
            } else SecurityRuleResult.UNKNOWN
        }.subscribeOn(Schedulers.boundedElastic()).onErrorReturn(SecurityRuleResult.REJECTED)
    }
}
