package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GithubAppClientServiceTest {

    private val mapper = ObjectMapper()
    private val client = GithubAppClientService(mapper)

    @Test
    fun `parseAlert maps a full GitHub Dependabot alert JSON node`() {
        val json = """
            {
              "number": 3,
              "security_advisory": {
                "severity": "high",
                "ghsa_id": "GHSA-xxxx-yyyy-zzzz",
                "cve_id": "CVE-2024-12345",
                "summary": "Prototype pollution in lodash"
              },
              "security_vulnerability": {
                "vulnerable_version_range": "< 4.17.21",
                "first_patched_version": { "identifier": "4.17.21" }
              },
              "dependency": {
                "package": { "name": "lodash", "ecosystem": "npm" },
                "manifest_path": "package.json"
              },
              "html_url": "https://github.com/org/repo/security/dependabot/3",
              "created_at": "2026-01-01T00:00:00Z",
              "updated_at": "2026-01-02T00:00:00Z"
            }
        """.trimIndent()
        val node = mapper.readTree(json)

        val alert = client.parseAlert(node, "high")

        assertThat(alert.alertNumber).isEqualTo(3)
        assertThat(alert.packageName).isEqualTo("lodash")
        assertThat(alert.ecosystem).isEqualTo("npm")
        assertThat(alert.manifestPath).isEqualTo("package.json")
        assertThat(alert.severity).isEqualTo("high")
        assertThat(alert.ghsaId).isEqualTo("GHSA-xxxx-yyyy-zzzz")
        assertThat(alert.cveId).isEqualTo("CVE-2024-12345")
        assertThat(alert.summary).isEqualTo("Prototype pollution in lodash")
        assertThat(alert.vulnerableVersionRange).isEqualTo("< 4.17.21")
        assertThat(alert.firstPatchedVersion).isEqualTo("4.17.21")
        assertThat(alert.htmlUrl).isEqualTo("https://github.com/org/repo/security/dependabot/3")
        assertThat(alert.alertCreatedAt.toString()).isEqualTo("2026-01-01T00:00:00Z")
        assertThat(alert.alertUpdatedAt.toString()).isEqualTo("2026-01-02T00:00:00Z")
    }

    @Test
    fun `parseAlert tolerates missing optional fields`() {
        val json = """
            {
              "number": 9,
              "security_advisory": { "severity": "critical" },
              "security_vulnerability": {},
              "dependency": { "package": { "name": "left-pad", "ecosystem": "npm" } }
            }
        """.trimIndent()
        val node = mapper.readTree(json)

        val alert = client.parseAlert(node, "critical")

        assertThat(alert.alertNumber).isEqualTo(9)
        assertThat(alert.packageName).isEqualTo("left-pad")
        assertThat(alert.manifestPath).isNull()
        assertThat(alert.ghsaId).isNull()
        assertThat(alert.cveId).isNull()
        assertThat(alert.vulnerableVersionRange).isNull()
        assertThat(alert.firstPatchedVersion).isNull()
        assertThat(alert.alertCreatedAt).isNull()
    }
}
