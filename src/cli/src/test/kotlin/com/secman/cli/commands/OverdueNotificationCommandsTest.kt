package com.secman.cli.commands

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.secman.cli.service.OverdueNotificationCliService
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID

/** HTTP contract, exit codes and rendering of `show-notified-users` and `notify-users-overdue`. */
class OverdueNotificationCommandsTest {
    private val mapper = jacksonObjectMapper()

    private fun withServer(status: Int, body: String, onRequest: (method: String, query: String?, body: String) -> Unit = { _, _, _ -> },
                           action: (URI) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val token = UUID.randomUUID().toString()
        server.createContext("/api/auth/login") { exchange ->
            exchange.responseHeaders.add("Set-Cookie", "secman_auth=$token; HttpOnly; Path=/")
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        server.createContext("/api/overdue-notifications") { exchange ->
            assertThat(exchange.requestHeaders.getFirst("Authorization")).isEqualTo("Bearer $token")
            onRequest(exchange.requestMethod, exchange.requestURI.rawQuery, exchange.requestBody.readAllBytes().decodeToString())
            exchange.responseHeaders.add("Content-Type", "application/json")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { action(URI("http://127.0.0.1:${server.address.port}")) } finally { server.stop(0) }
    }

    @Test
    fun `overview sends the encoded email and threshold with the bearer token`() {
        var seenQuery: String? = null
        withServer(200, """{"email":"a+b@example.com","status":"NO_PATHS"}""", { _, query, _ -> seenQuery = query }) { url ->
            val report = OverdueNotificationCliService(mapper).overview(url, "admin", "pw", "a+b@example.com", 45)
            assertThat(report.path("status").asText()).isEqualTo("NO_PATHS")
        }
        assertThat(seenQuery).isEqualTo("email=a%2Bb%40example.com&days=45")
    }

    @Test
    fun `send posts the run options as json`() {
        var seenBody = ""
        withServer(200, """{"status":"DRY_RUN"}""", { method, _, body -> assertThat(method).isEqualTo("POST"); seenBody = body }) { url ->
            OverdueNotificationCliService(mapper).send(url, "admin", "pw", 14, true, "user@example.com")
        }
        val json = mapper.readTree(seenBody)
        assertThat(json.path("thresholdDays").asInt()).isEqualTo(14)
        assertThat(json.path("dryRun").asBoolean()).isTrue()
        assertThat(json.path("notificationUser").asText()).isEqualTo("user@example.com")
    }

    @Test
    fun `http errors map to distinct exit codes`() {
        for ((status, exit) in listOf(400 to 2, 401 to 3, 403 to 3, 500 to 4)) {
            withServer(status, """{"error":"Invalid request: days must be between 1 and 3650"}""") { url ->
                val failure = assertThrows(OverdueNotificationCliService.Failure::class.java) {
                    OverdueNotificationCliService(mapper).overview(url, "admin", "pw", "x@example.com", 30)
                }
                assertThat(failure.exitCode).describedAs("HTTP $status").isEqualTo(exit)
                if (status == 400) assertThat(failure.message).contains("days must be between")
            }
        }
    }

    @Test
    fun `each overview status prints its own explanation`() {
        val lines = listOf("USER_NOT_FOUND", "USER_DISABLED", "NO_PATHS", "NO_OVERDUE_ASSETS", "WOULD_BE_NOTIFIED")
            .map { ShowNotifiedUsersCommand.statusLine(mapper.readTree("""{"status":"$it"}""")) }
        assertThat(lines.toSet()).hasSize(5)
        assertThat(lines[0]).contains("No user account")
        assertThat(lines[2]).contains("not a direct member")
    }

    @Test
    fun `overview explains case-sensitive prefix and retains excluded memberships`() {
        val report = mapper.readTree("""{"status":"NO_PATHS","workgroups":[
            {"name":"AWS-Backend","workgroupId":1,"enabled":true,"assignedAssetCount":8,"awsAccountIds":[]}
        ],"assets":[],"totalAssets":0,"totalOverdue":0,"totalCritical":0,"totalHigh":0}""")
        val output = ShowNotifiedUsersCommand.renderOverview(report)
        assertThat(output).contains("lowercase aws-", "other groups are ignored", "AWS-Backend | 1 | yes | 8")
        assertThat(output).contains("Totals: 0 assets, 0 overdue findings")
    }

    @Test
    fun `overview table lists reasons and strips control characters`() {
        val report = mapper.readTree("""
            {"email":"m@example.com","status":"WOULD_BE_NOTIFIED","userId":4,"username":"m","thresholdDays":30,
             "observationWindowStartUtc":"2026-10-23T09:00","observationWindowEndUtc":"2026-10-26T09:00",
             "alreadyNotifiedToday":true,
             "workgroups":[{"workgroupId":9,"name":"aws-DevOps-x","enabled":true,"assignedAssetCount":0,"awsAccountIds":["012345678901"]}],
             "assets":[{"assetId":1,"assetName":"ec2\u001b[31m-a","awsAccountId":"012345678901","overdueCount":2,
               "criticalCount":1,"highCount":1,"mediumCount":0,"lowCount":0,"oldestDays":45,
               "reasons":[{"type":"AWS_WORKGROUP","workgroupId":9,"workgroupName":"aws-DevOps-x","awsAccountId":"012345678901"},
                          {"type":"DIRECT_ASSIGNMENT","workgroupId":3,"workgroupName":"ops"}]}],
             "totalAssets":1,"totalOverdue":2,"totalCritical":1,"totalHigh":1}
        """.trimIndent())

        val table = ShowNotifiedUsersCommand.renderOverview(report)

        assertThat(table).contains("AWS_WORKGROUP:aws-DevOps-x/012345678901; DIRECT_ASSIGNMENT:ops")
        assertThat(table).contains("aws-DevOps-x | 9 | yes | 0 | 012345678901")
        assertThat(table).contains("Already notified today: yes")
        assertThat(table).doesNotContain("\u001b")
    }

    @Test
    fun `run summary and exit code follow the backend status`() {
        val partial = mapper.readTree("""
            {"status":"PARTIAL_FAILURE","dryRun":false,"thresholdDays":30,"recipientsResolved":2,"emailsSent":1,
             "emailsFailed":1,"skipped":0,"recipients":[
               {"email":"a@example.com","outcome":"SENT","assetCount":1,"overdueCount":2,"workgroups":["wg"]},
               {"email":"b@example.com","outcome":"FAILED","assetCount":1,"overdueCount":1,"workgroups":["wg"]}]}
        """.trimIndent())

        assertThat(NotifyUsersOverdueCommand.exitCode(partial)).isEqualTo(1)
        assertThat(NotifyUsersOverdueCommand.renderSummary(partial, verbose = false)).contains("Failed recipients:", "b@example.com")
        assertThat(NotifyUsersOverdueCommand.exitCode(mapper.readTree("""{"status":"DRY_RUN"}"""))).isZero()
        assertThat(NotifyUsersOverdueCommand.exitCode(mapper.readTree("""{"status":"SUCCESS"}"""))).isZero()
        assertThat(NotifyUsersOverdueCommand.renderSummary(mapper.readTree("""{"status":"DRY_RUN","dryRun":true,"recipients":[]}"""), false))
            .contains("DRY-RUN", "(no recipients)")
    }
}
