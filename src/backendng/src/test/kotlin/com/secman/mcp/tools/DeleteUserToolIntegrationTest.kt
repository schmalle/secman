package com.secman.mcp.tools

import com.secman.domain.McpApiKey
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.McpApiKeyRepository
import com.secman.repository.UserRepository
import com.secman.service.UserService
import com.secman.testutil.TestDataFactory
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

/** delete_user must use the same complete deletion as REST, so no API key outlives its owner. */
@MicronautTest(environments = ["test"], transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeleteUserToolIntegrationTest {

    @Inject lateinit var tool: DeleteUserTool
    @Inject lateinit var userRepository: UserRepository
    @Inject lateinit var userService: UserService
    @Inject lateinit var mcpApiKeyRepository: McpApiKeyRepository

    private val created = mutableListOf<Long>()

    @AfterEach
    fun cleanUp() {
        created.filter { userRepository.existsById(it) }.forEach { userService.deleteUser(it) }
        created.clear()
    }

    @Test
    fun `delete_user removes the user's MCP API keys`() {
        val tag = UUID.randomUUID().toString().take(8)
        val target = userRepository.save(TestDataFactory.createRegularUser("tool-$tag", "tool-$tag@secman.test"))
        created += target.id!!
        mcpApiKeyRepository.save(
            McpApiKey(keyId = "tool-test-key-$tag", keyHash = "h", name = "k", userId = target.id!!, permissions = "ASSETS_READ")
        )

        val result = runBlocking { tool.execute(mapOf("userId" to target.id!!), adminContext()) }

        assertThat(result.isError).isFalse()
        assertThat(userRepository.existsById(target.id!!)).isFalse()
        assertThat(mcpApiKeyRepository.findByUserId(target.id!!)).isEmpty()
    }

    private fun adminContext() = McpExecutionContext(
        apiKeyId = 1L, apiKeyName = "k", delegatedUserId = -1L,
        delegatedUserEmail = "admin@example.com", delegatedUsername = "admin",
        delegatedUserRoles = setOf("ADMIN"), effectivePermissions = emptySet(), isAdmin = true,
        accessibleAssetIds = null, accessibleWorkgroupIds = null
    )
}
