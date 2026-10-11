package com.secman.service.aibuddy

import com.fasterxml.jackson.databind.JsonNode

/** Ephemeral public inventory facts; user input never establishes verified patch applicability. */
data class AiBuddyInventoryInput(
    val build: String = "", val edition: String = "", val architecture: String = "",
    val firefoxVersion: String = "", val firefoxChannel: String = ""
) {
    fun apply(snapshot: AiBuddySnapshot): AiBuddySnapshot {
        if (firefoxVersion.isNotEmpty() && snapshot.products.filter { it.productClass != "INSTALLER_ARTIFACT" && AiBuddyProducts.canonical(it.name) == "mozilla firefox" }
                .mapNotNull { it.version }.distinct().size > 1) throw AiBuddyFailure("INVENTORY_AMBIGUOUS")
        val firefox = if (firefoxVersion.isNotEmpty() && firefoxChannel.isNotEmpty()) listOf(RecordedProduct(
            -1, "Mozilla Firefox ${if (firefoxChannel == "ESR") "ESR" else "Release"}", "Mozilla", firefoxVersion,
            "INSTALLED", null, null, null, null)) else emptyList()
        return snapshot.copy(system = snapshot.system.copy(build = build.ifEmpty { snapshot.system.build },
            edition = edition.ifEmpty { snapshot.system.edition }, architecture = architecture.ifEmpty { snapshot.system.architecture }),
            products = if (firefox.isEmpty()) snapshot.products else snapshot.products.filter { AiBuddyProducts.canonical(it.name) != "mozilla firefox" } + firefox)
    }

    companion object {
        fun parse(node: JsonNode): AiBuddyInventoryInput {
            if (!node.isObject || node.fieldNames().asSequence().any { it !in setOf("build", "edition", "architecture", "firefoxVersion", "firefoxChannel") }) throw AiBuddyFailure("INVALID_REQUEST")
            fun field(name: String): String {
                val item = node.path(name)
                if (item.isMissingNode) return ""
                if (!item.isTextual) throw AiBuddyFailure("INVALID_REQUEST")
                return item.asText()
            }
            val input = AiBuddyInventoryInput(field("build"), field("edition"), field("architecture"), field("firefoxVersion"), field("firefoxChannel"))
            if (input.build.isNotEmpty() && !Regex("[0-9]{5}\\.[0-9]{1,6}").matches(input.build) ||
                input.edition !in setOf("", "Standard", "Datacenter") || input.architecture !in setOf("", "x64", "x86", "ARM64") ||
                input.firefoxVersion.isNotEmpty() && !Regex("[0-9]{1,8}(?:\\.[0-9]{1,8}){0,5}").matches(input.firefoxVersion) ||
                input.firefoxChannel !in setOf("", "RELEASE", "ESR") ||
                input.firefoxVersion.isEmpty() != input.firefoxChannel.isEmpty()) throw AiBuddyFailure("INVALID_REQUEST")
            return input
        }
    }
}
