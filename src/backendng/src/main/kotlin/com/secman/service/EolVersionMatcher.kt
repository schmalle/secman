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
        // A distribution-packaged build is supported by the distribution, not by
        // upstream, so no upstream cycle can describe it. Rejected outright rather
        // than matched against the wrong lifecycle.
        if (hasDistroRevision(version) || hasDistroVendor(vendor)) return null
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
        val observed = segments(normalizeJavaVersion(product.productKey, version))
        if (observed.isEmpty()) return null
        var best: CatalogRelease? = null
        var bestLength = 0
        for (release in product.releases) {
            // A labelled cycle cannot be identified from a numeric version — see
            // isNumericCycle. Matching one would be decided by list order.
            if (!isNumericCycle(release.cycle)) continue
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

        /**
         * The Java distributions endoflife.date publishes. There is deliberately
         * no generic `java` product upstream, so a curated alias pointing at one
         * would be dropped at index build time and Java would silently never match.
         */
        val JAVA_PRODUCT_KEYS = setOf(
            "oracle-jdk", "openjdk-builds-from-oracle", "amazon-corretto",
            "eclipse-temurin", "azul-zulu", "microsoft-build-of-openjdk",
            "redhat-build-of-openjdk", "graalvm-ce", "oracle-graalvm"
        )

        /**
         * Distribution packaging revisions: `1.8.0.482.b08-1.amzn2.0.1`,
         * `2.52.3-0ubuntu0.24.04.1`, `2026c-1.el8_10`, `23.01+dfsg-11ubuntu0.1~esm1`.
         *
         * A packaging revision *proves* the support contract belongs to the
         * distribution rather than to upstream — Canonical still supports nginx
         * 1.18 long after upstream dropped it. Matching those against an upstream
         * cycle table would report supported production software as end of life,
         * which is the single failure this matcher exists to avoid. Rejecting is
         * therefore correct even though it costs coverage on Linux hosts.
         */
        private val DISTRO_REVISION = Regex(
            "~|\\+dfsg|\\+deb|\\+nmu|\\+really|ubuntu|\\.el\\d|el\\d[_.]|\\.fc\\d|amzn|\\.suse|deb\\d|esm",
            RegexOption.IGNORE_CASE
        )

        /** True when [version] carries a distribution packaging revision. */
        fun hasDistroRevision(version: String?): Boolean =
            !version.isNullOrBlank() && DISTRO_REVISION.containsMatchIn(version)

        /**
         * Package-maintainer vendors: `Ubuntu Developers <ubuntu-devel-discuss@…>`,
         * `Debian Python Team <…>`, `Ubuntu Kernel Team <…>`.
         *
         * The vendor field on a `.deb` is its maintainer, so this string *proves*
         * the component is distribution-packaged even when the version carries no
         * visible revision — `node-arrify 2.0.1-2` looks like a plain version but
         * is an Ubuntu npm-library package, and matching it against the Node.js
         * *runtime* lifecycle reports "Node.js 2, end of life" for a library that
         * has nothing to do with the runtime's support window.
         *
         * Red Hat and Amazon Linux are deliberately absent: their packages already
         * carry `.el8` / `.amzn2` in the version and are caught by
         * [hasDistroRevision], and excluding those vendors here would also make
         * `redhat-build-of-openjdk` — a real catalogue product — unmatchable.
         */
        private val DISTRO_VENDOR = Regex(
            "ubuntu|debian|amazon linux|opensuse|suse linux|centos|rocky linux|almalinux",
            RegexOption.IGNORE_CASE
        )

        /** True when [vendor] identifies a distribution's package maintainer. */
        fun hasDistroVendor(vendor: String?): Boolean =
            !vendor.isNullOrBlank() && DISTRO_VENDOR.containsMatchIn(vendor)

        /**
         * Java numbered its releases `1.x` up to Java 8 and plainly `x` from Java 9
         * on — `1.8.0_371` *is* Java 8. Catalogue cycles use the modern form, so
         * `1.8.0.392` has to become `8.0.392` or it prefix-matches no cycle at all.
         *
         * A documented versioning fact rather than an inference, and narrowly
         * applied: only for the Java distributions, and only when the leading
         * segment is exactly `1`. `1.4` becomes `4`, which no cycle carries, so it
         * fails closed.
         */
        fun normalizeJavaVersion(productKey: String, version: String): String {
            if (productKey !in JAVA_PRODUCT_KEYS) return version
            val parts = version.split('.')
            if (parts.size < 2 || parts[0] != "1") return version
            return parts.drop(1).joinToString(".")
        }

        /** Architecture / packaging noise that never carries lifecycle meaning. */
        private val NOISE_TOKENS = setOf(
            "x64", "x86", "x8664", "amd64", "arm64", "aarch64", "i386", "i686",
            "64bit", "32bit", "64", "32", "bit", "edition", "release", "version",
            "sp1", "sp2", "update", "build",
            // Corporate suffixes carried by the vendor field ("Oracle Corporation",
            // "Tanuki Software, Ltd."). They never distinguish a product, and
            // dropping them keeps the vendor-widened candidate readable.
            "inc", "corp", "corporation", "ltd", "llc", "gmbh"
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
            // Java is not one product upstream. endoflife.date publishes a
            // separate product per distribution because their support windows
            // genuinely differ (Oracle JDK 8, Amazon Corretto 8 and Eclipse
            // Temurin 8 all end on different dates), so each observed spelling
            // maps to the distribution that actually ships it. A build whose
            // distribution cannot be identified produces no finding rather than
            // borrowing another vendor's date — see JAVA_PRODUCT_KEYS.
            "oracle java" to "oracle-jdk",
            "java se" to "oracle-jdk",
            "java se development kit" to "oracle-jdk",
            "java se runtime environment" to "oracle-jdk",
            // AdoptOpenJDK was renamed Eclipse Temurin; same artefacts, same dates.
            "adoptopenjdk" to "eclipse-temurin",
            "amazon corretto jre" to "amazon-corretto",
            // "OpenJDK Platform" names no distribution on its own, so the vendor
            // decides. These are the vendor+name forms after tokenization, which
            // drops the corporate suffix ("Oracle Corporation" -> "oracle").
            "oracle openjdk platform" to "openjdk-builds-from-oracle",
            "amazon.com openjdk platform" to "amazon-corretto",
            // ASP.NET Core has no product of its own upstream; its versions track
            // .NET exactly (ASP.NET Core 8.0.29 ships with .NET 8.0).
            "asp net core" to "dotnet",
            "microsoft asp net core" to "dotnet",
            "net desktop runtime" to "dotnet",
            "microsoft net desktop runtime" to "dotnet",
            // Microsoft ships the .NET desktop runtime under the product name
            // "Microsoft Windows Desktop Runtime - 8.0.21". Without these entries
            // the shorter "microsoft windows" alias wins and .NET 8 is reported as
            // Windows 8, end of life since 2016. Longest alias wins, so naming the
            // full form here is what resolves it.
            "microsoft windows desktop runtime" to "dotnet",
            "windows desktop runtime" to "dotnet",
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

        /**
         * True when [cycle] is a bare numeric version and nothing else.
         *
         * A labelled cycle — `10-1507`, `11-ltsc`, `11-24h2-e-lts`, `2026c` — names
         * an edition or servicing channel. [segments] keeps only the leading numeric
         * run, so every one of them collapses to its major version: `10-1507`
         * becomes `[10]`, and a build number like `10.0.20348.3451` (Windows Server
         * 2022) then prefix-matches it and is reported as Windows 10 version 1507,
         * end of life since 2017 — decided by nothing more than release list order.
         *
         * Which edition a bare build number belongs to cannot be recovered without a
         * build-to-release table the catalogue does not publish, so labelled cycles
         * are skipped rather than guessed at. Products whose cycles are plain
         * numbers (`8`, `22.04`, `4.8`, `2019`, `11`) are unaffected.
         */
        fun isNumericCycle(cycle: String): Boolean {
            val trimmed = cycle.trim()
            if (trimmed.isEmpty()) return false
            return segments(trimmed).joinToString(".") == trimmed
        }
    }
}
