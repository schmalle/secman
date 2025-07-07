import java.sql.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*

/**
 * Test Data Population Script for Secman (Kotlin Version)
 *
 * This script populates meaningful test data into an empty secman instance.
 * It creates users, assets, standards, use cases, requirements, risks, and risk assessments
 * with realistic relationships between entities.
 *
 * Usage:
 *   kotlin PopulateTestData.kt [--username <username>] [--password <password>]
 *
 * If no credentials are provided, defaults to testadmin/testpass
 */
class PopulateTestData {

    companion object {
        private const val DB_URL = "jdbc:mariadb://localhost:3306/secman"
        private const val DB_USER = "secman"
        private const val DB_PASS = "CHANGEME"

        private var appUsername = "testadmin"
        private var appPassword = "testpass"

        @JvmStatic
        fun main(args: Array<String>) {
            // Parse command line arguments
            parseArguments(args)

            val populator = PopulateTestData()
            try {
                populator.run()
            } catch (e: Exception) {
                System.err.println("Error populating test data: ${e.message}")
                e.printStackTrace()
                kotlin.system.exitProcess(1)
            }
        }

        private fun parseArguments(args: Array<String>) {
            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--username", "-u" -> {
                        if (i + 1 < args.size) {
                            appUsername = args[++i]
                        } else {
                            System.err.println("Error: --username requires a value")
                            printUsage()
                            kotlin.system.exitProcess(1)
                        }
                    }
                    "--password", "-p" -> {
                        if (i + 1 < args.size) {
                            appPassword = args[++i]
                        } else {
                            System.err.println("Error: --password requires a value")
                            printUsage()
                            kotlin.system.exitProcess(1)
                        }
                    }
                    "--help", "-h" -> {
                        printUsage()
                        kotlin.system.exitProcess(0)
                    }
                    else -> {
                        System.err.println("Unknown argument: ${args[i]}")
                        printUsage()
                        kotlin.system.exitProcess(1)
                    }
                }
                i++
            }
        }

        private fun printUsage() {
            println("Usage: kotlin PopulateTestData.kt [OPTIONS]")
            println()
            println("OPTIONS:")
            println("  -u, --username <username>  Application username (default: testadmin)")
            println("  -p, --password <password>  Application password (default: testpass)")
            println("  -h, --help                 Show this help message")
            println()
            println("This script populates meaningful test data for the secman tool.")
            println("The username and password are for application users, not database credentials.")
        }
    }

    private lateinit var connection: Connection

    fun run() {
        println("=== Secman Test Data Population Script (Kotlin) ===")
        println("App Username: $appUsername")
        println("App Password: $appPassword")
        println()

        // Connect to database
        connectToDatabase()

        try {
            // Check if database is empty (or if we should proceed)
            if (!confirmPopulation()) {
                println("Operation cancelled.")
                return
            }

            // Populate data in dependency order
            println("Populating test data...")

            populateUsers()
            populateAssets()
            populateStandards()
            populateUseCases()
            linkStandardToUseCase()
            populateNorms()
            populateRequirements()
            populateRisks()
            populateRiskAssessments()

            println("\n✅ Test data population completed successfully!")
            println("\nYou can now log in with:")
            println("  Username: $appUsername")
            println("  Password: $appPassword")
            println("  Role: ADMIN")

        } finally {
            connection.close()
        }
    }

    private fun connectToDatabase() {
        println("Connecting to database...")
        try {
            Class.forName("org.mariadb.jdbc.Driver")
        } catch (e: ClassNotFoundException) {
            System.err.println("")
            System.err.println("ERROR: MariaDB JDBC driver not found in classpath.")
            System.err.println("")
            System.err.println("This usually means the MariaDB connector JAR file is missing.")
            System.err.println("The script should have automatically downloaded it.")
            System.err.println("")
            System.err.println("Manual solutions:")
            System.err.println("1. Re-run the populate-testdata-kotlin.sh script (recommended)")
            System.err.println("2. Download manually from:")
            System.err.println("   https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.3.2/mariadb-java-client-3.3.2.jar")
            System.err.println("3. Place in: ../src/backend/lib/mariadb-java-client.jar")
            System.err.println("")
            throw SQLException("MariaDB JDBC driver not found in classpath", e)
        }

        try {
            connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)
            connection.autoCommit = false
            println("Connected to database successfully.")
        } catch (e: SQLException) {
            System.err.println("")
            System.err.println("ERROR: Failed to connect to database.")
            System.err.println("")
            System.err.println("Database details:")
            System.err.println("  URL: $DB_URL")
            System.err.println("  Username: $DB_USER")
            System.err.println("  Password: $DB_PASS")
            System.err.println("")
            System.err.println("Possible solutions:")
            System.err.println("1. Ensure MariaDB is running: sudo systemctl start mariadb")
            System.err.println("2. Check if database exists: mysql -u $DB_USER -p secman")
            System.err.println("3. Run database setup: ./install.sh")
            System.err.println("4. Verify credentials in src/backend/conf/application.conf")
            System.err.println("")
            System.err.println("Original error: ${e.message}")
            throw e
        }
    }

    private fun confirmPopulation(): Boolean {
        // Check if users table has any data
        val stmt = connection.prepareStatement("SELECT COUNT(*) FROM users")
        val rs = stmt.executeQuery()
        rs.next()
        val userCount = rs.getInt(1)
        rs.close()
        stmt.close()

        if (userCount > 0) {
            println("WARNING: Database contains $userCount users.")
            println("This script will add more test data to the existing database.")
        } else {
            println("Database appears to be empty. Ready to populate with test data.")
        }

        print("Do you want to proceed? (y/N): ")
        val scanner = Scanner(System.`in`)
        val response = scanner.nextLine().trim().lowercase()
        return response == "y" || response == "yes"
    }

    private fun populateUsers() {
        println("Creating users...")

        val now = LocalDateTime.now()
        var createdUsers = 0

        // Create admin user (with provided credentials) if not exists
        var adminUserId = createUserIfNotExists(appUsername, "$appUsername@secman.local", appPassword, now)
        if (adminUserId > 0) {
            createdUsers++
            println("✓ Created admin user: $appUsername")
        } else {
            println("⚠ User '$appUsername' already exists, skipping")
            // Get existing user ID for role assignment
            adminUserId = getUserId(appUsername)
        }

        // Regular test users - use test_ prefix to avoid conflicts
        val users = arrayOf(
            arrayOf("test_analyst", "test_analyst@secman.local", "password123"),
            arrayOf("test_auditor", "test_auditor@secman.local", "password123"),
            arrayOf("test_riskmanager", "test_riskmanager@secman.local", "password123"),
            arrayOf("test_compliance", "test_compliance@secman.local", "password123"),
            arrayOf("test_security", "test_security@secman.local", "password123")
        )

        for (user in users) {
            val userId = createUserIfNotExists(user[0], user[1], user[2], now)
            if (userId > 0) {
                createdUsers++
                println("✓ Created user: ${user[0]}")
            } else {
                println("⚠ User '${user[0]}' already exists, skipping")
            }
        }

        // Assign roles to all users (existing and new)
        populateUserRoles(adminUserId)

        connection.commit()
        if (createdUsers > 0) {
            println("✓ Created $createdUsers new users")
        } else {
            println("✓ All users already exist, roles updated")
        }
    }

    private fun createUserIfNotExists(username: String, email: String, password: String, now: LocalDateTime): Long {
        // Check if user already exists
        val checkStmt = connection.prepareStatement("SELECT id FROM users WHERE username = ?")
        checkStmt.setString(1, username)
        val rs = checkStmt.executeQuery()

        if (rs.next()) {
            // User exists
            rs.close()
            checkStmt.close()
            return -1 // Indicate user already exists
        }
        rs.close()
        checkStmt.close()

        // Create new user
        val sql = "INSERT INTO users (username, email, password_hash, created_at, updated_at) VALUES (?, ?, ?, ?, ?)"
        val stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)

        stmt.setString(1, username)
        stmt.setString(2, email)
        stmt.setString(3, hashPassword(password))
        stmt.setTimestamp(4, Timestamp.valueOf(now))
        stmt.setTimestamp(5, Timestamp.valueOf(now))
        stmt.executeUpdate()

        val generatedKeys = stmt.generatedKeys
        generatedKeys.next()
        val userId = generatedKeys.getLong(1)
        generatedKeys.close()
        stmt.close()

        return userId
    }

    private fun getUserId(username: String): Long {
        val stmt = connection.prepareStatement("SELECT id FROM users WHERE username = ?")
        stmt.setString(1, username)
        val rs = stmt.executeQuery()

        if (rs.next()) {
            val userId = rs.getLong(1)
            rs.close()
            stmt.close()
            return userId
        }

        rs.close()
        stmt.close()
        throw SQLException("User not found: $username")
    }

    private fun populateUserRoles(adminUserId: Long) {
        // Admin user gets ADMIN role (if not already assigned)
        assignRoleIfNotExists(adminUserId, "ADMIN")

        // All users get USER role (including admin) - but only test users and the specified admin
        val userStmt = connection.prepareStatement(
            "SELECT id FROM users WHERE username = ? OR username LIKE 'test_%'"
        )
        userStmt.setString(1, appUsername)
        val rs = userStmt.executeQuery()

        while (rs.next()) {
            val userId = rs.getLong(1)
            assignRoleIfNotExists(userId, "USER")
        }

        rs.close()
        userStmt.close()
    }

    private fun assignRoleIfNotExists(userId: Long, roleName: String) {
        // Check if role already exists for this user
        val checkStmt = connection.prepareStatement(
            "SELECT COUNT(*) FROM user_roles WHERE user_id = ? AND role_name = ?"
        )
        checkStmt.setLong(1, userId)
        checkStmt.setString(2, roleName)
        val rs = checkStmt.executeQuery()

        rs.next()
        val count = rs.getInt(1)
        rs.close()
        checkStmt.close()

        if (count == 0) {
            // Role doesn't exist, create it
            val insertStmt = connection.prepareStatement(
                "INSERT INTO user_roles (user_id, role_name) VALUES (?, ?)"
            )
            insertStmt.setLong(1, userId)
            insertStmt.setString(2, roleName)
            insertStmt.executeUpdate()
            insertStmt.close()
        }
    }

    private fun populateAssets() {
        println("Creating assets...")

        val sql = "INSERT INTO asset (name, type, ip, owner, description, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
        val stmt = connection.prepareStatement(sql)

        val now = LocalDateTime.now()

        val assets = arrayOf(
            arrayOf("Web Server 01", "Server", "192.168.1.10", "IT Operations", "Primary web application server hosting customer portal"),
            arrayOf("Database Server 01", "Database", "192.168.1.20", "DBA Team", "Production database server containing customer data"),
            arrayOf("API Gateway", "Network Device", "192.168.1.30", "Network Team", "External API gateway for partner integrations"),
            arrayOf("Employee Laptops", "Endpoint", "DHCP Range", "IT Security", "Corporate laptops for remote employees"),
            arrayOf("File Server", "Server", "192.168.1.40", "IT Operations", "Central file storage for document management"),
            arrayOf("Email Server", "Server", "192.168.1.50", "IT Operations", "Corporate email and communication platform"),
            arrayOf("Backup Storage", "Storage", "192.168.1.60", "Backup Team", "Centralized backup storage system"),
            arrayOf("Mobile Application", "Application", "N/A", "Development Team", "Customer mobile application for iOS and Android"),
            arrayOf("Payment Gateway", "Service", "External", "Finance IT", "Third-party payment processing service"),
            arrayOf("VPN Infrastructure", "Network Device", "192.168.1.70", "Network Security", "Remote access VPN for employees")
        )

        for (asset in assets) {
            stmt.setString(1, asset[0])
            stmt.setString(2, asset[1])
            stmt.setString(3, asset[2])
            stmt.setString(4, asset[3])
            stmt.setString(5, asset[4])
            stmt.setTimestamp(6, Timestamp.valueOf(now))
            stmt.setTimestamp(7, Timestamp.valueOf(now))
            stmt.executeUpdate()
        }

        stmt.close()
        connection.commit()
        println("✓ Created ${assets.size} assets")
    }

    private fun populateStandards() {
        println("Creating standards...")

        val sql = "INSERT INTO standard (name, description, created_at, updated_at) VALUES (?, ?, ?, ?)"
        val stmt = connection.prepareStatement(sql)

        val now = LocalDateTime.now()

        // Single standard for external hosting
        stmt.setString(1, "External hosting")
        stmt.setString(2, "Security requirements and controls for external hosting services and SAAS platforms")
        stmt.setTimestamp(3, Timestamp.valueOf(now))
        stmt.setTimestamp(4, Timestamp.valueOf(now))
        stmt.executeUpdate()

        stmt.close()
        connection.commit()
        println("✓ Created 1 standard")
    }

    private fun populateUseCases() {
        println("Creating use cases...")

        val sql = "INSERT INTO usecase (name, created_at, updated_at) VALUES (?, ?, ?)"
        val stmt = connection.prepareStatement(sql)

        val now = LocalDateTime.now()

        // Single use case for SAAS
        stmt.setString(1, "SAAS")
        stmt.setTimestamp(2, Timestamp.valueOf(now))
        stmt.setTimestamp(3, Timestamp.valueOf(now))
        stmt.executeUpdate()

        stmt.close()
        connection.commit()
        println("✓ Created 1 use case")
    }

    private fun linkStandardToUseCase() {
        println("Linking standard to use case...")

        // Get the standard ID for "External hosting"
        val standardStmt = connection.prepareStatement("SELECT id FROM standard WHERE name = ?")
        standardStmt.setString(1, "External hosting")
        val standardRs = standardStmt.executeQuery()

        if (!standardRs.next()) {
            standardRs.close()
            standardStmt.close()
            throw SQLException("Standard 'External hosting' not found")
        }
        val standardId = standardRs.getLong(1)
        standardRs.close()
        standardStmt.close()

        // Get the use case ID for "SAAS"
        val useCaseStmt = connection.prepareStatement("SELECT id FROM usecase WHERE name = ?")
        useCaseStmt.setString(1, "SAAS")
        val useCaseRs = useCaseStmt.executeQuery()

        if (!useCaseRs.next()) {
            useCaseRs.close()
            useCaseStmt.close()
            throw SQLException("Use case 'SAAS' not found")
        }
        val useCaseId = useCaseRs.getLong(1)
        useCaseRs.close()
        useCaseStmt.close()

        // Link them in the junction table
        val linkStmt = connection.prepareStatement(
            "INSERT INTO standard_usecase (standard_id, usecase_id) VALUES (?, ?)"
        )
        linkStmt.setLong(1, standardId)
        linkStmt.setLong(2, useCaseId)
        linkStmt.executeUpdate()
        linkStmt.close()

        connection.commit()
        println("✓ Linked 'External hosting' standard to 'SAAS' use case")
    }

    private fun populateNorms() {
        println("Creating norms...")

        val sql = "INSERT INTO norm (name, version, year, created_at, updated_at) VALUES (?, ?, ?, ?, ?)"
        val stmt = connection.prepareStatement(sql)

        val now = LocalDateTime.now()

        // Single ISO27001 norm with 2022 data
        stmt.setString(1, "ISO27001")
        stmt.setString(2, "2022")
        stmt.setInt(3, 2022)
        stmt.setTimestamp(4, Timestamp.valueOf(now))
        stmt.setTimestamp(5, Timestamp.valueOf(now))
        stmt.executeUpdate()

        stmt.close()
        connection.commit()
        println("✓ Created 1 norm")
    }

    private fun populateRequirements() {
        println("Creating requirements...")

        val sql = "INSERT INTO requirement (shortreq, description, language, example, motivation, usecase, norm, chapter, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val stmt = connection.prepareStatement(sql)

        val now = LocalDateTime.now()

        val requirements = arrayOf(
            arrayOf(
                "SAAS Data Protection",
                "All customer data processed by SAAS applications must be encrypted both in transit and at rest using industry-standard encryption methods.",
                "en",
                "Customer data is encrypted using AES-256 for data at rest and TLS 1.3 for data in transit between client and server.",
                "Data protection is critical for SAAS providers to maintain customer trust and comply with privacy regulations.",
                "SAAS",
                "ISO27001",
                "Information Security"
            ),
            arrayOf(
                "External Hosting Security",
                "All externally hosted services must implement multi-factor authentication and regular security assessments to ensure data protection.",
                "en",
                "SAAS platforms require MFA for all administrative access and undergo quarterly penetration testing by certified security firms.",
                "External hosting introduces additional risks that must be mitigated through comprehensive security controls.",
                "SAAS",
                "ISO27001",
                "Access Control"
            ),
            arrayOf(
                "SAAS Availability Requirements",
                "SAAS platforms must maintain 99.9% uptime with documented disaster recovery procedures and regular backup testing.",
                "en",
                "Service level agreements specify maximum 43 minutes of downtime per month with automated failover to secondary data centers.",
                "High availability is essential for SAAS services as customers depend on continuous access to their data and applications.",
                "SAAS",
                "ISO27001",
                "Business Continuity"
            )
        )

        for (req in requirements) {
            stmt.setString(1, req[0])
            stmt.setString(2, req[1])
            stmt.setString(3, req[2])
            stmt.setString(4, req[3])
            stmt.setString(5, req[4])
            stmt.setString(6, req[5])
            stmt.setString(7, req[6])
            stmt.setString(8, req[7])
            stmt.setTimestamp(9, Timestamp.valueOf(now))
            stmt.setTimestamp(10, Timestamp.valueOf(now))
            stmt.executeUpdate()
        }

        stmt.close()
        connection.commit()
        println("✓ Created ${requirements.size} requirements")
    }

    private fun populateRisks() {
        println("Creating risks...")

        val sql = "INSERT INTO risk (asset_id, name, description, likelihood, impact, risk_level, status, owner, severity, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val stmt = connection.prepareStatement(sql)

        // Get asset IDs
        val assetStmt = connection.prepareStatement("SELECT id, name FROM asset ORDER BY id LIMIT 5")
        val assetRs = assetStmt.executeQuery()

        val now = LocalDateTime.now()

        val risks = arrayOf(
            arrayOf("Data Breach via SQL Injection", "Vulnerability in web application could allow unauthorized data access", "3", "5", "HIGH", "riskmanager"),
            arrayOf("Unpatched Operating System", "Critical security patches not applied in timely manner", "4", "4", "HIGH", "securityeng"),
            arrayOf("Unauthorized Physical Access", "Inadequate physical security controls in server room", "2", "4", "MEDIUM", "IT Operations"),
            arrayOf("Phishing Attack Success", "Employees clicking malicious links leading to credential theft", "4", "3", "MEDIUM", "analyst1"),
            arrayOf("Third-Party Data Exposure", "Vendor security incident affecting our customer data", "2", "5", "MEDIUM", "complianceofficer")
        )

        var riskIndex = 0
        while (assetRs.next() && riskIndex < risks.size) {
            val assetId = assetRs.getLong(1)
            val risk = risks[riskIndex]

            val likelihood = risk[2].toInt()
            val impact = risk[3].toInt()
            val riskLevel = calculateRiskLevel(likelihood, impact)

            stmt.setLong(1, assetId)
            stmt.setString(2, risk[0])
            stmt.setString(3, risk[1])
            stmt.setInt(4, likelihood)
            stmt.setInt(5, impact)
            stmt.setInt(6, riskLevel)
            stmt.setString(7, "OPEN")
            stmt.setString(8, risk[5])
            stmt.setString(9, risk[4])
            stmt.setTimestamp(10, Timestamp.valueOf(now))
            stmt.setTimestamp(11, Timestamp.valueOf(now))
            stmt.executeUpdate()

            riskIndex++
        }

        assetRs.close()
        assetStmt.close()
        stmt.close()
        connection.commit()
        println("✓ Created $riskIndex risks")
    }

    private fun populateRiskAssessments() {
        println("Creating risk assessments...")

        // Get user IDs
        val userStmt = connection.prepareStatement("SELECT id, username FROM users ORDER BY id LIMIT 3")
        val userRs = userStmt.executeQuery()

        // Get asset IDs
        val assetStmt = connection.prepareStatement("SELECT id FROM asset ORDER BY id LIMIT 3")
        val assetRs = assetStmt.executeQuery()

        val sql = "INSERT INTO risk_assessment (asset_id, start_date, end_date, status, assessor_id, requestor_id, notes, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val stmt = connection.prepareStatement(sql)

        val today = LocalDate.now()
        val now = LocalDateTime.now()

        if (userRs.next() && assetRs.next()) {
            val assessorId = userRs.getLong(1)
            val requestorId = if (userRs.next()) userRs.getLong(1) else assessorId
            val assetId = assetRs.getLong(1)

            stmt.setLong(1, assetId)
            stmt.setDate(2, Date.valueOf(today))
            stmt.setDate(3, Date.valueOf(today.plusDays(30)))
            stmt.setString(4, "STARTED")
            stmt.setLong(5, assessorId)
            stmt.setLong(6, requestorId)
            stmt.setString(7, "Quarterly risk assessment for critical infrastructure")
            stmt.setTimestamp(8, Timestamp.valueOf(now))
            stmt.setTimestamp(9, Timestamp.valueOf(now))
            stmt.executeUpdate()

            // Second assessment if more assets available
            if (assetRs.next()) {
                stmt.setLong(1, assetRs.getLong(1))
                stmt.setDate(2, Date.valueOf(today.minusDays(15)))
                stmt.setDate(3, Date.valueOf(today.plusDays(15)))
                stmt.setString(4, "IN_PROGRESS")
                stmt.setString(7, "Security audit following recent policy updates")
                stmt.setTimestamp(8, Timestamp.valueOf(now.minusDays(15)))
                stmt.setTimestamp(9, Timestamp.valueOf(now))
                stmt.executeUpdate()
            }
        }

        userRs.close()
        userStmt.close()
        assetRs.close()
        assetStmt.close()
        stmt.close()
        connection.commit()
        println("✓ Created risk assessments")
    }

    private fun calculateRiskLevel(likelihood: Int, impact: Int): Int {
        val product = likelihood * impact
        return when {
            product <= 4 -> 1
            product <= 9 -> 2
            product <= 15 -> 3
            else -> 4
        }
    }

    private fun hashPassword(password: String): String {
        // Simple hash for demo purposes - in production use proper BCrypt
        return "hashed_${password}_${password.hashCode()}"
    }
}
