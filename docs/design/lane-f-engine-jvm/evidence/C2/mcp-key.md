# C2-3: MCP key delivery and receipt projection

2026-09-13; implementation based on d5c43a9d9. This item completes the MCP part of
C2-3a. Persisted preparation, its approval transfer, remaining ingress and batch2
acceptance remain open.

The existing curated browse/ingest declarations advertise optional `operationKey`,
matching the C2 ingestion and955 consumer vocabulary. HTTP retains its existing
`idempotencyKey` envelope field. MCP removes its transport key from a copied argument
map before the Operation-owned schema validates public input. The dispatcher receives
that key separately, so canonical public identity does not depend on the key field.
No second handler schema, key validator or key authority is introduced. Wrong key
types refuse at ingress; UUIDv7/expiry/identity validation remains the runner/store's.

Pending authorization retains the same key and invoke mode. The shared HTTP error
projection supplies public key/storage codes; native causes stay private. Failed
attempts also retain the authoritative key and row id in both text and structured
content. Only those two receipt fields are copied from failure structured data.

## Verification

- Negative924 is a test compilation failure (wrong factory name), not behavior proof.
- Negative925 executes12 represented cases (10 new MCP witnesses plus2 inherited
  test harness cases), with10 failures before implementation: missing declaration,
  dropped dispatch/approval key, absent typed key failures and wrong-type refusal.
  The typed-error cases at this revision fail before reaching their mocked store
  exception because the six-argument dispatcher is not called; they are not claimed
  as an independent old error-projection mutation control.
- Focused926 executes72 cases with one failure: the old browse property-order
  expectation. Both browse and ingest order assertions retain their ordering check,
  appending the newly declared optional key. Focused927 passes72 cases.
- Negative929 executes3 represented cases with one intended failure: restoring the
  pre-existing failure response drops the minted receipt key. Private payload fields
  remain excluded by the positive regression.
- Interim930 executes73 cases across5 suites, zero failures/errors/skips; UI PMD main
  and test execute, UI integration-test compilation is up-to-date. This is focused
  mocked-dispatcher proof, not a live API/model query or hosted/full acceptance.
- Final936 passes278 represented cases: UI74 execute (including the real JSON-RPC
  handler ingress witness); app-api204 reuse the successful execution within935.
  Run935 failed at UI test compilation (missing throws declaration), so only its
  app-api result is reused. Affected PMD and UI integration-test compilation pass.
- Independent schema mapping identified the advertised tool-surface version obligation:
  McpContractVersions now advertises0.8.0. The runtime compatibility matrix is current;
  its umbrella0.3.0 stays unchanged under the additive/bump-only-on-break policy.
  Registry schemas and their agent emitter baselines are unchanged.
- Commit hook934 flagged the verification map's source hash next to a key-named path
  as a generic API key. It is a SHA256 digest, not a credential; separate path/sha256
  records preserve the evidence and allow the unchanged secret scanner to run.
- Docs928/931–933 and final937–940 regenerate and check the115-entry index,5 generated skills/9 sources
  and156 canonical link sources. The edited MCP reference is not embedded in a skill;
  no derived-file change is produced.

Exact command, task status, counts and source hashes:
[mcp-key-verification.json](mcp-key-verification.json).
Raw logs/counts/XML: `tmp/c2-3-mcp-negative{924,925,929}.txt` (no XML from924),
`tmp/c2-3-mcp{926,927,930,935,936}{.txt,-counts.json,-xml/}` and negative925/929 counts/XML.
Docs output is `tmp/c2-3-mcp-docs{928,931,932,933,937,938,939,940}.txt`.
All paths are under the lane worktree; retain until lane acceptance plus30days and
export before worktree release. They are accessible local artifacts, not Git blobs.
