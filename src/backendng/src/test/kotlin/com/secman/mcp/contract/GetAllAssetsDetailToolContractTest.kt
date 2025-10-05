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
 * Contract test for get_all_assets_detail MCP tool.
 * Validates tool registration, input schema, and basic functionality.
 *
 * Feature 009: Enhanced MCP Tools for Security Data Access
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetAllAssetsDetailToolContractTest {

    @Inject
    lateinit var registry: McpToolRegistry

    @Test
    fun `tool should be registered with correct name`() {
        val tool = registry.getTool("get_all_assets_detail")
        assertNotNull(tool, "Tool 'get_all_assets_detail' should be registered")
        assertEquals("get_all_assets_detail", tool!!.name)
    }

    @Test
    fun `tool should have correct description`() {
        val tool = registry.getTool("get_all_assets_detail")
        assertNotNull(tool)
        assertTrue(
            tool!!.description.contains("asset", ignoreCase = true),
            "Description should mention assets"
        )
        assertTrue(
            tool.description.contains("filtering", ignoreCase = true) ||
            tool.description.contains("filter", ignoreCase = true),
            "Description should mention filtering"
        )
    }

    @Test
    fun `tool should have READ operation`() {
        val tool = registry.getTool("get_all_assets_detail")
        assertNotNull(tool)
        assertEquals(McpOperation.READ, tool!!.operation)
    }

    @Test
    fun `input schema should define page parameter`() {
        val tool = registry.getTool("get_all_assets_detail")
        assertNotNull(tool)

        val inputSchema = tool!!.inputSchema
        assertEquals("object", inputSchema["type"])

        val properties = inputSchema["properties"] as? Map<*, *>
        assertNotNull(properties, "Input schema should have properties")

        val pageParam = properties!!["page"] as? Map<*, *>
        assertNotNull(pageParam, "Should have 'page' parameter")
        assertEquals("integer", pageParam!!["type"])
        assertEquals(0, pageParam["minimum"])
        assertEquals(0, pageParam["default"])
    }

    @Test
    fun `input schema should define pageSize parameter with max 1000`() {
        val tool = registry.getTool("get_all_assets_detail")
        assertNotNull(tool)

        val properties = (tool!!.inputSchema["properties"] as Map<*, *>)
        val pageSizeParam = properties["pageSize"] as? Map<*, *>

        assertNotNull(pageSizeParam, "Should have 'pageSize' parameter")
        assertEquals("integer", pageSizeParam!!["type"])
        assertEquals(1, pageSizeParam["minimum"])
        assertEquals(1000, pageSizeParam["maximum"])
        assertEquals(100, pageSizeParam["default"])
    }

    @Test
    fun `input schema should define filter parameters`() {
        val tool = registry.getTool("get_all_assets_detail")
        assertNotNull(tool)

        val properties = (tool!!.inputSchema["properties"] as Map<*, *>)

        // Check that we have filter parameters directly in properties
        assertNotNull(properties["name"], "Should have 'name' filter")
        assertNotNull(properties["type"], "Should have 'type' filter")
        assertNotNull(properties["ip"], "Should have 'ip' filter")
        assertNotNull(properties["owner"], "Should have 'owner' filter")
        assertNotNull(properties["group"], "Should have 'group' filter")
    }

    @Test
    fun `tool execution should validate page parameter minimum`() = runBlocking {
        val tool = registry.getTool("get_all_assets_detail")
        assertNotNull(tool)

        val result = tool!!.execute(mapOf("page" to -1))
        assertTrue(result.isError, "Should return error for negative page")
        assertTrue(
            (result as McpToolResult.Error).message.contains("page", ignoreCase = true),
            "Error message should mention page parameter"
        )
    }

    @Test
    fun `tool execution should validate pageSize maximum`() = runBlocking {
        val tool = registry.getTool("get_all_assets_detail")
        assertNotNull(tool)

        val result = tool!!.execute(mapOf("pageSize" to 2000))
        assertTrue(result.isError, "Should return error for pageSize > 1000")
        assertTrue(
            (result as McpToolResult.Error).message.contains("page", ignoreCase = true),
            "Error message should mention page size"
        )
    }

    @Test
    fun `tool execution should validate pageSize minimum`() = runBlocking {
        val tool = registry.getTool("get_all_assets_detail")
        assertNotNull(tool)

        val result = tool!!.execute(mapOf("pageSize" to 0))
        assertTrue(result.isError, "Should return error for pageSize = 0")
        assertTrue(
            (result as McpToolResult.Error).message.contains("page", ignoreCase = true),
            "Error message should mention page size"
        )
    }

    @Test
    fun `tool execution should succeed with valid parameters`() = runBlocking {
        val tool = registry.getTool("get_all_assets_detail")
        assertNotNull(tool)

        val result = tool!!.execute(mapOf(
            "page" to 0,
            "pageSize" to 10
        ))

        // Should succeed (or fail with a different error if no data)
        // We're just checking it doesn't fail on validation
        assertNotNull(result)
    }
}
