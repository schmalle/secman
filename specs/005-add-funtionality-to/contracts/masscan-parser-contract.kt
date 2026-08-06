/**
 * Contract specification for MasscanParserService
 *
 * This file defines the expected behavior and contracts for the Masscan XML parser.
 * Actual test implementations will be created in:
 * - src/backendng/src/test/kotlin/com/secman/service/MasscanParserServiceTest.kt
 *
 * Related to:
 * - Feature: 005-add-funtionality-to (Masscan XML Import)
 * - Spec: specs/005-add-funtionality-to/spec.md
 */

// ============================================================================
// SERVICE CONTRACT
// ============================================================================

/**
 * MasscanParserService
 *
 * Service responsible for parsing Masscan XML format into domain objects.
 *
 * Package: com.secman.service
 * Annotation: @Singleton
 */
interface MasscanParserServiceContract {

    /**
     * Parse Masscan XML content
     *
     * Contract:
     * - Input: ByteArray (raw XML file content)
     * - Output: MasscanScanData (parsed scan data)
     * - Throws: MasscanParseException if XML is malformed or invalid
     *
     * Validation:
     * - MUST validate root element is <nmaprun> with scanner="masscan"
     * - MUST prevent XXE attacks (disable external entities)
     * - MUST handle malformed XML gracefully with clear error message
     *
     * Behavior:
     * - Parse scan metadata (start time)
     * - Extract all host elements
     * - For each host: extract IP, timestamp, and ports
     * - Filter ports: only include state="open"
     * - Skip hosts with missing IP addresses
     * - Continue processing on individual host failures (log warning)
     */
    fun parseMasscanXml(xmlContent: ByteArray): MasscanScanData
}

// ============================================================================
// DATA CLASS CONTRACTS
// ============================================================================

/**
 * MasscanScanData
 *
 * Top-level result of parsing Masscan XML
 *
 * Contract:
 * - scanDate: When the scan was initiated (from <nmaprun start="...">)
 * - hosts: List of discovered hosts with their ports
 */
data class MasscanScanData(
    val scanDate: LocalDateTime,
    val hosts: List<MasscanHost>
)

/**
 * MasscanHost
 *
 * Represents a single host discovered in the scan
 *
 * Contract:
 * - ipAddress: IPv4 address (e.g., "193.99.144.85")
 * - timestamp: When this host was scanned (from <host endtime="...">)
 * - ports: List of open ports discovered on this host
 */
data class MasscanHost(
    val ipAddress: String,
    val timestamp: LocalDateTime,
    val ports: List<MasscanPort>
)

/**
 * MasscanPort
 *
 * Represents a single open port discovered on a host
 *
 * Contract:
 * - portNumber: Port number (1-65535)
 * - protocol: Protocol type ("tcp", "udp", etc.)
 * - state: Port state (should always be "open" after filtering)
 */
data class MasscanPort(
    val portNumber: Int,
    val protocol: String,
    val state: String
)

/**
 * MasscanParseException
 *
 * Exception thrown when Masscan XML parsing fails
 *
 * Contract:
 * - Extends Exception
 * - message: Clear description of what went wrong
 * - cause: Original exception (if applicable)
 */
class MasscanParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

// ============================================================================
// TEST CONTRACTS (to be implemented)
// ============================================================================

/**
 * MasscanParserServiceTest
 *
 * Unit tests for MasscanParserService following TDD approach.
 * Tests MUST be written before implementation.
 *
 * Test Coverage Requirements:
 * - ✅ Parsing valid Masscan XML with single host and port
 * - ✅ Parsing valid Masscan XML with multiple hosts
 * - ✅ Parsing valid Masscan XML with multiple ports per host
 * - ✅ Filtering: Only "open" ports included, skip "closed"/"filtered"
 * - ✅ Timestamp conversion from Unix epoch to LocalDateTime
 * - ✅ Missing IP address: Skip host, continue processing
 * - ✅ Invalid port number: Skip port, continue processing
 * - ✅ Invalid timestamp: Use current time, log warning
 * - ✅ Empty XML: Return empty host list
 * - ✅ Malformed XML: Throw MasscanParseException
 * - ✅ Wrong root element: Throw MasscanParseException
 * - ✅ Wrong scanner attribute: Throw MasscanParseException (not "masscan")
 * - ✅ XXE attack prevention: External entities disabled
 * - ✅ Large file handling: Process efficiently (within memory limits)
 */

// Test Case 1: Valid single host with single port
// Input: masscan.xml with 1 host, 1 open port
// Expected: MasscanScanData with 1 host, 1 port
contract_test_valid_single_host_single_port {
    given: "Valid Masscan XML with single host and port"
    val xml = """
        <?xml version="1.0"?>
        <nmaprun scanner="masscan" start="1759560572" version="1.0-BETA">
          <host endtime="1759560572">
            <address addr="193.99.144.85" addrtype="ipv4"/>
            <ports>
              <port protocol="tcp" portid="80">
                <state state="open" reason="syn-ack"/>
              </port>
            </ports>
          </host>
        </nmaprun>
    """.toByteArray()

    when: "Parsing the XML"
    val result = masscanParserService.parseMasscanXml(xml)

    then: "Should return scan data with 1 host and 1 port"
    assert result.hosts.size == 1
    assert result.hosts[0].ipAddress == "193.99.144.85"
    assert result.hosts[0].ports.size == 1
    assert result.hosts[0].ports[0].portNumber == 80
    assert result.hosts[0].ports[0].protocol == "tcp"
    assert result.hosts[0].ports[0].state == "open"
    assert result.scanDate.isEqual(LocalDateTime.of(2025, 10, 4, 8, 49, 32))
}

// Test Case 2: Port state filtering
// Input: XML with mixed port states (open, closed, filtered)
// Expected: Only "open" ports in result
contract_test_port_state_filtering {
    given: "Masscan XML with open, closed, and filtered ports"
    val xml = """
        <nmaprun scanner="masscan" start="1759560572">
          <host endtime="1759560572">
            <address addr="192.168.1.1" addrtype="ipv4"/>
            <ports>
              <port protocol="tcp" portid="80">
                <state state="open"/>
              </port>
              <port protocol="tcp" portid="81">
                <state state="closed"/>
              </port>
              <port protocol="tcp" portid="443">
                <state state="open"/>
              </port>
              <port protocol="tcp" portid="444">
                <state state="filtered"/>
              </port>
            </ports>
          </host>
        </nmaprun>
    """.toByteArray()

    when: "Parsing the XML"
    val result = masscanParserService.parseMasscanXml(xml)

    then: "Should return only open ports (80 and 443)"
    assert result.hosts.size == 1
    assert result.hosts[0].ports.size == 2
    assert result.hosts[0].ports.map { it.portNumber }.containsAll(listOf(80, 443))
    assert result.hosts[0].ports.all { it.state == "open" }
}

// Test Case 3: Invalid root element
// Input: XML with wrong root element
// Expected: MasscanParseException
contract_test_invalid_root_element {
    given: "XML with non-nmaprun root element"
    val xml = """
        <?xml version="1.0"?>
        <scanresult>
          <host>...</host>
        </scanresult>
    """.toByteArray()

    when: "Parsing the XML"
    val exception = assertThrows<MasscanParseException> {
        masscanParserService.parseMasscanXml(xml)
    }

    then: "Should throw MasscanParseException with clear message"
    assert exception.message.contains("Invalid root element")
    assert exception.message.contains("nmaprun")
}

// Test Case 4: Wrong scanner attribute
// Input: nmaprun with scanner="nmap" instead of "masscan"
// Expected: MasscanParseException
contract_test_wrong_scanner_attribute {
    given: "nmaprun XML with scanner=\"nmap\""
    val xml = """
        <?xml version="1.0"?>
        <nmaprun scanner="nmap" start="1234567890">
          <host>...</host>
        </nmaprun>
    """.toByteArray()

    when: "Parsing the XML"
    val exception = assertThrows<MasscanParseException> {
        masscanParserService.parseMasscanXml(xml)
    }

    then: "Should throw MasscanParseException identifying wrong scanner"
    assert exception.message.contains("Not a Masscan XML file")
    assert exception.message.contains("scanner=nmap")
}

// Test Case 5: Malformed XML
// Input: Invalid XML syntax
// Expected: MasscanParseException with clear error
contract_test_malformed_xml {
    given: "Malformed XML with unclosed tags"
    val xml = """
        <?xml version="1.0"?>
        <nmaprun scanner="masscan" start="1234567890">
          <host endtime="1234567890">
            <address addr="192.168.1.1"
          </host>
    """.toByteArray()

    when: "Parsing the XML"
    val exception = assertThrows<MasscanParseException> {
        masscanParserService.parseMasscanXml(xml)
    }

    then: "Should throw MasscanParseException with XML parse error details"
    assert exception.message.contains("Invalid Masscan XML format")
}

// Test Case 6: Missing IP address
// Input: Host element without address element
// Expected: Skip that host, continue processing others
contract_test_missing_ip_address {
    given: "XML with one valid host and one missing IP"
    val xml = """
        <nmaprun scanner="masscan" start="1759560572">
          <host endtime="1759560572">
            <address addr="192.168.1.1" addrtype="ipv4"/>
            <ports>
              <port protocol="tcp" portid="80">
                <state state="open"/>
              </port>
            </ports>
          </host>
          <host endtime="1759560572">
            <!-- Missing address element -->
            <ports>
              <port protocol="tcp" portid="443">
                <state state="open"/>
              </port>
            </ports>
          </host>
        </nmaprun>
    """.toByteArray()

    when: "Parsing the XML"
    val result = masscanParserService.parseMasscanXml(xml)

    then: "Should return only the valid host"
    assert result.hosts.size == 1
    assert result.hosts[0].ipAddress == "192.168.1.1"
}

// Test Case 7: Empty XML (no hosts)
// Input: Valid Masscan XML structure but no host elements
// Expected: MasscanScanData with empty hosts list
contract_test_empty_xml {
    given: "Masscan XML with no hosts"
    val xml = """
        <?xml version="1.0"?>
        <nmaprun scanner="masscan" start="1759560572" version="1.0-BETA">
          <scaninfo type="syn" protocol="tcp" />
          <runstats>
            <finished time="1759560583" elapsed="11" />
          </runstats>
        </nmaprun>
    """.toByteArray()

    when: "Parsing the XML"
    val result = masscanParserService.parseMasscanXml(xml)

    then: "Should return scan data with empty hosts list"
    assert result.hosts.isEmpty()
    assert result.scanDate != null
}

// Test Case 8: Timestamp conversion
// Input: Various epoch timestamp formats
// Expected: Correct LocalDateTime conversion
contract_test_timestamp_conversion {
    given: "Masscan XML with known epoch timestamp"
    val xml = """
        <nmaprun scanner="masscan" start="1759560572">
          <host endtime="1759560572">
            <address addr="192.168.1.1" addrtype="ipv4"/>
            <ports>
              <port protocol="tcp" portid="80">
                <state state="open"/>
              </port>
            </ports>
          </host>
        </nmaprun>
    """.toByteArray()

    when: "Parsing the XML"
    val result = masscanParserService.parseMasscanXml(xml)

    then: "Should convert epoch 1759560572 to correct LocalDateTime"
    // 1759560572 seconds since epoch = 2025-10-04 08:49:32 UTC
    assert result.scanDate.year == 2025
    assert result.scanDate.monthValue == 10
    assert result.scanDate.dayOfMonth == 4
    assert result.hosts[0].timestamp.year == 2025
}

// ============================================================================
// INTEGRATION TEST CONTRACT
// ============================================================================

/**
 * MasscanImportIntegrationTest
 *
 * End-to-end test covering full import workflow:
 * 1. Upload Masscan XML file via API endpoint
 * 2. Verify assets created/updated in database
 * 3. Verify scan results created with correct data
 * 4. Verify response contains accurate counts
 *
 * Test Environment:
 * - Uses test database with rollback after test
 * - Authenticated user (JWT token)
 * - Test data: testdata/masscan.xml
 *
 * Test Coverage:
 * - ✅ Upload valid Masscan XML, verify assets created
 * - ✅ Upload Masscan XML with existing asset IP, verify lastSeen updated
 * - ✅ Verify only open ports imported (closed/filtered skipped)
 * - ✅ Verify duplicate ports create separate scan results
 * - ✅ Verify default values applied (owner, type, name, description)
 * - ✅ Upload invalid XML, verify 400 error response
 * - ✅ Upload without authentication, verify 401 error
 * - ✅ Upload oversized file, verify 400 error
 * - ✅ Upload non-XML file, verify 400 error
 */

contract_integration_test_full_import_workflow {
    // Test will be implemented in MasscanImportIntegrationTest.kt
    // Verifies: API → Parser → Repository → Database → Response
}

// ============================================================================
// CONTRACT COMPLIANCE CHECKLIST
// ============================================================================

/**
 * Before marking implementation complete, verify:
 *
 * Security:
 * - [ ] XXE attacks prevented (external entities disabled)
 * - [ ] File size validation (max 10MB)
 * - [ ] Input sanitization (IP address, port numbers)
 * - [ ] Authentication enforced (@Secured annotation)
 *
 * Functional:
 * - [ ] Parse valid Masscan XML correctly
 * - [ ] Filter ports (only state="open")
 * - [ ] Convert timestamps (epoch → LocalDateTime)
 * - [ ] Create assets with default values
 * - [ ] Update existing assets (lastSeen only)
 * - [ ] Create separate scan results (no deduplication)
 * - [ ] Handle errors gracefully (continue on failures)
 *
 * Quality:
 * - [ ] Unit tests pass (parser tests)
 * - [ ] Integration tests pass (E2E workflow)
 * - [ ] Code coverage ≥80%
 * - [ ] Linting passes (ktlint)
 * - [ ] No security vulnerabilities (security scan)
 *
 * Documentation:
 * - [ ] API contract documented (OpenAPI YAML)
 * - [ ] Code comments reference feature/FR numbers
 * - [ ] Error messages are user-friendly
 */
