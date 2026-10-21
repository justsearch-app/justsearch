# PR 841: UI and app-services test stalls

Task t-20261020-540095; builder session 01a1562b-b2e9-7510-832f-04ea6ad6da28.
Before repair: HEAD 56ef4483e. Merge base: 2ba7d810e4c5a5173d5182c3c5a7294aab49562a.
No commits, installed Engines, model downloads or million-row fixtures were used.

## Cause and repair

Both stalls park the JUnit Test worker in CompletableFuture.join at
IndexingJobsBridgeWiring.java:72, reached through RemoteIndexingJobsBridge.start and
HeadAssembly.activateKnowledgeServerBinding. The mocks do not acknowledge the newly added
terminal-outcome registration. Activation waits synchronously before it can return its future;
JUnit's timeout interruption cannot end join. The row subscription has not opened yet, so an
SSE sender or slow row drainer is not needed to reproduce either stall.

| Test | Main | Broken branch | Fixed branch |
| --- | ---: | --- | ---: |
| HeadlessAppComponentRegistryCoverageTest.productionApiPhaseRegistersAllFourComponentsInTheEngineRootRegistry | 1.186 s | parked; dump captured; terminated at 76.883 s | 1.963 s |
| HeadAssemblyTest.recoveredWorkerPreparationDefersIndexingJobsUntilActivation | 0.022 s (clean baseline) | parked; dump captured; terminated at 57.412 s | 0.021 s |

The dump files are ui-stalled.threads.txt and services-stalled.threads.txt. They were produced by
`jcmd <owned-test-pid> Thread.print` before terminating those two diagnostic JVMs with SIGTERM.
Their identities and start ticks were checked before signaling. The resulting diagnostic task
failures/aborted-test SKIPPED records are not passing checks.

Repair: open the row subscription from the terminal registration's completion callback.
Activation returns its pending readiness future immediately. The registration barrier remains:
no rows open before the independent terminal feed is registered. Cancellation closes a pending
subscription and any handle returned after it was closed. Repeated starts share the same
in-flight readiness future; stop or producer completion before the initial snapshot fails it.
No thread, buffer, timeout policy or observer-delivery executor was added. Terminal ledger
forwarding and scan-rollup handling are unchanged.

## Full default-task results

Durations below are wall time from the Gradle Test task's before-execution callback through its
root afterSuite callback, including test-worker startup. These are task times, not compilation
or time waiting for a Gradle slot. The captured suite times differ by only a few milliseconds.

| Module | Merge-base wall time | Fixed wall time | Fixed results |
| --- | ---: | ---: | --- |
| ui | 482.076 s | 493.686 s | 2022 tests, 0 failed, 1 skipped |
| app-services | 687.927 s (diagnostic; see caveat) | 683.880 s | 3989 tests, 0 failed, 11 skipped |

The fixed two-module invocation completed successfully in 19m 47s. UI had the same 2,022 case
names and one skip before/after. Every one of the 3,972 distinct main app-services case names
also ran on the candidate; the branch adds 17 cases (12 from the previously accepted queue
work and five lifecycle checks in this repair). Candidate app-services: 3,989 tests, zero
failures and 11 skips. No filters, assumptions, tags or assertions were weakened.

Baseline caveat: the first complete app-services run executed 3,990 results, including class
retries, in 687.927 s. One existing StoredChatBudgetTest relative-path assertion failed in
three attempts because the global task-local data/home overrides interfered with its fixture;
all main case names ran. That is not a clean passing baseline. The clean, CI-environment rerun
later aborted after 323.596 s / 1,445 results with a native SIGSEGV in
lightgbmlibJNI.new_voidpp during LambdaMartRerankerTest.rerank_threadSafe_noConcurrentExceptions.
This is in the unmodified merge-base checkout; its hs_err crash log was kept outside the repository (unrelated LightGBM JNI crash, 290 KB with local paths).
The candidate passed that native section and completed the full suite. No unrelated native
code/test was changed or excluded, and no complete clean-baseline duration is claimed.

## Matching CI and separating local setup failures

Read .github/workflows/ci.yml jobs app-ui-ui and app-ui-services. CI runs:

```sh
./gradlew :modules:ui:test -PskipWebBuild=true --console=plain
./gradlew :modules:app-agent:test :modules:app-agent-api:test :modules:app-api:test \
  :modules:app-api-tck:test :modules:app-config:test :modules:app-launcher:test \
  :modules:app-observability:test :modules:app-services:test :modules:app-util:test \
  -PskipWebBuild=true --console=plain
```

The initial comparisons used those task sets batched together, with --continue and --info.
Final fixed verification reran only the two affected modules. Every actual Gradle invocation
used js-gradle-slot with its default wait. CI=true; 384 MiB test heap, module JVM flags, default
JUnit 30-second timeout, existing longer per-test timeouts, retry policy, excluded tags and
asset-dependent self-skipping were retained. Node dependencies and CI's native bound-exec
helper were staged in the shared baseline clone. Models remained unavailable as in CI.

The instrumentation is preserved as documentary text in timing-init.gradle.txt. It writes
per-test start/end/result and task timings without changing test selection or assertions.
A private Gradle home reuses installed wrapper/caches and avoids the machine-wide tempdir
argument provider overriding the requested java.io.tmpdir. Its normal environment sanitizer
was duplicated: JUSTSEARCH_SERVER_EXE and JUSTSEARCH_GPU_ENABLED are host-only developer
overrides and are absent in CI. UI uses task-local JUSTSEARCH_HOME/JUSTSEARCH_DATA_DIR.
App-services removes those overrides and scopes user.home to an ignored temporary directory,
so path fixtures see CI's unset overrides while settings remain isolated. Actual test JVM
metadata is captured in actual-jvm-*.json; java.io.tmpdir is the ignored tmp/qh directory.
No JAVA_TOOL_OPTIONS was used. No Engine was launched outside the tests.

Exploratory setup runs are not green evidence: an overlong fixture temp path caused the
app-agent Unix-socket fixture to fail; a data-dir override affected app-launcher's default-path
fixture; a private Gradle home initially omitted the host server/GPU sanitizer. Failed runtime
activation fixtures were then prioritized by Gradle on a rerun and initialized Mockito while
os.name was spoofed as Windows, causing java.exe bootstrap failures on Linux. A cleanTest
restored fresh-checkout ordering. These setup runs were stopped only by their recorded JVM
identity. No test or product change was made to conceal those failures. The fresh baseline's
separate native crash is retained, not converted into a pass.

## Tests and intent

IndexingJobsRegistrationLifecycleTest is new and durable, with five controlled checks:
nonblocking activation with the acknowledgement withheld; a shared pending readiness future
and preserved registration barrier; stop before acknowledgement; registration error and early
producer completion; and closing a row handle returned after cancellation. The fixture always
releases the old implementation's join during fail-before cleanup and joins its test thread.
It modifies no JVM-wide config, locale or clock and creates no Engine or database.

HeadAssemblyTest adaptation (only recoveredWorkerPreparationDefersIndexingJobsUntilActivation):

| Old fixture/assertion | New fixture/assertion | Classification |
| --- | --- | --- |
| Mock provides no response on the new outcome port | Mock sends the required empty registration snapshot on that port; no job/terminal row added | Adaptation: signature/input encoding |
| verify(client, never()).subscribeIndexingJobs(...) before activation | Identical call | Kept |
| verify(client).subscribeIndexingJobs(...) after activation | Identical call | Kept |
| assertFalse(successfulRegistration.isClosed()) | Identical call | Kept |

fixture-assertion-proof.json records the three byte-equivalent calls after whitespace
normalization. Changeset entries 36 and 37 cover the adaptation and new expectations;
all existing entries and acceptance records remain unchanged.

The unchanged run-evidence.mjs ran its entire lifecycle under js-gradle-slot and produced
queue-scale-registration-lifecycle.json. Candidate: all six selected tests pass. On 56ef4483e,
the five new cases fail with the activation-return assertion (15 failures because CI retries
the class), and the adapted existing case passes. The evidence runner exits 1 because that
preservation check correctly passes before; the repaired-class check is a valid assertion
fail-before. No receipt was hand-authored. The task-local before worktree is removed by the
producer. Targeted verification before the full run passed 69 tests; final app-services PMD
main/test passed in 12 s. The final full run includes the subsequently added fifth case.

Final gate: `node scripts/governance/gates/test-intent/cli.mjs --pr 841` exits 1 with only
acceptance-stale remaining. The existing reconsider-rate warning is nonblocking. Existing
acceptance records were verified byte-for-byte unchanged. No commit was made.

Cleanup verified: shared baseline and producer worktrees removed, monitors ended, no task-owned
test JVM remains, and ~/.config/justsearch is absent. Abandoned task-local JUnit temp folders
were removed only after their processes ended. Gradle daemons were not stopped.
