#!/bin/bash
# Script to properly restart the frontend after changes

echo "🛑 Stopping any running dev servers..."
pkill -f "astro dev"
pkill -f "node.*dist/server/entry.mjs"
sleep 2

echo "🧹 Cleaning build cache..."
rm -rf dist .astro node_modules/.vite

echo "🔨 Building fresh..."
npm run build

if [ $? -eq 0 ]; then
    echo "✅ Build successful!"
    echo ""
    echo "📦 To start in production mode:"
    echo "   node dist/server/entry.mjs"
    echo ""
    echo "🔧 To start in development mode:"
    echo "   npm run dev"
else
    echo "❌ Build failed!"
    exit 1
fi
