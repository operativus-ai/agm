#!/usr/bin/env node
/**
 * audit-api-calls.mjs
 *
 * Walks every *Api.ts file under src/ and produces an authoritative list
 * of backend endpoints the UI calls. Resolves `const BASE = '...'` /
 * `const BASE_PATH = '...'` constants and substitutes them inside
 * template literals — the step previous string-grep enumerators
 * skipped, which produced multiple false-positive waves during the
 * 2026-04-24 parity audits.
 *
 * Usage:
 *   node scripts/audit-api-calls.mjs              # human-readable markdown
 *   node scripts/audit-api-calls.mjs --json        # JSON output
 *   node scripts/audit-api-calls.mjs --bare        # one path per line
 *
 * Heuristics (intentionally simple — no full TS parser):
 *   - Files matched by glob: src/**\/api/*.ts plus any file ending
 *     `Api.ts` under src/.
 *   - In each file, find every line declaring
 *     `const <NAME> = '<literal>'` or `const <NAME>: <type> = '<literal>'`
 *     where NAME starts with `BASE` (e.g. BASE, BASE_PATH, BASE_URL,
 *     API_BASE_URL).
 *   - For each call to ApiClient.<method>(...) or apiClient.<method>(...)
 *     where method is one of: get | post | put | patch | delete | stream |
 *     request — extract the first string argument (literal or template
 *     literal), substituting any `${NAME}` token using the BASE map.
 *   - Calls that can't be resolved (e.g. dynamic-only paths) are
 *     reported with `[unresolved]`.
 *
 * Limitations:
 *   - Doesn't follow imports across files. Each file is parsed in
 *     isolation. If a path constant lives in a different file it'll
 *     be reported [unresolved].
 *   - Doesn't traverse expressions like `path + '/x'` (string
 *     concatenation). Template literals only.
 *   - Doesn't run the TS compiler — relies on textual patterns. Edge
 *     cases (multi-line declarations, JSX-embedded calls) may slip.
 *
 * If output looks suspicious, fall back to ts-morph or similar AST
 * tooling.
 */

import { promises as fs } from 'node:fs';
import path from 'node:path';
import url from 'node:url';

const __dirname = path.dirname(url.fileURLToPath(import.meta.url));
const SRC_ROOT = path.resolve(__dirname, '..', 'src');

// ────────────────────────────────────────────────────────────────────
// Argv parsing
// ────────────────────────────────────────────────────────────────────

const argv = new Set(process.argv.slice(2));
const OUTPUT_MODE = argv.has('--json') ? 'json' : argv.has('--bare') ? 'bare' : 'markdown';

// ────────────────────────────────────────────────────────────────────
// File discovery
// ────────────────────────────────────────────────────────────────────

async function* walk(dir) {
  for (const entry of await fs.readdir(dir, { withFileTypes: true })) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules' || entry.name === 'dist' || entry.name.startsWith('.')) continue;
      yield* walk(fullPath);
    } else if (entry.isFile()) {
      yield fullPath;
    }
  }
}

function isApiFile(absPath) {
  if (!absPath.endsWith('.ts') && !absPath.endsWith('.tsx')) return false;
  if (absPath.endsWith('.test.ts') || absPath.endsWith('.test.tsx')) return false;
  if (absPath.endsWith('.d.ts')) return false;
  // Match either `/api/` directory or `*Api.ts` filename.
  return /\/api\/[^/]+\.ts$/.test(absPath) || /Api\.ts$/.test(absPath);
}

// ────────────────────────────────────────────────────────────────────
// Per-file extraction
// ────────────────────────────────────────────────────────────────────

const BASE_DECL_RE = /(?:^|\n)\s*(?:export\s+)?const\s+(BASE\w*|API_BASE_URL|API_PATH)\s*(?::\s*\w+)?\s*=\s*['"`]([^'"`]+)['"`]\s*;?/g;

const CALL_RE = /\bApiClient\s*\.\s*(get|post|put|patch|delete|stream|request)\s*(?:<[^>]*>)?\s*\(/g;

// Raw fetch() calls — common for FormData uploads and stream-with-auth
// where ApiClient doesn't fit. Method is determined by the second-argument
// options object's `method:` field; defaults to GET.
const FETCH_CALL_RE = /(?:^|[^.\w])fetch\s*\(/g;

// Axios-style calls. Less common in this codebase but possible.
const AXIOS_CALL_RE = /\baxios\s*\.\s*(get|post|put|patch|delete|head|options)\s*\(/g;

// Find the matching close-paren for a `fetch(` call so we can scan its
// argument list for a `method:` field. Returns the index just past `)` or
// null on parse failure.
function findMatchingClose(source, openIdx) {
  let depth = 1;
  let i = openIdx + 1;
  let inStr = null; // ' " ` or null
  let inTpl = 0; // depth of ${} inside template literal
  while (i < source.length) {
    const c = source[i];
    if (inStr) {
      if (c === '\\') { i += 2; continue; }
      if (inStr === '`') {
        if (c === '$' && source[i + 1] === '{') { inTpl++; i += 2; continue; }
        if (c === '}' && inTpl > 0) { inTpl--; i++; continue; }
      }
      if (c === inStr && inTpl === 0) inStr = null;
      i++;
      continue;
    }
    if (c === "'" || c === '"' || c === '`') { inStr = c; i++; continue; }
    if (c === '(') depth++;
    else if (c === ')') {
      depth--;
      if (depth === 0) return i + 1;
    }
    i++;
  }
  return null;
}

// Parse the HTTP method out of a fetch options object. Looks for
// `method: 'POST'` / `method: "DELETE"` patterns; case-insensitive on the
// method value. Returns 'GET' if no method field found (browser default).
function detectFetchMethod(source, optionsStart, optionsEnd) {
  if (optionsEnd <= optionsStart) return 'GET';
  const slice = source.slice(optionsStart, optionsEnd);
  const m = slice.match(/method\s*:\s*['"`](\w+)['"`]/);
  if (m) return m[1].toUpperCase();
  return 'GET';
}

/**
 * Extract the first argument expression (string literal or template literal)
 * starting at `idx` in `source`. Returns { raw, end } where `raw` is the
 * literal text (without surrounding quotes/backticks) and `end` is the index
 * just past the closing delimiter.
 *
 * Returns null if the argument isn't a string/template literal (e.g. a
 * variable identifier that we can't resolve).
 */
function extractFirstStringArg(source, idx) {
  // Skip whitespace
  while (idx < source.length && /\s/.test(source[idx])) idx++;
  const ch = source[idx];
  if (ch === "'" || ch === '"') {
    let end = idx + 1;
    while (end < source.length) {
      if (source[end] === '\\') { end += 2; continue; }
      if (source[end] === ch) break;
      end++;
    }
    return { raw: source.slice(idx + 1, end), end: end + 1, kind: 'literal' };
  }
  if (ch === '`') {
    let end = idx + 1;
    let depth = 0;
    while (end < source.length) {
      if (source[end] === '\\') { end += 2; continue; }
      if (source[end] === '`' && depth === 0) break;
      if (source[end] === '$' && source[end + 1] === '{') {
        depth++;
        end += 2;
        continue;
      }
      if (source[end] === '}' && depth > 0) {
        depth--;
      }
      end++;
    }
    return { raw: source.slice(idx + 1, end), end: end + 1, kind: 'template' };
  }
  return null;
}

function resolveTemplate(raw, baseMap) {
  // Replace ${NAME} where NAME is a known base; leave other ${...}
  // expressions intact (probably runtime-resolved param IDs).
  return raw.replace(/\$\{(\w+)\}/g, (full, name) => {
    if (Object.prototype.hasOwnProperty.call(baseMap, name)) return baseMap[name];
    return full;
  });
}

async function auditFile(absPath, repoRoot) {
  const source = await fs.readFile(absPath, 'utf8');
  const baseMap = {};
  for (const m of source.matchAll(BASE_DECL_RE)) {
    baseMap[m[1]] = m[2];
  }

  const results = [];
  for (const m of source.matchAll(CALL_RE)) {
    const method = m[1].toUpperCase();
    const callStart = m.index + m[0].length; // index just past `(`
    const arg = extractFirstStringArg(source, callStart);
    if (!arg) {
      results.push({
        file: path.relative(repoRoot, absPath),
        method,
        path: '[unresolved]',
        kind: 'non-literal-arg',
        raw: null,
      });
      continue;
    }
    const resolved = arg.kind === 'literal' ? arg.raw : resolveTemplate(arg.raw, baseMap);
    const isResolved = !/\$\{[A-Z_][A-Z0-9_]*\}/.test(resolved);
    results.push({
      file: path.relative(repoRoot, absPath),
      method,
      path: resolved.split('?')[0], // strip query string for tabling; full kept in raw
      kind: isResolved ? arg.kind : 'partially-resolved',
      raw: resolved,
    });
  }

  // Raw fetch() calls — usually for FormData uploads or stream-with-auth.
  // Only record those whose URL string starts with `/api` (i.e. same-origin
  // calls to the backend). External fetch (e.g. third-party docs) is ignored.
  // Skip the ApiClient implementation itself (shared/api/client.ts) — its
  // internal fetch IS the wrapper, not a consumer call.
  const isApiClientImpl = /\bshared\/api\/client\.tsx?$/.test(absPath);
  if (isApiClientImpl) {
    return { baseMap, results };
  }
  for (const m of source.matchAll(FETCH_CALL_RE)) {
    // Match index points at start of `fetch` token (or one char before for
    // the `[^.\w]` lookbehind class). Find the actual `(` opener.
    const openParen = source.indexOf('(', m.index);
    if (openParen === -1) continue;
    const callStart = openParen + 1;
    const arg = extractFirstStringArg(source, callStart);
    if (!arg) {
      // fetch(<some-variable>, ...) — can't resolve.
      continue;
    }
    const resolved = arg.kind === 'literal' ? arg.raw : resolveTemplate(arg.raw, baseMap);
    // Only same-origin /api/ calls
    if (!resolved.startsWith('/api')) continue;

    const closeIdx = findMatchingClose(source, openParen);
    let method = 'GET';
    if (closeIdx !== null) {
      // The URL is the first arg; scan from end-of-URL to close-paren for
      // the options object's method field.
      method = detectFetchMethod(source, arg.end, closeIdx);
    }

    const isResolved = !/\$\{[A-Z_][A-Z0-9_]*\}/.test(resolved);
    results.push({
      file: path.relative(repoRoot, absPath),
      method,
      path: resolved.split('?')[0],
      kind: isResolved ? `raw-fetch (${arg.kind})` : 'raw-fetch (partially-resolved)',
      raw: resolved,
    });
  }

  // axios calls
  for (const m of source.matchAll(AXIOS_CALL_RE)) {
    const method = m[1].toUpperCase();
    const callStart = m.index + m[0].length;
    const arg = extractFirstStringArg(source, callStart);
    if (!arg) continue;
    const resolved = arg.kind === 'literal' ? arg.raw : resolveTemplate(arg.raw, baseMap);
    results.push({
      file: path.relative(repoRoot, absPath),
      method,
      path: resolved.split('?')[0],
      kind: `axios (${arg.kind})`,
      raw: resolved,
    });
  }

  // EventSource constructor calls
  for (const m of source.matchAll(/\bnew\s+EventSource\s*\(/g)) {
    const callStart = m.index + m[0].length;
    const arg = extractFirstStringArg(source, callStart);
    if (!arg) {
      results.push({
        file: path.relative(repoRoot, absPath),
        method: 'SSE',
        path: '[unresolved]',
        kind: 'non-literal-arg',
        raw: null,
      });
      continue;
    }
    const resolved = arg.kind === 'literal' ? arg.raw : resolveTemplate(arg.raw, baseMap);
    results.push({
      file: path.relative(repoRoot, absPath),
      method: 'SSE',
      path: resolved.split('?')[0],
      kind: 'event-source',
      raw: resolved,
    });
  }

  return { baseMap, results };
}

// ────────────────────────────────────────────────────────────────────
// Main
// ────────────────────────────────────────────────────────────────────

async function main() {
  const allResults = [];
  const fileBaseMaps = [];

  for await (const absPath of walk(SRC_ROOT)) {
    if (!isApiFile(absPath)) continue;
    try {
      const { baseMap, results } = await auditFile(absPath, path.resolve(SRC_ROOT, '..'));
      if (Object.keys(baseMap).length > 0 || results.length > 0) {
        fileBaseMaps.push({ file: path.relative(path.resolve(SRC_ROOT, '..'), absPath), baseMap });
      }
      allResults.push(...results);
    } catch (err) {
      console.error(`Failed to audit ${absPath}: ${err.message}`);
    }
  }

  // Sort: by resolved path, then by method
  allResults.sort((a, b) => {
    if (a.path !== b.path) return a.path < b.path ? -1 : 1;
    return a.method < b.method ? -1 : 1;
  });

  if (OUTPUT_MODE === 'json') {
    console.log(JSON.stringify({
      generatedAt: new Date().toISOString(),
      fileCount: fileBaseMaps.length,
      callCount: allResults.length,
      files: fileBaseMaps,
      calls: allResults,
    }, null, 2));
    return;
  }

  if (OUTPUT_MODE === 'bare') {
    for (const r of allResults) {
      console.log(`${r.method} ${r.path}`);
    }
    return;
  }

  // Markdown
  console.log('# Frontend API Audit\n');
  console.log(`Generated ${new Date().toISOString()}`);
  console.log(`\n- **Files scanned:** ${fileBaseMaps.length}`);
  console.log(`- **Calls found:** ${allResults.length}`);

  const unresolved = allResults.filter(r => r.kind === 'non-literal-arg' || r.kind === 'partially-resolved');
  console.log(`- **Unresolved/partial:** ${unresolved.length}`);

  console.log('\n## All API calls\n');
  console.log('| Method | Resolved path | Source file | Kind |');
  console.log('|--------|---------------|-------------|------|');
  for (const r of allResults) {
    console.log(`| ${r.method} | \`${r.path}\` | ${r.file} | ${r.kind} |`);
  }

  console.log('\n## BASE constants found\n');
  console.log('| File | Constant | Value |');
  console.log('|------|----------|-------|');
  for (const { file, baseMap } of fileBaseMaps) {
    for (const [k, v] of Object.entries(baseMap)) {
      console.log(`| ${file} | \`${k}\` | \`${v}\` |`);
    }
  }

  if (unresolved.length > 0) {
    console.log('\n## Unresolved / partially resolved\n');
    console.log('Calls where the path argument couldn\'t be statically resolved (e.g. variable from another file, dynamic concatenation). Verify these manually.\n');
    console.log('| Method | Raw | Source file |');
    console.log('|--------|-----|-------------|');
    for (const r of unresolved) {
      console.log(`| ${r.method} | \`${r.raw ?? r.path}\` | ${r.file} |`);
    }
  }

  console.log('\n## Coverage by path prefix\n');
  const groups = {};
  for (const r of allResults) {
    if (r.path.startsWith('[')) continue;
    // Group by 3-level prefix when applicable: /v1/<domain> or /<domain>
    const parts = r.path.split('/').filter(Boolean);
    let prefix;
    if (parts[0] === 'v1' && parts[1]) prefix = `/v1/${parts[1]}`;
    else if (parts[0]) prefix = `/${parts[0]}`;
    else prefix = '/';
    groups[prefix] = (groups[prefix] || 0) + 1;
  }
  console.log('| Path prefix | Calls |');
  console.log('|-------------|-------|');
  for (const [prefix, count] of Object.entries(groups).sort()) {
    console.log(`| \`${prefix}\` | ${count} |`);
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
