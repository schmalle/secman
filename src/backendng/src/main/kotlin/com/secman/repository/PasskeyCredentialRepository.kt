package com.secman.repository

import com.secman.domain.PasskeyCredential
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

/**
 * Repository for PasskeyCredential entities
 * Feature: Passkey MFA Support
 */
@Repository
interface PasskeyCredentialRepository : JpaRepository<PasskeyCredential, Long> {

    /**
     * Find all passkeys for a given user
     */
    fun findByUserId(userId: Long): List<PasskeyCredential>

    /**
     * Find a specific passkey by credential ID
     */
    fun findByCredentialId(credentialId: String): Optional<PasskeyCredential>

    /**
     * Delete all passkeys for a given user
     */
    fun deleteByUserId(userId: Long): Int

    /**
     * Count passkeys for a given user
     */
    fun countByUserId(userId: Long): Long
}
