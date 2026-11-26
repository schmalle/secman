package com.secman.service.cyberdef

import com.secman.domain.CyberdefSeverity
import io.micronaut.core.annotation.Introspected
import io.micronaut.serde.annotation.Serdeable
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeParseException

/** One affected resource as submitted. */
@Serdeable
@Introspected
data class CyberdefResourceInput(
    val type: String? = null,
    val identifier: String? = null,
    val region: String? = null
)

/** Submission payload, shared by `POST /api/security-tickets` and the MCP create tools. */
@Serdeable
@Introspected
data class CyberdefTicketCreateRequest(
    val externalReference: String? = null,
    val awsAccountId: String? = null,
    val title: String? = null,
    val severity: String? = null,
    val description: String? = null,
    val recommendedAction: String? = null,
    val cves: List<String>? = null,
    val resources: List<CyberdefResourceInput>? = null,
    val references: List<String>? = null,
    val sourceSystem: String? = null,
    val dueAt: String? = null,
    val allowUnrouted: Boolean? = null
)

/** A submission that passed [CyberdefTicketInput.validate]: trimmed, bounded, normalised. */
data class ValidTicket(
    val externalReference: String?,
    val awsAccountId: String,
    val title: String,
    val severity: CyberdefSeverity,
    val description: String,
    val recommendedAction: String?,
    val cves: List<String>,
    val resources: List<CyberdefResourceInput>,
    val references: List<String>,
    val sourceSystem: String?,
    val dueAt: Instant?,
    val allowUnrouted: Boolean
)

/** Result of [CyberdefTicketInput.validate]: the cleaned ticket or every problem found. */
sealed class ValidationOutcome {
/** Input passed every check. */
    data class Valid(val ticket: ValidTicket) : ValidationOutcome()
/** Input failed; [errors] lists each problem, not just the first. */
    data class Invalid(val errors: List<String>) : ValidationOutcome()
}

/**
 * Validation for ticket input. Every field is bounded; free text keeps newlines and tabs
 * but loses every other control character, single-line fields lose newlines too, so no
 * value can forge a log line or a mail header later.
 */
object CyberdefTicketInput {
    const val TITLE_MAX = 200
    const val DESCRIPTION_MAX = 20_000
    const val ACTION_MAX = 10_000
    const val COMMENT_MAX = 4_000
    const val CVES_MAX = 100
    const val RESOURCES_MAX = 500
    const val REFERENCES_MAX = 20
    const val URL_MAX = 2_048
    const val IDENTIFIER_MAX = 512
    const val BATCH_MAX = 100

    private val ACCOUNT_ID = Regex("^[0-9]{12}$")
    private val CVE = Regex("^CVE-[0-9]{4}-[0-9]{4,7}$")
    private val EXTERNAL_REFERENCE = Regex("^[A-Za-z0-9._:/#-]{1,128}$")
    private val SOURCE_SYSTEM = Regex("^[A-Za-z0-9._ -]{1,64}$")
    private val RESOURCE_TYPE = Regex("^[A-Z0-9_]{1,32}$")
    private val REGION = Regex("^[a-z0-9-]{1,32}$")
    private val MULTILINE_CONTROL = Regex("[\\p{Cntrl}&&[^\\n\\t]]")
    private val ANY_CONTROL = Regex("\\p{Cntrl}")

    /** Keys the MCP tool accepts. An unknown key is a typo that would silently drop data. */
    val ALLOWED_KEYS = setOf(
        "externalReference", "awsAccountId", "title", "severity", "description", "recommendedAction",
        "cves", "resources", "references", "sourceSystem", "dueAt", "allowUnrouted"
    )
    private val RESOURCE_KEYS = setOf("type", "identifier", "region")

    fun cleanMultiline(value: String): String = value.replace("\r\n", "\n").replace(MULTILINE_CONTROL, "").trim()

    fun cleanSingleLine(value: String): String = value.replace(ANY_CONTROL, " ").trim()

    /** Comment validation shared by transitions and plain comments. Null means "no comment". */
    fun cleanComment(raw: String?): String? {
        val cleaned = raw?.let(::cleanMultiline)?.takeIf { it.isNotEmpty() } ?: return null
        require(cleaned.length <= COMMENT_MAX) { "comment must be at most $COMMENT_MAX characters" }
        return cleaned
    }

/** Checks and normalises [request]; [now] is the reference for `dueAt`. */
    fun validate(request: CyberdefTicketCreateRequest, now: Instant = Instant.now()): ValidationOutcome {
        val errors = mutableListOf<String>()

        val account = request.awsAccountId?.trim().orEmpty()
        if (!ACCOUNT_ID.matches(account)) errors += "awsAccountId must be exactly 12 digits"

        val title = request.title?.let(::cleanSingleLine).orEmpty()
        if (title.isEmpty() || title.length > TITLE_MAX) errors += "title is required and at most $TITLE_MAX characters"

        val severity = request.severity?.trim()?.uppercase()?.let { raw ->
            CyberdefSeverity.entries.firstOrNull { it.name == raw }
        }
        if (severity == null) errors += "severity must be one of ${CyberdefSeverity.entries.joinToString()}"

        val description = request.description?.let(::cleanMultiline).orEmpty()
        if (description.isEmpty() || description.length > DESCRIPTION_MAX) {
            errors += "description is required and at most $DESCRIPTION_MAX characters"
        }

        val action = request.recommendedAction?.let(::cleanMultiline)?.takeIf { it.isNotEmpty() }
        if (action != null && action.length > ACTION_MAX) errors += "recommendedAction must be at most $ACTION_MAX characters"

        val reference = request.externalReference?.trim()?.takeIf { it.isNotEmpty() }
        if (reference != null && !EXTERNAL_REFERENCE.matches(reference)) {
            errors += "externalReference must be 1-128 characters of A-Z a-z 0-9 . _ : / # -"
        }

        val source = request.sourceSystem?.trim()?.takeIf { it.isNotEmpty() }
        if (source != null && !SOURCE_SYSTEM.matches(source)) errors += "sourceSystem must be 1-64 characters of A-Z a-z 0-9 . _ - and space"

        val cves = validateCves(request.cves.orEmpty(), errors)
        val resources = validateResources(request.resources.orEmpty(), errors)
        val references = validateReferences(request.references.orEmpty(), errors)
        val dueAt = parseDueAt(request.dueAt, now, errors)

        if (errors.isNotEmpty()) return ValidationOutcome.Invalid(errors)
        return ValidationOutcome.Valid(
            ValidTicket(
                externalReference = reference, awsAccountId = account, title = title, severity = severity!!,
                description = description, recommendedAction = action, cves = cves, resources = resources,
                references = references, sourceSystem = source, dueAt = dueAt,
                allowUnrouted = request.allowUnrouted == true
            )
        )
    }

    private fun validateCves(raw: List<String>, errors: MutableList<String>): List<String> {
        if (raw.size > CVES_MAX) errors += "at most $CVES_MAX cves"
        val cves = raw.map { it.trim().uppercase() }.distinct()
        cves.filterNot { CVE.matches(it) }.take(3).forEach { errors += "invalid CVE id '${cleanSingleLine(it).take(40)}'" }
        return cves
    }

    private fun validateResources(raw: List<CyberdefResourceInput>, errors: MutableList<String>): List<CyberdefResourceInput> {
        if (raw.size > RESOURCES_MAX) errors += "at most $RESOURCES_MAX resources"
        return raw.mapIndexedNotNull { index, resource ->
            val type = resource.type?.trim()?.uppercase().orEmpty()
            val identifier = resource.identifier?.let(::cleanSingleLine).orEmpty()
            val region = resource.region?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            val problems = buildList {
                if (!RESOURCE_TYPE.matches(type)) add("type must be 1-32 characters of A-Z 0-9 _")
                if (identifier.isEmpty() || identifier.length > IDENTIFIER_MAX) add("identifier is required and at most $IDENTIFIER_MAX characters")
                if (region != null && !REGION.matches(region)) add("region must look like eu-central-1")
            }
            if (problems.isNotEmpty()) {
                errors += "resources[$index]: ${problems.joinToString("; ")}"
                null
            } else CyberdefResourceInput(type, identifier, region)
        }
    }

    private fun validateReferences(raw: List<String>, errors: MutableList<String>): List<String> {
        if (raw.size > REFERENCES_MAX) errors += "at most $REFERENCES_MAX references"
        val references = raw.map { it.trim() }.distinct()
        references.withIndex().filterNot { isSafeHttpsUrl(it.value) }.take(3).forEach { (index, _) ->
            errors += "references[$index] must be an absolute https URL without credentials"
        }
        return references
    }

    /** Display-only links: https, a host, no embedded credentials, no whitespace. Never fetched. */
    fun isSafeHttpsUrl(value: String): Boolean {
        if (value.isEmpty() || value.length > URL_MAX || value.any { it.isWhitespace() || it.isISOControl() }) return false
        val uri = try {
            URI(value)
        } catch (e: java.net.URISyntaxException) {
            return false
        }
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }

    private fun parseDueAt(raw: String?, now: Instant, errors: MutableList<String>): Instant? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsed = try {
            Instant.parse(text)
        } catch (e: DateTimeParseException) {
            errors += "dueAt must be an ISO-8601 instant such as 2026-12-31T00:00:00Z"
            return null
        }
        if (!parsed.isAfter(now)) errors += "dueAt must be in the future"
        return parsed
    }

    /**
     * Converts the loosely typed MCP argument map into a request. Unknown keys and wrongly
     * typed values are reported, not ignored.
     */
    fun fromArguments(arguments: Map<String, Any?>): Pair<CyberdefTicketCreateRequest?, List<String>> {
        val errors = mutableListOf<String>()
        val unknown = arguments.keys - ALLOWED_KEYS
        if (unknown.isNotEmpty()) errors += "unknown field(s): ${unknown.sorted().joinToString { cleanSingleLine(it).take(40) }}"

        fun text(key: String): String? = when (val value = arguments[key]) {
            null -> null
            is String -> value
            is Number -> value.toString()
            else -> { errors += "$key must be a string"; null }
        }
        fun stringList(key: String): List<String>? = when (val value = arguments[key]) {
            null -> null
            is List<*> -> value.map { item -> item as? String ?: run { errors += "$key must contain strings only"; "" } }
            else -> { errors += "$key must be an array of strings"; null }
        }
        val resources = when (val value = arguments["resources"]) {
            null -> null
            is List<*> -> value.mapIndexed { index, item -> resourceFromMap(index, item, errors) }
            else -> { errors += "resources must be an array of objects"; null }
        }
        val allowUnrouted = when (val value = arguments["allowUnrouted"]) {
            null -> null
            is Boolean -> value
            else -> { errors += "allowUnrouted must be a boolean"; null }
        }
        if (errors.isNotEmpty()) return null to errors
        return CyberdefTicketCreateRequest(
            externalReference = text("externalReference"), awsAccountId = text("awsAccountId"),
            title = text("title"), severity = text("severity"), description = text("description"),
            recommendedAction = text("recommendedAction"), cves = stringList("cves"), resources = resources,
            references = stringList("references"), sourceSystem = text("sourceSystem"), dueAt = text("dueAt"),
            allowUnrouted = allowUnrouted
        ) to errors
    }

    private fun resourceFromMap(index: Int, item: Any?, errors: MutableList<String>): CyberdefResourceInput {
        val map = item as? Map<*, *> ?: run {
            errors += "resources[$index] must be an object"
            return CyberdefResourceInput()
        }
        val unknown = map.keys.map { it.toString() }.toSet() - RESOURCE_KEYS
        if (unknown.isNotEmpty()) errors += "resources[$index]: unknown field(s) ${unknown.sorted().joinToString { cleanSingleLine(it).take(40) }}"
        return CyberdefResourceInput(map["type"] as? String, map["identifier"] as? String, map["region"] as? String)
    }
}
