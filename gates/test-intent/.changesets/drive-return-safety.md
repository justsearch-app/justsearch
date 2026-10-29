---
schema: test-intent.v1
task: drive-return-safety
author-role: builder
author-session: codex-drive-return-builder-20261029
---

# Disposable drive-test ownership and descriptor baseline

This change preserves the class's product assertions, per-cycle descriptor ceiling, scenario
inputs, time budgets and timeouts. It changes test selection and the ownership/lifetime of the
loop-image infrastructure. Only the descriptor campaign uses a short telemetry interval and
waits for its own retained RRD descriptor before taking the unchanged baseline.

Native verification is pending. The untracked `tmp/drive-return-host-check.sh` records S1 default
exclusion/no udisks invocation, S2 all 19 invocations without skips, S3 SIGTERM cleanup including
the decoy, S4 SIGKILL recovery and preservation of an unrelated decoy, and S5 lazy-open fail-before
and one-leaked-descriptor-per-cycle mutation failures. It writes logs, XML and loop/mount snapshots
to `tmp/drive-return-host-evidence/`. The before-state mutation forces the original oracle's first
real telemetry flush into a return cycle rather than relying on wall-clock timing at 60 seconds.
No real-drive execution or mutation result is claimed from the builder sandbox.

```json
{
  "entries": [
    {
      "items": [
        "build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt",
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/LinuxLoopVolume.java",
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/LinuxWatchedDriveReturnIntegrationTest.java"
      ],
      "class": "Incidental",
      "assessment": {
        "purpose": "Keep the real drive-return product oracle intact while making mounts an explicit opt-in, reclaiming only owned test associations after interruption, and observing telemetry's one-time retained RRD open before the descriptor baseline.",
        "callersAndStateOwners": "JvmBaseConventionsPlugin configures every Test task including UI integrationTest. The tagged class creates LinuxLoopVolume resources. Each JVM owns an exclusive image-directory lock, its volume and decoy images, its tracked loops and a bounded shutdown hook. Udisks owns the loop and mount associations. An earlier JVM's loops can be reclaimed only under the exclusive run lock after path, backing-file ownership and SetupByUID checks.",
        "indirectBehaviours": "Ordinary check/integrationTest tasks exclude real-drives and log -PincludeRealDrives=true. Opt-in retains every invocation. SIGTERM cleanup retains images on failure; SIGKILL recovery inspects only distinctive image names in this checkout's owned dedicated directory. Unrelated loops are preserved. The short telemetry interval is scoped to construction of the descriptor fixture and the prior system property is restored. Descriptor and watcher-thread inequalities are unchanged; all other fixtures, assertions and budgets are unchanged.",
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
