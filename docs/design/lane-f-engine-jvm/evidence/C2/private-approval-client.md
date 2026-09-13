# C2-3 frontend private approval consumption

September13, based on1de0750ff. Human tool approval now reads GET /api/chat/approval
and uses the live gate's full summary, operation, risk and gate behavior. Missing,
malformed or failed lookup refuses the existing gate and shows a notice, with no
replayed raw-argument fallback. Backend AUTO keeps its existing approval path.
A local read revision plus existing run/call identity invalidates late responses on
conclusion, replacement, call completion or destruction; no new approval store is added.

Initial1030 has seven failures among134 controller cases because old fixtures supplied
empty JSON instead of a live gate, or counted GET as approval. The old assist+LOW
fixture used INLINE_CONFIRM while claiming immediate auto-approval and counted any
fetch: a rejection passed for the wrong reason. It now supplies backend AUTO and
asserts the actual approve endpoint, preserving its stated intended behavior.
The medium/high test counts approval POSTs rather than all traffic. Snapshot and
ceremony fixtures explicitly serve live details. Typecheck1030 passes.

Runs1031/1032 pass the original134 tests after fixture migration; attempts to insert
new regressions before those runs failed (first wrong script working directory, then
a non-ASCII marker mismatch), so those runs do not prove the new cases. Run1033
includes all144 passing controller cases. Negative1034 bypasses the pre-prompt stale
read guards and allfive selected late-response cases fail;139 others are unselected.
Negative1035 substitutes raw arguments for the server summary and the selected frozen
summary case fails;143 others are unselected. Both controls are restored before1036.

Final1036 passes6490 tests across483 files, zero failures/skips, plus typecheck and
lint. It includes full frozen target/owning-run approval, four unavailable/malformed
lookup cases and five stale-response cases. The JSON report and logs are retained.
Unchanged Java inputs retain successful integration compilation from1029; no new
Java compile execution is claimed for this TypeScript item. Existing happy-dom abort
teardown diagnostics are retained in the full log and did not fail tests.

```text
cd modules/ui-web
npm run test:unit:run -- --reporter=default --reporter=json --outputFile=../../tmp/c2-3-private-approval-client1036-vitest.json
npm run typecheck
npm run lint
```

UI-step coverage and canonical documentation checks pass at1037 after index/skill
regeneration. The initial affected-step query refuses a Python editable install from
another checkout (1036-affected). With this worktree's scripts/jseval on PYTHONPATH,
1037 reports no mapped steps for the controller-only change; this is not a browser
render proof. The earlier1007 frozen-target presentation geometry remains historical
coverage. Live/model and hosted v3 C2-3 acceptance remain required.

[Verification](private-approval-client-verification.json) pins source hashes and raw
artifacts under `F:/justsearch-public/.claude/worktrees/lane-F-A/tmp/`, prefixes
`c2-3-private-approval-client1030` through `c2-3-private-approval-client1033`,
`c2-3-private-approval-client-negative1034`, `c2-3-private-approval-client-negative1035`,
`c2-3-private-approval-client1036` and `c2-3-private-approval-client1037`.
Keep through stage acceptance plus30 days and export before worktree release.
Continue with C2-3 remaining ingress and integrated acceptance; no prepared producer
activation or later recovery completion is claimed by this client item.
