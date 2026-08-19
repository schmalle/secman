package com.secman.domain

/**
 * How a finding's subject relates to software that is actually deployed on the host.
 *
 * CrowdStrike reports installer payloads as first-class "applications" alongside the products
 * they install ("Chrome Installer" is a separate application entity from "Chrome"), so a
 * technically-true EOL or vulnerability finding may describe a payload rather than anything
 * that runs. This enum records that distinction so read surfaces can filter on it.
 *
 * Deliberately NOT derived from the installation path. Phase-0 measurement against the live
 * tenant (2026-08-19) showed the path cannot carry this signal:
 *  - "Chrome Installer" — the single largest source of installer noise, 17910 rows
 *    estate-wide — returns NO installation path at all.
 *  - `C:\Windows\Installer\*.msi` (565594 rows) and `C:\ProgramData\Package Cache\`
 *    (150812 rows) are where Windows and WiX keep the package OF AN INSTALLED PRODUCT for
 *    repair and uninstall. Splunk Universal Forwarder is 100% the former and is running.
 *    Treating those as artifacts would hide genuine risk, so they classify as [INSTALLED].
 *  - Genuine stray-download locations exist but are negligible: `*Downloads*` matched 5 rows
 *    estate-wide, `*ccmcache*` 5.
 *
 * Path rules are still supported ([RuleMatchField.INSTALL_PATH]) for the narrow Downloads /
 * Temp / ccmcache cases, but product identity is the load-bearing signal.
 */
enum class ProductClass {
    /** Real deployed software. The default, and always visible. */
    INSTALLED,

    /** An installer, setup bundle or download payload rather than deployed software. */
    INSTALLER_ARTIFACT,

    /**
     * Not yet classified — the column default before the first materialization pass.
     * Treated exactly like [INSTALLED] by every read filter: absence of evidence must never
     * hide a finding.
     */
    UNKNOWN
}

/** Which attribute of a finding a [ProductClassificationRule] tests. */
enum class RuleMatchField {
    /** Product name — `Vulnerability.vulnerableProductVersions` / `InstalledProduct.name`. */
    PRODUCT_NAME,

    /** Vendor — `InstalledProduct.vendor`. Not available on vulnerability rows. */
    VENDOR,

    /**
     * Installation path — `InstalledProduct.installationPath`. Not available on vulnerability
     * rows, and populated for only ~18% of Discover rows even with `facet=install_usage`.
     */
    INSTALL_PATH
}
