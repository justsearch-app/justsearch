# C2-4 Activity accessibility and browser correction

2026-09-13. Browser1266 (light) and1267 (dark) each pass nine checks using the real Lit UI
and owned backend. Both capture five matching installed/built jar hashes, explicit theme
metadata, actual SSE frames and clean teardown. The
[manifest](activity-accessibility-verification.json) binds source and all retained artifacts.
Root inspection of each PNG agrees with the measurement: two readable history rows, no
page overflow, zero console errors and zero axe violations in these captures. This is
bounded to these captured states/themes, not a claim about every surface or palette.

The table now has a valid table ancestor for its row/cell roles. Meaningful unread dots
have image semantics and labels. Ledger counts/source labels no longer reduce contrast
through opacity; source labels use the existing secondary text grade. StatusBadge uses the
existing toneText authority for status text over its tinted fill. The ordinary light muted
text token advances from0.58 to0.62 opacity after measurement exposed the same low-contrast
text in both StatusDeck and onboarding. Its regression computes actual composited contrast
on all five light surface elevations and preserves the dimmer-than-tertiary hierarchy.
A temporary StatusDeck override was removed once this shared cause was corrected.

Failed1260 remains retained: the first role/count fixes passed but the stricter assertion
caught source/status-deck contrast. Failed1264 then caught onboarding's shared muted token.
Dark1261 passed an earlier variant; final1266/1267 are the final production UI proof. The
controlled EventSource detachment/error stimulus, real checkpoint replay, Activity tab
selection after reload and same-key retry scope remain as documented in
[the preceding campaign](activity-browser.md); no physical-outage or monotonic-lifecycle-frame
claim is added.

Typecheck1273 passes. Full unit1276 passes6521 tests across485 files with explicit exit0,
using four workers and the existing test timeouts. Failed1273 retains one5s import timeout
in unchanged resourceRegistry.test; its focused1275 run passes14 cases in1.38s, and the
complete four-worker run passes in89.93s. Failed1269 retains the new contrast test's tuple
cast type error; root replaced the array cast with an explicit three-element tuple.

Shell fixtures now remove elements, reset the shared AI poller and then restore globals;
ShellRail supplies a fetch stub instead of issuing real requests from a unit fixture.
Focused1271 executes all36 cases across those three suites with zero AggregateError blocks.
The full suite still emits inherited happy-dom connection-refused/abort warnings from other
fixtures; no universal clean-teardown claim or console suppression is made. Those warnings
remain in the raw logs and do not change the captured exit0/unit counts.

The coverage gate passes. The first multi-path affected command1270 was invalid; corrected
single-path1272 queries are retained. Table maps to the existing table step; other queried
files have no mapped step. The real Activity captures above are the behavioral/measurement
proof, independent of that mapping. Hosted and installed-schema5 acceptance checks remain
required; this evidence does not close C2 or the lane. Artifacts remain accessible through
lane acceptance plus30days and must be exported before worktree release.

Independent read-only source/evidence review at9534c3330 rechecked final source hashes,
all nine checks in each final capture, binary scope and unit/typecheck results. No
concrete blocker remains in this UI correction slice; hosted/installed obligations above remain.
