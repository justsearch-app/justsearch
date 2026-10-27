---
schema: test-intent.v1
task: t-20261027-4da1d8
author-role: builder
author-session: 01a17c67-4997-7ad2-b841-8e7b5b5c67d3
---

# Retry durable-file open denial on every platform within the existing read budget

Open denial retries on every platform, matching AtomicFileWrites and handling Windows open
denial during atomic replacement. Remaining test methods and their selection remain unchanged.
The new checks listed below execute on Linux; the permission fixture assumes the process
does not bypass file permissions. The orchestrator supplies script-generated evidence and
the independent acceptance record.

The real Windows failure was an AccessDeniedException opening the file during
AtomicFileWrites' atomic replacement, seen once in
concurrentAtomicReplacementsExposeOnlyWholeVersionsAndPropagateWriterFailure on Windows CI.
It has no deterministic real-file reproduction, because on current Windows a delete with
an open handle removes the name at once. The deterministic tests simulate the denial
through a test filesystem provider, and the existing concurrent-replacement test keeps
running on Windows CI as the real-system check.

```json
{
  "entries": [
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/ContendedFileReadsTest.java"
      ],
      "class": "New",
      "behaviour": "On every platform, open denial followed by missing then success reopens and reads the whole replacement through the same shared-locked channel. Mixed denial, lock and missing attempts share one budget, whose last failure selects the timeout exception. Interruption retains the reader interrupt flag.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18",
          "delegationScope": "Repair durable-file open denial on every platform, matching AtomicFileWrites, while preserving callers, atomic writes, saved formats and all other retry boundaries.",
          "choice": "Exercise retry transitions, channel ownership, shared budget and cancellation through the existing Path and Duration entry point using a test-only FileSystemProvider. Run all four checks on every OS; each must fail before the repair with an assertion failure.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/windows-contended-read.json",
      "newChecks": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/ContendedFileReadsTest.java#denialThenMissingReopensAndReadsOneLockedChannel",
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/ContendedFileReadsTest.java#mixedRetriesReportTheLastFailedAttempt",
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/ContendedFileReadsTest.java#denialAndLockContentionShareOneBudget",
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/ContendedFileReadsTest.java#interruptedDenialRetainsTheReadersInterruptFlag"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/ContendedFileReadsTest.java"
      ],
      "class": "New",
      "behaviour": "On non-Windows systems, a real zero-permission file retries open denial until the short budget is spent, then throws the original last AccessDeniedException for that path. Skip only if the process bypasses file permissions; restore permissions in finally.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18",
          "delegationScope": "Repair durable-file open denial on every platform, matching AtomicFileWrites, while preserving callers, atomic writes, saved formats and all other retry boundaries.",
          "choice": "Probe permission enforcement on an owned temporary file, then assert original last denial identity, its path, repeated opens and elapsed budget. This pins the all-platform rule that matches AtomicFileWrites and handles Windows open denial during atomic replacement.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/windows-contended-read.json",
      "newChecks": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/ContendedFileReadsTest.java#permissionDenialRetriesUntilBudgetIsSpent"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/ContendedFileReadsTest.java"
      ],
      "class": "New",
      "behaviour": "On every platform, disappearance after lock contention consumes the existing budget and retains the last NoSuchFileException. Other filesystem open failures and failures from channel locking, reading and closing propagate immediately, including AccessDeniedException at those later boundaries.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18",
          "delegationScope": "Repair durable-file open denial on every platform, matching AtomicFileWrites, while preserving callers, atomic writes, saved formats and all other retry boundaries.",
          "choice": "Keep the three all-OS preservation checks and every pre-existing test unchanged. Check exception identity and a single attempt for failures outside the open-only repair.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/windows-contended-read.json",
      "newChecks": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/ContendedFileReadsTest.java#disappearanceAfterLockContentionTimesOutAsMissing",
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/ContendedFileReadsTest.java#otherOpenFailuresPropagateWithoutRetry",
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/ContendedFileReadsTest.java#channelLockReadAndCloseFailuresPropagateWithoutRetry"
      ]
    },
    {
      "items": [
        "scripts/ci/unit-test-shard-policy.v1.json"
      ],
      "class": "Structural rule kept",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18",
          "delegationScope": "Repair durable-file open denial on every platform, matching AtomicFileWrites, while preserving callers, atomic writes, saved formats and all other retry boundaries.",
          "choice": "List permissionDenialRetriesUntilBudgetIsSpent() as the one additional allowed Windows skip of ContendedFileReadsTest: it is @DisabledOnOs(OS.WINDOWS) because its fixture removes POSIX permissions, and Gradle records an empty <skipped/> for it on Windows (run 40485394296).",
          "label": "agent choice"
        }
      ],
      "rule": "The Windows contracts lane requires ContendedFileReadsTest to execute and rejects every skip of that class that the policy does not name.",
      "whySurvives": "Only one exact test name is added to that class's allowedSkips; matching stays by exact name, so any other skipped case is still rejected, and the class still fails when no case executes. No lane, task selection, timeout or other class changes."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a17ccf-d705-7281-83f2-8017b86a682a",
  "verdict": "accept",
  "digest": "sha256:6262ec5d6065eabb57530e8ca9895fe632c94f40db920f6cc962a83e80f05307"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a17d31-3548-7b40-bcc4-131df45c7145",
  "verdict": "accept",
  "digest": "sha256:21820c1307403cf824e4b43d86c20f1bc04d589d433a22701140b71981ea2161"
}
```
