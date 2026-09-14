# C2-6 component-choice producer — 2026-09-14

Decline/re-enable now uses one captured settings snapshot for both the no-op
predicate and the candidate. Changed choices accept through the composed internal
SettingsService before the fixed owner commits. Unchanged choices allocate no row.
A stale candidate fails the existing API response and cannot erase newer intent;
missing composition fails before any write. No extra store or terminal owner.

Focused1423 executes 28 cases in seven suites, zero failures/errors/skips. Main/test
PMD and Spotless pass. The real SQLite/owner fixture proves committed witness
advance, repeated-choice no-accept, concurrent chat intent preservation and a
missing-owner refusal without mutation. Tests1421 also passed before the explicit
no-extra-row assertion. Source hashes, exact command, artifacts and retention are
in [the manifest](component-choice-producer.json).

Independent review is clear after adding an explicit successful-response /
ACCEPTED-row regression: it must fail without changing settings or the witness.
Final1423 executes that regression.

This is an installer sub-item. Chat model selection, ONNX selection, pack import,
public settings ingress and integrated live/installed/hosted proof remain required.
The earlier hosted gate correction e0cfb76de passed [CI34791639369](https://github.com/justsearch-app/justsearch/actions/runs/34791639369);
that success does not substitute for current-head proof.
