/**
 * SARIF rule descriptions for the test-intent gate (tempdoc 966 D1). Agent-facing rules:
 * docs/reference/testing/test-intent.md.
 */

export const TEST_INTENT_RULE_DESCRIPTIONS = {
  'test-intent/no-flagged-items': 'Nothing in the product test scope changed; no entry is needed',
  'test-intent/flagged-item':
    'A test file, test-owned data file, frontend helper only tests import, watched baseline, or build configuration that selects or runs tests changed and needs a test-intent entry',
  'test-intent/byte-identical-move': 'A test file moved without a byte of change and runs in the same execution context; it needs no entry',
  'test-intent/move-changes-execution':
    'A test file moved without a byte of change, but into a different execution context (module, source set or vitest selection); both sides are flagged',
  'test-intent/out-of-scope': 'Script and gate self-tests under scripts/ and the jseval suite are out of scope by design',
  'test-intent/uncovered': 'A flagged item has no test-intent entry (write one from the skeleton: cli.mjs --skeleton)',
  'test-intent/covered': 'A flagged item is covered by an entry or a test-efficacy reference',
  'test-intent/reference-not-allowed':
    'Only test-efficacy changesets are an existing record with a source; this item needs a full entry',
  'test-intent/second-entry': 'An item a test-efficacy changeset accounts for needs a reference, not a second entry',
  'test-intent/entry-invalid': 'An entry lacks what its class needs (docs/reference/testing/test-intent.md)',
  'test-intent/source-not-a-citation': 'Task scope, a heading or a brief was cited; that is never a source of intent',
  'test-intent/source-unresolved': 'A cited source does not resolve, or its quote is not in it',
  'test-intent/source-written-in-pr': 'A cited document was added or modified in the same PR; a source written in the PR is circular',
  'test-intent/evidence-invalid': 'The execution evidence for a new check is missing, stale, skipped, or lacks an assertion fail-before',
  'test-intent/changeset-unparseable': 'A test-intent changeset could not be parsed; nothing in it counts',
  'test-intent/acceptance-missing': 'A changeset with entries has no acceptance record from a non-author acceptor',
  'test-intent/acceptance-stale': 'No acceptance record matches the current digest; something changed after acceptance',
  'test-intent/acceptor-is-author': 'The acceptance record was written by the author',
  'test-intent/acceptance-rejected': 'A reviewer rejected the entries on the current content (only changed content clears a rejection), or the tie-break rejected them',
  'test-intent/audit-required': 'This PR is audit-selected (every fifth by number) and needs a second record from a different reviewer',
  'test-intent/tie-break-required': 'Reviewers disagree on the same content (a rejection and a later acceptance, or acceptor and auditor); a third reviewer writes the deciding tie-break record',
  'test-intent/accepted': 'The entries are accepted by a non-author acceptor, bound to the current content',
  'test-intent/base-unresolved': 'The base commit could not be resolved (shallow clone, missing base); the gate fails closed',
  'test-intent/fork-pr': 'Fork PR: a maintainer agent completes the changeset and the acceptance record',
  'test-intent/reconsider-rate': 'Advisory, never fails: over the rolling four weeks, third-reviewer tie-breaks (or, before any tie-break, disagreements) exceed one in ten audited PRs; the orchestrator reports it as a finding in its next delivery',
  'test-intent/stale-entry-item': 'An entry names an item that is not flagged in this diff',
};
