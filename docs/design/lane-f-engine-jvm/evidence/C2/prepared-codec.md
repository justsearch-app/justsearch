# C2-3b: bounded prepared-envelope codec prerequisite

2026-09-13, based on0104647cf. This is a staged prerequisite, not activation:
OperationExecutorImpl still refuses every non-null replay schema. Database storage,
its v3 migration, runner serialization, existing-key-manager wiring and frozen
approval transfer are the immediately following C2-3 work.

OperationPreparation gains a server-only CONTENT classification; the existing
constructor retains metadata semantics. Its schema/payload bounds now count UTF-8
bytes. OperationPreparedPayload bounds the opaque stored value and hides it in
its string representation. PreparedInvocationCodec binds key, server nonce and the
existing canonical public descriptor to a versioned envelope. The full prepared
record and original attribution are retained, with process-local work id and signed
intent token stripped. CONTENT requires an enabled/unlocked existing StoreCipher;
the seal result is checked too, so disabling encryption between the check and seal
cannot silently write plaintext. Terminal metadata receipt lookup does not use it.

## Proof

- Negative943 removes both disabled-key guards. Seven represented cases run; the
  named disabled-cipher witness fails because plaintext is returned. Six cases are
  inherited service test-harness checks, not additional codec coverage.
- Positive944 passes543 represented cases, all three module test tasks execute.
- Negative945 removes key/nonce/public-identity binding. Nine represented cases run,
  with all three independent rebinding cases failing; ciphertext decrypts but cannot
  be accepted for a different invocation once the guard is restored.
- Positive946 passes545 represented cases; agent-api239/app-api204 reuse944.
- Negative947 removes only the post-seal guard. Seven represented cases run, with
  one intended failure: a deterministic key-state change makes StoreCipher return
  plaintext after the initial check. No data-key access occurs in that path.
- Interim948 passes546 represented cases (services103 execute; agent-api239 and
  app-api204 reuse944), zero failures/errors/skips, affected PMD and UI integration
  compilation pass. Tests include actual DataKeyManager/keystore restart and unlock,
  frozen target preservation, original attribution, token/work-id stripping,
  malformed/future/classification refusal and UTF-8/storage/envelope bounds.

Independent read-only refutation found that the constructor assumed forward mode
when recomputing the digest. Negative949 adds separate invoke/undo cases and fails
only undo (8 represented cases,1 failure). The codec now reads the validated mode
from the existing descriptor; it does not add a second mode authority. Final950
passes548 represented cases: services105 execute; agent-api239/app-api204 reuse944.
Affected PMD and UI integration-test compilation pass. Ciphertext rebinding and
mode-crossing remain separate tested refusals. Independent bounded reread confirms
the mode finding resolved with no remaining actionable codec defect; the reviewer
read the recorded950 evidence and did not run additional checks.

This proves codec and dispatcher compatibility, not operations.db restart, full
approval, live API/model or hosted behavior. Those checks remain required before
C2-3/batch2 acceptance. The contract mechanism is in [C2-3-plan.md](C2-3-plan.md).

Exact command/counts/source hashes: [prepared-codec-verification.json](prepared-codec-verification.json).
Raw logs/XML/counts are under lane `tmp/c2-3-preparation-negative{943,945,947,949}` and
`tmp/c2-3-preparation{944,946,948,950}`, with `.txt`, `-xml/`, `-counts.json` suffixes.
Retain local artifacts until lane acceptance plus30days; export before releasing the
worktree. Source hashes identify bytes, not a replacement for artifact access.
