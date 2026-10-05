#!/usr/bin/env node
/**
 * Test-intent counts over a rolling window — tempdoc 966 "Measurement and stop rules (automated)".
 *
 * Reads MERGED history only (first-parent commits on the ref, i.e. one squash commit per PR) and the
 * test-intent changesets each commit added or modified, and counts: entries per class, references to
 * test-efficacy changesets, rejections (records with verdict `reject`; acceptors append records and
 * never delete them), audited PRs, audit disagreements, third-reviewer tie-breaks, and new checks per
 * PR. It flags the reconsider rule the orchestrator acts on: tie-breaks above one in ten audited PRs.
 *
 *   node scripts/governance/gates/test-intent/weekly-report.mjs [--ref origin/main] [--days 28]
 *        [--out-md <file>] [--out-json <file>]
 *
 * In CI, the test-intent gate step in "Public claims" appends the rolling four-week counts to the
 * job summary on every run (writeStepSummary, called by enforcer.mjs). There is no scheduled
 * workflow: ADR-0026 forbids `schedule:` triggers. Run locally, the counts print to stdout (and,
 * inside a GitHub Actions job, are also appended to $GITHUB_STEP_SUMMARY).
 */

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { AUDIT_EVERY, CHANGESETS_DIR, TEST_INTENT_CLASSES, isAuditSelected } from './classifications.mjs';
import { parseChangeset } from './changeset.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
export const REPORT_KIND = 'justsearch-test-intent-weekly.v1';
/** The window the gate step reports on every CI run: rolling four weeks. */
export const ROLLING_DAYS = 28;

const git = (args, cwd) => execFileSync('git', args, { cwd, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024, stdio: ['pipe', 'pipe', 'pipe'] });

/** First-parent commits on `ref` since `sinceIso`, each with the test-intent changesets it added or modified. */
export function collectHistory({ repoRoot, ref, sinceIso }) {
  const log = git(['log', '--first-parent', `--since=${sinceIso}`, '--format=%H%x1f%cI%x1f%s', ref], repoRoot);
  // One pass for the changesets each first-parent commit added or modified (diff against the first parent).
  const touched = new Map();
  const names = git(['log', '--first-parent', '--diff-merges=first-parent', `--since=${sinceIso}`, '--diff-filter=AM',
    '--name-only', '--format=%x1e%H', ref, '--', CHANGESETS_DIR], repoRoot);
  for (const block of names.split('\x1e').filter((b) => b.trim())) {
    const [sha, ...files] = block.split('\n').map((s) => s.trim()).filter(Boolean);
    touched.set(sha, files.filter((p) => p.endsWith('.md') && !p.endsWith('README.md')));
  }
  const commits = [];
  for (const line of log.split('\n').filter(Boolean)) {
    const [sha, date, subject] = line.split('\x1f');
    const files = touched.get(sha) ?? [];
    const changesets = files.map((p) => {
      let text = null;
      try {
        text = git(['show', `${sha}:${p}`], repoRoot);
      } catch { /* deleted later in the same commit */ }
      return { path: p, text };
    });
    const pr = [...subject.matchAll(/\(#(\d+)\)/g)].pop();
    commits.push({ sha, date, subject, pr: pr ? Number(pr[1]) : null, changesets });
  }
  return commits;
}

/** The reconsider threshold of tempdoc 966 Measurement: above one in ten audited PRs. */
export const RECONSIDER_RATE = 0.1;

/**
 * The reconsider rule: third-reviewer tie-breaks per audited PR or, before any tie-break has
 * happened, two-reviewer disagreements per audited PR, above one in ten. With no audited PR in the
 * window, any tie-break or disagreement counts as above (there is nothing to divide by, and the
 * warning is advisory).
 */
export function reconsiderRate({ audited, tieBreaks, auditDisagreements }) {
  const useTieBreaks = tieBreaks > 0;
  const count = useTieBreaks ? tieBreaks : auditDisagreements;
  const basis = useTieBreaks ? 'third-reviewer tie-breaks' : 'two-reviewer disagreements (no tie-break yet)';
  const rate = audited > 0 ? count / audited : count > 0 ? Infinity : 0;
  return { basis, count, audited, rate, above: rate > RECONSIDER_RATE };
}

export function reconsiderMessage(r, windowDays, ref) {
  const pct = Number.isFinite(r.rate) ? `${(r.rate * 100).toFixed(1)}%` : 'no audited PR to divide by';
  return `Reconsider (tempdoc 966 Measurement): over the last ${windowDays} days on ${ref}, ${r.basis} were ` +
    `${r.count} of ${r.audited} audited PRs (${pct}), above one in ten. The orchestrator must report this as a finding ` +
    'in its next delivery. This warning never fails the gate.';
}

const days = (sinceIso, untilIso) => {
  const d = Math.round((Date.parse(untilIso) - Date.parse(sinceIso)) / 86400000);
  return Number.isFinite(d) ? d : ROLLING_DAYS;
};

/** The ref the rolling counts read: origin/main, else main, else HEAD. */
export function rollingRef(repoRoot) {
  for (const ref of ['origin/main', 'main']) {
    try {
      git(['rev-parse', '--verify', '--quiet', `${ref}^{commit}`], repoRoot);
      return ref;
    } catch { /* try the next */ }
  }
  return 'HEAD';
}

/** Pure: compute the report from collected history. */
export function buildReport({ commits, ref, sinceIso, untilIso }) {
  const perClass = Object.fromEntries(TEST_INTENT_CLASSES.map((c) => [c, 0]));
  let references = 0;
  let rejections = 0;
  let audited = 0;
  let auditDisagreements = 0;
  let tieBreaks = 0;
  let newChecks = 0;
  let unparseable = 0;
  const prs = [];
  for (const c of commits) {
    let entries = 0;
    let prNewChecks = 0;
    let prTieBreak = false;
    let prDisagree = false;
    const classes = new Set();
    for (const cs of c.changesets) {
      const parsed = cs.text === null ? { ok: false } : parseChangeset(cs.text);
      if (!parsed.ok) {
        unparseable += 1;
        continue;
      }
      for (const e of parsed.entries) {
        if (e?.ref !== undefined) {
          references += 1;
          continue;
        }
        entries += 1;
        if (e?.class in perClass) {
          perClass[e.class] += 1;
          classes.add(e.class);
        }
        if (Array.isArray(e?.newChecks)) prNewChecks += e.newChecks.length;
      }
      rejections += parsed.records.filter((r) => r?.verdict === 'reject').length;
      const acc = parsed.records.filter((r) => (r?.kind ?? 'acceptance') === 'acceptance');
      const aud = parsed.records.filter((r) => r?.kind === 'audit');
      if (aud.length && acc.length && aud[aud.length - 1].verdict !== acc[acc.length - 1].verdict) prDisagree = true;
      if (acc.some((r) => r.verdict !== acc[acc.length - 1].verdict)) prDisagree = true;
      if (parsed.records.some((r) => r?.kind === 'tie-break')) prTieBreak = true;
    }
    const isAudited = entries > 0 && isAuditSelected(c.pr);
    if (isAudited) audited += 1;
    if (prDisagree) auditDisagreements += 1;
    if (prTieBreak) tieBreaks += 1;
    newChecks += prNewChecks;
    prs.push({ pr: c.pr, sha: c.sha.slice(0, 12), date: c.date, entries, classes: [...classes], newChecks: prNewChecks, audited: isAudited, disagreement: prDisagree, tieBreak: prTieBreak });
  }
  const merged = commits.length;
  const withEntries = prs.filter((p) => p.entries > 0).length;
  const findings = [];
  const rate = reconsiderRate({ audited, tieBreaks, auditDisagreements });
  if (rate.above) findings.push(reconsiderMessage(rate, days(sinceIso, untilIso), ref));
  if (unparseable > 0) findings.push(`${unparseable} merged changeset(s) could not be parsed.`);
  return {
    kind: REPORT_KIND,
    ref,
    since: sinceIso,
    until: untilIso,
    auditEvery: AUDIT_EVERY,
    totals: {
      prsMerged: merged,
      prsWithEntries: withEntries,
      entriesPerClass: perClass,
      references,
      rejections,
      audited,
      auditDisagreements,
      tieBreaks,
      newChecks,
      newChecksPerMergedPr: merged ? Number((newChecks / merged).toFixed(2)) : 0,
      newChecksPerPrWithEntries: withEntries ? Number((newChecks / withEntries).toFixed(2)) : 0,
    },
    findings,
    prs,
  };
}

export function renderMarkdown(report) {
  const t = report.totals;
  const lines = [
    `# Test-intent counts`,
    '',
    `Merged history on \`${report.ref}\` from ${report.since} to ${report.until} (tempdoc 966 Measurement).`,
    '',
    '| Measure | Value |',
    '| --- | --- |',
    `| PRs merged | ${t.prsMerged} |`,
    `| PRs with test-intent entries | ${t.prsWithEntries} |`,
    ...Object.entries(t.entriesPerClass).map(([k, v]) => `| Entries: ${k} | ${v} |`),
    `| References to test-efficacy changesets | ${t.references} |`,
    `| Rejections (records with verdict reject) | ${t.rejections} |`,
    `| Audited PRs (every ${report.auditEvery}th by number) | ${t.audited} |`,
    `| Audit disagreements | ${t.auditDisagreements} |`,
    `| Third-reviewer tie-breaks | ${t.tieBreaks} |`,
    `| New checks (total) | ${t.newChecks} |`,
    `| New checks per merged PR | ${t.newChecksPerMergedPr} |`,
    `| New checks per PR with entries | ${t.newChecksPerPrWithEntries} |`,
    '',
    '## Findings',
    '',
    ...(report.findings.length ? report.findings.map((f) => `- ${f}`) : ['- none']),
    '',
  ];
  const withEntries = report.prs.filter((p) => p.entries > 0);
  if (withEntries.length) {
    lines.push('## PRs with entries', '', '| PR | Commit | Entries | Classes | New checks | Audited | Disagreement | Tie-break |', '| --- | --- | --- | --- | --- | --- | --- | --- |');
    for (const p of withEntries) {
      lines.push(`| ${p.pr ? `#${p.pr}` : '-'} | ${p.sha} | ${p.entries} | ${p.classes.join(', ')} | ${p.newChecks} | ${p.audited ? 'yes' : 'no'} | ${p.disagreement ? 'yes' : 'no'} | ${p.tieBreak ? 'yes' : 'no'} |`);
    }
    lines.push('');
  }
  return lines.join('\n');
}

/** Build the report for the last `days` days of `ref`. */
export function reportFor({ repoRoot, ref, days, now = new Date() }) {
  const since = new Date(now.getTime() - days * 24 * 60 * 60 * 1000);
  return buildReport({
    commits: collectHistory({ repoRoot, ref, sinceIso: since.toISOString() }),
    ref,
    sinceIso: since.toISOString(),
    untilIso: now.toISOString(),
  });
}

/**
 * Append the rolling four-week counts to the GitHub job summary (the gate step calls this on every
 * CI run). Never throws: the counts are advisory and must not change the gate's verdict.
 * @returns {string|null} what was written, or null when there is no job summary to write to
 */
export function writeStepSummary({ repoRoot, env = process.env }) {
  const file = env.GITHUB_STEP_SUMMARY;
  if (!file) return null;
  let md;
  try {
    md = renderMarkdown(reportFor({ repoRoot, ref: rollingRef(repoRoot), days: ROLLING_DAYS }));
  } catch (e) {
    md = `# Test-intent counts\n\nNot available on this run: ${e.message.split('\n')[0]}\n`;
  }
  try {
    fs.appendFileSync(file, md + '\n');
  } catch {
    return null;
  }
  return md;
}

function main() {
  const args = { ref: 'origin/main', days: ROLLING_DAYS };
  const argv = process.argv.slice(2);
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i];
    const v = argv[++i];
    if (v === undefined) throw new Error(`${k} needs a value`);
    if (k === '--ref') args.ref = v;
    else if (k === '--days') args.days = Number(v);
    else if (k === '--out-md') args.outMd = v;
    else if (k === '--out-json') args.outJson = v;
    else if (k === '--repo') args.repo = v;
    else throw new Error(`unknown argument ${k}`);
  }
  const repoRoot = args.repo ? path.resolve(args.repo) : REPO_ROOT;
  const report = reportFor({ repoRoot, ref: args.ref, days: args.days });
  const md = renderMarkdown(report);
  for (const [file, content] of [[args.outMd, md], [args.outJson, JSON.stringify(report, null, 2) + '\n']]) {
    if (!file) continue;
    fs.mkdirSync(path.dirname(path.resolve(file)), { recursive: true });
    fs.writeFileSync(file, content, 'utf8');
  }
  if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, md + '\n');
  console.log(md);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (e) {
    console.error(`test-intent weekly-report: ${e.message}`);
    process.exitCode = 2;
  }
}
