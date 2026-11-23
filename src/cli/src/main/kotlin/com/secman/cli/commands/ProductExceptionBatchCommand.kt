package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import jakarta.inject.Inject
import picocli.CommandLine.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Callable

@Command(name = "exception batch", description = ["Preview, submit or show a product exception batch."], mixinStandardHelpOptions = true)
class ProductExceptionBatchCommand : Callable<Int> {
    @field:io.micronaut.context.annotation.Value("\${secman.backend.base-url}")
    lateinit var backendUrl: String
    @Inject lateinit var client: CliHttpClient
    @Parameters(index = "0", description = ["preview | submit | show"]) lateinit var action: String
    @Option(names = ["--file"], description = ["Path to a version 1 JSON manifest (preview only)"]) var file: Path? = null
    @Option(names = ["--account"], description = ["12-digit AWS account ID, repeatable"]) var accounts: List<String> = emptyList()
    @Option(names = ["--exact"], description = ["Whole product label, repeatable"]) var exact: List<String> = emptyList()
    @Option(names = ["--glob"], description = ["Whole-label wildcard pattern, repeatable. Quote asterisks in the shell"]) var glob: List<String> = emptyList()
    @Option(names = ["--through"], description = ["Inclusive end date in UTC (YYYY-MM-DD)"]) var through: String? = null
    @Option(names = ["--reason"], description = ["Business justification (50–2048 characters)"]) var reason: String? = null
    @Option(names = ["--preview-id"], description = ["ID returned by preview"]) var previewId: UUID? = null
    @Option(names = ["--request-key"], description = ["Stable UUID for retries of this submission"]) var requestKey: UUID? = null
    @Option(names = ["--id"], description = ["Batch ID from the submission receipt"]) var id: UUID? = null
    @Option(names = ["--output"], description = ["text or json"]) var output: String = "text"

    override fun call(): Int = try {
        execute()
    } catch (invalid: IllegalArgumentException) {
        System.err.println(invalid.message)
        2
    }

    private fun execute(): Int {
        require(output in setOf("text", "json")) { "Use --output text or json" }
        val hasDraft = file != null || accounts.isNotEmpty() || exact.isNotEmpty() || glob.isNotEmpty() || through != null || reason != null
        require(action == "preview" || !hasDraft) { "Draft options are only valid with preview" }
        require(action == "submit" || (previewId == null && requestKey == null)) { "--preview-id and --request-key are only valid with submit" }
        require(action == "show" || id == null) { "--id is only valid with show" }
        val body: Any? = when (action) {
            "preview" -> manifest()
            "submit" -> mapOf("previewId" to requireNotNull(previewId) { "Supply --preview-id" },
                "requestKey" to requireNotNull(requestKey) { "Supply a stable UUID with --request-key" })
            "show" -> { requireNotNull(id) { "Supply --id" }; null }
            else -> throw IllegalArgumentException("Use preview, submit or show")
        }
        val endpoint = "/api/vulnerability-exception-requests/batches"
        val user = requireNotNull(System.getenv("SECMAN_ADMIN_NAME")) { "Set SECMAN_ADMIN_NAME" }
        val password = requireNotNull(System.getenv("SECMAN_ADMIN_PASS")) { "Supply SECMAN_ADMIN_PASS through the credential wrapper" }
        val token = client.authenticate(user, password, backendUrl) ?: return 3
        val (code, result) = if (body == null) {
            client.getMapWithStatus("$endpoint/$id", token)
        } else client.postMapWithStatus(if (action == "preview") "$endpoint/preview" else endpoint, body, token)
        if (code !in 200..299 || result == null) {
            System.err.println("Batch $action failed (HTTP $code): ${result?.get("message") ?: "check the identifier and account access"}")
            return when (code) { 400 -> 2; 401, 403 -> 3; 409 -> 4; else -> 1 }
        }
        val mapper = client.getObjectMapper()
        if (output == "json") println(mapper.writeValueAsString(result))
        else if (action == "preview") {
            val impact = result["impact"] as Map<*, *>
            val draft = impact["draft"] as Map<*, *>
            println("Through ${draft["through"]} UTC · ${impact["expectedStatus"]} on submission")
            println("${impact["combinations"]} combinations · ${impact["findings"]} matched findings · ${impact["additional"]} additional")
            for (row in impact["accounts"] as List<*>) {
                val account = row as Map<*, *>
                println("${account["account"]}: ${account["findings"]} findings, ${account["additional"]} additional, ${account["unresolved"]} unresolved")
            }
            for (row in impact["products"] as List<*>) {
                val product = row as Map<*, *>
                println("${product["mode"]} ${product["selector"]}: ${product["findings"]} findings")
            }
            println("Preview ID: ${result["previewId"]}")
        } else println("${result["status"]} · batch ${result["batchId"]}")
        return 0
    }

    private fun manifest(): Any {
        val source = file ?: return mapOf("version" to 1, "accounts" to accounts,
            "products" to mapOf("exact" to exact, "glob" to glob), "through" to through, "reason" to reason)
        require(accounts.isEmpty() && exact.isEmpty() && glob.isEmpty() && through == null && reason == null) { "Use a file or inline selectors" }
        require(Files.isRegularFile(source) && Files.size(source) <= 1_000_000) { "Supply a JSON file up to 1 MB" }
        return try {
            Files.newInputStream(source).use { client.getObjectMapper().readValue(it, Map::class.java) }
        } catch (error: java.io.IOException) {
            throw IllegalArgumentException("Could not read a JSON object from --file", error)
        }
    }
}
