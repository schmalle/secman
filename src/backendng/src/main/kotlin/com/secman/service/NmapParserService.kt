package com.secman.service

import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Service for parsing nmap XML scan files
 *
 * Parses nmap XML output format to extract:
 * - Scan metadata (scan date, duration)
 * - Host information (IP, hostname)
 * - Port details (number, protocol, state, service, version)
 *
 * Related to:
 * - Feature: 002-implement-a-parsing (Nmap Scan Import)
 * - FR-001: Parse nmap XML files
 * - FR-006: File validation
 * - NFR-001: Validate XML structure before processing
 */
@Singleton
class NmapParserService {

    private val logger = LoggerFactory.getLogger(NmapParserService::class.java)

    /**
     * Parse nmap XML content
     *
     * @param xmlContent Raw XML file content as byte array
     * @return Parsed nmap scan data
     * @throws NmapParseException if XML is malformed or invalid
     */
    fun parseNmapXml(xmlContent: ByteArray): NmapScanData {
        try {
            val document = parseXmlDocument(xmlContent)
            validateNmapXml(document)

            val root = document.documentElement

            return NmapScanData(
                scanDate = extractScanDate(root),
                duration = extractDuration(root),
                hosts = extractHosts(document)
            )
        } catch (e: NmapParseException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to parse nmap XML", e)
            throw NmapParseException("Invalid nmap XML format: ${e.message}", e)
        }
    }

    /**
     * Parse XML document using javax.xml.parsers
     */
    private fun parseXmlDocument(xmlContent: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false

        // Security: Complete XXE prevention per OWASP guidelines
        // Disallow DOCTYPE declarations entirely (most secure option)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.setXIncludeAware(false)
        factory.setExpandEntityReferences(false)

        val builder = factory.newDocumentBuilder()
        return builder.parse(ByteArrayInputStream(xmlContent))
    }

    /**
     * Validate that XML is nmap format
     * Checks for required <nmaprun> root element
     */
    private fun validateNmapXml(document: Document) {
        val root = document.documentElement
        if (root.nodeName != "nmaprun") {
            throw NmapParseException("Invalid nmap XML: root element must be <nmaprun>, found <${root.nodeName}>")
        }
    }

    /**
     * Extract scan date from nmap XML
     * From: <nmaprun start="1234567890">
     */
    private fun extractScanDate(root: Element): LocalDateTime {
        val startAttr = root.getAttribute("start")
        if (startAttr.isNullOrBlank()) {
            logger.warn("Nmap XML missing 'start' attribute, using current time")
            return LocalDateTime.now()
        }

        return try {
            val epochSeconds = startAttr.toLong()
            LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault())
        } catch (e: NumberFormatException) {
            logger.warn("Invalid 'start' attribute value: $startAttr, using current time", e)
            LocalDateTime.now()
        }
    }

    /**
     * Extract scan duration from nmap XML
     * From: <runstats><finished elapsed="123"/>
     */
    private fun extractDuration(root: Element): Int? {
        val runstats = root.getElementsByTagName("runstats")
        if (runstats.length == 0) return null

        val finished = (runstats.item(0) as Element).getElementsByTagName("finished")
        if (finished.length == 0) return null

        val elapsed = (finished.item(0) as Element).getAttribute("elapsed")
        return elapsed?.toIntOrNull()
    }

    /**
     * Extract all host elements from nmap XML
     */
    private fun extractHosts(document: Document): List<NmapHost> {
        val hosts = mutableListOf<NmapHost>()
        val hostElements = document.getElementsByTagName("host")

        for (i in 0 until hostElements.length) {
            val hostElement = hostElements.item(i) as Element

            // Check if host is up
            val status = hostElement.getElementsByTagName("status")
            if (status.length == 0) continue

            val state = (status.item(0) as Element).getAttribute("state")
            if (state != "up") {
                logger.debug("Skipping host with state: $state")
                continue
            }

            try {
                val host = extractHostData(hostElement)
                hosts.add(host)
            } catch (e: Exception) {
                logger.warn("Failed to parse host element, skipping", e)
            }
        }

        return hosts
    }

    /**
     * Extract data for a single host
     */
    private fun extractHostData(hostElement: Element): NmapHost {
        val ipAddress = extractIpAddress(hostElement)
        val hostname = extractHostname(hostElement)
        val ports = extractPorts(hostElement)

        return NmapHost(
            ipAddress = ipAddress,
            hostname = hostname,
            ports = ports
        )
    }

    /**
     * Extract IP address from host
     * From: <address addr="192.168.1.1" addrtype="ipv4"/>
     */
    private fun extractIpAddress(hostElement: Element): String {
        val addresses = hostElement.getElementsByTagName("address")

        for (i in 0 until addresses.length) {
            val address = addresses.item(i) as Element
            val addrType = address.getAttribute("addrtype")

            if (addrType == "ipv4" || addrType == "ipv6") {
                val addr = address.getAttribute("addr")
                if (addr.isNotBlank()) return addr
            }
        }

        throw NmapParseException("Host missing IP address")
    }

    /**
     * Extract hostname from host (if available)
     * From: <hostnames><hostname name="example.com" type="PTR"/></hostnames>
     * Returns: First hostname found, or null if none
     * Implements: Decision 1 (IP as fallback when hostname missing)
     */
    private fun extractHostname(hostElement: Element): String? {
        val hostnamesElement = hostElement.getElementsByTagName("hostnames")
        if (hostnamesElement.length == 0) return null

        val hostnameElements = (hostnamesElement.item(0) as Element).getElementsByTagName("hostname")
        if (hostnameElements.length == 0) return null

        val firstHostname = hostnameElements.item(0) as Element
        val name = firstHostname.getAttribute("name")

        return if (name.isNotBlank()) name else null
    }

    /**
     * Extract all ports for a host
     * From: <ports><port protocol="tcp" portid="80">...
     */
    private fun extractPorts(hostElement: Element): List<NmapPort> {
        val ports = mutableListOf<NmapPort>()
        val portsElement = hostElement.getElementsByTagName("ports")
        if (portsElement.length == 0) return ports

        val portElements = (portsElement.item(0) as Element).getElementsByTagName("port")

        for (i in 0 until portElements.length) {
            val portElement = portElements.item(i) as Element

            try {
                val port = extractPortData(portElement)
                ports.add(port)
            } catch (e: Exception) {
                logger.warn("Failed to parse port element, skipping", e)
            }
        }

        return ports
    }

    /**
     * Extract data for a single port
     */
    private fun extractPortData(portElement: Element): NmapPort {
        val portNumber = portElement.getAttribute("portid").toInt()
        val protocol = portElement.getAttribute("protocol")

        // Extract state
        val stateElement = portElement.getElementsByTagName("state")
        val state = if (stateElement.length > 0) {
            (stateElement.item(0) as Element).getAttribute("state")
        } else {
            "unknown"
        }

        // Extract service info (optional)
        val serviceElement = portElement.getElementsByTagName("service")
        val service = if (serviceElement.length > 0) {
            (serviceElement.item(0) as Element).getAttribute("name").takeIf { it.isNotBlank() }
        } else {
            null
        }

        // Extract version info (optional)
        val version = if (serviceElement.length > 0) {
            val svcElement = serviceElement.item(0) as Element
            val product = svcElement.getAttribute("product")
            val versionAttr = svcElement.getAttribute("version")

            when {
                product.isNotBlank() && versionAttr.isNotBlank() -> "$product $versionAttr"
                product.isNotBlank() -> product
                versionAttr.isNotBlank() -> versionAttr
                else -> null
            }
        } else {
            null
        }

        return NmapPort(
            portNumber = portNumber,
            protocol = protocol,
            state = state,
            service = service,
            version = version
        )
    }
}

/**
 * Parsed nmap scan data
 */
data class NmapScanData(
    val scanDate: LocalDateTime,
    val duration: Int?,
    val hosts: List<NmapHost>
)

/**
 * Parsed host data
 */
data class NmapHost(
    val ipAddress: String,
    val hostname: String?,
    val ports: List<NmapPort>
)

/**
 * Parsed port data
 */
data class NmapPort(
    val portNumber: Int,
    val protocol: String,
    val state: String,
    val service: String?,
    val version: String?
)

/**
 * Exception thrown when nmap XML parsing fails
 */
class NmapParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
