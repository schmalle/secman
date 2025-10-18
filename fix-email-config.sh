#!/bin/bash
# Fix Email Configuration with Masked Username
# This script helps fix email configs that have the masked value stored as the username

set -e

BACKEND_URL="${BACKEND_URL:-http://localhost:8080}"

echo "========================================="
echo "Email Configuration Username Fix"
echo "========================================="
echo ""
echo "This script fixes the issue where '***HIDDEN***' was saved as the username"
echo "instead of your actual email address."
echo ""

# Get admin credentials
read -p "Admin username: " ADMIN_USER
read -sp "Admin password: " ADMIN_PASS
echo ""
echo ""

# Login to get JWT token
echo "1. Logging in as admin..."
TOKEN_RESPONSE=$(curl -s -X POST "${BACKEND_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"${ADMIN_USER}\",\"password\":\"${ADMIN_PASS}\"}")

TOKEN=$(echo "$TOKEN_RESPONSE" | grep -o '"token":"[^"]*' | sed 's/"token":"//')

if [ -z "$TOKEN" ]; then
    echo "❌ Failed to login. Check your credentials."
    exit 1
fi

echo "✅ Logged in successfully"
echo ""

# Get all email configs
echo "2. Fetching email configurations..."
CONFIGS=$(curl -s -X GET "${BACKEND_URL}/api/email-config" \
  -H "Authorization: Bearer ${TOKEN}")

echo "$CONFIGS" | python3 -m json.tool 2>/dev/null || echo "$CONFIGS"
echo ""

# Ask user what to do
echo "========================================="
echo "Fix Options"
echo "========================================="
echo ""
echo "Choose how to fix the configuration:"
echo ""
echo "1. Delete existing config and create new one (RECOMMENDED)"
echo "2. Update existing config with correct username"
echo ""
read -p "Choose option (1 or 2): " OPTION

if [ "$OPTION" == "1" ]; then
    echo ""
    echo "========================================="
    echo "Option 1: Delete and Recreate"
    echo "========================================="
    echo ""

    read -p "Enter the ID of the config to delete: " CONFIG_ID

    echo "Deleting config ID ${CONFIG_ID}..."
    DELETE_RESPONSE=$(curl -s -X DELETE "${BACKEND_URL}/api/email-config/${CONFIG_ID}" \
      -H "Authorization: Bearer ${TOKEN}")

    echo "Response: $DELETE_RESPONSE"
    echo ""

    echo "Now let's create a new configuration with correct settings:"
    echo ""

    read -p "Configuration name (e.g., Gmail SMTP): " CONFIG_NAME
    read -p "SMTP host (e.g., smtp.gmail.com): " SMTP_HOST
    read -p "SMTP port (e.g., 587): " SMTP_PORT
    read -p "Enable TLS? (true/false, default: true): " SMTP_TLS
    SMTP_TLS=${SMTP_TLS:-true}
    read -p "Enable SSL? (true/false, default: false): " SMTP_SSL
    SMTP_SSL=${SMTP_SSL:-false}

    echo ""
    echo "⚠️  IMPORTANT: Enter your FULL email address as username"
    read -p "SMTP username (your.email@gmail.com): " SMTP_USERNAME

    echo ""
    echo "⚠️  IMPORTANT: Enter your app password WITHOUT spaces"
    echo "If your app password is: abcd efgh ijkl mnop"
    echo "Enter it as: abcdefghijklmnop"
    read -sp "SMTP password (app password, no spaces): " SMTP_PASSWORD
    echo ""

    read -p "From email (same as username): " FROM_EMAIL
    read -p "From name (e.g., SecMan Notifications): " FROM_NAME
    read -p "Set as active? (true/false, default: true): " IS_ACTIVE
    IS_ACTIVE=${IS_ACTIVE:-true}

    echo ""
    echo "Creating new configuration..."

    CREATE_RESPONSE=$(curl -s -X POST "${BACKEND_URL}/api/email-config" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "{
        \"name\": \"${CONFIG_NAME}\",
        \"smtpHost\": \"${SMTP_HOST}\",
        \"smtpPort\": ${SMTP_PORT},
        \"smtpTls\": ${SMTP_TLS},
        \"smtpSsl\": ${SMTP_SSL},
        \"smtpUsername\": \"${SMTP_USERNAME}\",
        \"smtpPassword\": \"${SMTP_PASSWORD}\",
        \"fromEmail\": \"${FROM_EMAIL}\",
        \"fromName\": \"${FROM_NAME}\",
        \"isActive\": ${IS_ACTIVE}
      }")

    echo "$CREATE_RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$CREATE_RESPONSE"
    echo ""

    NEW_CONFIG_ID=$(echo "$CREATE_RESPONSE" | grep -o '"id":[0-9]*' | head -1 | sed 's/"id"://')

    if [ -n "$NEW_CONFIG_ID" ]; then
        echo "✅ Created new configuration with ID: ${NEW_CONFIG_ID}"
        echo ""
        echo "Now let's test it:"
        read -p "Enter test email address: " TEST_EMAIL

        echo "Sending test email..."
        TEST_RESPONSE=$(curl -s -X POST "${BACKEND_URL}/api/email-config/${NEW_CONFIG_ID}/test" \
          -H "Authorization: Bearer ${TOKEN}" \
          -H "Content-Type: application/json" \
          -d "{\"testEmail\": \"${TEST_EMAIL}\"}")

        echo "$TEST_RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$TEST_RESPONSE"
    else
        echo "❌ Failed to create configuration. Check the response above for errors."
    fi

elif [ "$OPTION" == "2" ]; then
    echo ""
    echo "========================================="
    echo "Option 2: Update Existing Config"
    echo "========================================="
    echo ""

    read -p "Enter the ID of the config to update: " CONFIG_ID

    echo ""
    echo "⚠️  IMPORTANT: Enter your FULL email address as username"
    read -p "SMTP username (your.email@gmail.com): " SMTP_USERNAME

    echo ""
    echo "⚠️  IMPORTANT: Enter your app password WITHOUT spaces"
    echo "If your app password is: abcd efgh ijkl mnop"
    echo "Enter it as: abcdefghijklmnop"
    read -sp "SMTP password (app password, no spaces): " SMTP_PASSWORD
    echo ""
    echo ""

    echo "Updating configuration..."

    UPDATE_RESPONSE=$(curl -s -X PUT "${BACKEND_URL}/api/email-config/${CONFIG_ID}" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "{
        \"smtpUsername\": \"${SMTP_USERNAME}\",
        \"smtpPassword\": \"${SMTP_PASSWORD}\"
      }")

    echo "$UPDATE_RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$UPDATE_RESPONSE"
    echo ""

    echo "✅ Updated configuration"
    echo ""
    echo "Now let's test it:"
    read -p "Enter test email address: " TEST_EMAIL

    echo "Sending test email..."
    TEST_RESPONSE=$(curl -s -X POST "${BACKEND_URL}/api/email-config/${CONFIG_ID}/test" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "{\"testEmail\": \"${TEST_EMAIL}\"}")

    echo "$TEST_RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$TEST_RESPONSE"

else
    echo "Invalid option"
    exit 1
fi

echo ""
echo "========================================="
echo "Done!"
echo "========================================="
echo ""
echo "Check the logs to verify the test email was sent successfully:"
echo "  tail -f src/logs/kotlin-backend.log | grep -i 'email\\|smtp'"
echo ""
echo "Expected success log:"
echo "  'Successfully sent email to ... with subject: ...'"
echo "  'Updated email configuration: smtp.gmail.com (active: true, username: your.email@gmail.com)'"
echo ""
