package com.secman.controller

import com.secman.domain.RequirementExportTemplateStatus
import com.secman.repository.RequirementExportTemplateRepository
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
 */
class RequirementExportTemplateIntegrationTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var templateRepository: RequirementExportTemplateRepository

    @Inject
    lateinit var seeder: RequirementExportTemplateSeeder

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
        assertThat(templateRepository.findById(id).get().status)
            .isEqualTo(RequirementExportTemplateStatus.INACTIVE)

        client.toBlocking().exchange(
            HttpRequest.POST("/api/requirement-export-templates/$id/activate", emptyMap<String, Any>())
                .header("Authorization", authHeader(adminUser)),
            String::class.java
        )
        assertThat(templateRepository.findById(id).get().status)
            .isEqualTo(RequirementExportTemplateStatus.ACTIVE)
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
