# D1-3 coherent applied configuration revision

## Decision and ownership

Compute the overall revision on demand in EngineRoot, the existing cross-half
composition owner. It is a projection of applied component values and committed
active-generation inputs, not another mutable revision authority. The accepted
settings counter remains separate.

Existing getCommitMetadata is best-effort diagnostics. captureServingGeneration
requires an idle generation for mutation safety; Blue must remain observable while
Green builds. captureIndexTarget computes desired rebuild inputs and must not be
used to report applied configuration. Preserve all three contracts.

Add a strict immutable AppliedIndexGeneration observation across the existing index
boundary, through a package-local bounded EngineKnowledgeClient call consumed by
EngineRoot. No general IndexingService contract is added without an external caller.
Reuse IndexTargetSnapshot for its validated fingerprint/input pair and byte
limit, retaining the generation ID alongside it. The Worker reads its captured
search runtime's committed metadata and checks the authoritative active path before
and after that read. Add a read-only active observation to IndexGenerationManager
without weakening idleActiveGeneration. Missing or unreadable state/metadata refuses
with UNAVAILABLE; observed movement refuses with ABORTED. Cancellation and deadline
handling stay inside the existing bounded call.

The EngineKnowledgeClient call also captures the WorkerAppServices object and checks
it is still published after the observation. KnowledgeServer creates a fresh owner
for runtime reconstruction; A-to-B-to-new-A therefore fails the identity check. A
pointer-only A-to-B-to-A with the unchanged serving A runtime may linearize to A:
this revision identifies applied values, not an activation count. No persistent
epoch, lock, marker or new runtime registry is needed.

EngineRoot captures registry snapshot A, obtains the strict generation observation,
then captures B. A changed registry revision or replaced EngineKnowledgeClient
refuses instead of hashing a mixed
observation. The revision is only a validation fence, never a digest input. Require
the four registered component names; use each actual appliedVersion, including
explicit null for an absent optional owner. Hash sorted names/applied versions,
generation ID and committed canonical inputs through the existing canonical digest
utility. Desired values, lifecycle states, timestamps, evidence and registry
revision must not affect the hash.

Primary-source review and race analysis are retained in
tmp/2403-overall-revision-design.md (reviewed921053650). Its file/line inventory
covers RuntimeSession's single-commit metadata copy, strict manager state reads,
KnowledgeServer's fresh service publication and the root's volatile client. Parse
committed inputs as an object for recursive canonical serialization; the stored
fingerprint validates their bytes and is not a second independent hash input.

## Required evidence

- Identical applied values and generation across independently composed engines
  produce the same digest despite different lifecycle metadata or registration order.
- Changing any component applied version changes the digest; changing desired values
  alone does not. Same inputs under a different generation ID change the digest.
- Blue remains observable while Green builds. Missing or malformed committed metadata
  refuses. Changing desired settings without a commit leaves the observation unchanged.
- Controlled promotion/runtime replacement between reads returns a coherent observation
  or refuses, never a mixed pair. Runtime-owner rebound and registry movement are
  independent negative cases.
- Exercise the actual composed port, not only a pure canonicalizer or a test-only
  supplier. Retain focused/static and integrated evidence at the implementation boundary.

This implements D1-3 revision calculation only. D1-4 dispatch, D1-12 model binding
and the fourteen known fingerprint gaps remain mandatory subsequent work.
