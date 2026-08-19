package com.secman.service

import com.secman.domain.ProductClass
import com.secman.domain.ProductClassificationRule
import com.secman.domain.RuleMatchField

/**
 * Decides whether a finding describes deployed software or an installer artifact.
 *
 * Pure by design — no JPA, no clock, no Micronaut — so the whole rule semantics are unit
 * testable in isolation, the same shape as [EolVersionMatcher].
 *
 * Two entry points, because the two data sources carry different evidence:
 *  - [classifyProduct] for `InstalledProduct` rows, which have name, vendor and (sometimes) a path.
 *  - [classifyVulnerability] for `Vulnerability` rows, which carry only
 *    `vulnerableProductVersions` — a `", "`-joined list of CrowdStrike `product_name_version`
 *    strings. No vendor, no path.
 */
object ProductClassifier {

    /** `", "`-joined `apps[].product_name_version`, as built by `CrowdStrikeApiClientImpl`. */
    private const val PRODUCT_SEPARATOR = ","

    /**
     * Compiled form of one rule. Building the [Regex] is the expensive part, so callers
     * compile the rule set once per pass ([compile]) and reuse it across every row.
     */
    data class CompiledRule(
        val matchField: RuleMatchField,
        val classification: ProductClass,
        val regex: Regex,
        val source: ProductClassificationRule
    )

    /**
     * Compile enabled rules into evaluation order: every [ProductClass.INSTALLED] allowlist
     * rule first, then the artifact rules, each ordered by `priority` then `id`.
     *
     * An unparseable or over-long pattern is dropped rather than thrown: one bad admin entry
     * must not take down an import or a list query. Callers that need to surface it should
     * validate on write instead.
     */
    fun compile(rules: List<ProductClassificationRule>): List<CompiledRule> =
        rules.asSequence()
            .filter { it.enabled }
            .filter { it.pattern.isNotBlank() && it.pattern.length <= ProductClassificationRule.MAX_PATTERN_LENGTH }
            .sortedWith(
                compareBy(
                    { if (it.classification == ProductClass.INSTALLED) 0 else 1 },
                    { it.priority },
                    { it.id ?: Long.MAX_VALUE }
                )
            )
            .mapNotNull { rule ->
                globToRegex(rule.pattern)?.let { CompiledRule(rule.matchField, rule.classification, it, rule) }
            }
            .toList()

    /**
     * Classify one installed-product row.
     *
     * Returns [ProductClass.INSTALLED] when nothing matches: the default must be "visible",
     * because a finding we cannot classify is not thereby a false positive.
     */
    fun classifyProduct(
        name: String?,
        vendor: String?,
        paths: List<String>,
        rules: List<CompiledRule>
    ): ProductClass {
        if (name.isNullOrBlank()) return ProductClass.UNKNOWN
        val normalizedName = normalizeText(name)
        val normalizedVendor = vendor?.takeIf { it.isNotBlank() }?.let { normalizeText(it) }
        val normalizedPaths = paths.filter { it.isNotBlank() }.map { normalizePath(it) }

        for (rule in rules) {
            val matched = when (rule.matchField) {
                RuleMatchField.PRODUCT_NAME -> rule.regex.matches(normalizedName)
                RuleMatchField.VENDOR -> normalizedVendor != null && rule.regex.matches(normalizedVendor)
                // Asymmetric on purpose. An artifact rule must describe EVERY known path before
                // it can hide the row — a product present both in Program Files and as a
                // leftover payload is installed. An allowlist rule only needs ONE path to
                // vouch for the row.
                RuleMatchField.INSTALL_PATH -> normalizedPaths.isNotEmpty() && when (rule.classification) {
                    ProductClass.INSTALLED -> normalizedPaths.any { rule.regex.matches(it) }
                    else -> normalizedPaths.all { rule.regex.matches(it) }
                }
            }
            if (matched) return rule.classification
        }
        return ProductClass.INSTALLED
    }

    /**
     * Classify one vulnerability row from its `vulnerableProductVersions` string.
     *
     * The field may name several products (`"Chrome Installer 1.0, Chrome 2.0"`). The row is an
     * artifact only if EVERY named product is one — a single real product makes the finding
     * actionable, so it stays visible.
     */
    fun classifyVulnerability(
        vulnerableProductVersions: String?,
        rules: List<CompiledRule>
    ): ProductClass {
        if (vulnerableProductVersions.isNullOrBlank()) return ProductClass.UNKNOWN
        val products = vulnerableProductVersions.split(PRODUCT_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (products.isEmpty()) return ProductClass.UNKNOWN

        // Only name-based rules can apply: a vulnerability row carries no vendor and no path.
        val nameRules = rules.filter { it.matchField == RuleMatchField.PRODUCT_NAME }
        if (nameRules.isEmpty()) return ProductClass.INSTALLED

        var sawArtifact = false
        for (product in products) {
            val normalized = normalizeText(product)
            val hit = nameRules.firstOrNull { it.regex.matches(normalized) }
            when (hit?.classification) {
                ProductClass.INSTALLER_ARTIFACT -> sawArtifact = true
                // An explicit allowlist hit, or no hit at all, means this product is real.
                else -> return ProductClass.INSTALLED
            }
        }
        return if (sawArtifact) ProductClass.INSTALLER_ARTIFACT else ProductClass.INSTALLED
    }

    /** Lowercase and collapse whitespace so patterns need not care about spacing or case. */
    internal fun normalizeText(value: String): String =
        value.trim().lowercase().replace(WHITESPACE, " ")

    /**
     * Path form used for matching: backslashes to forward slashes, lowercase, repeated
     * separators collapsed, trailing separator dropped. Windows paths arrive in both
     * `C:\Windows\` and `C:\WINDOWS\` spellings from the same tenant, and CrowdStrike's own
     * FQL wildcard matching on this field is case-sensitive — so normalizing here (rather than
     * filtering server-side) is what makes path rules behave predictably.
     */
    internal fun normalizePath(value: String): String {
        val slashed = value.trim().replace('\\', '/').lowercase().replace(MULTI_SLASH, "/")
        return if (slashed.length > 1 && slashed.endsWith("/")) slashed.dropLast(1) else slashed
    }

    /**
     * Translate a GLOB to an anchored, case-insensitive [Regex], escaping every other
     * metacharacter. Never accepts a regex from the caller.
     *
     * Returns null if the resulting pattern will not compile, so one malformed rule cannot
     * break a whole pass.
     */
    internal fun globToRegex(glob: String): Regex? {
        val sb = StringBuilder(glob.length * 2)
        for (ch in glob.trim().lowercase()) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(ch.toString()))
            }
        }
        return try {
            Regex(sb.toString(), RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            null
        }
    }

    private val WHITESPACE = Regex("\\s+")
    private val MULTI_SLASH = Regex("/{2,}")
}
