#!/bin/bash

# Secman Test Data Population Script Wrapper
# This script compiles and runs the Java test data population utility

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_FILE="$SCRIPT_DIR/PopulateTestData.java"
CLASS_FILE="$SCRIPT_DIR/PopulateTestData.class"
BACKEND_DIR="$SCRIPT_DIR/../src/backend"

echo "=== Secman Test Data Population Script ==="
echo ""

# Check if Java is available
if ! command -v java &> /dev/null; then
    echo "ERROR: Java is not installed or not in PATH"
    echo "Please install Java 11 or later and try again"
    exit 1
fi

if ! command -v javac &> /dev/null; then
    echo "ERROR: Java compiler (javac) is not installed or not in PATH"
    echo "Please install Java Development Kit (JDK) and try again"
    exit 1
fi

# Check if backend directory exists
if [ ! -d "$BACKEND_DIR" ]; then
    echo "ERROR: Backend directory not found at $BACKEND_DIR"
    echo "Please run this script from the secman project root/scripts directory"
    exit 1
fi

# Function to download MariaDB JDBC driver
download_mariadb_driver() {
    local lib_dir="$BACKEND_DIR/lib"
    local jar_file="$lib_dir/mariadb-java-client.jar"
    local download_url="https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.3.2/mariadb-java-client-3.3.2.jar"
    
    echo "MariaDB JDBC driver not found. Downloading..."
    
    # Create lib directory if it doesn't exist
    mkdir -p "$lib_dir"
    
    # Download the driver
    if command -v curl &> /dev/null; then
        if curl -L -o "$jar_file" "$download_url" --fail --silent --show-error; then
            echo "✓ Successfully downloaded MariaDB JDBC driver to $jar_file"
            return 0
        else
            echo "✗ Failed to download MariaDB JDBC driver using curl"
            return 1
        fi
    elif command -v wget &> /dev/null; then
        if wget -q -O "$jar_file" "$download_url"; then
            echo "✓ Successfully downloaded MariaDB JDBC driver to $jar_file"
            return 0
        else
            echo "✗ Failed to download MariaDB JDBC driver using wget"
            return 1
        fi
    else
        echo "✗ Neither curl nor wget found. Cannot download MariaDB JDBC driver automatically."
        return 1
    fi
}

# Find or download MariaDB JDBC driver
MARIADB_JAR=""
MARIADB_VERSION="3.3.2"

# Check common locations for the driver
if [ -f "$BACKEND_DIR/lib/mariadb-java-client.jar" ]; then
    MARIADB_JAR="$BACKEND_DIR/lib/mariadb-java-client.jar"
    echo "Found MariaDB JDBC driver: $MARIADB_JAR"
elif [ -f "$HOME/.m2/repository/org/mariadb/jdbc/mariadb-java-client/$MARIADB_VERSION/mariadb-java-client-$MARIADB_VERSION.jar" ]; then
    MARIADB_JAR="$HOME/.m2/repository/org/mariadb/jdbc/mariadb-java-client/$MARIADB_VERSION/mariadb-java-client-$MARIADB_VERSION.jar"
    echo "Found MariaDB JDBC driver: $MARIADB_JAR"
else
    # Try to download the driver
    if download_mariadb_driver; then
        MARIADB_JAR="$BACKEND_DIR/lib/mariadb-java-client.jar"
    else
        echo ""
        echo "ERROR: MariaDB JDBC driver could not be found or downloaded."
        echo ""
        echo "Manual download options:"
        echo "1. Download from: https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/$MARIADB_VERSION/mariadb-java-client-$MARIADB_VERSION.jar"
        echo "2. Save to: $BACKEND_DIR/lib/mariadb-java-client.jar"
        echo ""
        echo "Or install via Maven:"
        echo "  mvn dependency:copy-dependencies"
        echo ""
        exit 1
    fi
fi

# Compile the Java file if needed
if [ ! -f "$CLASS_FILE" ] || [ "$JAVA_FILE" -nt "$CLASS_FILE" ]; then
    echo "Compiling PopulateTestData.java..."
    if [ -n "$MARIADB_JAR" ]; then
        javac -cp "$MARIADB_JAR" "$JAVA_FILE"
    else
        javac "$JAVA_FILE"
    fi
    echo "✓ Compilation successful"
fi

# Check if database is running
echo "Checking database connectivity..."
if ! nc -z localhost 3306 2>/dev/null; then
    echo "WARNING: Cannot connect to MariaDB on localhost:3306"
    echo "Please ensure MariaDB is running and accessible"
    echo ""
    read -p "Do you want to continue anyway? (y/N): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Operation cancelled."
        exit 1
    fi
fi

# Run the Java application
echo "Running test data population script..."
echo ""

if [ -n "$MARIADB_JAR" ]; then
    java -cp ".:$MARIADB_JAR" PopulateTestData "$@"
else
    java PopulateTestData "$@"
fi

# Clean up compiled class file
if [ -f "$CLASS_FILE" ]; then
    rm "$CLASS_FILE"
fi

echo ""
echo "Script completed."