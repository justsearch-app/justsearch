# Lane F pause checkpoint — 2026-09-15

Paused at the user's request because usage is low. Preserve the active worktree
`.claude/worktrees/lane-f-pr1-verify`, branch `codex/lane-f-pr1`. Merge remains at F;
existing autonomous lane decisions/publication authorization remains in force on resumption.

## Completed preceding item

0431bbe74 is pushed. The bounded recorded Engine producer passes full1826:3,240 cases,
three existing skips, no failures/errors; independent source/evidence review, PMD/format,
docs/governance and local reachability check pass. All13 CI34900811163 jobs and
CLA34900809246 pass. See [the producer proof](recorded-engine-producer.md).

## Current WIP: d.3b.3a

[The connection plan](recorded-handler-connection.md) owns this batch. Installed changes:
atomic watched-root membership/label snapshot; strict resolved exclusion capture; prepared-only
IngestTool and ReindexHandler; eager/late/replacement factory composition using the current
generation supplier; removal of old handler effects; migrated and new focused tests.
Public REST still has its old effect path: .3b retires it through the dispatcher. This WIP
does not finish .3a, C2, or the lane.

1837 production compilation passed.1839 test compilation exposed a missed fully-qualified
ReindexHandler fixture and an unavailable Awaitility helper; root corrected both.1840 then
ran15 agent cases with five failures because newly written test paths were inserted into JSON
without Windows escaping. Root switched fixtures to the JSON serializer, corrected an explicit
collection assertion to require same-policy collapse, and pinned the partial-input test's
failure reason so malformed JSON cannot masquerade as a path refusal.

1841 passes91 cases/15 suites with zero failures/errors/skips; both selected test tasks execute.
Its exact command, revision description, copied XML and counts are under tmp/1841.txt,
1841-counts.json and1841-xml.1840 failure evidence is copied separately.1842 generated-doc,
skills and canonical-link checks pass.1843 applies formatting to the two affected modules.
1844 evaluates three governance gates with zero failures/findings.
These are local focused WIP checks, not independent source review, full suites or final proof.

## Resume in this order

1. Review the actual .3a diff against the selected mechanism. Independent design review was
   completed, but it intentionally excluded implementation. Root integrated the worker drafts
   and corrected the fixture errors; do not treat a worker's self-report as verification.
2. Audit retained path/collection tests against the removed IngestTool tests. Add missing
   boundary coverage, especially NOFOLLOW link/type handling, invalid input/limits, frozen
   ingest correlation refusal and meaningful approval preview. Review new factory tests and
   root snapshot concurrency test; prove their guards with discriminating negative controls.
3. Run affected full app-agent/app-services tests, PMD/format and architecture/governance;
   reconcile any fresh hosted WIP results. Update canonical ingestion/agent/storage docs and
   stale adapter-scan comments to the final implemented behavior, then regenerate projections.
   Canonical documentation still needs that .3a follow-through.
4. Complete .3b REST dispatcher/consumer contract retirement and .3c actual production
   resolver -> runner -> SQLite -> coordinator -> Java producer integration/recovery proof.
5. Reconcile all remaining C2-8/9/10/11/12 and live/model/installed/hosted acceptance before
   proceeding through D1/D2/E/F. None is owner-gated or waived; merge only at F.

No Gradle build, dev stack or fixture UI helper should remain running at pause. The closeout
sweep1838 retained ten stale ui-shot records whose PIDs no longer exist, and reported the
ownerless otlp-sink singleton; it did not kill unrelated processes. The worktree is held for
resumption with review date2026-09-22. Evidence stays in the active worktree's tmp directory
through lane completion plus30 days, at least2026-10-15. Keep this worktree/evidence intact.
