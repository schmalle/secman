#!/bin/bash

# Secman Test Data Population Script Wrapper (Kotlin Version)
# This script compiles and runs the Kotlin test data population utility

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KOTLIN_FILE="$SCRIPT_DIR/PopulateTestData.kt"
JAR_FILE="$SCRIPT_DIR/PopulateTestData.jar"
BACKEND_DIR="$SCRIPT_DIR/../src/backend"

echo "=== Secman Test Data Population Script (Kotlin) ==="
echo ""

# Check if Kotlin is available
if ! command -v kotlinc &> /dev/null; then
    echo "ERROR: Kotlin compiler (kotlinc) is not installed or not in PATH"
    echo ""
    echo "Installation options:"
    echo "1. Install via SDKMAN: curl -s https://get.sdkman.io | bash && sdk install kotlin"
    echo "2. Install via Homebrew: brew install kotlin"
    echo "3. Download from: https://kotlinlang.org/docs/command-line.html"
    echo ""
    exit 1
fi

if ! command -v kotlin &> /dev/null; then
    echo "ERROR: Kotlin runtime (kotlin) is not installed or not in PATH"
    echo "Please install Kotlin and try again"
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

# Compile the Kotlin file if needed
if [ ! -f "$JAR_FILE" ] || [ "$KOTLIN_FILE" -nt "$JAR_FILE" ]; then
    echo "Compiling PopulateTestData.kt..."
    if [ -n "$MARIADB_JAR" ]; then
        kotlinc -cp "$MARIADB_JAR" -include-runtime -d "$JAR_FILE" "$KOTLIN_FILE"
    else
        kotlinc -include-runtime -d "$JAR_FILE" "$KOTLIN_FILE"
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

# Run the Kotlin application
echo "Running test data population script (Kotlin version)..."
echo ""

if [ -n "$MARIADB_JAR" ]; then
    kotlin -cp "$JAR_FILE:$MARIADB_JAR" PopulateTestDataKt "$@"
else
    kotlin -cp "$JAR_FILE" PopulateTestDataKt "$@"
fi

# Clean up compiled JAR file
if [ -f "$JAR_FILE" ]; then
    rm "$JAR_FILE"
fi

echo ""
echo "Kotlin script completed."
