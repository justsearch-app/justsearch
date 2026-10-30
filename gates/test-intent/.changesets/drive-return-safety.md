---
schema: test-intent.v1
task: drive-return-safety
author-role: builder
author-session: codex-drive-return-builder-20261029
---

# Disposable drive-test ownership and descriptor baseline

This change preserves the class's product assertions, per-cycle descriptor ceiling, scenario
inputs and test time budgets. Ordinary external operations retain their 30-second timeout;
only commands during JVM shutdown use 5 seconds within the hook's 20-second bound.
It changes test selection and the ownership/lifetime of the
loop-image infrastructure. Only the descriptor campaign uses a short telemetry interval and
waits for its own retained RRD descriptor before taking the unchanged baseline.

Native verification is pending. The untracked `tmp/drive-return-host-check.sh` records S1 default
exclusion/no udisks invocation, S2 all 19 invocations without skips, S3 SIGTERM cleanup including
the decoy, S4 SIGKILL recovery and preservation of an unrelated decoy, and S5 lazy-open fail-before
and one-leaked-descriptor-per-cycle mutation failures. It writes logs, XML and loop/mount snapshots
to `tmp/drive-return-host-evidence/`. The before-state mutation forces the original oracle's first
real telemetry flush into a return cycle rather than relying on wall-clock timing at 60 seconds.
No real-drive execution or mutation result is claimed from the builder sandbox.

Round 2 corrects the telemetry configuration source: the shared fixture starts with a bare
`ResolvedConfig.builder()`, which reads only contributed entries, not JVM properties. The old
`descriptorFixture()` property override therefore never reached `buildTelemetry()` and the
resolved interval stayed at 60 seconds. The fixture now accepts an explicit interval and contributes
it before building the immutable configuration; only the descriptor fixture passes 1 second.
The RRD is initialized by the first nonempty export and retained until store close, and the
existing descriptor-open wait still guards the unchanged baseline. No system property is mutated.
The host script's lazy-open mutation now uses the same constructor parameter to select 300 seconds.
The opt-in lifecycle message is limited to tasks named `integrationTest`; tag exclusion still
applies to every `Test` task.

Mount-free builder probe: `tmp/drive-return-round2-probe/DriveReturnTelemetryProbe.java`, output
`tmp/drive-return-round2-telemetry.log`, passed. It sets the JVM override to 1000, observes a bare
builder resolving 60000, contributes the fixture default and observes 1000. A fresh RrdMetricStore
has no descriptor before initialization, retains one after initialization returns, and releases it
on close. This checks configuration resolution and actual RRD descriptor lifetime without mounts;
it does not replace the pending native class run. Both host-script mutation transformations also
applied successfully to their exact source inputs without executing drive commands.

Mount-free command-timeout probe: `tmp/drive-return-round2-command-timeouts.log`. The same 6-second
sleep fails on the shared before checkout at the 5-second command timeout and passes on the
candidate; shutdown still times out at approximately 5 seconds. Each checkout's helper was
compiled into its own workspace probe build directory. Required Gradle checks passed:
`:modules:ui:compileIntegrationTestJava`, `:modules:ui:spotlessCheck`,
`:modules:ui:pmdIntegrationTest`, and `:build-logic:check` (including `validatePlugins`);
`tmp/drive-return-round2-gradle.log` records the run. No `.agents/preflight` exists in this tree.
The test-intent gate against `origin/main` remains blocked by unrelated uncovered changes from
the advanced base and this changeset's pending independent acceptance; all four candidate items
are covered. Builder evidence is not an acceptance record.

```json
{
  "entries": [
    {
      "items": [
        "build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt",
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/LinuxLoopVolume.java",
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/LinuxWatchedDriveReturnIntegrationTest.java",
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java"
      ],
      "class": "Incidental",
      "assessment": {
        "purpose": "Keep the real drive-return product oracle intact while making mounts an explicit opt-in, reclaiming only owned test associations after interruption, and observing telemetry's one-time retained RRD open before the descriptor baseline.",
        "callersAndStateOwners": "JvmBaseConventionsPlugin configures every Test task including UI integrationTest. The tagged class creates LinuxLoopVolume resources. Each JVM owns an exclusive image-directory lock, its volume and decoy images, its tracked loops and a bounded shutdown hook. Udisks owns the loop and mount associations. An earlier JVM's loops can be reclaimed only under the exclusive run lock after path, backing-file ownership and SetupByUID checks.",
        "indirectBehaviours": "Ordinary Test tasks exclude real-drives; integrationTest tasks log -PincludeRealDrives=true. Opt-in retains every invocation. SIGTERM cleanup retains images on failure; SIGKILL recovery inspects only distinctive image names in this checkout's owned dedicated directory. Unrelated loops are preserved. The descriptor fixture contributes its short telemetry interval before configuration build, without mutating system properties; all other fixtures keep 60 seconds. External commands have 30 seconds normally and 5 seconds during shutdown within the hook's 20-second bound. Descriptor and watcher-thread inequalities are unchanged; all other assertions and test budgets are unchanged.",
        "evidence": "Native evidence must be produced by tmp/drive-return-host-check.sh: s1-default.log and the absent s1-udisks-invocations.txt; s2-opted-xml (19 passed, zero skipped); s3-after loop/mount inventory; s4-leftovers and s4-after inventories plus s4-recovery-xml and outside-pattern decoy preservation; s5-lazy-xml with the original retained-handles assertion and metrics.rrd, and s5-leak-xml with the repaired retained-handles assertion. These checks fail on invocation, leftovers, unrelated association removal, skipped cases, unexpected mutation success or a different failure. Not run inside the sandbox because its udisks caller identity is wrong.",
        "unresolvedRisks": "Host evidence for S1-S5 and independent acceptance remain pending. Shutdown cannot guarantee physical detachment when udisks refuses or stalls; the hook is bounded to 20 seconds and retains identifiable backing images so the next opted-in run can recover. The sweep is local to this checkout's image directory and does not adopt old generic JUnit volume.img files."
      },
      "sources": [
        {
          "kind": "owner-words",
          "quote": "an ordinary test run must not mount drives in the developer's desktop session, and a run that ends any way must not leave loop devices or mounts behind; a check fails only for a real defect.",
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
