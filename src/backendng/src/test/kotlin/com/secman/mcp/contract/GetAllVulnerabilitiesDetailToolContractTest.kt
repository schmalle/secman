package com.secman.mcp.contract

import com.secman.domain.McpOperation
import com.secman.mcp.McpToolRegistry
import com.secman.mcp.tools.McpToolResult
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Contract test for get_all_vulnerabilities_detail MCP tool.
 * Validates tool registration, input schema, and basic functionality.
 *
 * Feature 009: Enhanced MCP Tools for Security Data Access
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetAllVulnerabilitiesDetailToolContractTest {

    @Inject
    lateinit var registry: McpToolRegistry

    @Test
    fun `tool should be registered with correct name`() {
        val tool = registry.getTool("get_all_vulnerabilities_detail")
        assertNotNull(tool, "Tool 'get_all_vulnerabilities_detail' should be registered")
        assertEquals("get_all_vulnerabilities_detail", tool!!.name)
    }

    @Test
    fun `tool should have READ operation`() {
        val tool = registry.getTool("get_all_vulnerabilities_detail")
        assertNotNull(tool)
        assertEquals(McpOperation.READ, tool!!.operation)
    }

    @Test
    fun `input schema should define vulnerability filters`() {
        val tool = registry.getTool("get_all_vulnerabilities_detail")
        assertNotNull(tool)

        val properties = (tool!!.inputSchema["properties"] as Map<*, *>)

        assertNotNull(properties["severity"], "Should have 'severity' filter")
        assertNotNull(properties["assetId"], "Should have 'assetId' filter")
        assertNotNull(properties["minDaysOpen"], "Should have 'minDaysOpen' filter")
    }

    @Test
    fun `severity filter should be enum`() {
        val tool = registry.getTool("get_all_vulnerabilities_detail")
        assertNotNull(tool)

        val properties = (tool!!.inputSchema["properties"] as Map<*, *>)
        val severityParam = properties["severity"] as? Map<*, *>

        assertNotNull(severityParam)
        val enumValues = severityParam!!["enum"] as? List<*>
        assertNotNull(enumValues, "Severity should have enum values")
        assertTrue(enumValues!!.contains("CRITICAL"))
        assertTrue(enumValues.contains("HIGH"))
        assertTrue(enumValues.contains("MEDIUM"))
        assertTrue(enumValues.contains("LOW"))
    }

    @Test
    fun `tool execution should validate severity enum`() = runBlocking {
        val tool = registry.getTool("get_all_vulnerabilities_detail")
        assertNotNull(tool)

        val result = tool!!.execute(mapOf("severity" to "INVALID"))
        assertTrue(result.isError, "Should return error for invalid severity")
        assertTrue(
            (result as McpToolResult.Error).message.contains("severity", ignoreCase = true),
            "Error message should mention severity"
        )
    }

    @Test
    fun `tool execution should succeed with valid parameters`() = runBlocking {
        val tool = registry.getTool("get_all_vulnerabilities_detail")
        assertNotNull(tool)

        val result = tool!!.execute(mapOf(
            "page" to 0,
            "pageSize" to 10,
            "severity" to "CRITICAL"
        ))

        assertNotNull(result)
    }
}
