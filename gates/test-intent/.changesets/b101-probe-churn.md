---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b101-probe-churn
---

# Named limits

- `ProbeChurnPrimaryRateMeasurement` is opt-in. It needs a Windows host, about ten minutes, and `JS_PROBE_CHURN_MEASURE=1`; without that variable it is skipped, so the normal suite never runs it. Check (c) is `JS_PROBE_CHURN_MEASURE=1 JS_PROBE_CHURN_ARMS=off,nosink JS_PROBE_CHURN_ASSERT=1 JS_PROBE_CHURN_DOCS=900 JS_PROBE_CHURN_ROUNDS=4`. It asserts the ledger's cost directly (write time at most 2% of indexing time, at most 32 helper records per burst). The off/nosink rate ratio is printed with the host's CPU load and is not asserted: on a shared host single trials vary by a factor of two, and indexing speed is re-measured on a quiet host after the merge.
- `GpuSnapshotNvidiaSmiRateTest` and the Windows process checks run real processes. The GPU rate check is skipped where nvidia-smi is not on PATH.
- On a host where NVML reports a GPU, the nvidia-smi section (and so `nvidiaSmiAvailable` on `/api/inference/status`) is filled from NVML with source `read in-process (NVML)`, also when nvidia-smi itself is not installed.
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
          "choice": "The extraction worker declares no helper window where Tesseract cannot run (markdown, text, HTML and the first-pass OCR configuration, which launches nothing). OCR-capable parses share one process-wide Tesseract window, opened on demand, ended after 30 s idle and at shutdown; a lease that could outlive the window's deadline opens a fresh one. Only the window's start record is forced to disk. Attribution of Tesseract processes to their owner is kept.",
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
          "choice": "The extraction worker declares no helper window where Tesseract cannot run (markdown, text, HTML and the first-pass OCR configuration, which launches nothing). OCR-capable parses share one process-wide Tesseract window, opened on demand, ended after 30 s idle and at shutdown; a lease that could outlive the window's deadline opens a fresh one. Only the window's start record is forced to disk. Attribution of Tesseract processes to their owner is kept.",
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
          "choice": "Read GPU memory in process through NVML when it answers, with no nvidia-smi process. The snapshot's nvidia-smi section then carries the same driver and memory figures read from NVML, names its source ('read in-process (NVML)') and names each figure NVML did not give; a section read from nvidia-smi names 'nvidia-smi'. Without NVML, one process-wide nvidia-smi reading (one combined query for total, free and driver) answers every caller for 5 s, never two probes at once; a forced re-read is shared, and a start failure is sticky until invalidated.",
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
      "behaviour": "The shared nvidia-smi reading, on an injected clock and runner: one probe answers every reader for 5 s; probes never overlap and waiting readers share the answer; a forced reading inside the interval is shared; a start failure is sticky until invalidated; partial and unparseable output is handled; a section built from a shared reading names nvidia-smi as its source and lists no missing figure.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "Read GPU memory in process through NVML when it answers, with no nvidia-smi process. The snapshot's nvidia-smi section then carries the same driver and memory figures read from NVML, names its source ('read in-process (NVML)') and names each figure NVML did not give; a section read from nvidia-smi names 'nvidia-smi'. Without NVML, one process-wide nvidia-smi reading (one combined query for total, free and driver) answers every caller for 5 s, never two probes at once; a forced re-read is shared, and a start failure is sticky until invalidated.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-nvidia-smi-readings.json",
      "newChecks": [
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/NvidiaSmiReadingsTest.java#oneProbeAnswersEveryReaderForFiveSeconds",
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/NvidiaSmiReadingsTest.java#probesNeverRunConcurrentlyAndWaitingReadersShareTheAnswer",
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/NvidiaSmiReadingsTest.java#aForcedReadingHappensWithinTheIntervalAndIsShared",
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
          "choice": "Read GPU memory in process through NVML when it answers, with no nvidia-smi process. The snapshot's nvidia-smi section then carries the same driver and memory figures read from NVML, names its source ('read in-process (NVML)') and names each figure NVML did not give; a section read from nvidia-smi names 'nvidia-smi'. Without NVML, one process-wide nvidia-smi reading (one combined query for total, free and driver) answers every caller for 5 s, never two probes at once; a forced re-read is shared, and a start failure is sticky until invalidated.",
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
          "choice": "Probe a volume's disk-encryption state at most once per ten minutes, shared by every reader in the process; the reading carries its age and read time, and /api/status carries the read time next to the value (atRestProtection.probedAtMs, epoch ms). A failed probe keeps the last value marked stale (source 'stale:<source>', LOW confidence) with its own read time, retries after the ten minutes, and a stale ENCRYPTED or ENCRYPTING value reads as UNKNOWN, never as protected.",
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
          "choice": "Probe a volume's disk-encryption state at most once per ten minutes, shared by every reader in the process; the reading carries its age and read time, and /api/status carries the read time next to the value (atRestProtection.probedAtMs, epoch ms). A failed probe keeps the last value marked stale (source 'stale:<source>', LOW confidence) with its own read time, retries after the ten minutes, and a stale ENCRYPTED or ENCRYPTING value reads as UNKNOWN, never as protected.",
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
          "choice": "Probe a volume's disk-encryption state at most once per ten minutes, shared by every reader in the process; the reading carries its age and read time, and /api/status carries the read time next to the value (atRestProtection.probedAtMs, epoch ms). A failed probe keeps the last value marked stale (source 'stale:<source>', LOW confidence) with its own read time, retries after the ten minutes, and a stale ENCRYPTED or ENCRYPTING value reads as UNKNOWN, never as protected.",
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
      "behaviour": "Opt-in (JS_PROBE_CHURN_MEASURE=1, Windows): the real in-process indexing path inside a real Engine job with the children ledger indexes 900 generated documents per trial, alternating a ledger-on arm (off) with a ledger-discarded arm (nosink). With JS_PROBE_CHURN_ASSERT=1, every ledger-on trial's helper ledger writes must take at most 2% of its indexing time and number at most 32, whatever the document count (b599d4d7e: about two forced records per document). The rate ratio and the host's CPU load are printed, not asserted. Skipped in the normal suite.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Items 5 (GPU co-residence) and 6 (process ownership and exit accounting).",
          "choice": "The children ledger's cost to indexing is bounded directly: in each ledger-on trial of the real in-process indexing path the helper owner's ledger writes take at most 2% of the trial's indexing time and number at most 32 per burst, independent of the document count (b599d4d7e forced two records per document). The ledger-on to ledger-discarded rate ratio is reported, not asserted, because single trials on a shared host vary by a factor of two.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b101-primary-rate.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ProbeChurnPrimaryRateMeasurement.java#primaryRateWithAndWithoutProbeChurn"
      ],
      "repaired": true
    }
  ]
}
```
