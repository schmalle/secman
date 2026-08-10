package com.secman.repository

import com.secman.domain.RelayIdentity
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

/**
 * Repository for [RelayIdentity] — the external-account to secman-user mapping
 * used by the mobile relay.
 *
 * Derived queries only: nothing here interpolates a value into a query string.
 */
@Repository
interface RelayIdentityRepository : JpaRepository<RelayIdentity, Long> {

    fun findByUserId(userId: Long): List<RelayIdentity>

    fun findByProviderAndProviderSubject(provider: String, providerSubject: String): RelayIdentity?

    fun findByUserIdAndProvider(userId: Long, provider: String): RelayIdentity?

    fun deleteByUserIdAndProvider(userId: Long, provider: String): Long

    fun countByUserId(userId: Long): Long
}
