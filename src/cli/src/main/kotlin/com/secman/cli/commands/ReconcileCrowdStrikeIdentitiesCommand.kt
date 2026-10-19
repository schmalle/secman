package com.secman.cli.commands

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.cli.service.CliHttpClient
import com.secman.cli.service.CrowdStrikeIdentityRepairSession
import com.secman.cli.service.RepairRequestProblem
import com.secman.cli.service.RepairRequestUnknown
import io.micronaut.http.client.DefaultHttpClientConfiguration
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.client.netty.DefaultHttpClient
import jakarta.inject.Singleton
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Spec
import picocli.CommandLine.Model.CommandSpec
import java.time.Duration
import java.util.concurrent.Callable

@Singleton
@Command(name = "reconcile-crowdstrike-identities", mixinStandardHelpOptions = true,
    description = ["Discover, preview or repair unverified legacy multi-AID assets using fresh Falcon evidence (ADMIN)"])
class ReconcileCrowdStrikeIdentitiesCommand(private val mapper: ObjectMapper) : Callable<Int> {
    @Option(names = ["--asset-id"]) var assetId: Long = 0
    @Option(names = ["--expected-aids"], split = ",", description = ["All current AIDs bound to the source asset (2..20)"])
    var aids: Set<String> = emptySet()
    @Option(names = ["--all-candidates"], description = ["Discover and process every eligible legacy asset; omit --asset-id/--expected-aids"])
    var allCandidates: Boolean = false
    @Option(names = ["--after-id"], description = ["Resume discovery after this asset ID (default: 0; all-candidates only)"])
    var afterId: Long = 0
    @Option(names = ["--batch-size"], description = ["Candidate discovery page size, 1..100 (default: 50; all-candidates only)"])
    var batchSize: Int = 50
    @Option(names = ["--request-status"], description = ["Look up a durable request outcome by UUID; performs no repair"])
    var requestStatus: String? = null
    @Option(names = ["--reason"], description = ["Audit reason, 10..255 characters; required for previews/repairs"])
    var reason: String = ""
    @Option(names = ["--apply"], description = ["Commit the repair; default is a read-only preview"])
    var apply: Boolean = false
    @Option(names = ["--backend-url"], description = ["Backend origin; defaults to SECMAN_HOST or SECMAN_BACKEND_URL"])
    var backendUrl: String? = null
    @Spec lateinit var spec: CommandSpec

    override fun call(): Int = try {
        if (requestStatus != null) {
            require(!allCandidates && !apply && assetId == 0L && aids.isEmpty() && afterId == 0L && batchSize == 50 && reason.isBlank()) {
                "--request-status cannot be combined with repair or discovery options"
            }
            require(requestStatus!!.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) { "Request status requires a UUID" }
        } else {
            require(if (allCandidates) assetId == 0L && aids.isEmpty() else
                assetId > 0 && aids.size in 2..20 && aids.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }) {
                "Choose --all-candidates OR a positive --asset-id with 2..20 valid --expected-aids"
            }
            require(afterId >= 0 && batchSize in 1..100 && (allCandidates || (afterId == 0L && batchSize == 50))) { "Invalid discovery pagination" }
            require(reason.trim().length in 10..255 && reason.none(Char::isISOControl)) { "Supply an audit reason of 10..255 characters" }
        }
        val origin = UserAccessOverviewCommand.validateBackendUrl(backendUrl ?: System.getenv("SECMAN_HOST")
            ?: System.getenv("SECMAN_BACKEND_URL") ?: throw IllegalArgumentException("Set --backend-url or SECMAN_HOST"))
        val username = credential("SECMAN_ADMIN_NAME")
        val password = credential("SECMAN_ADMIN_PASS")
        val report = submit(origin, username, password)
        spec.commandLine().out.println(if (allCandidates) mapper.writeValueAsString(report) else
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report))
        if (requestStatus != null) {
            if (report.path("state").asText() in setOf("PREVIEWED", "REPAIRED") || report.path("errorCode").asText() == "ALREADY_RESOLVED") 0 else 1
        } else if (allCandidates && (!report.path("complete").asBoolean() || report.path("unresolved").asLong() > 0)) 1 else 0
    } catch (e: RepairRequestProblem) {
        spec.commandLine().err.println(mapper.writeValueAsString(mapOf("requestId" to e.requestId, "errorCode" to e.errorCode, "aid" to e.aid, "message" to e.message)))
        1
    } catch (e: RepairRequestUnknown) {
        spec.commandLine().err.println(mapper.writeValueAsString(mapOf("requestId" to e.requestId, "errorCode" to "OUTCOME_UNCONFIRMED", "message" to e.message)))
        1
    } catch (e: IllegalArgumentException) {
        spec.commandLine().err.println("Error: ${e.message}")
        2
    } catch (e: HttpClientResponseException) {
        spec.commandLine().err.println("Request rejected (HTTP ${e.status.code}); check authorization and the request ID.")
        1
    } catch (e: Exception) {
        spec.commandLine().err.println("Could not confirm the repair result. Check backend audit records before retrying.")
        1
    }

    internal fun submit(origin: java.net.URI, username: String, password: String): com.fasterxml.jackson.databind.JsonNode {
        val config = DefaultHttpClientConfiguration().apply {
            setConnectTimeout(Duration.ofSeconds(30))
            setReadTimeout(Duration.ofMinutes(10))
            setFollowRedirects(false)
        }
        @Suppress("DEPRECATION")
        return DefaultHttpClient(origin, config).use { client ->
            val token = CliHttpClient(client, mapper).authenticate(username, password, origin.toString())
                ?: throw IllegalStateException("Authentication failed")
            val session = CrowdStrikeIdentityRepairSession(client.toBlocking(), mapper, token, apply, reason)
            if (requestStatus != null) session.requestStatus(requireNotNull(requestStatus))
            else if (allCandidates) session.allCandidates(afterId, batchSize) { event ->
                spec.commandLine().out.println(mapper.writeValueAsString(event))
                spec.commandLine().out.flush()
            } else session.repair(assetId, aids) { id ->
                if (::spec.isInitialized) {
                    spec.commandLine().err.println(mapper.writeValueAsString(mapOf("type" to "request", "assetId" to assetId, "requestId" to id, "status" to "STARTED")))
                    spec.commandLine().err.flush()
                }
            }
        }
    }

    private fun credential(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() && !it.startsWith("pass://") }
        ?: throw IllegalArgumentException("$name must be supplied through the secret provider")
}
