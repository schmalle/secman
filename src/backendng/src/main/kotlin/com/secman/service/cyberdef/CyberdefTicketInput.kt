package com.secman.service.cyberdef

import com.secman.domain.CyberdefSeverity
import com.secman.util.EmailAddressValidator
import io.micronaut.core.annotation.Introspected
import io.micronaut.serde.annotation.Serdeable
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeParseException

/** One affected resource as submitted. */
@Serdeable
@Introspected
data class CyberdefResourceInput(
    /** Optional when [identifier] is an ARN: it is then derived from the ARN's service and resource type. */
    val type: String? = null,
    val identifier: String? = null,
    /** Optional when [identifier] is a regional ARN. */
    val region: String? = null
)

/** The finding itself. Sending options live separately in [CyberdefDelivery]. */
@Serdeable
@Introspected
data class CyberdefTicketFields(
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
    val dueAt: String? = null
)

/** How a submission is handled, once per request; it applies to every ticket in a batch. */
@Serdeable
@Introspected
data class CyberdefDelivery(
    /** Validate and resolve everything, store and send nothing. */
    val dryRun: Boolean? = null,
    /** Mail these addresses instead of the account's owners (at most [CyberdefTicketInput.RECIPIENTS_MAX]). */
    val recipients: List<String>? = null,
    /** Create tickets for accounts nobody is allocated to. */
    val allowUnrouted: Boolean? = null
)

/** `POST /api/security-tickets` and `create_cyberdef_ticket`. */
@Serdeable
@Introspected
data class CyberdefTicketCreateRequest(val ticket: CyberdefTicketFields? = null, val delivery: CyberdefDelivery? = null)

/** `POST /api/security-tickets/batch` and `create_cyberdef_tickets`. */
@Serdeable
@Introspected
data class CyberdefBatchCreateRequest(val tickets: List<CyberdefTicketFields>? = null, val delivery: CyberdefDelivery? = null)

/**
 * One problem with one input field. [field] is a path such as `title` or `resources[2].region`
 * (relative to the ticket, or `delivery.recipients[0]`), so a form can show it in place.
 */
@Serdeable
data class CyberdefFieldError(val field: String, val code: String, val message: String) {
    companion object {
        const val REQUIRED = "REQUIRED"
        const val INVALID = "INVALID"
        const val TOO_LONG = "TOO_LONG"
        const val TOO_MANY = "TOO_MANY"
        const val NOT_FUTURE = "NOT_FUTURE"
        const val ACCOUNT_MISMATCH = "ACCOUNT_MISMATCH"
        const val UNKNOWN_FIELD = "UNKNOWN_FIELD"
        const val WRONG_TYPE = "WRONG_TYPE"
        const val NOT_ALLOWED = "NOT_ALLOWED"
    }
}

/** A ticket that passed [CyberdefTicketInput.validate]: trimmed, bounded, normalised. */
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
    val dueAt: Instant?
)

/** Delivery options that passed [CyberdefTicketInput.validateDelivery]. */
data class ValidDelivery(val dryRun: Boolean, val recipients: List<String>, val allowUnrouted: Boolean)

/** Result of a validation: the cleaned value or every problem found. */
sealed class Validated<out T> {
    /** Input passed every check. */
    data class Valid<T>(val value: T) : Validated<T>()

    /** Input failed; [errors] lists each problem, not just the first. */
    data class Invalid(val errors: List<CyberdefFieldError>) : Validated<Nothing>()
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
    const val RECIPIENTS_MAX = 20

    private val ACCOUNT_ID = Regex("^[0-9]{12}$")
    private val CVE = Regex("^CVE-[0-9]{4}-[0-9]{4,7}$")
    private val EXTERNAL_REFERENCE = Regex("^[A-Za-z0-9._:/#-]{1,128}$")
    private val SOURCE_SYSTEM = Regex("^[A-Za-z0-9._ -]{1,64}$")
    private val RESOURCE_TYPE = Regex("^[A-Z0-9_]{1,32}$")
    private val REGION = Regex("^[a-z0-9-]{1,32}$")
    private val MULTILINE_CONTROL = Regex("[\\p{Cntrl}&&[^\\n\\t]]")
    private val ANY_CONTROL = Regex("\\p{Cntrl}")

    /** Keys a ticket object accepts. An unknown key is a typo that would silently drop data. */
    val TICKET_KEYS = setOf(
        "externalReference", "awsAccountId", "title", "severity", "description", "recommendedAction",
        "cves", "resources", "references", "sourceSystem", "dueAt"
    )
    val DELIVERY_KEYS = setOf("dryRun", "recipients", "allowUnrouted")
    val RESOURCE_KEYS = setOf("type", "identifier", "region")

    fun cleanMultiline(value: String): String = value.replace("\r\n", "\n").replace(MULTILINE_CONTROL, "").trim()

    fun cleanSingleLine(value: String): String = value.replace(ANY_CONTROL, " ").trim()

    /** Comment validation shared by transitions and plain comments. Null means "no comment". */
    fun cleanComment(raw: String?): String? {
        val cleaned = raw?.let(::cleanMultiline)?.takeIf { it.isNotEmpty() } ?: return null
        require(cleaned.length <= COMMENT_MAX) { "comment must be at most $COMMENT_MAX characters" }
        return cleaned
    }

    /** Checks and normalises one ticket; [now] is the reference for `dueAt`. */
    fun validate(fields: CyberdefTicketFields, now: Instant = Instant.now()): Validated<ValidTicket> {
        val errors = mutableListOf<CyberdefFieldError>()
        val account = validateAccount(fields.awsAccountId, errors)
        val title = requiredText("title", fields.title?.let(::cleanSingleLine), TITLE_MAX, errors)
        val severity = validateSeverity(fields.severity, errors)
        val description = requiredText("description", fields.description?.let(::cleanMultiline), DESCRIPTION_MAX, errors)
        val action = fields.recommendedAction?.let(::cleanMultiline)?.takeIf { it.isNotEmpty() }
        if (action != null && action.length > ACTION_MAX) {
            errors += CyberdefFieldError("recommendedAction", CyberdefFieldError.TOO_LONG, "at most $ACTION_MAX characters")
        }
        val reference = optionalPattern("externalReference", fields.externalReference, EXTERNAL_REFERENCE,
            "1-128 characters of A-Z a-z 0-9 . _ : / # -", errors)
        val source = optionalPattern("sourceSystem", fields.sourceSystem, SOURCE_SYSTEM,
            "1-64 characters of A-Z a-z 0-9 . _ - and space", errors)
        val cves = validateCves(fields.cves.orEmpty(), errors)
        val resources = validateResources(fields.resources.orEmpty(), account, errors)
        val references = validateReferences(fields.references.orEmpty(), errors)
        val dueAt = parseDueAt(fields.dueAt, now, errors)

        if (errors.isNotEmpty()) return Validated.Invalid(errors)
        return Validated.Valid(
            ValidTicket(
                externalReference = reference, awsAccountId = account!!, title = title, severity = severity!!,
                description = description, recommendedAction = action, cves = cves, resources = resources,
                references = references, sourceSystem = source, dueAt = dueAt
            )
        )
    }

    /** Syntax only; whether SecMan may mail an address is [CyberdefRecipientResolver.resolveOverride]'s call. */
    fun validateDelivery(delivery: CyberdefDelivery?): Validated<ValidDelivery> {
        val errors = mutableListOf<CyberdefFieldError>()
        val raw = delivery?.recipients.orEmpty()
        if (raw.size > RECIPIENTS_MAX) {
            errors += CyberdefFieldError("delivery.recipients", CyberdefFieldError.TOO_MANY, "at most $RECIPIENTS_MAX recipients")
        }
        val addresses = raw.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        addresses.withIndex().filterNot { EmailAddressValidator.isValidRecipient(it.value) }.take(3).forEach { (index, _) ->
            errors += CyberdefFieldError("delivery.recipients[$index]", CyberdefFieldError.INVALID, "not a valid email address")
        }
        if (errors.isNotEmpty()) return Validated.Invalid(errors)
        return Validated.Valid(ValidDelivery(delivery?.dryRun == true, addresses, delivery?.allowUnrouted == true))
    }

    /** The 12-digit account, or null (with an error recorded) when it is missing or malformed. */
    private fun validateAccount(raw: String?, errors: MutableList<CyberdefFieldError>): String? {
        val account = raw?.trim().orEmpty()
        return when {
            account.isEmpty() -> null.also { errors += CyberdefFieldError("awsAccountId", CyberdefFieldError.REQUIRED, "AWS account id is required") }
            !ACCOUNT_ID.matches(account) -> null.also { errors += CyberdefFieldError("awsAccountId", CyberdefFieldError.INVALID, "must be exactly 12 digits") }
            else -> account
        }
    }

    private fun validateSeverity(raw: String?, errors: MutableList<CyberdefFieldError>): CyberdefSeverity? {
        val severity = raw?.trim()?.uppercase()?.let { name -> CyberdefSeverity.entries.firstOrNull { it.name == name } }
        if (severity == null) {
            val code = if (raw.isNullOrBlank()) CyberdefFieldError.REQUIRED else CyberdefFieldError.INVALID
            errors += CyberdefFieldError("severity", code, "must be one of ${CyberdefSeverity.entries.joinToString()}")
        }
        return severity
    }

    private fun requiredText(field: String, cleaned: String?, max: Int, errors: MutableList<CyberdefFieldError>): String {
        val text = cleaned.orEmpty()
        when {
            text.isEmpty() -> errors += CyberdefFieldError(field, CyberdefFieldError.REQUIRED, "$field is required")
            text.length > max -> errors += CyberdefFieldError(field, CyberdefFieldError.TOO_LONG, "at most $max characters")
        }
        return text
    }

    private fun optionalPattern(field: String, raw: String?, pattern: Regex, rule: String, errors: MutableList<CyberdefFieldError>): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!pattern.matches(value)) errors += CyberdefFieldError(field, CyberdefFieldError.INVALID, rule)
        return value
    }

    private fun validateCves(raw: List<String>, errors: MutableList<CyberdefFieldError>): List<String> {
        if (raw.size > CVES_MAX) errors += CyberdefFieldError("cves", CyberdefFieldError.TOO_MANY, "at most $CVES_MAX CVEs")
        val cves = raw.map { it.trim().uppercase() }.distinct()
        cves.withIndex().filterNot { CVE.matches(it.value) }.take(3).forEach { (index, _) ->
            errors += CyberdefFieldError("cves[$index]", CyberdefFieldError.INVALID, "must look like CVE-2026-12345")
        }
        return cves
    }

    private fun validateResources(
        raw: List<CyberdefResourceInput>,
        account: String?,
        errors: MutableList<CyberdefFieldError>
    ): List<CyberdefResourceInput> {
        if (raw.size > RESOURCES_MAX) errors += CyberdefFieldError("resources", CyberdefFieldError.TOO_MANY, "at most $RESOURCES_MAX resources")
        return raw.mapIndexedNotNull { index, resource ->
            val before = errors.size
            val cleaned = validateResource("resources[$index]", resource, account, errors)
            cleaned.takeIf { errors.size == before }
        }
    }

    /**
     * One resource. An ARN identifier fills in region and type when they are missing and
     * must name the ticket's own account, so a ticket cannot point at another tenant.
     */
    private fun validateResource(
        path: String,
        resource: CyberdefResourceInput,
        account: String?,
        errors: MutableList<CyberdefFieldError>
    ): CyberdefResourceInput {
        val identifier = resource.identifier?.let(::cleanSingleLine).orEmpty()
        val arn = AwsArn.parse(identifier)
        val identifierError = when {
            identifier.isEmpty() -> CyberdefFieldError.REQUIRED to "identifier is required"
            identifier.length > IDENTIFIER_MAX -> CyberdefFieldError.TOO_LONG to "at most $IDENTIFIER_MAX characters"
            identifier.startsWith("arn:") && arn == null -> CyberdefFieldError.INVALID to "not a valid ARN"
            arn != null && account != null && arn.account.isNotEmpty() && arn.account != account ->
                CyberdefFieldError.ACCOUNT_MISMATCH to "ARN belongs to account ${arn.account}, not $account"
            else -> null
        }
        identifierError?.let { (code, message) -> errors += CyberdefFieldError("$path.identifier", code, message) }

        val type = resource.type?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: arn?.resourceType()
        when {
            type == null -> errors += CyberdefFieldError("$path.type", CyberdefFieldError.REQUIRED, "type is required unless the identifier is an ARN")
            !RESOURCE_TYPE.matches(type) -> errors += CyberdefFieldError("$path.type", CyberdefFieldError.INVALID, "1-32 characters of A-Z 0-9 _")
        }
        val region = resource.region?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: arn?.region?.takeIf { it.isNotEmpty() }
        if (region != null && !REGION.matches(region)) {
            errors += CyberdefFieldError("$path.region", CyberdefFieldError.INVALID, "must look like eu-central-1")
        }
        return CyberdefResourceInput(type, identifier, region)
    }

    private fun validateReferences(raw: List<String>, errors: MutableList<CyberdefFieldError>): List<String> {
        if (raw.size > REFERENCES_MAX) {
            errors += CyberdefFieldError("references", CyberdefFieldError.TOO_MANY, "at most $REFERENCES_MAX references")
        }
        val references = raw.map { it.trim() }.distinct()
        references.withIndex().filterNot { isSafeHttpsUrl(it.value) }.take(3).forEach { (index, _) ->
            errors += CyberdefFieldError("references[$index]", CyberdefFieldError.INVALID, "must be an absolute https URL without credentials")
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

    private fun parseDueAt(raw: String?, now: Instant, errors: MutableList<CyberdefFieldError>): Instant? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsed = try {
            Instant.parse(text)
        } catch (e: DateTimeParseException) {
            errors += CyberdefFieldError("dueAt", CyberdefFieldError.INVALID, "must be an ISO-8601 instant such as 2026-12-31T00:00:00Z")
            return null
        }
        if (!parsed.isAfter(now)) errors += CyberdefFieldError("dueAt", CyberdefFieldError.NOT_FUTURE, "must be in the future")
        return parsed
    }
}
