package com.secman.service

import com.secman.domain.*
import com.secman.repository.*
import io.micronaut.security.authentication.Authentication
import io.mockk.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.Optional

/** Real workbook parsing must enforce the same placement boundary as single-asset creation. */
class AssetImportAuthorizationTest {
    private val assets = mockk<AssetRepository>()
    private val users = mockk<UserRepository>()
    private val groups = mockk<WorkgroupRepository>()
    private val actor = User(id = 1L, username = "importer", email = "importer@test.com", passwordHash = "unused",
        roles = mutableSetOf(User.Role.VULN))
    private val group = Workgroup(id = 2L, name = "team", users = mutableSetOf(actor))
    private val service = AssetImportService(assets, groups, users, AssetCreationService(assets, users, groups))

    /** Keep the parser input representative while varying only security-sensitive columns. */
    private fun importRow(account: String = "", domain: String = "", workgroup: String = "team"): Int {
        every { users.findByUsername(actor.username) } returns Optional.of(actor)
        every { assets.findByName("new-server") } returns Optional.empty()
        every { groups.findByNameIgnoreCase("team") } returns Optional.of(group)
        every { assets.saveAll(any<Iterable<Asset>>()) } answers { firstArg<Iterable<Asset>>().toList() }
        val bytes = XSSFWorkbook().use { book ->
            val sheet = book.createSheet()
            val headings = listOf("Name", "Type", "Owner", "Cloud Account ID", "AD Domain", "Workgroups")
            val values = listOf("new-server", "SERVER", "metadata", account, domain, workgroup)
            headings.forEachIndexed { index, value -> sheet.getRow(0).let { (it ?: sheet.createRow(0)).createCell(index).setCellValue(value) } }
            values.forEachIndexed { index, value -> sheet.getRow(1).let { (it ?: sheet.createRow(1)).createCell(index).setCellValue(value) } }
            ByteArrayOutputStream().use { output -> book.write(output); output.toByteArray() }
        }
        return service.importFromExcel(bytes.inputStream(), Authentication.build(actor.username, listOf("VULN"))).imported
    }

    @Test fun `VULN can import into an enabled direct workgroup`() { assertEquals(1, importRow()) }

    @Test fun `VULN cannot inject account or domain associations`() {
        assertEquals(0, importRow(account = "123456789012"))
        assertEquals(0, importRow(domain = "private.test"))
        verify(exactly = 0) { assets.saveAll(any<Iterable<Asset>>()) }
    }

    @Test fun `VULN cannot place imports outside enabled direct membership`() {
        assertEquals(0, importRow(workgroup = ""))
        group.users.clear()
        assertEquals(0, importRow())
        group.users.add(actor)
        group.enabled = false
        assertEquals(0, importRow())
        verify(exactly = 0) { assets.saveAll(any<Iterable<Asset>>()) }
    }

    @Test fun `grant manager may import account associations without placement`() {
        actor.roles = mutableSetOf(User.Role.SECCHAMPION)
        assertEquals(1, importRow(account = "123456789012", workgroup = ""))
    }
}
