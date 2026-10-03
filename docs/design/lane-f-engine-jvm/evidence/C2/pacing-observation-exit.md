# Pacing fixture observation exit correction (2026-09-14)

Full1685 exposed an inconsistent successful-exit condition in the existing
EngineForegroundPacingTest. Its poll phase stopped once a document was visible and
five seconds elapsed, then asserted more than10 samples. The saved XML records
exactly10 samples and the failure at line171. Nothing in the exit condition required
the sample-count witness. The unchanged30-second deadline was not what ended that loop.

Root added only pollSamples > 10 to the successful-exit condition. The later assertion,
all health/status calls, corpus, duration floor, deadlines, foreground gauge checks,
and load-phase progress/duty-cycle checks remain intact. No production pacing changed.
The test's class contract explicitly treats its bounds as observation deadlines rather
than speed thresholds; phase2 already exits only after all required witnesses exist.
Independent refute review confirmed this correction, checked the ADR-0048 probe mapping
in governance/adr-probes.v1.json, and rejected weakening/removing the sample assertion.

Focused1687 at1ced178d9 plus this one-line correction executes the actual pacing test
with zero skips/failures/errors; Engine test PMD and whole formatting pass. The command
uses the repository's existing -PtestParallelism=1 setting. The fixture patch and the
different scheduling must not be conflated: source logic and the exact10-sample failure
establish the exit defect; this isolated run does not establish why the separate
concurrent-read drain and CPU embedding tests failed in1685. Their sequential full
verification remains required, as does the integrated lifecycle proof.

Evidence: tmp/1685.txt, tmp/1685-counts.json and tmp/1685-xml; focused tmp/1687.txt,
tmp/1687-counts.json and tmp/1687-xml in
F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify. Retain through final lane
reconciliation plus30 days, at least2026-10-14. Independent review ran no build.
The per-item lifecycle checkpoint1ced178d9 is pushed; its first push received GitHub
HTTP500, remote ref was verified unchanged, and the same non-force push then succeeded.
