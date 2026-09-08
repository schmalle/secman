package com.secman.mcp.tools

import com.secman.domain.McpPermission
import com.secman.dto.IntegrationPage
import com.secman.dto.IntegrationSubjectDto
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.UserRepository
import com.secman.service.IntegrationReadService
import com.secman.testutil.TestDataFactory
import io.micronaut.security.authentication.Authentication
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class IntegrationToolsTest {
    private val reads = mockk<IntegrationReadService>()
    private val users = mockk<UserRepository>()
    private val tool = ListIntegrationSubjectsTool(reads, users)
    private val user = TestDataFactory.createRegularUser().also { it.id = 50 }
    private fun context() = McpExecutionContext(
        1, "test", 50, user.email, user.username, setOf("USER"), setOf(McpPermission.ASSETS_READ), true, null, null
    )

    @Test
    fun `admin key does not manufacture admin roles and delegated auth carries asset filter attributes`() = runBlocking {
        val auth = slot<Authentication>()
        every { users.findById(50) } returns Optional.of(user)
        every { reads.subjects(1, 0, 100, capture(auth)) } returns IntegrationPage<IntegrationSubjectDto>(emptyList(), 0, 0, 0, 100)
        val result = tool.execute(mapOf("scannerId" to 1), context())
        assertThat(result.isError).isFalse()
        assertThat(auth.captured.roles).containsExactly("USER")
        assertThat(auth.captured.attributes["userId"]).isEqualTo(50L)
        assertThat(auth.captured.attributes["email"]).isEqualTo(user.email)
    }

    @Test
    fun `missing delegation or explicit permission never reaches reads`() = runBlocking {
        assertThat(tool.execute(mapOf("scannerId" to 1), context().copy(delegatedUserId = null)).isError).isTrue()
        assertThat(tool.execute(mapOf("scannerId" to 1), context().copy(effectivePermissions = emptySet())).isError).isTrue()
        verify(exactly = 0) { reads.subjects(any(), any(), any(), any()) }
    }
}
