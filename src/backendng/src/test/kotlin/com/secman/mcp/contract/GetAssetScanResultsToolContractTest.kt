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
 * Contract test for get_asset_scan_results MCP tool.
 * Validates tool registration, input schema, and basic functionality.
 *
 * Feature 009: Enhanced MCP Tools for Security Data Access
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetAssetScanResultsToolContractTest {

    @Inject
    lateinit var registry: McpToolRegistry

    @Test
    fun `tool should be registered with correct name`() {
        val tool = registry.getTool("get_asset_scan_results")
        assertNotNull(tool, "Tool 'get_asset_scan_results' should be registered")
        assertEquals("get_asset_scan_results", tool!!.name)
    }

    @Test
    fun `tool should have READ operation`() {
        val tool = registry.getTool("get_asset_scan_results")
        assertNotNull(tool)
        assertEquals(McpOperation.READ, tool!!.operation)
    }

    @Test
    fun `input schema should define pagination parameters`() {
        val tool = registry.getTool("get_asset_scan_results")
        assertNotNull(tool)

        val properties = (tool!!.inputSchema["properties"] as Map<*, *>)

        val pageParam = properties["page"] as? Map<*, *>
        assertNotNull(pageParam, "Should have 'page' parameter")
        assertEquals("integer", pageParam!!["type"])

        val pageSizeParam = properties["pageSize"] as? Map<*, *>
        assertNotNull(pageSizeParam, "Should have 'pageSize' parameter")
        assertEquals("integer", pageSizeParam!!["type"])
        assertEquals(1000, pageSizeParam["maximum"])
    }

    @Test
    fun `input schema should define scan result filters`() {
        val tool = registry.getTool("get_asset_scan_results")
        assertNotNull(tool)

        val properties = (tool!!.inputSchema["properties"] as Map<*, *>)

        assertNotNull(properties["portMin"], "Should have 'portMin' filter")
        assertNotNull(properties["portMax"], "Should have 'portMax' filter")
        assertNotNull(properties["service"], "Should have 'service' filter")
        assertNotNull(properties["state"], "Should have 'state' filter")
    }

    @Test
    fun `port filter should have valid range constraints`() {
        val tool = registry.getTool("get_asset_scan_results")
        assertNotNull(tool)

        val properties = (tool!!.inputSchema["properties"] as Map<*, *>)

        val portMinParam = properties["portMin"] as? Map<*, *>
        assertNotNull(portMinParam)
        assertEquals(1, portMinParam!!["minimum"])
        assertEquals(65535, portMinParam["maximum"])

        val portMaxParam = properties["portMax"] as? Map<*, *>
        assertNotNull(portMaxParam)
        assertEquals(1, portMaxParam!!["minimum"])
        assertEquals(65535, portMaxParam["maximum"])
    }

    @Test
    fun `state filter should be enum`() {
        val tool = registry.getTool("get_asset_scan_results")
        assertNotNull(tool)

        val properties = (tool!!.inputSchema["properties"] as Map<*, *>)
        val stateParam = properties["state"] as? Map<*, *>

        assertNotNull(stateParam)
        val enumValues = stateParam!!["enum"] as? List<*>
        assertNotNull(enumValues, "State should have enum values")
        assertTrue(enumValues!!.contains("open"))
        assertTrue(enumValues.contains("filtered"))
        assertTrue(enumValues.contains("closed"))
    }

    @Test
    fun `tool execution should validate port minimum`() = runBlocking {
        val tool = registry.getTool("get_asset_scan_results")
        assertNotNull(tool)

        val result = tool!!.execute(mapOf("portMin" to 0))
        assertTrue(result.isError, "Should return error for port < 1")
        assertTrue(
            (result as McpToolResult.Error).message.contains("port", ignoreCase = true),
            "Error message should mention port"
        )
    }

    @Test
    fun `tool execution should validate port range`() = runBlocking {
        val tool = registry.getTool("get_asset_scan_results")
        assertNotNull(tool)

        val result = tool!!.execute(mapOf("portMin" to 70000))
        assertTrue(result.isError, "Should return error for port > 65535")
        assertTrue(
            (result as McpToolResult.Error).message.contains("port", ignoreCase = true),
            "Error message should mention port"
        )
    }

    @Test
    fun `tool execution should succeed with valid parameters`() = runBlocking {
        val tool = registry.getTool("get_asset_scan_results")
        assertNotNull(tool)

        val result = tool!!.execute(mapOf(
            "page" to 0,
            "pageSize" to 10,
            "portMin" to 80,
            "portMax" to 443
        ))

        assertNotNull(result)
    }
}
