/**
 * Execution evidence (tempdoc 966 D4): the file format, and the gate-side validation.
 *
 * The evidence file is written by run-evidence.mjs after it ran the named checks itself; an agent
 * does not write it. The gate checks that the file is well-formed and self-consistent (its own
 * digest), that it was produced by that script, that the content of every check it names is
 * unchanged since the run, that every new check of the entry appears as EXECUTED and passed (not
 * skipped, not missing), and — for repaired behaviour — that a fail-before run on the before-state
 * failed the check with an ASSERTION failure, not a compile or environment failure.
 *
 * Stated limit: the gate cannot prove the script, rather than a hand, produced the bytes; the
 * self-digest and producer fields make a hand-edit a deliberate forgery rather than a slip, and the
 * acceptor's procedure (docs/reference/testing/test-intent.md) re-runs the script on doubt.
 */

import { sha256, normalizeText } from './changeset.mjs';

export const EVIDENCE_SCHEMA = 'test-intent-evidence.v1';
export const EVIDENCE_PRODUCER = 'scripts/governance/gates/test-intent/run-evidence.mjs';

/** A check id is `<repo path>` or `<repo path>#<test name>`. */
export function checkFile(checkId) {
  const i = checkId.indexOf('#');
  return i === -1 ? checkId : checkId.slice(0, i);
}

export function checkName(checkId) {
  const i = checkId.indexOf('#');
  return i === -1 ? null : checkId.slice(i + 1);
}

/** Digest of a check's source file content (line endings normalised). */
export function contentDigest(text) {
  return text === null ? null : sha256(normalizeText(text));
}

/** The digest an evidence file carries over itself (the `digest` field excluded). */
export function evidenceSelfDigest(evidence) {
  const { digest: _omit, ...rest } = evidence;
  return sha256(stableStringify(rest));
}

export function stableStringify(value) {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((k) => `${JSON.stringify(k)}:${stableStringify(value[k])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

export const FAIL_BEFORE_ASSERTION = 'assertion';

/**
 * @param {{evidenceText: string|null, evidencePath: string, newChecks: string[], requireFailBefore: boolean,
 *          readWorking: (rel: string) => string|null}} input
 * @returns {Array<{rule: string, message: string}>}
 */
export function validateEvidence({ evidenceText, evidencePath, newChecks, requireFailBefore, readWorking }) {
  const errors = [];
  const fail = (message) => errors.push({ rule: 'evidence-invalid', message: `${evidencePath}: ${message}` });
  if (evidenceText === null) {
    fail('evidence file not found; run `node scripts/governance/gates/test-intent/run-evidence.mjs` for the new checks');
    return errors;
  }
  let ev;
  try {
    ev = JSON.parse(evidenceText);
  } catch (e) {
    fail(`not valid JSON (${e.message})`);
    return errors;
  }
  if (ev?.schema !== EVIDENCE_SCHEMA) fail(`schema must be '${EVIDENCE_SCHEMA}'`);
  if (ev?.producer?.script !== EVIDENCE_PRODUCER) fail(`producer.script must be '${EVIDENCE_PRODUCER}'`);
  if (ev?.digest !== evidenceSelfDigest(ev ?? {})) {
    fail('self-digest does not match its content: the file was edited after the script wrote it');
  }
  for (const field of ['revision', 'tree', 'createdAt']) {
    if (typeof ev?.[field] !== 'string' || !ev[field]) fail(`missing '${field}'`);
  }
  if (!Array.isArray(ev?.runs) || ev.runs.length === 0 || ev.runs.some((r) => typeof r?.command !== 'string')) {
    fail('missing the commands the script ran (runs[].command)');
  }
  if (!ev?.environment || typeof ev.environment !== 'object') fail("missing 'environment'");
  const checks = Array.isArray(ev?.checks) ? ev.checks : [];
  if (!Array.isArray(newChecks) || newChecks.length === 0) {
    errors.push({ rule: 'entry-invalid', message: "the entry must list 'newChecks' (the new checks the evidence covers)" });
  }
  for (const id of newChecks ?? []) {
    const rec = checks.find((c) => c.id === id);
    if (!rec) {
      fail(`new check '${id}' is not in the evidence`);
      continue;
    }
    const current = contentDigest(readWorking(checkFile(id)));
    if (current === null) fail(`new check '${id}': file ${checkFile(id)} does not exist`);
    else if (rec.contentDigest !== current) {
      fail(`new check '${id}': ${checkFile(id)} changed since the evidence run; re-run the evidence script`);
    }
    if (rec.executed !== true || rec.skipped === true) {
      fail(`new check '${id}' was not executed (${rec.outcome ?? 'skipped'}); a skipped or unselected check is not a pass`);
    } else if (rec.outcome !== 'passed') {
      fail(`new check '${id}' did not pass on the candidate (${rec.outcome})`);
    }
    if (requireFailBefore) {
      const fb = Array.isArray(ev?.failBefore?.checks) ? ev.failBefore.checks.find((c) => c.id === id) : null;
      if (!fb) {
        fail(`repaired behaviour needs a fail-before run for '${id}' (run-evidence.mjs --before <ref>)`);
      } else if (fb.classification !== FAIL_BEFORE_ASSERTION) {
        fail(`fail-before for '${id}' is '${fb.classification}', not an assertion failure in the named check ` +
          '(a compile or environment failure does not show the old behaviour was wrong)');
      }
    }
  }
  return errors;
}
