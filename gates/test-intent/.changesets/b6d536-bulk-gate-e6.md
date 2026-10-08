---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, item 5, Spec E step E6, on b6d536/bulk-gate-2 (base 79b5e2e25, the E4
commit). Device pinning (section 5). GpuDriverApiProbe reads each CUDA ordinal's LUID, UUID, PCI
bus id and total memory, and says how it ended (OK, NO_DEVICE with the driver's code, FAILED).
DeviceFactsSource (app-services) is the process's device facts: the probe plus DXGI's adapter list,
probed once. DevicePinning chooses the device: among DXGI's hardware NVIDIA adapters that a CUDA
device maps to by LUID, the largest dedicated memory, ties in DXGI order; with no such adapter one
CUDA device is ordinal 0's device and several are not pinned (WARN). The pinned device is used in
four places: (1) its CUDA ordinal is the auto-detected value (ordinal 150) of the six per-role ORT
device-id keys, so a role's explicit id keeps its value; (2) the Engine's GPU memory reader opens
with it (LUID and ordinal); (3) admission's live NVML read selects the device by its PCI bus id; (4)
a GPU launch of llama-server with a pinned UUID carries `--device CUDA0 -sm none` and runs with
CUDA_VISIBLE_DEVICES=GPU-<uuid>. The UUID is handed to llama-server only when the driver's CUDA API
version (cuDriverGetVersion) is at least the bundled llama-server runtime's CUDA major, 12 for
`variants/cuda12` (coordinator's instruction); otherwise a WARN says why and the GPU launch keeps
E4's unpinned line and environment. The flags were checked against the bundled llama-server b8571
(`--help`, `--list-devices`, and `--list-devices` under CUDA_VISIBLE_DEVICES set to this machine's
UUID).

Delegated choices recorded here: (1) The argv builder still writes `--device CUDA0 -sm none` after
`-ngl` on a GPU line; a launch with no UUID for llama-server removes the four flags, so the
unpinned line is exactly E4's. The driver gate applies to llama-server only: the ORT ordinal, the
reader and admission keep the pinned device, since they name a device rather than require a CUDA
12 runtime. (2) Without a pinned UUID the environment is left as inherited. (3) A bus id NVML does not know
falls back to device 0, as before. (4) Departure from Spec E section 5.2, accepted by
the coordinator: rule 1's explicit choices (`justsearch.gpu.device`, agreeing per-role ids,
USER_SETTING_UNMET) are not built in E6; step E7 owns the explicit keys. Until then the pin is always
rule 2's or the single-device rule's.

Checked on this machine with the bundled b8571 and the 9B model: with the UUID in
CUDA_VISIBLE_DEVICES and the exact pinned launch line, the server is healthy, logs "using device
CUDA0 (NVIDIA GeForce RTX 4070) (0000:01:00.0)" and "offloaded 33/33 layers to GPU", and holds about
6.4 GB on that GPU. When the CUDA runtime finds no device (CUDA_VISIBLE_DEVICES=-1, or a UUID the
driver does not have) the pinned line exits 1 with `invalid device: CUDA0`, where the E4 line warned
"no usable GPU found" and loaded on the CPU; the driver gate keeps the E4 line where the driver is
known to be too old for the runtime. With no CUDA device at all nothing is pinned and the
auto-detected GPU layers stay 0, so the launch line and environment are unchanged. Not covered: a
driver that reports CUDA 12 but still cannot start the runtime, and a user-supplied llama-server
built for another CUDA major (the gate compares with 12).

DevicePinningTest#aDriverBelowTheLlamaRuntimesCudaMajorHandsLlamaServerNoDevice was run on the
uncommitted E6 tree before the gate existed and failed by assertion ("below CUDA 12 llama-server is
launched unpinned, as before the pin existed ==> expected: <Optional.empty> but was:
<Optional[GPU-x]>"); that tree is not a commit, so this is not in an evidence file.

Evidence files (all written by run-evidence.mjs):

- `b6d536-bulk-gate-e6-repaired.json`: E-S10a, failing by assertion on 79b5e2e25 (the GPU launch
  line named no device).
- `b6d536-bulk-gate-e6-preservation.json`: E-P7b, the DeviceMemoryLine table and the NVML-first
  precedence pass on 79b5e2e25 and on the change; exits 1 by design because it shows no repair.
  These test files are unchanged, so no entry below names them.
- `b6d536-bulk-gate-e6-new.json`: checks of classes and signatures added in this change.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerDevicePinningTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "E-S10a: LlamaServerOps.buildLaunchCommand(cfg, rc, 99, plan) contains --device CUDA0 -sm none, so a GPU launch offloads to one device rather than llama.cpp's default layer split across every CUDA device. On 79b5e2e25 the launch line named no device, failing by assertion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S10",
          "quote": "On multi-GPU and hybrid laptops, chat and encoders use the same chosen GPU.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "The argv builder writes the single-device flags after -c and -ngl on a GPU line; the memory-plan block stays the tail of the line; a launch with no UUID for llama-server removes the four flags.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e6-repaired.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerDevicePinningTest.java#gpuLaunchOffloadsToTheOnePinnedDevice"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerPinnedDeviceEnvironmentTest.java"
      ],
      "class": "New",
      "behaviour": "The launch environment names the pinned device: a pinned UUID gives exactly CUDA_VISIBLE_DEVICES=GPU-<uuid>; no UUID, or a blank one, adds nothing. On a GPU launch the four flags --device CUDA0 -sm none follow -ngl <layers> directly; a CPU launch (-ngl 0) has neither --device nor -sm.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S10",
          "quote": "On multi-GPU and hybrid laptops, chat and encoders use the same chosen GPU.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "Without a pinned UUID the inherited environment is kept; the CPU launch names no device.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e6-new.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerPinnedDeviceEnvironmentTest.java#pinnedUuidBecomesTheOnlyVisibleCudaDevice",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerPinnedDeviceEnvironmentTest.java#nothingPinnedLeavesTheInheritedEnvironment",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerPinnedDeviceEnvironmentTest.java#pinningFlagsFollowTheWindowFlagsOnAGpuLaunch",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerPinnedDeviceEnvironmentTest.java#cpuLaunchNamesNoDevice"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/DevicePinningTest.java"
      ],
      "class": "New",
      "behaviour": "E-U6, the section 5.2 rule and the LUID / bus-id / UUID maps. The mapped NVIDIA adapter with the largest dedicated memory wins; a tie goes to DXGI's enumeration order, not CUDA's; a hybrid laptop's Intel adapter never counts; software adapters and NVIDIA adapters no CUDA device maps to are not candidates; one CUDA device without a LUID is pinned; several without a map, and none at all, pin nothing. Probe outcomes map to RAN_OK (with the pinned device), RAN_NO_DEVICE (with the driver code) and THREW. The pinned device gives the reader its GpuDevice (LUID and ordinal), admission its PCI bus id and llama-server its UUID; a driver whose CUDA API is below the llama-server runtime's CUDA major (11.8 against 12) gives llama-server no UUID, and 12.0 is enough; a pinned device without a LUID gives the reader no device; a pinned device outside the device list is refused. The pinned ordinal becomes the auto-detected value of the six per-role ORT device-id keys and reaches embedding, SPLADE, NER, BGE-M3, reranker and chunk reranker; a role set in settings keeps its value; with nothing pinned no key is contributed.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S10",
          "quote": "On multi-GPU and hybrid laptops, chat and encoders use the same chosen GPU.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "Candidates are DXGI hardware NVIDIA adapters that a CUDA device maps to by LUID; the pinned ordinal reaches ORT as an auto-detected default so explicit per-role ids win; the driver gate applies to llama-server's UUID only; the explicit device key and its unmet status wait for step E7.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e6-new.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/DevicePinningTest.java#theLargestMappedNvidiaAdapterWins",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/DevicePinningTest.java#aTieGoesToDxgiEnumerationOrderNotCudaOrder",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/DevicePinningTest.java#aHybridLaptopsIntegratedGpuNeverCounts",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/DevicePinningTest.java#softwareAndUnmappedAdaptersAreNotCandidates",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/DevicePinningTest.java#oneDeviceWithoutALuidIsOrdinalZero",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/DevicePinningTest.java#severalDevicesWithoutAMapAreUnresolved",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/DevicePinningTest.java#probeOutcomesMapToFactsStatus",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/DevicePinningTest.java#aDriverBelowTheLlamaRuntimesCudaMajorHandsLlamaServerNoDevice",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/DevicePinningTest.java#thePinnedDeviceMapsToReaderBusIdAndUuid",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/DevicePinningTest.java#thePinnedDeviceMustBeOneOfTheDevices",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/DevicePinningTest.java#thePinnedOrdinalIsEveryOrtRolesDefaultDevice",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/DevicePinningTest.java#anExplicitRoleDeviceIdKeepsItsValue",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/DevicePinningTest.java#nothingPinnedLeavesTheDefaults"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerDeviceLaunchTest.java"
      ],
      "class": "New",
      "behaviour": "A real managed launch (the HealthyFakeLlamaServer stand-in, 99 GPU layers): with a pinned UUID the launched argv carries --device CUDA0 -sm none; with none (nothing chosen, or a driver too old for the runtime) the argv has neither --device nor -sm, -ngl stays 99 and the memory-plan block follows it directly, which is E4's line.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S10",
          "quote": "On multi-GPU and hybrid laptops, chat and encoders use the same chosen GPU.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "A GPU launch with no UUID for llama-server keeps E4's unpinned line rather than naming a device the runtime may not see.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e6-new.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerDeviceLaunchTest.java#aPinnedGpuLaunchNamesTheDevice",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerDeviceLaunchTest.java#anUnpinnedGpuLaunchKeepsTheLineFromBeforeThePin"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/DeviceFactsMachineTest.java"
      ],
      "class": "New",
      "behaviour": "Step E6 on this machine's real APIs (Windows only). The process's device facts pin a device when there is exactly one; its LUID from cuDeviceGetLuid names a DXGI hardware NVIDIA adapter; the GPU memory reader opened with the pinned device reads it without an unknown reason and carries its LUID and CUDA ordinal; NVML finds a device by the PCI bus id CUDA reports, and on one GPU it is device 0's handle; admission's snapshot by bus id has the same total memory as the device-0 snapshot (P7). On a machine without a CUDA device nothing is pinned.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S10",
          "quote": "On multi-GPU and hybrid laptops, chat and encoders use the same chosen GPU.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e6-new.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/DeviceFactsMachineTest.java#thePinnedDeviceIsTheSameDeviceAcrossCudaDxgiPdhAndNvml"
      ]
    },
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/GpuDriverApiProbeDevicesTest.java"
      ],
      "class": "New",
      "behaviour": "The probe extension of section 5.3. A LUID's halves are formatted as PDH names its instances (luid_0x<high>_0x<low>, eight lower-case hex digits each, a negative high part as ffffffff); a 16-byte CUuuid is formatted as nvidia-smi and CUDA_VISIBLE_DEVICES name it (GPU-8-4-4-4-12). On a machine with a CUDA device every ordinal is listed in order with a UUID, a PCI bus id, total memory above zero and, on Windows, a LUID; without one, none is listed and the status is not OK.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S10",
          "quote": "On multi-GPU and hybrid laptops, chat and encoders use the same chosen GPU.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e6-new.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/GpuDriverApiProbeDevicesTest.java#luidIsFormattedAsPdhNamesItsInstances",
        "modules/ort-common/src/test/java/io/justsearch/ort/GpuDriverApiProbeDevicesTest.java#uuidIsFormattedAsNvidiaSmiAndCudaVisibleDevicesName",
        "modules/ort-common/src/test/java/io/justsearch/ort/GpuDriverApiProbeDevicesTest.java#everyOrdinalCarriesItsIdentity"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerLaunchFlagsTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "S10 changes the exact ordered GPU launch line to include --device CUDA0 -sm none immediately after -ngl 99. All other launch expectations remain unchanged. The evidence names the whole class because JUnit reports the fullGpuLaunchCommand method under its display name; only that ordered-line assertion fails on the parent, and the other eleven checks pass.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S10",
          "quote": "On multi-GPU and hybrid laptops, chat and encoders use the same chosen GPU.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-e6-intent-repair.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerLaunchFlagsTest.java"
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
  "session": "a3a46d49-5d40-43d9-a1ad-d9e9e536d01b",
  "verdict": "reject",
  "digest": "sha256:dc24706768d5154c652263b540a85125fcd6000083f6de8fe0ca92331fe0491a",
  "note": "LlamaServerLaunchFlagsTest is entered as Adaptation (moved: signature), but fullGpuLaunchCommand's expected ordered launch line gains --device CUDA0 -sm none: the expected outcome changed and no signature moved. test-intent.md keeps the whole oracle for an Adaptation; Spec E section 9 naming it Adaptation does not change the class rule. Re-enter it as New (changed intent) citing S10, with evidence of the assertion failing on 79b5e2e25. The pinning behaviour itself is supported live (llama launched with --device CUDA0 -sm none and CUDA_VISIBLE_DEVICES=GPU-8b20006f-...). Other E6 entries checked and would be acceptable."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch1-20261008-independent",
  "verdict": "reject",
  "digest": "sha256:554876a32103128d96e1183d7fc4c4967010f50fa85960124165a6c33f0fe339"
}
```
