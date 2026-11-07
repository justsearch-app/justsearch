/**
 * wire-Category enforcer — tempdoc 530 §4.3 (Pass-7 Phase F deeper unification).
 *
 * Self-contained gate. Internally:
 *   - resolves baseline ref (git-base, fallback HEAD~1)
 *   - invokes runProtobufBufBreaking (the buf wrapper)
 *   - parses changesets via the protobuf-specific changeset-parser
 *   - reads VERSION delta vs baseline
 *   - computes verdict via the protobuf truth-table
 *   - synthesizes findings (structural breaks + verdict)
 *
 * Replaces the Pass-7 Phase D3 passthrough. The lib files (truth-table,
 * changeset-parser, rule-descriptions, protobuf-buf-breaking) all live in
 * this directory now; `scripts/contract-governance/` is retired.
 */

import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { runProtobufBufBreaking } from './protobuf-buf-breaking.mjs';
import {
  parseChangesets,
  aggregateClassifications,
} from './protobuf-changeset-parser.mjs';
import { computeVerdict } from './protobuf-truth-table.mjs';
import { CONTRACT_GOVERNANCE_RULE_DESCRIPTIONS } from './protobuf-rule-descriptions.mjs';
import { readFileAtRef, resolveBaselineRef } from '../../lib/git-utils.mjs';

export const WIRE_CLASSIFICATIONS = new Set([
  'additive-optional', 'additive-required', 'enum-value-added',
  'rename', 'remove', 'enum-value-removed', 'enum-value-renamed',
  'type-change', 'package-rename', 'emergency-override',
]);

// Re-export for SARIF emission.
export const WIRE_RULE_DESCRIPTIONS = CONTRACT_GOVERNANCE_RULE_DESCRIPTIONS;

function readFixtureBaselineVersion(fixtureRoot) {
  const p = resolve(fixtureRoot, '_baseline', 'VERSION');
  if (!existsSync(p)) return null;
  return readFileSync(p, 'utf8').trim();
}

/**
 * Self-contained wire-Category enforcer. Conforms to the kernel's gate
 * interface: (options) → { toolName, toolVersion, findings, verdict,
 * ruleDescriptions }.
 */
export async function enforceWire(options) {
  const { repoRoot, gate, baselineRef, fixtureMode = false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;

  const cfg = gate.config ?? {};
  const specDir = cfg.specDir ?? 'contracts/wire';
  const versionFile = cfg.versionFile ?? 'contracts/wire/VERSION';
  const changesetsDir = gate.changesetsDir ?? 'contracts/wire/.changesets';

  // Resolve baseline ref. The kernel pre-resolves for ratchet-file gates with
  // diffStrategy: git-base — but this gate's baseline.kind is 'git', and the
  // runner passes baselineRef. If it didn't (e.g., fixture mode), resolve ourselves.
  let ref = baselineRef;
  if (!ref && !fixtureMode) {
    try {
      ref = resolveBaselineRef(
        { strategy: gate.baseline?.strategy ?? 'git-base', fallback: gate.baseline?.fallback ?? 'HEAD~1' },
        sourceRoot,
      ).ref;
    } catch {
      ref = null;
    }
  }

  // Buf wrapper invocation. In fixture mode, the buf wrapper uses
  // <fixtureRoot>/_baseline/ as the "against" target.
  const target = { specDir: fixtureMode ? '' : specDir, format: 'protobuf' };
  const enforcerResult = await runProtobufBufBreaking(target, {
    repoRoot: sourceRoot,
    baselineRef: ref ?? 'HEAD',
    fixtureMode,
  });

  // Distinguish structural breaks from runner-level errors (per §A.10).
  const structuralBreaks = enforcerResult.findings.filter(
    f => !f.ruleId.startsWith('contract-governance/'),
  );
  const runnerErrors = enforcerResult.findings.filter(f =>
    f.ruleId.startsWith('contract-governance/'),
  );

  // Parse declared classifications (PR-scope via changeset-parser).
  const declarations = parseChangesets({
    repoRoot: sourceRoot,
    changesetsDir,
    baselineRef: ref ?? 'HEAD',
    fixtureMode,
  });
  const aggregated = aggregateClassifications(declarations);

  // VERSION delta.
  const currentVersion = (() => {
    const p = resolve(sourceRoot, versionFile);
    return existsSync(p) ? readFileSync(p, 'utf8').trim() : null;
  })();
  const baselineVersion = fixtureMode
    ? readFixtureBaselineVersion(fixtureRoot)
    : (ref ? readFileAtRef(ref, versionFile, sourceRoot) : null);

  // Compute verdict.
  const v = computeVerdict({
    classification: aggregated,
    breaks: structuralBreaks.length > 0,
    currentVersion,
    baselineVersion,
  });

  // Translate to gate-interface findings.
  const findings = [...runnerErrors, ...structuralBreaks];
  let verdict = 'pass';

  // Tempdoc 930 §19.3 F7 — FAIL CLOSED on a runner-level error.
  //
  // Until this branch existed, `runnerErrors` were pushed into `findings` and then ignored by the
  // verdict: with no buf CLI the wrapper returns zero structural breaks, `computeVerdict` sees
  // `breaks: false`, and the gate returned `pass` while having inspected no .proto at all. That
  // is not a soft edge, it is the failure mode this gate exists to prevent — a breaking protobuf
  // change ships with a green wire gate because the tool was absent. `buf-runner-error` (a buf
  // usage/config/internal exit) failed open the same way and is covered by the same branch: an
  // enforcement mechanism whose precondition is absent must fail loudly, not degrade to a pass
  // (tempdoc 742, the same rule the RUNNER applies to missing gate inputs).
  //
  // CI installs buf ahead of this gate (`bufbuild/buf-setup-action` + `npm ci --prefix
  // scripts/wire-contract`). Locally, `npm install` in scripts/wire-contract/ is the remedy the
  // finding already prints.
  if (runnerErrors.length > 0) {
    verdict = 'fail';
  }

  if (v.status === 'fail') {
    verdict = 'fail';
    findings.push({
      ruleId: `contract-governance/${v.ruleId}`,
      level: 'error',
      message: v.reason,
      uri: changesetsDir,
    });
  } else if (v.status === 'pass-noop' || v.status === 'pass') {
    // Pass verdicts are not surfaced as findings (matches the original
    // contract-governance behavior; only the structural-break + verdict-fail
    // findings appear in SARIF).
  }

  return {
    toolName: 'justsearch-wire',
    toolVersion: enforcerResult.toolVersion,
    findings,
    verdict,
    ruleDescriptions: WIRE_RULE_DESCRIPTIONS,
  };
}
