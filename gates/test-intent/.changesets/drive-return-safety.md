---
schema: test-intent.v1
task: drive-return-safety
author-role: builder
author-session: codex-drive-return-minimal-20261030
---

# Drive-test opt-in and descriptor baseline

Restore LinuxLoopVolume exactly to e580368790 and delete LinuxLoopVolumeSafetyTest.
All added cleanup machinery is removed, including shutdown cleanup, command cancellation,
attachment and image identity tracking, private image directories and cleanup ownership claims.
The real-drives tag, Gradle opt-in and log line, telemetry warm-up and fixture interval remain.

Settled scenarios:

- S1: ordinary tests exclude real-drives; -PincludeRealDrives=true explicitly enables them.
- S2: an opted-in run passes and leaves no test loop devices or mounts after normal teardown.
- S3: removed; interrupted-run cleanup is no longer promised or tested.
- S4: killed or interrupted runs can leave loop devices and /run/media mounts; the documented
  commands identify and remove them. A subsequent opted-in run preserves all such leftovers
  and the host script's outside-pattern decoy. The script removes only resources it created.
- S5: warm telemetry's retained store before counting descriptors; after each cycle, allow up to
  five seconds for transient background opens to close, retaining the original baseline and
  reporting added targets from the final observation. Retained per-cycle growth still fails.
- S6: preserve the other cases' product assertions, comparisons, budgets and timeouts.

The required Gradle checks passed in tmp/drive-return-minimal-gradle.log. Static preservation,
shell/Python syntax and isolated image-matcher checks passed in
tmp/drive-return-minimal-static.log. The old matcher rejects main's image paths; the updated
matcher accepts the test's paths and excludes unrelated paths. No .agents/preflight exists.
Shared resources touched: the prescribed Gradle slot/daemon and dependency caches.
No native drive operation is authorized in this sandbox. S1/S2/S4/S5 host execution remains
unverified; the untracked host script is prepared for those scenarios. S6 requires independent
review. The previous acceptance records are intentionally unchanged and stale; the gate result
is recorded in tmp/drive-return-minimal-gate.log.

S5 follow-up: `tmp/drive-return-settle-gradle.log` records successful integration-test
compilation, Spotless and PMD. `tmp/drive-return-settle-static.log` records isolated execution
of the exact before/after descriptor oracle against temporary and retained opens: the former
fails before and passes after; the latter fails in both versions with its target reported.
Both S5 host-script mutations apply unchanged. This is a narrower check; the native test was
not run in the sandbox. `tmp/drive-return-settle-gate.log` reports stale acceptance, left for
independent verification. CONTRIBUTING.md's temp path was checked against the developer
machine's `~/.gradle/init.d/50-agent-tmp.gradle`.

```json
{
  "entries": [
    {
      "items": [
        "build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt",
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/LinuxWatchedDriveReturnIntegrationTest.java",
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java"
      ],
      "class": "Incidental",
      "assessment": {
        "purpose": "Keep the real drive-return product oracle intact while making mounts an explicit opt-in and observing telemetry’s one-time retained RRD open before the descriptor baseline.",
        "callersAndStateOwners": "JvmBaseConventionsPlugin configures every Test task including UI integrationTest. The real-drives tagged class creates LinuxLoopVolume resources. LinuxLoopVolume is restored unchanged from e580368790: volume.img and decoy.img live in loop, original-loop or foreign-loop beneath JUnit @TempDir (java.io.tmpdir/junit-*). Udisks owns loop and mount associations. Normal close() releases only the loop names recorded by that volume; JUnit owns temporary-file cleanup.",
        "indirectBehaviours": "S1: ordinary Test tasks exclude real-drives; integrationTest logs the -PincludeRealDrives=true opt-in. S2: an opted-in run retains all invocations and normal teardown leaves no test loops or mounts. S3 is removed: there is no shutdown-cleanup guarantee. S4: killed or interrupted runs, including Ctrl-C/SIGTERM and SIGKILL, can leave loop devices and /run/media mounts. CONTRIBUTING.md documents listing, identifying and manually removing them; devices and mounts vanish at reboot. The next opted-in run never sweeps or cleans another run’s leftovers. S5: the descriptor fixture’s short telemetry interval and warm-up precede the baseline. Each cycle polls the descriptor count every 25 ms for up to five seconds, requiring it to return to or below that unchanged baseline; persistent excess fails with the added targets from the final observation. No targets are excluded and no descriptor allowance is added. S6: all other product assertions, inequalities, budgets and timeouts remain unchanged.",
        "evidence": "Static comparison checks LinuxLoopVolume against e580368790 and preserves S1/S5 source changes against the prior candidate. Required Gradle checks are logged in tmp/drive-return-minimal-gradle.log; script syntax and its embedded Python are checked without executing host operations. The untracked tmp/drive-return-host-check.sh covers S1, S2, S4 and S5: it isolates JUnit images under an invocation-specific temporary root, preserves SIGKILL leftovers and an outside-pattern decoy during the next run, then removes its own recorded resources. Native execution is prohibited in the sandbox and remains pending; S6 requires independent review.",
        "unresolvedRisks": "Interrupted opted-in runs can leave loop devices, mounts and temporary images. There is no shutdown hook or cross-run recovery. The host script has not been executed here, so real udisks behavior remains unverified. The acceptance records below are deliberately retained and stale; independent verification must write a new record."
      },
      "sources": [
        {
          "kind": "owner-words",
          "quote": "LinuxLoopVolume returns to main's behaviour (cleanup in close() only, as on e580368790); a killed opted-in run (SIGTERM or SIGKILL) can leave loop devices and mounts, which is documented.",
          "location": "task drive-return-safety request"
        },
        {
          "kind": "owner-words",
          "quote": "without loosening the comparison: per-cycle growth must still fail.",
          "location": "task drive-return-safety request"
        },
        {
          "kind": "owner-words",
          "quote": "Other cases in the class are unchanged in assertions, budgets and timeouts.",
          "location": "task drive-return-safety request"
        }
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-da2f9a-codex-1",
  "verdict": "accept",
  "digest": "sha256:80eef422333e803b711b231c1c4cac7be1643b3e60d79c4e8195053c188344af"
}
```
