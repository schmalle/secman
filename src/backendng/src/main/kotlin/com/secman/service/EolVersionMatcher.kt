package com.secman.service

import com.secman.domain.EolStatus
import jakarta.inject.Singleton
import java.time.LocalDate

/**
 * Resolves an observed component ("Ubuntu 22.04.3 LTS", `Google Chrome` +
 * `120.0.6099.109`, npm package `lodash@3.10.1`) to a catalogue product and
 * release cycle, and classifies that cycle against today's date.
 *
 * Deliberately free of Micronaut, JPA and clock access: everything is a pure
 * function of its arguments, which is what makes the matching rules testable
 * without a database (see `EolVersionMatcherTest`). The scan service builds an
 * [Index] once and reuses it for every asset.
 *
 * The matcher never guesses. A component whose version cannot be parsed, or
 * whose version has no cycle prefix in the catalogue, produces no finding —
 * a false "end of life" on a production server is more expensive than a miss.
 */
@Singleton
class EolVersionMatcher {

    // ------------------------------------------------------------------ models

    data class CatalogRelease(
        val releaseId: Long,
        val cycle: String,
        val label: String?,
        val eolDate: LocalDate?,
        val alreadyEol: Boolean,
        val eolUnknown: Boolean,
        val lts: Boolean
    )

    data class CatalogProduct(
        val productId: Long,
        val productKey: String,
        val label: String,
        val category: String?,
        val aliases: Set<String>,
        val releases: List<CatalogRelease>
    )

    data class Match(
        val product: CatalogProduct,
        val release: CatalogRelease,
        val status: EolStatus,
        val daysUntilEol: Long?
    )

    /**
     * Alias -> product lookup built once per scan.
     *
     * Later products never overwrite an alias already claimed by an earlier one,
     * so the mapping is deterministic regardless of catalogue iteration order.
     */
    class Index private constructor(
        private val byAlias: Map<String, CatalogProduct>,
        private val maxAliasTokens: Int
    ) {
        fun lookup(alias: String): CatalogProduct? = byAlias[alias]

        fun maxTokens(): Int = maxAliasTokens

        companion object {
            fun build(products: Collection<CatalogProduct>): Index {
                val byAlias = LinkedHashMap<String, CatalogProduct>()
                var maxTokens = 1
                for (product in products) {
                    val aliases = buildSet {
                        add(product.productKey)
                        add(product.label)
                        addAll(product.aliases)
                    }
                    for (raw in aliases) {
                        val normalized = normalizeAlias(raw)
                        if (normalized.isEmpty()) continue
                        byAlias.putIfAbsent(normalized, product)
                        maxTokens = maxOf(maxTokens, normalized.count { it == ' ' } + 1)
                    }
                }
                // Curated aliases are additive: they only fill gaps the upstream
                // catalogue leaves, never override a name upstream already owns.
                for ((alias, productKey) in CURATED_ALIASES) {
                    val product = byAlias[normalizeAlias(productKey)] ?: continue
                    val normalized = normalizeAlias(alias)
                    if (normalized.isEmpty()) continue
                    byAlias.putIfAbsent(normalized, product)
                    maxTokens = maxOf(maxTokens, normalized.count { it == ' ' } + 1)
                }
                return Index(byAlias, maxTokens)
            }
        }
    }

    // ------------------------------------------------------------------- public

    /**
     * Match a component whose name and version are already separate — the
     * `InstalledProduct` and repository-dependency case.
     *
     * [vendor] only widens the candidate names ("Chrome" from vendor "Google"
     * also tries "google chrome"); it is never required to match.
     */
    fun matchComponent(
        index: Index,
        name: String,
        vendor: String?,
        version: String?,
        today: LocalDate,
        horizonMonths: Long
    ): Match? {
        val versionHint = extractVersion(version)
        val candidates = LinkedHashSet<String>()
        candidates += name
        if (!vendor.isNullOrBlank()) candidates += "$vendor $name"

        for (candidate in candidates) {
            val resolved = resolveProduct(index, candidate) ?: continue
            // A version embedded in the name ("Windows Server 2019") wins over an
            // explicit version field, which for those products carries the build
            // number ("10.0.17763") and matches no cycle.
            val effectiveVersion = resolved.trailingVersion ?: versionHint ?: continue
            val release = resolveRelease(resolved.product, effectiveVersion) ?: continue
            return classify(resolved.product, release, today, horizonMonths)
        }
        return null
    }

    /**
     * Match a free-form OS string such as `Asset.osVersion`
     * ("Windows Server 2019", "Ubuntu 22.04.3 LTS", "Red Hat Enterprise Linux 8.6").
     */
    fun matchOs(
        index: Index,
        osVersion: String?,
        today: LocalDate,
        horizonMonths: Long
    ): Match? {
        if (osVersion.isNullOrBlank()) return null
        val resolved = resolveProduct(index, osVersion) ?: return null
        val version = resolved.trailingVersion ?: return null
        val release = resolveRelease(resolved.product, version) ?: return null
        return classify(resolved.product, release, today, horizonMonths)
    }

    /**
     * Classify a release against [today].
     *
     * A cycle upstream reports as EOL without a date is [EolStatus.EOL] with a
     * null horizon — it must never be counted into "goes EOL in N months",
     * which is what the owner notification promises.
     */
    fun classify(
        product: CatalogProduct,
        release: CatalogRelease,
        today: LocalDate,
        horizonMonths: Long
    ): Match {
        val eolDate = release.eolDate
        if (eolDate != null) {
            val days = java.time.temporal.ChronoUnit.DAYS.between(today, eolDate)
            val status = when {
                !eolDate.isAfter(today) -> EolStatus.EOL
                !eolDate.isAfter(today.plusMonths(horizonMonths)) -> EolStatus.APPROACHING_EOL
                else -> EolStatus.SUPPORTED
            }
            return Match(product, release, status, days)
        }
        if (release.alreadyEol) return Match(product, release, EolStatus.EOL, null)
        return Match(product, release, EolStatus.SUPPORTED, null)
    }

    // ------------------------------------------------------------------ private

    private data class ResolvedProduct(val product: CatalogProduct, val trailingVersion: String?)

    /**
     * Longest-alias-first resolution over the tokenized name.
     *
     * "windows server 2019" tries "windows server 2019", then "windows server"
     * (a hit) and hands back "2019" as the trailing version. Shortening from the
     * right, not the left, is what keeps "microsoft sql server" from resolving
     * to a product called "server".
     */
    private fun resolveProduct(index: Index, rawName: String): ResolvedProduct? {
        val tokens = tokenize(rawName)
        if (tokens.isEmpty()) return null
        val start = minOf(tokens.size, index.maxTokens())
        for (take in start downTo 1) {
            val alias = tokens.subList(0, take).joinToString(" ")
            val product = index.lookup(alias) ?: continue
            val rest = tokens.subList(take, tokens.size)
            return ResolvedProduct(product, rest.firstNotNullOfOrNull { versionOrNull(it) })
        }
        return null
    }

    /**
     * Pick the release whose cycle is the longest dot-segment prefix of
     * [version]. "22.04.3" matches cycle "22.04" but never cycle "22.0".
     */
    fun resolveRelease(product: CatalogProduct, version: String): CatalogRelease? {
        val observed = segments(version)
        if (observed.isEmpty()) return null
        var best: CatalogRelease? = null
        var bestLength = 0
        for (release in product.releases) {
            val cycle = segments(release.cycle)
            if (cycle.isEmpty() || cycle.size > observed.size) continue
            if (observed.subList(0, cycle.size) != cycle) continue
            if (cycle.size > bestLength) {
                best = release
                bestLength = cycle.size
            }
        }
        return best
    }

    companion object {
        private val VERSION_PREFIX = Regex("^(\\d+(?:\\.\\d+)*)")

        /** Architecture / packaging noise that never carries lifecycle meaning. */
        private val NOISE_TOKENS = setOf(
            "x64", "x86", "x8664", "amd64", "arm64", "aarch64", "i386", "i686",
            "64bit", "32bit", "64", "32", "bit", "edition", "release", "version",
            "sp1", "sp2", "update", "build"
        )

        /**
         * Vendor spellings that the upstream catalogue does not carry as an alias.
         * Maps a normalized observed name to an upstream product key; entries whose
         * product key is absent from the catalogue are ignored at index build time.
         */
        private val CURATED_ALIASES: List<Pair<String, String>> = listOf(
            "red hat enterprise linux" to "rhel",
            "red hat enterprise linux server" to "rhel",
            "rhel server" to "rhel",
            "microsoft windows server" to "windows-server",
            "windows server" to "windows-server",
            "microsoft windows" to "windows",
            "microsoft sql server" to "mssqlserver",
            "sql server" to "mssqlserver",
            "microsoft exchange server" to "msexchange",
            "exchange server" to "msexchange",
            "microsoft sharepoint server" to "sharepoint",
            "microsoft office" to "office",
            "google chrome" to "googlechrome",
            "chrome" to "googlechrome",
            "mozilla firefox" to "firefox",
            "oracle java" to "java",
            "java se" to "java",
            "openjdk" to "java",
            "eclipse temurin" to "java",
            "adoptopenjdk" to "java",
            "node js" to "nodejs",
            "node" to "nodejs",
            "apache http server" to "apache",
            "apache httpd" to "apache",
            "httpd" to "apache",
            "apache tomcat" to "tomcat",
            "postgresql server" to "postgresql",
            "postgres" to "postgresql",
            "mariadb server" to "mariadb",
            "mysql server" to "mysql",
            "microsoft net" to "dotnet",
            "microsoft net framework" to "dotnetfx",
            "net framework" to "dotnetfx",
            "net core" to "dotnet",
            "amazon linux" to "amazon-linux",
            "suse linux enterprise server" to "sles",
            "sles" to "sles",
            "oracle linux" to "oracle-linux",
            "rocky linux" to "rocky-linux",
            "alma linux" to "almalinux",
            "debian gnu linux" to "debian",
            "ubuntu linux" to "ubuntu",
            "spring boot" to "spring-boot",
            "spring framework" to "spring-framework",
            "elastic search" to "elasticsearch",
            "vmware esxi" to "esxi",
            "esxi" to "esxi",
            "vmware vcenter server" to "vcenter",
            "python 3" to "python",
            "php" to "php"
        )

        /** Lowercase, strip punctuation and packaging noise, collapse whitespace. */
        fun normalizeAlias(raw: String): String = tokenize(raw).joinToString(" ")

        fun tokenize(raw: String): List<String> {
            val cleaned = raw.lowercase()
                // Parenthesised segments are almost always arch/edition noise.
                .replace(Regex("\\([^)]*\\)"), " ")
                .replace(Regex("[^a-z0-9.]+"), " ")
                .trim()
            if (cleaned.isEmpty()) return emptyList()
            return cleaned.split(' ')
                .map { it.trim('.') }
                .filter { it.isNotEmpty() && it !in NOISE_TOKENS }
        }

        /** `"22.04.3"` from `"22.04.3"`, `"v18"` or `"18-lts"`; null otherwise. */
        fun versionOrNull(token: String): String? {
            val candidate = token.removePrefix("v")
            return VERSION_PREFIX.find(candidate)?.groupValues?.get(1)
        }

        /** First version-looking run in a free-form version string. */
        fun extractVersion(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            return Regex("\\d+(?:\\.\\d+)*").find(raw)?.value
        }

        fun segments(value: String): List<String> {
            val normalized = versionOrNull(value.trim()) ?: return emptyList()
            return normalized.split('.').filter { it.isNotEmpty() }
        }
    }
}
