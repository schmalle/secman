package com.secman.service

import com.secman.domain.UserMapping
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Delete semantics for user mappings.
 *
 * Two endpoints reach the service with genuinely different questions, and conflating them
 * broke the admin one:
 *
 *  - `DELETE /api/user-mappings/{id}` (`@Secured("ADMIN")`) addresses a mapping by id alone
 *    -> [UserMappingService.deleteMappingById]. Authorization is the annotation.
 *  - `DELETE /api/users/{userId}/mappings/{mappingId}` names *whose* mapping to delete
 *    -> [UserMappingService.deleteMapping], where the email comparison is a consistency
 *    guard against deleting one user's mapping through another user's URL.
 *
 * The admin endpoint used to call the second method with the *caller's* id, so an admin
 * could delete only mappings carrying their own address and got an unhandled
 * IllegalArgumentException (HTTP 500) for every other one. These tests pin both halves:
 * the admin path must delete anyone's mapping, and the scoped path must still refuse a
 * mapping that belongs to someone else.
 */
open class UserMappingServiceDeleteTest : BaseIntegrationTest() {

    @Inject
    lateinit var service: UserMappingService

    @Inject
    lateinit var mappingRepository: UserMappingRepository

    @Inject
    lateinit var userRepository: UserRepository

    @AfterEach
    fun tearDown() {
        mappingRepository.deleteAll()
        userRepository.findByUsername("del-test-owner").ifPresent { userRepository.delete(it) }
        userRepository.findByUsername("del-test-admin").ifPresent { userRepository.delete(it) }
    }

    @Test
    fun `admin delete removes a mapping belonging to another user`() {
        // The regression: the mapping's email is the owner's, the caller is the admin.
        userRepository.save(TestDataFactory.createRegularUser("del-test-owner", "del-owner@corp.com"))
        val mapping = mappingRepository.save(
            UserMapping(email = "del-owner@corp.com", awsAccountId = "111111111111", domain = null)
        )

        val deleted = service.deleteMappingById(mapping.id!!)

        assertThat(deleted).isTrue()
        assertThat(mappingRepository.findById(mapping.id!!)).isEmpty()
    }

    @Test
    fun `admin delete removes a mapping whose email has no user account`() {
        // Imports create mappings for addresses that are not provisioned yet, so there is
        // not always an owner to compare against — the old ownership check could never
        // succeed for these.
        val mapping = mappingRepository.save(
            UserMapping(email = "nobody-has-this@corp.com", awsAccountId = "222222222222", domain = null)
        )

        assertThat(service.deleteMappingById(mapping.id!!)).isTrue()
        assertThat(mappingRepository.findById(mapping.id!!)).isEmpty()
    }

    @Test
    fun `admin delete of an unknown id reports not-found rather than a server error`() {
        // The controller maps NoSuchElementException to 404; anything else escapes as 500.
        assertThatThrownBy { service.deleteMappingById(999_999_999L) }
            .isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `scoped delete still refuses a mapping that belongs to a different user`() {
        // Guards the fix from over-reaching: /api/users/{userId}/mappings/{mappingId} must
        // not delete someone else's mapping just because the id was guessed.
        val admin = userRepository.save(
            TestDataFactory.createRegularUser("del-test-admin", "del-admin@corp.com")
        )
        val mapping = mappingRepository.save(
            UserMapping(email = "del-owner@corp.com", awsAccountId = "333333333333", domain = null)
        )

        assertThatThrownBy { service.deleteMapping(admin.id!!, mapping.id!!) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("does not belong to user")

        assertThat(mappingRepository.findById(mapping.id!!)).isPresent()
    }
}
