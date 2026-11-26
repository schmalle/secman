package com.secman.service.cyberdef

/**
 * Turns loosely typed MCP arguments into the same request objects REST binds, reporting
 * every unknown key and wrongly typed value as a [CyberdefFieldError] instead of dropping it.
 */
object CyberdefTicketArguments {

    /** `{ticket: {...}, delivery: {...}}` for `create_cyberdef_ticket`. */
    fun single(arguments: Map<String, Any?>): Pair<CyberdefTicketCreateRequest?, List<CyberdefFieldError>> {
        val errors = mutableListOf<CyberdefFieldError>()
        unknownKeys(arguments, setOf("ticket", "delivery"), "", errors)
        val ticket = (arguments["ticket"] as? Map<*, *>)?.let { ticket(it, "ticket", errors) }
        if (ticket == null) errors += CyberdefFieldError("ticket", CyberdefFieldError.REQUIRED, "ticket must be an object")
        val delivery = delivery(arguments["delivery"], errors)
        return (if (errors.isEmpty()) CyberdefTicketCreateRequest(ticket, delivery) else null) to errors
    }

    /** `{tickets: [...], delivery: {...}}` for `create_cyberdef_tickets`. */
    fun batch(arguments: Map<String, Any?>): Pair<CyberdefBatchCreateRequest?, List<CyberdefFieldError>> {
        val errors = mutableListOf<CyberdefFieldError>()
        unknownKeys(arguments, setOf("tickets", "delivery"), "", errors)
        val items = arguments["tickets"] as? List<*>
        val tickets = items?.mapIndexed { index, item ->
            (item as? Map<*, *>)?.let { ticket(it, "tickets[$index]", errors) }
                ?: CyberdefTicketFields().also { errors += CyberdefFieldError("tickets[$index]", CyberdefFieldError.WRONG_TYPE, "must be an object") }
        }
        if (tickets == null) errors += CyberdefFieldError("tickets", CyberdefFieldError.REQUIRED, "tickets must be an array")
        val delivery = delivery(arguments["delivery"], errors)
        return (if (errors.isEmpty()) CyberdefBatchCreateRequest(tickets, delivery) else null) to errors
    }

    private fun ticket(map: Map<*, *>, path: String, errors: MutableList<CyberdefFieldError>): CyberdefTicketFields {
        val reader = Reader(map, path, errors)
        unknownKeys(map, CyberdefTicketInput.TICKET_KEYS, path, errors)
        return CyberdefTicketFields(
            externalReference = reader.text("externalReference"), awsAccountId = reader.text("awsAccountId"),
            title = reader.text("title"), severity = reader.text("severity"), description = reader.text("description"),
            recommendedAction = reader.text("recommendedAction"), cves = reader.strings("cves"),
            resources = reader.resources("resources"), references = reader.strings("references"),
            sourceSystem = reader.text("sourceSystem"), dueAt = reader.text("dueAt")
        )
    }

    private fun delivery(value: Any?, errors: MutableList<CyberdefFieldError>): CyberdefDelivery? {
        if (value == null) return null
        val map = value as? Map<*, *> ?: return null.also {
            errors += CyberdefFieldError("delivery", CyberdefFieldError.WRONG_TYPE, "must be an object")
        }
        unknownKeys(map, CyberdefTicketInput.DELIVERY_KEYS, "delivery", errors)
        val reader = Reader(map, "delivery", errors)
        return CyberdefDelivery(reader.flag("dryRun"), reader.strings("recipients"), reader.flag("allowUnrouted"))
    }

    private fun unknownKeys(map: Map<*, *>, allowed: Set<String>, path: String, errors: MutableList<CyberdefFieldError>) {
        (map.keys.map { it.toString() }.toSet() - allowed).sorted().take(5).forEach { key ->
            val shown = CyberdefTicketInput.cleanSingleLine(key).take(40)
            errors += CyberdefFieldError(join(path, shown), CyberdefFieldError.UNKNOWN_FIELD, "unknown field")
        }
    }

    private fun join(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"

    /** Typed reads from one JSON object, recording a WRONG_TYPE error per bad value. */
    private class Reader(val map: Map<*, *>, val path: String, val errors: MutableList<CyberdefFieldError>) {
        private fun wrong(key: String, expected: String) {
            errors += CyberdefFieldError(join(path, key), CyberdefFieldError.WRONG_TYPE, "must be $expected")
        }

        /** A string; numbers are accepted and turned into text (account ids often arrive as numbers). */
        fun text(key: String): String? = when (val value = map[key]) {
            null -> null
            is String -> value
            is Number -> value.toString()
            else -> null.also { wrong(key, "a string") }
        }

        /** A JSON boolean; "true" as a string is a type error, not a yes. */
        fun flag(key: String): Boolean? = when (val value = map[key]) {
            null -> null
            is Boolean -> value
            else -> null.also { wrong(key, "a boolean") }
        }

        /** An array whose every element is a string. */
        fun strings(key: String): List<String>? = when (val value = map[key]) {
            null -> null
            is List<*> -> if (value.all { it is String }) value.map { it as String } else null.also { wrong(key, "an array of strings") }
            else -> null.also { wrong(key, "an array of strings") }
        }

        /** An array of resource objects, each checked for unknown keys. */
        fun resources(key: String): List<CyberdefResourceInput>? {
            val value = map[key] ?: return null
            val items = value as? List<*> ?: return null.also { wrong(key, "an array of objects") }
            return items.mapIndexed { index, item ->
                val resource = item as? Map<*, *> ?: return@mapIndexed CyberdefResourceInput().also { wrong("$key[$index]", "an object") }
                unknownKeys(resource, CyberdefTicketInput.RESOURCE_KEYS, join(path, "$key[$index]"), errors)
                val reader = Reader(resource, join(path, "$key[$index]"), errors)
                CyberdefResourceInput(reader.text("type"), reader.text("identifier"), reader.text("region"))
            }
        }
    }
}
