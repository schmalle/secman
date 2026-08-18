package com.secman.controller

import com.secman.repository.UserRepository
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
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream

/**
 * Requirement XLSX import: header tolerance, and the visibility of rows that do not import.
 *
 * Both behaviours here come from one real incident. A customer workbook spelled the mandatory
 * column `ShortReq`; the importer compared headers case-insensitively but **not**
 * whitespace-insensitively against `Short req`, so it matched nothing. The resulting
 * `IllegalArgumentException` then fell into the controller's generic `catch (e: Exception)` and
 * surfaced as HTTP 500 "An internal error occurred" — the one message naming the actual problem was
 * computed and discarded.
 *
 * The second half is worse because it is silent: rows that fail to parse or save were only ever a
 * `log.warn`, and the response carried a processed count and nothing else. An admin whose 169-row
 * spreadsheet produced N requirements had no way to tell a deliberate skip from a lost row. These
 * tests pin the counts *and* the per-row reasons, because the count alone is what left people
 * guessing.
 *
 * The workbooks are generated here rather than checked in: the point is the header/row rules, and a
 * fixture file would hide them behind a binary.
 */
@MicronautTest(environments = ["test"], transactional = false)
class ImportDiagnosticTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    private lateinit var adminUser: String

    @BeforeEach
    fun setup() {
        adminUser = "import-admin-${System.nanoTime()}"
        userRepository.save(TestDataFactory.createAdminUser(adminUser, "$adminUser@secman.test"))
    }

    private fun authHeader() = "Bearer ${TestAuthHelper.getAuthToken(client, adminUser)}"

    /**
     * Builds a `Reqs` sheet from raw cell values; the first list is the header row verbatim, so a
     * test can spell a header any way a real workbook might.
     */
    private fun workbook(vararg rows: List<String>): ByteArray {
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Reqs")
            rows.forEachIndexed { r, values ->
                val row = sheet.createRow(r)
                values.forEachIndexed { c, value -> row.createCell(c).setCellValue(value) }
            }
            ByteArrayOutputStream().use { out ->
                wb.write(out)
                return out.toByteArray()
            }
        }
    }

    private fun upload(bytes: ByteArray): Map<*, *> {
        val response = client.toBlocking().exchange(
            HttpRequest.POST(
                "/api/import/upload-xlsx",
                MultipartBody.builder().addPart("xlsxFile", "reqs.xlsx", MediaType.of(XLSX), bytes).build()
            ).contentType(MediaType.MULTIPART_FORM_DATA).header("Authorization", authHeader()),
            Map::class.java
        )
        assertThat(response.status).isEqualTo(HttpStatus.OK)
        return response.body()!!
    }

    /** Header spellings a real workbook uses interchangeably; all name the same column. */
    private fun headerRow(shortReqSpelling: String) =
        listOf("Chapter", "Norm", shortReqSpelling, "DetailsEN", "MotivationEN", "ExampleEN", "UseCase")

    private fun dataRow(shortreq: String) =
        listOf("A.1", "ISO 27001: A.1", shortreq, "details", "motivation", "example", "General")

    @Test
    fun `a header spelled without the space still imports`() {
        // The regression: "ShortReq" vs "Short req" rejected the entire file.
        for (spelling in listOf("Short req", "ShortReq", "SHORTREQ", "Short Req", " short req ")) {
            val body = upload(workbook(headerRow(spelling), dataRow("req via '$spelling'")))

            assertThat(body["requirementsProcessed"])
                .describedAs("header spelled '%s' must import", spelling)
                .isEqualTo(1)
            assertThat(body["rowsSkipped"]).isEqualTo(0)
        }
    }

    @Test
    fun `rows without a short req are reported with their spreadsheet row numbers`() {
        val body = upload(
            workbook(
                headerRow("ShortReq"),
                dataRow("first requirement"),           // sheet row 2
                listOf("A.2", "ISO 27001: A.2", "", "d", "m", "e", "General"),  // row 3 — section header
                dataRow("second requirement"),          // row 4
                listOf("A.3", "ISO 27001: A.3", "", "d", "m", "e", "General")   // row 5 — section header
            )
        )

        assertThat(body["requirementsProcessed"]).isEqualTo(2)
        assertThat(body["rowsSkipped"])
            .describedAs("a silent partial import is the defect; the count must be reported")
            .isEqualTo(2)
        @Suppress("UNCHECKED_CAST")
        val reasons = body["skipReasons"] as List<String>
        assertThat(reasons).hasSize(2)
        // Row numbers are what make the report actionable — they must match what Excel shows.
        assertThat(reasons[0]).contains("Row 3")
        assertThat(reasons[1]).contains("Row 5")
        assertThat(reasons.joinToString()).contains("Short req")
    }

    @Test
    fun `an entirely empty row is padding, not a skipped row`() {
        // Trailing rows carrying only cell formatting are common in hand-maintained workbooks.
        // Reporting them would bury the real skips.
        val body = upload(
            workbook(
                headerRow("ShortReq"),
                dataRow("only requirement"),
                listOf("", "", "", "", "", "", "")
            )
        )

        assertThat(body["requirementsProcessed"]).isEqualTo(1)
        assertThat(body["rowsSkipped"]).isEqualTo(0)
    }

    @Test
    fun `a genuinely missing header is a 400 naming the column, not a generic 500`() {
        val noShortReq = listOf("Chapter", "Norm", "DetailsEN", "MotivationEN", "ExampleEN", "UseCase")

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.POST(
                    "/api/import/upload-xlsx",
                    MultipartBody.builder()
                        .addPart("xlsxFile", "reqs.xlsx", MediaType.of(XLSX), workbook(noShortReq))
                        .build()
                ).contentType(MediaType.MULTIPART_FORM_DATA).header("Authorization", authHeader()),
                Map::class.java
            )
        }

        assertThat(exception.status)
            .describedAs("a malformed upload is the caller's to fix, so it must not read as a server fault")
            .isEqualTo(HttpStatus.BAD_REQUEST)
        val error = exception.response.getBody(Map::class.java).get()["error"] as String
        assertThat(error).contains("Short req")
        assertThat(error)
            .describedAs("the message must name the problem instead of hiding it")
            .doesNotContain("An internal error occurred")
    }

    @Test
    fun `a regular user cannot import requirements`() {
        val user = "import-user-${System.nanoTime()}"
        userRepository.save(TestDataFactory.createRegularUser(user, "$user@secman.test"))

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.POST(
                    "/api/import/upload-xlsx",
                    MultipartBody.builder()
                        .addPart("xlsxFile", "reqs.xlsx", MediaType.of(XLSX), workbook(headerRow("ShortReq"), dataRow("x")))
                        .build()
                ).contentType(MediaType.MULTIPART_FORM_DATA)
                    .header("Authorization", "Bearer ${TestAuthHelper.getAuthToken(client, user)}"),
                Map::class.java
            )
        }

        assertThat(exception.status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    companion object {
        private const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
