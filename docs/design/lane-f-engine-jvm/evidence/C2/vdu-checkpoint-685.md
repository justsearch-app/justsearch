# VDU integrated checkpoint685

September12,2026; code revision9ca622e4eb3136db341b7cd89abd383a471bdf66,
Windows/Java25. Production source stayed fixed throughout the run; only noncanonical
offline-ownership planning notes changed while it ran.

```text
gradlew.bat build pmdAll -PtestParallelism=1 --max-workers=4 --console=plain
```

PASS in19m14s:9,974 cases/1,635 suites, zero failures/errors,35 skips. Of38 test
tasks,14 execute and24 reuse unchanged results (four FROM-CACHE,20 UP-TO-DATE).
The366 build tasks report57 executed, four from cache and305 up-to-date. PMD and
the build's other required checks pass. The prior focused/negative proofs remain
in backlog-reads.md, vdu-commits.md, vdu-replay-retention.md and vdu-writer.md.

Hosted CI34705992674 is successful at the same code revision. This is workflow
status evidence; no additional per-case artifact audit is claimed in this record.
Metadata: tmp/c2-hosted-34705992674-status.json. The preceding e225cbf4b and
e784a97f3 workflows also passed, as recorded in vdu-writer.md.

Retained local evidence: tmp/c2-2-vdu-full-685.txt, -685-xml/ and -685-counts.json;
the capture utility is tmp/c2-2-capture-vdu-full-685.py. Keep through lane acceptance
plus30 days and export before worktree release. Skips and unchanged-input reuse
are not fresh execution. This run does not establish actual-model or installed
offline-owner completion, current migration-transition correctness or C2-2 closure.

The subsequent source review found a distinct remaining defect: switch-buffer
drain snapshots rows, applies/commits them, then deletes the entire table, losing
new or replaced rows accepted during replay. Fixing exact replay-version removal
is the next bounded item. The coordinator owner/explicit-outcome work remains
required; neither this checkpoint nor its commit is a stopping point.
