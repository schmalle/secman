package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * Request DTO for POST /api/github/dependabot/import.
 *
 * One batch per GitHub repository. The CLI groups the Dependabot alerts it
 * pulls (via the GitHub REST API) by repository and posts a list of these
 * batches. The backend find-or-creates a REPOSITORY-type asset per batch and
 * applies the transactional delete-insert replace pattern (same as the
 * CrowdStrike import), so a re-import reflects remediated alerts by their
 * absence.
 *
 * @property repositoryFullName "owner/repo" — used as Asset.name (unique identifier)
 * @property repositoryUrl       html_url of the repository — stored on Asset.uri
 * @property alerts              The repository's open HIGH/CRITICAL Dependabot alerts
 */
@Serdeable
data class GitHubDependabotBatchDto(
    @field:NotBlank
    @field:Size(min = 1, max = 255)
    val repositoryFullName: String,

    @field:Size(max = 2048)
    val repositoryUrl: String?,

    @field:NotNull
    @field:Valid
    @field:Size(max = 50000)
    val alerts: List<DependabotAlertDto>
)

/**
 * A single Dependabot alert as forwarded by the CLI.
 *
 * @property ghsaId                 GitHub Security Advisory ID (always present)
 * @property cveId                  CVE identifier when GitHub has mapped one (optional)
 * @property severity               CRITICAL | HIGH | MEDIUM | LOW (advisory severity)
 * @property ecosystem              Package ecosystem (npm, maven, pip, …)
 * @property packageName            Vulnerable package name
 * @property vulnerableVersionRange Affected version range (e.g. "< 1.2.3")
 * @property createdAt              ISO-8601 timestamp the alert was created on GitHub
 */
@Serdeable
data class DependabotAlertDto(
    @field:NotBlank
    @field:Size(max = 255)
    val ghsaId: String,

    @field:Size(max = 255)
    val cveId: String?,

    @field:NotBlank
    @field:Size(max = 50)
    val severity: String,

    @field:Size(max = 100)
    val ecosystem: String?,

    @field:Size(max = 255)
    val packageName: String?,

    @field:Size(max = 255)
    val vulnerableVersionRange: String?,

    @field:Size(max = 64)
    val createdAt: String?
)
