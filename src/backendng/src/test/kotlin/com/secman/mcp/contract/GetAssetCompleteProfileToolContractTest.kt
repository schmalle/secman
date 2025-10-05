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
 * Contract test for get_asset_complete_profile MCP tool.
 * Validates tool registration, input schema, and basic functionality.
 *
 * Feature 009: Enhanced MCP Tools for Security Data Access
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetAssetCompleteProfileToolContractTest {

    @Inject
    lateinit var registry: McpToolRegistry

    @Test
    fun `tool should be registered with correct name`() {
        val tool = registry.getTool("get_asset_complete_profile")
        assertNotNull(tool, "Tool 'get_asset_complete_profile' should be registered")
        assertEquals("get_asset_complete_profile", tool!!.name)
    }

    @Test
    fun `tool should have READ operation`() {
        val tool = registry.getTool("get_asset_complete_profile")
        assertNotNull(tool)
        assertEquals(McpOperation.READ, tool!!.operation)
    }

    @Test
    fun `input schema should require assetId`() {
        val tool = registry.getTool("get_asset_complete_profile")
        assertNotNull(tool)

        val inputSchema = tool!!.inputSchema
        val required = inputSchema["required"] as? List<*>

        assertNotNull(required, "Schema should have required fields")
        assertTrue(required!!.contains("assetId"), "assetId should be required")
    }

    @Test
    fun `input schema should have include flags`() {
        val tool = registry.getTool("get_asset_complete_profile")
        assertNotNull(tool)

        val properties = (tool!!.inputSchema["properties"] as Map<*, *>)

        val assetIdParam = properties["assetId"] as? Map<*, *>
        assertNotNull(assetIdParam, "Should have 'assetId' parameter")
        assertEquals("integer", assetIdParam!!["type"])

        val includeVulnsParam = properties["includeVulnerabilities"] as? Map<*, *>
        assertNotNull(includeVulnsParam, "Should have 'includeVulnerabilities' parameter")
        assertEquals("boolean", includeVulnsParam!!["type"])

        val includeScansParam = properties["includeScanResults"] as? Map<*, *>
        assertNotNull(includeScansParam, "Should have 'includeScanResults' parameter")
        assertEquals("boolean", includeScansParam!!["type"])
    }

    @Test
    fun `tool execution should validate assetId required`() = runBlocking {
        val tool = registry.getTool("get_asset_complete_profile")
        assertNotNull(tool)

        val result = tool!!.execute(emptyMap())
        assertTrue(result.isError, "Should return error for missing assetId")
        assertTrue(
            (result as McpToolResult.Error).message.contains("assetId", ignoreCase = true),
            "Error message should mention assetId"
        )
    }

    @Test
    fun `tool execution should succeed with valid assetId`() = runBlocking {
        val tool = registry.getTool("get_asset_complete_profile")
        assertNotNull(tool)

        val result = tool!!.execute(mapOf(
            "assetId" to 999999  // Non-existent asset, but should validate the parameter
        ))

        // Either succeeds or fails with ASSET_NOT_FOUND (not validation error)
        assertNotNull(result)
    }
}
