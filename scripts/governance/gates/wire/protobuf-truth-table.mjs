/**
 * Truth-table verdict computation — slice 3a-1-8f §A.10 (post 2026-05-07
 * empirical dry-run).
 *
 * Inputs: aggregated classification (highest-bump-wins from changeset
 * parser), structural-diff observation (boolean — buf breaking found
 * any breaks), VERSION delta (current vs. baseline).
 *
 * Output: verdict { status, ruleId, reason }.
 *
 * Status values:
 *   pass     — verdict is OK
 *   fail     — verdict is not OK; gate-mode exits non-zero
 *   pass-noop — no diff at all; no-op PR (treated as pass for gating)
 */

/**
 * @param {{classification: {rule: string|null, requiredBump: string|null}, breaks: boolean, currentVersion: string|null, baselineVersion: string|null}} input
 */
export function computeVerdict(input) {
  const { classification, breaks, currentVersion, baselineVersion } = input;
  const rule = classification?.rule ?? null;
  const versionDelta = computeVersionDelta(currentVersion, baselineVersion);

  // Row 1: no classification, no diff, no bump → no-op PR.
  if (rule === null && !breaks && versionDelta === 'none') {
    return {
      status: 'pass',
      ruleId: 'noop-pr',
      reason: 'No contract changes; no classification or version bump',
    };
  }

  // V1.5 amendment (§B.11, 2026-05-12): unknown / downgrade deltas.
  // Previously fell through to row 2 (phantom-version), which is
  // semantically wrong. Placed before row 2 so phantom-version only
  // fires for the actual patch/minor/major no-diff case.

  // Unknown delta (one side null — typically the very first commit that
  // introduces a VERSION file alongside a new spec): informational pass.
  if (rule === null && !breaks && versionDelta === 'unknown') {
    return {
      status: 'pass',
      ruleId: 'baseline-introduction',
      reason:
        'No baseline VERSION available for comparison (likely first commit introducing the spec)',
    };
  }

  // Downgrade delta (current < baseline; likely a revert PR or an
  // accidental regression). Two sub-cases handled here; the
  // declared-classification × downgrade × no-breaks case falls through
  // to the existing row 4/5 logic, which correctly emits
  // insufficient-bump (downgrade has rank 0 and cannot satisfy any
  // required bump).
  if (versionDelta === 'downgrade') {
    if (breaks) {
      return {
        status: 'fail',
        ruleId: 'downgrade-with-breaks',
        reason: `VERSION downgraded (${baselineVersion} → ${currentVersion}) with structural breaks observed; explicit revert classification required`,
      };
    }
    if (rule === null) {
      return {
        status: 'fail',
        ruleId: 'phantom-downgrade',
        reason: `VERSION downgraded (${baselineVersion} → ${currentVersion}) with no observable spec change and no classification`,
      };
    }
  }

  // Row 2: no classification, no diff, bump present → phantom version.
  if (rule === null && !breaks && versionDelta !== 'none') {
    return {
      status: 'fail',
      ruleId: 'phantom-version',
      reason: `VERSION bumped (${versionDelta}) but no contract changes detected`,
    };
  }

  // Row 3: no classification, breaks present → undeclared break.
  if (rule === null && breaks) {
    return {
      status: 'fail',
      ruleId: 'undeclared-break',
      reason: 'Structural break detected without classification declaration',
    };
  }

  // Row 4 / 5: declared classification, no breaks. Check version-bump match.
  if (rule !== null && !breaks) {
    const requiredBump = classification.requiredBump;
    // Special case: declared rename/remove/etc. but no observed break.
    if (requiredBump === 'major') {
      return {
        status: 'fail',
        ruleId: 'declared-without-diff',
        reason: `Declared '${rule}' but no structural break observed; likely doc rot or stale changeset`,
      };
    }
    if (versionDelta === requiredBump || rankCovers(versionDelta, requiredBump)) {
      return {
        status: 'pass',
        ruleId: 'declared-additive',
        reason: `Declared '${rule}' matches structural state; VERSION bump '${versionDelta}' satisfies '${requiredBump}'`,
      };
    }
    return {
      status: 'fail',
      ruleId: 'insufficient-bump',
      reason: `Declared '${rule}' requires ${requiredBump} bump; got ${versionDelta}`,
    };
  }

  // Row 6 / 7 / etc: declared classification, breaks present.
  // Cross-validate: declared classification must match break severity.
  if (rule !== null && breaks) {
    const requiredBump = classification.requiredBump;
    if (requiredBump !== 'major') {
      return {
        status: 'fail',
        ruleId: 'misclassification',
        reason: `Declared '${rule}' (${requiredBump} severity) but structural breaks observed; classification must be major-tier`,
      };
    }
    // Top-tier classification with breaks: check VERSION bump.
    if (versionDelta === 'major') {
      return {
        status: 'pass',
        ruleId: 'declared-breaking',
        reason: `Declared '${rule}' matches breaks observed; VERSION bumped to ${currentVersion}`,
      };
    }
    return {
      status: 'fail',
      ruleId: 'insufficient-bump',
      reason: `Declared '${rule}' with structural breaks requires major bump; got ${versionDelta}`,
    };
  }

  // Fallback (should not reach).
  return {
    status: 'fail',
    ruleId: 'unhandled-case',
    reason: `Unhandled truth-table case: rule=${rule}, breaks=${breaks}, versionDelta=${versionDelta}`,
  };
}

/**
 * Compute SemVer delta class between two version strings.
 * Returns 'major', 'minor', 'patch', or 'none'.
 *
 * @param {string|null} current
 * @param {string|null} baseline
 */
export function computeVersionDelta(current, baseline) {
  if (!current || !baseline) {
    // If either side missing, treat as no delta. (E.g., the very first
    // commit introducing VERSION to the repo — buf would also see no
    // baseline structure, so verdict stays correct.)
    return current === baseline ? 'none' : 'unknown';
  }
  if (current === baseline) return 'none';
  const [cmaj, cmin, cpat] = parseSemver(current);
  const [bmaj, bmin, bpat] = parseSemver(baseline);
  if (cmaj > bmaj) return 'major';
  if (cmaj < bmaj) return 'downgrade';
  if (cmin > bmin) return 'minor';
  if (cmin < bmin) return 'downgrade';
  if (cpat > bpat) return 'patch';
  if (cpat < bpat) return 'downgrade';
  return 'none';
}

function parseSemver(s) {
  const m = String(s).trim().match(/^(\d+)\.(\d+)\.(\d+)/);
  if (!m) return [0, 0, 0];
  return [Number(m[1]), Number(m[2]), Number(m[3])];
}

/**
 * Does versionDelta cover requiredBump?
 *   major covers minor, patch
 *   minor covers patch
 */
function rankCovers(versionDelta, requiredBump) {
  const rank = { none: 0, patch: 1, minor: 2, major: 3 };
  return (rank[versionDelta] ?? 0) >= (rank[requiredBump] ?? 0);
}
