---
name: fix-compile-errors
description: Fix compile/build errors using a minimal-token, error-driven workflow. This skill prevents unnecessary file reads and avoids full codebase scanning.
tools: ["mvn compile", "tsc", "yarn build"]
---

# Skill: Fix Compile Errors (Low Token Mode)

## Purpose
Fix compile/build errors using a minimal-token, error-driven workflow. This skill prevents unnecessary file reads and avoids full codebase scanning.

## When to Use
Use this skill when:
* The user asks to fix compile errors
* Build failures are present (Java, TypeScript, etc.)
* Error logs are provided

## Core Principles
* Treat compiler output as the ONLY source of truth
* Never explore the entire repository
* Never read full files unless absolutely necessary
* Always operate on ONE error at a time
* Prefer summaries over raw code
* Reuse context; do not reread the same code

## Hard Rules
* ALWAYS start from compiler errors (not file exploration)
* ALWAYS locate file + line before reading code
* NEVER read more than 50 lines at a time unless required
* NEVER load unrelated files
* NEVER fix multiple errors simultaneously
* NEVER reread previously analyzed code unless required

## Execution Loop

### Step 1 — Select Error
* Take the first unresolved compile error

### Step 2 — Locate
* Identify:
  * file
  * line number
  * symbol or type involved

### Step 3 — Minimal Read
* Read only:
  * the method or function containing the error
  * or 20–50 lines around the error
* DO NOT read the full file

### Step 4 — Analyze
Produce a short summary:
* what the code is doing
* why the compile error occurs
* what dependency or symbol is missing/broken

### Step 5 — Fix
* Apply the smallest possible fix:
  * missing import
  * method signature correction
  * type fix
  * nullability / generics fix
* Avoid refactoring unless required

### Step 6 — Cache Knowledge
* Store a short working note:
  * file
  * issue
  * fix applied

### Step 7 — Repeat
* Move to the next compile error

## Optimization Strategies

### Partial Reads Only
* Prefer:
  * method-level reads
  * small line ranges
* Avoid:
  * full file ingestion

### Progressive Disclosure
* Expand code only if current context is insufficient

### Summary Reuse
* Use cached summaries instead of rereading files

### Noise Reduction
Ignore:
* imports (unless relevant)
* comments
* test files
* generated code

## Output Format
For each error:
### Error
### Location
### Summary
### Fix
### Notes

## Anti-Patterns to Avoid
* Scanning entire directories
* Loading multiple files upfront
* Re-reading the same file multiple times
* Fixing multiple errors in a single step
* Making speculative changes without compiler evidence

## Success Criteria
* All compile errors resolved
* Minimal token usage
* Minimal file reads
* Changes are precise and isolated
