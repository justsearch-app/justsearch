/**
 * Operation-surface truth table (tempdoc 550 Thesis III — the anti-fragmentation gate for the
 * operation / indexing-job lifecycle). Sibling of tempdoc 553's execution-surface truth table.
 *
 * Mechanizes "every production surface that reports indexing-job lifecycle state is a DECLARED
 * projection of the one canonical record (the worker jobs table / IndexingJobView), never an
 * independent model." Three checks:
 *
 *   - undeclared-surface: every Java-main / TS file that references the canonical IndexingJobView
 *     type must appear in governance/operation-surfaces.v1.json. A new fork that models the job
 *     lifecycle without registering fails the build (the §B.2 / F-2 recurrence guard).
 *   - orphan-surface: every registered surface path must still exist (register stays honest).
 *   - dangling-guard: every surface's named guard must resolve (a real gate id / test file).
 *
 * HONEST LIMIT (mirrors 553 §5): the auto-scan only sees surfaces that reference the canonical
 * IndexingJobView type. An undeclared fork that re-models the lifecycle from a different shape
 * (e.g. a raw count) is import-invisible — declared explicitly + reviewed, not auto-caught.
 *
 * Conforms to the kernel truth-table contract: (input) → { ruleId, status, reason }.
 */

/** Verdict: production files reference the canonical type but are absent from the register. */
export function verdictForUndeclaredSurfaces({ undeclared }) {
  if (undeclared.length > 0) {
    return {
      ruleId: 'operation-surface/undeclared-surface',
      status: 'fail',
      reason:
        `These files reference a registered operation/action lifecycle type but are NOT registered ` +
        `in governance/operation-surfaces.v1.json: ${undeclared.join(', ')}. Every surface that ` +
        `reports operation/action lifecycle state must declare its authoritative record — not ` +
        `an independent model (tempdoc 550 Thesis III; the §B.2 / F-2 drift class). Add each to the ` +
        `register (deciding projection vs fork), or stop referencing the canonical type.`,
    };
  }
  return {
    ruleId: 'operation-surface/all-surfaces-declared',
    status: 'pass',
    reason: 'Every production reference to the canonical IndexingJobView record is a registered surface.',
  };
}

/** Verdict: a registered surface path no longer exists on disk (stale register entry). */
export function verdictForOrphanSurfaces({ orphans }) {
  if (orphans.length > 0) {
    return {
      ruleId: 'operation-surface/orphan-surface',
      status: 'fail',
      reason:
        `Registered surface path(s) no longer exist: ${orphans.join(', ')}. The register ` +
        `(governance/operation-surfaces.v1.json) has drifted from reality — remove the stale ` +
        `entry or fix the path.`,
    };
  }
  return {
    ruleId: 'operation-surface/surfaces-resolve',
    status: 'pass',
    reason: 'Every registered surface path exists.',
  };
}

/** Verdict: a surface's declared guard does not resolve to a real gate id / test file. */
export function verdictForDanglingGuards({ dangling }) {
  if (dangling.length > 0) {
    return {
      ruleId: 'operation-surface/dangling-guard',
      status: 'fail',
      reason:
        `Surface guard reference(s) do not resolve: ${dangling.join('; ')}. A "gate:<id>" must ` +
        `name a real gate in governance/registry.v1.json; a "test:<Name>" must match a real ` +
        `*<Name>*.{java,py} file. Fix the guard or the reference.`,
    };
  }
  return {
    ruleId: 'operation-surface/guards-resolve',
    status: 'pass',
    reason: 'Every registered surface guard resolves.',
  };
}

/**
 * Verdict: every surface must declare which canonical projection it consumes (its semantic source),
 * and that declaration must resolve. This is the §B.2-class teeth the type-allowlist alone lacks:
 * a surface that models lifecycle state from its OWN source (rather than deriving from the one
 * record) is caught because the author must name a real upstream (`consumesProjection`) — another
 * surface's id, the literal `canonical-record`, or `self` (only the canonical type def). A missing
 * or dangling lineage fails the build.
 */
export function verdictForProjectionLineage({ missing, dangling }) {
  const problems = [];
  for (const id of missing) problems.push(`${id}: no consumesProjection declared`);
  for (const d of dangling) problems.push(d);
  if (problems.length > 0) {
    return {
      ruleId: 'operation-surface/projection-lineage',
      status: 'fail',
      reason:
        `Surface projection-lineage problem(s): ${problems.join('; ')}. Every surface must declare ` +
        `consumesProjection naming its semantic source — another surface id, "canonical-record", or ` +
        `"self" (canonical type def only) — so no surface can model lifecycle state from an ` +
        `undeclared source (tempdoc 550 Thesis III; the §B.2 drift class). Add or fix the declaration.`,
    };
  }
  return {
    ruleId: 'operation-surface/projection-lineage-resolves',
    status: 'pass',
    reason: 'Every surface declares a resolvable consumesProjection (its semantic source).',
  };
}

/**
 * Verdict: a forbidden SECOND-AUTHORITY pattern was reintroduced (tempdoc 561 P-C / §11). The
 * import-scan above only sees forks that reference a canonical TYPE; the §11 fork class — a NEW
 * durable write-store that re-models thread/interaction content with its OWN vocabulary (the removed
 * `InteractionLog`) — is import-invisible. This is the structural backstop the honest-limit residue
 * needs: a file whose name matches a declared `forbiddenReintroduction` pattern fails the build, so
 * the exact second-authority fork that §11 removed cannot silently return. Each pattern names the ONE
 * canonical authority the content already has, so the fix is "project that record, don't fork it".
 */
export function verdictForForbiddenReintroduction({ violations }) {
  if (violations.length > 0) {
    return {
      ruleId: 'operation-surface/forbidden-second-authority',
      status: 'fail',
      reason:
        `A forbidden second-authority pattern was reintroduced: ${violations.join('; ')}. This is the ` +
        `tempdoc 561 §11 fork class — a new write-store re-modeling content that already has ONE ` +
        `canonical authority. The import-scan cannot catch a new-vocabulary store, so it is named ` +
        `explicitly here. Project the canonical record (named in each violation), do NOT fork a second ` +
        `store. If this file is legitimately NOT a second authority, refine the pattern in the register.`,
    };
  }
  return {
    ruleId: 'operation-surface/no-second-authority',
    status: 'pass',
    reason: 'No forbidden second-authority (§11 fork-class) pattern is present.',
  };
}

/** Verdict when the register file itself is absent. */
export function verdictForMissingRegister({ path }) {
  return {
    ruleId: 'operation-surface/register-missing',
    status: 'fail',
    reason: `Cannot run the operation-surface gate: the register was not found at ${path}.`,
  };
}
