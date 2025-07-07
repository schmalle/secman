# Secman Test Data Population Script

This directory contains a script to populate meaningful test data into an empty secman instance.

## Files

- `PopulateTestData.java` - Main Java script that creates test data
- `populate-testdata.sh` - Shell wrapper script for easy execution
- `TESTDATA_README.md` - This documentation file

## Prerequisites

1. **Java Development Kit (JDK)** - Java 11 or later
2. **MariaDB** - Running and accessible on localhost:3306  
3. **Secman Database** - Created and configured (run `./install.sh` first)
4. **Internet Connection** - For automatic JDBC driver download (first run only)

## Quick Start

1. Ensure MariaDB is running and the secman database exists:
   ```bash
   # If database doesn't exist yet, run:
   ./install.sh
   ```

2. Run the test data population script:
   ```bash
   ./populate-testdata.sh
   ```

3. Use custom credentials (optional):
   ```bash
   ./populate-testdata.sh --username myuser --password mypass
   ```

## What Data Is Created

The script populates the following entities with focused SAAS and external hosting test data:

### Users (6 total)
- **Admin User**: Username and password as specified (default: testadmin/testpass)
- **Regular Users**: test_analyst, test_auditor, test_riskmanager, test_compliance, test_security
- **Roles**: Admin gets ADMIN+USER roles, others get USER role
- **Safe Naming**: All test users use "test_" prefix to avoid conflicts with system users

### Assets (10 total)
- Web servers, database servers, network devices
- Employee endpoints and infrastructure
- External services and applications
- Each with realistic IP addresses, owners, and descriptions

### Standards (1 total)
- **External hosting**: Security requirements and controls for external hosting services and SAAS platforms
- Linked to the SAAS use case

### Use Cases (1 total)
- **SAAS**: Software as a Service security requirements
- Linked to the External hosting standard

### Norms (1 total)
- **ISO27001**: Information security management standard with 2022 version data

### Requirements (3 total)
- **SAAS Data Protection**: Encryption requirements for customer data
- **External Hosting Security**: MFA and assessment requirements
- **SAAS Availability Requirements**: Uptime and disaster recovery requirements  
- All requirements reference the ISO27001 norm and SAAS use case

### Risks (5 total)
- SQL injection vulnerabilities
- Unpatched systems
- Physical security gaps
- Phishing attacks
- Third-party exposures
- With realistic likelihood/impact ratings

### Risk Assessments (2 total)
- Ongoing quarterly assessments
- Security audits
- Linked to assets and users

## Command Line Options

```bash
./populate-testdata.sh [OPTIONS]

OPTIONS:
  -u, --username <username>  Application username (default: testadmin)
  -p, --password <password>  Application password (default: testpass)  
  -h, --help                 Show help message
```

**Note**: The username and password parameters are for creating application user accounts, not database credentials.

## Examples

```bash
# Use default credentials (testadmin/testpass)
./populate-testdata.sh

# Create admin user with custom credentials  
./populate-testdata.sh --username myadmin --password securepass123

# Short form
./populate-testdata.sh -u myuser -p mypass
```

## Post-Population

After successful execution, you can:

1. **Log into the application** using the created admin credentials
2. **Explore the test data** through the web interface
3. **Run tests** against a populated database
4. **Develop features** with realistic data relationships

## Troubleshooting

### "Java not found"
Install Java Development Kit (JDK) 11 or later:
```bash
# Ubuntu/Debian
sudo apt install openjdk-11-jdk

# macOS with Homebrew
brew install openjdk@11

# CentOS/RHEL
sudo yum install java-11-openjdk-devel
```

### "MariaDB JDBC driver not found"
The script should automatically download the JDBC driver on first run. If this fails:

1. **Check internet connection** - The script downloads from Maven Central
2. **Manual download**: Get from https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.3.2/mariadb-java-client-3.3.2.jar
3. **Place in**: `src/backend/lib/mariadb-java-client.jar`

The script will automatically detect and use the driver on subsequent runs.

### "Cannot connect to database"
Ensure MariaDB is running:
```bash
# Check if MariaDB is running
sudo systemctl status mariadb

# Start MariaDB if needed
sudo systemctl start mariadb

# Test connection
mysql -u secman -p secman
```

### "Database installation failed"
Run the database setup first:
```bash
./install.sh
```

## Script Architecture

The script follows these principles:

1. **Dependency Order**: Creates entities in the correct order to maintain referential integrity
2. **Realistic Data**: Uses meaningful names, descriptions, and relationships
3. **Flexible Authentication**: Accepts custom credentials for the admin user
4. **Error Handling**: Provides clear error messages and troubleshooting guidance
5. **Idempotent**: Can be run multiple times (will add to existing data)
6. **Auto-Download**: Automatically downloads MariaDB JDBC driver when missing
7. **Zero-Config**: Works out of the box with internet connection

## Integration with Secman

This script is designed to work seamlessly with the secman application:

- Uses the same database configuration as the backend
- Creates data compatible with JPA entity models
- Follows the same naming conventions and data patterns
- Provides realistic test scenarios for all major features

## Security Note

This script is intended for **development and testing environments only**. 

- Passwords are weakly hashed for demo purposes
- Default credentials should be changed in production
- Test data includes realistic but fictional information

Do not use this script in production environments.