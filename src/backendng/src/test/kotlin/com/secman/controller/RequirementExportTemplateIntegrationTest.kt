package com.secman.controller

import com.secman.domain.RequirementExportScope
import com.secman.domain.RequirementExportTemplateMode
import com.secman.domain.RequirementExportTemplateStatus
import com.secman.domain.RequirementExportTemplateUsage
import com.secman.repository.RequirementExportTemplateRepository
import com.secman.repository.RequirementExportTemplateUsageRepository
import com.secman.repository.UserRepository
import com.secman.service.ExampleRequirementExportTemplateBuilder
import com.secman.service.RequirementExportTemplateSeeder
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestAuthHelper
import com.secman.testutil.TestDataFactory
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.client.multipart.MultipartBody
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream

/**
 * Integration coverage for the company Word template lifecycle.
 *
 * The two things worth proving here, because neither is reachable from a unit test:
 *  - the seeder actually installs the shipped example at startup, which is what makes a fresh
 *    installation export in a company design instead of the built-in layout, and
 *  - the ADMIN/REQADMIN gate on every write verb is enforced at the controller, not just hidden
 *    in the UI.
 *
 * `transactional = false` because every assertion here drives the controller over HTTP, on its own
 * connection. Under the default wrapping transaction, a row this test writes stays uncommitted for
 * the length of the method — and inserting a usage row takes an InnoDB **shared lock on the parent
 * template row** for the FK check, so the controller's DELETE blocks on it until the lock wait
 * times out. Same trap documented in `ExceptedFlagSqlAgreementIntegrationTest`.
 */
@MicronautTest(environments = ["test"], transactional = false)
class RequirementExportTemplateIntegrationTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var templateRepository: RequirementExportTemplateRepository

    @Inject
    lateinit var usageRepository: RequirementExportTemplateUsageRepository

    @Inject
    lateinit var seeder: RequirementExportTemplateSeeder

    /**
     * Re-read persisted state after an HTTP call.
     *
     * Reads the database, not a cached entity: with no ambient transaction each repository call
     * opens its own session, so nothing survives from an earlier read to mask the controller's
     * write. Kept as a named helper so the intent stays explicit at the call sites — a read that
     * silently returned a stale instance would look exactly like a broken endpoint.
     */
    private fun <T> rereadFromDb(block: () -> T): T = block()

    private lateinit var adminUser: String
    private lateinit var regularUser: String

    @BeforeEach
    fun setup() {
        val nonce = System.nanoTime()
        adminUser = "tpl-admin-$nonce"
        regularUser = "tpl-user-$nonce"

        userRepository.save(TestDataFactory.createAdminUser(adminUser, "$adminUser@secman.test"))
        userRepository.save(TestDataFactory.createRegularUser(regularUser, "$regularUser@secman.test"))
    }

    private fun authHeader(username: String) =
        "Bearer ${TestAuthHelper.getAuthToken(client, username)}"

    // ---------------------------------------------------------------- seeding

    @Test
    fun `the shipped example template is installed and active after startup`() {
        val seeded = templateRepository.findByStatusOrderByCreatedAtDesc(RequirementExportTemplateStatus.ACTIVE)

        assertThat(seeded)
            .describedAs("the seeder must install the example so templateMode=LATEST resolves")
            .anyMatch { it.name == ExampleRequirementExportTemplateBuilder.TEMPLATE_NAME }
        assertThat(seeded.first { it.name == ExampleRequirementExportTemplateBuilder.TEMPLATE_NAME }.uploadedBy)
            .isEqualTo(RequirementExportTemplateSeeder.SEEDED_BY)
    }

    @Test
    fun `seeding again is a no-op`() {
        val before = templateRepository.count()

        seeder.seed()
        seeder.seed()

        assertThat(templateRepository.count())
            .describedAs("re-seeding must never duplicate or resurrect a template")
            .isEqualTo(before)
    }

    // ------------------------------------------------------------------- RBAC

    @Test
    fun `a regular user cannot upload a template`() {
        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.POST("/api/requirement-export-templates", uploadBody(validTemplate()))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .header("Authorization", authHeader(regularUser)),
                String::class.java
            )
        }

        assertThat(exception.status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `a regular user cannot download the example template`() {
        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/requirement-export-templates/example")
                    .header("Authorization", authHeader(regularUser)),
                ByteArray::class.java
            )
        }

        assertThat(exception.status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `a regular user cannot activate, deactivate or delete a template`() {
        val id = uploadAsAdmin()

        // Looped rather than parameterised: junit-jupiter-params is not on the classpath.
        val forbidden = listOf(
            HttpRequest.POST("/api/requirement-export-templates/$id/activate", emptyMap<String, Any>()),
            HttpRequest.POST("/api/requirement-export-templates/$id/deactivate", emptyMap<String, Any>()),
            HttpRequest.DELETE<Any>("/api/requirement-export-templates/$id")
        )

        for (request in forbidden) {
            val exception = assertThrows<HttpClientResponseException> {
                client.toBlocking().exchange(
                    request.header("Authorization", authHeader(regularUser)),
                    String::class.java
                )
            }
            assertThat(exception.status)
                .describedAs("${request.method} ${request.uri} must be role-gated")
                .isEqualTo(HttpStatus.FORBIDDEN)
        }
    }

    // -------------------------------------------------------------- lifecycle

    @Test
    fun `an admin can download the shipped example template`() {
        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/requirement-export-templates/example")
                .header("Authorization", authHeader(adminUser)),
            ByteArray::class.java
        )

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        val body = response.body()!!
        // A .docx is a ZIP: the local file header magic is the cheapest proof we got a real one.
        assertThat(body.size).isGreaterThan(0)
        assertThat(body[0]).isEqualTo('P'.code.toByte())
        assertThat(body[1]).isEqualTo('K'.code.toByte())
    }

    @Test
    fun `an uploaded template can be deactivated and activated again`() {
        val id = uploadAsAdmin()

        client.toBlocking().exchange(
            HttpRequest.POST("/api/requirement-export-templates/$id/deactivate", emptyMap<String, Any>())
                .header("Authorization", authHeader(adminUser)),
            String::class.java
        )
        assertThat(rereadFromDb { templateRepository.findById(id).get().status })
            .isEqualTo(RequirementExportTemplateStatus.INACTIVE)

        client.toBlocking().exchange(
            HttpRequest.POST("/api/requirement-export-templates/$id/activate", emptyMap<String, Any>())
                .header("Authorization", authHeader(adminUser)),
            String::class.java
        )
        assertThat(rereadFromDb { templateRepository.findById(id).get().status })
            .isEqualTo(RequirementExportTemplateStatus.ACTIVE)
    }

    @Test
    fun `an unused template is deleted outright`() {
        val id = uploadAsAdmin()

        val response = client.toBlocking().exchange(
            HttpRequest.DELETE<Any>("/api/requirement-export-templates/$id")
                .header("Authorization", authHeader(adminUser)),
            String::class.java
        )

        assertThat(response.status).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(rereadFromDb { templateRepository.findById(id) }).isEmpty()
    }

    /**
     * Regression: delete used to degrade to "retire" for any template with an export behind it, and
     * because the usage count only ever grows, a retired template could never be removed — a second
     * Delete simply re-ran the same branch. The audit row has to outlive the template, so the fix
     * detaches rather than cascades.
     */
    @Test
    fun `a template that has been used is deleted and its usage history survives detached`() {
        val id = uploadAsAdmin()
        val digest = "b".repeat(64)
        usageRepository.save(
            RequirementExportTemplateUsage(
                template = templateRepository.findById(id).get(),
                templateSha256 = digest,
                exportedBy = adminUser,
                exportScope = RequirementExportScope.RELEASE,
                templateMode = RequirementExportTemplateMode.SAVED
            )
        )
        assertThat(usageRepository.countByTemplateId(id)).isEqualTo(1)

        val response = client.toBlocking().exchange(
            HttpRequest.DELETE<Any>("/api/requirement-export-templates/$id")
                .header("Authorization", authHeader(adminUser)),
            String::class.java
        )

        assertThat(response.status)
            .describedAs("a used template must be deleted, not silently retired")
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(rereadFromDb { templateRepository.findById(id) })
            .describedAs("the row must actually be gone from the list")
            .isEmpty()

        val history = rereadFromDb { usageRepository.findAll().filter { it.templateSha256 == digest } }
        assertThat(history)
            .describedAs("who exported what must remain auditable after the template is deleted")
            .hasSize(1)
        assertThat(history.first().template).isNull()
        assertThat(history.first().exportedBy).isEqualTo(adminUser)
    }

    /**
     * Regression: the endpoint handed the usage entities straight to the serializer. Their
     * `template` is a LAZY `@ManyToOne`, and serialization runs after the session is gone, so every
     * call answered 500 "could not initialize proxy … no session". It was easy to miss because the
     * export itself succeeded and `lastUsedAt` still advanced — only the audit history looked empty.
     */
    @Test
    fun `the usage history of a used template is readable`() {
        val id = uploadAsAdmin()
        val digest = "c".repeat(64)
        usageRepository.save(
            RequirementExportTemplateUsage(
                template = templateRepository.findById(id).get(),
                templateSha256 = digest,
                exportedBy = adminUser,
                exportScope = RequirementExportScope.RELEASE,
                templateMode = RequirementExportTemplateMode.SAVED
            )
        )

        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/requirement-export-templates/$id/usage")
                .header("Authorization", authHeader(adminUser)),
            String::class.java
        )

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        val body = response.body()!!
        assertThat(body).contains("\"templateId\":$id")
        assertThat(body).contains("\"exportedBy\":\"$adminUser\"")
        assertThat(body).contains(digest)
        assertThat(body)
            .describedAs("the template's .docx bytes must never be inlined into a usage response")
            .doesNotContain("\"template\":")
    }

    @Test
    fun `deleting an unknown template is a 404`() {
        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.DELETE<Any>("/api/requirement-export-templates/9999999")
                    .header("Authorization", authHeader(adminUser)),
                String::class.java
            )
        }

        assertThat(exception.status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a macro-enabled template is rejected on upload`() {
        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.POST("/api/requirement-export-templates", uploadBody(validTemplate(), "payload.docm"))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .header("Authorization", authHeader(adminUser)),
                String::class.java
            )
        }

        assertThat(exception.status).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `a template without an insertion point is rejected unless append mode is chosen`() {
        val noPlaceholder = templateDocx("Cover page only, no marker")

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.POST("/api/requirement-export-templates", uploadBody(noPlaceholder))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .header("Authorization", authHeader(adminUser)),
                String::class.java
            )
        }
        assertThat(exception.status).isEqualTo(HttpStatus.BAD_REQUEST)

        val accepted = client.toBlocking().exchange(
            HttpRequest.POST(
                "/api/requirement-export-templates",
                MultipartBody.builder()
                    .addPart("templateFile", "append-mode.docx", MediaType.of(DOCX), noPlaceholder)
                    .addPart("name", "Append mode template")
                    .addPart("description", "")
                    .addPart("versionLabel", "")
                    .addPart("activate", "false")
                    .addPart("requireRequirementsPlaceholder", "false")
                    .build()
            )
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .header("Authorization", authHeader(adminUser)),
            String::class.java
        )
        assertThat(accepted.status).isEqualTo(HttpStatus.CREATED)
    }

    // ---------------------------------------------------------------- helpers

    private fun uploadAsAdmin(): Long {
        val response = client.toBlocking().exchange(
            HttpRequest.POST("/api/requirement-export-templates", uploadBody(validTemplate()))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .header("Authorization", authHeader(adminUser)),
            Map::class.java
        )
        assertThat(response.status).isEqualTo(HttpStatus.CREATED)
        return (response.body()!!["id"] as Number).toLong()
    }

    private fun uploadBody(bytes: ByteArray, filename: String = "company-template.docx") =
        MultipartBody.builder()
            .addPart("templateFile", filename, MediaType.of(DOCX), bytes)
            .addPart("name", "Integration test template")
            .addPart("description", "")
            .addPart("versionLabel", "1.0")
            .addPart("activate", "true")
            .addPart("requireRequirementsPlaceholder", "true")
            .build()

    private fun validTemplate(): ByteArray =
        templateDocx("Cover: \${documentTitle}", "Release: \${releaseVersion}", "\${requirements}", "Appendix")

    private fun templateDocx(vararg lines: String): ByteArray {
        XWPFDocument().use { document ->
            lines.forEach { document.createParagraph().createRun().setText(it) }
            ByteArrayOutputStream().use { out ->
                document.write(out)
                return out.toByteArray()
            }
        }
    }

    companion object {
        private const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
