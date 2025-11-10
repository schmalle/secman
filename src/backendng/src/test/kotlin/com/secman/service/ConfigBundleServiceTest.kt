package com.secman.service

import com.secman.domain.*
import com.secman.dto.*
import com.secman.repository.*
import io.micronaut.security.authentication.Authentication
import io.micronaut.test.annotation.MockBean
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.util.Optional
import javax.persistence.EntityManager

@MicronautTest
class ConfigBundleServiceTest {

    @Inject
    lateinit var configBundleService: ConfigBundleService

    @MockBean(UserRepository::class)
    fun userRepository() = mock(UserRepository::class.java)

    @MockBean(WorkgroupRepository::class)
    fun workgroupRepository() = mock(WorkgroupRepository::class.java)

    @MockBean(UserMappingRepository::class)
    fun userMappingRepository() = mock(UserMappingRepository::class.java)

    @MockBean(IdentityProviderRepository::class)
    fun identityProviderRepository() = mock(IdentityProviderRepository::class.java)

    @MockBean(FalconConfigRepository::class)
    fun falconConfigRepository() = mock(FalconConfigRepository::class.java)

    @MockBean(McpApiKeyRepository::class)
    fun mcpApiKeyRepository() = mock(McpApiKeyRepository::class.java)

    @MockBean(EntityManager::class)
    fun entityManager() = mock(EntityManager::class.java)

    @MockBean(PasswordEncoder::class)
    fun passwordEncoder() = mock(PasswordEncoder::class.java)

    @MockBean(AuditLogService::class)
    fun auditLogService() = mock(AuditLogService::class.java)

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var workgroupRepository: WorkgroupRepository

    @Inject
    lateinit var identityProviderRepository: IdentityProviderRepository

    @Inject
    lateinit var falconConfigRepository: FalconConfigRepository

    @Inject
    lateinit var mcpApiKeyRepository: McpApiKeyRepository

    @Inject
    lateinit var userMappingRepository: UserMappingRepository

    @Inject
    lateinit var passwordEncoder: PasswordEncoder

    private lateinit var authentication: Authentication

    @BeforeEach
    fun setup() {
        authentication = mock(Authentication::class.java)
        `when`(authentication.name).thenReturn("testadmin")
        `when`(authentication.roles).thenReturn(listOf("ADMIN"))
    }

    @Test
    fun `exportBundle should export all configuration data`() {
        // Setup test data
        val testUser = User(
            id = 1L,
            username = "testuser",
            email = "test@example.com",
            passwordHash = "hashed",
            mfaEnabled = false
        ).apply {
            roles.add(Role.USER)
            createdAt = Instant.now()
        }

        val testWorkgroup = Workgroup(
            id = 1L,
            name = "TestGroup",
            description = "Test workgroup",
            criticality = "HIGH"
        ).apply {
            createdAt = Instant.now()
        }

        val testIdp = IdentityProvider(
            id = 1L,
            name = "TestIDP",
            type = IdentityProviderType.OIDC,
            clientId = "client123",
            clientSecret = "secret123",
            enabled = true
        ).apply {
            createdAt = Instant.now()
        }

        val testFalconConfig = FalconConfig(
            id = 1L,
            clientId = "falcon_client",
            clientSecret = "falcon_secret",
            cloudRegion = "us-1",
            isActive = true
        ).apply {
            createdAt = Instant.now()
        }

        val testMcpKey = McpApiKey(
            id = 1L,
            keyId = "key123",
            keyHash = "hashed_key",
            name = "TestKey",
            userId = 1L,
            permissions = "READ,WRITE",
            isActive = true
        ).apply {
            createdAt = Instant.now()
        }

        // Mock repository responses
        `when`(userRepository.findAll()).thenReturn(listOf(testUser))
        `when`(workgroupRepository.findAll()).thenReturn(listOf(testWorkgroup))
        `when`(userMappingRepository.findAll()).thenReturn(emptyList())
        `when`(identityProviderRepository.findAll()).thenReturn(listOf(testIdp))
        `when`(falconConfigRepository.findAll()).thenReturn(listOf(testFalconConfig))
        `when`(mcpApiKeyRepository.findAll()).thenReturn(listOf(testMcpKey))
        `when`(userRepository.findById(1L)).thenReturn(Optional.of(testUser))

        // Execute
        val bundle = configBundleService.exportBundle(authentication)

        // Verify
        assertNotNull(bundle)
        assertEquals(ConfigBundleService.BUNDLE_VERSION, bundle.version)
        assertEquals("testadmin", bundle.exportedBy)
        assertEquals(1, bundle.users.size)
        assertEquals("testuser", bundle.users[0].username)
        assertEquals(1, bundle.workgroups.size)
        assertEquals("TestGroup", bundle.workgroups[0].name)
        assertEquals(1, bundle.identityProviders.size)
        assertTrue(bundle.identityProviders[0].clientSecretMasked)
        assertNull(bundle.identityProviders[0].clientSecret) // Should be masked
        assertEquals(1, bundle.falconConfigs.size)
        assertTrue(bundle.falconConfigs[0].clientSecretMasked)
        assertEquals(1, bundle.mcpApiKeys.size)
        assertEquals("TestKey", bundle.mcpApiKeys[0].name)

        // Verify audit log was called
        verify(auditLogService).logAction(
            eq(authentication),
            eq("EXPORT_CONFIG_BUNDLE"),
            eq("ConfigBundle"),
            anyString()
        )
    }

    @Test
    fun `validateBundle should detect conflicts`() {
        // Setup existing data
        val existingUser = User(
            id = 1L,
            username = "existinguser",
            email = "existing@example.com",
            passwordHash = "hashed"
        )

        val existingWorkgroup = Workgroup(
            id = 1L,
            name = "ExistingGroup",
            description = "Existing workgroup"
        )

        `when`(userRepository.findByUsername("existinguser")).thenReturn(Optional.of(existingUser))
        `when`(userRepository.findByEmail("existing@example.com")).thenReturn(existingUser)
        `when`(workgroupRepository.findByName("ExistingGroup")).thenReturn(Optional.of(existingWorkgroup))

        // Create bundle with conflicting data
        val bundle = ConfigBundleDto(
            version = ConfigBundleService.BUNDLE_VERSION,
            exportedAt = Instant.now(),
            exportedBy = "admin",
            users = listOf(
                UserExportDto(
                    username = "existinguser",
                    email = "existing@example.com",
                    roles = setOf("USER")
                )
            ),
            workgroups = listOf(
                WorkgroupExportDto(
                    name = "ExistingGroup",
                    description = "Test"
                )
            )
        )

        // Execute
        val validation = configBundleService.validateBundle(bundle)

        // Verify
        assertFalse(validation.isValid) // Should be invalid due to conflicts
        assertEquals(2, validation.conflicts.size) // Username and email conflicts
        assertTrue(validation.conflicts.any { it.entityType == "User" && it.identifier == "existinguser" })
        assertTrue(validation.conflicts.any { it.entityType == "User" && it.identifier == "existing@example.com" })
    }

    @Test
    fun `validateBundle should detect required secrets`() {
        // Create bundle with masked secrets
        val bundle = ConfigBundleDto(
            version = ConfigBundleService.BUNDLE_VERSION,
            exportedAt = Instant.now(),
            exportedBy = "admin",
            identityProviders = listOf(
                IdentityProviderExportDto(
                    name = "TestIDP",
                    type = "OIDC",
                    clientId = "client123",
                    clientSecretMasked = true,
                    clientSecret = null
                )
            ),
            falconConfigs = listOf(
                FalconConfigExportDto(
                    cloudRegion = "us-1",
                    clientIdMasked = true,
                    clientSecretMasked = true
                )
            )
        )

        // Execute
        val validation = configBundleService.validateBundle(bundle)

        // Verify
        assertEquals(3, validation.requiredSecrets.size) // IDP secret + Falcon ID + Falcon secret
        assertTrue(validation.requiredSecrets.any {
            it.entityType == "IdentityProvider" && it.secretType == "client_secret"
        })
        assertTrue(validation.requiredSecrets.any {
            it.entityType == "FalconConfig" && it.secretType == "client_id"
        })
        assertTrue(validation.requiredSecrets.any {
            it.entityType == "FalconConfig" && it.secretType == "client_secret"
        })
    }

    @Test
    fun `importBundle with dryRun should not persist data`() {
        // Setup
        val bundle = ConfigBundleDto(
            version = ConfigBundleService.BUNDLE_VERSION,
            exportedAt = Instant.now(),
            exportedBy = "admin",
            users = listOf(
                UserExportDto(
                    username = "newuser",
                    email = "new@example.com",
                    roles = setOf("USER")
                )
            )
        )

        val request = ImportBundleRequest(
            bundle = bundle,
            options = ImportOptions(dryRun = true)
        )

        // Mock empty existing data
        `when`(userRepository.findByUsername(anyString())).thenReturn(Optional.empty())
        `when`(userRepository.findByEmail(anyString())).thenReturn(null)
        `when`(userRepository.findAll()).thenReturn(listOf(
            User(id = 1L, username = "admin", email = "admin@example.com", passwordHash = "hash").apply {
                roles.add(Role.ADMIN)
            }
        ))

        // Execute
        val result = configBundleService.importBundle(request, authentication)

        // Verify
        assertTrue(result.success)
        assertEquals("Dry run validation successful", result.message)
        assertEquals(0, result.imported.users) // Nothing should be imported in dry run
        verify(userRepository, never()).save(any(User::class.java))
    }

    @Test
    fun `importBundle should generate temporary passwords for users`() {
        // Setup
        val bundle = ConfigBundleDto(
            version = ConfigBundleService.BUNDLE_VERSION,
            exportedAt = Instant.now(),
            exportedBy = "admin",
            users = listOf(
                UserExportDto(
                    username = "newuser",
                    email = "new@example.com",
                    roles = setOf("USER")
                )
            )
        )

        val request = ImportBundleRequest(
            bundle = bundle,
            options = ImportOptions(generateTempPasswords = true, dryRun = false)
        )

        // Mock empty existing data
        `when`(userRepository.findByUsername("newuser")).thenReturn(Optional.empty())
        `when`(userRepository.findByEmail("new@example.com")).thenReturn(null)
        `when`(passwordEncoder.encode(anyString())).thenReturn("encoded_temp_password")

        // Execute
        val result = configBundleService.importBundle(request, authentication)

        // Verify
        verify(passwordEncoder, atLeastOnce()).encode(anyString())
        assertTrue(result.warnings.any { it.contains("Generated temporary password") })
    }

    @Test
    fun `importBundle should prevent removing last admin`() {
        // Setup - no existing admins
        `when`(userRepository.findAll()).thenReturn(emptyList())

        // Bundle with no admin users
        val bundle = ConfigBundleDto(
            version = ConfigBundleService.BUNDLE_VERSION,
            exportedAt = Instant.now(),
            exportedBy = "admin",
            users = listOf(
                UserExportDto(
                    username = "regularuser",
                    email = "user@example.com",
                    roles = setOf("USER") // Not an admin
                )
            )
        )

        // Execute
        val validation = configBundleService.validateBundle(bundle)

        // Verify
        assertFalse(validation.isValid)
        assertTrue(validation.errors.any { it.contains("without any ADMIN users") })
    }
}