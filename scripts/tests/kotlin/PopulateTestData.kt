import java.sql.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*
import kotlin.system.exitProcess

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

// Data classes for structured data
data class DatabaseConfig(
    val url: String = "jdbc:mariadb://localhost:3306/secman",
    val username: String = "secman",
    val password: String = "CHANGEME"
)

data class AppCredentials(
    val username: String = "testadmin",
    val password: String = "testpass"
)

data class User(
    val username: String,
    val email: String,
    val password: String
)

data class Asset(
    val name: String,
    val type: String,
    val ip: String,
    val owner: String,
    val description: String
)

data class Standard(
    val name: String,
    val description: String
)

data class Norm(
    val name: String,
    val version: String,
    val year: Int
)

data class Requirement(
    val shortreq: String,
    val description: String,
    val language: String,
    val example: String,
    val motivation: String,
    val usecase: String,
    val norm: String,
    val chapter: String
)

data class Risk(
    val name: String,
    val description: String,
    val likelihood: Int,
    val impact: Int,
    val severity: String,
    val owner: String
)

// Sealed class for command line arguments
sealed class CliArgument {
    data class Username(val value: String) : CliArgument()
    data class Password(val value: String) : CliArgument()
    object Help : CliArgument()
    data class Unknown(val arg: String) : CliArgument()
}

// Risk level calculation enum
enum class RiskLevel(val value: Int) {
    LOW(1), MEDIUM(2), HIGH(3), CRITICAL(4);

    companion object {
        fun calculate(likelihood: Int, impact: Int): RiskLevel {
            val product = likelihood * impact
            return when {
                product <= 4 -> LOW
                product <= 9 -> MEDIUM
                product <= 15 -> HIGH
                else -> CRITICAL
            }
        }
    }
}

class PopulateTestData(
    private val dbConfig: DatabaseConfig = DatabaseConfig(),
    private var appCredentials: AppCredentials = AppCredentials()
) {

    private lateinit var connection: Connection

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val arguments = parseArguments(args)
            var credentials = AppCredentials()

            arguments.forEach { arg ->
                when (arg) {
                    is CliArgument.Username -> credentials = credentials.copy(username = arg.value)
                    is CliArgument.Password -> credentials = credentials.copy(password = arg.value)
                    is CliArgument.Help -> {
                        printUsage()
                        exitProcess(0)
                    }
                    is CliArgument.Unknown -> {
                        System.err.println("Unknown argument: ${arg.arg}")
                        printUsage()
                        exitProcess(1)
                    }
                }
            }

            runCatching {
                PopulateTestData(appCredentials = credentials).run()
            }.onFailure { e ->
                System.err.println("Error populating test data: ${e.message}")
                e.printStackTrace()
                exitProcess(1)
            }
        }

        private fun parseArguments(args: Array<String>): List<CliArgument> {
            val result = mutableListOf<CliArgument>()
            var i = 0

            while (i < args.size) {
                when (args[i]) {
                    "--username", "-u" -> {
                        if (i + 1 < args.size) {
                            result.add(CliArgument.Username(args[++i]))
                        } else {
                            System.err.println("Error: --username requires a value")
                            printUsage()
                            exitProcess(1)
                        }
                    }
                    "--password", "-p" -> {
                        if (i + 1 < args.size) {
                            result.add(CliArgument.Password(args[++i]))
                        } else {
                            System.err.println("Error: --password requires a value")
                            printUsage()
                            exitProcess(1)
                        }
                    }
                    "--help", "-h" -> result.add(CliArgument.Help)
                    else -> result.add(CliArgument.Unknown(args[i]))
                }
                i++
            }
            return result
        }

        private fun printUsage() {
            println("""
                Usage: kotlin PopulateTestData.kt [OPTIONS]
                
                OPTIONS:
                  -u, --username <username>  Application username (default: testadmin)
                  -p, --password <password>  Application password (default: testpass)
                  -h, --help                 Show this help message
                
                This script populates meaningful test data for the secman tool.
                The username and password are for application users, not database credentials.
            """.trimIndent())
        }
    }

    fun run() {
        println("=== Secman Test Data Population Script (Kotlin) ===")
        println("App Username: ${appCredentials.username}")
        println("App Password: ${appCredentials.password}")
        println()

        connectToDatabase()

        connection.use {
            if (!confirmPopulation()) {
                println("Operation cancelled.")
                return
            }

            println("Populating test data...")

            // Use sequence for ordered execution
            sequenceOf(
                ::populateUsers,
                ::populateAssets,
                ::populateStandards,
                ::populateUseCases,
                ::linkStandardToUseCase,
                ::populateNorms,
                ::populateRequirements,
                ::populateRisks,
                ::populateRiskAssessments
            ).forEach { operation ->
                operation()
            }

            println("\n✅ Test data population completed successfully!")
            println("""
                You can now log in with:
                  Username: ${appCredentials.username}
                  Password: ${appCredentials.password}
                  Role: ADMIN
            """.trimIndent())
        }
    }

    private fun connectToDatabase() {
        println("Connecting to database...")

        runCatching {
            Class.forName("org.mariadb.jdbc.Driver")
        }.onFailure {
            System.err.println("""
                ERROR: MariaDB JDBC driver not found in classpath.
                
                This usually means the MariaDB connector JAR file is missing.
                The script should have automatically downloaded it.
                
                Manual solutions:
                1. Re-run the populate-testdata-kotlin.sh script (recommended)
                2. Download manually from:
                   https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.3.2/mariadb-java-client-3.3.2.jar
                3. Place in: ../src/backend/lib/mariadb-java-client.jar
            """.trimIndent())
            throw SQLException("MariaDB JDBC driver not found in classpath", it)
        }

        runCatching {
            connection = DriverManager.getConnection(dbConfig.url, dbConfig.username, dbConfig.password)
            connection.autoCommit = false
            println("Connected to database successfully.")
        }.onFailure { e ->
            System.err.println("""
                ERROR: Failed to connect to database.
                
                Database details:
                  URL: ${dbConfig.url}
                  Username: ${dbConfig.username}
                  Password: ${dbConfig.password}
                
                Possible solutions:
                1. Ensure MariaDB is running: sudo systemctl start mariadb
                2. Check if database exists: mysql -u ${dbConfig.username} -p secman
                3. Run database setup: ./install.sh
                4. Verify credentials in src/backend/conf/application.conf
                
                Original error: ${e.message}
            """.trimIndent())
            throw e
        }
    }

    private fun confirmPopulation(): Boolean {
        val userCount = connection.prepareStatement("SELECT COUNT(*) FROM users").use { stmt ->
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

        val message = if (userCount > 0) {
            "WARNING: Database contains $userCount users.\nThis script will add more test data to the existing database."
        } else {
            "Database appears to be empty. Ready to populate with test data."
        }

        println(message)
        print("Do you want to proceed? (y/N): ")

        return Scanner(System.`in`).nextLine().trim().lowercase().let { response ->
            response == "y" || response == "yes"
        }
    }

    private fun populateUsers() {
        println("Creating users...")

        val now = LocalDateTime.now()
        var createdUsers = 0

        // Create admin user
        val adminUserId = createUserIfNotExists(
            User(appCredentials.username, "${appCredentials.username}@secman.local", appCredentials.password),
            now
        )

        when {
            adminUserId > 0 -> {
                createdUsers++
                println("✓ Created admin user: ${appCredentials.username}")
            }
            else -> {
                println("⚠ User '${appCredentials.username}' already exists, skipping")
            }
        }

        val actualAdminUserId = if (adminUserId > 0) adminUserId else getUserId(appCredentials.username)

        // Regular test users
        val testUsers = listOf(
            User("test_analyst", "test_analyst@secman.local", "password123"),
            User("test_auditor", "test_auditor@secman.local", "password123"),
            User("test_riskmanager", "test_riskmanager@secman.local", "password123"),
            User("test_compliance", "test_compliance@secman.local", "password123"),
            User("test_security", "test_security@secman.local", "password123")
        )

        testUsers.forEach { user ->
            val userId = createUserIfNotExists(user, now)
            if (userId > 0) {
                createdUsers++
                println("✓ Created user: ${user.username}")
            } else {
                println("⚠ User '${user.username}' already exists, skipping")
            }
        }

        populateUserRoles(actualAdminUserId)
        connection.commit()

        val message = if (createdUsers > 0) {
            "✓ Created $createdUsers new users"
        } else {
            "✓ All users already exist, roles updated"
        }
        println(message)
    }

    private fun createUserIfNotExists(user: User, now: LocalDateTime): Long {
        // Check if user exists
        val exists = connection.prepareStatement("SELECT id FROM users WHERE username = ?").use { stmt ->
            stmt.setString(1, user.username)
            stmt.executeQuery().use { rs -> rs.next() }
        }

        if (exists) return -1 // User already exists

        // Create new user
        return connection.prepareStatement(
            "INSERT INTO users (username, email, password_hash, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS
        ).use { stmt ->
            stmt.apply {
                setString(1, user.username)
                setString(2, user.email)
                setString(3, hashPassword(user.password))
                setTimestamp(4, Timestamp.valueOf(now))
                setTimestamp(5, Timestamp.valueOf(now))
                executeUpdate()
            }

            stmt.generatedKeys.use { keys ->
                keys.next()
                keys.getLong(1)
            }
        }
    }

    private fun getUserId(username: String): Long {
        return connection.prepareStatement("SELECT id FROM users WHERE username = ?").use { stmt ->
            stmt.setString(1, username)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getLong(1)
                else throw SQLException("User not found: $username")
            }
        }
    }

    private fun populateUserRoles(adminUserId: Long) {
        assignRoleIfNotExists(adminUserId, "ADMIN")

        // Assign USER role to test users and admin
        connection.prepareStatement(
            "SELECT id FROM users WHERE username = ? OR username LIKE 'test_%'"
        ).use { stmt ->
            stmt.setString(1, appCredentials.username)
            stmt.executeQuery().use { rs ->
                generateSequence { if (rs.next()) rs.getLong(1) else null }
                    .forEach { userId -> assignRoleIfNotExists(userId, "USER") }
            }
        }
    }

    private fun assignRoleIfNotExists(userId: Long, roleName: String) {
        val exists = connection.prepareStatement(
            "SELECT COUNT(*) FROM user_roles WHERE user_id = ? AND role_name = ?"
        ).use { stmt ->
            stmt.setLong(1, userId)
            stmt.setString(2, roleName)
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1) > 0
            }
        }

        if (!exists) {
            connection.prepareStatement(
                "INSERT INTO user_roles (user_id, role_name) VALUES (?, ?)"
            ).use { stmt ->
                stmt.setLong(1, userId)
                stmt.setString(2, roleName)
                stmt.executeUpdate()
            }
        }
    }

    private fun populateAssets() {
        println("Creating assets...")

        val assets = listOf(
            Asset("Web Server 01", "Server", "192.168.1.10", "IT Operations", "Primary web application server hosting customer portal"),
            Asset("Database Server 01", "Database", "192.168.1.20", "DBA Team", "Production database server containing customer data"),
            Asset("API Gateway", "Network Device", "192.168.1.30", "Network Team", "External API gateway for partner integrations"),
            Asset("Employee Laptops", "Endpoint", "DHCP Range", "IT Security", "Corporate laptops for remote employees"),
            Asset("File Server", "Server", "192.168.1.40", "IT Operations", "Central file storage for document management"),
            Asset("Email Server", "Server", "192.168.1.50", "IT Operations", "Corporate email and communication platform"),
            Asset("Backup Storage", "Storage", "192.168.1.60", "Backup Team", "Centralized backup storage system"),
            Asset("Mobile Application", "Application", "N/A", "Development Team", "Customer mobile application for iOS and Android"),
            Asset("Payment Gateway", "Service", "External", "Finance IT", "Third-party payment processing service"),
            Asset("VPN Infrastructure", "Network Device", "192.168.1.70", "Network Security", "Remote access VPN for employees")
        )

        insertAssets(assets)
        println("✓ Created ${assets.size} assets")
    }

    private fun insertAssets(assets: List<Asset>) {
        val sql = "INSERT INTO asset (name, type, ip, owner, description, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
        val now = LocalDateTime.now()

        connection.prepareStatement(sql).use { stmt ->
            assets.forEach { asset ->
                stmt.apply {
                    setString(1, asset.name)
                    setString(2, asset.type)
                    setString(3, asset.ip)
                    setString(4, asset.owner)
                    setString(5, asset.description)
                    setTimestamp(6, Timestamp.valueOf(now))
                    setTimestamp(7, Timestamp.valueOf(now))
                    executeUpdate()
                }
            }
        }
        connection.commit()
    }

    private fun populateStandards() {
        println("Creating standards...")

        val standard = Standard(
            "External hosting",
            "Security requirements and controls for external hosting services and SAAS platforms"
        )

        insertStandard(standard)
        println("✓ Created 1 standard")
    }

    private fun insertStandard(standard: Standard) {
        val sql = "INSERT INTO standard (name, description, created_at, updated_at) VALUES (?, ?, ?, ?)"
        val now = LocalDateTime.now()

        connection.prepareStatement(sql).use { stmt ->
            stmt.apply {
                setString(1, standard.name)
                setString(2, standard.description)
                setTimestamp(3, Timestamp.valueOf(now))
                setTimestamp(4, Timestamp.valueOf(now))
                executeUpdate()
            }
        }
        connection.commit()
    }

    private fun populateUseCases() {
        println("Creating use cases...")

        val useCase = "SAAS"
        val now = LocalDateTime.now()

        connection.prepareStatement("INSERT INTO usecase (name, created_at, updated_at) VALUES (?, ?, ?)").use { stmt ->
            stmt.apply {
                setString(1, useCase)
                setTimestamp(2, Timestamp.valueOf(now))
                setTimestamp(3, Timestamp.valueOf(now))
                executeUpdate()
            }
        }

        connection.commit()
        println("✓ Created 1 use case")
    }

    private fun linkStandardToUseCase() {
        println("Linking standard to use case...")

        val standardId = getEntityId("standard", "External hosting")
        val useCaseId = getEntityId("usecase", "SAAS")

        connection.prepareStatement(
            "INSERT INTO standard_usecase (standard_id, usecase_id) VALUES (?, ?)"
        ).use { stmt ->
            stmt.setLong(1, standardId)
            stmt.setLong(2, useCaseId)
            stmt.executeUpdate()
        }

        connection.commit()
        println("✓ Linked 'External hosting' standard to 'SAAS' use case")
    }

    private fun getEntityId(tableName: String, name: String): Long {
        return connection.prepareStatement("SELECT id FROM $tableName WHERE name = ?").use { stmt ->
            stmt.setString(1, name)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getLong(1)
                else throw SQLException("$tableName '$name' not found")
            }
        }
    }

    private fun populateNorms() {
        println("Creating norms...")

        val norm = Norm("ISO27001", "2022", 2022)
        insertNorm(norm)
        println("✓ Created 1 norm")
    }

    private fun insertNorm(norm: Norm) {
        val sql = "INSERT INTO norm (name, version, year, created_at, updated_at) VALUES (?, ?, ?, ?, ?)"
        val now = LocalDateTime.now()

        connection.prepareStatement(sql).use { stmt ->
            stmt.apply {
                setString(1, norm.name)
                setString(2, norm.version)
                setInt(3, norm.year)
                setTimestamp(4, Timestamp.valueOf(now))
                setTimestamp(5, Timestamp.valueOf(now))
                executeUpdate()
            }
        }
        connection.commit()
    }

    private fun populateRequirements() {
        println("Creating requirements...")

        val requirements = listOf(
            Requirement(
                "SAAS Data Protection",
                "All customer data processed by SAAS applications must be encrypted both in transit and at rest using industry-standard encryption methods.",
                "en",
                "Customer data is encrypted using AES-256 for data at rest and TLS 1.3 for data in transit between client and server.",
                "Data protection is critical for SAAS providers to maintain customer trust and comply with privacy regulations.",
                "SAAS",
                "ISO27001",
                "Information Security"
            ),
            Requirement(
                "External Hosting Security",
                "All externally hosted services must implement multi-factor authentication and regular security assessments to ensure data protection.",
                "en",
                "SAAS platforms require MFA for all administrative access and undergo quarterly penetration testing by certified security firms.",
                "External hosting introduces additional risks that must be mitigated through comprehensive security controls.",
                "SAAS",
                "ISO27001",
                "Access Control"
            ),
            Requirement(
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

        insertRequirements(requirements)
        println("✓ Created ${requirements.size} requirements")
    }

    private fun insertRequirements(requirements: List<Requirement>) {
        val sql = "INSERT INTO requirement (shortreq, description, language, example, motivation, usecase, norm, chapter, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val now = LocalDateTime.now()

        connection.prepareStatement(sql).use { stmt ->
            requirements.forEach { req ->
                stmt.apply {
                    setString(1, req.shortreq)
                    setString(2, req.description)
                    setString(3, req.language)
                    setString(4, req.example)
                    setString(5, req.motivation)
                    setString(6, req.usecase)
                    setString(7, req.norm)
                    setString(8, req.chapter)
                    setTimestamp(9, Timestamp.valueOf(now))
                    setTimestamp(10, Timestamp.valueOf(now))
                    executeUpdate()
                }
            }
        }
        connection.commit()
    }

    private fun populateRisks() {
        println("Creating risks...")

        val risks = listOf(
            Risk("Data Breach via SQL Injection", "Vulnerability in web application could allow unauthorized data access", 3, 5, "HIGH", "riskmanager"),
            Risk("Unpatched Operating System", "Critical security patches not applied in timely manner", 4, 4, "HIGH", "securityeng"),
            Risk("Unauthorized Physical Access", "Inadequate physical security controls in server room", 2, 4, "MEDIUM", "IT Operations"),
            Risk("Phishing Attack Success", "Employees clicking malicious links leading to credential theft", 4, 3, "MEDIUM", "analyst1"),
            Risk("Third-Party Data Exposure", "Vendor security incident affecting our customer data", 2, 5, "MEDIUM", "complianceofficer")
        )

        val createdRisks = insertRisks(risks)
        println("✓ Created $createdRisks risks")
    }

    private fun insertRisks(risks: List<Risk>): Int {
        val sql = "INSERT INTO risk (asset_id, name, description, likelihood, impact, risk_level, status, owner, severity, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val now = LocalDateTime.now()
        var createdCount = 0

        connection.prepareStatement("SELECT id FROM asset ORDER BY id LIMIT 5").use { assetStmt ->
            assetStmt.executeQuery().use { assetRs ->
                connection.prepareStatement(sql).use { stmt ->

                    risks.forEachIndexed { index, risk ->
                        if (assetRs.next()) {
                            val assetId = assetRs.getLong(1)
                            val riskLevel = RiskLevel.calculate(risk.likelihood, risk.impact)

                            stmt.apply {
                                setLong(1, assetId)
                                setString(2, risk.name)
                                setString(3, risk.description)
                                setInt(4, risk.likelihood)
                                setInt(5, risk.impact)
                                setInt(6, riskLevel.value)
                                setString(7, "OPEN")
                                setString(8, risk.owner)
                                setString(9, risk.severity)
                                setTimestamp(10, Timestamp.valueOf(now))
                                setTimestamp(11, Timestamp.valueOf(now))
                                executeUpdate()
                            }
                            createdCount++
                        }
                    }
                }
            }
        }

        connection.commit()
        return createdCount
    }

    private fun populateRiskAssessments() {
        println("Creating risk assessments...")

        val today = LocalDate.now()
        val now = LocalDateTime.now()

        // Get first few users and assets
        val userIds = getEntityIds("users", 3)
        val assetIds = getEntityIds("asset", 3)

        if (userIds.isNotEmpty() && assetIds.isNotEmpty()) {
            val assessorId = userIds.first()
            val requestorId = userIds.getOrElse(1) { assessorId }

            val assessments = listOf(
                Triple(assetIds.first(), "STARTED", "Quarterly risk assessment for critical infrastructure"),
                Triple(assetIds.getOrNull(1) ?: assetIds.first(), "IN_PROGRESS", "Security audit following recent policy updates")
            )

            insertRiskAssessments(assessments, assessorId, requestorId, today, now)
        }

        println("✓ Created risk assessments")
    }

    private fun getEntityIds(tableName: String, limit: Int): List<Long> {
        return connection.prepareStatement("SELECT id FROM $tableName ORDER BY id LIMIT $limit").use { stmt ->
            stmt.executeQuery().use { rs ->
                generateSequence { if (rs.next()) rs.getLong(1) else null }.toList()
            }
        }
    }

    private fun insertRiskAssessments(
        assessments: List<Triple<Long, String, String>>,
        assessorId: Long,
        requestorId: Long,
        today: LocalDate,
        now: LocalDateTime
    ) {
        val sql = "INSERT INTO risk_assessment (asset_id, start_date, end_date, status, assessor_id, requestor_id, notes, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"

        connection.prepareStatement(sql).use { stmt ->
            assessments.forEachIndexed { index, (assetId, status, notes) ->
                val startDate = if (index == 1) today.minusDays(15) else today
                val endDate = if (index == 1) today.plusDays(15) else today.plusDays(30)
                val createdAt = if (index == 1) now.minusDays(15) else now

                stmt.apply {
                    setLong(1, assetId)
                    setDate(2, Date.valueOf(startDate))
                    setDate(3, Date.valueOf(endDate))
                    setString(4, status)
                    setLong(5, assessorId)
                    setLong(6, requestorId)
                    setString(7, notes)
                    setTimestamp(8, Timestamp.valueOf(createdAt))
                    setTimestamp(9, Timestamp.valueOf(now))
                    executeUpdate()
                }
            }
        }
        connection.commit()
    }

    private fun hashPassword(password: String): String {
        // Simple hash for demo purposes - in production use proper BCrypt
        return "hashed_${password}_${password.hashCode()}"
    }
}
