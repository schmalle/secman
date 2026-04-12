#!/bin/bash

# Install screenshot dependencies
print_status "Secman :: Installing screenshot dependencies..."
if command -v npm &> /dev/null; then
    npm install playwright
    npx playwright install chromium
    print_status "Screenshot dependencies installed"
else
    print_warning "npm not found, please install playwright manually:"
    print_warning "  npm install playwright"
    print_warning "  npx playwright install chromium"
fi

./installdb.sh
