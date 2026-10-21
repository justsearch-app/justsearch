/** dead-code truth-table — tempdoc 530 §2.9. Verdict logic inlined in enforcer.mjs. */

/**
 * Was a pinned number in `baseline.txt` RAISED in this PR without a changeset saying so?
 *
 * Added by tempdoc 910: `dead-code` was the only ratchet gate with no baseline-shift rule, so
 * editing the pin upward passed as `rebalance-available` and the ratchet could be relaxed by hand.
 *
 * @param {{path: string, priorPin: number, livePin: number, classification: string}} input
 */
export function verdictForBaselineShift({ path, priorPin, livePin, classification }) {
  if (livePin <= priorPin) {
    return {
      ruleId: 'dead-code/within-baseline',
      status: 'pass',
      reason: `${path}: baseline unchanged or tightening`,
    };
  }
  if (classification === 'silent-growth') {
    return {
      ruleId: 'dead-code/silent-baseline-shift',
      status: 'fail',
      reason: `${path}: baseline raised ${priorPin} → ${livePin} without declared changeset`,
    };
  }
  return {
    ruleId: 'dead-code/declared-growth',
    status: 'info',
    reason: `${path}: baseline raised ${priorPin} → ${livePin}; '${classification}' covers`,
  };
}

export function verdictForFile({ path, current, pinned, classification }) {
  if (current <= pinned) return current < pinned
    ? { ruleId: 'dead-code/rebalance-available', status: 'info', reason: `${path}: ${current} < ${pinned}` }
    : { ruleId: 'dead-code/within-baseline', status: 'pass', reason: `${path}: at baseline` };
  return classification === 'silent-growth'
    ? { ruleId: 'dead-code/silent-growth', status: 'fail', reason: `${path}: silent growth` }
    : { ruleId: 'dead-code/declared-growth', status: 'info', reason: `${path}: classification covers` };
}
