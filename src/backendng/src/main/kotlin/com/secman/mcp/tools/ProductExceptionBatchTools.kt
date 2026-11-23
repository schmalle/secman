package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.ProductExceptionBatchDraft
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.*
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.json.JsonMapper
import jakarta.inject.Singleton
import java.util.UUID

abstract class BatchTool(
    protected val batches: ProductExceptionBatchService,
    override val name: String, override val description: String, override val operation: McpOperation,
    fields: Map<String, Map<String, Any>>
) : McpTool {
    override val inputSchema = mapOf("type" to "object", "properties" to fields, "required" to fields.keys.toList())
    abstract fun run(args: Map<String, Any>, ctx: McpExecutionContext, username: String): Any
    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        val name = context.delegatedUsername ?: return McpToolResult.error("ACCESS_DENIED", "User delegation required")
        return try {
            McpToolResult.success(run(arguments, context, batches.delegatedName(requireNotNull(context.delegatedUserId), name)))
        } catch (error: HttpStatusException) {
            McpToolResult.error(error.status.name, error.message ?: "Request failed")
        } catch (invalid: IllegalArgumentException) {
            McpToolResult.error("INVALID_INPUT", "Check the manifest and identifiers")
        } catch (malformed: java.io.IOException) {
            McpToolResult.error("INVALID_INPUT", "Invalid manifest")
        }
    }
}

@Singleton
class PreviewProductBatchTool(batches: ProductExceptionBatchService, private val assets: AssetFilterService, private val mapper: JsonMapper) :
    BatchTool(batches, "preview_product_exception_batch", "Preview account-scoped exact and wildcard product exceptions. Pass a version 1 manifest with accounts, products (exact and glob arrays), through and reason.", McpOperation.READ, mapOf("manifest" to mapOf(
        "type" to "object", "required" to listOf("version", "accounts", "products", "through", "reason"),
        "properties" to mapOf(
            "version" to mapOf("const" to 1),
            "accounts" to mapOf("type" to "array", "items" to mapOf("type" to "string", "pattern" to "^[0-9]{12}$")),
            "products" to mapOf("type" to "object", "required" to listOf("exact", "glob"),
                "properties" to listOf("exact", "glob").associateWith { mapOf("type" to "array", "items" to mapOf("type" to "string")) }),
            "through" to mapOf("type" to "string", "format" to "date"),
            "reason" to mapOf("type" to "string", "minLength" to 50, "maxLength" to 2048)
        )
    ))) {
    override fun run(args: Map<String, Any>, ctx: McpExecutionContext, username: String): Any {
        val input = mapper.readValue(mapper.writeValueAsBytes(args["manifest"]), ProductExceptionBatchDraft::class.java)
        val ids = assets.getAccessibleAssetIds(ctx.authentication()).filterTo(mutableSetOf(), ctx::canAccessAsset)
        return batches.preview(requireNotNull(input), username, ids)
    }
}

@Singleton
class SubmitProductBatchTool(batches: ProductExceptionBatchService) :
    BatchTool(batches, "submit_product_exception_batch", "Submit the reviewed preview. Use a stable UUID request key for retries.", McpOperation.WRITE,
        mapOf("previewId" to mapOf("type" to "string"), "requestKey" to mapOf("type" to "string", "format" to "uuid"))) {
    override fun run(args: Map<String, Any>, ctx: McpExecutionContext, username: String) =
        batches.submit(BatchSubmission(args["previewId"].toString(), UUID.fromString(args["requestKey"].toString())), username)
}

@Singleton
class GetProductBatchTool(batches: ProductExceptionBatchService) :
    BatchTool(batches, "get_product_exception_batch", "Get a product exception batch and its decision.", McpOperation.READ,
        mapOf("batchId" to mapOf("type" to "string"))) {
    override fun run(args: Map<String, Any>, ctx: McpExecutionContext, username: String) = batches.show(args["batchId"].toString(), username)
}
