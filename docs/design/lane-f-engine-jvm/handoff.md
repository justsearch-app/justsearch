# Lane F handoff: implementation orchestrator

## Current state (2026-09-21)

Continue autonomously in `.claude/worktrees/lane-f-pr1-verify`, branch
`codex/lane-f-pr1`. Main contains unrelated work; never edit or clean it.
Existing checkpoint commits/pushes to [PR727](https://github.com/justsearch-app/justsearch/pull/727)
are authorized; merge remains at stage F. No routine owner approval is pending.
Continue after status answers, commits and reviews. C2 is accepted; D1/D2/E/F
remain open. Root owns all Gradle runs, stack lifecycle and integration.

Base for the latest verified slice: `ad9ac55ba` (production trigger, post-bind
request and constructor cleanup). Current checkpoint adds actual conditional index
sampling and registry-driven manifest publication.2322b passes132 UI cases freshly;
2325 restores matching proof FROM-CACHE, not a fresh run.13 EngineRoot/trigger cases
passed freshly in2322. Full static collection's eight UI issues and a later missed
qualifier are corrected; required checks pass. Negative2323/2324 fail for all five
intended omissions and restore both sources byte-exactly. See
[registry connection proof](evidence/D1/registry-readiness-verification-2026-09-21.md)
for precise revisions, commands, failures, reuse and artifacts. Runtime closure,
readiness-code and documentation checks pass. No Gradle or stack is active at this
checkpoint; next run2326. D1 is not accepted.

## Next coherent work

1. Continue capability migration after the verified registry checkpoint. Cached
   stale reads must demote READY even while an RPC is blocked; that publication
   invalidates the old result and queues one fresh follow-up. Fresh debug/background
   samples share ownership; cached reads do not wait on the RPC lock. The active
   manifest subscription covers all four components, rejects stale aggregate and
   sibling updates, and preserves component-owned READY timestamps. See the
   [readiness plan](evidence/D1/readiness-plan-2026-09-21.md).
2. Retire WorkerCapability and InferenceCapability as mutable authorities. Pass the
   same decorated physical handles to producers; construct read-only registry
   adapters for gates. Remove temporary direct physical READY publication and the
   generative capability mirror. Preserve recovery occurrence attributes at the
   monitor's recovery decision, not on a coalescing adapter. Replace test/default
   constructors with explicit owner fixtures, not mutable fallback authorities.
3. Connect schema2 status/health from one post-sampling registry snapshot; retire
   independent lifecycle/manifest fallback derivation. Migrate wire schema, UI,
   dev host and Rust host together. Hosts use index component READY with local
   monotonic stability clocks. Keep diagnostic dimensions/composites intact.
4. Complete the291-key apply register and real readers/dispatch, then all remaining
   D1/D2/E/F obligations. Classification is not implemented apply behavior. Required
   D1 live API, real standard-model query and platform proof remain outstanding.

D1 Flow A must reconcile streaming core.reindex versus captured core.bulk-reindex:
abandonment cannot delete C2 evidence before terminal ownership and exact queue
ACK. COMPLETE_WITH_GAPS/accept-gaps/live activation replaces current behavior only
with an explicit design and proof. All acceptance items remain binding.

## Active ownership

- Root: sampler, EngineRoot index handle, builder/CoreApiAssembly plumbing, worker
  and capability integration, all builds, evidence and scoped publication.
- `d1_encoder_projection`: RuntimeManifestPublisher/ListenerWiring and direct tests;
  only its manifest wire call in HeadlessApp. Delivery complete; no further edits.
- `bulk_engine_restart_proof`: bounded generative producer/callback design for
  InferenceCapabilityWiring, RuntimeActivationService, InferenceHandlers and direct
  tests. Existing settings revision/key witness fences stale activation failure;
  root clears edits after checkpoint and owns integration call sites.
- `d1_owner_review`: read-only independent review. Never runs Gradle or stacks.

## Evidence and owner map

| Concern | Governing record |
| --- | --- |
| Predecessor teachings and honest self-audit | [Resumption contract](evidence/C2/resume-2026-09-21.md) |
| C2 accepted proof, including installed/stress/hosted/real-model | [C2 acceptance](evidence/C2/verification-2026-09-21.md) |
| D1 actual owners and captured configuration | [Owner map](evidence/D1/regrounding-2026-09-21.md), [component plan](evidence/D1/component-plan-2026-09-21.md), [wiring proof](evidence/D1/owner-wiring-2026-09-21.md) |
| Broad affected-module proof2298 at6c95d7989 | [Integrated verification](evidence/D1/owner-integrated-verification-2026-09-21.md) |
| Full-snapshot CAS and stateless reason retention | [Publication seam](evidence/D1/publication-seam-verification-2026-09-21.md) |
| Pure schema2 projection, trigger feedback and tool composition | [Schema/trigger proof](evidence/D1/schema-trigger-verification-2026-09-21.md) |
| Physical-health initialization and close/retry ownership | [Bootstrap proof](evidence/D1/bootstrap-initialization-verification-2026-09-21.md) |
| Runtime readiness and six-state decisions | [Readiness plan](evidence/D1/readiness-plan-2026-09-21.md) |
| Schema and host migration | [Schema consumers](evidence/D1/schema2-consumer-plan-2026-09-21.md), [host plan](evidence/D1/host-readiness-plan-2026-09-21.md) |
| Apply classification and audited missing readers | [Apply-register plan](evidence/D1/apply-register-plan-2026-09-21.md) |

## Working rules to retain

Start with the acceptance path, then reuse actual owners. Delegate bounded files
and deliverables; consolidate review and reassess after two substantive rounds.
Root owns shared state, stack and Gradle. Freeze compiled sources before a build.
Run focused checks while correcting and integrated checks at coherent boundaries;
collect independent static failures using `spotlessCheck pmdAll --continue`.
Workflow correction `b4d01c1cc` already records this in canonical guidance and both
CI-triage skills; no extra always-loaded policy copy is needed.

Keep compiling checkpoint commits per item, push each checkpoint and preserve WIP
at least hourly. Stage explicit paths; check native exit codes before mutations.
Preserve XML before reruns. Name tested revision, reuse, failures, skips and proof
tier; BUILD SUCCESSFUL or hosted job success alone is not test-level acceptance.
Refute expected-looking passes and reviewer mechanisms against the actual owner.
Do not replace a concurrency defect with an unnecessary marker, store or timer.

Discover paths with `rg --files`; use `-g` for filename globs and bounded excerpts.
Use UTF-8 editing. Root has repeatedly failed to apply the path/output discipline;
more wording is not evidence of improvement. The concrete context correction here
is to keep this handoff current, with history in a separate archive.

Raw logs/XML under this worktree's `tmp/` remain accessible through lane acceptance
plus30days; export before deleting the worktree. Use `--no-ignore` to discover
ignored evidence. Hashes supplement accessible artifacts rather than replace them.

[Historical handoff archive](handoff-history-through-2026-09-21.md) preserves the
prior1850-line record and old decisions. It is background, not a resumption queue;
read only the historical section needed to resolve a specific uncertainty.
