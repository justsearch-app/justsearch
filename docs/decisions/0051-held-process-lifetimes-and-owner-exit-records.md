---
title: "ADR-0051: Held process lifetimes and owner-recorded exits"
type: decision
status: stable
description: "Every service process is bound to its owner's lifetime, so nothing outlives the process that started it: on Windows through the owner's kill-on-close job, on Linux through PR_SET_PDEATHSIG set by the bound-exec helper. The chat server is no longer adopted across Engine restarts; every stop is recorded by its owner with a reason before it happens, and instruments classify exits from those records instead of from exit codes."
date: 2026-10-05
amends:
  - "0049-one-engine-jvm-and-the-boundaries-that-survive.md"
probes: "none - the probes land with the implementing branches; the candidates are listed under Enforcement"
last_reviewed: 2026-10-05
---

# ADR-0051: Held process lifetimes and owner-recorded exits

## Status

Accepted by the owner on 2026-10-05 (decisions D2, D7 and D9 of the co-residence design).
Implemented in #759 (`1d272de4b`), including Linux parent-death containment. ADR-specific probes
are not registered in `governance/adr-probes.v1.json`.

## Context

ADR-0049 keeps two process boundaries below the Engine: llama-server and the extraction child pool. The Engine also starts short-lived helpers that no registry tracks:
- tesseract;
- curl;
- nvidia-smi;
- MCP stdio servers;
- the activation self-test's llama-server.

How lifetimes and exits are handled today:
- **Ownership is inferred from records, not held.** Child manifests, declared-configuration hashes and start-time matches decide what to adopt and what to kill. Each fix to that inference exposed the next path: the stop-orphan reviews rejected seven rounds.
- **Lifetimes don't nest.**
  - A shell crash orphans the Engine. The next launch then exits `DATA_DIR_LOCKED` and exhausts.
  - An Engine crash leaves llama-server holding about 7.3 GiB. The next Engine adopts it or kills it, depending on a hash that ignores the realized context.
  - In development, Node's job object lets llama-server break away silently.
- **Every stop looks like a crash.**
  - Java `destroy()`, Rust `kill()` and `taskkill /F` all end a process with exit code 1. llama-server also exits 1 on its own load failure.
  - Termination intents are written only for extraction children, and only the census reads them.
  - As a result, every clean quit counts llama-server's exit as abnormal.
- **Adoption saves little.**
  - It saves 10–24 s of chat readiness after an Engine restart.
  - Encoders need a median of 26 s after a start anyway.
  - Every restart in the recorded runs was injected or requested.

## Decision

1. **Service lifetimes nest, enforced by the kernel.**
   - At boot the Engine creates a kill-on-close job and joins it. Every process it starts afterwards inherits the job, including each console child's conhost (verified from Java via FFM on Windows 11).
   - The job handle is not inheritable.
   - The job does not set die-on-unhandled-exception, so crash dumps survive.
   - The shell places the Engine in a second kill-on-close job right after spawning it. The shell itself never joins a job, so installers, Explorer windows and opened links outlive it.
   - MCP stdio servers die with the Engine.
2. **No adoption.**
   - An Engine restart ends every child with the old Engine. The new Engine starts query encoders first, then the chat server.
   - Adopt-or-kill, the declared-configuration identity and the orphaned-chat-server state retire.
   - Each managed launch uses a fresh loopback port. A user-supplied external llama-server is used only when explicitly configured; it is reported and never stopped. This keeps a crashed Engine's unregistered server from being mistaken for the user's own (pending founder decision F1).
3. **The stopper records first.**
   - Each owner stops its children through one API, `stop(child, reason, mode)`. It writes an intent to an append-only exit ledger per data directory, terminates, waits on the process handle, and records the outcome.
   - An exit observed after our terminate call, with a matching intent, is `REQUESTED(reason)`.
   - An exit observed before that call is `UNEXPECTED`, even if an intent exists.
   - A child killed by job closure is `OWNER_DIED`.
   - A failed observation is `UNKNOWN`, never "dead".
   - The supervisor records Engine incarnations the same way.
4. **Instruments read the owners' records.**
   - The stop census, the E4–E6 tooling and the dev tools compare the ledger and the status surface with OS observation.
   - They stop parsing stderr markers and inferring intent from exit codes.
5. **Supervision is one state machine with startup inside it.**
   - Startup is incarnation 1 of the supervision loop, with the same accounting and cleanup as later incarnations.
   - Quit has one deadline, and every shutdown step has a sub-budget inside it.
   - The dev-runner takes the same actuator shape. The conformance cases cover start, discovery, quit and kill against both production actuators.
   - A shared supervisor binary is deferred.

## Consequences

- **Quit, crash and shell death leave no process and no VRAM behind.** That includes the conhosts and helpers no registry tracked.
- **A crash during an intended stop stays visible as a crash.**
- **Chat is ready later after each Engine restart:** median about 4 s, at most about 22 s observed. This applies to crash, hang and restart-required settings, index migrations and development restarts. A GPU-contended hang restart now frees the chat model's memory too.
- **The ledger is new saved state.** It is bounded and versioned, and it tolerates partial lines.
- **MCP stdio servers die with the Engine.** A tool that must outlive the Engine is not supported.
- **What retires:**
  - manifest-driven terminal sweeps in the shell and dev-runner;
  - reconciler adoption;
  - stderr termination markers.

## Alternatives considered

- **Keep adoption and fix its identity.** Rejected. It is the source of most lifecycle defects, and the benefit is seconds after restarts that are not frequent in the field.
- **Graceful llama-server stop** (console attach helper or router-mode stdin). Rejected. The process holds no state to save, and a forced kill plus a ledger record classifies correctly.
- **Shell joins its own job.** Rejected. It would kill installers and user-opened applications.
- **One shared supervisor binary for dev and production now.** Deferred. Node's job already contains the dev Engine, so the Engine job closes the dev leak. Shared conformance covers the divergence at lower cost.

## Reassessment triggers

- Engine restarts become frequent in the field, so the chat reload cost is felt.
- A required child must outlive the Engine.
- Conformance cannot keep the two actuators equivalent.

## Enforcement (probe candidates, added with the implementation)

- The Engine creates and joins its job at boot, in one place.
- No adoption code path remains in the chat-server decider or the reconciler.
- Every child stop goes through the stop API (no direct `destroy` outside it).
- The census reads the ledger.

## Evidence

- Lane F stage B and the E4–E6 records under `docs/design/lane-f-engine-jvm/evidence/`.
- The job-object spike and the process research in the task record of this decision. They are copied into the implementing changes' evidence.
