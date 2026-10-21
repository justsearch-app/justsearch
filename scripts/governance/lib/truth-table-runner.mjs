/**
 * Truth-table contract — discipline-gate kernel (tempdoc 530 §Open questions
 * "hard" lean).
 *
 * Every gate class authors a `truth-table.mjs` sibling file under
 * `scripts/governance/gates/<gate-id>/` whose exports are pure functions of
 * the shape:
 *
 *   (input) → { ruleId, status, reason }
 *
 * Inputs are gate-specific (a config-surface verdict reads `{metric, current,
 * pinned, ...}`; an npm-audit verdict reads `{target, severity, baseline,
 * current, ...}`). The contract is uniform on the output side:
 *
 *   ruleId : string  (kebab-namespaced; the gate's id is the namespace prefix)
 *   status : 'pass' | 'fail' | 'info'
 *   reason : string  (human-readable; surfaced in SARIF + console output)
 *
 * The enforcer dispatches measurements through its verdict function(s); the
 * runner aggregates verdicts into SARIF findings. The shape constraint
 * prevents drift toward bespoke per-gate vocabularies — every gate's verdict
 * surface is a pure function with the same output type, so adding a new gate
 * is "write some verdict functions + register the enforcer," not "design a
 * new output shape."
 *
 * `assertVerdictShape` is the runtime check. The runner (`run.mjs`) invokes
 * it after loading each gate to verify the contract held; gates that ship
 * non-conforming verdict outputs fail-fast at load time, not at run time.
 */

const VALID_STATUSES = new Set(['pass', 'fail', 'info']);

/**
 * @typedef {Object} Verdict
 * @property {string} ruleId
 * @property {'pass'|'fail'|'info'} status
 * @property {string} reason
 */

/**
 * Assert that a verdict-shaped object satisfies the contract.
 * Throws if not; returns the verdict unchanged on success (for chaining).
 *
 * @param {unknown} verdict
 * @param {{gate?: string, source?: string}} [ctx]
 * @returns {Verdict}
 */
export function assertVerdictShape(verdict, ctx = {}) {
  const label = `${ctx.gate ?? 'unknown-gate'}${ctx.source ? `/${ctx.source}` : ''}`;
  if (!verdict || typeof verdict !== 'object') {
    throw new Error(`[${label}] verdict must be an object; got ${typeof verdict}`);
  }
  const v = /** @type {Verdict} */ (verdict);
  if (typeof v.ruleId !== 'string' || v.ruleId.length === 0) {
    throw new Error(`[${label}] verdict.ruleId must be a non-empty string`);
  }
  if (!VALID_STATUSES.has(v.status)) {
    throw new Error(
      `[${label}] verdict.status must be one of pass/fail/info; got '${v.status}'`,
    );
  }
  if (typeof v.reason !== 'string' || v.reason.length === 0) {
    throw new Error(`[${label}] verdict.reason must be a non-empty string`);
  }
  return v;
}

/**
 * Helper: validate that a gate's truth-table module exports at least one
 * verdict function. Invoked at runner load-time per gate; surfaces drift
 * away from the shape contract before the gate actually runs.
 *
 * @param {Record<string, unknown>} mod  the imported truth-table.mjs module
 * @param {string} gateId
 */
export function assertTruthTableShape(mod, gateId) {
  const verdictExports = Object.entries(mod).filter(
    ([name, val]) => typeof val === 'function' && name.startsWith('verdict'),
  );
  if (verdictExports.length === 0) {
    throw new Error(
      `[${gateId}] truth-table.mjs must export at least one 'verdict*' function ` +
        `(see scripts/governance/lib/truth-table-runner.mjs).`,
    );
  }
}

/**
 * Map a verdict status to a SARIF level. The enforcer is responsible for
 * actually building the SARIF finding; this helper centralizes the mapping
 * so all gates agree.
 *
 * @param {'pass'|'fail'|'info'} status
 * @returns {'note'|'warning'|'error'}
 */
export function statusToSarifLevel(status) {
  if (status === 'fail') return 'error';
  if (status === 'info') return 'note';
  return 'note';
}
