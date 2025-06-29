#!/bin/bash

# Git hooks installer for Secman
# This script installs the pre-commit hook that takes screenshots

set -e

# Change to project root
cd "$(dirname "$0")/.."

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

print_status() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_status "Installing Git hooks for Secman..."

# Check if we're in a git repository
if [ ! -d ".git" ]; then
    echo "❌ Not in a git repository"
    exit 1
fi

# Create hooks directory if it doesn't exist
mkdir -p .git/hooks

# Install pre-commit hook
HOOK_PATH=".git/hooks/pre-commit"
SCRIPT_PATH="scripts/pre-commit-hook.sh"

if [ -f "$HOOK_PATH" ]; then
    print_warning "Pre-commit hook already exists, backing up..."
    mv "$HOOK_PATH" "$HOOK_PATH.backup.$(date +%s)"
fi

# Create the hook
cat > "$HOOK_PATH" << 'EOF'
#!/bin/bash
# Secman pre-commit hook
exec ./scripts/pre-commit-hook.sh "$@"
EOF

# Make hook executable
chmod +x "$HOOK_PATH"

print_status "✅ Pre-commit hook installed successfully!"
print_status "The hook will:"
print_status "  - Run backend tests before each commit"
print_status "  - Take screenshots of the UI"
print_status "  - Add screenshots to the commit"

print_status ""
print_status "To temporarily skip the hook, use: git commit --no-verify"
print_status ""

# Install screenshot dependencies
print_status "Installing screenshot dependencies..."
if command -v npm &> /dev/null; then
    npm install playwright
    npx playwright install chromium
    print_status "✅ Screenshot dependencies installed"
else
    print_warning "npm not found, please install playwright manually:"
    print_warning "  npm install playwright"
    print_warning "  npx playwright install chromium"
fi

print_status "🎉 Git hooks setup completed!"