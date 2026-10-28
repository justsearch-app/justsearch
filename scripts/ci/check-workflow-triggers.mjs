#!/usr/bin/env node
/**
 * Validate GitHub workflow triggers against workflow-signal-policy.v1.json.
 *
 * This intentionally uses a small line scanner instead of a YAML parser so it
 * can run before npm install and inside lightweight local checks.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

// Triggers an external, unprivileged actor can fire on a PUBLIC repo. A
// self-hosted runner executes on the maintainer's own machine, so any of these
// on a self-hosted job is remote-code-execution-from-any-PR (tempdoc 747 P-D;
// motivated by the April-2026 frontier-agent production-DB-deletion incident
// class). This is a HARD invariant, enforced independently of the per-workflow
// expectedTriggers allowlist below — so editing the workflow AND the policy
// together (which would satisfy the allowlist match) still fails here.
export const SELF_HOSTED_FORBIDDEN_TRIGGERS = new Set([
  'pull_request',
  'pull_request_target',
  'pull_request_review',
  'pull_request_review_comment',
  'issue_comment',
]);

function repoRootFromCwd() {
  const markers = ['settings.gradle.kts', 'build.gradle.kts', '.git'];
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (markers.some((marker) => fs.existsSync(path.join(dir, marker)))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) break;
  }
  return process.cwd();
}

function normalizeRel(p) {
  return p.replaceAll('\\', '/');
}

function stripOuterQuotes(value) {
  const trimmed = value.trim();
  if (
    (trimmed.startsWith("'") && trimmed.endsWith("'")) ||
    (trimmed.startsWith('"') && trimmed.endsWith('"'))
  ) {
    return trimmed.slice(1, -1);
  }
  return trimmed;
}

function stripInlineComment(line) {
  let quote = null;
  for (let i = 0; i < line.length; i += 1) {
    const ch = line[i];
    if ((ch === '"' || ch === "'") && line[i - 1] !== '\\') {
      quote = quote === ch ? null : quote ?? ch;
    }
    if (ch === '#' && quote === null) {
      return line.slice(0, i);
    }
  }
  return line;
}

function leadingSpaces(line) {
  const match = /^ */.exec(line);
  return match ? match[0].length : 0;
}

function parseMappingEntry(trimmed) {
  const match = /^(['"]?)([A-Za-z_][\w-]*)\1\s*:\s*(.*)$/.exec(trimmed);
  return match ? { key: match[2], rest: match[3] } : null;
}

function parseInlineEvents(rest) {
  const events = [];
  const trimmed = rest.trim();
  if (!trimmed) return events;
  if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
    for (const item of trimmed.slice(1, -1).split(',')) {
      events.push(stripOuterQuotes(item));
    }
    return events;
  }
  if (trimmed.startsWith('{') && trimmed.endsWith('}')) {
    for (const item of trimmed.slice(1, -1).split(',')) {
      const key = parseMappingEntry(item.trim())?.key;
      if (key) events.push(key);
    }
    return events;
  }
  events.push(stripOuterQuotes(trimmed));
  return events;
}

// A GitHub-HOSTED runner label. GitHub-hosted labels are name-prefixed
// `ubuntu-*` / `windows-*` / `macos-*` (incl. versioned + `-arm` variants);
// GitHub-hosted runners cannot carry arbitrary custom labels, so any label
// that isn't one of these can only belong to a self-hosted runner.
const HOSTED_LABEL = /^(ubuntu|windows|macos)-[\w.-]+$/i;
// A GitHub-Actions expression — cannot be resolved statically (e.g. a
// `runs-on: ${{ matrix.os }}` matrix). This is the ONE documented limit where
// the detector does NOT fail closed, because it genuinely can't know the value.
const EXPRESSION = /\$\{\{/;

function labelIsHosted(rawLabel) {
  const label = stripOuterQuotes(rawLabel).trim();
  if (!label) return true; // empty token → ignore
  if (EXPRESSION.test(label)) return true; // documented matrix limit
  return HOSTED_LABEL.test(label);
}

function anyLabelSelfHosted(csv) {
  return csv.split(',').some((label) => !labelIsHosted(label));
}

// True if any job's `runs-on:` resolves to a self-hosted runner. FAIL-CLOSED:
// a value is treated as self-hosted unless every label is a known hosted label
// (or an unresolvable expression). This catches custom-label-only targeting
// (`runs-on: justsearch-perf`) and the runner-group / block-`labels:` mapping
// forms, not just the literal `self-hosted` token. Comments are already
// stripped by stripInlineComment, so a bare mention in a comment does not count.
export function usesSelfHostedRunner(lines) {
  let inRunsOnBlock = false;
  let runsOnIndent = -1;
  let blockSelfHosted = false;

  const finishBlock = () => {
    const result = inRunsOnBlock && blockSelfHosted;
    inRunsOnBlock = false;
    runsOnIndent = -1;
    blockSelfHosted = false;
    return result;
  };

  for (let i = 0; i < lines.length; i += 1) {
    const noComment = stripInlineComment(lines[i]).trimEnd();
    if (!noComment.trim()) continue;
    const indent = leadingSpaces(noComment);
    const trimmed = noComment.trim();

    if (inRunsOnBlock && indent <= runsOnIndent) {
      if (finishBlock()) return true;
    }

    if (inRunsOnBlock) {
      // Inside a block-form runs-on: `- <label>` items, a `group:` key (runner
      // groups are self-hosted infrastructure), or a `labels:` list.
      const item = /^-\s*(.*)$/.exec(trimmed);
      if (item) {
        if (!labelIsHosted(item[1])) blockSelfHosted = true;
        continue;
      }
      if (/^group\s*:/.test(trimmed)) {
        blockSelfHosted = true;
        continue;
      }
      const labels = /^labels\s*:\s*(.*)$/.exec(trimmed);
      if (labels) {
        const value = labels[1].trim();
        const inner = value.startsWith('[') && value.endsWith(']') ? value.slice(1, -1) : value;
        if (inner && anyLabelSelfHosted(inner)) blockSelfHosted = true;
        continue;
      }
      continue;
    }

    const m = /^runs-on\s*:\s*(.*)$/.exec(trimmed);
    if (m) {
      const value = m[1].trim();
      if (value === '') {
        inRunsOnBlock = true;
        runsOnIndent = indent;
        blockSelfHosted = false;
        continue;
      }
      if (value.startsWith('[') && value.endsWith(']')) {
        if (anyLabelSelfHosted(value.slice(1, -1))) return true;
      } else if (value.startsWith('{')) {
        // Inline mapping form: a group is self-hosted; else inspect labels: [..];
        // an opaque mapping fails closed.
        if (/group\s*:/.test(value)) return true;
        const lm = /labels\s*:\s*\[([^\]]*)\]/.exec(value);
        if (lm) {
          if (anyLabelSelfHosted(lm[1])) return true;
        } else {
          return true;
        }
      } else if (!labelIsHosted(value)) {
        return true;
      }
    }
  }

  return finishBlock();
}

// Environment deployment rules are the authorization boundary for production
// signing. Keep this independent of the editable trigger allowlist.
function signingSecretErrors(lines, rel, repoRoot) {
  const errors = [];
  const releaseWorkflows = ['build-installer', 'build-installer-windows', 'build-installer-linux',
    'publish-release', 'packaging-smoke'].map(name => `.github/workflows/${name}.yml`);
  const releaseWorkflow = releaseWorkflows.includes(rel);
  const privateName = 'JUSTSEARCH_(?:CODESIGN_\\w+|TAURI_UPDATER_PRIVATE_KEY\\w*|RELEASE_METADATA_PRIVATE_KEY_PEM)';
  const reference = new RegExp(`secrets\\s*(?:\\.\\s*${privateName}\\b|\\[\\s*['"]${privateName}['"]\\s*\\])`, 'i');
  const anchors = new Map();
  for (let i = 0; i < lines.length; i++) {
    const match = /(?:[:]|-)\s*&([\w-]+)/.exec(stripInlineComment(lines[i]));
    if (!match) continue;
    const block = [lines[i]];
    for (let j = i + 1; j < lines.length; j++) {
      if (lines[j].trim() && leadingSpaces(lines[j]) <= leadingSpaces(lines[i])) break;
      block.push(stripInlineComment(lines[j]));
    }
    anchors.set(match[1], block);
  }
  const referencesPrivateKey = (line, seen = new Set()) => {
    // Only an exact presence expression may cross into a non-signing step.
    if (releaseWorkflow && /^\s*HAS_\w+:\s*\$\{\{\s*secrets(?:\.[A-Z_]+|\[['"][A-Z_]+['"]\])\s*!=\s*''\s*\}\}\s*$/i.test(line)) return false;
    if (reference.test(line)) return true;
    const alias = /(?:[:]|-)\s*\*([\w-]+)\s*$/.exec(line)?.[1];
    if (!alias || seen.has(alias)) return false;
    return (anchors.get(alias) ?? []).some(value => referencesPrivateKey(value, new Set([...seen, alias])));
  };
  let inJobs = false;
  let job = [];
  const check = () => {
    if (!job.length) return;
    if (releaseWorkflow && job.some(({ line }) => /^    secrets:\s*['"]?inherit['"]?\s*$/i.test(line))) {
      const calledWorkflow = job.find(({ line }) => /^    uses:/.test(line));
      const destination = calledWorkflow && stripOuterQuotes(parseMappingEntry(calledWorkflow.line.trim()).rest);
      if (!releaseWorkflows.some(file => destination === `./${file}`)) errors.push({ rel,
        lineNumber: job.find(({ line }) => /^    secrets:/i.test(line)).lineNumber,
        message: 'signing secrets may be inherited only by a per-release workflow' });
    }
    let environment;
    let environmentBlock = false;
    for (const { line } of job) {
      if (leadingSpaces(line) === 4) {
        const entry = parseMappingEntry(line.trim());
        environmentBlock = entry?.key === 'environment' && !entry.rest.trim();
        if (entry?.key === 'environment') environment = stripOuterQuotes(entry.rest);
      } else if (environmentBlock && leadingSpaces(line) === 6) {
        const entry = parseMappingEntry(line.trim());
        if (entry?.key === 'name') environment = stripOuterQuotes(entry.rest);
      }
    }
    let inSteps = false;
    for (const { line, lineNumber } of job) {
      if (leadingSpaces(line) === 4) inSteps = parseMappingEntry(line.trim())?.key === 'steps';
      if (!inSteps && referencesPrivateKey(line)) errors.push({ rel, lineNumber,
        message: 'production signing secrets must be scoped to a single release-signing step' });
    }
    if (environment !== 'release-signing') {
      for (const { line, lineNumber } of job) {
        if (referencesPrivateKey(line)) errors.push({ rel, lineNumber, message: 'production signing secrets require the release-signing environment' });
      }
    }
    if (!releaseWorkflow) { job = []; return; }
    const expand = (line, seen = new Set()) => {
      const alias = /(?:[:]|-)\s*\*([\w-]+)\s*$/.exec(line)?.[1];
      if (!alias || seen.has(alias)) return [line];
      const block = anchors.get(alias) ?? [];
      const offset = leadingSpaces(line) - leadingSpaces(block[0] ?? '');
      return block.flatMap(value => expand(' '.repeat(Math.max(0, offset)) + value, new Set([...seen, alias])));
    };
    const expanded = job.flatMap(({ line, lineNumber }) => expand(line).map(value => ({ line: value, lineNumber })));
    let step = [];
    const checkStep = () => {
      if (!step.some(({ line }) => referencesPrivateKey(line))) { step = []; return; }
      const text = step.map(({ line }) => line).join('\n');
      const lineNumber = step[0].lineNumber;
      // These are the existing signing entry points, not names supplied by a workflow author.
      const run = text.match(/^(?:      - |        )run:([^\n]*(?:\n          [^\n]*)*)/m)?.[1] ?? '';
      const signingCommand = /^\s*(?:node\s+scripts\/release\/release-candidate\.mjs\s+build\b|(?:&\s+)?\.[\\/]scripts[\\/]ci[\\/]build-installer-windows\.ps1\b|npm\s+--prefix\s+modules\/shell\s+run\s+tauri\s+--\s+signer\s+sign\b)/;
      const signingLines = run.split('\n').filter(line => signingCommand.test(line));
      const signs = signingLines.length > 0;
      if (!signs || /(?:^|\n)\s*(?:-\s*)?uses:/.test(text)) errors.push({ rel, lineNumber,
        message: 'production signing secrets may be referenced only by the step that signs, never by an action' });
      let source = run;
      for (const match of signingLines.join('\n').matchAll(/scripts[\/\\][\w./\\-]+\.(?:mjs|ps1|sh)\b/g)) {
        const file = path.resolve(repoRoot, match[0].replaceAll('\\', '/'));
        if (fs.existsSync(file)) source += '\n' + fs.readFileSync(file, 'utf8');
      }
      if (/GITHUB_(?:ENV|OUTPUT)/.test(source)) errors.push({ rel, lineNumber,
        message: 'a signing-secret step must not export secret-derived values or paths to GITHUB_ENV or GITHUB_OUTPUT' });
      step = [];
    };
    for (const entry of expanded) {
      if (/^      -/.test(entry.line)) checkStep();
      if (step.length || /^      -/.test(entry.line)) step.push(entry);
    }
    checkStep();
    job = [];
  };
  for (let i = 0; i < lines.length; i++) {
    const line = stripInlineComment(lines[i]).trimEnd();
    if (!line.trim()) continue;
    if (releaseWorkflow && (/\btoJSON\s*\(\s*secrets\s*\)/i.test(line)
      || /\bsecrets\s*\[(?!\s*['"][A-Z_][A-Z_0-9]*['"]\s*\])/i.test(line))) {
      errors.push({ rel, lineNumber: i + 1,
        message: 'release workflows must use literal secret names, never computed lookups or toJSON(secrets)' });
    }
    const indent = leadingSpaces(line);
    if (indent === 0) {
      check();
      inJobs = parseMappingEntry(line)?.key === 'jobs';
    }
    if (inJobs && indent === 2 && parseMappingEntry(line.trim())) check();
    if (inJobs && indent >= 2) job.push({ line, lineNumber: i + 1 });
    else if (referencesPrivateKey(line)) errors.push({ rel, lineNumber: i + 1, message: 'production signing secrets must be scoped to a single release-signing step' });
  }
  check();
  return errors;
}

export function scanWorkflow(file, repoRoot) {
  const rel = normalizeRel(path.relative(repoRoot, file));
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
  let inOnBlock = false;
  let onIndent = -1;
  let sawOn = false;
  const events = new Map();
  const selfHosted = usesSelfHostedRunner(lines);

  function addEvent(event, lineNumber) {
    const normalized = stripOuterQuotes(event).trim();
    if (!normalized) return;
    if (!events.has(normalized)) events.set(normalized, lineNumber);
  }

  for (let i = 0; i < lines.length; i += 1) {
    const lineNumber = i + 1;
    const noComment = stripInlineComment(lines[i]).trimEnd();
    if (!noComment.trim()) continue;

    const indent = leadingSpaces(noComment);
    const trimmed = noComment.trim();

    if (inOnBlock && indent <= onIndent) {
      inOnBlock = false;
      onIndent = -1;
    }

    if (indent === 0) {
      const topLevelEntry = parseMappingEntry(trimmed);
      if (topLevelEntry?.key === 'on') {
        sawOn = true;
        onIndent = indent;
        const inlineEvents = parseInlineEvents(topLevelEntry.rest);
        inOnBlock = inlineEvents.length === 0;
        for (const event of inlineEvents) addEvent(event, lineNumber);
      }
      continue;
    }

    if (inOnBlock) {
      const listMatch = /^-\s*(['"]?)([A-Za-z_][\w-]*)\1\s*$/.exec(trimmed);
      if (listMatch && indent === onIndent + 2) {
        addEvent(listMatch[2], lineNumber);
        continue;
      }

      const entry = parseMappingEntry(trimmed);
      if (indent === onIndent + 2 && entry?.key) {
        addEvent(entry.key, lineNumber);
      }
    }
  }

  return { rel, sawOn, events, selfHosted, signingErrors: signingSecretErrors(lines, rel, repoRoot) };
}

function workflowEntries(policy) {
  return (policy.workflows || []).filter(
    (entry) => typeof entry.path === 'string' && normalizeRel(entry.path).startsWith('.github/workflows/')
  );
}

export function validateWorkflows({ repoRoot, policy }) {
  const workflowsDir = path.join(repoRoot, '.github', 'workflows');
  const errors = [];
  if (!fs.existsSync(workflowsDir)) return errors;

  const policyByPath = new Map();
  for (const entry of workflowEntries(policy)) {
    const rel = normalizeRel(entry.path);
    if (policyByPath.has(rel)) {
      errors.push({ rel, lineNumber: 1, message: `duplicate workflow policy entry for ${rel}` });
      continue;
    }
    policyByPath.set(rel, entry);
  }

  const files = fs
    .readdirSync(workflowsDir)
    .filter((name) => /\.(ya?ml)$/i.test(name))
    .map((name) => path.join(workflowsDir, name))
    .sort((a, b) => a.localeCompare(b));

  const seen = new Set();
  for (const file of files) {
    const scanned = scanWorkflow(file, repoRoot);
    errors.push(...scanned.signingErrors);
    seen.add(scanned.rel);

    // HARD invariant (policy-independent, tempdoc 747 P-D): a self-hosted job
    // must never carry an externally-triggerable event. Checked before the
    // policy lookup so it also fires for a workflow missing from the policy.
    if (scanned.selfHosted) {
      for (const [event, lineNumber] of scanned.events) {
        if (SELF_HOSTED_FORBIDDEN_TRIGGERS.has(event)) {
          errors.push({
            rel: scanned.rel,
            lineNumber,
            message: `self-hosted runner job must not use externally-triggerable event '${event}' (RCE-from-any-PR on the maintainer's machine; tempdoc 747 P-D). Move the job to a hosted runner or drop the trigger.`,
          });
        }
      }
    }

    const policyEntry = policyByPath.get(scanned.rel);
    if (!policyEntry) {
      errors.push({ rel: scanned.rel, lineNumber: 1, message: 'workflow file is missing from workflow-signal-policy.v1.json' });
      continue;
    }

    if (!scanned.sawOn) {
      errors.push({ rel: scanned.rel, lineNumber: 1, message: 'missing top-level on: block' });
      continue;
    }

    const expected = new Set(policyEntry.expectedTriggers || []);
    for (const event of expected) {
      if (!scanned.events.has(event)) {
        errors.push({ rel: scanned.rel, lineNumber: 1, message: `missing expected trigger from policy: ${event}` });
      }
    }
    for (const [event, lineNumber] of scanned.events) {
      if (!expected.has(event)) {
        errors.push({ rel: scanned.rel, lineNumber, message: `unexpected trigger not declared in policy: ${event}` });
      }
    }
  }

  for (const [rel, entry] of policyByPath) {
    if (!seen.has(rel)) {
      errors.push({ rel, lineNumber: 1, message: `policy entry for ${entry.name || rel} points at a missing workflow file` });
    }
  }

  return errors;
}

function loadPolicy(repoRoot) {
  const policyPath = path.join(repoRoot, 'scripts', 'ci', 'workflow-signal-policy.v1.json');
  return JSON.parse(fs.readFileSync(policyPath, 'utf8'));
}

function main() {
  const repoRoot = repoRootFromCwd();
  const errors = validateWorkflows({ repoRoot, policy: loadPolicy(repoRoot) });
  if (errors.length === 0) {
    console.log('check-workflow-triggers: OK (workflow triggers match workflow-signal-policy.v1.json)');
    return;
  }

  console.error('check-workflow-triggers: FAIL');
  console.error('Workflow triggers must match scripts/ci/workflow-signal-policy.v1.json.');
  for (const error of errors) {
    console.error(`- ${error.rel}:${error.lineNumber} ${error.message}`);
  }
  process.exitCode = 1;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
