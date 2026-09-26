# C2 integrated local proof1059

Tested13b6bd6802c860dcb5a891bcab730891f19464c7 on Windows/Java25.
Command: `gradlew.bat build pmdAll :modules:ui:compileIntegrationTestJava --continue -PtestParallelism=1 --max-workers=4 --console=plain`.

BUILD SUCCESSFUL in10m36s.10301 represented cases,0 failures/errors,35 inherited
skips,1662 suites. Eight test tasks executed;30 reused unchanged successful inputs.
See latest-full-run-summary.json for executed counts and each task's status.
PMD and UI integration-test compilation pass. Full1038's obsolete-overload failure
remains preserved; this is the successful integrated recheck after those removals
and the workflow/stream corrections. The frontend's latest full proof is1050
(6508 cases, typecheck/lint), with exact-source restored focused1052 afterward.

Retained raw evidence: tmp/c2-3-full1059.txt, tmp/c2-3-full1059-counts.json and
all XML in tmp/c2-3-full1059-xml/. The inventory hashes these accessible artifacts;
retain through lane acceptance plus30 days and export before worktree release.

Hosted is separate: at09:13UTC CI34748767868 for this revision is pending, with
zero assigned jobs. CLA34748767771 is queued on ubuntu-latest and has no runner.
The owner supplied the older CLA34746837244 runner log explicitly reporting all
hosted ubuntu-latest runners busy. This explains that job's allocation wait; it is
not a test failure or successful hosted proof. The public GitHub status page reports
Actions operational, so there is no evidence here to claim a global outage or a
specific account limit. Continue local work and required pushes; hosted-required
acceptance remains open until jobs execute successfully.

Hosted metadata: tmp/c2-hosted-34748767868.json,
tmp/c2-hosted-34748767868-jobs.json, tmp/c2-hosted-34748767771-jobs.json.
[CI run](https://github.com/justsearch-app/justsearch/actions/runs/34748767868).
[GitHub status](https://www.githubstatus.com/).

Next: C2-4's wire drift test/correction; C2-3's settings/ingestion/reindex consumers
remain owned by C2-6/8/10 as recorded in C2-4-plan.md. No batch/stage closure.

Inventory correction1065 treats absolute and relative temporary-directory roots as
storage locations, not artifact selections. The earlier1060 expansion traversed
the entire temporary tree; its replacement retains29240 explicitly cited C2 files
(2877558111 bytes), with two historical unresolved prefix citations preserved as
unresolved. Individual verification JSON files additionally own their source and
artifact inventories. The inventory is identity/access evidence, not a test pass.
