/**
 * test-intent analysis — tempdoc 966 D1 (first increment: file-level, conservative).
 *
 * One function, `analyzeTestIntent`, shared by the kernel enforcer (CI's "Public claims" job) and
 * the local CLI, so the gate runs the same way in both places:
 *
 *   1. resolve the base for the event (pull_request / merge_group / push / local), fail closed on a
 *      shallow clone;
 *   2. diff base → working tree, deletions included; pair only byte-identical moves that keep the
 *      file in the same execution context (scope.mjs executionContext);
 *   3. flag every in-scope file (scope.mjs), the frontend helpers only tests import (the list in
 *      test-support.mjs, read at the base and the head), the changed #[cfg(test)] content of Rust
 *      files, the law/targetTests projection of the logic-seam register, every changed watched
 *      baseline, and build files whose changed lines touch test selection or execution
 *      (build-config.mjs);
 *   4. load the test-intent changesets the branch adds or modifies (kernel loader) and check that
 *      each flagged item is covered, each entry carries what its class needs, each source resolves
 *      and predates the PR, and each evidence file matches;
 *   5. check each changeset with entries has a non-author acceptance record whose digest matches
 *      the current content, plus the audit record on audit-selected PRs.
 */

import { relative } from 'node:path';

import { loadChangesets } from '../../lib/changeset-loader.mjs';
import { TEST_EFFICACY_CLASSIFICATIONS } from '../test-efficacy/classifications.mjs';
import { parseFrontmatter } from '../../lib/frontmatter.mjs';
import {
  ACCEPTANCE_HEADING,
  ADAPTATION_KINDS,
  CHANGESET_SCHEMA,
  CHANGESETS_DIR,
  EXPECTATION_SOURCE_KINDS,
  RULES_DOC,
  TEST_INTENT_CLASSES,
  isAuditSelected,
} from './classifications.mjs';
import { computeDigest, entryItems, normalizeText, parseChangeset, sha256 } from './changeset.mjs';
import { validateEvidence } from './evidence.mjs';
import { BaseResolutionError, diffWorkingTree, git, readAtRef, readWorking, resolveBase } from './git-diff.mjs';
import { RustScanError, extractTestItems, testModuleFiles } from './rust-tests.mjs';
import {
  LOGIC_SEAMS_ITEM,
  LOGIC_SEAMS_PATH,
  OUT_OF_SCOPE_STATEMENT,
  RUST_CRATE_ROOT,
  TEST_EFFICACY_REFERABLE_ITEMS,
  classifyPath,
  executionContext,
  isOutOfScopeTestLike,
  isRustSource,
} from './scope.mjs';
import { buildConfigChange, buildConfigKind } from './build-config.mjs';
import { validateSource } from './sources.mjs';
import { TEST_SUPPORT_PATHS_FILE, parseTestSupportList } from './test-support.mjs';
import { verdictForAcceptance, verdictForItem } from './truth-table.mjs';
import { ROLLING_DAYS, reconsiderMessage, reconsiderRate, reportFor, rollingRef } from './weekly-report.mjs';

const RUST_ITEM_SUFFIX = '#cfg(test)';
const TEST_EFFICACY_CHANGESETS = 'gates/test-efficacy/.changesets/';
const nonEmpty = (v) => typeof v === 'string' && v.trim().length > 0;
const hashText = (t) => (t === null ? null : sha256(normalizeText(t)));

function finding(rule, level, message, uri) {
  return { ruleId: `test-intent/${rule}`, level, message, ...(uri ? { uri } : {}) };
}

/** The law/targetTests projection of the logic-seam register (null when absent). */
export function seamsProjection(text) {
  if (text === null) return null;
  const reg = JSON.parse(text);
  const out = {};
  for (const seam of Array.isArray(reg?.seams) ? reg.seams : []) {
    out[seam?.id ?? '?'] = { law: seam?.law ?? null, targetTests: seam?.targetTests ?? null };
  }
  const keys = Object.keys(out).sort();
  return JSON.stringify(keys.map((k) => [k, out[k]]));
}

/** Content identity of a projected (non-whole-file) item; parse failures become a distinct marker. */
function projectedIdentity(text, project) {
  if (text === null) return null;
  try {
    const p = project(text);
    return p === null ? null : hashText(p);
  } catch {
    return `unparseable:${hashText(text)}`;
  }
}

function rustProjection(text) {
  return extractTestItems(normalizeText(text)).text;
}

/**
 * @param {{repoRoot: string, env?: Record<string,string|undefined>, explicitBase?: string|null,
 *          prOverride?: number|null}} options
 */
export function analyzeTestIntent({ repoRoot, env = process.env, explicitBase = null, prOverride = null }) {
  const findings = [];
  let base;
  try {
    base = resolveBase({ repoRoot, env, explicit: explicitBase });
  } catch (e) {
    if (!(e instanceof BaseResolutionError)) throw e;
    findings.push(finding('base-unresolved', 'error', `${e.message} The gate fails closed.`));
    return { verdict: 'fail', findings, base: null, flagged: [], moves: [], outOfScope: [], changesets: [], uncovered: [] };
  }

  const { changes, baseBlobs } = diffWorkingTree(base.ref, repoRoot);
  const changedPaths = new Set(changes.map((c) => c.path));
  const changeStatus = new Map(changes.map((c) => [c.path, c.status]));
  const readBase = (rel) => readAtRef(base.ref, rel, repoRoot);
  const readNow = (rel) => readWorking(rel, repoRoot);

  // Rust: files a test-only `mod` declaration loads are test code as a whole.
  const rustTestFiles = new Set();
  if (changes.some((c) => isRustSource(c.path))) {
    const basePaths = [...baseBlobs.keys()].filter(isRustSource);
    const nowPaths = git(['ls-files', '-co', '--exclude-standard', '--', RUST_CRATE_ROOT], repoRoot)
      .split(/\r?\n/).filter((p) => p && isRustSource(p));
    for (const p of basePaths) for (const f of testModuleFiles(p, normalizeText(readBase(p) ?? ''))) rustTestFiles.add(f);
    for (const p of nowPaths) {
      const t = readNow(p);
      if (t !== null) for (const f of testModuleFiles(p, normalizeText(t))) rustTestFiles.add(f);
    }
  }

  /** Content identity of any item id, used for flagging and for the digest alike. */
  const identity = (id) => {
    if (id === LOGIC_SEAMS_ITEM) {
      return {
        before: projectedIdentity(readBase(LOGIC_SEAMS_PATH), seamsProjection),
        after: projectedIdentity(readNow(LOGIC_SEAMS_PATH), seamsProjection),
      };
    }
    if (id.endsWith(RUST_ITEM_SUFFIX)) {
      const rel = id.slice(0, -RUST_ITEM_SUFFIX.length);
      return {
        before: projectedIdentity(readBase(rel), rustProjection),
        after: projectedIdentity(readNow(rel), rustProjection),
      };
    }
    const change = changes.find((c) => c.path === id);
    if (change) return { before: change.before, after: change.after };
    const now = readNow(id) === null ? null : git(['hash-object', '--', id], repoRoot).trim();
    return { before: baseBlobs.get(id) ?? null, after: now };
  };

  // Frontend helpers only tests import: the list at the base and at the head, so dropping a file from
  // the list in the PR that edits it still flags the edit. A malformed side adds nothing; the list
  // itself is a watched baseline and is flagged whenever it changes.
  const testSupport = new Set();
  for (const text of [readBase(TEST_SUPPORT_PATHS_FILE), readNow(TEST_SUPPORT_PATHS_FILE)]) {
    if (text === null) continue;
    try {
      for (const p of parseTestSupportList(text)) testSupport.add(p);
    } catch {
      // flagged as a watched baseline below
    }
  }
  const TEST_SUPPORT_REASON = { kind: 'test-code', reason: `frontend helper only tests import (${TEST_SUPPORT_PATHS_FILE})` };
  const isTestPath = (rel) => classifyPath(rel) !== null || testSupport.has(rel);

  // ---- 1. flag ----------------------------------------------------------------------------------
  const wholeFile = [];
  const flagged = [];
  const outOfScope = [];
  for (const c of changes) {
    if (c.path === LOGIC_SEAMS_PATH) {
      const { before, after } = identity(LOGIC_SEAMS_ITEM);
      if (before !== after) {
        const unparseable = String(before).startsWith('unparseable') || String(after).startsWith('unparseable');
        flagged.push({
          id: LOGIC_SEAMS_ITEM, path: c.path, status: c.status, kind: 'watched-baseline', before, after,
          reason: unparseable ? 'logic-seam register could not be parsed; flagged whole' : 'logic-seam law/targetTests changed',
        });
      }
      continue;
    }
    const cls = classifyPath(c.path)
      ?? (testSupport.has(c.path) ? TEST_SUPPORT_REASON : null)
      ?? (isRustSource(c.path) && rustTestFiles.has(c.path) ? { kind: 'test-code', reason: 'Rust test module file' } : null);
    if (cls) {
      wholeFile.push({ ...c, ...cls });
      continue;
    }
    if (isRustSource(c.path)) {
      let item = null;
      const beforeText = c.before ? readBase(c.path) : null;
      const afterText = c.after ? readNow(c.path) : null;
      try {
        const b = beforeText === null ? null : extractTestItems(normalizeText(beforeText));
        const a = afterText === null ? null : extractTestItems(normalizeText(afterText));
        if ((b?.wholeFile || a?.wholeFile)) {
          wholeFile.push({ ...c, kind: 'test-code', reason: 'Rust file under #![cfg(test)]' });
          continue;
        }
        if ((b?.text ?? '') !== (a?.text ?? '')) item = { reason: 'Rust #[cfg(test)] content changed' };
      } catch (e) {
        if (!(e instanceof RustScanError)) throw e;
        item = { reason: `Rust source could not be scanned (${e.message}); flagged as a parse failure` };
      }
      if (item) {
        const id = c.path + RUST_ITEM_SUFFIX;
        flagged.push({ id, path: c.path, status: c.status, kind: 'test-code', ...identity(id), reason: item.reason });
      }
      continue;
    }
    if (buildConfigKind(c.path)) {
      // Build configuration: flagged when its changed lines touch test selection or execution.
      // A workflow's root test scripts resolve through the root package.json (base and head).
      const options = buildConfigKind(c.path) === 'workflow' ? { rootPackageJson: [readBase('package.json'), readNow('package.json')] } : {};
      const r = buildConfigChange(c.path, c.before ? readBase(c.path) : null, c.after ? readNow(c.path) : null, options);
      if (r) {
        flagged.push({
          id: c.path, path: c.path, status: c.status, kind: 'watched-build-config', before: c.before, after: c.after, reason: r.reason,
        });
      }
      continue;
    }
    if (isOutOfScopeTestLike(c.path)) outOfScope.push(c.path);
  }

  // Only byte-identical moves that keep the execution context pass: pair a deleted and an added
  // in-scope file with the same blob and the same non-null executionContext. A byte-identical move
  // into another module, source set or vitest selection changes how (or whether) the test runs, so
  // both sides stay flagged.
  const moves = [];
  const contextMoves = new Map();
  const added = wholeFile.filter((c) => c.status === 'A');
  const paired = new Set();
  for (const d of wholeFile.filter((c) => c.status === 'D')) {
    const from = executionContext(d.path);
    const same = added.filter((a) => !paired.has(a.path) && a.after === d.before);
    const match = from === null ? undefined : same.find((a) => executionContext(a.path) === from);
    if (match) {
      paired.add(match.path).add(d.path);
      moves.push({ from: d.path, to: match.path });
    } else if (same.length > 0) {
      const to = same[0];
      const note = `moved byte-identically ${d.path} -> ${to.path}, but the execution context changes ` +
        `(${from ?? 'none known'} -> ${executionContext(to.path) ?? 'none known'})`;
      contextMoves.set(d.path, note).set(to.path, note);
      findings.push(finding('move-changes-execution', 'note', `${note}; both sides need an entry`, to.path));
    }
  }
  for (const c of wholeFile) {
    if (paired.has(c.path)) continue;
    const reason = contextMoves.has(c.path) ? `${c.reason}; ${contextMoves.get(c.path)}` : c.reason;
    flagged.push({ id: c.path, path: c.path, status: c.status, kind: c.kind, before: c.before, after: c.after, reason });
  }
  flagged.sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  const flaggedIds = new Set(flagged.map((f) => f.id));

  // ---- 2. changesets ----------------------------------------------------------------------------
  const csPaths = new Set(changes
    .filter((c) => c.status !== 'D' && c.path.startsWith(`${CHANGESETS_DIR}/`) && c.path.endsWith('.md') && !c.path.endsWith('README.md'))
    .map((c) => c.path));
  // Kernel loader: the changesets the branch adds or modifies (all commits, plus local work).
  for (const d of loadChangesets({
    repoRoot, changesetsDir: CHANGESETS_DIR, baselineRef: base.ref,
    allowedClassifications: new Set([CHANGESET_SCHEMA]), classificationField: 'schema', validate: false,
  })) {
    const rel = relative(repoRoot, d.file).replaceAll('\\', '/');
    if (!rel.endsWith('README.md')) csPaths.add(rel);
  }

  const changesets = [];
  for (const rel of [...csPaths].sort()) {
    const text = readNow(rel);
    const parsed = text === null ? { ok: false, error: 'file not readable' } : parseChangeset(text);
    if (!parsed.ok) {
      findings.push(finding('changeset-unparseable', 'error', `${rel}: ${parsed.error}. Nothing in it counts.`, rel));
      continue;
    }
    changesets.push({ path: rel, ...parsed });
  }

  const sourceCtx = { readBase, readNow, changedPaths, changeStatus, isTestPath };
  const coverage = new Map();
  const refs = new Map();
  for (const cs of changesets) {
    cs.entries.forEach((entry, idx) => {
      const where = `${cs.path} entry ${idx + 1}`;
      const items = entryItems(entry);
      if (items.length === 0) {
        findings.push(finding('entry-invalid', 'error', `${where}: no 'items'`, cs.path));
        return;
      }
      for (const id of items) {
        if (!flaggedIds.has(id)) {
          findings.push(finding('stale-entry-item', 'note', `${where}: '${id}' is not flagged in this diff`, cs.path));
        }
      }
      if (entry.ref !== undefined) {
        const errs = validateRef(entry.ref, { readNow, changedPaths });
        for (const e of errs) findings.push(finding(e.rule, 'error', `${where}: ${e.message}`, cs.path));
        if (errs.length === 0) for (const id of items) refs.set(id, (refs.get(id) ?? 0) + 1);
        return;
      }
      const errs = validateEntry(entry, { sourceCtx, readNow });
      for (const e of errs) findings.push(finding(e.rule, 'error', `${where}: ${e.message}`, cs.path));
      if (errs.length === 0) for (const id of items) coverage.set(id, (coverage.get(id) ?? 0) + 1);
      else for (const id of items) if (!coverage.has(id)) coverage.set(id, 0);
    });
  }

  // ---- 3. coverage per flagged item -------------------------------------------------------------
  const uncovered = [];
  for (const item of flagged) {
    const v = verdictForItem({
      id: item.id,
      fullEntries: coverage.get(item.id) ?? 0,
      refs: refs.get(item.id) ?? 0,
      referable: TEST_EFFICACY_REFERABLE_ITEMS.has(item.id),
    });
    item.coverage = v.ruleId.replace('test-intent/', '');
    if (v.status === 'fail') {
      const invalidOnly = v.ruleId === 'test-intent/uncovered' && coverage.has(item.id);
      findings.push(finding(v.ruleId.replace('test-intent/', ''), 'error',
        invalidOnly ? `${item.id}: its entry is invalid (see above), so it is not covered` : v.reason, item.path));
      if (v.ruleId === 'test-intent/uncovered') uncovered.push(item);
    }
  }

  // ---- 4. acceptance per changeset with entries -------------------------------------------------
  const prNumber = base.prNumber ?? prOverride ?? null;
  for (const cs of changesets) {
    const full = cs.entries.filter((e) => e && e.ref === undefined);
    if (full.length === 0) continue;
    const items = [...new Set(cs.entries.flatMap(entryItems))];
    const evidence = [...new Set(full.map((e) => e.evidence).filter(nonEmpty))]
      .map((p) => ({ path: p, content: readNow(p) }));
    const digest = computeDigest({
      changesetPath: cs.path,
      digestedText: cs.digestedText,
      items: items.map((id) => ({ id, ...identity(id) })),
      evidence,
    });
    cs.digest = digest;
    const pr = prNumber ?? (Number.parseInt(cs.frontmatter.pr, 10) || null);
    const v = verdictForAcceptance({
      changeset: cs.path,
      authorSession: nonEmpty(cs.frontmatter['author-session']) && !/^TODO/.test(cs.frontmatter['author-session'])
        ? cs.frontmatter['author-session'] : null,
      digest,
      records: cs.records,
      auditSelected: isAuditSelected(pr),
      prNumber: pr,
    });
    cs.acceptance = v;
    findings.push(finding(v.ruleId.replace('test-intent/', ''), v.status === 'fail' ? 'error' : 'note', v.reason, cs.path));
    if (pr === null) {
      findings.push(finding('accepted', 'note',
        `${cs.path}: PR number unknown here, so audit selection (every fifth PR) was not evaluated; CI evaluates it`, cs.path));
    }
  }

  // ---- 5. summary findings ----------------------------------------------------------------------
  for (const m of moves) {
    findings.push(finding('byte-identical-move', 'note', `${m.from} -> ${m.to} moved without a byte of change; no entry needed`, m.to));
  }
  findings.push(finding('out-of-scope', 'note',
    `${OUT_OF_SCOPE_STATEMENT}${outOfScope.length ? ` Changed here and not flagged: ${outOfScope.length} file(s).` : ''}`));
  if (flagged.length === 0) {
    findings.push(finding('no-flagged-items', 'note', 'Nothing in the product test scope changed; no test-intent entry is needed.'));
  }
  for (const item of flagged) {
    findings.push(finding('flagged-item', 'note', `${item.id} (${item.status}, ${item.reason}) — ${item.coverage}`, item.path));
  }
  const failed = findings.some((f) => f.level === 'error');
  if (failed && base.fork) {
    findings.push(finding('fork-pr', 'error',
      'This PR comes from a fork. A maintainer agent completes the test-intent changeset and the acceptance record ' +
        `(${RULES_DOC}); nothing is needed from the contributor beyond the change itself.`));
  }

  // ---- 6. reconsider rule over the rolling four-week counts (advisory; never changes the verdict) ----
  let reconsider = null;
  try {
    const ref = rollingRef(repoRoot);
    const report = reportFor({ repoRoot, ref, days: ROLLING_DAYS });
    reconsider = { ...reconsiderRate(report.totals), ref, days: ROLLING_DAYS };
    if (reconsider.above) {
      reconsider.message = reconsiderMessage(reconsider, ROLLING_DAYS, ref);
      findings.push(finding('reconsider-rate', 'warning', reconsider.message));
    }
  } catch (e) {
    findings.push(finding('reconsider-rate', 'note', `rolling test-intent counts unavailable here: ${String(e.message).split('\n')[0]}`));
  }
  return {
    verdict: failed || findings.some((f) => f.level === 'error') ? 'fail' : 'pass',
    findings,
    base,
    flagged,
    moves,
    outOfScope,
    changesets,
    uncovered,
    reconsider,
  };
}

/** A reference to a test-efficacy changeset this branch adds or modifies. */
function validateRef(ref, { readNow, changedPaths }) {
  if (!nonEmpty(ref) || !ref.startsWith(TEST_EFFICACY_CHANGESETS) || !ref.endsWith('.md')) {
    return [{ rule: 'entry-invalid', message: `'ref' must name a changeset under ${TEST_EFFICACY_CHANGESETS}` }];
  }
  const text = readNow(ref);
  if (text === null) return [{ rule: 'source-unresolved', message: `'ref' ${ref} does not exist` }];
  if (!changedPaths.has(ref)) {
    return [{ rule: 'source-unresolved', message: `'ref' ${ref} is not part of this branch, so test-efficacy does not count it here` }];
  }
  const fm = parseFrontmatter(normalizeText(text))?.frontmatter ?? {};
  if (!TEST_EFFICACY_CLASSIFICATIONS.has(fm.classification)) {
    return [{ rule: 'source-unresolved', message: `'ref' ${ref} is not a classified test-efficacy changeset` }];
  }
  return [];
}

/** Everything an entry's class needs (D2), its sources (D3) and its evidence (D4). */
export function validateEntry(entry, { sourceCtx, readNow }) {
  const errors = [];
  const cls = entry?.class;
  if (typeof cls !== 'string' || /^TODO/.test(cls)) {
    return [{ rule: 'uncovered', message: "skeleton entry not filled: 'class' is still TODO" }];
  }
  if (!TEST_INTENT_CLASSES.includes(cls)) {
    return [{ rule: 'entry-invalid', message: `class '${cls}' is not one of: ${TEST_INTENT_CLASSES.join(', ')}` }];
  }
  const need = (field, what = field) => {
    if (!nonEmpty(entry[field])) errors.push({ rule: 'entry-invalid', message: `${cls} entry needs '${what}'` });
  };
  const sources = Array.isArray(entry.sources) ? entry.sources : [];
  const checkSources = ({ min, allowRemoval = false, expectation = false }) => {
    if (sources.length < min) {
      errors.push({ rule: 'entry-invalid', message: `${cls} entry needs at least ${min} source in 'sources' (D3)` });
    }
    for (const s of sources) errors.push(...validateSource(s, sourceCtx, { allowRemoval }));
    if (expectation && sources.length > 0 && !sources.some((s) => EXPECTATION_SOURCE_KINDS.includes(s?.kind))) {
      errors.push({
        rule: 'entry-invalid',
        message: `${cls} entry: the expectation needs its own source (${EXPECTATION_SOURCE_KINDS.join(', ')}); ` +
          'a defect source establishes that the old behaviour is wrong, not which fix is right',
      });
    }
  };
  const checkEvidence = (requireFailBefore) => {
    if (!nonEmpty(entry.evidence)) {
      errors.push({ rule: 'evidence-invalid', message: `${cls} entry needs 'evidence' (a file run-evidence.mjs wrote)` });
      return;
    }
    errors.push(...validateEvidence({
      evidenceText: readNow(entry.evidence),
      evidencePath: entry.evidence,
      newChecks: Array.isArray(entry.newChecks) ? entry.newChecks : [],
      requireFailBefore,
      readWorking: readNow,
    }));
  };

  switch (cls) {
    case 'New':
      need('behaviour');
      checkSources({ min: 1, expectation: true });
      checkEvidence(entry.repaired === true);
      break;
    case 'Obsolete':
      checkSources({ min: 1, allowRemoval: true });
      need('conflictSearch', "conflictSearch ('decision records searched for a conflict: none / which')");
      break;
    case 'Adaptation': {
      const moved = Array.isArray(entry.moved) ? entry.moved : [entry.moved];
      if (moved.length === 0 || moved.some((m) => !ADAPTATION_KINDS.includes(m))) {
        errors.push({ rule: 'entry-invalid', message: `Adaptation entry needs 'moved' from: ${ADAPTATION_KINDS.join(', ')}` });
      }
      need('whatMoved');
      for (const s of sources) errors.push(...validateSource(s, sourceCtx));
      break;
    }
    case 'Structural rule kept':
      need('rule');
      need('whySurvives');
      for (const s of sources) errors.push(...validateSource(s, sourceCtx));
      break;
    case 'Incidental': {
      const a = entry.assessment ?? {};
      for (const f of ['purpose', 'callersAndStateOwners', 'indirectBehaviours', 'evidence', 'unresolvedRisks']) {
        if (!nonEmpty(a[f])) errors.push({ rule: 'entry-invalid', message: `Incidental entry needs 'assessment.${f}'` });
      }
      for (const s of sources) errors.push(...validateSource(s, sourceCtx));
      break;
    }
    case 'Defect pin': {
      const ds = entry.defectSource;
      if (!ds || ds.kind !== 'defect-source') {
        errors.push({ rule: 'entry-invalid', message: "Defect pin entry needs 'defectSource' of kind 'defect-source' (D3)" });
      } else {
        errors.push(...validateSource(ds, sourceCtx));
      }
      need('reproduction');
      checkSources({ min: 1, expectation: true });
      checkEvidence(true);
      break;
    }
    default:
      break;
  }
  return errors;
}

/** Plain-text report for a terminal or a CI log. */
export function formatReport(result) {
  const lines = [];
  const b = result.base;
  lines.push(`test-intent: ${result.verdict.toUpperCase()}${b ? ` (base ${b.ref.slice(0, 12)} via ${b.how}; event ${b.event}${b.prNumber ? `; PR #${b.prNumber}` : ''})` : ''}`);
  if (result.reconsider?.above) {
    lines.push('!!!! WARNING test-intent/reconsider-rate (does not fail the gate) !!!!');
    lines.push(`  ${result.reconsider.message}`);
  }
  if (result.flagged.length) {
    lines.push(`Flagged items (${result.flagged.length}):`);
    for (const f of result.flagged) lines.push(`  [${f.coverage ?? '?'}] ${f.status} ${f.id} — ${f.reason}`);
  }
  for (const m of result.moves) lines.push(`  [move] ${m.from} -> ${m.to} (byte-identical, no entry needed)`);
  const errors = result.findings.filter((f) => f.level === 'error');
  if (errors.length) {
    lines.push(`Problems (${errors.length}):`);
    for (const f of errors) lines.push(`  ${f.ruleId}: ${f.message}`);
  }
  for (const cs of result.changesets) {
    if (cs.digest) lines.push(`Changeset ${cs.path}: current digest ${cs.digest} — ${cs.acceptance?.reason ?? ''}`);
  }
  lines.push(result.findings.find((f) => f.ruleId === 'test-intent/out-of-scope')?.message ?? OUT_OF_SCOPE_STATEMENT);
  if (result.verdict === 'fail') {
    lines.push(`How to fix: read ${RULES_DOC}.`);
    if (result.uncovered.length) {
      lines.push('  Write a skeleton for the uncovered items: node scripts/governance/gates/test-intent/cli.mjs --skeleton <name>');
    }
    lines.push(`  Acceptance records go under '${ACCEPTANCE_HEADING}' and are written by the acceptor: cli.mjs accept <changeset> ...`);
  }
  return lines.join('\n');
}

