/**
 * Auto-generate the "Key Documentation" section of docs/llms.txt from canonical doc frontmatter.
 *
 * Usage:
 *   node scripts/docs/llmstxt-generate.mjs          # rewrite the generated section in docs/llms.txt
 *   node scripts/docs/llmstxt-generate.mjs --check   # verify committed file matches generated (CI)
 *
 * Only the section between <!-- generated:start --> and <!-- generated:end --> markers is touched.
 * The hand-authored header (summary, canonical vs noncanonical) and footer (Core Concepts) remain
 * editable directly in docs/llms.txt — that file is the source of truth for those sections.
 *
 * Reads `title`, `status`, and `description` from YAML frontmatter.
 * Includes docs with status: stable, in-progress, or advisory. Excludes draft.
 * Errors on files missing a `status` field. Warns on files missing a `description` field.
 */

import fs from "node:fs";
import path from "node:path";
import matter from "gray-matter";

const MARKER_START = "<!-- generated:start — do not edit between markers; run: node scripts/docs/llmstxt-generate.mjs -->";
const MARKER_END = "<!-- generated:end -->";

/* ------------------------------------------------------------------ */
/* Helpers                                                             */
/* ------------------------------------------------------------------ */

function repoRootFromCwd() {
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
    else if (ent.name.toLowerCase().endsWith(".md")) out.push(full);
  }
  return out;
}

function parseFrontmatter(filePath) {
  const raw = fs.readFileSync(filePath, "utf8");
  const { data } = matter(raw);
  return data;
}

/** Sort by filename (basename), e.g. 01-foo.md before 02-bar.md */
function byFilename(a, b) {
  return path.basename(a.rel).localeCompare(path.basename(b.rel), "en");
}

/** Sort by relative path */
function byRelPath(a, b) {
  return a.rel.localeCompare(b.rel, "en");
}

/** README first, then alphabetical by basename */
function readmeFirstSort(a, b) {
  const aBase = path.basename(a.rel);
  const bBase = path.basename(b.rel);
  if (aBase === "README.md") return -1;
  if (bBase === "README.md") return 1;
  return aBase.localeCompare(bBase, "en");
}

function formatEntry(doc) {
  const desc = doc.description ? `: ${doc.description}` : "";
  return `- [${doc.title}](${doc.rel})${desc}`;
}

/* ------------------------------------------------------------------ */
/* Main                                                                */
/* ------------------------------------------------------------------ */

function main() {
  const root = repoRootFromCwd();
  const docsDir = path.join(root, "docs");
  const llmsTxtPath = path.join(docsDir, "llms.txt");

  // Read existing llms.txt and find markers
  if (!fs.existsSync(llmsTxtPath)) {
    console.error("ERROR: docs/llms.txt does not exist");
    process.exitCode = 1;
    return;
  }

  const existing = fs.readFileSync(llmsTxtPath, "utf8");
  const startIdx = existing.indexOf(MARKER_START);
  const endIdx = existing.indexOf(MARKER_END);

  if (startIdx === -1 || endIdx === -1) {
    console.error("ERROR: docs/llms.txt is missing generation markers.");
    console.error(`Expected to find:\n  ${MARKER_START}\n  ${MARKER_END}`);
    process.exitCode = 1;
    return;
  }

  const before = existing.slice(0, startIdx);
  const after = existing.slice(endIdx + MARKER_END.length);

  // Walk all canonical dirs and parse frontmatter
  const canonicalDirs = ["explanation", "reference", "how-to", "decisions", "governance"];
  const allDocs = [];

  for (const dir of canonicalDirs) {
    const fullDir = path.join(docsDir, dir);
    for (const filePath of walk(fullDir)) {
      const rel = path.relative(docsDir, filePath).replaceAll("\\", "/");
      let fm;
      try {
        fm = parseFrontmatter(filePath);
      } catch (err) {
        console.error(`ERROR parsing frontmatter in ${rel}: ${err.reason || err.message}`);
        process.exitCode = 1;
        return;
      }
      allDocs.push({
        rel,
        title: fm.title || path.basename(filePath, ".md"),
        status: fm.status || null,
        description: fm.description || "",
      });
    }
  }

  // Validate: every file must have a status field
  const noStatus = allDocs.filter((d) => !d.status);
  if (noStatus.length > 0) {
    for (const d of noStatus) {
      console.error(`ERROR: ${d.rel} has no 'status' field in frontmatter`);
    }
    process.exitCode = 1;
    return;
  }

  // Filter: stable + in-progress + advisory only (excludes draft)
  const included = allDocs.filter(
    (d) => d.status === "stable" || d.status === "in-progress" || d.status === "advisory"
  );

  // Warn about missing descriptions
  const noDesc = included.filter((d) => !d.description);
  if (noDesc.length > 0) {
    for (const d of noDesc) {
      console.error(`WARNING: ${d.rel} has no 'description' field`);
    }
  }

  // Group into sections
  const agentGuide = included.find(
    (d) => d.rel === "reference/contributing/agent-guide.md"
  );
  if (!agentGuide) {
    console.error("ERROR: agent-guide.md not found in canonical docs");
    process.exitCode = 1;
    return;
  }

  const explanation = included
    .filter((d) => d.rel.startsWith("explanation/"))
    .sort(byFilename);

  const howTo = included
    .filter((d) => d.rel.startsWith("how-to/"))
    .sort(byFilename);

  const reference = included
    .filter(
      (d) =>
        d.rel.startsWith("reference/") &&
        d.rel !== "reference/contributing/agent-guide.md"
    )
    .sort(byRelPath);

  const governance = included
    .filter((d) => d.rel.startsWith("governance/"))
    .sort(byRelPath);

  const decisions = included
    .filter((d) => d.rel.startsWith("decisions/"))
    .sort(readmeFirstSort);

  // Build the generated section (between markers)
  const generated = [
    MARKER_START,
    "",
    "## Key Documentation",
    "",
    "### Development",
    formatEntry(agentGuide),
    "",
    "### Explanation (Understanding the Architecture)",
    ...explanation.map(formatEntry),
    "",
    "### How-To (Specific Tasks)",
    ...howTo.map(formatEntry),
    "",
    "### Reference (Standards)",
    ...reference.map(formatEntry),
    "",
    "### Governance (Policies & SLOs)",
    ...governance.map(formatEntry),
    "",
    "### Decisions (Architecture Decision Records)",
    ...decisions.map(formatEntry),
    "",
    MARKER_END,
  ].join("\n");

  const output = before + generated + after;

  if (process.argv.includes("--check")) {
    if (existing === output) {
      console.log(
        `llmstxt-generate --check: OK (${included.length} docs indexed)`
      );
    } else {
      console.error("llmstxt-generate --check: FAIL — llms.txt is out of date");
      console.error(
        "Run: node scripts/docs/llmstxt-generate.mjs   to regenerate"
      );

      // Show a simple diff summary
      const committedLines = existing.split("\n");
      const outputLines = output.split("\n");
      const maxLen = Math.max(committedLines.length, outputLines.length);
      let diffCount = 0;
      for (let i = 0; i < maxLen; i++) {
        if (committedLines[i] !== outputLines[i]) {
          diffCount++;
          if (diffCount <= 10) {
            console.error(`  line ${i + 1}:`);
            if (committedLines[i] !== undefined)
              console.error(`    - ${committedLines[i]}`);
            if (outputLines[i] !== undefined)
              console.error(`    + ${outputLines[i]}`);
          }
        }
      }
      if (diffCount > 10) {
        console.error(`  ... and ${diffCount - 10} more differences`);
      }
      process.exitCode = 1;
    }
  } else {
    fs.writeFileSync(llmsTxtPath, output, "utf8");
    console.log(
      `llmstxt-generate: wrote docs/llms.txt (${included.length} docs indexed)`
    );
  }
}

main();
