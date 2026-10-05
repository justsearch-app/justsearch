/**
 * SARIF rule descriptions for the ssot-catalog-sync gate.
 */

export const SSOT_SYNC_RULE_DESCRIPTIONS = {
  'ssot-catalog-sync/in-sync':
    'Root SSOT catalog and its classpath copy are in sync',
  'ssot-catalog-sync/drift':
    'Root SSOT catalog and its classpath copy diverged — production loads the classpath copy, so the difference is silently dropped in packaged builds',
  'ssot-catalog-sync/copy-missing':
    'A declared mirror is missing on one side (root or classpath copy)',
  'ssot-catalog-sync/intentional-divergence':
    'Copies deliberately differ; covered by a justified intentional-divergence changeset',
  'ssot-catalog-sync/emergency-override':
    'Drift/missing permitted via emergency-override classification (manual review required)',
  'ssot-catalog-sync/silent-mirror-removal':
    'A mirror present at the baseline ref was removed from mirrors.json without a mirror-retirement changeset (silently disables its sync check)',
  'ssot-catalog-sync/declared-mirror-removal':
    'A mirror was retired via a mirror-retirement changeset',
  'ssot-catalog-sync/malformed-mirrors':
    'gates/ssot-catalog-sync/mirrors.json failed to parse — the gate fails rather than crashing the run',
};
