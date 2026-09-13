# C2 independent-review correction batch — September 13

Accepted review range: c9f5e3e93..c6556fa02; intake checked at429115fec.
This batch precedes further C2-2 producer work. C2-2 remains open; recorded ingest
and reindex activation move to their C2-8/C2-10 owners. No new owner approval is needed.
The supplied independent review is the batch's initial review; root owns correction.
One final independent batch review, at most two correction rounds. Any third-round
scope addition is recorded under the next item's section0.1, never silently expanded.

## Ordered work and acceptance

1. **R1 OPEN — asynchronous persistence failure.** Named test first: fail the effect
   and terminal write, assert the storage failure retains the effect cause, ERROR log
   identifies key and intended state, the Health condition reports unresolved outcome,
   and dispatcher history emits FAILURE. Reuse OperationRecoveryNotice. A single
   first-failure completion signal on the runner feeds a sticky condition and retains
   a fault that occurs before Health attaches; subsequent faults still log. This is
   smaller than another event registry or durable error store. Raw cause text stays
   out of Health/history. Both completion consumers use whenComplete.
   [Named proof and gate](review-r1-persistence-failure.md): final790 passes92 cases.
   The prior synchronousCompletionWriteFailureCannotReturnSuccess assertion that
   history stays empty is superseded by the supplied review's explicit FAILURE
   publication contract; retain its thrown-error, one-effect and RUNNING-row assertions.
2. **R2 OPEN — launcher exclusion.** Acquire AppInstanceLock before operations open;
   two-runner test proves no live sweep/quarantine. Preserve orderly lock release.
   [R2 proof](review-r2-launcher-lock.md): final795 executes45 cases and passes the item gate.
3. **R3 OPEN — transition refusal.** Re-read all ignored lifecycle booleans, reject
   unexpected terminal-write refusal and prove the failure is observable.
   [R3 proof](review-r3-transition-refusal.md): three intended negative failures; final797 passes.
4. **R4 OPEN — audit/admission.** Audit NONE cannot bypass durable mutation acceptance;
   admission failures preserve their reason. Preserve deliberate audit suppression.
   [R4 proof](review-r4-audit-admission.md): five intended negative failures; final800
   passes88 cases, PMD and UI integration-test compilation. Batch review pending.
5. **R5 OPEN — bounded history.** Add identity/checkpoint SQL bounds, inspect SQLite
   efficiently without violating snapshot ownership, bring C2-5 retention/cap/fence
   acceptance forward before any more producers.
   [R5 proof](review-r5-bounded-history.md): final810 passes228 cases, PMD and UI
   integration-test compilation; named negative controls expose retention and
   terminal-publication races. Store/port gates pass; batch review pending.
6. **R6 OPEN — named crash test.** Halt a child JVM after acceptance before its first
   effect, reopen: same key ACCEPTED and effect store empty.
   [R6 proof](review-r6-acceptance-crash.md): final814 executes20 cases, including
   two wrong-side crash witnesses; PMD and UI integration-test compilation pass.
7. **R7 OPEN — installed proof/status.** C2-11 must inspect operations and fail on
   substrate reversion; correct batch1/handoff claims and execute its specified tier.
   [Installed816 and bypass-negative817](review-r7-installed-row.md) prove the
   foundation witness; six keyed C2-11 scenarios and final batch review remain open.
8. **R8 OPEN — swallowed failures.** Background terminalization, failed root walks,
   post-commit queue notifications, bridge snapshot reset/coalescing and launcher close
   all need their specific runnable regressions without suppressing failures.
   [R8 proof](review-r8-failure-boundaries.md): final821 passes136 cases and the
   item gate; negative819/820 expose five boundaries and822 proves coalescing
   is required. Restore823 passes. Batch review remains pending.
9. **R9 OPEN — checklist/design reconciliation.** Reopen/reprove C2-1 schema16; name
   content_hash's C2-8 consumer/absence. Record generation predicate in D1 section1
   as replaceable. Record replacement-null window in C2-12 and D1 removal. Stop
   ingestion work in C2-2; assign C2-8/C2-10. Resolve prepared replay/public-key
   identity before activation, connect or hold unused public preparation surface.
   Supersede lookup-margin decision; restore historical queue-projection sentence
   and add a new dated row. Restore per-item section0.1 instruction/entries; reconcile
   C1 status with the later MCP correction. Never rewrite dated decisions in place.
   [R9 proof](review-r9-reconciliation.md): final835 represents281 passing cases;
   Rust85, store/release compatibility, ports and canonical links pass. Held source
   reconstructs from its packet. Final batch review remains pending.
10. **R10 OPEN — evidence/build/CI.** Generate and commit C2 raw-artifact SHA inventory
    plus latest full-run summary; repair source snapshots from a committed tree;
    include ui:compileIntegrationTestJava in every implementation item's gate;
    correct final hosted outcomes and record successful runs; wire release-assets
    self-test into CI. Every new correction commit from5a10b9dc9 onward has an
    item/command/result body (or an exact command/result evidence pointer) and is pushed
    immediately. The54 earlier bodyless commits are mapped without rewriting history
    in [the historical proof index](historical-commit-proof-map.md).
    [R10 evidence](review-r10-evidence.md): full869 passes10133 represented cases,
    with231 Engine cases freshly executed. Hosted CI34730328727 passes all13 jobs;
    source snapshots and held archives verify exactly. Current CI34731185342 also
    passes all13 jobs; the single final independent review remains pending.

## Verification discipline

Named acceptance test, item gate, then only required additional tiers. Full suite once
at the batch boundary, preserve XML before focused reruns. No elevation beyond stage17.3
and C2 section12 without a dated reason. Per-item commits compile on their own; WIP at
least hourly. Proof inventory and full-run summary are committed and large artifacts
remain accessible with retention. Close only after all named checks and final review.

First review-batch raw inventory is committed in raw-evidence-sha256.json: 194 citations,
12,912 available files (about1.47GB). Unresolved shorthand citations remain explicit
and are R10 work, not silently counted as proof. latest-full-run-summary.json retains
full744 at its original revision; it does not certify this correction batch.

September13 R10 supersedes that initial inventory/summary state: all literal raw
citations resolve, and latest-full-run-summary.json records successful full869 with
explicit reuse. The original inventory and full744 summary remain available from
their prior Git revisions. Final independent review is underway atc56e1a838; R1–R10
stay OPEN until its findings are resolved and the final evidence is accepted.

September13 final review is complete atc56e1a838/a0c7d390a with seven findings.
Implementation corrections are committed through2168d1245; the same reviewer is
checking those corrections. The living schema row is corrected at06a8f85c2. The
literal every-commit-body claim above is narrowed to new correction commits; all54
historical missing bodies now have an immutable item/result/proof mapping with85
verified source blobs. Existing20 correction commits through06a8f85c2 all have bodies.
No history is rewritten, no old check is rerun by this mapping, and no prior failed
or partial proof is reclassified as success. Full900 and fresh hosted verification
remain pending; R1–R10 remain OPEN until correction verification is complete.
