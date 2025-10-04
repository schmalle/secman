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
 * Service for parsing Masscan XML scan files
 *
 * Parses Masscan XML output format to extract:
 * - Scan metadata (scan date)
 * - Host information (IP address, timestamp)
 * - Port details (number, protocol, state - open only)
 *
 * Related to:
 * - Feature: 005-add-funtionality-to (Masscan XML Import)
 * - FR-002: Parse Masscan XML format
 * - FR-013: Import only state="open" ports
 *
 * @see NmapParserService for similar pattern
 */
@Singleton
class MasscanParserService {

    private val logger = LoggerFactory.getLogger(MasscanParserService::class.java)

    /**
     * Parse Masscan XML content
     *
     * @param xmlContent Raw XML file content as byte array
     * @return Parsed Masscan scan data
     * @throws MasscanParseException if XML is malformed or invalid
     */
    fun parseMasscanXml(xmlContent: ByteArray): MasscanScanData {
        try {
            val document = parseXmlDocument(xmlContent)
            validateMasscanXml(document)

            val root = document.documentElement

            return MasscanScanData(
                scanDate = extractScanDate(root),
                hosts = extractHosts(document)
            )
        } catch (e: MasscanParseException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to parse Masscan XML", e)
            throw MasscanParseException("Invalid Masscan XML format: ${e.message}", e)
        }
    }

    /**
     * Parse XML document using javax.xml.parsers
     */
    private fun parseXmlDocument(xmlContent: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false

        // Security: Disable external entities to prevent XXE attacks
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)

        val builder = factory.newDocumentBuilder()
        return builder.parse(ByteArrayInputStream(xmlContent))
    }

    /**
     * Validate that XML is Masscan format
     * Checks for required <nmaprun> root element with scanner="masscan"
     */
    private fun validateMasscanXml(document: Document) {
        val root = document.documentElement
        if (root.nodeName != "nmaprun") {
            throw MasscanParseException("Invalid root element: ${root.nodeName}, expected <nmaprun>")
        }

        val scanner = root.getAttribute("scanner")
        if (scanner != "masscan") {
            throw MasscanParseException("Not a Masscan XML file (scanner=$scanner)")
        }
    }

    /**
     * Extract scan date from Masscan XML
     * From: <nmaprun start="1234567890">
     */
    private fun extractScanDate(root: Element): LocalDateTime {
        val startAttr = root.getAttribute("start")
        if (startAttr.isNullOrBlank()) {
            logger.warn("Masscan XML missing 'start' attribute, using current time")
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
     * Extract all host elements from Masscan XML
     */
    private fun extractHosts(document: Document): List<MasscanHost> {
        val hosts = mutableListOf<MasscanHost>()
        val hostElements = document.getElementsByTagName("host")

        for (i in 0 until hostElements.length) {
            val hostElement = hostElements.item(i) as Element

            try {
                val host = extractHostData(hostElement)
                hosts.add(host)
            } catch (e: Exception) {
                logger.warn("Failed to parse host element, skipping", e)
                // Continue processing other hosts
            }
        }

        return hosts
    }

    /**
     * Extract data for a single host
     */
    private fun extractHostData(hostElement: Element): MasscanHost {
        val ipAddress = extractIpAddress(hostElement)
        val timestamp = extractTimestamp(hostElement)
        val ports = extractPorts(hostElement)

        return MasscanHost(
            ipAddress = ipAddress,
            timestamp = timestamp,
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

            if (addrType == "ipv4") {
                val addr = address.getAttribute("addr")
                if (addr.isNotBlank()) return addr
            }
        }

        throw MasscanParseException("Host missing IP address")
    }

    /**
     * Extract timestamp from host element
     * From: <host endtime="1234567890">
     */
    private fun extractTimestamp(hostElement: Element): LocalDateTime {
        val endtimeAttr = hostElement.getAttribute("endtime")
        if (endtimeAttr.isNullOrBlank()) {
            logger.warn("Host missing 'endtime' attribute, using current time")
            return LocalDateTime.now()
        }

        return try {
            val epochSeconds = endtimeAttr.toLong()
            LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault())
        } catch (e: NumberFormatException) {
            logger.warn("Invalid 'endtime' attribute value: $endtimeAttr, using current time", e)
            LocalDateTime.now()
        }
    }

    /**
     * Extract all ports for a host
     * From: <ports><port protocol="tcp" portid="80">...
     *
     * CRITICAL: Only imports ports with state="open" (FR-013)
     */
    private fun extractPorts(hostElement: Element): List<MasscanPort> {
        val ports = mutableListOf<MasscanPort>()
        val portsElement = hostElement.getElementsByTagName("ports")
        if (portsElement.length == 0) return ports

        val portElements = (portsElement.item(0) as Element).getElementsByTagName("port")

        for (i in 0 until portElements.length) {
            val portElement = portElements.item(i) as Element

            try {
                val port = extractPortData(portElement)
                // ONLY add if state is "open" (port state filtering)
                if (port.state == "open") {
                    ports.add(port)
                } else {
                    logger.debug("Skipping port {} with state: {} (only 'open' ports imported)",
                                port.portNumber, port.state)
                }
            } catch (e: Exception) {
                logger.warn("Failed to parse port element, skipping", e)
                // Continue processing other ports
            }
        }

        return ports
    }

    /**
     * Extract data for a single port
     */
    private fun extractPortData(portElement: Element): MasscanPort {
        val portNumber = try {
            portElement.getAttribute("portid").toInt()
        } catch (e: NumberFormatException) {
            throw MasscanParseException("Invalid port number: ${portElement.getAttribute("portid")}")
        }

        val protocol = portElement.getAttribute("protocol")

        // Extract state
        val stateElement = portElement.getElementsByTagName("state")
        val state = if (stateElement.length > 0) {
            (stateElement.item(0) as Element).getAttribute("state")
        } else {
            "unknown"
        }

        return MasscanPort(
            portNumber = portNumber,
            protocol = protocol,
            state = state
        )
    }
}

/**
 * Parsed Masscan scan data
 */
data class MasscanScanData(
    val scanDate: LocalDateTime,
    val hosts: List<MasscanHost>
)

/**
 * Parsed host data
 */
data class MasscanHost(
    val ipAddress: String,
    val timestamp: LocalDateTime,
    val ports: List<MasscanPort>
)

/**
 * Parsed port data
 */
data class MasscanPort(
    val portNumber: Int,
    val protocol: String,
    val state: String
)

/**
 * Exception thrown when Masscan XML parsing fails
 */
class MasscanParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
