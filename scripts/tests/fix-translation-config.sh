#!/bin/bash

# Fix Translation Configuration Script
# This script updates the translation configuration with a proper OpenRouter API key

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
BASE_URL="http://localhost:9000"
COOKIE_FILE="/tmp/secman_cookies.txt"

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
    esac
}

show_help() {
    cat << EOF
Translation Configuration Fix Script

USAGE:
    $0 [OPTIONS]

OPTIONS:
    --api-key KEY       Set the OpenRouter API key
    --help             Show this help message

EXAMPLES:
    # Set a real OpenRouter API key
    $0 --api-key sk-or-v1-1234567890abcdef...

    # Show current configuration
    $0

NOTES:
    - You need a valid OpenRouter API key from https://openrouter.ai/
    - The key should start with 'sk-or-v1-'
    - You must be authenticated to update the configuration

EOF
}

check_current_config() {
    log "INFO" "Checking current translation configuration..."
    
    local response=$(curl -s -b "$COOKIE_FILE" "$BASE_URL/api/translation-config/active" 2>/dev/null || echo '{"error": "request_failed"}')
    
    if echo "$response" | jq -e '.id' > /dev/null 2>&1; then
        local config_id=$(echo "$response" | jq -r '.id')
        local api_key=$(echo "$response" | jq -r '.apiKey')
        local model=$(echo "$response" | jq -r '.modelName')
        local is_active=$(echo "$response" | jq -r '.isActive')
        
        log "INFO" "Current configuration found:"
        log "INFO" "  - ID: $config_id"
        log "INFO" "  - API Key: $api_key"
        log "INFO" "  - Model: $model"
        log "INFO" "  - Active: $is_active"
        
        if [[ "$api_key" == "***HIDDEN***" ]]; then
            log "ERROR" "API key is a placeholder! This needs to be updated with a real OpenRouter API key."
            log "INFO" "Get your API key from: https://openrouter.ai/keys"
            log "INFO" "Then run: $0 --api-key sk-or-v1-YOUR_ACTUAL_KEY"
            return 1
        else
            log "SUCCESS" "API key appears to be set (not a placeholder)"
            return 0
        fi
    else
        log "ERROR" "No active translation configuration found"
        return 1
    fi
}

update_api_key() {
    local new_api_key="$1"
    
    if [[ -z "$new_api_key" ]]; then
        log "ERROR" "API key cannot be empty"
        return 1
    fi
    
    # Basic validation of OpenRouter API key format
    if [[ ! "$new_api_key" =~ ^sk-or-v1- ]]; then
        log "WARN" "API key doesn't start with 'sk-or-v1-' - this might not be a valid OpenRouter key"
        log "INFO" "OpenRouter keys typically start with 'sk-or-v1-'"
    fi
    
    log "INFO" "Updating translation configuration with new API key..."
    
    # Get current config ID
    local config_response=$(curl -s -b "$COOKIE_FILE" "$BASE_URL/api/translation-config/active")
    local config_id=$(echo "$config_response" | jq -r '.id')
    
    if [[ "$config_id" == "null" ]]; then
        log "ERROR" "No active configuration found to update"
        return 1
    fi
    
    # Update the configuration
    local update_data='{
        "apiKey": "'$new_api_key'"
    }'
    
    local response=$(curl -s -X PUT \
        -H "Content-Type: application/json" \
        -b "$COOKIE_FILE" \
        -d "$update_data" \
        "$BASE_URL/api/translation-config/$config_id" 2>/dev/null || echo '{"error": "request_failed"}')
    
    if echo "$response" | jq -e '.id' > /dev/null 2>&1; then
        log "SUCCESS" "API key updated successfully!"
        
        # Test the new configuration
        log "INFO" "Testing the new configuration..."
        local test_response=$(curl -s -X POST \
            -H "Content-Type: application/json" \
            -b "$COOKIE_FILE" \
            -d '{"testText": "This is a test"}' \
            "$BASE_URL/api/translation-config/$config_id/test" 2>/dev/null || echo '"Test failed"')
        
        if [[ "$test_response" == '"Translation test successful"' ]]; then
            log "SUCCESS" "Translation test passed! The API key is working correctly."
        else
            log "ERROR" "Translation test failed. Please verify your API key is correct and has proper permissions."
            log "INFO" "Test response: $test_response"
        fi
        
        return 0
    else
        log "ERROR" "Failed to update API key: $response"
        return 1
    fi
}

# Parse command line arguments
API_KEY=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --api-key)
            API_KEY="$2"
            shift 2
            ;;
        --help|-h)
            show_help
            exit 0
            ;;
        *)
            log "ERROR" "Unknown option: $1"
            show_help
            exit 1
            ;;
    esac
done

# Main execution
log "INFO" "Starting translation configuration diagnostics..."

# Check if authenticated
if [[ ! -f "$COOKIE_FILE" ]]; then
    log "ERROR" "No authentication cookie found. Please run the translation test script first to authenticate."
    exit 1
fi

if [[ -n "$API_KEY" ]]; then
    # Update API key
    update_api_key "$API_KEY"
else
    # Just check current configuration
    check_current_config
fi

log "INFO" "Translation configuration diagnostics completed"