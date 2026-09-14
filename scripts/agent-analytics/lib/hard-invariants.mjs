/**
 * Single-authority reader for AGENTS.md's `## Hard invariants`.
 *
 * The cross-harness invariants have ONE home (AGENTS.md). Any consumer that needs them —
 * notably `subagent-guide.mjs`, which injects them into subagents that don't
 * see CLAUDE.md — must PROJECT from this reader, never hand-copy them. A
 * hand-copy drifts: `subagent-guide` previously inlined invariants 1-4 and
 * silently fell out of date when #5 (frontend-is-Lit) and #6 (language-agnostic)
 * were added, so subagents doing frontend work were never told "Lit, not React"
 * (tempdoc 620 Part V — the doc-as-projection principle applied to a hook).
 *
 * Live read at call time, straight from the register — no build-time snapshot.
 * Fail-open: returns [] on any parse/IO failure (a hook must never crash).
 */

import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const AGENTS_MD = resolve(REPO_ROOT, 'AGENTS.md');

/**
 * Parse the `## Hard invariants` numbered list out of AGENTS.md, stripping each
 * item's trailing `<!-- rule:* -->` anchor.
 *
 * @returns {string[]} invariant texts in order (e.g. "**Application code never
 *   touches Lucene.** Index I/O belongs to the index half, via a port
 *   (ADR-0049)."), or [] on failure.
 */
export function hardInvariants() {
  try {
    const lines = readFileSync(AGENTS_MD, 'utf8').split(/\r?\n/);
    const out = [];
    let inSection = false;
    let current = null;
    for (const raw of lines) {
      const line = raw.trimEnd();
      if (/^##\s+Hard Invariants/i.test(line)) {
        inSection = true;
        continue;
      }
      if (inSection && /^##\s+/.test(line)) {
        if (current) out.push(current);
        break;
      }
      if (!inSection) continue;
      const m = line.match(/^\d+\.\s+(.*)$/);
      if (m) {
        if (current) out.push(current);
        const text = m[1].replace(/<!--\s*rule:[a-z0-9-]+\s*-->\s*$/i, '').trim();
        current = text || null;
      } else if (current && /^\s+\S/.test(raw)) {
        current += ` ${line.trim()}`;
      }
    }
    if (current && !out.includes(current)) out.push(current);
    return out;
  } catch {
    return [];
  }
}
