# C2 projection checkpoint: integrated, stress and hosted proof

September 12, 2026. Code revision758aeb2eb; later0bdf584bc changes only the
encoding of the orphan amendment's link label. Queue projection, snapshot ordering
and corrected orphan containment have coherent proof. C2-2 remains open for offline
completion, recorded ingestion, direct HTTP producers and installed fault scenarios.

## Integrated and stress

Windows/Java25 full656, `gradlew.bat build pmdAll -PtestParallelism=1
--max-workers=4 --console=plain`, passes in13m45s. The38 selected test tasks account
for9,917 cases/1,626 suites, zero failures/errors and35 skips. Thirty tasks reuse
unchanged results; eight execute. Source inputs remained unchanged throughout;
the only concurrent edit was the documentation encoding correction.

Preserved before subsequent filters: tmp/c2-2-projection-full-656.txt,
-656-xml/, -656-counts.json and tmp/c2-2-capture-full-656.py. The counts list each
task's execution/reuse status and exclude NO-SOURCE/SKIPPED task directories.

Required repository-wide stress658 (`test -PincludeStress=true --tests '*Stress*'
with the same parallelism limits) stops at app-agent: no tests match that filter.
This is a selection failure, not a failed test. Source inventory locates the two
named stress classes in adapters-lucene and ort-common, plus the changed fixture's
stress-tagged EngineExtractionSandboxChaosTest in app-engine. Targeted659 runs:

```text
gradlew.bat :modules:adapters-lucene:test --tests '*Stress*'
  :modules:ort-common:test --tests '*Stress*'
  :modules:app-engine:test --tests '*EngineExtractionSandboxChaosTest'
  -PincludeStress=true -PtestParallelism=1 --max-workers=4 --console=plain
```

Result: three cases/three suites, zero failures/errors/skips,1m29s. The changed
parser chaos test executes; adapters-lucene is UP-TO-DATE and ort-common FROM-CACHE
with unchanged inputs. Logs, XML and per-task counts are retained under
tmp/c2-2-projection-stress-{658,659} (XML/counts for659). No test selector, tag or
assertion was weakened to bypass the empty modules.

## Hosted revision758aeb2eb

[CI34700660160](https://github.com/justsearch-app/justsearch/actions/runs/34700660160)
is run attempt1. Twelve jobs pass; Public claims fails only because one amendment
link contains a non-UTF-8 section-sign byte. Commit0bdf584bc repairs that byte;
local docs-validate passes and the successor CI's Public claims job passes.
Do not call the original overall run green.

Independent artifact audit finds:

| Artifact | Suites | Cases | Skips | Failures/errors | Recorded flaky retries |
| --- | ---: | ---: | ---: | --- | ---: |
| integration-test-results | 21 | 89 | 42 | 0/0 | 0 |
| unit-test-attribution-app-ui | 807 | 5,228 | 4 | 0/0 | 0 |
| unit-test-attribution-search-worker | 585 | 3,280 | 43 | 0/0 | 0 |

QueueProjectionCommitTest6 and IndexingJobsSnapshotOrderTest10 are unskipped and
green. The operation/admission producer suites listed in hosted-ci.md also remain
unskipped and green. Task logs independently confirm execution of indexer-worker,
worker-services and system-tests integration, rather than inferring it from XML.

The corrected orphan case passes once without retry: Engine3228, deliberately
reused parser8892 (EXTRACTION manifest entry), native ping.exe7248; exact creator
identities and parent chain match, and both descendants die503ms after forced
Engine death. This closes the corrected hosted first-attempt proof. The old run's
failed attempt is preserved as historical evidence, not rewritten.

Raw artifacts: tmp/c2-hosted-34700660160-artifacts/, sibling -run.json, -jobs.json,
-artifacts.json, -integration.txt and -search-worker.txt. Failure log:
tmp/c2-2-hosted-758-failed.txt; workflow routing: tmp/c2-2-workflow-health-657.md.
Hosted artifacts expire December11,2026. Keep local logs/XML through lane acceptance
plus30 days and export them before worktree deletion. This checkpoint does not
claim live installed completion for the remaining producers or close later stages.
