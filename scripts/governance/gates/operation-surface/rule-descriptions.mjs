/**
 * Rule descriptions for the operation-surface gate (tempdoc 550 Thesis III).
 * Keyed by ruleId; surfaced in SARIF + `--explain`.
 */
export const OPERATION_SURFACE_RULE_DESCRIPTIONS = {
  'operation-surface/undeclared-surface':
    'A production file references a registered operation/action lifecycle type but is not ' +
    'registered in governance/operation-surfaces.v1.json. Every surface that reports operation/action ' +
    'lifecycle state must declare its authoritative record (tempdoc 550 Thesis III; the ' +
    '§B.2 / F-2 drift class) — register it (decide projection vs fork) or stop referencing the type.',
  'operation-surface/orphan-surface':
    'A registered surface path no longer exists — the register has drifted from the code. Remove ' +
    'the stale entry or fix the path.',
  'operation-surface/dangling-guard':
    'A surface declares a guard (gate:<id> / test:<Name>) that does not resolve to a real gate in ' +
    'registry.v1.json or a real *<Name>*.{java,py} file. Fix the guard or the reference.',
  'operation-surface/register-missing':
    'governance/operation-surfaces.v1.json (the operation-surface register) was not found at its ' +
    'configured path.',
  'operation-surface/projection-lineage':
    'A surface is missing consumesProjection, or names one that does not resolve. Every surface must ' +
    'declare its semantic source (another surface id, "canonical-record", or "self") so no surface ' +
    'can model lifecycle state from an undeclared source (tempdoc 550 Thesis III; §B.2 teeth). ' +
    'Add or fix the consumesProjection declaration.',
  'operation-surface/all-surfaces-declared':
    'Every production reference to the canonical IndexingJobView record is a registered surface (healthy).',
  'operation-surface/surfaces-resolve': 'Every registered surface path exists (healthy).',
  'operation-surface/guards-resolve': 'Every registered surface guard resolves (healthy).',
  'operation-surface/projection-lineage-resolves':
    'Every surface declares a resolvable consumesProjection — its semantic source (healthy).',
  'operation-surface/forbidden-second-authority':
    'A file matches a declared forbiddenReintroduction pattern (tempdoc 561 P-C / §11): a NEW ' +
    'write-store re-modeling content that already has ONE canonical authority (e.g. the removed ' +
    'InteractionLog thread fork). The import-scan cannot catch a new-vocabulary store, so the fork ' +
    'class is named explicitly. Project the canonical record named in the violation; do not fork.',
  'operation-surface/no-second-authority':
    'No forbidden second-authority (§11 fork-class) pattern is present (healthy).',
  'operation-surface/vacuous-scan':
    'The auto-scan found fewer lifecycle-record-referencing files than scan.expectedMinPopulation ' +
    '(default 1) — almost always a renamed/moved scan root, not a real removal. A positive-coverage ' +
    'gate whose scan collapses to zero passes VACUOUSLY (enforces nothing while green) — the §5 ' +
    'vacuous-pass downgrade (tempdoc 576). Fix scan.javaMainRoots / tsRoots, or lower ' +
    'scan.expectedMinPopulation in the register with the change that shrank the population.',
  'operation-surface/scan-population-live':
    'The auto-scan detected >= the declared floor of lifecycle-record-referencing files — not ' +
    'vacuous (healthy).',
};
