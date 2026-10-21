---
title: "Project operations: cross-platform contributor onramp, contract lifecycle signals, succession, and diagnostic handoff"
type: tempdocs
status: "D1-D6 REPOSITORY DELIVERABLES IMPLEMENTED, VERIFIED, AND PUBLISHED; D2 ISSUE DRAFTS REMAIN FOUNDER-OWNED (2026-09-04)"
created: 2026-09-02
updated: 2026-09-04
lane: 887 L17
model: opus (takeover)
parent: 887-improvement-landscape-register
related:
  - 656-five-minute-agent-runtime-onramp   # doctor, tiered onramp, CONTRIBUTING sections
  - 631-go-public-publish-machinery / 633-go-public-launch-content / 634-cutover
  - 654-local-runtime-contract-and-product-center / 655-mcp-conformance-and-capability-policy
  - 660-plugin-sdk-community-onramp        # open since 2026-06-28, unstarted
  - 802-release-artifact-provenance        # SPDX/step-3 owner decision pattern
---

# 899 — Project operations

## Briefing for the agent picking this up

Fresh start. Read this file and 887 Appendix A10 (§10.1, 10.2, 10.5, 10.6) + A3 §3.6. Work in a
worktree. Root community files (`CONTRIBUTING.md`, `MAINTAINING.md`, `SUPPORT.md`,
`.github/`) are public-facing: `check-root-readme` and the docs-lint checks apply. Anything
outward-facing that is not a file in this repo (creating GitHub labels/issues, npm publish) is
**drafted here for the founder**, not executed. Six immediate PRs plus one conditional generated-
package PR.

## Thesis

Contributor onboarding is real but Windows-only and agent-shaped (656): no devcontainer, a
PowerShell-only bootstrap, and a live `good first issue` label with no curated issue list. Bus factor is
one name in CODEOWNERS with no succession document. The runtime contract has a 90-day
deprecation policy but no on-wire signal — a client learns of a removal by reading a doc. There
is no runtime-contract client SDK in any language, and 660 has sat open since June.

## Settled scope (supersedes the initial charter assumptions recorded in §Status)

- **Devcontainer:** `.devcontainer/` on Ubuntu 24.04 with JDK 25 (Temurin), Node 24, Python 3.13,
  and Rust stable; `postCreate` runs the cross-platform bootstrap and doctor. Tier-0 keyword search
  must work inside it. This is a contributor environment, not Linux product-support expansion; no
  GPU expectation and no claim that existing Ubuntu CI proves the child-process smoke.
- **Bootstrap:** add a Node `scripts/setup/bootstrap.mjs` as the post-Node coordinator, reusing the
  JDK and platform-command mechanics already in `scripts/dev/lib/resolve-jdk.cjs` and
  `scripts/dev/prepare-worktree.cjs`. Keep the `.ps1` as the Windows pre-Node implementation.
- **On-wire deprecation:** HTTP — RFC-correct `Deprecation`, optional `Sunset`, and documentation
  `Link` headers projected from `RouteContractPolicy`, plus manifest/OpenAPI projections and CORS
  exposure. MCP — namespaced Tool `_meta`, description fallback, and a versioned experimental
  capability projected from `McpToolSurface`; standard annotations remain untouched. Focused
  closed-world contract/snapshot tests replace the unrelated protobuf `wire` gate.
- **SDK:** decision — **TypeScript first and generated**. First land a six-operation, read-only,
  Node-native public-contract projection with complete schemas and a source/snapshot coherence gate;
  then generate `@justsearch/runtime-client` conditionally. Do not hand-write endpoint methods or
  treat `/mcp` as a generic OpenAPI JSON-RPC binding.
- **Crash reporting:** no submit path (NON-GOALS). Instead: a "Copy diagnostic summary" action
  that puts an allowlisted, size-bounded Head-local summary on the clipboard for pasting into a
  GitHub issue — user-initiated, no network, no log/ZIP reuse, and no payload-bearing effect journal.

## Scope

1. Devcontainer + bootstrap + CONTRIBUTING update; verify the Tier-0 onramp
   (`scripts/dev/test-onramp-first-success.mjs`) inside the container (Codespaces or local).
2. Five curated starter issues drafted in §Status with file pointers and acceptance criteria; the
   founder opens them using the existing `good first issue` label. No inert label-sync file.
3. On-wire deprecation (HTTP headers/OpenAPI/CORS + MCP extension + docs + tests).
4. `MAINTAINING.md` §Succession skeleton: what a second maintainer needs (signing credential
   mode, release workflow secrets list by *name* only, npm/GitHub org roles, model-registry
   upstream accounts, the private cutover package's existence). Placeholders for values only the
   founder knows.
5. "Copy diagnostic summary" action (typed Head-local allowlist + non-journaling clipboard path) +
   the bug-report template field.
6. SDK prerequisite: HTTP contract authority, canonical public schemas, SDK-filtered OpenAPI
   snapshot, and non-vacuous source-to-snapshot gate. Generated package follows only after this is
   green and the generator bakeoff passes.

## Acceptance criteria

- Item 1: container builds; onramp test green inside it; link the run.
- Item 3: focused lifecycle, manifest/OpenAPI, CORS, MCP, and doc-sync checks green; orphaned and
  duplicate lifecycle rows fail; a deprecated test route emits the correct applicable headers.
- Item 5: ui-web gates + typecheck/tests; hostile values cannot escape the allowlist or enter the
  journal/persistence/export surfaces, clipboard failure is visible, and 297 redaction coverage is
  extended independently.
- Item 6: every v0.1 SDK operation has an operation id, exact request/query shape, every observed
  success/lifecycle/error status, schema, and enforcement-derived security projection; the committed
  SDK OpenAPI projection is self-contained and byte-stable with the in-process route source;
  executable handler tests validate every declared status/body pair against its schema.
- `node scripts/ci/check-root-readme.mjs` and docs-lint green.

## Constraints

- No outward actions (labels, issues, npm publish, secrets) — draft only.
- Non-goals: KPI/telemetry (NON-GOALS), SDK implementation before typed public-contract coverage
  and source/snapshot coherence exist, Linux product support, per-client MCP identity.

## Status

### Takeover investigation and verdict (2026-09-03)

The charter identifies real project-operability gaps, but four premises changed or were too loose:

1. The live GitHub repository already has the standard `good first issue` label (and no open
   issues). A `.github/labels.yml` file would have no consumer: GitHub does not apply that file by
   itself, and this charter does not add a label-sync action. Do not add dead configuration. Draft
   five issues here and use the existing label when the founder opens them.
2. MCP protocol `2025-11-25`, which this runtime pins, does **not** define `deprecated` or `sunset`
   in `ToolAnnotations`. Its closed standard fields are `title`, `readOnlyHint`, `destructiveHint`,
   `idempotentHint`, and `openWorldHint`. Putting lifecycle fields there would make JustSearch look
   standard while actually relying on an extension.
3. The future 893 OpenAPI snapshot is necessary but insufficient for a generated SDK. The current
   route snapshot has 201 routes, only 7 with a response schema (3.5%), and no request-body
   descriptions. Generating now would produce a large, weakly typed client and falsely imply a
   supported HTTP surface much broader than the deliberately small runtime contract.
4. There is no "658 bundle index" to reuse. The diagnostics ZIP is built directly by
   `DiagnosticsServiceImpl`; it includes free-form logs and crash messages/stacks. The existing
   path scrubber has no direct regression test and is not a safe source for clipboard text. The
   clipboard summary must be constructed from an explicit field allowlist.

**LITE-CLASS: no.** This work introduces new cross-platform, HTTP, MCP, service, and frontend
behaviour; it is not pure teardown/rename/config deletion.

**Verdict: GO now, with the corrected scope below.** Implement the devcontainer/onramp, issue
drafts, succession skeleton, HTTP/MCP lifecycle authorities, allowlisted diagnostic summary, and
the SDK contract-projection prerequisite. Generate the SDK only after that projection and its
coherence gate are green. The trigger is not merely "893 item 2 landed"; it is "the committed SDK
OpenAPI projection completely describes its enumerated operations and is proven coherent with the
in-process route source." Keep the wider plugin-authoring onramp in 660 distinct from this runtime
client.

The cheapest decisive evidence is already partly present:

- **Need:** no `.devcontainer/`, no cross-platform setup coordinator, no succession section, no
  lifecycle metadata in HTTP/MCP, and no clipboard diagnostic summary exist on this base.
- **HTTP/MCP shape:** one fake deprecated route and one fake deprecated tool can prove positive
  projections without a live stack; deliberate duplicate, orphan, and misspelling fixtures prove
  the catalogs fail closed.
- **SDK no-go:** the 3.5% response-schema coverage and absent request bodies already invalidate a
  useful generated client; no speculative generation experiment is required.
- **Container:** one `devcontainer build` followed by the Tier-0 onramp is still required. This host
  has neither Docker nor the `devcontainer` CLI, so that proof does not exist yet.
- **Diagnostic privacy:** a hostile fixture containing absolute paths, exception messages, tokens,
  and oversized values should produce only allowlisted, bounded output. This evidence does not
  exist yet and is the highest-value pre-merge negative test.

What this displaces or extends:

- `bootstrap.mjs` becomes the cross-platform **post-Node coordinator**; it does not delete or hide
  `bootstrap-node-win.ps1`, which remains the Windows pre-Node entry point.
- `RouteContractPolicy` follows the existing `RouteCapabilityPolicy` seam and supersedes both the
  current `RouteResponseSchemas` map and the previously proposed, unimplemented
  `RouteLifecyclePolicy`. Existing response-schema rows migrate into it. The live route manifest,
  full structural OpenAPI, and SDK-filtered OpenAPI are projections, not new authorities.
- MCP lifecycle metadata extends `McpToolSurface`'s catalog and names itself as a JustSearch
  extension; it does not misuse standard `annotations`.
- The diagnostic action extends `DiagnosticsService`, `CoreOperationCatalog`, the existing Help
  surface operation mount, and the existing clipboard utility, but it must not put the summary in
  the payload-bearing persistent Effect journal. It adds one awaitable, non-payload-journaling copy
  action rather than a parallel raw fetch/button path.
- `.github/labels.yml` is deleted from the design because it would duplicate live GitHub state with
  no synchronizer.

### Research pass (2026-09-03)

Internet research was warranted because the HTTP deprecation RFC is recent, MCP changed protocol
eras in 2026, and devcontainer feature availability is live infrastructure. Only primary standards
and upstream project sources were used; no external code or text is copied into the repository.

| Subject | Finding | Design consequence |
|---|---|---|
| HTTP deprecation | [RFC 9745](https://www.rfc-editor.org/rfc/rfc9745.html) defines `Deprecation` as an RFC 9651 Structured Field Date, e.g. `@<epoch-seconds>`; `Deprecation: true` is not the final RFC syntax. | Correct `docs/reference/contracts/api-evolution.md`, which currently says `Deprecation: true`; store an instant, not a boolean-only marker. |
| HTTP sunset | [RFC 8594](https://www.rfc-editor.org/rfc/rfc8594.html) says `Sunset` is the HTTP date at which a URI is expected to become unresponsive. Deprecation does not automatically imply a known sunset. | `sunsetAt` is optional. Emit `Sunset` only when a removal date is committed; validate it is after `deprecatedSince` and respects the promised window for public-contract routes. |
| Browser visibility | The [Fetch Standard](https://fetch.spec.whatwg.org/#cors-safelisted-response-header-name) does not CORS-safelist `Deprecation`, `Sunset`, or `Link`. | Add those names to `Access-Control-Expose-Headers` for already-allowed origins, with positive and negative security-filter tests. Do not alter Host or Origin admission. |
| OpenAPI | [OpenAPI 3.1](https://spec.openapis.org/oas/v3.1.0) has standard `deprecated: true` and `externalDocs` on an Operation Object and permits `x-*` specification extensions. | Project the standard fields plus `x-deprecated-since`, optional `x-sunset`, and `x-justsearch-replacement` from the route lifecycle authority. |
| MCP 2025-11-25 | The official [schema reference](https://modelcontextprotocol.io/specification/2025-11-25/schema) has no lifecycle fields in `ToolAnnotations`; Tool `_meta` is open, and server capabilities include a namespaced experimental extension area. | Put structured lifecycle data in namespaced Tool `_meta`; advertise the extension in `capabilities.experimental`; add a short description prefix as the human/model fallback. |
| MCP currency | MCP `2026-07-28` removed the initialize handshake in its new stateless era ([upstream changelog](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/specification/2026-07-28/changelog.mdx)). | Do not upgrade protocol versions in 899. Design against the repository's pinned `2025-11-25`; a future protocol migration owns how the extension is rediscovered without initialize. |
| Devcontainers | Official Features support Java, Node, Python, and Rust; the Java feature accepts a distribution selector. Repository CI currently pins Python 3.13 and Node 24. | Use Ubuntu 24.04 plus official versioned Features: Temurin 25, Node 24, Python 3.13, Rust stable. Pin Feature major versions and let 893's platform-EOL register own currency. |
| GitHub intake | Read-only `gh label list` on 2026-09-03 found `good first issue` (`7057ff`, "Good for newcomers"); `gh issue list --state open` returned none. | Reuse the live label; draft issues only. Re-check immediately before the founder opens them. |

### Settled design

#### D1. Contributor onramp: a reproducible container plus an honest bootstrap boundary

Add `.devcontainer/devcontainer.json` on Ubuntu 24.04 with official Features for Temurin JDK 25,
Node 24, Python 3.13 (matching CI), and stable Rust. This is an all-repository, CPU-only contributor
environment, not a newly supported Linux product/runtime platform: no GPU feature, model mount,
model download, packaged-app promise, or Tier-1/2 promise. Preserve the canonical Windows-only
product scope unless a separate decision deliberately changes it. `postCreateCommand` runs the
cross-platform bootstrap coordinator and then `node scripts/dev/doctor.mjs`; the terminal message
points to the Tier-0 proof.

`scripts/setup/bootstrap.mjs` is intentionally a **post-Node** coordinator. A Node program cannot
install Node on a machine that has no Node runtime. It composes or extracts the portable mechanics
already owned by `scripts/dev/lib/resolve-jdk.cjs` and `scripts/dev/prepare-worktree.cjs`; it must not
duplicate their JDK discovery or platform-correct command selection, and must not invoke the whole
maintainer-oriented worktree preparer because that also seeds maintainer configuration. It:

- validates the supported Node/JDK/Python core prerequisites and prints one actionable failure at a
  time; Rust is present in the all-repository container but is not a core-bootstrap failure for
  contributors who are not working on the Rust/Tauri shell;
- installs repository-local JavaScript dependencies with the lockfile-preserving command already
  used by CI, and verifies the Gradle wrapper is executable on Unix;
- selects platform-correct wrapper spellings (`gradlew.bat` on Windows, `./gradlew` elsewhere);
- supports a non-mutating `--check` mode for tests and diagnosis;
- never chooses a host package manager or edits a user's shell profile.

Keep `bootstrap-node-win.ps1` as the Windows **pre-Node** entry point and fix its version parsing/
failure reporting while it is touched. Native Linux/macOS users either bring a supported Node
runtime themselves or use the devcontainer; do not pretend a Node script solved the pre-Node
bootstrap problem.
`CONTRIBUTING.md` presents Windows and devcontainer paths side by side and uses platform-neutral
Gradle command notation where possible. The container pins Node 24 and JDK 25 for reproducibility,
while native bootstrap validation requires the contributor Node 24+ and JDK 24+ floors.

Verification: build the container, assert all four tool versions, run `bootstrap.mjs --check`, run
the doctor, and execute `scripts/dev/test-onramp-first-success.mjs` inside the container. Record the
Codespaces or local-container run link/output in this section. The smoke must report Tier 0 and a
real keyword result without models or GPU. This is a blocking acceptance gate: current Ubuntu CI
proves assembly/unit coverage, not Linux child-process integration, and the existing onramp smoke is
Windows-only.

#### D2. Community intake: use live labels; publish five bounded issue drafts

Do not create `.github/labels.yml`. The founder opens the following only after a last-minute
ownership/staleness check and applies the existing `good first issue` label. Each issue must state
that architectural invariants and generated files are out of scope.

| Draft title | Starting pointers | Acceptance boundary |
|---|---|---|
| Add `--help` and unknown-option handling to the onramp doctor | `scripts/dev/doctor.mjs` | Establish a side-effect-free main guard/CLI test seam; `--help` exits 0 without probing hardware/files; an unknown option exits nonzero with usage; focused Node tests cover both. |
| Unit-test the onramp tier decision matrix (after the doctor CLI seam lands) | `scripts/dev/doctor.mjs` (`deriveTier`) | Reuse the first issue's main guard/export seam. Cover no artifacts, embedding only, full artifacts with and without verified GPU, and contradictory inputs; production output stays unchanged. Extract only the smallest pure helper needed for testing. |
| Expand URL-grammar fixtures for scalar, array, and enum arguments | `scripts/ci/url-grammar-fixtures/generate.mjs:137`, adjacent README/fixture | Add the missing small deterministic sampler; do not claim an existing schema walker. First correct the generator prose that currently overclaims these variants and the README command that names `MarkdownTextExtractorConformanceTest` instead of the real `MarkdownUrlExtractorConformanceTest`. Cover the named shapes for both operation `inputSchema` and surface `stateSchema`, then pass existing parser/fixture checks. No URL grammar redesign. |
| Add one fabricated non-Markdown document to the Tier-0 onramp corpus | `examples/onramp-corpus/`, `scripts/dev/test-onramp-first-success.mjs` | Add a license-clean plain-text or HTML fixture with a unique token and prove it is indexed/searchable in the existing smoke without models. Do not broaden the extraction matrix. |
| Convert the feature-request template to a validated GitHub issue form | `.github/ISSUE_TEMPLATE/feature_request.md`, `CONTRIBUTING.md`, `NON-GOALS.md`, `SUPPORT.md` | Required problem/alternative fields, scope reminder, no private data prompt, valid YAML, old Markdown template removed, and both direct template links updated without changing security-report routing. |

These drafts are intentionally documentation/tooling/test tasks with tight seams. The tier-matrix
issue is sequenced after the CLI seam to prevent two contributors creating competing main guards.
If any becomes owned by another lane before opening, replace it rather than creating duplicate work.

#### D3. One HTTP contract authority, projected to HTTP, OpenAPI, and lifecycle signals

**HTTP.** Add `RouteContractPolicy`, parallel to `RouteCapabilityPolicy`, keyed by method + route
pattern. It is the machine-readable HTTP contract authority for the deliberately small set of routes
that need more than structural enumeration. A row carries stability class, optional SDK exposure and
stable `operationId`, request/query schema references, status-to-response-schema mappings, security
projection reference, and an optional lifecycle block (`deprecatedSince`, optional `sunsetAt`,
replacement, and documentation URI). Security metadata must call or project the same predicates
used by `ApiSecurityFilters`; copied labels would become a second trust-boundary authority. Migrate
every `RouteResponseSchemas` entry into this registry and delete that superseded map in the same
change; do not copy it into a third table.

A pre-1.0 lifecycle exception is an explicit row field containing its rationale and decision-
document URI, never an implicit bypass. Construction validates chronology, requires the exception
record when a public-contract sunset is inside the 90-day floor, and rejects exception records on
other rows. Canonical prose remains the authority for routes with no contract row until a broader
classification projection is justified. The route manifest adds the applicable fields and bumps its
schema version. OpenAPI projects standard `deprecated: true`, standard `externalDocs`,
`x-deprecated-since`, optional `x-sunset`, and an explicitly named
`x-justsearch-replacement` extension.

A single Javalin matched-route filter reads that policy and stamps responses. `Deprecation` uses the
RFC 9745 `@epochSeconds` syntax. `Sunset` uses an IMF-fixdate and is absent when no shutdown date is
committed. Add a `Link: <...>; rel="deprecation"` when documentation is present. Tests use a fake
deprecated route; do not deprecate a production route merely to exercise the mechanism. Expose
`Deprecation`, `Sunset`, and `Link` through CORS for already-allowed origins, with tests proving the
existing Origin and Host rejection paths remain unchanged.

**MCP.** Add a small lifecycle catalog beside the six curated `McpToolSurface` declarations, keyed
by tool name and carrying the same conceptual fields. `tools/list` projects lifecycle data under
top-level namespaced `_meta` keys (`io.justsearch/deprecated`, `io.justsearch/deprecatedSince`,
`io.justsearch/sunsetAt`, `io.justsearch/replacement`) and prefixes the description with a concise
deprecation sentence so clients that discard `_meta` still expose the warning to the model/user.
The initialize result advertises a versioned `io.justsearch/tool-lifecycle` experimental capability
obtained from the surface, keeping protocol transport ignorant of tool details. Standard
`annotations` remain unchanged.

Do not extend the protobuf `wire` gate for this. It governs `contracts/wire` protobuf evolution.
Instead, add focused route-lifecycle and MCP lifecycle contract tests and extend the relevant
snapshot/regen checks. 893's full structural OpenAPI snapshot remains useful for route inspection,
but S1's SDK-filtered snapshot and in-process comparison are the public-client drift guard. An
actual deprecated tool requires a tool-surface version change; adding the empty
capability mechanism alone does not. Both lifecycle catalogs are closed-world: construction or a
contract test must reject duplicates and orphaned/misspelled keys, require each entry to resolve to
exactly one live route/tool, require every SDK `operationId` to be nonblank and globally unique, and
prove each deprecated entry appears in every required projection. Add a deliberate duplicate-
operation-id fixture as well as duplicate/orphan route keys.

Update `docs/reference/runtime-contract.md`, `docs/reference/contracts/api-evolution.md`, and
`docs/reference/mcp-production-server.md`. The docs must distinguish standard HTTP fields, standard
OpenAPI metadata, and the namespaced JustSearch MCP extension.

#### D4. Succession is a public recovery map, never a secret dump

Add `MAINTAINING.md` §Succession and emergency handover with a table for responsibility, service,
minimum successor role, credential mode, repository secret **name**, recovery/rotation procedure,
and owner placeholder. Link to `docs/how-to/cut-a-release.md` as the single durable release
procedure rather than duplicating its steps. Include:

- GitHub organization ownership, repository administration, rulesets/merge queue, release access,
  and the separate `justsearch-releases` asset repository;
- future/unprovisioned npm organization/package ownership for `@justsearch/plugin-api`
  (publication remains separately gated by 660);
- code-signing mode/provider and the names already consumed by workflows:
  `JUSTSEARCH_CODESIGN_MODE`, `JUSTSEARCH_CODESIGN_PFX_B64`,
  `JUSTSEARCH_CODESIGN_PFX_PASSWORD`, `JUSTSEARCH_CODESIGN_THUMBPRINT`,
  `JUSTSEARCH_CODESIGN_TIMESTAMP_URL`, and `JUSTSEARCH_CODESIGN_COMMAND`;
- release/update keys by name only:
  `JUSTSEARCH_RELEASE_METADATA_PRIVATE_KEY_PEM`, `JUSTSEARCH_TAURI_UPDATER_PRIVATE_KEY`, and
  `JUSTSEARCH_TAURI_UPDATER_PRIVATE_KEY_PASSWORD`;
- release/update repository variables by name only:
  `JUSTSEARCH_UPDATE_ARTIFACT_PUBLIC_KEY`, `JUSTSEARCH_UPDATE_ARTIFACT_KEY_ID`,
  `JUSTSEARCH_RELEASE_METADATA_PUBLIC_KEY_PEM`, `JUSTSEARCH_RELEASE_METADATA_ROOT_PUBLIC_KEY`,
  `JUSTSEARCH_RELEASE_METADATA_ROOT_KEY_ID`, and `JUSTSEARCH_RELEASE_DESCRIPTOR_URL`;
- CLA administration (`PERSONAL_ACCESS_TOKEN` as an optional compatibility path, not a current
  requirement), upstream model accounts/terms sources, and the
  existence/location class of the private cutover package without copying its contents.

Every unknown value is a plainly marked founder placeholder. Add a twice-yearly dry-run checklist:
a second maintainer can identify each account, rotate one non-production credential, run the
existing non-tag branch candidate dispatch, and locate the private recovery package. No credential
values, recovery codes, personal emails, account IDs, or private URLs enter Git.

#### D5. Copy diagnostic summary: allowlist construction plus a non-journaling clipboard action

Extend `DiagnosticsService` with a bounded text-summary method implemented in app-services. The
summary is built from typed/structured sources, not from the ZIP, logs, stack traces, exception
messages, environment dumps, or generic regex redaction. Allow only:

- JustSearch build version and runtime-contract constituent versions;
- OS family/version/architecture and JVM version;
- lifecycle state + stable reason codes for Head/Worker/Inference;
- GPU vendor/model and capability tier, with no serial/UUID/device path;
- latest crash timestamp/process/exception **type** only, if a parseable crash report exists;
- a fixed note saying the summary was generated locally and copied only by the user's action.

Use deterministic field order, replace control characters, cap each value and the whole UTF-8
payload (8 KiB target), and omit unknown fields rather than copying arbitrary fallback text. The
service returns the text through a new low-risk, user-audience, **Head-local** core operation that
requires neither Worker nor Inference availability. Test it with both offline and with absent or
malformed optional snapshots. The Help surface mounts that operation next to Export diagnostics;
on success it invokes the existing clipboard utility through a new awaitable action that never
places the text in the persistent Effect journal. Journal only an opaque event (operation id,
outcome, timestamp) if auditability is required, and render success only after clipboard writing
returns true. No network request leaves loopback and no automatic issue opening is added.

Update the bug-report template with a fenced "Diagnostic summary (optional)" field and an explicit
instruction to review before posting. Tests must inject hostile absolute paths, messages, secret-like
strings, control characters, and oversized values and prove none escape. Frontend tests must also
prove the summary never enters journal memory, local storage, effect archives, logs, or receipts,
and that a failed clipboard write cannot produce a success receipt. Separately extend 297's ZIP
redaction regression coverage while this privacy seam is open: the current implementation has no
direct test, and its Windows regex is not sufficient evidence for paths containing spaces or other
edge cases.

#### D6. SDK decision: stage a small Node-native runtime client behind its contract projection

The first runtime client is `@justsearch/runtime-client`: TypeScript, ESM, Node 20+ at runtime, and
generated endpoint methods. It is separate from `@justsearch/plugin-api`: one talks to the local
Head HTTP contract from a same-user native process; the other authors in-process extensions. Reuse
the plugin package's metadata/exports/files/prepublish shape only after correcting its unproven
ESM/CommonJS assumptions; do not inherit its contract version or publication status.

**v0.1 allowlist — six read-only JSON operations:**

1. `GET /api/runtime/manifest`
2. `GET /.well-known/justsearch/manifest.json`
3. `GET /api/runtime/ready`
4. `GET /api/runtime/live`
5. `GET /api/health`
6. `GET /api/status`, exposing only the stable lifecycle subset and treating undeclared response
   properties as unknown/additive; the reference-client `fresh` query is not in v0.1.

The package accepts an injected `fetch` and a runtime base URL. A small hand-written factory may
validate that the URL is loopback, configure transport defaults, and check the advertised runtime-
contract compatibility range; it must not contain hand-written endpoint paths, serialization, or
response types. The generated methods preserve status distinctions: readiness/health `503` bodies
are typed lifecycle results, not collapsed into a generic thrown error. Model the global Host
rejection separately from true application failures. Before declaring an application-error schema,
route the manifest's current raw-exception `500` through the existing sanitizing `ApiErrorHandler`;
add a hostile exception/path fixture proving no raw message escapes.

**Explicit v0.1 exclusions:**

- `POST`/`DELETE`/`GET /mcp`: use the official MCP TypeScript SDK; one OpenAPI operation cannot
  faithfully represent the stateful JSON-RPC protocol.
- `GET /api/mcp/token`: no v0.1 operation mutates state or calls MCP, so the credential bootstrap is
  unnecessary and remains outside the runtime-client promise. Reclassify it only with a future
  native mutation/MCP client design.
- `HEAD /api/runtime/ready` and `/live`: implementation conveniences not named by the canonical
  public-contract table.
- `GET /api/runtime/manifest/stream`: remains public-contract but enters a later runtime-client
  version only after a generated SSE transport proves reset/resume semantics, cancellation, and
  typed frames. v0.1 is explicitly the snapshot/probe subset, not the entire runtime contract.

**Two implementation phases clear the no-go:**

- **S1 — contract projection (start now):** implement `RouteContractPolicy`; add canonical schemas
  for the public manifest, probe bodies, lifecycle snapshot/status subset, Host rejection, and
  sanitized application failures; normalize the manifest's unsafe `500`; generate a self-contained
  SDK-filtered OpenAPI 3.1 snapshot (bundled components or checked-in relative references, never
  runtime `/api/schemas/...` references) from the same in-process Javalin registration used by
  `OpenApiController`; and add a Java test that compares that projection byte-for-byte with the
  committed snapshot. Separately, invoke every declared route outcome and validate its actual
  serialized body/status against the declared schema. Snapshot equality closes projection drift;
  executable handler tests close policy-versus-behavior drift.
- **S2 — generated package (conditional):** after S1 is green, pin the selected generator exactly,
  generate `packages/runtime-client`, and add deterministic regen, TypeScript build/typecheck,
  package-content, Node 20 runtime, mocked-fetch contract, and live compatibility smokes. Package
  SemVer is independent; it declares the runtime-contract versions it supports rather than copying
  the desktop application version. npm publication remains a founder action.

The current `apiRoutes.ts` generator remains the shell's internal typed route table; it is not
renamed or exported as the SDK. The current `RouteResponseSchemas` map is retired into
`RouteContractPolicy`. LangChain/LlamaIndex adapters remain 660 follow-ons after one generated
runtime client has real users.

#### Focused SDK research and generator decision (2026-09-03)

Research was warranted because TypeScript OpenAPI generators and their runtime/SSE support changed
in 2026. No external code was copied. The design uses an evidence-gated selection rather than
committing a dependency from feature lists alone:

- OpenAPI Generator 7.25 is Apache-2.0 and offers a stable `typescript-fetch` generator, but its own
  compatibility table still labels OpenAPI 3.1 support beta and the generator feature matrix has
  material gaps ([upstream compatibility](https://github.com/OpenAPITools/openapi-generator/blob/master/README.md),
  [TypeScript Fetch matrix](https://openapi-generator.tech/docs/generators/typescript-fetch/)).
- `openapi-typescript` supports OpenAPI 3.1 well, but its maintainers put `openapi-fetch` into
  maintenance mode in the 2026 roadmap. Use it only as a type-generation comparator, not as the
  runtime-client choice ([upstream roadmap](https://github.com/openapi-ts/openapi-typescript/discussions/2559)).
- Hey API's generator is MIT-licensed, generates Fetch SDKs and SSE paths, and is the front-runner,
  but it is pre-1.0 and its generator requires Node 22.18+. Pin an exact version, run generation only
  under the Node 24 contributor/CI toolchain, and prove the generated package itself still runs on
  Node 20 ([upstream package](https://github.com/hey-api/openapi-ts),
  [release history](https://github.com/hey-api/hey-api/releases)).
- Orval is the fallback comparator: MIT-licensed, stable major, native Fetch output, but also
  requires Node 22.18+ and has less decisive evidence for this SSE contract
  ([upstream Fetch docs](https://orval.dev/docs/guides/fetch/)).

Before S2 chooses a generator, run the same checked-in contract fixture through the front-runner and
fallback. The winner must produce deterministic ESM, compile/run on Node 20, accept a runtime base
URL and injected fetch, preserve `200` versus typed lifecycle `503` versus error envelopes, support
OpenAPI 3.1 nullable/reference shapes, and generate fully offline from the self-contained snapshot.
Validate that snapshot offline before either generator runs. The generator version and config become
reviewed inputs to the regen gate; generated output is committed.

#### Active SDK implementation plan (2026-09-03)

This is the active implementation scope for the present worktree. It completes D6 S1 and S2 end to
end; the contributor-container, community-intake, lifecycle-header/MCP, succession, and diagnostic-
clipboard workstreams remain separate changes because they do not gate the six-operation client.

- [x] **Establish the contract authority.** Add an immutable `RouteContractPolicy` keyed by HTTP
  method and route pattern, with stable operation ids, SDK exposure, request/query declarations,
  status-to-schema declarations, and security metadata derived from `ApiSecurityFilters`. Validate
  duplicate keys, duplicate operation ids, orphaned routes, and incomplete SDK rows. Migrate every
  `RouteResponseSchemas` row and delete the superseded class in this same change.
- [x] **Make the six public responses schema-complete and safe.** Add canonical schemas for the
  public runtime manifest (without the session token), ready/live probes, lifecycle health/status
  subset, Host rejection, and sanitized application failures. Route manifest serialization failures
  through `ApiErrorHandler`; prove hostile exception messages and paths cannot escape.
- [x] **Project a self-contained SDK OpenAPI document.** Extend the existing in-process Javalin route
  enumeration rather than maintaining a second route list. Produce only the six allowlisted GET
  operations, stable operation ids, exact declared statuses, enforcement-derived security details,
  and bundled local component schemas. Keep the existing full structural OpenAPI and shell
  `apiRoutes.ts` projection intact.
- [x] **Close both drift classes.** Add a byte-for-byte source-to-committed-snapshot test and a
  deterministic regeneration command. Separately execute each declared route outcome and validate
  its status and serialized body against the policy's schema; include lifecycle `503`, Host `403`,
  sanitized application failure, and public-projection negative fixtures.
- [x] **Select and pin the generator.** Run the checked-in snapshot through exact versions of Hey API
  and Orval using the same fixture. Record the winner from deterministic ESM output, injected-fetch
  and runtime-base-url support, typed non-2xx bodies, OpenAPI 3.1 shape fidelity, offline generation,
  and Node 20 runtime compatibility. Remove the losing bakeoff artifacts and dependency.
- [x] **Ship `@justsearch/runtime-client`.** Add `packages/runtime-client` with committed generated
  endpoint methods and types plus only a small hand-written loopback/transport/contract-version
  factory. Keep package SemVer independent of the application and do not expose endpoint paths or
  response types from hand-written code.
- [x] **Wire release-quality gates.** Add offline OpenAPI validation, deterministic client regen,
  TypeScript build/typecheck, packed-file inspection, mocked-fetch status/body tests, and a Node 20
  runtime smoke to the owning governance/CI path. Run focused Java and Node checks, then the
  repository-required build and affected tests once the shared Gradle lease is clear.
- [x] **Prove live compatibility and close out.** Start an owned development stack, call all six
  generated operations through the package (including a normal lifecycle-unavailable outcome where
  feasible), verify the runtime-contract compatibility check, stop the owned stack, update canonical
  runtime-contract/contributor documentation and this evidence ledger, perform a refute-first review,
  and commit explicit paths without publishing or opening a PR.

#### Focused SDK implementation evidence (2026-09-03)

- `RouteContractPolicy` now supersedes `RouteResponseSchemas`. The six SDK rows carry stable
  operation ids, exact status/schema maps, explicit empty query shapes, and security projected by
  `ApiSecurityFilters.contractSecurity`; duplicate routes/operation ids and orphaned SDK rows fail
  closed. The existing nine internal schema rows moved into the same policy.
- Five canonical response schemas now cover the public manifest, readiness, liveness, lifecycle
  minimum, and ordinary API errors. Real HTTP tests validate manifest/mirror `200`/`503`, readiness
  `200`/`503`, liveness `200`, health `200`/`503`, status `200`, Host rejection `403`, and a hostile
  manifest serialization failure `500`. The last case confirms that exception text, a token-like
  value, and a private path do not reach the response.
- `generateRuntimeClientOpenApi` registers the production `RuntimeApiRoutes` and the lifecycle
  registration seam, then emits a self-contained OpenAPI 3.1 snapshot containing exactly six GET
  operations and bundled schemas. `SdkOpenApiProjectionTest` compares that output byte-for-byte
  with the committed snapshot and rejects internal/MCP/token/SSE leakage.
- Generator bakeoff used the same committed snapshot. `@hey-api/openapi-ts@0.99.0` plus its exact
  TypeScript peer failed on the valid self-contained fixture with an unresolved `finalName`; no Hey
  API artifact or dependency remains. `orval@8.27.0` generated deterministic Fetch/ESM output and
  preserved the typed `200`/`403`/`500`/`503` unions, so it is pinned with TypeScript `5.9.3` and
  `@types/node@20.19.43`.
- `@justsearch/runtime-client@0.1.0` is a pack-ready, ESM-only Node 20+ package. Its async factory
  validates the loopback origin, disables redirects, verifies the advertised Runtime Contract before
  returning operation methods, and preserves injected-fetch isolation per client. Package inputs/
  config/source are excluded from the tarball; `LICENSE` and `NOTICE` are required and checked
  against repository copies. npm publication was not performed.
- CI's existing Build lane installs the pinned generator under Node 24 and rejects generated-source
  drift. After the repository assemble, it switches to Node 20 and runs package build/tests plus
  packed-file inspection. The app-ui unit lane independently closes Java route-source → OpenAPI
  snapshot drift.
- Verification: `./gradlew.bat :modules:ui:test` passed (938 tests, one skipped before the final two
  HTTP assertions; the full rerun after those assertions also passed), launcher
  `UnreferencedCodeTest` passed, and `./gradlew.bat build -x test` passed (251 tasks). In
  `packages/runtime-client`, `npm run generate`, the repaired Windows-safe `npm run check:regen`,
  `npm test`, and `npm run check:pack` passed; the Node behavior suite now has seven cases, including
  a real two-server redirect refusal and pre-operation compatibility failure, and passed 7/7 under
  Node 20.
  The `llmstxt --check`, `skills-sync --check`, canonical-link, module-dependency, runtime-config,
  workflow-trigger, and workflow-policy checks passed, as did `git diff --check` before final
  closeout.
- Owned live verification ran from a clean detached worktree at reviewed commit
  `7c8801514a45d20186f714916dd5cffd4ddea024`. The dev runner accepted `distFrom: 899-sdk-live`,
  built the distributions, reached `ready_worker`, and assigned run
  `663985c3-c434-4b19-b30f-edc4a060f921` at `http://127.0.0.1:54589`. The package's exact live-smoke
  result was `runtime-client live smoke passed (contract 0.2.0, readiness 503, health 200)`. Factory
  construction first fetched the manifest and enforced compatibility; the smoke then called all six
  generated operations. The readiness `503` was the declared, typed lifecycle-unavailable outcome,
  while the runner's independent Worker-readiness gate was green.
- Teardown targeted that exact run id and completed with `disposition: normal_stop`,
  `portsClosed: true`, and no errors. The runner retained its local machine record at
  `tmp/dev-runner/runs/663985c3-c434-4b19-b30f-edc4a060f921/stop-report.json`; this ledger preserves
  the run identity, reviewed commit, endpoint, exact smoke result, and teardown outcome as durable
  branch evidence. The temporary resolver junction used to expose the existing
  machine-local Scoop Temurin 25 installation to the long-lived MCP process was removed, its
  target was preserved, and the disposable worktree was removed after the stack stopped.
- The independent refute-first review found three issues, all fixed before closeout: nested manifest
  objects now expose their actual typed public fields while retaining the contract's additive-field
  tolerance; the client compatibility constant is generated from the OpenAPI runtime-contract
  extension and its test reads that same extension; and the factory rejects path-prefixed base URLs
  while generated absolute paths resolve from the loopback origin.
- A second refute-first review found four substantive gaps. Redirect-following could escape the
  loopback origin; compatibility checking was optional; both npm subprocess gates failed silently on
  native Windows/Node 24; and Host `403` coverage used a copied filter against an unrelated route.
  The transport now forces `redirect: "error"`, the async factory verifies compatibility before it
  returns a client, npm subprocesses run through the active npm CLI with launch errors surfaced, and
  `LocalApiHostValidationTest` installs the production filter set and exercises all six SDK routes.
- The prior JDK-resolution blocker was environmental and is closed: the resolver's standard Scoop
  fallback assumes `%USERPROFILE%\scoop`, while this host's existing installation is rooted at
  the machine-local Scoop root. A temporary user-path junction let the unmodified runner resolve that JDK for this run;
  no repository code, global environment, or machine installation was changed. Canonical runtime-
  contract and security documentation had already been updated in the reviewed implementation
  commits, and both refute-first review rounds were complete before this final live proof.

### Design reach

**Immediate design.** Six implementation PRs remain the right publication shape: (1) container +
bootstrap + contributor docs, (2) issue drafts/intake docs, (3) HTTP/MCP lifecycle authority + docs,
(4) succession skeleton, (5) diagnostic summary + issue-template field, and (6) SDK contract
projection S1. Generated package S2 is a seventh conditional PR after S1 and the bakeoff pass.

**Broader reach.** This work reveals three reusable principles but does not justify new generalized
frameworks:

1. **Lifecycle metadata is authored once per governed surface and projected into every transport's
   native extension point.** It applies to HTTP routes, MCP tools, and later generated clients.
   Evidence it earns its keep: one forced deprecation change updates headers, route manifest,
   OpenAPI, tool list, and docs from one catalog row, and a missing projection fails a test.
   Retire the local MCP extension if a future pinned MCP specification standardizes equivalent tool
   lifecycle fields that supported clients actually consume.
2. **Shareable diagnostics are allowlisted products, not redacted internal telemetry.** It also
   applies to future issue attachments, support bundles, and clipboard exports. Evidence it earns
   its keep: hostile fixtures cannot surface undeclared fields and adding a field requires an
   explicit code/test change. Retire the separate text builder only if the diagnostics bundle gains
   a typed, privacy-reviewed manifest with the same strict field/size contract; do not retire it in
   favor of free-form log scrubbing.
3. **A generated client is a projection of an explicit support boundary, never a projection of all
   registered routes.** This applies to later language SDKs and reference-client codegen. Evidence it
   earns its keep: an internal route cannot appear in the SDK snapshot, and every SDK-exposed route
   has complete request/status/security metadata enforced by one coverage test. Retire the separate
   SDK-filtered projection only if the full HTTP surface itself becomes intentionally public and
   equally complete; route count alone is not that condition.

Do not build a universal lifecycle registry across HTTP, MCP, operations, and configuration now.
Their identifiers, version rules, and consumers differ. Reconsider only after a third surface needs
the same chronology/replacement model and two concrete implementations have shown a stable shared
shape.

### Pre-implementation derisk pass (2026-09-03)

#### Confidence-building plan executed

1. Verify the live GitHub label/issue state and the 893 dependency.
2. Inspect the actual route manifest/OpenAPI coverage, HTTP `wire` gate, MCP schema/protocol handler,
   diagnostics service, operation catalog, Help surface, and clipboard authority.
3. Check devcontainer/tool availability on this host and avoid violating the one-Gradle-build/one-
   stack rule.
4. Convert every corrected assumption into a negative or projection test required before feature
   implementation.

#### Results and residual risks

- **Closed:** the label exists; no sync file is needed. There are currently no open issues to
  collide with, but active worktree churn still requires a re-check before opening the five drafts.
- **Closed:** HTTP syntax and MCP extension placement are settled by primary specifications. The
  repository's current `api-evolution.md` syntax is stale and must be fixed in the lifecycle PR.
- **Closed:** the SDK dependency is measurable. The committed route snapshot's response-schema
  coverage is 7/201 (3.5%) and request bodies are absent, so deferral is evidence-based.
- **Closed:** the v0.1 SDK boundary is now explicit: Node-native, six read-only JSON operations,
  stable status subset only, with MCP/token/HEAD/SSE excluded for stated reasons. This removes the
  mutation-token and stream-parser risks from the first package.
- **Closed:** source/snapshot drift is not hypothetical. Current Java maps the two failed-indexing-
  job routes to a schema while the committed route snapshot still records `null`; S1 therefore uses
  an in-process byte-for-byte snapshot test rather than relying on manual live capture.
- **Closed:** Help already mounts the diagnostics operation and the clipboard utility is reusable,
  but the generic clipboard Effect is not: it journals, persists, and exports the payload. The
  dedicated awaitable action must keep summary text out of every journal/persistence surface and
  propagate clipboard failure before showing success.
- **Closed:** the diagnostic summary must be an allowlist. Crash reports contain free-form message
  and stack trace; those fields are explicitly excluded.
- **Residual:** this host matches the proposed Node 24, JDK 25, and Python 3.13 versions but has no
  Docker/devcontainer CLI; Rust has rustup with no default toolchain. The exact container build and
  Tier-0 proof remain implementation gates, not assumed facts.
- **Residual:** several other sessions were actively running Gradle (including a lane-E headless
  evaluation and separate builds). Per the repository's single-build rule, no Gradle tests or live
  stack probes were started in this pass. Existing focused test seams were identified instead.
- **Residual:** Javalin matched-route behavior and header persistence across normal/error responses
  should be pinned with one minimal test before broad route integration. Use the matched route
  pattern, not the concrete request URI, as the policy key. Browser visibility additionally requires
  explicit CORS exposure without weakening Host/Origin admission.
- **Residual:** generic MCP clients may ignore custom `_meta`; the description prefix is the required
  legibility fallback. This is an interoperability ceiling, not a reason to forge standard fields.
- **Residual:** the public manifest, probes, lifecycle subset, Host rejection, and sanitized
  application failures do not all have canonical JSON Schemas today. S1 must author/generate them
  without exposing internal status fields or `head.sessionToken`.
- **Residual:** generator selection is not proven until the same fixture demonstrates Node 20
  runtime compatibility and typed lifecycle `503` handling. Hey API is only a front-runner, not an
  accepted dependency.
- **Residual:** succession values known only to the founder remain explicit placeholders. Their
  absence does not block the skeleton or secret-name inventory.

#### Bounded subagent review

Five read-only subagents challenged disjoint parts of the design; none edited files, ran builds,
touched the shared stack, or mutated GitHub:

- A refute-first contract/privacy reviewer found one design blocker: the generic clipboard Effect
  persists and exports its text payload. It also found missing CORS exposure, missing lifecycle
  classification/exception and closed-world validation, ambiguous OpenAPI documentation projection,
  and an offline-availability requirement for diagnostics. D3 and D5 now incorporate all six
  corrections, so the blocker is closed in design but remains an implementation acceptance gate.
- A contributor-operations explorer confirmed SDK generation is a no-go, then tightened container
  platform wording/version alignment, reuse of existing setup seams, issue sequencing, SDK surface
  enumeration/coherence, and succession variables. D1, D2, D4, and D6 now incorporate those
  corrections.
- A focused SDK route explorer enumerated the six JSON candidates and resolved v0.1's target and
  exclusions; a packaging/codegen explorer found real route-snapshot drift, confirmed that the
  existing `apiRoutes.ts` is only an internal route table, and identified the contract-projection
  gate as the reusable governance seam. Their evidence produced S1/S2 rather than one oversized SDK
  change.
- A final refute-first SDK reviewer showed that byte-stable projection alone cannot prove handler
  behavior, runtime-served `$ref`s are not an offline generator input, the manifest's raw exception
  response is unsafe to standardize, and copied security labels would fork the trust boundary. S1
  now requires executable status/body schema tests, a self-contained snapshot, sanitized failures,
  and enforcement-derived security metadata.

Subagents are useful during implementation only where write ownership stays disjoint: container +
bootstrap, intake/succession prose, lifecycle, and diagnostics can each be separate worktree/PR
chunks. Keep one owner for HTTP+MCP lifecycle because they share projection rules, one owner for the
backend+frontend diagnostic path because privacy is end-to-end, and never assign the two doctor
issues concurrently. Shared-stack operation, outward GitHub actions, publication, and founder-only
succession values remain with the coordinating agent/founder.

#### Pre-implementation confidence and recommendation (historical)

| Chunk | Confidence | Difficulty / recommended effort |
|---|---:|---|
| Container + bootstrap | 6/10 | Medium; strongest residual is the unavailable container proof. |
| Issue drafts + succession | 9/10 | Low; writing judgment and founder placeholders only. |
| HTTP/MCP lifecycle | 7/10 | Medium-high; cross-transport projection and Javalin middleware tests. |
| Diagnostic summary | 7/10 | Medium-high; typed composition, an awaitable non-journaling copy path, and adversarial privacy/persistence tests. |
| SDK contract projection S1 | 7/10 | Medium-high; six routes, canonical schemas, sanitized failures, enforcement-derived security, and both projection and executable-handler closure. |
| Generated runtime client S2 | 7/10 plan / 3/10 ship-now readiness | Medium after S1; generator bakeoff, Node 20 runtime proof, package and live smokes remain. |

**At design time, overall confidence for the then-remaining authorized implementation was 7/10.**
The SDK was no longer an unbounded unknown: S1 could begin at 7/10 confidence, while S2 remained
conditional rather than pretending absent schemas were implementation-ready. Use an Opus-class /
high-reasoning implementation pass (for Codex: `gpt-5.6-sol` at high or xhigh) for the lifecycle
and diagnostic PRs; the docs/intake PRs fit a Sonnet-class / medium-effort pass. Keep the six
immediate PRs separate so the container proof, contract projection, founder-input skeleton, and
privacy surface can each be reviewed and reverted independently.

#### Takeover closeout evidence (historical; before SDK implementation)

- `git diff --check`: passed before commit.
- `node scripts/ci/check-tempdoc-numbers.mjs`: passed across 624 distinct tempdoc numbers and 21
  worktrees.
- `node scripts/ci/check-tempdoc-status-staleness.mjs`: report-mode exit 0; it reported unrelated
  stale statuses elsewhere and no 899-specific defect.
- `node scripts/agent-analytics/world-state.mjs`: completed at takeover entry before worktree
  selection; the closeout rerun was unavailable because `gray-matter` was no longer installed in
  either checkout. No dependency install was performed merely to satisfy closeout.
- `node scripts/dev/agent-spawn-sweep.cjs --occasion session-closeout --session-id
  01a06822-4e63-73c2-be47-5f74ef402868`: retained and reported the expected ownerless `otlp-sink`;
  nothing was reaped or refused.
- No Gradle, frontend, container, or live-stack claim is made. Other sessions owned active Gradle
  work, this host lacked Docker/devcontainer tooling, and this takeover changed only the tempdoc.

**BLOCKED ON YOU**

- Nothing for research/design/derisk. During implementation, the founder supplies only the unknown
  succession placeholders and performs the outward GitHub issue creation.

**PROCEEDING / DONE**

- Takeover investigation, external research, corrected design, design-reach judgment, and derisk
  pass are complete. No feature implementation, GitHub mutation, SDK generation, or dev-stack
  takeover occurred.

#### SDK implementation closeout (2026-09-03)

- D6 S1 and S2 are implemented, independently reviewed twice, fully verified by the focused Java,
  Gradle, Node 24, Node 20, generation, package, governance, and documentation checks enumerated in
  the focused evidence ledger, and live-verified by owned run
  `663985c3-c434-4b19-b30f-edc4a060f921`.
- The owned stack was stopped with both ports closed. The disposable accepted worktree and temporary
  JDK resolver junction were removed; the existing JDK target was preserved.
- The owned-live-evidence commit is `4187d452` (`docs(899): record owned SDK live proof`). At closeout,
  `node scripts/agent-analytics/world-state.mjs` reported the feature worktree clean and unpushed. No
  push, pull request, merge, or npm publication was authorized or performed.
- `node scripts/dev/agent-spawn-sweep.cjs --occasion session-closeout --session-id
  01a06822-4e63-73c2-be47-5f74ef402868` reaped nothing. It retained two `ui-shot` processes attributed
  to unknown other sessions as contention and reported the expected ownerless `otlp-sink`; none was
  owned by this work.

**BLOCKED ON YOU**

- Nothing remains for the SDK implementation or verification. Publishing the branch, opening or
  merging a pull request, and publishing the npm package each require explicit founder action or
  authorization.
- The broader 899 charter still needs founder-only succession values and outward GitHub issue
  creation when those separate workstreams are implemented.

**PROCEEDING / DONE**

- The focused SDK D6 S1+S2 scope is complete and ready for publication review.
- Contributor-container/onramp, community intake, HTTP/MCP lifecycle, succession, and diagnostic-
  clipboard work remain separate 899 implementation changes and are not part of this SDK branch.

### Focused devcontainer/bootstrap derisk pass (2026-09-03)

The remaining D1 design is implementable without inventing a second setup system. The existing
seams divide cleanly: `resolve-jdk.cjs` owns JDK discovery and the >=24 floor;
`prepare-worktree.cjs` demonstrates platform-correct wrapper selection and lockfile-preserving
`npm ci`; `doctor.mjs` owns tier diagnosis; and `test-onramp-first-success.mjs` owns the real
zero-model keyword-search proof. The new bootstrap is a thin post-Node coordinator over those
authorities, not a replacement for any of them.

Current upstream primary sources confirm the planned configuration shape: Ubuntu 24.04 is the
Dev Containers `base:noble` line; the official Feature majors are Java `:1`, Node `:2`, Python
`:1`, and Rust `:1`; Java's Temurin selector is `jdkDistro: "tem"`; and lifecycle commands support
an explicit `waitFor: "postCreateCommand"`. Pin Feature majors and requested tool versions, but do
not claim immutable image or Feature contents without digest pins.

Confidence-building checks:

- `node scripts/dev/test-resolve-jdk.mjs` passed all 11 resolver checks on this branch.
- `node scripts/dev/doctor.mjs --json` returned a valid structured tier report.
- The repository has five committed npm lock roots: root, `modules/ui-web`, `modules/shell`,
  `packages/runtime-client`, and `scripts/wire-contract`. Bootstrap must use an explicit reviewed
  list so a future fixture lockfile cannot silently become an installation target.
- `gradlew` is already tracked executable with LF endings; bootstrap still checks it and repairs
  the executable bit only outside `--check` mode.
- This host has no Docker or Podman command and no installed WSL distribution. Installing a
  container runtime would be a machine-level mutation outside this task. Therefore the actual
  Ubuntu image build and in-container Tier-0 run cannot be produced locally; static configuration,
  pure bootstrap tests, native `--check`, and normal repository checks can run here. A
  container-capable host or explicitly authorized hosted workflow remains the final D1 proof.
- Two bounded read-only subagents were started to challenge script reuse and CI parity, but neither
  returned before the bounded wait and both were shut down without edits or usable evidence. Their
  absence does not change the code-backed findings above.

Main risks and controls:

1. Enforce the native contributor floor (Node >=24, JDK >=24) while pinning Node 24 and JDK 25 in
   the reproducible container.
2. Require Python 3.13 for the all-repository bootstrap, while treating Rust stable as advisory for
   the core Java/web contribution path.
3. Make `--check` structurally non-mutating: no dependency installation, chmod, download, profile
   edit, or package-manager selection. Test this with injected command/filesystem seams.
4. Fix the Windows pre-Node script's malformed patch-version regex and replace its silent network
   fallback with an actionable failure. Exercise URL resolution against a local fixture rather than
   depending on nodejs.org in tests.
5. Keep container setup CPU-only and model-free. The proof must supply an empty models directory and
   run the existing onramp smoke; no GPU, model mount, or Linux product-support promise is added.

**Focused confidence: 8/10 for implementation, 6/10 for final proof on this host.** The code path is
small and the ownership seams are known. The two-point proof deduction is entirely the unavailable
container engine, not unresolved product design. Use a high-reasoning implementation pass for the
cross-platform process/error semantics; the configuration and prose alone are medium effort.

### Active devcontainer/bootstrap implementation plan (2026-09-03)

This plan owns only D1. It extends the existing onramp and worktree setup authorities; it does not
replace `prepare-worktree.cjs`, `doctor.mjs`, or `test-onramp-first-success.mjs`, and it keeps
`bootstrap-node-win.ps1` as the Windows pre-Node entry point.

- [x] **Pin the contributor container.** Add a strict-JSON `.devcontainer/devcontainer.json` using
  the Ubuntu 24.04/noble base, official major-pinned Java/Node/Python/Rust Features, Temurin 25,
  Node 24, Python 3.13, and stable Rust. Run bootstrap then doctor in `postCreateCommand`, and make
  `waitFor` cover that command. Add no GPU, model, Docker-in-Docker, host mount, or Linux product-
  support claim.
- [x] **Build the post-Node coordinator behind pure seams.** Add `scripts/setup/bootstrap.mjs` with
  a main guard and exported parsers/planners. Validate Node >=24, resolve and validate JDK >=24 via
  `resolve-jdk.cjs`, require Python 3.13, report Rust without making it a core failure, verify the
  Gradle wrapper, and run explicit lockfile-preserving installs for the five reviewed npm roots.
  `--check` must execute no install, chmod, download, profile edit, or package-manager selection.
- [x] **Make bootstrap behavior executable evidence.** Add Node tests for version parsing, floor
  rejection, explicit install-plan coverage, fail-fast command errors, advisory Rust, platform-
  correct Gradle handling, and structural non-mutation in `--check`. Wire a root package command so
  contributors and CI can run the suite directly.
- [x] **Repair the Windows pre-Node entry point.** Correct its patch-version regex, eliminate the
  fictitious/silent fallback URL, provide actionable network/parse/download failures, and add a
  resolve-only seam exercised against a local HTTP fixture. Preserve its existing default install
  behavior and session-local PATH update.
- [x] **Make the container proof reproducible.** Extend the manual `onramp-smoke.yml` specialty
  workflow with an Ubuntu job using an exact Dev Container CLI version. Build/up the container,
  verify all four tool versions, build the two runtime distributions, force an empty models
  directory, run the existing Tier-0 smoke inside the container, and always remove the container.
  Do not dispatch the workflow without explicit publication/outward-action authorization.
- [x] **Update contributor truth.** Present native Windows and devcontainer routes side by side in
  `CONTRIBUTING.md`; explain post-Node versus pre-Node bootstrap; use platform-neutral wrapper
  notation where it is genuinely portable; preserve the Windows-only packaged-product boundary.
- [x] **Verify and review.** Run the pure bootstrap and PowerShell fixture tests, native
  `bootstrap.mjs --check`, JSON/config validation, root README/docs/governance checks, and the
  repository build required for the changed scripts. Record the unavailable local container proof
  honestly, perform a refute-first review, fix findings, update this ledger, and commit explicit
  paths without pushing, opening a PR, dispatching CI, or publishing.

### Devcontainer/bootstrap implementation evidence (2026-09-04)

D1 is implemented as a reviewable contributor lane. `.devcontainer/devcontainer.json` supplies the
CPU-only noble environment and runs the shared bootstrap plus doctor. `bootstrap.mjs` owns only the
post-Node portable work: it validates the native floors, resolves the existing JDK authority,
checks or repairs the Unix wrapper mode, and installs the five explicitly reviewed npm lock roots.
The Windows script remains the pre-Node path and now fails closed on malformed indexes and custom
download origins; plain HTTP is accepted only by the loopback `ResolveOnly` fixture seam.

The manual onramp workflow now retains the Windows proof and adds an Ubuntu devcontainer job that
pins Dev Container CLI `0.89.0`, disables GPU discovery, asserts all four toolchains, explicitly
runs `bootstrap.mjs --check`, builds the runtime distributions, forces an empty models directory,
runs the existing Tier-0 smoke, and removes the labeled container in an `always()` step.

Refute-first review found seven substantive defects in the first implementation: stale
`JAVA_HOME` could survive a successful bootstrap; the Windows fixture URL could feed a normal
installation over arbitrary HTTP; Python probing stopped at an unsupported first spelling; the
workflow omitted the explicit check-mode proof; CONTRIBUTING misstated the JDK floor; the
multi-root install test used one root; and resolver selection tests exercised a copied loop rather
than production. All seven were corrected. One first reviewer did not return after bounded waits
and was shut down; a replacement independent reviewer supplied the findings above and made no
edits.

Concrete evidence on the owned Windows worktree:

- `npm run test:bootstrap`: 18/18 passed, including loopback PowerShell fixtures, HTTP/custom-origin
  rejection, mixed Python spellings, stale `JAVA_HOME`, all five install roots, strict devcontainer
  JSON, and workflow invariants.
- `npm run test:resolve-jdk`: 12/12 passed; candidate ranking now invokes the exported production
  selector over temporary candidate homes.
- `npm run bootstrap:check`: passed with Node 24.12.0, JDK 25, Python 3.13.14, npm 11.6.2, and all
  five lockfile roots; Rust absence remained advisory.
- A negative real-process check with an invalid ambient `JAVA_HOME` failed before claiming ready
  and named the resolved JDK 25 remedy. A live `ResolveOnly` lookup against nodejs.org resolved
  `v24.20.0` without downloading or installing it.
- `npm run bootstrap`: completed all five real `npm ci` operations. Existing root/frontend npm
  audit advisories remained visible; no audit suppression or automatic dependency mutation was
  performed.
- `./gradlew.bat build -x test --console=plain`: `BUILD SUCCESSFUL`, 251 actionable tasks.
- `npx markdownlint CONTRIBUTING.md`, `llmstxt-generate --check`, canonical-link verification,
  root-README verification, workflow-trigger verification and its regression test, strict JSON/
  YAML parsing, PowerShell parser validation, and `git diff --check` all passed.

The blocking D1 acceptance proof is still external: this host has no Docker, Podman, Dev Container
CLI installation, or WSL distribution, and workflow dispatch is an outward action that was not
authorized. Therefore no honest container build/up, Feature-install, in-container doctor, or
Tier-0 keyword-result output exists yet. The checked-in workflow is the reproducible way to obtain
that evidence on a container-capable host. The normal Windows Node archive download/extraction path
also remains intentionally unexecuted; URL resolution and all failure/security seams are covered.

**Post-review confidence: 8.5/10 for the implementation, 6.5/10 for final D1 acceptance until the
container workflow is run.** No unresolved code finding remains from the independent review; the
remaining deduction is execution-environment evidence, not design uncertainty.

Publication CI correctly rejected `SdkOpenApiProjection` as a production-unreferenced class. The
projection is a snapshot-generator implementation whose Gradle entry point already runs on the
test runtime classpath, so it was moved from `main` to `test` and its obsolete dead-code exemptions
were removed instead of expanding the accepted-dead baseline.

The next publication run also correctly rejected ADR-0015's stale executable premise: tempdoc 899
had replaced six inline MCP tool registrations with the typed
`McpToolSurface.PRODUCTION_TOOL_DEFINITIONS` registry, while the ADR probe still counted the old
call syntax and therefore found zero tools. The six-tool decision remains true. ADR-0015 was
re-examined and amended with current `McpToolSurface.java:373-496` evidence, and
`adr-0015-six-mcp-tools` now counts the six canonical `ToolDefinition` entries. The focused
`node scripts/governance/run.mjs --gate adr-coverage --mode gate` rerun passed with zero failures;
the remaining 51 findings are the gate's existing informational ADR/risk notices.

### D1 session closeout

The D1 implementation is committed on `codex/899-project-operations-onboarding` as `2207733f`,
`e7368538`, `7ba480d6`, and review-closeout commit `8a3212e8`. The worktree was clean at closeout.
The branch is intentionally unpushed because this session had no authorization to push, open a PR,
dispatch the manual workflow, or publish.

The required session-closeout process sweep reaped nothing. It left one live `ui-shot` owned by
another session as contention, retained one already-gone `ui-shot` record after identity mismatch,
and reported the ownerless `otlp-sink` singleton as designed; none belonged to this D1 work.

### Active remaining implementation plan (2026-09-04)

This plan owns every remaining repository change in tempdoc 899. It preserves the existing D6
route-contract authority and keeps D2 draft-only. The D1 container execution proof was subsequently
obtained through the authorized hosted workflow recorded in the publication outcome below.

- [x] **Reconcile completed and deferred scope.** D6 is complete and live-verified. D1 is
  implemented and its container-capable execution proof is recorded below. D2's deliverable is the
  five issue drafts already recorded here, not opening issues or implementing their starter work. Refine the
  URL-grammar draft to cover its current generator overclaim and the stale Java test name, then
  defer only the live label, ownership, and collision recheck to the founder's issue-opening pass.
  Do not create `.github/labels.yml` or convert the feature-request template in this workstream.
- [x] **Implement HTTP lifecycle signaling in the shared D6 authority.** Extend
  `RouteContractPolicy` with canonical stability and validated immutable lifecycle metadata,
  including the 90-day public floor and bounded pre-1.0 exception. Require closed-world exact-one
  route resolution. Add one matched-route response filter, project policy data into route manifest
  schema 2.0 and both OpenAPI documents, and expose `Deprecation`, `Sunset`, and `Link` only through
  already-admitted CORS responses. Keep all production lifecycle rows empty and prove behavior with
  fake deprecated routes, including exception-mapped responses and negative fixtures.
- [x] **Implement the MCP lifecycle extension.** Add a validated, empty production lifecycle
  catalog beside the existing six tool declarations. Project the versioned experimental capability,
  namespaced top-level `_meta`, and description fallback without changing standard annotations or
  the tool-surface version. Cover duplicate, orphaned, and duplicate-live-tool failures with injected
  test fixtures; no production tool is deprecated for demonstration.
- [x] **Add the succession map without storing secrets.** Extend `MAINTAINING.md` only, link rather
  than duplicate the release runbook, inventory every known custody surface by name, and leave
  founder-fillable placeholders for people, roles, providers, and private recovery-package location.
  Include rehearsal-only `JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED` with an explicit requirement that it
  remain unset in production. Do not change CODEOWNERS, workflows, credentials, or release policy.
- [x] **Build the privacy-bounded diagnostic summary backend.** Extend the diagnostics service with
  a deterministic allowlist-only composer and an 8 KiB UTF-8 ceiling, using typed lifecycle,
  runtime-contract, safe GPU, platform, and crash-metadata sources only. Add the LOW/NONE,
  UI/USER, `METADATA_ONLY` `core.copy-diagnostic-summary` operation with no Worker or Inference
  capabilities, and prove hostile values, controls, limits, omission, malformed crash input, and ZIP
  path redaction. Never place the summary in effects, history, logs, receipts, or persistence.
- [x] **Integrate and validate the clipboard UX.** Mount the operation beside Export Diagnostics and
  directly await the existing clipboard utility; emit only fixed success or failure receipts after
  the real result. Add frontend tests, the optional fenced bug-report field with a review warning,
  and a supported-browser check for activation, offline availability, privacy, accessibility, and
  narrow layout. Remove the direct `exportDiagnostics()` frontend helper only if a final repository-
  wide search proves it unreferenced; retain Export Diagnostics, its API, debug state, and runtime
  manifest.
- [x] **Reconcile canonical truth and generated projections.** Update API evolution, runtime,
  MCP, diagnostics, Help-surface, API-map, and security-threat-model documentation in the same
  change. Regenerate/check route and SDK OpenAPI snapshots, generated clients, `llms.txt`, and skill
  projections through their owning scripts. The CORS change exposes lifecycle metadata after the
  existing allowlist and must not broaden Host, Origin, or mutation-token admission.
- [x] **Verify, independently refute, and close out.** Run focused backend/frontend tests, generated-
  artifact checks, documentation/governance checks, the relevant multi-module test/build gates, and
  live browser/API verification on an owned stack. Obtain an independent refute-first review, fix
  every valid finding, update this evidence ledger, and commit explicit paths. Do not push, open a
  pull request, dispatch the manual devcontainer workflow, publish npm, or merge without separate
  authorization.
- [x] **Obtain the external D1 proof on an authorized container host.** Hosted Onramp Smoke run
  `33856241515` passed the native lane in 9m14s and the devcontainer lane in 29m34s. The container
  started, verified its toolchains and bootstrap check mode, built the required distributions, ran
  the Tier-0 first-success smoke, and removed the container successfully.

Teardown is narrow and explicit: `RouteResponseSchemas` is already removed and the proposed
`RouteLifecyclePolicy` never existed; no second lifecycle authority will be created. The losing SDK
generator left no artifact. D2's starter-issue targets remain intentionally unimplemented. D5 may
remove only a proven-dead direct frontend export helper and stale prose that describes that helper.

Implementation used disjoint bounded subagents for the succession prose, MCP-only code, and
diagnostics backend while this lane owned the shared HTTP policy/projections and final UI/docs
integration. Each slice was reviewed and committed separately before the final combined verification.

### Repository implementation and verification evidence (2026-09-04)

The planned repository work is complete. Bounded subagents implemented the disjoint succession,
MCP-lifecycle, and diagnostics-backend slices; this lane integrated the shared HTTP policy,
projections, Help-surface clipboard UX, canonical documentation, and generated artifacts. Production
HTTP and MCP lifecycle catalogs remain intentionally empty: tests inject deprecated fixtures rather
than deprecating a live contract for demonstration.

Verification evidence:

- `./gradlew.bat test` passed after the final review fixes: 186 actionable tasks, build successful.
- `./gradlew.bat build -x test` passed after the final review fixes: 251 actionable tasks, build
  successful. `spotlessApply` and `git diff --check` also passed.
- Focused route lifecycle, route manifest, and route policy tests passed after correcting `Sunset`
  to strict IMF-fixdate and moving exact-one lifecycle validation ahead of the server bind.
- The runtime client passed `check:regen`, 7/7 package tests, and `check:pack` with 20 files verified.
- The Help surface passed its focused 5/5 tests, changed-file ESLint, typecheck, policy-axis liveness,
  UI-step coverage, and all 40 governed UI gates. The full Vitest run passed 6270/6271 tests; its
  sole failure was the pinned full-suite `EnvelopeStream` heartbeat flake, which passed 24/24 in
  isolation. Repository-wide ESLint retained 24 unrelated pre-existing findings; every changed
  frontend file is clean.
- The operation-surface, execution-surface, and register-guard-resolution governance gates passed
  with zero findings. Route/client generation, `llms.txt`, skill projection, canonical-link,
  module-dependency, runtime-config-matrix, and privacy-claim checks passed.
- Live verification used owned run `3cde1704-d3ea-47cb-96ce-286a96898cf9`. Help rendered both
  diagnostics actions, Copy Diagnostic Summary produced a fixed success receipt, and the action
  ledger retained only operation id, outcome, and transport. The accessibility/layout capture is
  `scripts/jseval/tmp/ui-shot/help.png` with its adjacent `.measure.json`; it reported zero axe
  violations and no overflow. The owned stack stopped cleanly; its report is
  `tmp/dev-runner/runs/3cde1704-d3ea-47cb-96ce-286a96898cf9/stop-report.json`.
- Independent refute-first review found no exploitable security or privacy issue. It identified two
  medium lifecycle defects: single-digit RFC 1123 sunset dates and validation deferred until route-
  manifest access. Both were fixed and covered by the final focused and full Gradle passes. Its low
  documentation objection is resolved by this checked acceptance ledger.

At this pre-publication checkpoint, the only open evidence dependency was the unchecked D1
container-host proof above. D2 remained draft-only by design: a founder must recheck current labels,
ownership, and issue collisions before opening the five issues. This branch was local and unpushed;
no push, pull request, workflow dispatch, npm publication, or merge had been performed.

### Session closeout (2026-09-04)

`world-state.mjs` reported `codex/899-project-operations-onboarding` clean after implementation
commit `1ce5a0b5`, local/unpushed, ahead 311 and behind 6 relative to its configured comparison ref.
The main checkout remained dirty with unrelated concurrent work and was not modified or staged by
this lane. The required ownership-aware closeout sweep pruned nothing: it left one other-session
`ui-shot` as contention, retained one already-gone `ui-shot` identity-mismatch record, and reported
the ownerless `otlp-sink` singleton as designed. No helper owned by this session remained live.

### Review-changes follow-up (2026-09-04)

A fresh independent refute-first review accepted the HTTP lifecycle, MCP lifecycle, succession,
generated-artifact, and overall integration claims, and found no P0/P1 defect. It identified three
remaining evidence gaps before the implementation-complete claim could stand:

1. **Bound the crash-report read on the opened handle.** The first implementation checked
   `Files.size()` and then called `Files.readString()`, leaving a growth/path-replacement window
   between those operations. `DiagnosticSummaryComposer` now opens the report once and reads at
   most 64 KiB + 1 byte from that handle before parsing. The package seam test supplies a 128 KiB
   stream, proves rejection, and proves exactly 65,537 bytes were consumed.
2. **Exercise every frontend privacy sink through the real operation path.** The Help regression
   now seeds the operation catalog, dispatches through `jf-operation`/`jf-op-button`, returns a
   hostile sentinel from the mocked invoke response, and proves the sentinel reaches only the
   clipboard callback. It is absent from rendered text and fixed receipts, console calls,
   `localStorage`, the in-memory effect journal, and the serialized journal archive.
3. **Retain a meaningful narrow-layout proof.** The deterministic UI fixture formerly served an
   empty operation catalog, so a fixture-backed Help capture could not render either diagnostics
   action. It now carries only the two schema-valid Help operations, and the registered
   `help-narrow` step captures the action region at 760x720. Its measurement reports zero axe
   violations and `overflowX=false`/`overflowY=false`; both Copy Diagnostic Summary and Export
   Diagnostics are visible in the PNG.

Fresh closure evidence:

- `:modules:app-services:test --tests "*DiagnosticSummaryComposerTest*"` passed (72 actionable
  tasks), including the bounded-open-handle regression.
- `HelpSurface.test.ts` passed 6/6; changed-file ESLint and the full UI TypeScript typecheck passed.
- The complete Gradle suite passed with 186 actionable tasks, and `build -x test` passed with 251
  actionable tasks. The complete frontend suite passed 468 files / 6,272 tests with no failure.
- `test_ui_fixtures.py` plus `test_ui_step_index.py` passed 17/17, and an earlier isolated step-index
  run passed 4/4. The final fixture/step/schema set passed 20/20, and the self-covering UI-step
  coverage gate passed.
- The retained narrow proof is
  `scripts/jseval/tmp/ui-shot-899-help-narrow/help-narrow.png` with adjacent
  `help-narrow.measure.json` (760x720, zero axe violations, no document overflow). Its demo-shell
  console output contains the same `jf-control` self-check and transition-timeout/unhandled-
  rejection noise independently observed on the pre-change/main artifact; those are pre-existing
  and outside this Help/diagnostics change.

These fixes closed every valid implementation objection from the review. At this review checkpoint,
the only remaining item in tempdoc 899 was the external D1 container-host execution proof; D2
remained the founder-owned draft/publication pass by design. No shipped frontend presentation
behavior or loopback/inbound-access policy changed in this follow-up, so the governing frontend-
kernel and security threat-model documents require no update.

### Review follow-up closeout

The review fixes are committed locally as `d0f2cce6` on
`codex/899-project-operations-onboarding`. The post-commit world-state check reported the worktree
clean, local/unpushed, ahead 313 and behind 6 relative to its configured comparison ref; the dirty
main checkout remained untouched. The ownership-aware session-closeout sweep successfully reaped
both same-session `ui-shot` Vite helpers (ports 5174 and 5175) and only reported the intentional
ownerless `otlp-sink` singleton. No push, pull request, workflow dispatch, publication, or merge was
performed.

### Publication outcome (2026-09-04)

The implementation was transplanted onto current public main in the clean publication worktree and
published through [PR #634](https://github.com/justsearch-app/justsearch/pull/634). Candidate commit
`fd58584b` passed all required PR checks in CI run `33856234406`; the squash-message preview reported
zero warnings and retained the required session attribution. Exact-candidate Onramp Smoke run
`33856241515` passed both the native first-success lane (9m14s) and the devcontainer first-success
lane (29m34s), including container toolchains, bootstrap check mode, distribution builds, Tier-0
smoke, and cleanup.

Publication CI found and closed two real governance defects before merge. `SdkOpenApiProjection`
was moved from the production source set to the test source set instead of expanding the dead-code
baseline. ADR-0015 was then re-examined append-only because its probe counted the superseded inline
MCP registration syntax; the decision still pins six tools, and the probe now counts the canonical
typed production registry. Both the platform-contracts and Public claims lanes passed afterward.

Merge-group CI run `33858750284` passed on synthetic commit `1cf5fca4`, and the merge queue landed
that same commit as `feat(operations): complete project onboarding surface (#634)`. The first
post-merge main run was cancelled only because a subsequent queued merge advanced main. Replacement
main CI run `33860368773` passed on `b9811a6d`, which contains the 899 squash. D1's external evidence
dependency and the publication NOGO are therefore closed. D2 remains intentionally limited to the
five issue drafts in this tempdoc; opening those public issues still requires a current founder
recheck of labels, ownership, and collisions.
