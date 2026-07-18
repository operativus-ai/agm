#!/bin/bash

# 🔧 Gettabuzz React App - Duplicate Code Cleanup Script
# Removes duplicate utilities and consolidates component structure
# REQUIRES MANUAL IMPORT UPDATES AFTER RUNNING

set -e  # Exit on any error

echo "🔧 Starting duplicate code cleanup of Gettabuzz React App..."
echo "==========================================================="

# Ensure we're in the right directory
if [ ! -f "package.json" ]; then
    echo "❌ Error: Must be run from project root (where package.json exists)"
    exit 1
fi

echo ""
echo "📝 Creating safety backup commit..."
git add .
git commit -m "Pre-cleanup backup: before removing duplicates" || echo "No changes to commit"

echo ""
echo "🗂️  Phase 1: Analyzing Duplicate Utilities..."
echo "--------------------------------------------"

# Check which files in app/utils are exact duplicates of shared/utils
echo "🔍 Comparing app/utils vs shared/utils files..."

UTILS_TO_REMOVE=()
SAVED_BYTES=0

if [ -d "src/app/utils" ] && [ -d "src/shared/utils" ]; then
    for file in src/app/utils/*.ts; do
        if [ -f "$file" ]; then
            filename=$(basename "$file")
            shared_file="src/shared/utils/$filename"
            
            if [ -f "$shared_file" ]; then
                if diff -q "$file" "$shared_file" >/dev/null 2>&1; then
                    SIZE=$(wc -c < "$file" 2>/dev/null || echo 0)
                    SAVED_BYTES=$((SAVED_BYTES + SIZE))
                    UTILS_TO_REMOVE+=("$file")
                    echo "✓ Exact duplicate found: $filename ($(numfmt --to=iec --suffix=B $SIZE))"
                else
                    echo "⚠️  Files differ: $filename (manual review needed)"
                fi
            else
                echo "⚠️  Only in app/utils: $filename (manual review needed)"
            fi
        fi
    done
else
    echo "⚠️  Utils directories not found as expected"
fi

echo ""
echo "🗂️  Phase 2: Removing Duplicate Utilities..."
echo "--------------------------------------------"

if [ ${#UTILS_TO_REMOVE[@]} -gt 0 ]; then
    echo "🗑️  Removing $(${#UTILS_TO_REMOVE[@]}) duplicate utility files..."
    
    for file in "${UTILS_TO_REMOVE[@]}"; do
        echo "🗑️  Removing: $file"
        rm "$file"
    done
    
    # Remove app/utils directory if it's now empty (except .gitkeep)
    if [ -d "src/app/utils" ]; then
        remaining=$(find src/app/utils -name "*.ts" -o -name "*.tsx" | wc -l)
        if [ "$remaining" -eq 0 ]; then
            echo "🗑️  Removing empty app/utils directory..."
            rm -rf src/app/utils
        fi
    fi
else
    echo "ℹ️  No exact duplicate utilities found to remove"
fi

echo ""
echo "🗂️  Phase 3: Removing Migrated Card Components..."
echo "------------------------------------------------"

if [ -d "src/shared/components/ui/card/migrated" ]; then
    MIGRATED_SIZE=$(du -sb src/shared/components/ui/card/migrated 2>/dev/null | cut -f1)
    SAVED_BYTES=$((SAVED_BYTES + MIGRATED_SIZE))
    echo "🗑️  Removing migrated card components directory ($(numfmt --to=iec --suffix=B $MIGRATED_SIZE))..."
    rm -rf src/shared/components/ui/card/migrated/
else
    echo "⏩ Migrated card components directory not found"
fi

echo ""
echo "🗂️  Phase 4: Finding Remaining Duplicates..."
echo "--------------------------------------------"

echo "🔍 Scanning for additional potential duplicates..."

# Look for files with similar names in different directories
echo ""
echo "📋 Similar Card components found:"
find src -name "*Card*.tsx" -type f | sort | while read -r file; do
    basename=$(basename "$file" .tsx)
    echo "  - $file"
done | head -10

echo ""
echo "📋 Files that may need manual review:"
# Find files that might be duplicates but with different names
find src \( -name "*Basic*" -o -name "*Metric*" -o -name "*Stats*" -o -name "*Action*" \) -name "*.tsx" -type f | while read -r file; do
    echo "  - $file"
done

echo ""
echo "📊 Cleanup Summary"
echo "=================="
echo "✅ Duplicate utilities analyzed and removed"
echo "✅ Migrated components removed"
echo "✅ Total space saved: $(numfmt --to=iec --suffix=B $SAVED_BYTES)"

# Count remaining files
REMAINING_CARDS=$(find src -name "*Card*.tsx" -type f | wc -l)
REMAINING_UTILS=$(find src -path "*/utils/*.ts" -type f | wc -l)

echo "📈 Remaining files:"
echo "  - Card components: $REMAINING_CARDS"
echo "  - Utility files: $REMAINING_UTILS"

echo ""
echo "🔄 CRITICAL NEXT STEPS"
echo "======================"
echo "⚠️  MANUAL ACTIONS REQUIRED:"
echo ""
echo "1. Update imports from @app/utils to @shared/utils:"
echo "   - Search: 'from [\"'](@|\.\./)app/utils'"
echo "   - Replace: 'from \"@shared/utils\"'"
echo ""
echo "2. Update TypeScript path mappings in tsconfig.json if needed"
echo ""
echo "3. Run build to check for import errors:"
echo "   npm run validate-types"
echo ""
echo "4. Run tests to ensure functionality:"
echo "   npm test"
echo ""
echo "5. Fix any broken imports found by the build/tests"
echo ""
echo "6. Commit changes when all imports are updated:"
echo "   git add . && git commit -m 'Remove duplicate utilities and migrate imports'"

echo ""
echo "🔍 Search/Replace Commands for VS Code:"
echo "========================================"
echo "Search (regex):    from [\"'](@app/utils|@/app/utils|\\.\\./\\.\\./utils)[\"']"
echo "Replace with:      from \"@shared/utils\""
echo ""
echo "Or for terminal:   find src -name '*.ts' -o -name '*.tsx' | xargs sed -i '' 's|@app/utils|@shared/utils|g'"

echo ""
echo "✅ Duplicate cleanup phase complete!"
echo "⚠️  Remember: Update all imports before running the application!"