# C2-4 Activity browser proof

2026-09-13. Browser1252 passes eight functional checks using real Chromium, the Lit app,
and the owned backend. The [manifest](activity-browser-verification.json) pins scripts,
frames, screenshots, measurement, commands and failed attempts. UI1248 reports typecheck and all
6520 tests passing, but its retained unit log also contains a happy-dom teardown AggregateError
and lacks a retained process exit code; this is not a clean-exit proof. The Activity description now names retained history; no routing behavior
changed. The campaign reused the previously verified UI install1235 with skipBuild=true;
its own runtimeHashes list is empty, so this campaign alone does not pin the running binaries.
The next corrected campaign must capture installed/built jar identity.

The actual history EventSource receives a completion. The harness closes that native source,
performs a second real operation, then dispatches an error event on the closed source to
stimulate EnvelopeStream reconnect. A real network request carries the prior checkpoint,
and the missed row renders once. Reconnect sends a connected lifecycle checkpoint before
the lower-sequence replayed update; this proves recovered rows, not monotonic frame ordering. This is controlled source detachment/error injection,
not a physical network outage. Reload followed by selecting the real Activity tab restores
two durable rows; retrying the second UUIDv7 key leaves two rows. Both operation outcomes
are intentional BAD_REQUEST failures from missing resolve-path-hash arguments.

Browser1244 remains FAILED because Playwright offline mode did not terminate the existing
EventSource. Browser1247 passed reconnect but failed an unsupported route-persistence
assumption. Activity is a member of System; URLProjector records the System address and
SystemSurface defaults to Health after reload. Browser1252 follows this existing contract
by clicking the visible Activity tab; no private component state is modified.

The root inspected the screenshot and measurement: both rows are readable, the history
status is visually connected (not a harness assertion), there is no document overflow and there are zero console errors.
The measured theme label is inaccurate: the custom harness omitted capture_measure's
explicit theme argument, which defaults to dark, while the page rendered light. This is
not dark-theme evidence.

Functional success is not accessibility closure. Measurement finds three unbaselined axe
rules: aria-prohibited-attr on new-event dots (2 nodes), aria-required-parent on table rows
(3 nodes), and color-contrast on ledger chip counts (5 nodes, measured 3.66:1). Independent
source tracing confirms these components are unchanged relative to028be4ac8 and the table
hierarchy is a real defect, not a shadow-DOM false positive. The next C2-4 UI correction
will give meaningful new dots valid semantics, restore the table role hierarchy, and remove
insufficient count dimming, then rerun the same browser proof with explicit theme metadata.
No baseline waiver is introduced. Installed-schema5 and hosted proof remain required.

Every owned campaign stack stopped; the retained PASS quick_health reports ABSENT,
foreignRuns empty and no inference orphan. Raw artifacts are retained through lane
acceptance plus30days and must be exported before worktree release.
