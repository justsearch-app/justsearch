---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, Spec B (item 6) step 2: the Engine's `DefaultChildControl` with its Windows
FFM and POSIX terminators, the NVML `VramProbe`, and the `LedgerReconciler`, opened by the Engine
under `app.lock` over `runtime/exit-ledger/children.v1.jsonl`; plus the manifest `incarnation` field
of Spec F section 3.2. Every flagged item is a new check of new behaviour; no pre-existing check is
changed. The extraction pool is not moved onto ChildControl in this slice (step 4), so no stop path
writes child records yet in production. The Node census tests (`scripts/dev/test-*.mjs`) need no
entry (Spec F section 8.1); `governance/fixtures/exit-ledger/engine-written/children.v1.jsonl` is
a source read by path.

All checks ran in one evidence run: `gates/test-intent/evidence/b6d536-child-control.json`. No
fail-before is claimed through run-evidence: on a0d6c4c50 every new check fails to compile because
the classes and the manifest field it exercises do not exist there.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/DefaultChildControlTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/ChildControlHarness.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/FakeChild.java"
      ],
      "class": "New",
      "behaviour": "Spec B T1-T9, T13 and T14 against real fake-child JVMs (FakeChild: sleep, exit, eof, ignore-eof, exit-on-signal-file) and the real OS terminator, with the ledger written by the real ExitLedgerWriter; every ledger line written in a test conforms to governance/exit-ledger.v1.schema.json. T1: stop(NOW) on a running child is REQUESTED(reason), its code is our terminate code, exactly one stop-intent precedes the terminate and exactly one child-exit follows. T2: a child that exits 7 after the intent and before terminate stays UNEXPECTED(7) with terminateResult ALREADY_EXITED. T3: stop on an already-exited child returns the recorded UNEXPECTED outcome and writes no intent. T4: eight concurrent stops write one intent and share one future, the first reason wins, and NOW escalates a pending cooperative stop. T5: cooperative close-stdin with eof:0 is REQUESTED and not forced; ignore-eof is forced at the grace; eof:3 is UNEXPECTED(3). T6: stopAll of three children shares one 2 s grace (under 3 s in total). T7: a terminator that never kills gives SURVIVED after 10 s of virtual waiting (two calls), child-survived is recorded, and a retry under the original intent succeeds. T8: the intent line is on disk when terminate runs. T9: a throwing ledger never stops the terminate (ledgerDurable false), and a failing write leaves a ledger-gap before the child-exit. T13: the GPU poll recovers at 0.75 of the expected bytes or at a stable reading after a drop, times out at 5 s, is UNMEASURED without a probe, and is skipped for QUIT, UPGRADE and ENGINE_RESTART. T14: a GPU start and awaitGpuRelease wait for a pending poll. A started child is registered in the manifest registry with its declared hash and removed at exit; a refused registration stops it as ROLLBACK and fails the start.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S15",
          "quote": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S16",
          "quote": "Quitting at awkward moments (boot, model load, context relaunch, config apply, update prepare, restart cooldown) and Windows shutdown leave nothing running.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S19",
          "quote": "The activation self-test never loads a second chat model beside the running one when that would not fit.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for items 5 and 6",
          "choice": "The intent is appended before the terminate on the caller's thread; the exit class is decided by whether an intent preceded an exit we caused, never by the exit code. A child-exit records the intent's stop mode, and an escalation is its own stop-escalate record. Terminate is tried twice with a 5 s wait each before SURVIVED. The GPU poll samples every 250 ms for at most 5 s.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-child-control.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/DefaultChildControlTest.java"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/LedgerReconcilerTest.java"
      ],
      "class": "New",
      "behaviour": "Spec B T10. At boot the reconciler reads children.v1.jsonl left by earlier Engine incarnations. A child whose last record is an open start, intent or spawn and whose PID is gone, or reused by a process with another creation time, is recorded OWNER_DIED with its basis. A child that is alive with a matching creation time and executable gets child-survived owner_died_alive and is never touched. A child known only from its spawn record is UNKNOWN. A second boot over the same ledger writes nothing more. Every line written conforms to the ledger schema.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S17",
          "quote": "Dev and app hosts give the same accounting and cleanup when the Engine dies during startup or later; an identity lookup failure reads as unknown, never dead.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-child-control.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/LedgerReconcilerTest.java"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/EngineWrittenChildrenFixtureTest.java",
        "governance/fixtures/exit-ledger/engine-written/children.v1.jsonl"
      ],
      "class": "New",
      "behaviour": "The ledger the Engine's own ChildControl (openForEngine) writes for three extraction children: a timeout stop (REQUESTED TIMEOUT, forced), a cooperative quit after stdin EOF (REQUESTED QUIT, code 0), and a crash with exit 1 followed by an oom note (UNEXPECTED). It conforms to the ledger schema and equals the committed fixture once volatile fields, ids and the platform's terminate code are normalized. scripts/dev/test-dev-runner-exit-census.mjs reads the same fixture and the census reports exit accounting complete for every extraction child in it.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S15",
          "quote": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-child-control.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/EngineWrittenChildrenFixtureTest.java"
      ]
    },
    {
      "items": [
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/memory/NvmlVramProbeTest.java"
      ],
      "class": "New",
      "behaviour": "The NVML probe for the child GPU release poll gives adapter used bytes only when NVML sees exactly one device and the caller's CUDA ordinal, when known, is 0. Several devices, another ordinal, or a failing NVML is no reading, never an exception.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S19",
          "quote": "The activation self-test never loads a second chat model beside the running one when that would not fit.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-child-control.json",
      "newChecks": [
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/memory/NvmlVramProbeTest.java"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/RuntimeManifestIncarnationTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestIncarnationPublishTest.java"
      ],
      "class": "New",
      "behaviour": "Spec F section 3.2: the Engine copies JUSTSEARCH_ENGINE_INCARNATION (<session>/<n>) into the runtime manifest. RuntimeManifestIncarnationTest: a well-formed value parses, split at the last slash; a missing, empty, unnumbered, zero or oversized value is absent; the field round-trips, is omitted when absent, and is kept in the public projection. RuntimeManifestIncarnationPublishTest: the first ownership-seed write carries the incarnation, every later publish carries it forward, and an unsupervised Engine writes none. scripts/dev/test-dev-runner-exit-census.mjs checks that a census does not attribute a manifest naming another run to this run.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S17",
          "quote": "Dev and app hosts give the same accounting and cleanup when the Engine dies during startup or later; an identity lookup failure reads as unknown, never dead.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-child-control.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/RuntimeManifestIncarnationTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestIncarnationPublishTest.java"
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
  "session": "9ab35ce5-d0fe-4b77-bc6b-47bb55c9efa6",
  "verdict": "accept",
  "digest": "sha256:416d6bc62a119ef851c42bf07fe9085edda73f91f663b5674411a6b30762fd06",
  "note": "Verifier ran the full app-api (358), app-engine, ui and gpu-bridge suites on 9b88b9e03: DefaultChildControlTest 19, LedgerReconcilerTest 2, EngineWrittenChildrenFixtureTest 1, NvmlVramProbeTest 2, RuntimeManifestIncarnationTest 2 and RuntimeManifestIncarnationPublishTest 2 all pass. All entries are New for an API absent at b76f89ba2, so the fail-before is compile-only, as claimed; no assertion fail-before exists. The reconciler and incarnation behaviour was confirmed live: OWNER_DIED for a killed Engine's child, owner_died_alive for a live match left running, GONE on PID reuse, UNKNOWN for spawn-only, and the manifest incarnation n=2 after restart."
}
```
