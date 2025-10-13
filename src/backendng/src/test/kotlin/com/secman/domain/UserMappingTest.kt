package com.secman.domain

import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach

/**
 * Test suite for UserMapping entity
 * Feature: 013-user-mapping-upload
 * 
 * Tests entity validation, persistence, normalization, and constraints.
 * Following TDD approach - these tests are written BEFORE entity implementation.
 */
@MicronautTest
@Transactional
open class UserMappingTest {

    @Inject
    lateinit var entityManager: EntityManager

    @AfterEach
    fun cleanup() {
        entityManager.createQuery("DELETE FROM UserMapping").executeUpdate()
    }

    @Test
    fun `should create user mapping with all fields`() {
        val mapping = UserMapping(
            email = "test@example.com",
            awsAccountId = "123456789012",
            domain = "example.com"
        )
        
        entityManager.persist(mapping)
        entityManager.flush()
        
        assertNotNull(mapping.id, "ID should be generated")
        assertNotNull(mapping.createdAt, "Created timestamp should be set")
        assertNotNull(mapping.updatedAt, "Updated timestamp should be set")
        assertEquals("test@example.com", mapping.email)
        assertEquals("123456789012", mapping.awsAccountId)
        assertEquals("example.com", mapping.domain)
    }

    @Test
    fun `should normalize email to lowercase on persist`() {
        val mapping = UserMapping(
            email = "Test@Example.COM",
            awsAccountId = "123456789012",
            domain = "example.com"
        )
        
        entityManager.persist(mapping)
        entityManager.flush()
        entityManager.clear()
        
        val saved = entityManager.find(UserMapping::class.java, mapping.id)
        assertEquals("test@example.com", saved.email, "Email should be normalized to lowercase")
    }

    @Test
    fun `should normalize domain to lowercase on persist`() {
        val mapping = UserMapping(
            email = "test@example.com",
            awsAccountId = "123456789012",
            domain = "Example.COM"
        )
        
        entityManager.persist(mapping)
        entityManager.flush()
        entityManager.clear()
        
        val saved = entityManager.find(UserMapping::class.java, mapping.id)
        assertEquals("example.com", saved.domain, "Domain should be normalized to lowercase")
    }

    @Test
    fun `should trim whitespace from all fields on persist`() {
        val mapping = UserMapping(
            email = "  test@example.com  ",
            awsAccountId = "  123456789012  ",
            domain = "  example.com  "
        )
        
        entityManager.persist(mapping)
        entityManager.flush()
        entityManager.clear()
        
        val saved = entityManager.find(UserMapping::class.java, mapping.id)
        assertEquals("test@example.com", saved.email, "Email should be trimmed")
        assertEquals("123456789012", saved.awsAccountId, "AWS account ID should be trimmed")
        assertEquals("example.com", saved.domain, "Domain should be trimmed")
    }

    @Test
    fun `should enforce unique constraint on composite key`() {
        val mapping1 = UserMapping(
            email = "test@example.com",
            awsAccountId = "123456789012",
            domain = "example.com"
        )
        entityManager.persist(mapping1)
        entityManager.flush()

        val mapping2 = UserMapping(
            email = "test@example.com",
            awsAccountId = "123456789012",
            domain = "example.com"
        )
        
        assertThrows(Exception::class.java) {
            entityManager.persist(mapping2)
            entityManager.flush()
        }
    }

    @Test
    fun `should allow same email with different AWS account`() {
        val mapping1 = UserMapping(
            email = "test@example.com",
            awsAccountId = "123456789012",
            domain = "example.com"
        )
        entityManager.persist(mapping1)
        entityManager.flush()

        val mapping2 = UserMapping(
            email = "test@example.com",
            awsAccountId = "987654321098",
            domain = "example.com"
        )
        entityManager.persist(mapping2)
        entityManager.flush()
        
        assertNotNull(mapping2.id, "Second mapping should be created successfully")
        assertNotEquals(mapping1.id, mapping2.id, "Mappings should have different IDs")
    }

    @Test
    fun `should allow same email with different domain`() {
        val mapping1 = UserMapping(
            email = "test@example.com",
            awsAccountId = "123456789012",
            domain = "example.com"
        )
        entityManager.persist(mapping1)
        entityManager.flush()

        val mapping2 = UserMapping(
            email = "test@example.com",
            awsAccountId = "123456789012",
            domain = "other.com"
        )
        entityManager.persist(mapping2)
        entityManager.flush()
        
        assertNotNull(mapping2.id, "Second mapping should be created successfully")
        assertNotEquals(mapping1.id, mapping2.id, "Mappings should have different IDs")
    }

    @Test
    fun `should auto-populate timestamps on creation`() {
        val mapping = UserMapping(
            email = "test@example.com",
            awsAccountId = "123456789012",
            domain = "example.com"
        )
        
        val beforeCreation = java.time.Instant.now()
        Thread.sleep(10) // Small delay to ensure time difference
        
        entityManager.persist(mapping)
        entityManager.flush()
        
        assertNotNull(mapping.createdAt, "createdAt should be set")
        assertNotNull(mapping.updatedAt, "updatedAt should be set")
        assertTrue(
            mapping.createdAt!!.isAfter(beforeCreation) || mapping.createdAt == beforeCreation,
            "createdAt should be after or equal to creation time"
        )
        assertEquals(mapping.createdAt, mapping.updatedAt, "createdAt and updatedAt should be equal on creation")
    }
}
