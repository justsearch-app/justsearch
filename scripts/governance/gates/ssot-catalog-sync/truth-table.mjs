/**
 * SSOT catalog-sync truth table — mechanizes the documented "Classpath catalog
 * drift" silent-failure (docs/explanation/04-storage-engine.md): the root SSOT catalog
 * (`SSOT/catalogs/*`) and its classpath copy
 * (`modules/adapters-lucene/src/main/resources/SSOT/catalogs/*`) must stay in
 * sync, or production (which loads the classpath copy) silently drops fields.
 * Converts the advisory `ssot-hint` PostToolUse hook (~70% adherence) into a
 * ~100% gate.
 *
 * Conforms to the kernel's truth-table contract:
 *   (input) → { ruleId, status: 'pass'|'fail'|'info', reason }
 */

/**
 * Verdict for one declared mirror (root file ↔ classpath copy).
 *
 * @param {{
 *   id: string,
 *   rootExists: boolean,
 *   copyExists: boolean,
 *   equal: boolean,
 *   classification: string | null,
 * }} input
 */
export function verdictForMirror(input) {
  const { id, rootExists, copyExists, equal, classification } = input;

  if (!rootExists || !copyExists) {
    if (classification === 'emergency-override') {
      return { ruleId: 'ssot-catalog-sync/emergency-override', status: 'pass', reason: `${id}: missing side permitted by emergency-override` };
    }
    const which = !rootExists && !copyExists ? 'both sides' : !rootExists ? 'the root' : 'the classpath copy';
    return {
      ruleId: 'ssot-catalog-sync/copy-missing',
      status: 'fail',
      reason: `${id}: ${which} is missing. A declared mirror must exist on both sides.`,
    };
  }

  if (equal) {
    return { ruleId: 'ssot-catalog-sync/in-sync', status: 'pass', reason: `${id}: root and classpath copy are in sync` };
  }

  // Content drift between the two copies.
  if (classification === 'intentional-divergence' || classification === 'emergency-override') {
    return {
      ruleId: `ssot-catalog-sync/${classification}`,
      status: 'pass',
      reason: `${id}: copies diverge; classification '${classification}' covers it`,
    };
  }
  return {
    ruleId: 'ssot-catalog-sync/drift',
    status: 'fail',
    reason:
      `${id}: the root catalog and its classpath copy have diverged. ` +
      `Production loads the classpath copy, so the difference is silently dropped in packaged builds. ` +
      `Sync both copies (load /ssot-catalog), or declare 'intentional-divergence'.`,
  };
}

/**
 * Baseline-tampering guard: a mirror present at the baseline ref but removed
 * from the live config silently disables its sync check (mirrors class-size's
 * silent-pin-bump). Only a declared `mirror-retirement` legitimizes removal.
 *
 * @param {{ id: string, classification: string | null }} input
 */
export function verdictForMirrorRemoval(input) {
  const { id, classification } = input;
  if (classification === 'mirror-retirement' || classification === 'emergency-override') {
    return { ruleId: 'ssot-catalog-sync/declared-mirror-removal', status: 'pass', reason: `${id}: mirror retired via changeset` };
  }
  return {
    ruleId: 'ssot-catalog-sync/silent-mirror-removal',
    status: 'fail',
    reason: `${id}: mirror removed from mirrors.json without a 'mirror-retirement' changeset — silently disables its sync check.`,
  };
}
