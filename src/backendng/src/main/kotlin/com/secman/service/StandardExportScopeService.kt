package com.secman.service

import com.secman.domain.Release
import com.secman.domain.Requirement
import com.secman.domain.Standard
import com.secman.repository.ReleaseRepository
import com.secman.repository.RequirementRepository
import com.secman.repository.StandardRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Resolves the `standard` / `standardId` / `release` / `releaseId` query parameters of the
 * public requirement export endpoints into a concrete (standard, release) pair, and selects
 * the requirements that pair covers.
 *
 * A *standard* has no direct link to requirements: `Standard.useCases` is joined to
 * `Requirement.usecases` through the use case tags, so "the IT/OT Security standard" means
 * "every requirement tagged with at least one of that standard's use cases" — the same rule
 * [com.secman.controller.StandardController.getStandardRequirements] applies.
 *
 * A *release* is a frozen copy of the corpus in `requirement_snapshot`, so a release-scoped
 * standard export filters the snapshots (via [ReleaseRequirementScopeService]) rather than
 * the live rows, and historical downloads stay reproducible.
 *
 * Every lookup here is a bound-parameter query. Neither the standard name nor the release
 * version is ever concatenated into a query, and both reach the response only through
 * [exportFilename], which strips them to `[A-Za-z0-9_-]` before they touch a
 * `Content-Disposition` header.
 */
@Singleton
open class StandardExportScopeService(
    private val standardRepository: StandardRepository,
    private val releaseRepository: ReleaseRepository,
    private val requirementRepository: RequirementRepository,
    private val releaseRequirementScopeService: ReleaseRequirementScopeService
) {
    private val log = LoggerFactory.getLogger(StandardExportScopeService::class.java)

    /**
     * Outcome of resolving the four scope parameters.
     *
     * [Invalid] maps to 400 and [Missing] to 404; [Resolved] carries nulls when the caller
     * asked for neither a standard nor a release, which is the pre-existing "export
     * everything, live" behaviour.
     */
    sealed interface Resolution {
        data class Resolved(val standard: Standard?, val release: Release?) : Resolution
        data class Invalid(val message: String) : Resolution
        data class Missing(val message: String) : Resolution
    }

    /**
     * @param standardId numeric standard id; wins when both id and name are supplied
     * @param standardName exact standard name, case-insensitive (e.g. `IT/OT Security`)
     * @param releaseId numeric release id (the parameter the UI has always sent)
     * @param releaseRef `latest` for the ACTIVE release, or an exact release version string
     */
    open fun resolve(
        standardId: Long?,
        standardName: String?,
        releaseId: Long?,
        releaseRef: String?
    ): Resolution {
        val standard = when {
            standardId != null -> standardRepository.findByIdWithUseCases(standardId).firstOrNull()
                ?: return notFound("Standard not found", "id=$standardId")
            !standardName.isNullOrBlank() -> {
                val name = standardName.trim()
                // Bound before the query runs: a name longer than the column can hold cannot
                // match anything, and keeps an oversized string out of the log line below.
                if (name.length > MAX_STANDARD_NAME_LENGTH) {
                    return notFound("Standard not found", "name too long (${name.length})")
                }
                standardRepository.findByNameIgnoreCaseWithUseCases(name).firstOrNull()
                    ?: return notFound("Standard not found", "name=${forLog(name)}")
            }
            else -> null
        }

        val byId = if (releaseId != null) {
            releaseRepository.findById(releaseId).orElse(null)
                ?: return notFound("Release not found", "id=$releaseId")
        } else {
            null
        }

        val ref = releaseRef?.trim()
        val byRef = if (!ref.isNullOrEmpty()) {
            if (ref.equals(LATEST, ignoreCase = true)) {
                // "Latest released version" is the release currently in force. Exactly one
                // release is ACTIVE at a time; with none, this fails rather than silently
                // serving the live, unfrozen corpus under a name that promises a release.
                releaseRequirementScopeService.findActiveRelease()
                    ?: return notFound("No active release", "release=latest")
            } else {
                if (ref.length > MAX_RELEASE_VERSION_LENGTH) {
                    return notFound("Release not found", "version too long (${ref.length})")
                }
                releaseRepository.findByVersion(ref).orElse(null)
                    ?: return notFound("Release not found", "version=${forLog(ref)}")
            }
        } else {
            null
        }

        if (byId != null && byRef != null && byId.id != byRef.id) {
            return Resolution.Invalid("releaseId and release identify different releases")
        }

        return Resolution.Resolved(standard, byRef ?: byId)
    }

    /**
     * The requirements covered by [standard], frozen to [release] when one was selected.
     *
     * Two selection modes, and the distinction between them is the whole point:
     *
     *  - [Standard.allRequirements] set — the standard covers the entire corpus. Use cases are
     *    ignored, including requirements that carry none. This is opt-in and deliberate; it is
     *    the only way an export legitimately returns everything.
     *  - otherwise — the union over the standard's use cases. A standard with no use cases covers
     *    nothing and returns empty rather than falling back to "everything", so a *misconfigured*
     *    standard still cannot silently publish the full corpus.
     */
    open fun requirementsFor(standard: Standard, release: Release?): List<Requirement> {
        val releaseId = release?.id

        if (standard.allRequirements) {
            log.debug("Standard '{}' covers all requirements; use cases ignored", forLog(standard.name))
            return if (releaseId != null) {
                // The release's frozen content, not the live corpus — a release export has to stay
                // reproducible after requirements change.
                releaseRequirementScopeService.allRequirementsForRelease(releaseId)
            } else {
                requirementRepository.findAll().sortedWith(REQUIREMENT_ORDER)
            }
        }

        val useCaseIds = standard.useCases.mapNotNull { it.id }
        if (useCaseIds.isEmpty()) {
            log.debug("Standard '{}' has no use cases; export is empty", forLog(standard.name))
            return emptyList()
        }

        return if (releaseId != null) {
            releaseRequirementScopeService.requirementsForRelease(releaseId, useCaseIds)
        } else {
            useCaseIds
                .flatMap { requirementRepository.findByUsecaseId(it) }
                .distinctBy { it.id }
                .sortedWith(REQUIREMENT_ORDER)
        }
    }

    /** Document title, e.g. `IT/OT Security - Release 98.739714.0`. */
    open fun exportTitle(standard: Standard, release: Release?): String =
        if (release != null) "${standard.name} - Release ${release.version}" else standard.name

    /**
     * Download filename. Both interpolated parts are stripped to `[A-Za-z0-9_-]`, so a
     * standard named `IT/OT Security` cannot inject a quote, CR or LF into
     * `Content-Disposition`.
     */
    open fun exportFilename(standard: Standard, release: Release?, extension: String): String {
        val date = LocalDateTime.now().format(FILENAME_DATE)
        val versionPart = release?.let { "_v${safeFilenamePart(it.version)}" } ?: ""
        return "requirements_${safeFilenamePart(standard.name)}${versionPart}_$date.$extension".take(200)
    }

    private fun notFound(message: String, detail: String): Resolution.Missing {
        // A05: the client gets the generic message, the detail stays in the server log.
        log.debug("Public export scope unresolved: {} ({})", message, detail)
        return Resolution.Missing(message)
    }

    /**
     * Reduce a name or version to characters that are safe in a quoted `Content-Disposition`
     * filename. Dots survive so a version reads as `v98.739714.0` rather than `v987397140`,
     * but a run of them is collapsed and a leading one dropped, so no stripped input can come
     * back out looking like a relative path.
     */
    private fun safeFilenamePart(value: String): String =
        value.replace(" ", "_")
            .replace(UNSAFE_FILENAME_CHARACTERS, "")
            .replace(REPEATED_DOTS, ".")
            .trimStart('.')
            .take(60)
            .ifEmpty { "standard" }

    /** A09: strip CR/LF and friends so an unresolved lookup cannot forge a log line. */
    private fun forLog(value: String): String =
        value.replace(CONTROL_CHARACTERS, "").take(120)

    companion object {
        /** Keyword accepted by the `release` parameter for "the ACTIVE release". */
        const val LATEST = "latest"

        /** `standard.name` is a plain `varchar(255)`; longer input cannot match a row. */
        const val MAX_STANDARD_NAME_LENGTH = 255

        /** `releases.version` is `varchar(50)`. */
        const val MAX_RELEASE_VERSION_LENGTH = 50

        private val UNSAFE_FILENAME_CHARACTERS = Regex("[^A-Za-z0-9._-]")

        private val REPEATED_DOTS = Regex("\\.{2,}")

        /**
         * Java's `\p{Cntrl}` is ASCII-only without `UNICODE_CHARACTER_CLASS`, so NEL (U+0085)
         * and the Unicode line/paragraph separators survive it — and they are line breaks to
         * a log reader. Same set as [com.secman.controller.RequirementController].
         */
        private val CONTROL_CHARACTERS = Regex("[\\p{Cntrl}\\u0085\\u2028\\u2029]")

        private val FILENAME_DATE = DateTimeFormatter.ofPattern("yyyyMMdd")

        private val REQUIREMENT_ORDER =
            compareBy<Requirement>({ it.chapter ?: "" }, { it.id ?: 0 })
    }
}
