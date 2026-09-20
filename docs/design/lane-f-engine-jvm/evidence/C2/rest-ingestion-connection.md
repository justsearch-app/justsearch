# C2 d.3b.3b — REST ingestion through the operation owner

Selected 2026-09-20 after prepared-handler checkpoint132b25ec1. This refines
[the owning connection plan](recorded-handler-connection.md); C2 remains open and merge stays at F.

## Mechanism

Keep POST /api/knowledge/ingest as a flat-input alias for core.ingest-files. Reuse the
existing OperationsController invocation body, context/provenance construction, dispatcher,
confirmation/pending authorization and HTTP response mapping. The alias extracts only the
existing invocation controls (idempotencyKey, confirmationToken, preparationNonce); the
remaining public JSON object is the operation's arguments. No new operation, writer, store,
retry state or acknowledgement representation is introduced. ResourceApiModule owns the
alias beside the generic invoke endpoint, so it exists only with the actual operation owner.
KnowledgeSearchController and KnowledgeRoutes cease owning ingestion.

The alias accepts existing paths/optional collection and the three optional controls.
An explicit null collection has the same resolution semantics as an omitted collection;
the operation catalog accepts string or null so the real dispatcher reaches that handler rule.
Preserve explicit JSON null values in the existing immutable invocation argument map;
the operation schema/handler decides whether a value is valid, rather than Map.copyOf
rejecting nullable public JSON before the operation validator. Public arguments exclude
transport controls. Keyed retry matches exactly those public arguments and does no fresh
preparation for an existing row. The operation store remains the only key/outcome authority.

Return OperationInvocationResponse and the existing 428 confirmation body unchanged.
Acceptance is success plus the operation metadata/key; it does not assert a file count or
indexing completion. The read-only GET /api/operation-history/{operationKey} supplies the
durable state and progress. Consumers must retain a key across retries, distinguish
HTTP/transport success from success=false, and preserve confirmation/key/nonce details.
Do not automatically approve a trust gate or turn a refusal into accepted:0.
Both stdio MCP proxies send X-JustSearch-Transport: MCP on the alias, preserving
the protocol's attribution and confirmation policy through the HTTP bridge. The
request context owner applies C1's MCP_CLIENT partition and server-session identity
(mcp-anonymous when unresolved) to the bridge too, ignoring cooperative client-id/kind
hints. Native MCP reads Mcp-Session-Id; HTTP bridges read X-JustSearch-Session-Id.
Mutation-token headers remain.
The production stdio bridge mirrors the native justsearch_operation_outcome read tool
over the keyed HTTP endpoint, and the dev bridge exposes that GET through its existing
api_call allowlist. Both preserve the complete outcome DTO and never retry or start work.
A failed operation is still a successful outcome query. Global index health is not a
substitute for the returned operation's state.

The old accepted/error/scanId response and controller filesystem/config/root fallback loop
are superseded. Remove KnowledgeHttpApiAdapter's ingest/scan entry points. With their sole
producer gone, retire ScanProgressRegistry, its SSE controller/route, and ScanRollupLedger's
live aggregator/composition/schedulers/tests/register entries. Preserve existing persisted
ActionEvent.ScanRollup decoding/rendering: removing a producer does not delete old history.
Ordinary Worker maintenance traversal and indexed-job observations keep their existing owners.
Retire corresponding orphan declarations, exceptions, comments and generated route projections.

This applies the existing rule that an ingress is a projection of one mutation owner.
The evidence is identical operation identity, preparation and refusal across REST and MCP,
including retry after an interrupted response. No generic ingress framework is warranted;
the shared controller path can be retired when either supported HTTP entry point disappears.

## Per-item implementation and proof

1. Backend alias and retirement: extract shared invocation dispatch in OperationsController;
   remove controller/adapter effects and orphan scan observation producers; keep historical
   ledger reads. Preserve loopback/Host/Origin/per-boot mutation-token checks. Test flat JSON
   mapping, null/invalid bodies, context, key/nonce/token propagation, same-key outcome,
   changed-input conflict, confirmation with pending identity, and asynchronous operation
   metadata through an actual HTTP server and registered prepared handler. Prove the
   response key addresses RUNNING progress with monotonic committed units,
   followed by a terminal outcome through GET /api/operation-history/{operationKey}.
   Preserve the optional phase: ordinary ingestion has no phase; C2's existing
   COMPLETE_WITH_GAPS projection supplies awaiting_acceptance. No new phase writer is needed.
   This is polling of durable progress; it makes no scan-counter or cancellation claim.
2. Consumers in the same batch: migrate dev/prod stdio MCP input/output schemas and handlers,
   reference-corpus staging, supervisor-conformance scenarios, jseval/fixture consumers,
   system tests, examples and canonical contracts. Preserve operation responses, eliminate
   accepted-count success guesses, and use keyed retry where a caller retries. Assert
actual HTTP MCP attribution and untrusted confirmation behavior. Verify Node/
   Python schemas and fixture behavior without bypassing shared-stack ownership. Retired
   scan API coverage and route manifests must match the new surface.
3. Run focused regressions, full affected Java suites/PMD/format, applicable Node/Python tests,
   architecture/governance and generated-doc/route checks. Review independently and use
   negative controls for alias/key/failure contracts. Push each per-item/WIP commit and
   continue; all .3b acceptance stays open until callers and teardown are coherent.

Actual resolver -> runner -> SQLite -> coordinator -> Java producer crash/replacement proof
for both operation families remains .3c. Live model, installed and final hosted checks remain
C2 acceptance; these mechanisms do not claim D1 convergence or D2 durable deletion.

## Backend checkpoint (2026-09-20)

The alias, JSON-null preservation, MCP context correction and observer retirement are
implemented. Typed ledger listeners had no remaining production consumer after the scan
aggregator removal, so their secondary fan-out is also retired. Duplicate delivery, durable
journal retry and history projection tests now observe the actual SSE channel; the historical
ScanRollup journal round-trip remains.

Focused1863 passes the alias HTTP tests and selected store/bootstrap/history tests. It caught
and corrected a draft test's wrong PendingAuthorization accessor. The earlier1859 format/build
attempt raced a source rewrite against Gradle input normalization; format now runs separately.
Full1865 executes4,043 cases across app-api (236), app-observability (570), app-services
(2,941) and app-engine (296): zero failures/errors, three existing service skips. All eight
PMD tasks pass. Its source predates the last listener cleanup. Full1870 then reruns all570
observability cases with that cleanup and passes its PMD, but fails UI test compilation on a
missing checked close exception in the new fixture. Root corrects that and removes an unused
fixture field found by1873 PMD; no check is suppressed. Final1875 executes19 HTTP/context
cases across five suites with zero failures/errors/skips, including the actual prepared
handler -> runner -> SQLite -> keyed GET path, live and terminal key-first retry, changed-input
conflict and real MCP confirmation refusal. UI PMD/format pass. This uses a controlled
RecordedIngestionService, not the full Java producer, and therefore does not claim .3c.

Commands/logs: tmp/1865-backend-full.txt, tmp/1870-rest-integrated.txt,
tmp/1873-http-focused.txt, tmp/1875-http-final.txt. Copied suite XML and counts use matching
tmp/1865-,1870-,1873-,1875- prefixes. Retain in this worktree through lane completion plus
30 days, at least2026-10-20. Tested revision is132b25ec1 plus this backend checkpoint.

Consumer migrations and read-only outcome tools are separate .3b.2 work. Live route snapshots,
final focused/full/architecture/negative-control checks, independent review and hosted proof
remain .3b.3. This is a pushed WIP item boundary, not completed .3b or C2 acceptance.

Independent implementation review found the catalog still rejected explicit null before the
handler's inherited nullable-collection rule, despite mocked alias coverage. The catalog now
allows string/null and a real HTTP/dispatcher test covers it. It also found the production
outcome proxy's generic parser discarded typed backend errors. The outcome reader now retains
the complete error body and code, marks a failed query as an MCP error, and validates key shape
before constructing its URL. Node regression checks key-invalid and storage-failed responses
and keeps failed/unknown/expired *operation outcomes* as successful reads.

## Review correction and caller checkpoint (2026-09-20)

Full1876 executes 1,250 UI cases (one existing skip) and 37 launcher architecture
cases, with no failures; system integration-test compilation and PMD pass. After the
nullable schema correction, full1878 executes 692 app-agent and 1,251 UI cases with
zero failures/errors and three existing skips. All four PMD tasks and format pass.
The later historical-rollup edits change comments only. Sources are 093ef4166 plus
the correction/caller commits following it; logs and copied XML/counts are under
tmp/1876-* and tmp/1878-*.

Negative1880 restores string-only collection validation and fails the real HTTP
test at its success assertion. Negative1881 leaves invocation controls in public
arguments and fails the alias's control-exclusion assertion. Negative1882 omits the
MCP transport header and fails the actual stdio proxy HTTP assertion (undefined
instead of MCP). Each mutation restores exact saved bytes in a finally block.
Logs are tmp/1880-null-schema.txt, tmp/1881-alias-controls.txt and
tmp/1882-mcp-transport.txt; 1881 also preserves failing XML. Restored1884 executes
17 HTTP/context cases without failures/errors/skips and passes format. Restored1885
executes all 10 Node caller tests without failures. Full-suite output was copied
before negative runs replaced test results.

The consumer item migrates both MCP proxies, reference-corpus/conformance callers,
system-test inputs, jseval and shell fixtures, mock API receipts, examples and
canonical contracts. Python ui_perf has eight passing cases. Documentation index,
skill projections, MCP-doc sync and canonical links pass. The UI affected-step
lookup reports no steps for the comment-only ActionLedgerClient change; historical
rendering behavior is unchanged. Independent read-only review finds no remaining
substantive implementation defect after the recorded corrections.

The mounted HTTP fixture does not prove full ResourceApiModule/filter composition;
live owner verification and generated-route capture remain .3b.3. The full producer,
restart/replacement, installed/model and final hosted acceptance remain required.
Evidence retention follows the backend checkpoint above. This checkpoint does not
close .3b or C2 and authorizes no earlier merge placement.

Full-build1887 prerequisite: build -x test also runs UI integrationTest. Its old
LocalApiServer fixture omits the C2 recorded settings service, so real settings and
pack-path writes refuse with SETTINGS_RECOVERY_REQUIRED/PACK_APPLY_FAILED. Migrate
the fixture to the existing SQLite/runner/SettingsCommitCoordinator composition
used by current HTTP tests; retain persistence and policy assertions, and close the
owned store after the server. No production fallback or writer bypass is introduced.
The Windows machine-policy case also skips because Gradle retains inherited
ProgramData beside its PROGRAMDATA override. Normalize that single environment key
before installing the sandbox; the guard against real machine-policy writes remains.
Original logs/XML are tmp/1887-full-build.txt and tmp/1887-integration-xml/ui.
Corrective1891 proves all nine cases execute on Windows (no skips), but still fails
the two writes: the public prod fixture also needs the C2 witness/key request, and
CoreApiAssembly's no-HeadAssembly branch did not forward the explicitly injected
settings service to its AI helpers. Pass that existing service into their existing
constructors; the normal HeadAssembly branch retains its process-owned instances.
This repairs composition, without creating another settings owner.
Full1893 build -x test now passes, including all nine UI integration cases with no
failures/errors/skips and PMD. It proves the original prod token/persistence and
machine/user policy contracts against the recorded settings fixture. Logs and copied
XML: tmp/1893-full-build.txt and tmp/1893-integration-xml/ui. No dependencies changed.
Independent source review finds no actionable defect in the fixture, helper forwarding
or environment normalization. Full1900 reruns all 1,251 UI cases (one existing skip,
zero failures/errors) with the final helper wiring; copied XML/counts are tmp/1900-*.
