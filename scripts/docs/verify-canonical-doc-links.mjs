/**
 * Fail-closed docs lint for canonical docs.
 *
 * Fails when:
 * - canonical docs reference noncanonical tempdocs files (docs/tempdocs/*.md)
 * - canonical docs reference missing docs paths (e.g., docs/explanation/*.md, docs/reference/*.md, docs/llms.txt)
 * - canonical docs contain markdown links to missing local targets
 *
 * Canonical trees:
 * - docs/explanation/
 * - docs/reference/
 * - docs/how-to/
 * Plus the docs index file:
 * - docs/llms.txt
 */

import fs from "node:fs";
import path from "node:path";

function repoRootFromCwd() {
  // NodeScriptTask sometimes runs with workingDir=scripts/, so resolve repo root from markers.
  const cwd = process.cwd();
  const markers = ["settings.gradle.kts", "build.gradle.kts", ".git"];
  for (let dir = cwd; ; dir = path.dirname(dir)) {
    for (const m of markers) {
      if (fs.existsSync(path.join(dir, m))) return dir;
    }
    const parent = path.dirname(dir);
    if (parent === dir) break;
  }
  return cwd;
}

function walk(dir) {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, ent.name);
    if (ent.isDirectory()) out.push(...walk(full));
    else out.push(full);
  }
  return out;
}

function normalizeRel(p) {
  return p.replaceAll("\\", "/");
}

function stripFragmentAndQuery(href) {
  const noQuery = href.split("?")[0];
  return noQuery.split("#")[0];
}

function extractDocsPathRefs(line) {
  // Capture explicit docs/.. references in prose/backticks.
  // Examples:
  // - docs/explanation/01-system-overview.md
  // - docs/llms.txt
  // The `(?<![\w-])` boundary stops `docs/` matching MID-PATH (e.g. inside
  // `archive/source-tempdocs/…` or `subdocs/…`) — that substring bug false-flagged
  // retired-archive citations as broken `docs/` refs (tempdoc 579).
  const re = /(?<![\w-])(docs[\\/][A-Za-z0-9_.\-\\/]+?\.(?:md|txt))/gi;
  const out = [];
  let m;
  while ((m = re.exec(line)) !== null) {
    out.push(normalizeRel(m[1]));
  }
  return out;
}

function extractMarkdownLinkTargets(line) {
  // Basic markdown link parser for: [text](target)
  // We intentionally keep this simple (no nested parens handling).
  const re = /\[[^\]]*]\(([^)]+)\)/g;
  const out = [];
  let m;
  while ((m = re.exec(line)) !== null) {
    out.push(m[1].trim());
  }
  return out;
}

function isSkippableHref(href) {
  if (!href) return true;
  if (href.startsWith("#")) return true;
  if (/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(href)) return true; // scheme (http:, https:, mailto:, etc.)
  return false;
}

function looksLikeLocalDocPath(href) {
  const p = stripFragmentAndQuery(href);
  const lower = p.toLowerCase();
  return lower.endsWith(".md") || lower.endsWith(".txt");
}

function main() {
  const repoRoot = repoRootFromCwd();

  const canonicalDirs = [
    path.join(repoRoot, "docs", "explanation"),
    path.join(repoRoot, "docs", "reference"),
    path.join(repoRoot, "docs", "how-to"),
    path.join(repoRoot, "docs", "decisions"),
  ];

  const canonicalIndexFiles = [
    path.join(repoRoot, "docs", "llms.txt"),
  ];

  const files = [
    ...canonicalDirs.flatMap((d) => walk(d)).filter((p) => p.toLowerCase().endsWith(".md")),
    ...canonicalIndexFiles.filter((p) => fs.existsSync(p)),
  ];

  const MAX_ERRORS = 60;
  const errors = [];

  for (const f of files) {
    const relFile = normalizeRel(path.relative(repoRoot, f));
    const dirOfFile = path.dirname(f);
    const lines = fs.readFileSync(f, "utf8").split(/\r?\n/);

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const lineNumber = i + 1;

      // 1) Explicit docs/.. references (in prose/backticks)
      for (const ref of extractDocsPathRefs(line)) {
        const refLower = ref.toLowerCase();
        if (refLower.startsWith("docs/tempdocs/") && refLower.endsWith(".md")) {
          errors.push({
            kind: "CANONICAL_TEMP_DOC_REF",
            relFile,
            lineNumber,
            ref,
            message: "Canonical docs must not reference docs/tempdocs/*.md",
          });
        }

        const abs = path.join(repoRoot, ref);
        if (!fs.existsSync(abs)) {
          errors.push({
            kind: "MISSING_DOC_REF",
            relFile,
            lineNumber,
            ref,
            message: "Referenced docs path does not exist",
          });
        }
      }

      // 2) Markdown links to local targets
      for (const hrefRaw of extractMarkdownLinkTargets(line)) {
        if (isSkippableHref(hrefRaw)) continue;
        if (!looksLikeLocalDocPath(hrefRaw)) continue;

        const href = stripFragmentAndQuery(hrefRaw);
        const abs = path.resolve(dirOfFile, href);
        const relTarget = normalizeRel(path.relative(repoRoot, abs));
        const relTargetLower = relTarget.toLowerCase();

        if (relTargetLower.startsWith("docs/tempdocs/") && relTargetLower.endsWith(".md")) {
          errors.push({
            kind: "CANONICAL_TEMP_DOC_LINK",
            relFile,
            lineNumber,
            ref: hrefRaw,
            message: "Canonical docs must not link to docs/tempdocs/*.md",
          });
        }

        if (!fs.existsSync(abs)) {
          errors.push({
            kind: "MISSING_LINK_TARGET",
            relFile,
            lineNumber,
            ref: hrefRaw,
            message: `Markdown link target does not exist (resolved=${relTarget})`,
          });
        }
      }

      if (errors.length >= MAX_ERRORS) break;
    }

    if (errors.length >= MAX_ERRORS) break;
  }

  if (errors.length === 0) {
    console.log(`verify-canonical-doc-links: OK (files=${files.length})`);
    return;
  }

  console.log(
    `verify-canonical-doc-links: FAIL (files=${files.length} errors=${errors.length}${errors.length >= MAX_ERRORS ? "+" : ""})`
  );
  for (const e of errors) {
    console.log(`- ${e.relFile}:${e.lineNumber} :: ${e.kind} :: ${e.ref}`);
  }
  process.exitCode = 1;
}

main();
