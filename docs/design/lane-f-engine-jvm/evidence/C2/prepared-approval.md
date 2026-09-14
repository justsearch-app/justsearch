# C2-3c: frozen approval reference and consent prerequisite

2026-09-13, based on83a78c564. Gate exceptions can carry the server-minted operation
key and exact preparation nonce. HTTP and MCP preserve that reference in the existing
PendingAuthorizationStore; public arguments remain separate for display and dispatch.
Approval returns the reference to its caller or sends it to the seven-argument
server dispatch/undo path. Default dispatch implementations refuse a non-null nonce,
rather than silently dropping it and selecting a new target.

ConsentCapsuleAuthority exposes prepared mint/verify methods with fail-closed defaults.
The existing service binds operationId plus the canonical public digest, key and nonce.
Its signed BoundAction argument binding is prepared-v1: followed by the canonical
binding digest; ordinary capsules have an unprefixed hexadecimal digest. This domain
separation prevents caller JSON shaped like the binding representation from impersonating
prepared consent. Both use the same mint/signature/expiry/revocation/single-use implementation
and registry. No new grant type, token registry, digest algorithm or key authority is added.
A public-only capsule cannot authorize a prepared target. Passthrough consent is unchanged.

The dispatcher still refuses prepared replay schemas. No production gate emits the new
nonce yet, and its prepared overload still refuses by default. This prerequisite only
makes reference delivery and approval safe for the upcoming connected implementation.
It does not claim frozen-target execution, stale-approval-before-preparation enforcement,
or any prepared producer activation.

## Local proof

- Negative967 adds a real capsule regression before changing approval minting. Three
  represented cases execute with1 intended failure: a frozen-target approval authorizes
  public arguments alone. Correct minting binds the stored reference.
- Positive968 passes418 represented cases with all three test tasks executed.
- Negative969 removes only the nonce from server dispatch/undo. Four represented cases
  execute with2 intended failures: both prepared approval routes fail rather than reach
  their exact seven-argument dispatcher. Positive970 passes420 cases after restoration.
- Root then refuted the initial JSON-only binding: a caller can ask for ordinary consent
  to public JSON equal to that representation. Negative971 executes3 represented cases
  with1 intended failure, proving the collision across ordinary/prepared consent.
- After factoring the existing capsule implementation and adding the signed-domain
  prefix, positive972 passes421 cases, all three test tasks executed. Negative973 removes
  only prepared-v1: from the final mechanism and again fails exactly the crafted-JSON
  regression (3 cases,1 failure). Failed task status is execution, not reuse.
- Positive974 passes422 cases across52 suites, zero failures/errors/skips. UI69 execute;
  app-agent-api239 and services114 reuse successful972 (the latter's only later source
  change is a comment). Affected PMD and UI integration-test compilation pass. Coverage
  includes changed public input/key/nonce refusal, malformed/missing reference refusal,
  canonical whitespace equivalence, successful one-time consumption, default authority
  refusal without fallback, original unprepared approval, server invoke/undo, and MCP
  preservation when the caller supplied no key.

[Verification JSON](prepared-approval-verification.json) records exact command/counts,
reuse and source hashes. Raw lane tmp logs/counts/XML are
`c2-3-preparation-approval-negative{967,969,971,973}` and
`c2-3-preparation-approval{968,970,972,974,975}` with `.txt`, `-counts.json`, `-xml/`.
Retain until lane acceptance plus30days and export before worktree release. No new
live/model/installed/hosted proof is claimed; integrated C2-3 and v3 hosted gates remain.

Final975 after the review comment correction passes422 cases across52 suites with
zero failures/errors/skips: services114 and UI69 execute, app-agent-api239 reuse972.
PMD and UI integration-test compilation pass.

Read-only review of restored final source found no remaining scoped behavior/security
defect. Its capsule-wire comment correction is included; root independently reread
the captured negative973 failure and positive proof.

## Connected follow-on ownership

OperationExecutorImpl must freeze under the runner's pure scope, use the existing
DataKeyManager cipher, look up accepted rows before preparation/decryption, and verify
the supplied nonce before any replacement preparation. It must verify prepared consent
through the new authority method and preserve current hard-stop/provenance/grant checks.
Acceptance transfers the same envelope, then effects and observation run outside the key
lock. A retry cannot authorize an accepted incomplete effect; reconciliation still owns it.

Read-only consumer mapping identified these remaining paths: OperationClient invoke-first
consent and undo retries; pendingAuthorizationBridge server execution; AgentSessionController's
legacy undo 428 projection; GatedOperationExecutor and the agent/workflow boolean approval
planes. The agent/workflow planes currently keep in-session gates rather than this pending
store, so their continuation must preserve the same key/nonce and frozen request without
creating another authority. URLExtractor only classifies gate failure; BackendIntentRouter
propagates it. Review all captures before activation. Exact locations are discoverable from
ConfirmationRequiredException, PendingAuthorizationStore.create and capsule mint/verify uses.

The preceding runner commit83a78c564 omitted its commit body; its item command, exact
source hashes, negative controls and reuse are recorded in prepared-runner.md/json.
This explicit correction preserves published history; later commits include evidence bodies.
