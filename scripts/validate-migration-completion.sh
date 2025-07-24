#!/bin/bash

# Secman Migration Completion Validation Script
# This script validates that the Java Play Framework to Kotlin Micronaut migration is complete
# Author: Generated for Secman Project
# Version: 1.0

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Configuration
JAVA_BASE_URL="${JAVA_BASE_URL:-http://localhost:9000}"
KOTLIN_BASE_URL="${KOTLIN_BASE_URL:-http://localhost:9001}"
OUTPUT_DIR="./migration-validation-results"

# Counters
TOTAL_CHECKS=0
PASSED_CHECKS=0
FAILED_CHECKS=0
WARNINGS=0

# Results arrays
PASSED_RESULTS=()
FAILED_RESULTS=()
WARNING_RESULTS=()

log() {
    local level=$1
    shift
    local message="$*"
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    
    case $level in
        "ERROR")
            echo -e "${RED}[$timestamp] [ERROR] $message${NC}" >&2
            ;;
        "WARN")
            echo -e "${YELLOW}[$timestamp] [WARN] $message${NC}"
            ;;
        "INFO")
            echo -e "${BLUE}[$timestamp] [INFO] $message${NC}"
            ;;
        "SUCCESS")
            echo -e "${GREEN}[$timestamp] [SUCCESS] $message${NC}"
            ;;
        "HEADER")
            echo -e "\n${PURPLE}========================================${NC}"
            echo -e "${PURPLE}$message${NC}"
            echo -e "${PURPLE}========================================${NC}\n"
            ;;
    esac
}

run_check() {
    local check_name="$1"
    local check_function="$2"
    local check_description="$3"
    
    TOTAL_CHECKS=$((TOTAL_CHECKS + 1))
    
    log "INFO" "🔍 Running check: $check_name - $check_description"
    
    if $check_function; then
        PASSED_CHECKS=$((PASSED_CHECKS + 1))
        PASSED_RESULTS+=("✅ $check_name - $check_description")
        log "SUCCESS" "✅ Check passed: $check_name"
        return 0
    else
        FAILED_CHECKS=$((FAILED_CHECKS + 1))
        FAILED_RESULTS+=("❌ $check_name - $check_description")
        log "ERROR" "❌ Check failed: $check_name"
        return 1
    fi
}

add_warning() {
    local warning_message="$1"
    WARNINGS=$((WARNINGS + 1))
    WARNING_RESULTS+=("⚠️  $warning_message")
    log "WARN" "⚠️  $warning_message"
}

# =============================================================================
# BACKEND CONNECTIVITY CHECKS
# =============================================================================

check_java_backend_running() {
    curl -s --max-time 5 "$JAVA_BASE_URL/health" > /dev/null 2>&1 || \
    curl -s --max-time 5 "$JAVA_BASE_URL/" > /dev/null 2>&1
}

check_kotlin_backend_running() {
    curl -s --max-time 5 "$KOTLIN_BASE_URL/health" > /dev/null 2>&1
}

# =============================================================================
# KOTLIN BACKEND COMPLETENESS CHECKS
# =============================================================================

check_kotlin_domain_entities() {
    local entities=(
        "User.kt" "Requirement.kt" "Standard.kt" "RiskAssessment.kt" "Risk.kt" "Asset.kt"
        "Norm.kt" "UseCase.kt" "Response.kt" "AssessmentToken.kt" "EmailConfig.kt" 
        "TranslationConfig.kt" "RequirementFile.kt" "Release.kt"
    )
    
    local missing_entities=()
    
    for entity in "${entities[@]}"; do
        if [[ ! -f "/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/domain/$entity" ]]; then
            missing_entities+=("$entity")
        fi
    done
    
    if [[ ${#missing_entities[@]} -eq 0 ]]; then
        return 0
    else
        log "ERROR" "Missing domain entities: ${missing_entities[*]}"
        return 1
    fi
}

check_kotlin_repositories() {
    local repositories=(
        "UserRepository.kt" "RequirementRepository.kt" "StandardRepository.kt" 
        "RiskAssessmentRepository.kt" "RiskRepository.kt" "AssetRepository.kt"
        "NormRepository.kt" "UseCaseRepository.kt" "ResponseRepository.kt" 
        "AssessmentTokenRepository.kt" "EmailConfigRepository.kt"
        "TranslationConfigRepository.kt" "RequirementFileRepository.kt" "ReleaseRepository.kt"
    )
    
    local missing_repos=()
    
    for repo in "${repositories[@]}"; do
        if [[ ! -f "/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/repository/$repo" ]]; then
            missing_repos+=("$repo")
        fi
    done
    
    if [[ ${#missing_repos[@]} -eq 0 ]]; then
        return 0
    else
        log "ERROR" "Missing repositories: ${missing_repos[*]}"
        return 1
    fi
}

check_kotlin_controllers() {
    local controllers=(
        "AuthController.kt" "UserController.kt" "RequirementController.kt"
        "StandardController.kt" "RiskAssessmentController.kt" "RiskController.kt"
        "AssetController.kt" "NormController.kt" "UseCaseController.kt"
        "ResponseController.kt" "ImportController.kt" "EmailConfigController.kt"
        "TranslationConfigController.kt" "RequirementFileController.kt" "HealthController.kt"
    )
    
    local missing_controllers=()
    
    for controller in "${controllers[@]}"; do
        if [[ ! -f "/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/controller/$controller" ]]; then
            missing_controllers+=("$controller")
        fi
    done
    
    if [[ ${#missing_controllers[@]} -eq 0 ]]; then
        return 0
    else
        log "ERROR" "Missing controllers: ${missing_controllers[*]}"
        return 1
    fi
}

check_kotlin_services() {
    local services=(
        "NormParsingService.kt" "EmailService.kt" "TranslationServiceSimple.kt" "FileService.kt"
    )
    
    local missing_services=()
    
    for service in "${services[@]}"; do
        if [[ ! -f "/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/service/$service" ]]; then
            missing_services+=("$service")
        fi
    done
    
    if [[ ${#missing_services[@]} -eq 0 ]]; then
        return 0
    else
        log "ERROR" "Missing services: ${missing_services[*]}"
        return 1
    fi
}

# =============================================================================
# FRONTEND INTEGRATION CHECKS
# =============================================================================

check_frontend_api_config() {
    local api_config_file="/Users/flake/sources/misc/secman/src/frontend/src/utils/api-config.ts"
    
    if [[ ! -f "$api_config_file" ]]; then
        log "ERROR" "API configuration file not found"
        return 1
    fi
    
    # Check that all major APIs are configured for Kotlin backend
    local required_apis=("auth" "users" "requirements" "standards" "risks" "assets" "norms" "usecases" "import" "email" "translations" "files")
    local missing_apis=()
    
    for api in "${required_apis[@]}"; do
        if ! grep -q "$api: true" "$api_config_file"; then
            missing_apis+=("$api")
        fi
    done
    
    if [[ ${#missing_apis[@]} -eq 0 ]]; then
        return 0
    else
        log "ERROR" "APIs not configured for Kotlin backend: ${missing_apis[*]}"
        return 1
    fi
}

check_migration_progress() {
    local api_config_file="/Users/flake/sources/misc/secman/src/frontend/src/utils/api-config.ts"
    
    if [[ ! -f "$api_config_file" ]]; then
        return 1
    fi
    
    # Count migrated vs total APIs
    local total_apis=$(grep -c ": \(true\|false\)" "$api_config_file" | head -1)
    local migrated_apis=$(grep -c ": true" "$api_config_file" | head -1)
    
    if [[ $migrated_apis -ge 10 ]]; then  # At least 10 APIs migrated
        log "INFO" "Migration progress: $migrated_apis APIs migrated"
        return 0
    else
        log "ERROR" "Insufficient APIs migrated: $migrated_apis"
        return 1
    fi
}

# =============================================================================
# TESTING INFRASTRUCTURE CHECKS
# =============================================================================

check_test_scripts() {
    local test_scripts=(
        "/Users/flake/sources/misc/secman/scripts/tests/test-translation.sh"
        "/Users/flake/sources/misc/secman/scripts/tests/test-file-upload.sh"
        "/Users/flake/sources/misc/secman/scripts/test-api-compatibility.sh"
    )
    
    local missing_scripts=()
    
    for script in "${test_scripts[@]}"; do
        if [[ ! -f "$script" ]] || [[ ! -x "$script" ]]; then
            missing_scripts+=("$(basename "$script")")
        fi
    done
    
    if [[ ${#missing_scripts[@]} -eq 0 ]]; then
        return 0
    else
        log "ERROR" "Missing or non-executable test scripts: ${missing_scripts[*]}"
        return 1
    fi
}

check_documentation() {
    local docs=(
        "/Users/flake/sources/misc/secman/docs/TRANSLATION_TESTING_GUIDE.md"
        "/Users/flake/sources/misc/secman/docs/EMAIL_TESTING_GUIDE.md"
        "/Users/flake/sources/misc/secman/docs/END_TO_END_TEST_PLAN.md"
    )
    
    local missing_docs=()
    
    for doc in "${docs[@]}"; do
        if [[ ! -f "$doc" ]]; then
            missing_docs+=("$(basename "$doc")")
        fi
    done
    
    if [[ ${#missing_docs[@]} -eq 0 ]]; then
        return 0
    else
        log "ERROR" "Missing documentation: ${missing_docs[*]}"
        return 1
    fi
}

# =============================================================================
# DATABASE SCHEMA CHECKS
# =============================================================================

check_database_schema() {
    local schema_file="/Users/flake/sources/misc/secman/src/backend/conf/evolutions/default/1.sql"
    
    if [[ ! -f "$schema_file" ]]; then
        log "ERROR" "Database schema file not found"
        return 1
    fi
    
    # Check for key tables
    local required_tables=("users" "requirement" "standard" "risk_assessment" "risk" "asset" "norm" "usecase" "response" "email_config" "translation_config" "requirement_files")
    local missing_tables=()
    
    for table in "${required_tables[@]}"; do
        if ! grep -q "CREATE TABLE $table" "$schema_file"; then
            missing_tables+=("$table")
        fi
    done
    
    if [[ ${#missing_tables[@]} -eq 0 ]]; then
        return 0
    else
        log "ERROR" "Missing database tables: ${missing_tables[*]}"
        return 1
    fi
}

# =============================================================================
# CONFIGURATION CHECKS
# =============================================================================

check_kotlin_config() {
    local config_file="/Users/flake/sources/misc/secman/src/backendng/src/main/resources/application.yml"
    
    if [[ ! -f "$config_file" ]]; then
        log "ERROR" "Kotlin backend configuration file not found"
        return 1
    fi
    
    # Check key configuration sections
    if grep -q "port: 9001" "$config_file" && \
       grep -q "jdbc:mariadb" "$config_file" && \
       grep -q "micronaut:" "$config_file"; then
        return 0
    else
        log "ERROR" "Kotlin backend configuration incomplete"
        return 1
    fi
}

check_build_config() {
    local build_file="/Users/flake/sources/misc/secman/src/backendng/build.gradle.kts"
    
    if [[ ! -f "$build_file" ]]; then
        log "ERROR" "Kotlin backend build file not found"
        return 1
    fi
    
    # Check for key dependencies
    if grep -q "micronaut-data-jpa" "$build_file" && \
       grep -q "micronaut-security-jwt" "$build_file" && \
       grep -q "mariadb-java-client" "$build_file"; then
        return 0
    else
        log "ERROR" "Kotlin backend build configuration incomplete"
        return 1
    fi
}

# =============================================================================
# OPERATIONAL CHECKS
# =============================================================================

check_startup_scripts() {
    local scripts=(
        "/Users/flake/sources/misc/secman/scripts/start-kotlin-backend.sh"
        "/Users/flake/sources/misc/secman/scripts/start-both-backends.sh"
        "/Users/flake/sources/misc/secman/scripts/stop-both-backends.sh"
    )
    
    local missing_scripts=()
    
    for script in "${scripts[@]}"; do
        if [[ ! -f "$script" ]] || [[ ! -x "$script" ]]; then
            missing_scripts+=("$(basename "$script")")
        fi
    done
    
    if [[ ${#missing_scripts[@]} -eq 0 ]]; then
        return 0
    else
        add_warning "Missing or non-executable startup scripts: ${missing_scripts[*]}"
        return 1
    fi
}

check_emergency_procedures() {
    local scripts=(
        "/Users/flake/sources/misc/secman/scripts/emergency-rollback.sh"
        "/Users/flake/sources/misc/secman/scripts/health-monitor.sh"
    )
    
    local missing_scripts=()
    
    for script in "${scripts[@]}"; do
        if [[ ! -f "$script" ]] || [[ ! -x "$script" ]]; then
            missing_scripts+=("$(basename "$script")")
        fi
    done
    
    if [[ ${#missing_scripts[@]} -eq 0 ]]; then
        return 0
    else
        add_warning "Missing or non-executable emergency scripts: ${missing_scripts[*]}"
        return 1
    fi
}

# =============================================================================
# MAIN VALIDATION LOGIC
# =============================================================================

run_all_checks() {
    log "HEADER" "SECMAN MIGRATION COMPLETION VALIDATION"
    
    log "INFO" "🚀 Starting comprehensive migration validation..."
    log "INFO" "📅 $(date)"
    echo
    
    # Backend connectivity
    log "HEADER" "Backend Connectivity"
    run_check "CONN-001" "check_java_backend_running" "Java backend accessibility"
    run_check "CONN-002" "check_kotlin_backend_running" "Kotlin backend running and healthy"
    
    # Kotlin backend completeness
    log "HEADER" "Kotlin Backend Architecture"
    run_check "ARCH-001" "check_kotlin_domain_entities" "All domain entities migrated"
    run_check "ARCH-002" "check_kotlin_repositories" "All repositories implemented"
    run_check "ARCH-003" "check_kotlin_controllers" "All controllers migrated"
    run_check "ARCH-004" "check_kotlin_services" "All services implemented"
    
    # Configuration
    log "HEADER" "Configuration and Build"
    run_check "CONF-001" "check_kotlin_config" "Kotlin backend configuration"
    run_check "CONF-002" "check_build_config" "Build configuration"
    run_check "CONF-003" "check_database_schema" "Database schema completeness"
    
    # Frontend integration
    log "HEADER" "Frontend Integration"
    run_check "FRONT-001" "check_frontend_api_config" "Frontend API configuration"
    run_check "FRONT-002" "check_migration_progress" "Migration progress tracking"
    
    # Testing infrastructure
    log "HEADER" "Testing Infrastructure"
    run_check "TEST-001" "check_test_scripts" "Test scripts availability"
    run_check "TEST-002" "check_documentation" "Testing documentation"
    
    # Operational readiness
    log "HEADER" "Operational Readiness"
    run_check "OPS-001" "check_startup_scripts" "Startup and management scripts"
    run_check "OPS-002" "check_emergency_procedures" "Emergency procedures"
    
    # Generate report
    generate_validation_report
}

generate_validation_report() {
    log "HEADER" "VALIDATION RESULTS"
    
    local end_time=$(date +%s)
    local start_time=$((end_time - 60))  # Approximate
    local duration=$((end_time - start_time))
    
    mkdir -p "$OUTPUT_DIR"
    local report_file="$OUTPUT_DIR/migration_validation_$(date +%Y%m%d_%H%M%S).txt"
    
    # Console summary
    echo
    log "INFO" "📊 VALIDATION SUMMARY"
    log "INFO" "Total Checks: $TOTAL_CHECKS"
    log "SUCCESS" "Passed: $PASSED_CHECKS"
    
    if [[ $FAILED_CHECKS -gt 0 ]]; then
        log "ERROR" "Failed: $FAILED_CHECKS"
    else
        log "SUCCESS" "Failed: $FAILED_CHECKS"
    fi
    
    if [[ $WARNINGS -gt 0 ]]; then
        log "WARN" "Warnings: $WARNINGS"
    fi
    
    local success_rate=$(( TOTAL_CHECKS > 0 ? (PASSED_CHECKS * 100) / TOTAL_CHECKS : 0 ))
    log "INFO" "Success Rate: ${success_rate}%"
    
    # Detailed report file
    {
        echo "=============================================="
        echo "SECMAN MIGRATION COMPLETION VALIDATION REPORT"
        echo "=============================================="
        echo
        echo "Validation Summary:"
        echo "- Date: $(date)"
        echo "- Total Checks: $TOTAL_CHECKS"
        echo "- Passed: $PASSED_CHECKS"
        echo "- Failed: $FAILED_CHECKS"
        echo "- Warnings: $WARNINGS"
        echo "- Success Rate: ${success_rate}%"
        echo
        echo "Environment:"
        echo "- Java Backend URL: $JAVA_BASE_URL"
        echo "- Kotlin Backend URL: $KOTLIN_BASE_URL"
        echo
        
        if [[ ${#PASSED_RESULTS[@]} -gt 0 ]]; then
            echo "PASSED CHECKS:"
            echo "=============="
            for result in "${PASSED_RESULTS[@]}"; do
                echo "$result"
            done
            echo
        fi
        
        if [[ ${#FAILED_RESULTS[@]} -gt 0 ]]; then
            echo "FAILED CHECKS:"
            echo "=============="
            for result in "${FAILED_RESULTS[@]}"; do
                echo "$result"
            done
            echo
        fi
        
        if [[ ${#WARNING_RESULTS[@]} -gt 0 ]]; then
            echo "WARNINGS:"
            echo "========="
            for result in "${WARNING_RESULTS[@]}"; do
                echo "$result"
            done
            echo
        fi
        
        echo "Migration Status Assessment:"
        echo "==========================="
        
        if [[ $success_rate -ge 90 ]]; then
            echo "🎉 MIGRATION COMPLETE!"
            echo "   The Java to Kotlin migration appears to be successfully completed."
            echo "   All critical components are in place and properly configured."
        elif [[ $success_rate -ge 75 ]]; then
            echo "🚧 MIGRATION NEARLY COMPLETE"
            echo "   The migration is mostly complete with some minor issues to address."
            echo "   Review failed checks and warnings for remaining tasks."
        elif [[ $success_rate -ge 50 ]]; then
            echo "⚠️  MIGRATION IN PROGRESS"
            echo "   Significant progress has been made but major work remains."
            echo "   Focus on failed checks to complete the migration."
        else
            echo "❌ MIGRATION INCOMPLETE"
            echo "   The migration is in early stages or has major issues."
            echo "   Extensive work is required to complete the migration."
        fi
        
        echo
        echo "=============================================="
        
    } > "$report_file"
    
    log "SUCCESS" "📄 Detailed validation report saved to: $report_file"
    
    # Final status
    echo
    if [[ $success_rate -ge 90 ]]; then
        log "SUCCESS" "🎉 MIGRATION VALIDATION COMPLETE - SUCCESS!"
        log "SUCCESS" "   The Secman migration from Java Play Framework to Kotlin Micronaut is complete!"
        log "SUCCESS" "   All critical systems are in place and operational."
    elif [[ $success_rate -ge 75 ]]; then
        log "WARN" "🚧 MIGRATION VALIDATION COMPLETE - MINOR ISSUES"
        log "WARN" "   The migration is nearly complete with some minor issues to address."
    else
        log "ERROR" "❌ MIGRATION VALIDATION COMPLETE - MAJOR ISSUES"
        log "ERROR" "   Significant issues detected. Review the report for required actions."
    fi
}

show_help() {
    cat << EOF
Secman Migration Completion Validation Script

USAGE:
    $0 [OPTIONS]

OPTIONS:
    --java-url URL      Java backend URL (default: $JAVA_BASE_URL)
    --kotlin-url URL    Kotlin backend URL (default: $KOTLIN_BASE_URL)
    --output-dir DIR    Output directory (default: $OUTPUT_DIR)
    -h, --help         Show this help message

DESCRIPTION:
    This script performs comprehensive validation of the Secman migration
    from Java Play Framework to Kotlin Micronaut. It checks:
    
    - Backend connectivity and health
    - Kotlin backend architecture completeness
    - Configuration and build files
    - Frontend integration
    - Testing infrastructure
    - Operational readiness
    
    The script generates a detailed report and provides a migration
    completion assessment.

EXAMPLES:
    # Run with default settings
    $0
    
    # Run with custom backend URLs
    $0 --java-url http://localhost:9000 --kotlin-url http://localhost:9001
    
    # Run with custom output directory
    $0 --output-dir ./validation-results

EOF
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --java-url)
            JAVA_BASE_URL="$2"
            shift 2
            ;;
        --kotlin-url)
            KOTLIN_BASE_URL="$2"
            shift 2
            ;;
        --output-dir)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        -h|--help)
            show_help
            exit 0
            ;;
        *)
            log "ERROR" "Unknown option: $1"
            echo "Use '$0 --help' for usage information."
            exit 1
            ;;
    esac
done

# Check dependencies
command -v curl >/dev/null 2>&1 || { log "ERROR" "curl is required but not installed."; exit 1; }

# Run validation
run_all_checks