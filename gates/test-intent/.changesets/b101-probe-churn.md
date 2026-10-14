---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b101-probe-churn
---

# Named limits

- `ProbeChurnPrimaryRateMeasurement` is opt-in. It needs a Windows host, about ten minutes, and `JS_PROBE_CHURN_MEASURE=1`; without that variable it is skipped, so the normal suite never runs it. Check (c) is `JS_PROBE_CHURN_MEASURE=1 JS_PROBE_CHURN_ARMS=off,nosink JS_PROBE_CHURN_ASSERT=1 JS_PROBE_CHURN_DOCS=900 JS_PROBE_CHURN_ROUNDS=4`. It asserts the ledger's cost directly (write time at most 2% of indexing time, at most 32 helper records per burst). The off/nosink rate ratio is printed with the host's CPU load and is not asserted: on a shared host single trials vary by a factor of two, and indexing speed is re-measured on a quiet host after the merge.
- `GpuSnapshotNvidiaSmiRateTest` and the Windows process checks run real processes. The GPU rate check is skipped where nvidia-smi is not on PATH.
- On a host where NVML reports a GPU, the snapshot's nvidia-smi section is filled from NVML with source `read in-process (NVML)`, also when nvidia-smi itself is not installed. `nvidiaSmiAvailable` on `/api/inference/status` there says whether nvidia-smi is installed where a launch would find it (System32, the Windows directory, PATH), or what its last reading said, without starting it: an installed nvidia-smi that would fail to run reads available.
- An invalidated nvidia-smi reading inside the 5 s interval is served stale (marked so) rather than re-read; an admission decision taken inside the interval sees figures up to 5 s old.
- Helper receipts written inside an open window are forced to disk as a group at most a second later. They survive a crash of the process that wrote them; a host crash or power loss within that second can lose them, and the census then sees the process without its receipt under an open window.
- The Engine job witness reads windows from the children ledger only for processes started by another process (a child JVM); the Engine's own windows are resolved in memory. A child window whose start record is not yet on disk when its process exits is not seen.
- The verifier's `VXProbeChurnCrossJvmWindowTest` passes the child's window to the witness only on the child's stdout, with no shared ledger; under the ledger design it cannot pass, and `ChildWindowLedgerWitnessTest` checks the same path through the real shared ledger. The verifier's `VxProbeChurnDiskHttpVerifierTest` requires the status build to read the probe's clock twice (it expects the reading at 600000 ms after one clock read at 599999 ms); with one reading per build the status shows the reading at 0 ms with its own time, which `StatusAtRestOneReadingHttpTest` accepts. The verifier's `VXProbeChurnWindowRaceTest` calls the six-argument `ProcessIntents.resolve`, replaced by one that names the creating process.
- In `ProbeChurnPrimaryRateMeasurement` the ledger records not written through the Engine's helper owner recorder (the job witness's process records and the extraction child's records) are timed by replaying them afterwards, each forced, through a writer on the same volume: an upper bound on, not a measurement of, their own write time.
- `DiskEncryptionProbe` has no person-initiated re-read: no existing status or health action asks for one, so none was added and the unused `refresh()` was removed. A changed disk-encryption state shows within ten minutes.

# Test-intent entries

```json
{
  "entries": [
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/OwnedHelperExitOnceTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/JobWitnessIntentTest.java"
      ],
      "class": "New",
      "behaviour": "On real Windows processes: a helper whose exit the OS reports before the owner's wait is recorded once, REQUESTED; a helper nobody awaited or stopped is recorded once, UNEXPECTED, with a reason. In a child JVM inside a real Engine job, the witness records a stopped, an awaited and an unowned process with the owner's class and reason and the basis it used.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "Record each helper exit once, with its final class and a non-null reason: an exit the owner awaited or stopped is REQUESTED with the owner's reason, and one nobody awaited or stopped within a 2 s grace is UNEXPECTED with a reason saying so. The Engine job witness resolves an exiting process's class and reason from the owner's published intent (owned, stop intent, outcome) and records which basis it used (intentBasis).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-exit-once.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/OwnedHelperExitOnceTest.java#anExitSeenBeforeTheOwnersWaitIsRecordedOnceAsRequested",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/OwnedHelperExitOnceTest.java#anExitNobodyAwaitedOrStoppedIsRecordedOnceWithAReason",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/JobWitnessIntentTest.java#jobProcessExitsCarryTheOwnersIntent"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/OwnedHelperStopRecordTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation: a helper the owner stops is still recorded once, REQUESTED with the stop reason, now that exit records are write-once. Passes on b599d4d7e as well.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "Record each helper exit once, with its final class and a non-null reason: an exit the owner awaited or stopped is REQUESTED with the owner's reason, and one nobody awaited or stopped within a 2 s grace is UNEXPECTED with a reason saying so. The Engine job witness resolves an exiting process's class and reason from the owner's published intent (owned, stop intent, outcome) and records which basis it used (intentBasis).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-stop-record.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/OwnedHelperStopRecordTest.java#aStoppedHelperIsRecordedOnceAsRequestedWithTheStopReason"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/SharedHelperWindowTest.java"
      ],
      "class": "New",
      "behaviour": "The shared helper window, on a deterministic clock: 100 consecutive leases write one start and one end after the idle period; concurrent leases share it; a lease at the end of an idle period is never outside an open window; a lease that could outlive the deadline opens a fresh window; shutdown ends an idle window at once and a leased one at its release.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "The extraction worker declares no helper window where Tesseract cannot run: markdown, text, HTML, the text fallback used when Tesseract cannot be started, constructing an extractor and the first-pass OCR configuration, none of which launches anything; the recorded Tesseract runtime's one-time availability check is a real launch and keeps its own window. OCR-capable parses share one process-wide Tesseract window, opened on demand, ended after 30 s idle and at shutdown; a lease that could outlive the window's deadline opens a fresh one. Only the window's start record is forced to disk; helper receipts written while a window is open are not forced one by one but as a group, when the window closes, at shutdown and at least once a second while any is pending. Each window names its owner process (PID and creation time) and covers only processes that owner started. A window opened in a child JVM is read from the shared children ledger by the Engine job witness, and the stop census counts an open window, or one whose end record was lost, as covering until its owner's proven exit.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-shared-window.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/SharedHelperWindowTest.java#consecutiveLeasesShareOneWindowThatEndsOnceAfterIdle",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/SharedHelperWindowTest.java#concurrentLeasesShareTheWindow",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/SharedHelperWindowTest.java#aLeaseAtTheEndOfAnIdlePeriodIsNeverOutsideAnOpenWindow",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/SharedHelperWindowTest.java#aLeaseThatCouldOutliveTheDeadlineOpensAFreshWindow",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/SharedHelperWindowTest.java#shutdownEndsAnIdleWindowNowAndALeasedOneAtItsRelease"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionHelperWindowRecordsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaProbeOwnerIntentTest.java"
      ],
      "class": "New",
      "behaviour": "Through the real extractors: 1,000 markdown and text parses write no helper-window record (b599d4d7e: 2,000 window starts); 100 consecutive OCR-capable image parses write one start and one end (b599d4d7e: 200). In a child JVM inside a real Engine job, building the first-pass OCR configuration 20 times declares no window and starts no process. This replaces TikaProbeOwnerIntentTest#firstPassConfigurationProbeHasItsOwnWindow, which required a window around that configuration; the new check shows it launches nothing, so the window it required is the per-parse record this change removes. The file's other checks are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "The extraction worker declares no helper window where Tesseract cannot run: markdown, text, HTML, the text fallback used when Tesseract cannot be started, constructing an extractor and the first-pass OCR configuration, none of which launches anything; the recorded Tesseract runtime's one-time availability check is a real launch and keeps its own window. OCR-capable parses share one process-wide Tesseract window, opened on demand, ended after 30 s idle and at shutdown; a lease that could outlive the window's deadline opens a fresh one. Only the window's start record is forced to disk; helper receipts written while a window is open are not forced one by one but as a group, when the window closes, at shutdown and at least once a second while any is pending. Each window names its owner process (PID and creation time) and covers only processes that owner started. A window opened in a child JVM is read from the shared children ledger by the Engine job witness, and the stop census counts an open window, or one whose end record was lost, as covering until its owner's proven exit.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-extraction-windows.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionHelperWindowRecordsTest.java#markdownAndTextParsesWriteNoHelperWindowRecords",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionHelperWindowRecordsTest.java#consecutiveOcrCapableParsesShareOneWindow",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaProbeOwnerIntentTest.java#firstPassConfigurationLaunchesNothingAndWritesNoWindow"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/GpuSnapshotNvidiaSmiRateTest.java"
      ],
      "class": "New",
      "behaviour": "Real nvidia-smi launches counted through the helper owner: 20 GPU snapshots with NVML answering start no nvidia-smi process (b599d4d7e: the failure lists 27 or more PIDs before truncation); without NVML, 20 snapshots by 4 readers within 5 s start at most one (b599d4d7e: 44). Skipped where nvidia-smi is not on PATH.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "Read GPU memory in process through NVML when it answers, with no nvidia-smi process. The snapshot's nvidia-smi section then carries the same driver and memory figures read from NVML, names its source ('read in-process (NVML)') and names each figure NVML did not give; a section read from nvidia-smi names 'nvidia-smi'. Without NVML, one process-wide nvidia-smi reading (one combined query for total, free and driver) answers every caller for 5 s, never two probes at once. An invalidation marks the reading stale and never shortens the 5 s interval: a read inside it gets the last reading marked stale, and the next allowed probe answers every reader waiting for it. A start failure is sticky until invalidated. nvidiaSmiAvailable on /api/inference/status reports whether the nvidia-smi tool itself is available, from its detector (its last reading, otherwise whether it is installed where a launch would find it), whatever supplied the memory figures.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-gpu-snapshot-rate.json",
      "newChecks": [
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/GpuSnapshotNvidiaSmiRateTest.java#snapshotsStartNoNvidiaSmiWhenNvmlAnswersInProcess",
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/GpuSnapshotNvidiaSmiRateTest.java#withoutNvmlConcurrentSnapshotsShareOneReadingPerFiveSeconds"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/NvidiaSmiReadingsTest.java"
      ],
      "class": "New",
      "behaviour": "The shared nvidia-smi reading, on an injected clock and runner: one probe answers every reader for 5 s; probes never overlap and waiting readers share the answer; a start failure is sticky until invalidated; partial and unparseable output is handled; a section built from a shared reading names nvidia-smi as its source and lists no missing figure.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "Read GPU memory in process through NVML when it answers, with no nvidia-smi process. The snapshot's nvidia-smi section then carries the same driver and memory figures read from NVML, names its source ('read in-process (NVML)') and names each figure NVML did not give; a section read from nvidia-smi names 'nvidia-smi'. Without NVML, one process-wide nvidia-smi reading (one combined query for total, free and driver) answers every caller for 5 s, never two probes at once. An invalidation marks the reading stale and never shortens the 5 s interval: a read inside it gets the last reading marked stale, and the next allowed probe answers every reader waiting for it. A start failure is sticky until invalidated. nvidiaSmiAvailable on /api/inference/status reports whether the nvidia-smi tool itself is available, from its detector (its last reading, otherwise whether it is installed where a launch would find it), whatever supplied the memory figures.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-nvidia-smi-readings.json",
      "newChecks": [
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/NvidiaSmiReadingsTest.java#oneProbeAnswersEveryReaderForFiveSeconds",
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/NvidiaSmiReadingsTest.java#probesNeverRunConcurrentlyAndWaitingReadersShareTheAnswer",
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/NvidiaSmiReadingsTest.java#aStartFailureIsStickyUntilInvalidated",
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/NvidiaSmiReadingsTest.java#partialAndUnparseableOutputs",
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/NvidiaSmiReadingsTest.java#snapshotSectionFromASharedReadingNamesNvidiaSmi"
      ]
    },
    {
      "items": [
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/NvmlSnapshotSectionTest.java"
      ],
      "class": "New",
      "behaviour": "With NVML reporting a GPU, the snapshot's nvidia-smi section, read by component name as the /api/gpu/capabilities serializer sees it, names its source 'read in-process (NVML)' and carries NVML's driver version, major and minor, total and free memory and the '12.0 GB' description, with no figure named missing; when NVML gives no free memory, only that figure is named. b599d4d7e: the section had no source.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "Read GPU memory in process through NVML when it answers, with no nvidia-smi process. The snapshot's nvidia-smi section then carries the same driver and memory figures read from NVML, names its source ('read in-process (NVML)') and names each figure NVML did not give; a section read from nvidia-smi names 'nvidia-smi'. Without NVML, one process-wide nvidia-smi reading (one combined query for total, free and driver) answers every caller for 5 s, never two probes at once. An invalidation marks the reading stale and never shortens the 5 s interval: a read inside it gets the last reading marked stale, and the next allowed probe answers every reader waiting for it. A start failure is sticky until invalidated. nvidiaSmiAvailable on /api/inference/status reports whether the nvidia-smi tool itself is available, from its detector (its last reading, otherwise whether it is installed where a launch would find it), whatever supplied the memory figures.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-nvml-section.json",
      "newChecks": [
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/NvmlSnapshotSectionTest.java#nvmlFiguresFillTheSectionAndNameTheirSource",
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/NvmlSnapshotSectionTest.java#aFigureNvmlDidNotGiveIsNamed"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/atrest/DiskEncryptionProbeRateTest.java"
      ],
      "class": "New",
      "behaviour": "Three readers every 2.7 s for 30 minutes probe at most three times (b599d4d7e: 334); a failed probe keeps the last value, marked stale with LOW confidence (b599d4d7e: UNKNOWN, source none); a stale ENCRYPTED value is not reported as ENCRYPTED and is visibly stale.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "Probe a volume's disk-encryption state at most once per ten minutes, shared by every reader in the process; the reading carries its age and read time, and /api/status carries the read time next to the value (atRestProtection.probedAtMs, epoch ms), both taken from one immutable reading fetched once per status build. A failed probe keeps the last value marked stale (source 'stale:<source>', LOW confidence) with its own read time, retries after the ten minutes, and a stale ENCRYPTED or ENCRYPTING value reads as UNKNOWN, never as protected.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-disk-encryption-rate.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/atrest/DiskEncryptionProbeRateTest.java#readersEveryFewSecondsProbeOncePerTenMinutes",
        "modules/app-services/src/test/java/io/justsearch/app/services/atrest/DiskEncryptionProbeRateTest.java#aFailedProbeKeepsTheLastKnownValueMarkedStale",
        "modules/app-services/src/test/java/io/justsearch/app/services/atrest/DiskEncryptionProbeRateTest.java#aStaleProtectedValueIsNeverReportedAsProtected"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/atrest/DiskEncryptionReadingTest.java"
      ],
      "class": "New",
      "behaviour": "A cached reading shows its age and the read time of the probe that gave it; a failed probe keeps the value's own age and is retried after the ten minutes, not on every read, and a later success clears the stale mark; each volume is probed on its first read.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "Probe a volume's disk-encryption state at most once per ten minutes, shared by every reader in the process; the reading carries its age and read time, and /api/status carries the read time next to the value (atRestProtection.probedAtMs, epoch ms), both taken from one immutable reading fetched once per status build. A failed probe keeps the last value marked stale (source 'stale:<source>', LOW confidence) with its own read time, retries after the ten minutes, and a stale ENCRYPTED or ENCRYPTING value reads as UNKNOWN, never as protected.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-disk-encryption-reading.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/atrest/DiskEncryptionReadingTest.java#cachedReadsShowTheirAge",
        "modules/app-services/src/test/java/io/justsearch/app/services/atrest/DiskEncryptionReadingTest.java#aFailedProbeKeepsTheValuesOwnAgeAndRetriesAfterTheTtl",
        "modules/app-services/src/test/java/io/justsearch/app/services/atrest/DiskEncryptionReadingTest.java#eachVolumeIsProbedOnItsFirstRead"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusAtRestReadTimeHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Over HTTP on the real status route with the real disk-encryption probe, /api/status carries atRestProtection.probedAtMs next to the value: an integer time within this process's lifetime and no later than the answer. b599d4d7e: no read time on the wire.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "Probe a volume's disk-encryption state at most once per ten minutes, shared by every reader in the process; the reading carries its age and read time, and /api/status carries the read time next to the value (atRestProtection.probedAtMs, epoch ms), both taken from one immutable reading fetched once per status build. A failed probe keeps the last value marked stale (source 'stale:<source>', LOW confidence) with its own read time, retries after the ten minutes, and a stale ENCRYPTED or ENCRYPTING value reads as UNKNOWN, never as protected.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-at-rest-read-time.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusAtRestReadTimeHttpTest.java#statusSaysWhenTheDiskEncryptionValueWasRead"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/dead-code-audit/src/test/java/io/justsearch/deadcode/ProcessLaunchFunnelTest.java"
      ],
      "class": "New",
      "behaviour": "Across the audited modules' production classes, every class that starts an OS process is on the allowlist and every allowlisted class still starts one (b599d4d7e: VramDetector launched nvidia-smi itself and is not on the list).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "Only an allowlist of production classes, each with a one-line reason and a launch bound, may start an OS process (ProcessBuilder.start/startPipeline, Runtime.exec, OwnedHelper.start/own, WindowsEngineJob.createChat); a new launcher fails, and an entry whose class no longer launches must be deleted.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-launch-funnel.json",
      "newChecks": [
        "modules/dead-code-audit/src/test/java/io/justsearch/deadcode/ProcessLaunchFunnelTest.java#only_allowlisted_classes_start_os_processes"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/dead-code-audit/src/test/java/io/justsearch/deadcode/ProcessLaunchFunnelTest.java"
      ],
      "class": "New",
      "behaviour": "The launch scan's self-test on fixtures: it sees ProcessBuilder.start, startPipeline, Runtime.exec, OwnedHelper.start and a launch inside a lambda, folds the lambda onto its class, and ignores a class that only builds a ProcessBuilder.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "Only an allowlist of production classes, each with a one-line reason and a launch bound, may start an OS process (ProcessBuilder.start/startPipeline, Runtime.exec, OwnedHelper.start/own, WindowsEngineJob.createChat); a new launcher fails, and an entry whose class no longer launches must be deleted.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-launch-funnel-scan.json",
      "newChecks": [
        "modules/dead-code-audit/src/test/java/io/justsearch/deadcode/ProcessLaunchFunnelTest.java#the_scan_sees_each_kind_of_launch_and_folds_nested_code_onto_its_class"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ProbeChurnPrimaryRateMeasurement.java"
      ],
      "class": "New",
      "behaviour": "Opt-in (JS_PROBE_CHURN_MEASURE=1, Windows): the real in-process indexing path inside a real Engine job with the children ledger indexes 900 generated documents per trial, alternating a ledger-on arm (off) with a ledger-discarded arm (nosink). With JS_PROBE_CHURN_ASSERT=1, every ledger-on trial's ledger writes (the Engine's helper owner records, timed as written, and every other record the trial added, the job witness's process records and the extraction child's own records, replayed forced through a writer on the same volume and timed) must take at most 2% of its indexing time and number at most 32, whatever the document count (b599d4d7e: about two forced records per document). The rate ratio and the host's CPU load are printed, not asserted. Skipped in the normal suite.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "The children ledger's cost to indexing is bounded directly: in each ledger-on trial of the real in-process indexing path all its ledger writes, the Engine's helper owner records timed as written plus every other record the trial added (the job witness's process records and the extraction child's own records) replayed forced on the same volume, take at most 2% of the trial's indexing time and number at most 32 per burst, independent of the document count (b599d4d7e forced two records per document). The ledger-on to ledger-discarded rate ratio is reported, not asserted, because single trials on a shared host vary by a factor of two.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-primary-rate.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ProbeChurnPrimaryRateMeasurement.java#primaryRateWithAndWithoutProbeChurn"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/NvidiaSmiReadingsTest.java"
      ],
      "class": "New",
      "behaviour": "On an injected clock and runner: an invalidation inside the 5 s interval starts no probe; the read gets the last reading marked stale, and the first read once the interval allows starts one probe whose fresh reading answers every reader. Six invalidated GPU snapshots without NVML within 480 ms start one nvidia-smi, the next one only 5 s later. 47981cfa6: an invalidation started a probe inside the interval.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "Read GPU memory in process through NVML when it answers, with no nvidia-smi process. The snapshot's nvidia-smi section then carries the same driver and memory figures read from NVML, names its source ('read in-process (NVML)') and names each figure NVML did not give; a section read from nvidia-smi names 'nvidia-smi'. Without NVML, one process-wide nvidia-smi reading (one combined query for total, free and driver) answers every caller for 5 s, never two probes at once. An invalidation marks the reading stale and never shortens the 5 s interval: a read inside it gets the last reading marked stale, and the next allowed probe answers every reader waiting for it. A start failure is sticky until invalidated. nvidiaSmiAvailable on /api/inference/status reports whether the nvidia-smi tool itself is available, from its detector (its last reading, otherwise whether it is installed where a launch would find it), whatever supplied the memory figures.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-nvidia-smi-stale.json",
      "newChecks": [
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/NvidiaSmiReadingsTest.java#anInvalidationInsideTheIntervalServesTheLastReadingMarkedStale",
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/NvidiaSmiReadingsTest.java#invalidatedSnapshotsWithoutNvmlStartOneProbePerInterval"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/NvidiaSmiToolAvailabilityTest.java"
      ],
      "class": "New",
      "behaviour": "Whether nvidia-smi is available is answered without starting it: without a reading, from whether the tool is installed, looked up at most once per 5 s; after a reading, from that reading; with NVML supplying the figures the tool's own answer still decides, and no nvidia-smi starts.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "Read GPU memory in process through NVML when it answers, with no nvidia-smi process. The snapshot's nvidia-smi section then carries the same driver and memory figures read from NVML, names its source ('read in-process (NVML)') and names each figure NVML did not give; a section read from nvidia-smi names 'nvidia-smi'. Without NVML, one process-wide nvidia-smi reading (one combined query for total, free and driver) answers every caller for 5 s, never two probes at once. An invalidation marks the reading stale and never shortens the 5 s interval: a read inside it gets the last reading marked stale, and the next allowed probe answers every reader waiting for it. A start failure is sticky until invalidated. nvidiaSmiAvailable on /api/inference/status reports whether the nvidia-smi tool itself is available, from its detector (its last reading, otherwise whether it is installed where a launch would find it), whatever supplied the memory figures.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-nvidia-smi-tool.json",
      "newChecks": [
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/NvidiaSmiToolAvailabilityTest.java#withoutAReadingTheInstalledToolDecidesAndNothingStarts",
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/NvidiaSmiToolAvailabilityTest.java#aTakenReadingDecides",
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/NvidiaSmiToolAvailabilityTest.java#theNvmlSectionDoesNotDecideTheToolsAvailability"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/NvidiaSmiAvailabilityHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Over HTTP on /api/inference/status with NVML supplying the GPU memory: a detector without nvidia-smi reports nvidiaSmiAvailable false. Passes on b599d4d7e; 47981cfa6 reported true from NVML's figures.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "Read GPU memory in process through NVML when it answers, with no nvidia-smi process. The snapshot's nvidia-smi section then carries the same driver and memory figures read from NVML, names its source ('read in-process (NVML)') and names each figure NVML did not give; a section read from nvidia-smi names 'nvidia-smi'. Without NVML, one process-wide nvidia-smi reading (one combined query for total, free and driver) answers every caller for 5 s, never two probes at once. An invalidation marks the reading stale and never shortens the 5 s interval: a read inside it gets the last reading marked stale, and the next allowed probe answers every reader waiting for it. A start failure is sticky until invalidated. nvidiaSmiAvailable on /api/inference/status reports whether the nvidia-smi tool itself is available, from its detector (its last reading, otherwise whether it is installed where a launch would find it), whatever supplied the memory figures.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-nvidia-smi-availability.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/NvidiaSmiAvailabilityHttpTest.java#nvmlMemoryDoesNotMakeAMissingNvidiaSmiAvailable"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/NvidiaSmiAvailabilityHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation, over HTTP on /api/inference/status with NVML supplying the GPU memory: with nvidia-smi installed on PATH and the real detector, nvidiaSmiAvailable is true. Skipped where nvidia-smi is not on PATH.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "Read GPU memory in process through NVML when it answers, with no nvidia-smi process. The snapshot's nvidia-smi section then carries the same driver and memory figures read from NVML, names its source ('read in-process (NVML)') and names each figure NVML did not give; a section read from nvidia-smi names 'nvidia-smi'. Without NVML, one process-wide nvidia-smi reading (one combined query for total, free and driver) answers every caller for 5 s, never two probes at once. An invalidation marks the reading stale and never shortens the 5 s interval: a read inside it gets the last reading marked stale, and the next allowed probe answers every reader waiting for it. A start failure is sticky until invalidated. nvidiaSmiAvailable on /api/inference/status reports whether the nvidia-smi tool itself is available, from its detector (its last reading, otherwise whether it is installed where a launch would find it), whatever supplied the memory figures.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-nvidia-smi-installed.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/NvidiaSmiAvailabilityHttpTest.java#anInstalledNvidiaSmiIsAvailableWhenNvmlSuppliesTheMemory"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusAtRestOneReadingHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Over HTTP on the real status route, with the probe's clock crossing the ten-minute expiry between two consecutive reads and the probe answering differently after it, /api/status shows the disk-encryption value and probedAtMs of the same reading. 47981cfa6: ENCRYPTED (the reading at 0 ms) with probedAtMs 600000 (the next reading's time).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "Probe a volume's disk-encryption state at most once per ten minutes, shared by every reader in the process; the reading carries its age and read time, and /api/status carries the read time next to the value (atRestProtection.probedAtMs, epoch ms), both taken from one immutable reading fetched once per status build. A failed probe keeps the last value marked stale (source 'stale:<source>', LOW confidence) with its own read time, retries after the ten minutes, and a stale ENCRYPTED or ENCRYPTING value reads as UNKNOWN, never as protected.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-at-rest-single-reading.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusAtRestOneReadingHttpTest.java#statusPairsTheDiskValueWithTheTimeOfTheSameReading"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/HelperReceiptGroupFlushTest.java"
      ],
      "class": "New",
      "behaviour": "Helper receipts of real processes started inside an open shared window are written unforced and forced as a group within a second and at the window's close. 47981cfa6: each receipt was forced.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "The extraction worker declares no helper window where Tesseract cannot run: markdown, text, HTML, the text fallback used when Tesseract cannot be started, constructing an extractor and the first-pass OCR configuration, none of which launches anything; the recorded Tesseract runtime's one-time availability check is a real launch and keeps its own window. OCR-capable parses share one process-wide Tesseract window, opened on demand, ended after 30 s idle and at shutdown; a lease that could outlive the window's deadline opens a fresh one. Only the window's start record is forced to disk; helper receipts written while a window is open are not forced one by one but as a group, when the window closes, at shutdown and at least once a second while any is pending. Each window names its owner process (PID and creation time) and covers only processes that owner started. A window opened in a child JVM is read from the shared children ledger by the Engine job witness, and the stop census counts an open window, or one whose end record was lost, as covering until its owner's proven exit.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-receipt-group-flush.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/HelperReceiptGroupFlushTest.java#receiptsInsideAWindowAreUnforcedAndForcedAsAGroup"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/HelperReceiptGroupFlushTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation: helper receipts of a real process started outside any window are each forced to disk as they are written.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "The extraction worker declares no helper window where Tesseract cannot run: markdown, text, HTML, the text fallback used when Tesseract cannot be started, constructing an extractor and the first-pass OCR configuration, none of which launches anything; the recorded Tesseract runtime's one-time availability check is a real launch and keeps its own window. OCR-capable parses share one process-wide Tesseract window, opened on demand, ended after 30 s idle and at shutdown; a lease that could outlive the window's deadline opens a fresh one. Only the window's start record is forced to disk; helper receipts written while a window is open are not forced one by one but as a group, when the window closes, at shutdown and at least once a second while any is pending. Each window names its owner process (PID and creation time) and covers only processes that owner started. A window opened in a child JVM is read from the shared children ledger by the Engine job witness, and the stop census counts an open window, or one whose end record was lost, as covering until its owner's proven exit.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-receipt-forced.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/HelperReceiptGroupFlushTest.java#receiptsOutsideAnyWindowStayForced"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/ChildWindowLedgerWitnessTest.java"
      ],
      "class": "New",
      "behaviour": "Inside a real Engine job with a real rotating children ledger, a child JVM opens a helper window and starts a real process in it; the Engine job witness reads the window from the shared ledger and records that process REQUESTED with the window's reason and the basis 'owner window'. 47981cfa6: UNEXPECTED, no recorded intent.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "The extraction worker declares no helper window where Tesseract cannot run: markdown, text, HTML, the text fallback used when Tesseract cannot be started, constructing an extractor and the first-pass OCR configuration, none of which launches anything; the recorded Tesseract runtime's one-time availability check is a real launch and keeps its own window. OCR-capable parses share one process-wide Tesseract window, opened on demand, ended after 30 s idle and at shutdown; a lease that could outlive the window's deadline opens a fresh one. Only the window's start record is forced to disk; helper receipts written while a window is open are not forced one by one but as a group, when the window closes, at shutdown and at least once a second while any is pending. Each window names its owner process (PID and creation time) and covers only processes that owner started. A window opened in a child JVM is read from the shared children ledger by the Engine job witness, and the stop census counts an open window, or one whose end record was lost, as covering until its owner's proven exit.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-child-window-ledger.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/ChildWindowLedgerWitnessTest.java#aWindowOpenedInAChildJvmReachesTheEngineWitness"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowOwnerIdentityTest.java"
      ],
      "class": "New",
      "behaviour": "Inside a real Engine job, a process started by a child JVM that opened no window, while the Engine holds an open window for the same image, is recorded UNEXPECTED: a window covers only processes its owner started. Passes on b599d4d7e; 47981cfa6 recorded it REQUESTED under the Engine's window.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "The extraction worker declares no helper window where Tesseract cannot run: markdown, text, HTML, the text fallback used when Tesseract cannot be started, constructing an extractor and the first-pass OCR configuration, none of which launches anything; the recorded Tesseract runtime's one-time availability check is a real launch and keeps its own window. OCR-capable parses share one process-wide Tesseract window, opened on demand, ended after 30 s idle and at shutdown; a lease that could outlive the window's deadline opens a fresh one. Only the window's start record is forced to disk; helper receipts written while a window is open are not forced one by one but as a group, when the window closes, at shutdown and at least once a second while any is pending. Each window names its owner process (PID and creation time) and covers only processes that owner started. A window opened in a child JVM is read from the shared children ledger by the Engine job witness, and the stop census counts an open window, or one whose end record was lost, as covering until its owner's proven exit.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-window-owner.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowOwnerIdentityTest.java#aChildWithoutAWindowCannotBorrowTheEngineWindow"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionFallbackWindowTest.java"
      ],
      "class": "New",
      "behaviour": "Through the real extractors, text parsed through the fallback used when Tesseract cannot be started writes no helper-window record, and constructing the extractors once the Tesseract runtime is recorded writes none. 47981cfa6: a window start and end for each.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "The extraction worker declares no helper window where Tesseract cannot run: markdown, text, HTML, the text fallback used when Tesseract cannot be started, constructing an extractor and the first-pass OCR configuration, none of which launches anything; the recorded Tesseract runtime's one-time availability check is a real launch and keeps its own window. OCR-capable parses share one process-wide Tesseract window, opened on demand, ended after 30 s idle and at shutdown; a lease that could outlive the window's deadline opens a fresh one. Only the window's start record is forced to disk; helper receipts written while a window is open are not forced one by one but as a group, when the window closes, at shutdown and at least once a second while any is pending. Each window names its owner process (PID and creation time) and covers only processes that owner started. A window opened in a child JVM is read from the shared children ledger by the Engine job witness, and the stop census counts an open window, or one whose end record was lost, as covering until its owner's proven exit.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-fallback-windows.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionFallbackWindowTest.java#textParsedThroughTheFallbackWritesNoWindow",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionFallbackWindowTest.java#constructingExtractorsWritesNoWindow"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OcrReceiptForcingTest.java"
      ],
      "class": "New",
      "behaviour": "Three real Tesseract page OCRs through the real extractor: of the ledger records written, only the window start is forced one by one; the helper receipts are forced as a group after the last record. 47981cfa6: every receipt was forced. Needs a real Tesseract.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "The extraction worker declares no helper window where Tesseract cannot run: markdown, text, HTML, the text fallback used when Tesseract cannot be started, constructing an extractor and the first-pass OCR configuration, none of which launches anything; the recorded Tesseract runtime's one-time availability check is a real launch and keeps its own window. OCR-capable parses share one process-wide Tesseract window, opened on demand, ended after 30 s idle and at shutdown; a lease that could outlive the window's deadline opens a fresh one. Only the window's start record is forced to disk; helper receipts written while a window is open are not forced one by one but as a group, when the window closes, at shutdown and at least once a second while any is pending. Each window names its owner process (PID and creation time) and covers only processes that owner started. A window opened in a child JVM is read from the shared children ledger by the Engine job witness, and the stop census counts an open window, or one whose end record was lost, as covering until its owner's proven exit.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-ocr-receipt-forcing.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OcrReceiptForcingTest.java#threeRealOcrsForceOnlyTheWindowStart"
      ],
      "repaired": true
    }
  ]
}
```
