---
schema: test-intent.v1
task: drive-return-safety
author-role: builder
author-session: codex-drive-return-safety-scope-reduction-20261030
---

# Disposable drive-test ownership and descriptor baseline

Scope reduction removes cross-run leftover recovery after three repair rounds. Startup sweeps,
orphan-image recovery, process-owner liveness checks and global sysfs scans are gone. Cleanup
uses only device names recorded when this JVM attached its own images, with sysfs backing-file
and creation-identity checks. Ordinary test exclusion, opt-in, telemetry warm-up, fixture interval,
product assertions and test budgets remain unchanged.

Volume cleanup and every attachment have atomic ownership claims. A completed attachment stays
claimed even if its device number is reused. Image deletion has its own atomic claim to avoid
concurrent unlink attempts when shutdown overlaps an ordinary away/release. Individual cleanup
failures are logged without preventing the other owned device from being attempted. Images use
private 0700 directories created for each volume beneath the dedicated image directory; matching
creation identities authorize unlink, and empty private directories are removed. Same-user
replacement inside such a directory has the same trust level as JUnit @TempDir cleanup and is
explicitly outside the threat model. Failed or in-flight devices retain their images.

The bounded, monitor-free shutdown command cancellation remains. SIGKILL recovery is now manual:
CONTRIBUTING.md describes listing and detaching loops and removing retained image files; reboot
removes devices and mounts, while disk image files remain until deleted. No later JVM touches
another run’s leftovers. The untracked tmp/drive-return-host-check.sh S4 now requires the next
opted-in run to pass while preserving the killed run’s associations, backing files and its own
outside-pattern decoy. The script then removes its recorded leftovers and decoy itself. Other
stages and initial-leftover/UID/association safeguards remain.

The nine device-free safety tests passed with no skips, along with integration-test compilation,
Spotless and PMD in tmp/drive-return-scope-gradle.log; the final repeat is recorded in
tmp/drive-return-scope-gradle-final.log. A separate, identical ownership probe
runs unchanged against the shared before clone at 0137bfb250 and this candidate. It interleaves
shutdown cleanup inside ordinary teardown's loop-delete response with all native process
construction intercepted. Before: it observes two loop-delete calls and fails the single-owner
assertion; after: it observes one and passes. Sources and evidence are in
tmp/drive-return-ownership-probe/, tmp/drive-return-scope-probe-before.log/xml and
tmp/drive-return-scope-probe-after.log/xml. Each checkout uses its own build directory.
The probe needed an init-script source-set registration correction and a fixture-path correction
before the intended interleaving executed; no helper logic was changed in the before clone.
Script syntax is
checked with bash -n and its embedded Python is parsed without executing it. Native S1-S5
remain pending: no udisksctl, losetup, mount, drive class or developer apps were run here.
Shared resources touched: prescribed Gradle slot/daemon and dependency caches. No
.agents/preflight exists. The test-intent gate against e580368790 is recorded in
tmp/drive-return-scope-gate.log; the prior acceptance record below is intentionally unchanged.

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
        "purpose": "Keep the real drive-return product oracle intact while making mounts an explicit opt-in, cleaning only attachments recorded by this JVM, and observing telemetry’s one-time retained RRD open before the descriptor baseline.",
        "callersAndStateOwners": "JvmBaseConventionsPlugin configures every Test task including UI integrationTest. The tagged class creates LinuxLoopVolume resources. Each volume has a private 0700 directory created by its JVM beneath build/drive-return-images, creation device/inode identities in image names, and recorded loop-device attachments. Udisks owns loop and mount associations. Volume, device and image cleanup have atomic ownership claims. No startup recovery, orphan recovery, owner-liveness checks or global device scans remain.",
        "indirectBehaviours": "Ordinary Test tasks exclude real-drives; integrationTest tasks log -PincludeRealDrives=true. Opt-in retains every invocation. Normal teardown and SIGTERM use one cleanup owner per volume and per recorded attachment, check its sysfs backing_file and creation identity, and never detach a claimed attachment again. Individual failures are logged and the other owned device is still attempted. Failed or in-flight attachments retain their images. Images are deleted only by matching recorded identity inside this JVM-created private directory; same-user replacement inside this 0700 directory is outside the threat model, as with JUnit @TempDir cleanup. Empty private directories are removed. Shutdown retains tracked command cancellation and no volume/class monitors, with five seconds per cleanup command inside the twenty-second hook budget; normal commands retain thirty seconds. SIGKILL leftovers remain untouched by later runs and require reboot (for devices and mounts) or manual cleanup; disk image files require manual removal. CONTRIBUTING.md documents listing and removal. The descriptor fixture’s short interval, telemetry warm-up, all assertions, inequalities and other test budgets are unchanged.",
        "evidence": "Device-free LinuxLoopVolumeSafetyTest covers recorded associations and identity, teardown-then-hook, hook-during-teardown, hook-during-away, per-device failure continuation, private directory mode and isolation, replacement preservation, and bounded command cancellation. Required Gradle checks and test-intent gate are recorded in tmp/drive-return-scope-gradle.log and tmp/drive-return-scope-gate.log. Native execution is not authorized in the sandbox and remains pending.",
        "unresolvedRisks": "Bounded cleanup cannot guarantee detachment if udisks refuses or stalls. There is deliberately no cross-run recovery. A SIGKILLed run can leave attachments, images and directories; later runs preserve them. Device-free tests do not establish real udisks behavior. The host script is prepared for S1-S5 but has not been executed here. The prior acceptance record is deliberately retained and stale; independent verification must write a new record."
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
