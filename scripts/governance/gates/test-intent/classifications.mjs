/**
 * test-intent vocabulary — tempdoc 966 D2 (classes), D3 (sources), Measurement (audit).
 *
 * Unlike the ratchet gates, a test-intent changeset is not one classification for the whole PR: it
 * carries one entry per flagged item (or group of items), each with a class from D2 and the
 * warrant that class requires. The agent-facing rules live in docs/reference/testing/test-intent.md.
 */

export const CHANGESET_SCHEMA = 'test-intent.v1';
export const CHANGESETS_DIR = 'gates/test-intent/.changesets';
export const EVIDENCE_DIR = 'gates/test-intent/evidence';
export const RULES_DOC = 'docs/reference/testing/test-intent.md';
export const ACCEPTANCE_HEADING = '## Acceptance records';

/** D2 classes, spelled as the design spells them. */
export const TEST_INTENT_CLASSES = Object.freeze([
  'New',
  'Obsolete',
  'Adaptation',
  'Structural rule kept',
  'Incidental',
  'Defect pin',
]);

/** D2 Adaptation: what may move while the whole oracle is kept. */
export const ADAPTATION_KINDS = Object.freeze(['rename', 'signature', 'split', 'instrumentation', 'input re-encoding']);

/** D3 defect source: the safeguards every product must keep. */
export const SAFEGUARDS = Object.freeze([
  'no hang',
  'no data loss',
  'no crash',
  'no leak across a requested scope',
]);

/** D3 source kinds, plus `removal` (P6: removing test-only code together with its tests). */
export const SOURCE_KINDS = Object.freeze([
  'owner-words',
  'owner-adopted-scenario',
  'delegated-choice',
  'defect-source',
  'decision-record',
  'removal',
]);

/** Kinds that support an expectation (a defect source says the old behaviour is wrong, not which fix is right). */
export const EXPECTATION_SOURCE_KINDS = Object.freeze([
  'owner-words',
  'owner-adopted-scenario',
  'delegated-choice',
  'decision-record',
]);

/** Words agents use for "the task said so" — never a citation (D2 Obsolete, S6). */
export const NOT_A_CITATION_KINDS = Object.freeze(['task-scope', 'task', 'scope', 'heading', 'brief', 'agent-decision']);

/** D2: the acceptor is the verifier, or the orchestrator when it did not build. */
export const ACCEPTOR_ROLES = Object.freeze(['verifier', 'orchestrator']);
export const RECORD_KINDS = Object.freeze(['acceptance', 'audit', 'tie-break']);
export const VERDICTS = Object.freeze(['accept', 'reject']);

/** Measurement: every fifth accepted PR, chosen by PR number, gets a second acceptance record. */
export const AUDIT_EVERY = 5;
export function isAuditSelected(prNumber, every = AUDIT_EVERY) {
  return Number.isInteger(prNumber) && prNumber > 0 && prNumber % every === 0;
}
