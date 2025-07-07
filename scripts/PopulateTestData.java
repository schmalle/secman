import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;

/**
 * Test Data Population Script for Secman
 * 
 * This script populates meaningful test data into an empty secman instance.
 * It creates users, assets, standards, use cases, requirements, risks, and risk assessments
 * with realistic relationships between entities.
 * 
 * Usage:
 *   java PopulateTestData.java [--username <username>] [--password <password>]
 *   
 * If no credentials are provided, defaults to adminuser/password
 */
public class PopulateTestData {
    
    private static final String DB_URL = "jdbc:mariadb://localhost:3306/secman";
    private static final String DB_USER = "secman";
    private static final String DB_PASS = "CHANGEME";
    
    private static String appUsername = "testadmin";
    private static String appPassword = "testpass";
    
    private Connection connection;
    
    public static void main(String[] args) {
        // Parse command line arguments
        parseArguments(args);
        
        PopulateTestData populator = new PopulateTestData();
        try {
            populator.run();
        } catch (Exception e) {
            System.err.println("Error populating test data: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void parseArguments(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--username":
                case "-u":
                    if (i + 1 < args.length) {
                        appUsername = args[++i];
                    } else {
                        System.err.println("Error: --username requires a value");
                        printUsage();
                        System.exit(1);
                    }
                    break;
                case "--password":
                case "-p":
                    if (i + 1 < args.length) {
                        appPassword = args[++i];
                    } else {
                        System.err.println("Error: --password requires a value");
                        printUsage();
                        System.exit(1);
                    }
                    break;
                case "--help":
                case "-h":
                    printUsage();
                    System.exit(0);
                    break;
                default:
                    System.err.println("Unknown argument: " + args[i]);
                    printUsage();
                    System.exit(1);
            }
        }
    }
    
    private static void printUsage() {
        System.out.println("Usage: java PopulateTestData.java [OPTIONS]");
        System.out.println();
        System.out.println("OPTIONS:");
        System.out.println("  -u, --username <username>  Application username (default: testadmin)");
        System.out.println("  -p, --password <password>  Application password (default: testpass)");
        System.out.println("  -h, --help                 Show this help message");
        System.out.println();
        System.out.println("This script populates meaningful test data for the secman tool.");
        System.out.println("The username and password are for application users, not database credentials.");
    }
    
    public void run() throws Exception {
        System.out.println("=== Secman Test Data Population Script ===");
        System.out.println("App Username: " + appUsername);
        System.out.println("App Password: " + appPassword);
        System.out.println();
        
        // Connect to database
        connectToDatabase();
        
        try {
            // Check if database is empty (or if we should proceed)
            if (!confirmPopulation()) {
                System.out.println("Operation cancelled.");
                return;
            }
            
            // Populate data in dependency order
            System.out.println("Populating test data...");
            
            populateUsers();
            populateAssets();
            populateStandards();
            populateUseCases();
            linkStandardToUseCase();
            populateNorms();
            populateRequirements();
            populateRisks();
            populateRiskAssessments();
            
            System.out.println("\n✅ Test data population completed successfully!");
            System.out.println("\nYou can now log in with:");
            System.out.println("  Username: " + appUsername);
            System.out.println("  Password: " + appPassword);
            System.out.println("  Role: ADMIN");
            
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }
    
    private void connectToDatabase() throws SQLException {
        System.out.println("Connecting to database...");
        try {
            Class.forName("org.mariadb.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            System.err.println("");
            System.err.println("ERROR: MariaDB JDBC driver not found in classpath.");
            System.err.println("");
            System.err.println("This usually means the MariaDB connector JAR file is missing.");
            System.err.println("The script should have automatically downloaded it.");
            System.err.println("");
            System.err.println("Manual solutions:");
            System.err.println("1. Re-run the populate-testdata.sh script (recommended)");
            System.err.println("2. Download manually from:");
            System.err.println("   https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.3.2/mariadb-java-client-3.3.2.jar");
            System.err.println("3. Place in: ../src/backend/lib/mariadb-java-client.jar");
            System.err.println("");
            throw new SQLException("MariaDB JDBC driver not found in classpath", e);
        }
        
        try {
            connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
            connection.setAutoCommit(false);
            System.out.println("Connected to database successfully.");
        } catch (SQLException e) {
            System.err.println("");
            System.err.println("ERROR: Failed to connect to database.");
            System.err.println("");
            System.err.println("Database details:");
            System.err.println("  URL: " + DB_URL);
            System.err.println("  Username: " + DB_USER);
            System.err.println("  Password: " + DB_PASS);
            System.err.println("");
            System.err.println("Possible solutions:");
            System.err.println("1. Ensure MariaDB is running: sudo systemctl start mariadb");
            System.err.println("2. Check if database exists: mysql -u " + DB_USER + " -p " + "secman");
            System.err.println("3. Run database setup: ./install.sh");
            System.err.println("4. Verify credentials in src/backend/conf/application.conf");
            System.err.println("");
            System.err.println("Original error: " + e.getMessage());
            throw e;
        }
    }
    
    private boolean confirmPopulation() throws SQLException {
        // Check if users table has any data
        PreparedStatement stmt = connection.prepareStatement("SELECT COUNT(*) FROM users");
        ResultSet rs = stmt.executeQuery();
        rs.next();
        int userCount = rs.getInt(1);
        rs.close();
        stmt.close();
        
        if (userCount > 0) {
            System.out.println("WARNING: Database contains " + userCount + " users.");
            System.out.println("This script will add more test data to the existing database.");
        } else {
            System.out.println("Database appears to be empty. Ready to populate with test data.");
        }
        
        System.out.print("Do you want to proceed? (y/N): ");
        Scanner scanner = new Scanner(System.in);
        String response = scanner.nextLine().trim().toLowerCase();
        return response.equals("y") || response.equals("yes");
    }
    
    private void populateUsers() throws SQLException {
        System.out.println("Creating users...");
        
        LocalDateTime now = LocalDateTime.now();
        int createdUsers = 0;
        
        // Create admin user (with provided credentials) if not exists
        long adminUserId = createUserIfNotExists(appUsername, appUsername + "@secman.local", appPassword, now);
        if (adminUserId > 0) {
            createdUsers++;
            System.out.println("✓ Created admin user: " + appUsername);
        } else {
            System.out.println("⚠ User '" + appUsername + "' already exists, skipping");
            // Get existing user ID for role assignment
            adminUserId = getUserId(appUsername);
        }
        
        // Regular test users - use test_ prefix to avoid conflicts
        String[][] users = {
            {"test_analyst", "test_analyst@secman.local", "password123"},
            {"test_auditor", "test_auditor@secman.local", "password123"},
            {"test_riskmanager", "test_riskmanager@secman.local", "password123"},
            {"test_compliance", "test_compliance@secman.local", "password123"},
            {"test_security", "test_security@secman.local", "password123"}
        };
        
        for (String[] user : users) {
            long userId = createUserIfNotExists(user[0], user[1], user[2], now);
            if (userId > 0) {
                createdUsers++;
                System.out.println("✓ Created user: " + user[0]);
            } else {
                System.out.println("⚠ User '" + user[0] + "' already exists, skipping");
            }
        }
        
        // Assign roles to all users (existing and new)
        populateUserRoles(adminUserId);
        
        connection.commit();
        if (createdUsers > 0) {
            System.out.println("✓ Created " + createdUsers + " new users");
        } else {
            System.out.println("✓ All users already exist, roles updated");
        }
    }
    
    private long createUserIfNotExists(String username, String email, String password, LocalDateTime now) throws SQLException {
        // Check if user already exists
        PreparedStatement checkStmt = connection.prepareStatement("SELECT id FROM users WHERE username = ?");
        checkStmt.setString(1, username);
        ResultSet rs = checkStmt.executeQuery();
        
        if (rs.next()) {
            // User exists
            rs.close();
            checkStmt.close();
            return -1; // Indicate user already exists
        }
        rs.close();
        checkStmt.close();
        
        // Create new user
        String sql = "INSERT INTO users (username, email, password_hash, created_at, updated_at) VALUES (?, ?, ?, ?, ?)";
        PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        
        stmt.setString(1, username);
        stmt.setString(2, email);
        stmt.setString(3, hashPassword(password));
        stmt.setTimestamp(4, Timestamp.valueOf(now));
        stmt.setTimestamp(5, Timestamp.valueOf(now));
        stmt.executeUpdate();
        
        ResultSet generatedKeys = stmt.getGeneratedKeys();
        generatedKeys.next();
        long userId = generatedKeys.getLong(1);
        generatedKeys.close();
        stmt.close();
        
        return userId;
    }
    
    private long getUserId(String username) throws SQLException {
        PreparedStatement stmt = connection.prepareStatement("SELECT id FROM users WHERE username = ?");
        stmt.setString(1, username);
        ResultSet rs = stmt.executeQuery();
        
        if (rs.next()) {
            long userId = rs.getLong(1);
            rs.close();
            stmt.close();
            return userId;
        }
        
        rs.close();
        stmt.close();
        throw new SQLException("User not found: " + username);
    }
    
    private void populateUserRoles(long adminUserId) throws SQLException {
        // Admin user gets ADMIN role (if not already assigned)
        assignRoleIfNotExists(adminUserId, "ADMIN");
        
        // All users get USER role (including admin) - but only test users and the specified admin
        PreparedStatement userStmt = connection.prepareStatement(
            "SELECT id FROM users WHERE username = ? OR username LIKE 'test_%'"
        );
        userStmt.setString(1, appUsername);
        ResultSet rs = userStmt.executeQuery();
        
        while (rs.next()) {
            long userId = rs.getLong(1);
            assignRoleIfNotExists(userId, "USER");
        }
        
        rs.close();
        userStmt.close();
    }
    
    private void assignRoleIfNotExists(long userId, String roleName) throws SQLException {
        // Check if role already exists for this user
        PreparedStatement checkStmt = connection.prepareStatement(
            "SELECT COUNT(*) FROM user_roles WHERE user_id = ? AND role_name = ?"
        );
        checkStmt.setLong(1, userId);
        checkStmt.setString(2, roleName);
        ResultSet rs = checkStmt.executeQuery();
        
        rs.next();
        int count = rs.getInt(1);
        rs.close();
        checkStmt.close();
        
        if (count == 0) {
            // Role doesn't exist, create it
            PreparedStatement insertStmt = connection.prepareStatement(
                "INSERT INTO user_roles (user_id, role_name) VALUES (?, ?)"
            );
            insertStmt.setLong(1, userId);
            insertStmt.setString(2, roleName);
            insertStmt.executeUpdate();
            insertStmt.close();
        }
    }
    
    private void populateAssets() throws SQLException {
        System.out.println("Creating assets...");
        
        String sql = "INSERT INTO asset (name, type, ip, owner, description, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
        PreparedStatement stmt = connection.prepareStatement(sql);
        
        LocalDateTime now = LocalDateTime.now();
        
        String[][] assets = {
            {"Web Server 01", "Server", "192.168.1.10", "IT Operations", "Primary web application server hosting customer portal"},
            {"Database Server 01", "Database", "192.168.1.20", "DBA Team", "Production database server containing customer data"},
            {"API Gateway", "Network Device", "192.168.1.30", "Network Team", "External API gateway for partner integrations"},
            {"Employee Laptops", "Endpoint", "DHCP Range", "IT Security", "Corporate laptops for remote employees"},
            {"File Server", "Server", "192.168.1.40", "IT Operations", "Central file storage for document management"},
            {"Email Server", "Server", "192.168.1.50", "IT Operations", "Corporate email and communication platform"},
            {"Backup Storage", "Storage", "192.168.1.60", "Backup Team", "Centralized backup storage system"},
            {"Mobile Application", "Application", "N/A", "Development Team", "Customer mobile application for iOS and Android"},
            {"Payment Gateway", "Service", "External", "Finance IT", "Third-party payment processing service"},
            {"VPN Infrastructure", "Network Device", "192.168.1.70", "Network Security", "Remote access VPN for employees"}
        };
        
        for (String[] asset : assets) {
            stmt.setString(1, asset[0]);
            stmt.setString(2, asset[1]);
            stmt.setString(3, asset[2]);
            stmt.setString(4, asset[3]);
            stmt.setString(5, asset[4]);
            stmt.setTimestamp(6, Timestamp.valueOf(now));
            stmt.setTimestamp(7, Timestamp.valueOf(now));
            stmt.executeUpdate();
        }
        
        stmt.close();
        connection.commit();
        System.out.println("✓ Created " + assets.length + " assets");
    }
    
    private void populateStandards() throws SQLException {
        System.out.println("Creating standards...");
        
        String sql = "INSERT INTO standard (name, description, created_at, updated_at) VALUES (?, ?, ?, ?)";
        PreparedStatement stmt = connection.prepareStatement(sql);
        
        LocalDateTime now = LocalDateTime.now();
        
        // Single standard for external hosting
        stmt.setString(1, "External hosting");
        stmt.setString(2, "Security requirements and controls for external hosting services and SAAS platforms");
        stmt.setTimestamp(3, Timestamp.valueOf(now));
        stmt.setTimestamp(4, Timestamp.valueOf(now));
        stmt.executeUpdate();
        
        stmt.close();
        connection.commit();
        System.out.println("✓ Created 1 standard");
    }
    
    private void populateUseCases() throws SQLException {
        System.out.println("Creating use cases...");
        
        String sql = "INSERT INTO usecase (name, created_at, updated_at) VALUES (?, ?, ?)";
        PreparedStatement stmt = connection.prepareStatement(sql);
        
        LocalDateTime now = LocalDateTime.now();
        
        // Single use case for SAAS
        stmt.setString(1, "SAAS");
        stmt.setTimestamp(2, Timestamp.valueOf(now));
        stmt.setTimestamp(3, Timestamp.valueOf(now));
        stmt.executeUpdate();
        
        stmt.close();
        connection.commit();
        System.out.println("✓ Created 1 use case");
    }
    
    private void linkStandardToUseCase() throws SQLException {
        System.out.println("Linking standard to use case...");
        
        // Get the standard ID for "External hosting"
        PreparedStatement standardStmt = connection.prepareStatement("SELECT id FROM standard WHERE name = ?");
        standardStmt.setString(1, "External hosting");
        ResultSet standardRs = standardStmt.executeQuery();
        
        if (!standardRs.next()) {
            standardRs.close();
            standardStmt.close();
            throw new SQLException("Standard 'External hosting' not found");
        }
        long standardId = standardRs.getLong(1);
        standardRs.close();
        standardStmt.close();
        
        // Get the use case ID for "SAAS"
        PreparedStatement useCaseStmt = connection.prepareStatement("SELECT id FROM usecase WHERE name = ?");
        useCaseStmt.setString(1, "SAAS");
        ResultSet useCaseRs = useCaseStmt.executeQuery();
        
        if (!useCaseRs.next()) {
            useCaseRs.close();
            useCaseStmt.close();
            throw new SQLException("Use case 'SAAS' not found");
        }
        long useCaseId = useCaseRs.getLong(1);
        useCaseRs.close();
        useCaseStmt.close();
        
        // Link them in the junction table
        PreparedStatement linkStmt = connection.prepareStatement(
            "INSERT INTO standard_usecase (standard_id, usecase_id) VALUES (?, ?)"
        );
        linkStmt.setLong(1, standardId);
        linkStmt.setLong(2, useCaseId);
        linkStmt.executeUpdate();
        linkStmt.close();
        
        connection.commit();
        System.out.println("✓ Linked 'External hosting' standard to 'SAAS' use case");
    }
    
    private void populateNorms() throws SQLException {
        System.out.println("Creating norms...");
        
        String sql = "INSERT INTO norm (name, version, year, created_at, updated_at) VALUES (?, ?, ?, ?, ?)";
        PreparedStatement stmt = connection.prepareStatement(sql);
        
        LocalDateTime now = LocalDateTime.now();
        
        // Single ISO27001 norm with 2022 data
        stmt.setString(1, "ISO27001");
        stmt.setString(2, "2022");
        stmt.setInt(3, 2022);
        stmt.setTimestamp(4, Timestamp.valueOf(now));
        stmt.setTimestamp(5, Timestamp.valueOf(now));
        stmt.executeUpdate();
        
        stmt.close();
        connection.commit();
        System.out.println("✓ Created 1 norm");
    }
    
    private void populateRequirements() throws SQLException {
        System.out.println("Creating requirements...");
        
        String sql = "INSERT INTO requirement (shortreq, description, language, example, motivation, usecase, norm, chapter, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        PreparedStatement stmt = connection.prepareStatement(sql);
        
        LocalDateTime now = LocalDateTime.now();
        
        String[][] requirements = {
            {
                "SAAS Data Protection",
                "All customer data processed by SAAS applications must be encrypted both in transit and at rest using industry-standard encryption methods.",
                "en",
                "Customer data is encrypted using AES-256 for data at rest and TLS 1.3 for data in transit between client and server.",
                "Data protection is critical for SAAS providers to maintain customer trust and comply with privacy regulations.",
                "SAAS",
                "ISO27001",
                "Information Security"
            },
            {
                "External Hosting Security",
                "All externally hosted services must implement multi-factor authentication and regular security assessments to ensure data protection.",
                "en",
                "SAAS platforms require MFA for all administrative access and undergo quarterly penetration testing by certified security firms.",
                "External hosting introduces additional risks that must be mitigated through comprehensive security controls.",
                "SAAS",
                "ISO27001",
                "Access Control"
            },
            {
                "SAAS Availability Requirements",
                "SAAS platforms must maintain 99.9% uptime with documented disaster recovery procedures and regular backup testing.",
                "en",
                "Service level agreements specify maximum 43 minutes of downtime per month with automated failover to secondary data centers.",
                "High availability is essential for SAAS services as customers depend on continuous access to their data and applications.",
                "SAAS",
                "ISO27001",
                "Business Continuity"
            }
        };
        
        for (String[] req : requirements) {
            stmt.setString(1, req[0]);
            stmt.setString(2, req[1]);
            stmt.setString(3, req[2]);
            stmt.setString(4, req[3]);
            stmt.setString(5, req[4]);
            stmt.setString(6, req[5]);
            stmt.setString(7, req[6]);
            stmt.setString(8, req[7]);
            stmt.setTimestamp(9, Timestamp.valueOf(now));
            stmt.setTimestamp(10, Timestamp.valueOf(now));
            stmt.executeUpdate();
        }
        
        stmt.close();
        connection.commit();
        System.out.println("✓ Created " + requirements.length + " requirements");
    }
    
    private void populateRisks() throws SQLException {
        System.out.println("Creating risks...");
        
        String sql = "INSERT INTO risk (asset_id, name, description, likelihood, impact, risk_level, status, owner, severity, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        PreparedStatement stmt = connection.prepareStatement(sql);
        
        // Get asset IDs
        PreparedStatement assetStmt = connection.prepareStatement("SELECT id, name FROM asset ORDER BY id LIMIT 5");
        ResultSet assetRs = assetStmt.executeQuery();
        
        LocalDateTime now = LocalDateTime.now();
        
        String[][] risks = {
            {"Data Breach via SQL Injection", "Vulnerability in web application could allow unauthorized data access", "3", "5", "HIGH", "riskmanager"},
            {"Unpatched Operating System", "Critical security patches not applied in timely manner", "4", "4", "HIGH", "securityeng"},
            {"Unauthorized Physical Access", "Inadequate physical security controls in server room", "2", "4", "MEDIUM", "IT Operations"},
            {"Phishing Attack Success", "Employees clicking malicious links leading to credential theft", "4", "3", "MEDIUM", "analyst1"},
            {"Third-Party Data Exposure", "Vendor security incident affecting our customer data", "2", "5", "MEDIUM", "complianceofficer"}
        };
        
        int riskIndex = 0;
        while (assetRs.next() && riskIndex < risks.length) {
            long assetId = assetRs.getLong(1);
            String[] risk = risks[riskIndex];
            
            int likelihood = Integer.parseInt(risk[2]);
            int impact = Integer.parseInt(risk[3]);
            int riskLevel = calculateRiskLevel(likelihood, impact);
            
            stmt.setLong(1, assetId);
            stmt.setString(2, risk[0]);
            stmt.setString(3, risk[1]);
            stmt.setInt(4, likelihood);
            stmt.setInt(5, impact);
            stmt.setInt(6, riskLevel);
            stmt.setString(7, "OPEN");
            stmt.setString(8, risk[5]);
            stmt.setString(9, risk[4]);
            stmt.setTimestamp(10, Timestamp.valueOf(now));
            stmt.setTimestamp(11, Timestamp.valueOf(now));
            stmt.executeUpdate();
            
            riskIndex++;
        }
        
        assetRs.close();
        assetStmt.close();
        stmt.close();
        connection.commit();
        System.out.println("✓ Created " + riskIndex + " risks");
    }
    
    private void populateRiskAssessments() throws SQLException {
        System.out.println("Creating risk assessments...");
        
        // Get user IDs
        PreparedStatement userStmt = connection.prepareStatement("SELECT id, username FROM users ORDER BY id LIMIT 3");
        ResultSet userRs = userStmt.executeQuery();
        
        // Get asset IDs  
        PreparedStatement assetStmt = connection.prepareStatement("SELECT id FROM asset ORDER BY id LIMIT 3");
        ResultSet assetRs = assetStmt.executeQuery();
        
        String sql = "INSERT INTO risk_assessment (asset_id, start_date, end_date, status, assessor_id, requestor_id, notes, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        PreparedStatement stmt = connection.prepareStatement(sql);
        
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        
        if (userRs.next() && assetRs.next()) {
            long assessorId = userRs.getLong(1);
            long requestorId = userRs.next() ? userRs.getLong(1) : assessorId;
            long assetId = assetRs.getLong(1);
            
            stmt.setLong(1, assetId);
            stmt.setDate(2, Date.valueOf(today));
            stmt.setDate(3, Date.valueOf(today.plusDays(30)));
            stmt.setString(4, "STARTED");
            stmt.setLong(5, assessorId);
            stmt.setLong(6, requestorId);
            stmt.setString(7, "Quarterly risk assessment for critical infrastructure");
            stmt.setTimestamp(8, Timestamp.valueOf(now));
            stmt.setTimestamp(9, Timestamp.valueOf(now));
            stmt.executeUpdate();
            
            // Second assessment if more assets available
            if (assetRs.next()) {
                stmt.setLong(1, assetRs.getLong(1));
                stmt.setDate(2, Date.valueOf(today.minusDays(15)));
                stmt.setDate(3, Date.valueOf(today.plusDays(15)));
                stmt.setString(4, "IN_PROGRESS");
                stmt.setString(7, "Security audit following recent policy updates");
                stmt.setTimestamp(8, Timestamp.valueOf(now.minusDays(15)));
                stmt.setTimestamp(9, Timestamp.valueOf(now));
                stmt.executeUpdate();
            }
        }
        
        userRs.close();
        userStmt.close();
        assetRs.close();
        assetStmt.close();
        stmt.close();
        connection.commit();
        System.out.println("✓ Created risk assessments");
    }
    
    private int calculateRiskLevel(int likelihood, int impact) {
        int product = likelihood * impact;
        if (product <= 4) return 1;
        if (product <= 9) return 2;
        if (product <= 15) return 3;
        return 4;
    }
    
    private String hashPassword(String password) {
        // Simple hash for demo purposes - in production use proper BCrypt
        return "hashed_" + password + "_" + password.hashCode();
    }
}

