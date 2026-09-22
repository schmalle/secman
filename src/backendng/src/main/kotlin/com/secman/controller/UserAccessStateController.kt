package com.secman.controller

import com.secman.repository.UserRepository
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.serde.annotation.Serdeable
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory

/** Administrative suspension and explicit machine-identity classification. */
@Controller("/api/users/{id}/access-state")
@Secured("ADMIN")
@ExecuteOn(TaskExecutors.BLOCKING)
open class UserAccessStateController(private val users: UserRepository) {
    @Serdeable data class AccessState(val enabled: Boolean, val serviceAccount: Boolean)

    /** Apply suspension and service-account classification through the ADMIN boundary. */
    @Put
    @Transactional
    open fun update(id: Long, @Body state: AccessState, authentication: Authentication): AccessState {
        val user = users.findById(id).orElseThrow { IllegalArgumentException("User not found") }
        user.enabled = state.enabled
        user.serviceAccount = state.serviceAccount
        users.update(user)
        LoggerFactory.getLogger(javaClass).info("User access state changed actor={} target={} enabled={} serviceAccount={}",
            authentication.name, id, state.enabled, state.serviceAccount)
        return state
    }
}
