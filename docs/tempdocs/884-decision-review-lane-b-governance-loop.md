---
status: "IN REVIEW — PR 1 landed (#594: premise probes + 45-ADR frontmatter sweep); PR 2 implemented and open for independent review (ADR amendments, ADR-0046, risk register, fail-closed token, 0038 generation, review cadence). The store-recoverability register residue and the governance-kernel dead-code/module-deps/test-efficacy gate residue are settled by tempdocs 909 (#613) and 910 (#611) — see \"Residue routed\"."
created: 2026-09-01
updated: 2026-09-02
owner_session: unassigned (wave-1 orchestrator; no dev stack needed)
follows:
  - 882-decision-review-lane0-hygiene.md (lane 0; fixed ADR frontmatter on 0008/0010/0013/0038/0039/0041 + README)
  - 269-early-product-decisions-review.md (the only systematic ADR/risk trigger audit, 2026-03-09/11)
  - 742-history-survivorship-audit.md §D5 ("ADR-liveness stays manual at n=1")
  - 633 / 655 / 834 (local API security: session token, Host allowlist, MCP Origin check)
---

# 884 — Decision review, lane B: the governance loop for architectural decisions

**Thesis.** The project has run exactly one systematic review of its architectural decisions
(tempdoc 269, March). Its risk register was deleted seven days later with no successor. Only 4 of
45 ADRs carry a reassess trigger; only 9 are referenced by any gate; the other 28 are prose nobody
is scheduled to reread. The 2026-09-01 review found at least five ADRs whose load-bearing premise
is false in shipped code (0008, 0015, 0018, 0038, 0039) and two whose status contradicted their
body. 742 §D5 declined to build an ADR-liveness lint "if a second accepted-but-unimplemented ADR
ever surfaces"; that threshold is passed. This lane builds the mechanism that makes drift
mechanical to detect, restores the risk register with an instrument per risk, and settles the
drifted ADRs. It also writes the missing ADR for the local-API trust model and closes the one
security defect the review confirmed.

Lane B is in wave 1 alongside A and C (see `882-…lane0-hygiene.md` for the split, order and
cross-lane rules). It needs **no dev stack** and is off the critical path, but it should land
before lane F rewrites ADR-0001/0002 so those get premise probes from day one.

## Scope (contract)

| # | Item | This lane does | Not this lane |
|---|---|---|---|
| 25 | ADR premise probes | a probe register + new rule ids on the existing `adr-coverage` kernel gate: each ADR names 1–3 mechanical probes (named test/gate id, absence, or count where the premise is the count) that fail when its premise drifts; seeded for every ADR with a verifiable premise | frontmatter `status:` fixes (lane 0 did them); a second ADR gate |
| 25 | risk register | restore `docs/reference/architectural-risks.md` (deleted 2026-03-18, `55a3e07cf` in the pre-cutover repo) with one trigger **and one instrument** (metric name, gate, or test) per risk; migrate RISK-001..007 from 269 and add the review's new risks | building the instruments that do not exist yet (each becomes a tracked item on the owning lane) |
| 25 | reassess cadence | `last_reviewed` frontmatter on every ADR ("Reassess When" stays a body section); `adr-coverage/review-stale` warning plus a session-start hint when `last_reviewed` exceeds 6 months; the 269 procedure written into `docs/decisions/README.md` | a cron; a separate skill; a second review this lane does not run itself |
| 25 | drifted ADRs | amend 0015 (tool count), 0018 (VDU routing is default-on, tiered, not PDF-only; `JUSTSEARCH_LAYOUT_ENABLED` is gone), 0039 (narrow to the one Category that exists, or schedule the others), 0006 (record 847/867/869 as the trigger check); retire 0011 (never built, no desktop need); mark 0001/0002 "under re-examination, lane F" | rewriting 0001/0002 (lane F) |
| 23 | local trust model | new ADR "Local API trust boundary" stating the actual posture (loopback bind + Host allowlist + MCP Origin check + session token on mutating methods; same-user native processes are inside the boundary; the token bootstrap route is by design); rewrite CLAUDE.md hard invariant #2 to match; make token enforcement **fail closed** when prod mode has no token | new auth mechanisms; anything in `modules/ui-web` or the Tauri shell |

## File ownership (no other wave-1 lane edits these)

`docs/decisions/**`, `docs/reference/architectural-risks.md` (new), `governance/adr-probes.v1.json`
(new), `scripts/governance/gates/adr-coverage/**` and `scripts/governance/lib/frontmatter.mjs`,
`governance/consult-register.v1.json` (new `docs/decisions/**` region),
`scripts/agent-analytics/world-state.mjs` (staleness hint), `docs/reference/security/threat-model.md`,
`CLAUDE.md` hard-invariant #2 line, `modules/ui/.../api/ApiSecurityFilters.java` (token
fail-closed only) + a new `ApiSecurityFiltersTest`, JSON Schemas for the four Surface records +
their `contract-surfaces.v1.json` entries (the FE build wiring is a cross-lane request).

Lane 0 already edited ADR frontmatter, the ADR README and CLAUDE.md; branch after #592 merges.

Cross-lane rules from the independent review (2026-09-01, apply to A, B, C): ADR numbers are
reserved now — **0046 local trust model (this lane)**, 0047 context window (lane A), 0048
extraction isolation + pacing (lane C). A and C create only their own numbered file; this lane
owns `docs/decisions/README.md`, the index and every existing ADR, and adds 0047/0048 to the
index and the probe register when they land. `EnvRegistry`/`ResolvedConfigBuilder` append
regions are shared across lanes (lane A owns structure only).

## Evidence (verified 2026-09-01 on `main` at 8e148b3b; lane 0 may have moved lines)

### The mechanism gap

- Reassess sections exist only on 0001, 0002, 0003, 0006 (all added by 269, 2026-03-11).
- ADRs referenced from `governance/` or `scripts/ci/` as enforcement: 0014, 0024, 0026, 0030,
  0032, 0036, 0042, 0043, 0044, 0045 (10; review-corrected). The rest have no mechanism that can
  notice drift.
- 742 §D5 (`742-history-survivorship-audit.md:212-216`): "No fingerprint lint now — build it if a
  second accepted-but-unimplemented ADR ever surfaces." 0010 and 0041 (status/body contradiction,
  fixed by lane 0) and 0011 (accepted 2026-03-16, zero `RemoteShard` symbols) are that second case.
- `docs/reference/architectural-risks.md` (137 lines) and `schema-migration-roadmap.md` (591 lines)
  removed 2026-03-18 (`55a3e07cf`, "docs: remove stale reference docs", pre-cutover repo) with no
  successor; `RISK-00N` survives only in tempdocs 269 and 512. 269 §A12 had set RISK-006 to
  "Act — decompose before next feature"; `InferenceLifecycleManager.java` was 1,053 lines then and
  is 1,357 on `main`.

### Drifted ADRs (premise → code)

| ADR | Premise | Code | Probe to write |
|---|---|---|---|
| 0015 MCP tool surface | "4 task-oriented tools, because small models pick badly from long lists" | `McpToolSurface.java:221-254` registers 6: answer, search, browse, ingest, status, runtime_manifest | count of `registerTool(` in that file == N declared in the ADR |
| 0018 VLM PDF extraction | opt-in behind `JUSTSEARCH_LAYOUT_ENABLED`, PDFs only | `JUSTSEARCH_LAYOUT_ENABLED` absent from `modules/`; routing is per-extension inside a tiered fallback budget (`worker-services/.../VisualRoutingDecision.java:45,84`) gated by `justsearch.vdu.quality_threshold` (`EnvRegistry.java:583-588`, default 0.3, which still matches) | grep for the flag returns 0 → the ADR must not name it |
| 0038 wire contract SoT | "hand-written per-language mirrors are forbidden" | `modules/ui-web/src/api/types/surface.ts:1-9` is a self-described hand-written mirror of four Java types, imported by 18 files, absent from `governance/contract-surfaces.v1.json` | every `.ts` under `src/api/types` is either generated or registered |
| 0039 contract substrate | "every cross-language agreement is a Category" (wire, plugin SDK, catalogs, registry serialization) | `contracts/registry.v1.json` registers `wire` only; `contracts/catalog` exists unregistered | category count in registry ≥ count the ADR claims, or the ADR is narrowed |
| 0008 settings ephemeral | corrupt settings silently replaced | lane 0 restored this behaviour (backup + defaults + `SETTINGS_RESET_FROM_CORRUPT`); lane B adds the probe: the reset test exists and is green | named test present |
| 0006 citations | trigger #1 "LLM citation accuracy ≥ 95%" | 847/867/869 rebuilt citation marks (2026-08) without citing 0006; last trigger check 720 (2026-07-12) | record the check; probe = `CitationScorer` + `CitationMatchOps` still wired |
| 0011 remote shard SPI | accepted, "not yet implemented" banner (742) | still zero symbols 5.5 months later; a single-user desktop app has no shard | retire: status `rejected` with the reason; probe = no `RemoteShard` symbol appears without an ADR change |
| 0029 telemetry bridge vs direct-emit | criteria ADR | zero references in 629 tempdocs; both idioms exist | fold into 0027 or add a probe that new `Telemetry.*` direct-emit sites are registered |

### Item 23 — the local trust model

- Filters (`ApiSecurityFilters.java:104-112`): Host validation, MCP Origin validation, CORS,
  session token on `POST/PUT/DELETE` (`:403-411`), operation admission, capability gates.
- The shell never calls `/api/mcp/token`; it reads `head.sessionToken` from the runtime manifest
  (`modules/shell/src-tauri/src/binding.rs:116`, `lib.rs:237,843-844`). The route
  (`LocalApiServer.java:75-83,640,727`) exists for external MCP clients and is covered by the
  loopback-Origin check but not by the token (GET). `threat-model.md:90-113` records the residual:
  a same-user native process can read the data dir anyway. **Verdict: coherent; document it.**
- Defect: `ApiSecurityFilters.java:412-421` — prod mode with no token logs
  `TOKEN_ENFORCEMENT_DISABLED` and continues. This is fail-open on the one control that gates
  mutation. Fail closed: refuse to bind (or 503 every mutating call) and raise a lifecycle
  condition.
- CLAUDE.md hard invariant #2 says "Local API binds to 127.0.0.1 only"; true but no longer the
  posture. The ADR names the posture; the invariant line points at the ADR.

## Independent review fold (2026-09-01, session justsearch-public-9a)

Accepted findings, each re-read by this contract's author; the decisions below carry **[R#]**:

- **[R1]** Lane 0 already rewrote `status:` on 0010/0013/0038/0039/0041 with compound strings
  ("accepted - mechanism superseded by tempdoc 564", …); post-882 the vocabulary is 9 distinct
  strings over 46 ADRs. Amendments build on 882's text; any status logic prefix-matches
  (`startsWith('accepted')`), never equality. No gate validates the vocabulary today;
  `llmstxt-generate.mjs` only filters `stable|in-progress|advisory` for inclusion.
- **[R2]** The first draft named two incompatible mechanisms (standalone `scripts/ci/check-*.mjs`
  lint vs the kernel). The kernel already has an ADR gate: `adr-coverage`
  (`governance/registry.v1.json:315-318`, `scripts/governance/gates/adr-coverage/{enforcer,classifications,rule-descriptions,truth-table}.mjs`,
  tempdoc 530 §2.7) that parses every ADR's frontmatter and validates `Covers:` globs. A second
  ADR gate with its own register is the 553 register-drift shape.
- **[R3]** `scripts/governance/lib/frontmatter.mjs:16-29` is a per-line `key: value` regex; a YAML
  list parses as empty. `llmstxt-generate.mjs` already uses `gray-matter`.
- **[R4]** "Reassess When" is a body `##` section on 0001/0002/0003/0006, not frontmatter.
- **[R5]** The fail-open token branch is unreachable today: `HeadlessApp.java:364-365` pairs
  `prodMode` with `generateSessionToken()` on the only production path; dev-runner, jseval and
  ui-shot never set prod mode. No `ApiSecurityFiltersTest` exists;
  `LocalApiUiTokenPolicyTest.java:229` tests a double that duplicates the logic.
- **[R6]** A JSON-Schema→TypeScript emitter exists: `scripts/codegen/gen-wire-schema-types.mjs`
  driven by `governance/contract-surfaces.v1.json` (25 records, output
  `modules/ui-web/src/api/generated/schema-types`), checked by `check-wire-schema-types-regen.mjs`
  and the `contract-projection` kernel gate.
- **[R7]** Verified counts: MCP tools 6; registry categories 1; `RemoteShard` 0;
  `JUSTSEARCH_LAYOUT_ENABLED` 0; ADR-0029 tempdoc citations 0; gate-referenced ADRs **10** (add
  0030 via consult-register and 0032; 0028 in `observed-happening.v1.json:172` is a note, not
  enforcement). `\b0005\b` false-positives on a float literal in `check-contrast-matrix.mjs:115`.
- **[R8]** The deleted risk register is recoverable:
  `git -C /f/JustSearch show 55a3e07cf^:docs/reference/architectural-risks.md` (137 lines,
  RISK-001..007). The consult register's `workflow-agent-tool` region → ADR-0030
  (`consult-register.v1.json:154-172`) is the code-path→ADR template; both hooks consume the one
  compiled `GOVERNED_REGIONS` (`governed-regions.mjs:33-42`). Ride-along: `maintain-doc-hint.mjs:15`
  still says "currently shell-v0 only".

## Design decisions this lane must make (recommendation in bold)

1. **Mechanism [R2, R3].** **Extend the existing `adr-coverage` kernel gate**, no new gate: rule
   ids `adr-coverage/probe-failed`, `adr-coverage/no-probe` (warn), `adr-coverage/review-stale`
   (warn), with changeset classifications and truth-table fixtures per the `/governance` grammar.
   Switch the gate's frontmatter parsing to `gray-matter`. Register shape:
   `governance/adr-probes.v1.json` `{ adr, premise, probes: [...] }`, referenced from ADR
   frontmatter as `probes: [ids]`; keep "Reassess When" as the body section it already is
   **[R4]**; add only `probes:` and `last_reviewed:` as frontmatter keys.
2. **Probe kinds, in preference order (T1).** (1) an existing named test or gate id (ArchUnit
   rules already are premise probes); (2) absence probes (symbol / flag / file must not exist);
   (3) counts only where the premise *is* the count (0015), never as a general ratchet. The
   failure message quotes the premise and names the amendment procedure, so the fix is "amend
   the ADR" and not "edit the number".
3. **An ADR that cannot have a probe** says so in frontmatter (`probes: none - <reason>`); the
   gate warns for `accepted*`/`stable*` ADRs created after this lane lands and does not block
   old ones.
4. **Review staleness surfaces where agents look (T2).** `last_reviewed` older than 6 months is
   emitted by `world-state.mjs` / `known-state-hint` at session start, and by the gate as a
   warning; a CI-only warning is the 872 pile again.
5. **Risk register instrument rule (T4).** Every row names an instrument that exists on `main`
   (metric id, gate id, test FQCN) **or** a lane tempdoc + item id; the gate checks the referenced
   item heading exists, so a row dies loudly if a lane closes without building it. Add a
   `docs/decisions/**` governed region to the consult register on the `workflow-agent-tool`
   template so editing an ADR pushes the review procedure.
6. **0038's mirror [R6].** **Generate `surface.ts`** from JSON Schemas for the four records via the
   existing emitter and register them in `contract-surfaces.v1.json`; the FE build wiring is a
   one-line cross-lane request (UI files are out of scope for this lane). The exception-list
   amendment is the fallback only if schema authoring is judged out of scope, and then 0038 must
   say so.
7. **Fail-closed token [R5], stated honestly.** Defensive hardening with no live path to break:
   refuse to bind in prod mode without a token and raise a readiness reason code. The test
   exercises the real `setupSessionTokenEnforcement` (`ApiSecurityFilters.java:412-421`) with
   `prodMode = true`, `sessionToken = null`, not a double.
8. **The `/adr-review` skill (T5)** becomes a "how to re-examine an ADR" section of
   `docs/decisions/README.md`; the probe gate plus the staleness hint *are* the schedule.

## Acceptance criteria

- `node scripts/governance/run.mjs --gate adr-coverage --mode gate` green on `main` + this
  branch with the new rule ids; proved to bite by seeding one drift (register a 7th MCP tool in a
  scratch commit) → `adr-coverage/probe-failed`, then removed. Truth-table fixtures cover
  probe-failed, no-probe, review-stale, and a list-valued `probes:` key parsed correctly (R3).
- Every ADR has `last_reviewed`; every `accepted`/`stable` ADR either has ≥1 probe or a stated
  reason. Amended ADRs 0006/0015/0018/0039 read true against `main`; 0011 retired; 0001/0002
  carry the "under re-examination (lane F)" banner.
- `docs/reference/architectural-risks.md` restored with RISK-001..007 (from 269) reconciled
  against `main` (e.g. RISK-006's "Act" is still open: 1,357 lines) plus new rows for: production
  JVMs missing native access (closed by lane 0, keep as history), context window unmanaged
  (lane A), extraction sandbox unreachable (lane C), reindex mechanism has one honest detector
  (lane D), single-connection job queue (instrument: throughput metric, lane C).
- `check-premerge-table`, `check-root-readme` (if README touched), `check-readiness-reason-codes`,
  `check-repo-history-policy` green; `docs/llms.txt` regenerated via `/docs-maintenance`.
- A new `ApiSecurityFiltersTest` drives the real `setupSessionTokenEnforcement` with
  `prodMode = true` + null token → refuses to bind (or 503s every mutating call) and the
  `TOKEN_ENFORCEMENT_DISABLED` warning path is gone; `LocalApiUiTokenPolicyTest`'s duplicated
  double is retired or made to delegate.
- `surface.ts` is generated (or 0038 records the exception with the reason);
  `check-wire-schema-types-regen` green; the ride-along fix at `maintain-doc-hint.mjs:15`.
- Session-start hint: `node scripts/agent-analytics/world-state.mjs` (or the known-state hint)
  lists ADRs with `last_reviewed` older than 6 months.
- `./gradlew.bat build -x test`, `:modules:ui:test`; full `./gradlew.bat test` before closing.
- Independent review by a **named** other session (T6): the orchestrator wrote this contract, so
  the closing reviewer should be lane A's or lane C's session, not "someone else".

## Takeover checklist

0. **First PR, immediately after #592 (T3):** the 46-ADR frontmatter sweep (`probes:` +
   `last_reviewed:`, gray-matter parsing in `adr-coverage`) lands **before** lanes A and C file
   ADR-0047/0048, so they write the new shape from the start. Everything else in this lane follows.
1. Branch after `882-decision-review-lane0-hygiene` (#592) merges; lane 0 edited six ADRs
   (compound `status:` strings — build on them, R1), the ADR README, CLAUDE.md, and the
   readiness/store registers.
2. Load `/governance` before creating the gate; load `/docs-maintenance` before editing canonical docs.
3. Recover the deleted risk register's text from the pre-cutover repo:
   `cd /f/JustSearch && git show 55a3e07cf^:docs/reference/architectural-risks.md` (read-only).
4. Write the probe register first, run it against `main` unchanged, and record which ADRs it
   already flags; that list is the amendment worklist, not my table above.
5. Do not touch `docs/decisions/0001` / `0002` beyond the banner; lane F owns their rewrite.

## Open questions for the owner

- Retire 0011 outright, or keep it `deferred` with a trigger ("a second machine or a shared
  index appears on the roadmap")? Recommendation: retire; a trigger without a roadmap item is
  the pattern this lane exists to end.
- Should the 6-month `last_reviewed` warning become a blocking gate after the first full review
  cycle? Recommendation: warn for one cycle, then decide with data on how many ADRs it flags.

## §B — PR 1 pre-implementation pass (2026-09-02, lane B worker session)

Every claim below was re-verified against this branch (`worktree-lane-B`, based on
`6c3ba431`) before a line of gate code was written. `file:line` is primary-source; a
claim I could not confirm is marked **CORRECTION**.

### B.1 The gate substrate (confirms R2, R3)

- `governance/registry.v1.json:315-330` — the `adr-coverage` gate entry: enforcer,
  `changesetsDir: gates/adr-coverage/.changesets`, `baseline.kind: ratchet-file` over
  `docs/decisions`, `config.adrDir: docs/decisions`,
  `selfTestFixturesDir: scripts/governance/_fixtures/adr-coverage`. One ADR gate exists;
  PR 1 extends it (no second gate).
- `scripts/governance/gates/adr-coverage/enforcer.mjs:16,86` — imports and calls
  `parseFrontmatter` from `scripts/governance/lib/frontmatter.mjs`.
- `scripts/governance/lib/frontmatter.mjs:16-29` — per-line key/value regex. **R3
  confirmed**: a `probes:` key followed by `  - id` list items parses as the empty string
  and the list items are dropped (they do not match the key regex).
- **CORRECTION to R3's remedy scope.** `parseFrontmatter` has four callers
  (`grep -rn parseFrontmatter`): `gates/adr-coverage/enforcer.mjs:86`,
  `gates/tempdoc-wiring/enforcer.mjs:104`, `gates/wire/protobuf-changeset-parser.mjs:90`,
  `lib/changeset-loader.mjs:76`. `gray-matter` returns **typed** values — `tempdoc: 524`
  becomes the number `524` and `date: 2026-09-01` becomes a `Date` (probed:
  `matter(...).data.last_reviewed instanceof Date === true`). Swapping the shared lib
  would change value types under three unrelated gates. **Decision: the adr-coverage
  enforcer imports `gray-matter` directly**; the shared per-line lib is left untouched.
  The contract permits either (§1 "or have the enforcer use gray-matter directly").
- `scripts/governance/run.mjs:198-229` — the self-test harness runs exactly two fixture
  flavors, `positive` (must produce verdict `pass`) and `negative` (must produce verdict
  `fail`), asserting only the verdict. So the `no-probe` / `review-stale` warnings must
  live in the **positive** fixture (verdict stays `pass`) and `probe-failed` in the
  negative one.
- `scripts/governance/lib/truth-table-runner.mjs:76-88` — `assertTruthTableShape` requires
  at least one exported `verdict*` function; `assertVerdictShape` restricts `status` to
  `pass|fail|info`. Warnings are therefore `status: 'info'` verdicts the enforcer emits at
  SARIF `level: 'warning'` (precedent: `gates/consumer-presence/enforcer.mjs:39,54,97`).
- `gates/adr-coverage/.changesets/README.md` documents the *count-ratchet* vocabulary
  (`declared-growth` and friends), but `enforcer.mjs:18-20` exports
  `{covers-added, covers-updated, adr-superseded, emergency-override}`. The README is
  wrong today — ride-along fix in this PR (`log-pre-existing-issues`: verified fix, in a
  file this lane owns).
- Baseline before any edit: `node scripts/governance/run.mjs --gate adr-coverage --mode gate`
  → `adr-coverage: pass`, 46 findings, all `adr-coverage/no-covers-field` notes (no ADR
  carries a `covers:` key today; `docs/decisions/README.md` is counted as an ADR by the
  enforcer's `readdirSync(adrDir)` filter — the new rules must skip it).

### B.2 Frontmatter reality (confirms R1, R4)

- 45 ADRs + `README.md` in `docs/decisions/`. Every file has frontmatter with
  `title/type/status/description` and (except README) `date`.
- Compound statuses confirmed: `0013` "accepted - partially superseded by ADR-0043",
  `0026` "accepted - narrowed by ADR-0044", `0038` "accepted - mechanism superseded by
  tempdoc 564", `0039` "accepted - format superseded by tempdoc 564", `0041` "accepted -
  format superseded in part by tempdoc 564". `0012` is `Superseded` (capital S).
  **Status matching is `startsWith` on the lower-cased value.**
- R4 confirmed: no ADR has a `reassess` frontmatter key; "## Reassess When" is a body
  section (0001/0002/0003/0006). Not migrated.

### B.3 Probe targets — verified individually

| ADR | Probe kind | Verified at |
|---|---|---|
| 0001 | test | `modules/app-launcher/src/test/java/io/justsearch/app/launcher/IndexWriterOwnershipTest.java:52-60` — `onlyLuceneOwnersMayDependOnLuceneClasses`, allowlist `LUCENE_OWNER_PACKAGES` = adapters-lucene + indexerworker (`:25-28`). This is the enforcement of hard invariant #1. |
| 0002 | test | `modules/ipc-common/src/test/java/io/justsearch/ipc/mmf/MmfWorkerSignalLayoutV1Test.java` exists; layout constants at `modules/ipc-common/src/main/java/io/justsearch/ipc/mmf/MmfWorkerSignalLayoutV1.java:33-53`. **CORRECTION to the brief's suggestion** (grep-present per `OFFSET_` constant): a named test outranks a grep by the contract's own preference order (§2 T1), and a per-constant grep would flag `OFFSET_RESERVED1_START` (1 main-source file — its own declaration), a false positive on a deliberately reserved slot. Test kind used; reader counts recorded here instead (ACTIVITY/HEARTBEAT/SHUTDOWN/GRPC_PORT 4 files, ENERGY_REDUCED/MAIN_GPU_ACTIVE 3, RESERVED0_START/RELOAD_SIGNAL 2, RESERVED1_START 1). |
| 0003 | grep-present + grep-absent | `gradle/libs.versions.toml:9,60-66` (`lucene = "10.4.0"`, 7 lucene modules); zero `elasticsearch|opensearch|solr` matches in `libs.versions.toml`, `build.gradle.kts`, `settings.gradle.kts`. |
| 0004 | test + grep-present | `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/LoopPacingPolicy.java:60-66` — `shouldRunBackfill(mainGpuActive, energyReduced, embeddingProvider)` is the mutual-exclusion site; `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/LoopPacingPolicyTest.java` exists. |
| 0006 | grep-present | `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/GrpcSearchService.java:50,230-231,346,820` — `CitationScorerConfig` import, `setCitationScorer`, `isCitationScorerActive`, `matchCitations`; `modules/reranker/src/main/java/io/justsearch/reranker/CitationScorer.java` exists. |
| 0008 | test | `modules/app-services/src/test/java/io/justsearch/app/services/settings/SettingsRecoveryNoticeTest.java:28,38` — `publishAssertsCondition` asserts `LifecycleReasonCode.SETTINGS_RESET_FROM_CORRUPT`; the code is at `modules/app-api/src/main/java/io/justsearch/app/api/lifecycle/LifecycleReasonCode.java:158`. |
| 0011 | grep-absent | `RemoteShard` — 0 hits across `*.java`, `*.kts`, `*.ts` outside `docs/`. R7 confirmed. |
| 0015 | count (the ONE count probe) | `modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java:220-256` — 6 `tool("justsearch_...")` registrations (answer, search, browse, ingest, status, runtime_manifest); the section comment at `:213` says "6 curated tools". **CORRECTION to the brief**: the file has no `registerTool(` symbol — the factory is `tool(`, and three call sites break the line between `tool(` and the name, so the count regex must be multiline (probed: 6 matches). No test pins the count (`grep -rn listTools modules/*/src/test` finds no count assertion), which is why the premise-is-the-count probe is warranted here. |
| 0016 | grep-present | `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/QueryFilterBuilder.java:12,364,422` — `BoostQuery` import and `new BoostQuery(new ConstantScoreQuery(...))` with `BooleanClause.Occur.SHOULD`. |
| 0017 | grep-present + grep-absent | `settings.gradle.kts:127-129` — `:modules:ai-backend`, `:modules:gpu-bridge`, `:modules:prompt-support` present; `:modules:ai-bridge`, `:modules:app-ai`, `:modules:ai-worker` absent. |
| 0018 | grep-absent | `JUSTSEARCH_LAYOUT_ENABLED` — 0 hits under `modules/`. Remaining hits are prose only: `docs/decisions/0018-...:26`, `docs/explanation/23-search-pipeline-overview.md:92`, `docs/reference/performance/indexing-throughput.md:90`, `.claude/skills/search-quality/SKILL.md:3287`. The ADR body amendment is PR 2; the probe pins the code fact now. |
| 0022 | grep-present | `gradle/libs.versions.toml:40,138-139` — `recordBuilder = "52"`, `record-builder-processor`/`-core`. The pattern pins the coordinate, not the version. |
| 0024 | script | `scripts/ci/check-installer-execution-level.mjs:9,101` cites ADR-0024 for per-user install. |
| 0026 | script | `scripts/ci/check-workflow-triggers.mjs` exists; `scripts/ci/test-check-workflow-triggers.mjs:278,286` exercises the ADR-0026 note path. |
| 0028 | test | `modules/app-launcher/src/test/java/io/justsearch/app/launcher/LibraryResolveHashOnlyCallerPin.java:13,32-33` — "ADR-0028 / tempdoc 419 T5.4 — pins the 'only one HTTP entry point may resolve hashes' rule". |
| 0030 | json-path | `governance/consult-register.v1.json:154` — the `workflow-agent-tool` region's doc path is `docs/decisions/0030-policy-on-operations-vs-mcp-hints.md`. |
| 0032 | grep-absent | zero `react` matches in `modules/ui-web/package.json`. |
| 0036 | gate | kernel gate `observed-happening` (`governance/registry.v1.json:567`); `gates/observed-happening/rule-descriptions.mjs:32` and `truth-table.mjs:100,113` cite ADR-0036. |
| 0038 | file-set | `modules/ui-web/src/api/types/` holds 5 `.ts` files. `surface.ts:1-9`, `conversation-shape.ts:1-12` and `selection.ts:1-12` self-describe as hand-written mirrors; `registry.ts:1-12` is a re-export barrel over generated projections and `diagnostic.ts:1-12` is generated-derived. None is named in `governance/contract-surfaces.v1.json` (its `records` key on Java schemas plus `generatedDir: modules/ui-web/src/api/generated/schema-types`). All five are listed as explicit, reasoned exceptions; `surface.ts`'s reason points at PR 2 (contract §6, generate it). |
| 0039 | json-path | `contracts/registry.v1.json:5-23` — `categories` has exactly 1 entry (`wire`). R7 confirmed. |
| 0042 | script | `scripts/ci/check-live-witness.mjs:3,75` — "tempdoc 560 §4b/§5 / ADR-0042"; register `governance/live-witness.v1.json:5`. |
| 0043 | script | `scripts/ci/check-language-agnostic-analysis.mjs:3,44,63,80,104,118` cites ADR-0043; register `governance/language-agnostic-analysis.v1.json:2`. |
| 0044 | script | `scripts/ci/workflow-signal-policy.v1.json:11` (`"owner": "ADR-0044 ..."`) enforced by `scripts/ci/check-workflow-triggers.mjs`. |
| 0045 | script | `scripts/ci/check-repo-history-policy.mjs` exists; `scripts/ci/preview-squash-message.mjs:24` cites ADR-0045. |

Rejected probe candidates (recorded so PR 2 does not re-litigate them):

- **0027** (legacy `Telemetry.counter/timer/gauge` retired). A `grep-absent` on
  `Telemetry.gauge(` matches three *javadoc* references (`KnowledgeServer.java:1280`,
  `JvmMetricCatalog.java:20`, `WorkerOpsMetricCatalog.java:24`) that document the
  retirement. A probe that must special-case javadoc is not cheap-and-mechanical;
  `probes: none - <reason>` instead.
- **0029** — the contract itself leaves fold-vs-probe to PR 2.

### B.4 Kind vocabulary — one addition beyond the brief

The brief enumerates `test`, `gate`, `grep-absent`/`grep-present`, `json-path`. ADR-0038's
premise ("no unregistered hand-written mirror exists under `src/api/types`") is a
set-membership statement over a directory that none of those five can express without
degenerating into a file-count ratchet — which decision 2 forbids as a general lever. PR 1
therefore adds a sixth kind, **`file-set`** (`dir` + extension, minus `registeredIn`, minus
a reasoned `exceptions` list), used by exactly one probe. Recorded as a deviation.

The `gate` kind also accepts `script: scripts/ci/<name>.mjs` alongside a kernel
`gate: <id>`, because six of the ADR-owning enforcements (0024, 0026, 0042, 0043, 0044,
0045) are `scripts/ci` checks rather than kernel gates. Same rule id, same failure-message
shape.

## §C — PR 1 post-implementation critical analysis (2026-09-02, lane B worker session)

### C.1 Wrong-gate check — does the probe actually fail when the premise drifts?

Not assumed: every probe *kind* was made to fail on purpose, and the contract's mandated
bite (a 7th MCP tool) was run end-to-end through the kernel, not through a unit helper.

**The mandated bite.** A scratch edit added a 7th `tool("justsearch_scratch", …)` to
`modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java`, then
`node scripts/governance/run.mjs --gate adr-coverage --mode gate`:

```
governance: 1 gate evaluated, 1 fail, 47 findings
  adr-coverage: fail
adr-coverage/probe-failed :: 0015-mcp-tool-surface-design.md: premise "The MCP surface is a
small curated tool list, because small models pick badly from long lists. The count IS the
decision: 6 tools (answer, search, browse, ingest, status, runtime_manifest)." no longer
holds — expected 6 match(es) for /tool\(\s*"justsearch_[a-z_]+"/, found 7 in
modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java (7). The fix is to
re-examine and amend the ADR (docs/decisions/README.md § How to re-examine an ADR), not to
edit the probe until it passes. Probe id: 'adr-0015-six-mcp-tools' in
governance/adr-probes.v1.json.
```

The scratch edit was reverted (`git checkout -- <that one file>`); the tree is clean and
the gate is green again.

**Per-kind bites** (each forced, output recorded at the time):

| Kind | Forced drift | Result |
|---|---|---|
| `grep-present` + `expect` | 7th MCP tool | fail, message above |
| `grep-absent` | pointed the 0018 pattern at `docs/decisions` (where the flag still appears in prose) | `/JUSTSEARCH_LAYOUT_ENABLED/ now appears 1 time(s): docs/decisions/0018-…md (1)` |
| `test` | renamed the pinned member | `'…/IndexWriterOwnershipTest.java' no longer declares 'renamedRuleThatNoLongerExists'` |
| `gate` | unknown gate id | `kernel gate 'no-such-gate' is no longer registered` |
| `json-path` | declared 2 categories, tree has 1 | `contracts/registry.v1.json/categories has 1 entr(ies), declared 2` |
| `file-set` (new file) | dropped a scratch `.ts` into `src/api/types` | `scratch-mirror.ts … is neither registered … nor a declared exception` |
| `file-set` (stale exception) | listed an exception for a file that does not exist | `declared exception(s) … no longer exist — drop them from the register` |

The scratch `.ts` was deleted; `git status --porcelain modules/ui-web` is empty.

**Fail-closed paths, checked deliberately** (`green-masked-destructive`): a probe whose
`paths` resolve to zero files returns *fail*, not vacuous pass
(`probes.mjs` `evaluateGrep`, `scanned === 0`); a missing test file, a missing
`scripts/ci` check, an unparseable JSON register and an unknown `kind` all return fail.

### C.2 Findings from walking the diff (three fixed, in this PR)

1. **A bare `probes: none` satisfied the coverage rule.** The first implementation mapped
   an empty reason to the literal string `'none (no reason given)'`, which is truthy — so
   the laziest possible frontmatter silenced `no-probe`. Fixed: an empty reason returns
   `null`, so `probes: none` with no reason still warns. (`enforcer.mjs`, `readProbesField`.)
2. **`file-set` matched basenames, so a coincidence read as "registered".** Probed:
   `registry.ts` appears 4× and `diagnostic.ts` 1× in
   `governance/contract-surfaces.v1.json:145,151,157,163,169` — as full consumer paths.
   Under basename matching, a *new* hand-mirror named `registry.ts` anywhere would have
   been silently accepted. Fixed to full repo-relative path matching; the two genuinely
   registered files then need no exception entry, so their (redundant) exceptions were
   dropped and the register carries a note instead. ~~The exception list is now exactly the
   three hand-written mirrors the ADR's premise contradicts.~~ **FALSE — corrected in §D.2
   (2026-09-02, review B2):** the probe scoped one directory, so it could not see the
   mirrors ADR-0038's own Rationale names outside it. There are seven declared exceptions,
   not three.
3. **Malformed ADR frontmatter crashed the whole kernel run.** `gray-matter` throws on bad
   YAML where the old per-line parser returned `null`; an unparseable ADR would have taken
   down every gate in the same `run.mjs` invocation. Fixed: caught, and reported as an
   `adr-coverage/probe-failed` error on that ADR.

### C.3 Test precision — does the green mean what it looks like?

- **The negative fixture fails for two reasons** (the pre-existing `stale-coverage` case
  plus the new `probe-failed` case), and `run.mjs` asserts only the *verdict*
  (`run.mjs:206-222`), so on its own it cannot distinguish "probe evaluation works" from
  "the old rule still fires". Rather than delete the pre-existing coverage, the
  discrimination was put on the **positive** side: `positive/0001-sample.md` claims both
  fixture probes through a **list-valued `probes:` key**, and any register probe no ADR
  claims is reported as drift. So if list parsing regresses to the per-line parser (R3),
  the probes become unclaimed and the *positive* fixture flips to `fail`. Verified by
  deleting the list from the fixture: `self-test failed; gate machinery may be broken`,
  with two `adr-coverage/probe-failed` orphan findings. Restored; self-test green.
- **A `test`-kind probe checks that the pinned rule still exists, not that it passes.** A
  `@Disabled` or gutted test satisfies it. Likewise a `gate`-kind probe checks that the
  check is still registered/present, not that it still bites. Both are deliberate: the
  named test/gate *is* the enforcement, and this register's job is to notice when an ADR's
  enforcement is deleted or renamed out from under it. Recorded so a later reader does not
  mistake it for a stronger claim.
- **`expect` on ADR-0017's three-module probe is a presence assertion, not a ratchet.** The
  pattern matches only the three named modules, so `expect: 3` means "all three are still
  declared" and drops to 2 when one is deleted. It cannot grow. ADR-0015 remains the only
  probe where the *premise is the count* (decision 2, T1).

### C.4 Honesty note on `last_reviewed: 2026-09-02`

All 45 ADRs carry today's date. That attests to the 2026-09-01/02 decision review that
produced tempdocs 882/884 and this sweep — a pass over every ADR's status and premise — not
to a full per-ADR re-derivation of every claim. 25 ADRs got a probe whose target this session
verified at `file:line` (§B.3); the other 20 got a stated reason. The practical consequence
is that the whole corpus goes stale on the same day (~2026-03-04), which is the intended
first review cycle, and the open question at the end of this tempdoc ("blocking after one
cycle?") is answered with that data.

### C.5 Deviations from the PR-1 brief

| # | Deviation | Reason |
|---|---|---|
| 1 | `gray-matter` is imported by the adr-coverage enforcer, not by `scripts/governance/lib/frontmatter.mjs` | The brief allows either. gray-matter returns typed values (numbers, `Date`s); three unrelated gates consume the shared parser's string-valued output (§B.1). Smallest blast radius. |
| 2 | ADR-0002's probe is the named `MmfWorkerSignalLayoutV1Test`, not a per-`OFFSET_` grep | Preference order T1 puts a named test above a grep, and the per-constant grep false-fails on `OFFSET_RESERVED1_START` (§B.3). |
| 3 | ADR-0015's count regex matches `tool(` , not `registerTool(` | `registerTool` does not exist in that file; the factory is `tool(`, and it spans lines (§B.3). |
| 4 | Sixth probe kind `file-set` added | ADR-0038's premise is set-membership over a directory; none of the five enumerated kinds expresses it without becoming a file-count ratchet (§B.4). |
| 5 | `gate` kind accepts `script: scripts/ci/<name>.mjs` as well as a kernel gate id | Six ADR-owning enforcements are `scripts/ci` checks, not kernel gates (§B.4). |
| 6 | `no-probe` warns for every live ADR, not only those created after this lane | The brief's "created after this lane lands" is a date test that would need an authoring-date register; after the sweep every existing live ADR has a probe or a reason, so the behaviour is identical and the mechanism is simpler. |
| 7 | `docs/decisions/README.md` got no `probes:` / `last_reviewed:` keys | It is the index, not a decision; the enforcer skips it for the probe and cadence rules (`NON_ADR_FILES`). It still gets the pre-existing `no-covers-field` note, so the 46-finding baseline is unchanged. |
| 8 | Ride-along: `gates/adr-coverage/.changesets/README.md` corrected | It documented the count-ratchet classification vocabulary, which this gate's loader rejects (§B.1). Verified one-line class of fix in a file this lane owns. |

### C.6 PR 2 amendment worklist — what the unchanged tree flags

Run against the tree *before* any edit, `adr-coverage` was **green** with 46 findings, all
`adr-coverage/no-covers-field` notes: no ADR carried a `covers:` key, and the probe/cadence
rules did not exist yet. So the honest answer to "which ADRs did the unchanged tree flag" is
**none** — the pre-existing gate had nothing to say about premises.

The list PR 2 owes therefore comes from the probes this PR wrote plus the reasons it
recorded, not from a gate run:

1. **0038** — SEVEN declared exceptions (corrected by review B2, see §D.2), not three:
   `api/types/{surface,conversation-shape,selection}.ts`,
   `shell-v0/handshake/capabilities-types.ts`, `api/domains/indexing.ts`, `api/schemas.ts`
   and the marker false-positive `shell-v0/utils/aiInstallPoll.ts`. The ADR says
   hand-written mirrors are *forbidden*. PR 2 generates `surface.ts` (contract §6) and drops
   its exception — the probe fails on a stale exception, so the handshake is mechanical. The
   other five each need the same treatment or an ADR amendment that admits them. PR 2 also
   owns the four unregistered generated-wire consumers the kernel's `contract-projection`
   gate flags (§D.8), which is the same register drift on the other side.
2. **0018** — the probe pins the code fact (`JUSTSEARCH_LAYOUT_ENABLED` does not exist); the
   ADR body still names the flag and says "PDFs only". Amend. Same drift in two canonical
   docs and one skill mirror, listed at §B.3.
3. **0015** — the ADR description says "4 task-oriented tools"; the probe declares 6. Amend
   the ADR text to the count the probe pins.
4. **0039** — the probe declares one Category because one exists; the ADR claims every
   cross-language agreement is one. Narrow it, or schedule the others.
5. **0011** — the probe pins zero `RemoteShard` symbols. Retire the ADR per the contract's
   recommendation; keep the absence probe so a future build is a deliberate ADR change.
6. **0006** — record the 847/867/869 trigger check.
7. **0027 / 0029 / 0007 / 0014 / 0019 / 0020 / 0021 / 0023 / 0025 / 0031 / 0033 / 0034 /
   0035 / 0037** — carry a stated `probes: none - <reason>`; each reason names why. The
   candidates worth revisiting in PR 2 are 0023 (an ArchUnit pin would work) and 0027 (a
   javadoc-aware absence probe).

## §D — PR 1 independent-review response (2026-09-02, NEEDS-FIXES → fixed)

An independent review of #594 returned NEEDS-FIXES with two blockers, four should-fixes,
four nits and a ride-along. All are applied on `worktree-lane-B`. §C.2 and §C.6 are
corrected in place where they were wrong; this section records what changed and why.

### D.1 [B1] The probe engine had no test that could fail — fixed

The review's proof was exact: stubbing `evaluateProbe` to return `{ok:true}` left both
self-test fixtures "expected", because the negative fixture already failed on the
pre-existing `stale-coverage` rule (`negative/docs/decisions/0001-sample.md:3`) and
`run.mjs:206-222` compares only the verdict. §C.3 argued the positive fixture's orphan rule
covered this; it covers *list parsing*, not probe evaluation. The review is right.

Three changes:

1. **`scripts/governance/gates/adr-coverage/probes.test.mjs`** (41 checks) — every kind
   through **both** branches: `test` (member present / renamed / file deleted), `gate`
   (registered id / unknown id / script invoked from the pre-merge table / from a workflow /
   invoked by nothing / deleted), `grep-present` (match, no match, exact count, count up,
   count down, multiline regex), `grep-absent` (absent, reintroduced, include-filter both
   ways, zero-files-scanned), `json-path` (count, count drift, nested equals, value change,
   missing segment, contains, unparseable JSON, no `expect` declared), `file-set`
   (excepted, registered, new unregistered mirror, **a mirror outside `api/types`**,
   basename-vs-path laundering, stale exception, generated/tests excluded, missing dir),
   plus the pointer resolver, `PROBE_KINDS` as a closed vocabulary, `loadProbeRegister`
   (absent / broken JSON / id index), and a shape check over the shipped register.
2. **`scripts/governance/gates/adr-coverage/enforcer.test.mjs`** (22 checks) — asserts on
   **findings**, not verdicts, which is what the self-test cannot do: `stale-coverage`
   (including a list-valued `covers:`), `probe-failed` (premise quoted verbatim + amendment
   procedure named), the R3 list-parsing guard, unknown probe id, orphan register entry,
   unparseable register, unparseable frontmatter, `no-probe` (warning level, compound
   status, superseded exempt, stated reason, bare `none`, no frontmatter at all, README
   exempt), `review-stale` (in-window, out-of-window, missing date, `Date` normalization,
   configurable window).
3. **The negative fixture now fails for `probe-failed` ONLY** — the `covers:` line is gone
   from `negative/…/0001-sample.md`. `stale-coverage` keeps its regression coverage in
   `enforcer.test.mjs`, at finding level, which is strictly stronger than the verdict-only
   fixture it replaces.

**Wiring.** There was no runner: 17 `*.test.mjs` files under `scripts/governance/` ran
nowhere — not in `package.json`, not in any workflow (grep of `.github/workflows/*.yml`
finds only the `config-surface` gate invocation at `ci.yml:211`). That is the tempdoc 745 D6
shape the repo already named once. Added
**`scripts/governance/run-all-tests.mjs`** (auto-discovering, modelled on
`scripts/agent-analytics/run-all-tests.mjs`), `npm run test:governance`, and three CI steps
in the job that already runs a root `npm ci` (`ci.yml:111`): the kernel tests, the fixture
self-test, and the `adr-coverage` gate. All 16 pre-existing test files were run first and
are green, so the wiring does not import a red.

**One thing the review's own command would have missed.** `node …/run.mjs --self-test`
exits **0** on a mismatch — `run.mjs:312` is `process.exit(args.mode === 'gate' ? 1 : 0)`
and the default mode is `warn`. And the full-kernel self-test precondition at `run.mjs:320`
is guarded by `!args.gate`, so `--gate adr-coverage --mode gate` never runs fixtures at all.
The discriminating command is **`--self-test --mode gate`**; that is what CI now runs, and
the CI step carries the reason inline.

**Discrimination proof** (stub applied, then reverted):

```
$ node scripts/governance/run.mjs --gate adr-coverage --self-test --mode gate
self-test: adr-coverage/positive: pass (expected)
self-test mismatch: adr-coverage/negative expected fail, got pass
  - adr-coverage/no-covers-field (note): 0001-sample.md: missing Covers field …
  - adr-coverage/no-covers-field (note): 0002-probe-failed-sample.md: missing Covers field …
self-test failed; gate machinery may be broken
exit=1
```

`probes.test.mjs` and `enforcer.test.mjs` also went red under the stub (`'pass' !== 'fail'`
on the premise-drift and list-parsing checks). After reverting: 41 + 22 checks pass,
self-test green, exit 0.

### D.2 [B2] ADR-0038's probe scoped one directory — fixed, and §C.2 was wrong

The review is right and the §C.2 claim ("exactly the three hand-written mirrors") was false.
ADR-0038's own Rationale (`0038-wire-contract-source-of-truth.md:61-65`) names three drift
sites, and the probe could see none of them:
`modules/ui-web/src/shell-v0/handshake/capabilities-types.ts:3-10` self-declares a
hand-written mirror of `CapabilitiesService.CapabilitiesView` and is absent from
`contract-surfaces.v1.json`; `modules/ui-web/src/api/domains/indexing.ts:95,100` is mirrored
*by* `ExcludesService.ExcludesResult`, whose javadoc says so
(`ExcludesService.java:35-38` — "Mirrors the pre-existing … FE-side ApplyExcludesResponse");
`modules/ui-web/src/api/schemas.ts` is the hand-written Zod half.

**Why not a plain recursive file-set.** `modules/ui-web/src` holds 1,028 `.ts` files (522
after excluding `generated/`, tests and fixtures). Demanding each be "registered or
excepted" would need ~500 exception entries — residue, not a probe. The probe now scans the
**whole tree recursively** and flags files that **self-declare** a hand-written mirror
(`mirrorMarker` regex over file content); a flagged file must be registered by full path in
`contract-surfaces.v1.json` or be a declared, reasoned exception. Known mirrors that carry
no marker are declared explicitly, which is the only thing that makes them visible.

Current probe result:
`522 file(s) scanned under 'modules/ui-web/src', 7 self-declared mirror(s), 7 declared exception(s); none unaccounted for`.

| Declared exception | Reason |
|---|---|
| `api/types/surface.ts` | Hand-written mirror of Surface/SurfaceRef/Audience/Placement/SurfaceConsumes (slice 449 phase 5). PR 2 generates it via `gen-wire-schema-types.mjs` — PR 2 / 0038 amendment decides. |
| `api/types/conversation-shape.ts` | Hand-written mirror of ConversationShape/ConversationShapeRef (slice 491 §9.D) — PR 2 / 0038 amendment decides. |
| `api/types/selection.ts` | Hand-written mirror of the app-api sealed sums (tempdoc 526); sealed-sum unions are not expressible by the current emitter — PR 2 / 0038 amendment decides. |
| `shell-v0/handshake/capabilities-types.ts` | Hand-written TS view of `/infra/capabilities` (`:3-10`), mirrors `CapabilitiesService.CapabilitiesView`, unregistered — PR 2 / 0038 amendment decides. **Found by the review.** |
| `api/domains/indexing.ts` | No marker, but a mirror: `ApplyExcludesResponse`/`PatternMatch` (`:95,:100`) are what `ExcludesService.java:35-38` mirrors — PR 2 / 0038 amendment decides. **Found by the review.** |
| `api/schemas.ts` | Named by 0038's Rationale as the hand-written Zod half; tempdoc 683 reduced it to one deliberately `.loose()` agent-session schema — PR 2 / 0038 amendment decides. **Found by the review.** |
| `shell-v0/utils/aiInstallPoll.ts` | Marker **false positive**: `:27` says it *used to be* a hand-written mirror, retired by tempdoc 840 Phase 4. Listed so the scan stays green without weakening the regex. |

`api/types/registry.ts` and `api/types/diagnostic.ts` still need no exception — both appear
by full path in `contract-surfaces.v1.json:145,151,157,163,169` as declared consumers.

**Honest limit, stated rather than papered over:** a hand-written mirror that carries no
marker and is not in the exception list is invisible to this probe. Two such were found by
human reading, not by the scan. The probe's real guarantee is narrower than ADR-0038's
premise: *no NEW self-declared mirror appears unregistered*, plus a fixed list of known ones.

### D.3 [S1] `test` / `gate` probes check existence — README corrected, `gate` strengthened

`docs/decisions/README.md` said these kinds "already pin the premise", which overstates what
`probes.mjs` does (`evaluateTest` checks the file exists and still declares the member;
`evaluateGate` checked only that a file exists). Both fixed:

- The README now states plainly that a `test`/`gate` probe is **existence-only**, that a
  disabled or gutted test satisfies it, and that its job is to notice the enforcement being
  deleted or renamed — not to run it.
- `evaluateGate` for `kind: gate` + `script:` now additionally requires the check to be
  **invoked** from the root pre-merge table or a `.github/workflows/` file. The review's
  count was right: of the five script probes, `check-installer-execution-level.mjs` (ADR-0024)
  is referenced by **nothing** — not `CLAUDE.md`, not any workflow (the only hits are three
  tempdocs, one of which, 799, already names it as an uninvoked check). The other four
  resolve: `check-workflow-triggers` (CLAUDE.md + `ci.yml`), `check-live-witness`,
  `check-language-agnostic-analysis`, `check-repo-history-policy` (CLAUDE.md).
- ADR-0024 therefore **stops using a script probe**. Its premise is now checked directly:
  `json-path` on `modules/shell/src-tauri/tauri.conf.json` `/bundle/windows/nsis/installMode`
  `=== "currentUser"` — a stronger probe than "a file exists". That an existing check runs
  nowhere is routed as a finding, not fixed here.

### D.4 [S2] ADR-0002 — layout constants pinned, gRPC probe moved to real consumers

- The named-test probe now pins `reserved1EndsAtMmfSize`
  (`MmfWorkerSignalLayoutV1Test.java:87`) instead of
  `namedFieldRangesArePairwiseDisjoint` — the reserved tail running exactly to the end of
  the region is what makes a new signal an explicit layout change.
- New `adr-0002-mmf-constants-pinned`: `grep-present` `expect: 2` over
  `(MMF_SIZE_BYTES = 64|OFFSET_WORKER_GRPC_PORT = 20)` in
  `MmfWorkerSignalLayoutV1.java:31,43`. Moving either is a Head↔Worker wire break.
- `adr-0002-grpc-present` moved off `gradle/libs.versions.toml` (a version catalog entry
  proves nothing about use) onto `modules/**/build.gradle.kts` — 24 matches across 10 module
  build files, so the *consumers* are the evidence.

### D.5 [S3] ADR-0028 — pins the premise, not the allowlist's name

Was: the string `APPROVED_CALLERS` appears in the pin file — true of an 11-entry allowlist
regardless of what is in it. Now: `expect: 1` on
`"io\.justsearch\.ui\.api\.\w*Controller"` in `LibraryResolveHashOnlyCallerPin.java:60-70`.
Verified: exactly one entry matches (`io.justsearch.ui.api.IndexingController`), which *is*
ADR-0028's decision — one HTTP entry point may resolve a path hash. A second controller
added to the allowlist now fails the gate.

### D.6 [S4] ADR-0016 — exact count plus the forbidden shape

- `adr-0016-soft-boost-not-filter` gains `expect: 2` — the whole boost population
  (`QueryFilterBuilder.java:364` term boost, `:422` range boost). The register carries a note
  saying why this count is the population and not a ratchet.
- New `adr-0016-no-hard-filter-boost`: `grep-absent` on
  `new BoostQuery\(.*Occur\.(FILTER|MUST)\b`, scoped to `QueryFilterBuilder.java`. This is
  the anti-pattern the ADR's Decision forbids in its own words ("Never apply QU output as
  hard filters"), and it is greppable because both current boost sites add on a single line.
  `MUST_NOT` does not match (`\b` after `MUST`). Currently 0 matches. A whole-file
  `Occur.FILTER` absence probe would false-fail — the file legitimately uses FILTER for
  caller-supplied hard filters (`applyRuntimeFilters`), which the ADR explicitly permits.

### D.7 Nits

- `loadProbeRegister` no longer throws on a broken register: it returns `{parseError}` and
  the enforcer emits `adr-coverage/probe-failed` naming the file. A broken register used to
  take down every gate in the same `run.mjs` invocation.
- An ADR with **no frontmatter at all** now still gets `no-probe` and `review-stale`
  (`isLiveStatus('')` is true — a decision that declares nothing is exactly the kind that
  goes unexamined), and gets exactly one `no-covers-field` note, not two.
- ADR-0006's second probe was tautological (`class CitationScorer` inside
  `CitationScorer.java`). Replaced with the wiring site:
  `expect: 2` on `citationMatchOps.execute(|citationMatchOps.setCitationScorer(` in
  `GrpcSearchService.java:231,846` — the RPC path plus the injection point. A CitationScorer
  nothing calls now fails.
- README: `review-stale` is documented as firing on a **missing** `last_reviewed` too, and
  a bare `probes: none` is documented as not counting as a stated reason.

### D.8 Ride-along — expected-state pins for pre-existing kernel reds

Reproduced on this branch with `node scripts/governance/run.mjs --mode warn --skip-self-test`
(35 gates, 7 fail). Two pins added to `scripts/agent-analytics/expected-state.v1.json`
(`reviewBy: 2026-09-30`, each with an exit probe;
`node scripts/agent-analytics/expected-state-probe.mjs --gate` → 14 pins, 0 problems):

- **`governance-kernel-inputs-unbuilt`** — `npm-audit`, `module-deps`, `dead-code`,
  `dead-code-jvm` and `config-surface` all report `kernel/input-missing`. These are *unbuilt
  reports*, not reds: each names its producer, and `--produce-inputs` builds them. Pinned as
  one entry rather than five near-identical ones.
- **`wire-gate-buf-cli-missing`** — the buf CLI is a local devDependency of
  `scripts/wire-contract/`, absent until `npm install` runs there.

**Not pinned, because they are real defects (routed instead):**

- `contract-projection/undeclared-consumer` — four FE files import a generated wire module
  and are not declared consumers in `governance/contract-surfaces.v1.json`:
  `shell-v0/controllers/AgentSessionController.ts` (agent-sessions-response),
  `shell-v0/state/installComponents.ts`, `shell-v0/substrates/ai/aiInstallBridge.ts` and
  `shell-v0/substrates/tasks/aiInstallTasksBridge.ts` (all ai-install-status). This is
  register drift of exactly the class ADR-0038 governs — it belongs with lane B's 0038
  amendment (PR 2) or the owning FE lane, not in a pin.
- `ts-any/silent-growth` — 5 files gained `any`-casts with no changeset
  (`chat/citationResolve.test.ts`, `chat/MarkdownBlock.ts`, `state/indexingProgress.ts`,
  `search-v3/sv3-sessions.test.ts`, `searchResultViewModel.ts`). Pre-existing on `main`;
  this PR changes no FLAGGED `.ts` file. Owned by whoever lands the ui-web work, not pinnable.

## §E — PR 2 pre-implementation pass (2026-09-02, lane B worker session)

Base: `worktree-lane-B2` off `origin/main` `9e43df5f` (PR 1 = #594, merged). Every claim PR 2
acts on was re-verified against this base before an edit. `file:line` is primary-source; a claim
that turned out **wrong** is marked **CORRECTION** — the brief's errors are recorded, not quietly
routed around.

### E.1 ADR-0018 — what shipped instead of the flag

- `JUSTSEARCH_LAYOUT_ENABLED`: **0** occurrences under `modules/`. The ADR's gate never shipped.
- Routing is **default-on with no enable flag**: `IndexingDocumentOps.markVduIfNeeded`
  (`modules/worker-services/.../loop/ops/IndexingDocumentOps.java:737-762`) calls
  `VisualRoutingDecision.decide(...)` unconditionally for every extracted document.
- Scope is **not PDF-only**: `VisualRoutingDecision.java:20-21` —
  `VDU_ELIGIBLE_EXTENSIONS = Set.of(".pdf", ".png", ".jpg", ".jpeg", ".tiff", ".bmp", ".gif")`.
- Routing is **tiered**, not a single threshold: VDU/VLM is fallback **tier 2**
  (`VisualRoutingDecision.java:51-52`, citing tempdoc 790 item 2) and is skipped when the budget is
  spent (`fallback_budget_spent`); an extraction dropout is the strongest demand
  (`extraction_dropout`); an OCR result with a visual-enrichment signal routes as
  `visual_enrichment_signal`; only then does the quality-score gate apply.
- The ADR's 0.3 threshold **survives**: `EnvRegistry.java:587-588`
  (`justsearch.vdu.quality_threshold` / `JUSTSEARCH_VDU_QUALITY_THRESHOLD`), default applied at
  `IndexingDocumentOps.java:730-731`, clamped to 0..1.

So the ADR is wrong in two places and right in one — which is why it is amended rather than
retired.

### E.2 ADR-0007 — the two-alternative probe is necessary, and the grammar could not express it

- `ResolvedConfigBuilder.java:1336` — `resolveDouble("justsearch.search.entity_boost", 0.0)`:
  the boost defaults to **off**.
- `TextQueryOps.java:183-201` — the boost is applied only when `entityBoost > 0.0f`.
- `SSOT/catalogs/fields.v1.json:388,404,420` — the three `entity_*_text` fields are still declared.
- **CORRECTION to the register grammar assumption.** `probes.mjs` carried one `kind` per probe and
  a flat switch in `evaluateProbe`; there was no OR. A sixth kind `any-of` was added (smallest
  extension: an `alternatives` array, recursion through `evaluateProbe`, depth-capped, with
  missing/empty/unknown-kind alternatives all failing loudly rather than throwing), with 9 new
  checks in `probes.test.mjs` (41 → 50).

### E.3 ADR-0029 — kept, not folded

The contract left fold-into-0027 vs keep-with-a-probe to PR 2. **Kept**, because 0029 is a
criteria ADR about module-dependency structure with five shipped instances, while 0027 is about the
catalog *contract*; folding 189 lines of adoption-idiom criteria into it would bloat a
differently-scoped decision. The "zero tempdoc citations" finding says under-referenced, not wrong.
Both idioms verified live — bridge: `WorkerLuceneTelemetryAdapter.java:25`,
`OrtSessionTelemetryAdapter.java:33`, `EmbeddingTelemetry.java:16`; direct-emit:
`AgentTelemetry.java:24`, `IpcTelemetry.java:27` — and both are now probed, so the ADR fails loudly
if either idiom's population goes to zero (a single-idiom codebase makes the criteria moot).

### E.4 ADR-0046 — the posture was already coherent; only the record was missing

Re-verified the five controls before writing the ADR, so it documents code rather than intent:
loopback bind `LocalApiServer.java:582` (`this.app.start("127.0.0.1", bindPort)`); Host allowlist
`ApiSecurityFilters.java:160-166` + predicate `:664`; MCP Origin `:205-217` + predicate `:262`;
CORS `:337-350` + `resolveAllowedOrigin` `:687`; token `:413` + `requiresSessionToken` `:403-411`
(the run family is **prefix**-matched, so a future GET under `/api/runs/**` inherits the
requirement). `install()` at `:104-112` fixes the order. The residual — a same-user native process
can read the token from the runtime manifest — is `threat-model.md`'s, already reviewed 2026-08-13;
the ADR states it as the boundary rather than as a gap.

### E.5 CLAUDE.md invariant #2 — the byte arithmetic, and a deviation

Old line: 100 B. New line: **126 B** (`2. **Local API trust boundary** - loopback bind, Host
allowlist, mutation token (ADR-0046)` + the unchanged `rule:loopback-only-network` anchor).
Result: CLAUDE.md **22321 / 22322 B**, total **54292 / 54300 B**, `check-always-loaded-budget`
**pass**. The ceiling was not bumped.

**Deviation, stated rather than buried:** the brief required the new line to be no longer than the
old one *or* an equal shrink elsewhere in CLAUDE.md. Neither was achievable — no phrasing names
three controls plus the ADR inside 100 B (measured five candidates; the shortest that still names
the posture is 111 B), and every available shrink was another lane's prose, which on a shared
`main` buys a merge conflict for 26 bytes. The line grew instead. **Consequence the next
CLAUDE.md editor must know: exactly 1 byte of per-file headroom remains.** That is a real hazard
and is listed in Residue below, not left as an arithmetic footnote.

The rule anchor `rule:loopback-only-network` was deliberately **not** renamed: renaming a slug is a
retire+register pair in `docs/reference/contributing/tier-register.md` and a
`prose-tier-register` changeset, which buys nothing here. Row 2's *description* was updated to the
new wording and its "catches violations via" cell now names the ADR-0046 probes; the **tier column
is untouched**, so no `silent-tier-change`.

`scripts/agent-analytics/lib/hard-invariants.test.mjs:43` asserted the projected invariant list
still mentions `127.0.0.1` and went red on the rewrite. The needle — not the code — was stale: it
is one distinctive token per invariant, a canary that the CLAUDE.md parse has not silently
emptied (the file's own docblock says so). Changed to `loopback`, which still occurs exactly once
in CLAUDE.md, with a comment recording that the **bind address is now asserted where it lives** —
the `adr-0046-loopback-bind-literal` probe greps `this.app.start("127.0.0.1"` in
`LocalApiServer.java` with `expect: 1`. The assertion moved from prose-proxy to source; it was not
dropped.

### E.6 The ts-any red is not growth — it is the gate counting English

The coordinator routed `ts-any/silent-growth` (named as 2 files; the gate reports **5**). Before
writing a changeset that would classify "the growth", the six counted occurrences were read:

| file:line | counted text |
|---|---|
| `chat/citationResolve.test.ts:97` | `* are the mutation probe: any route that lets a lexical score reach a tier fails them.` |
| `chat/MarkdownBlock.ts:509` | `* nothing in it is a reference: any [n]/(n) is muted and named).` |
| `chat/MarkdownBlock.ts:768` | `* the predicate is deliberately broad: any [n] or (n),` |
| `state/indexingProgress.ts:130` | `* whenever there is no honest basis: any phase but` |
| `search-v3/sv3-sessions.test.ts:615` | `// set of three can never be described as any other number.` |
| `views/searchResultViewModel.ts:51` | `* not a justsearch-help string check: any named non-default` |

Every one is the English word "any" inside a comment. `countAny`
(`scripts/governance/gates/ts-any/enforcer.mjs:29-31`) runs `\bas\s+any\b|:\s*any\b|<\s*any\s*>`
over raw file text without stripping comments or strings. **There is no `any`-cast growth.**
A changeset classifying it as `declared-growth` would have put a falsehood in a governance
register — the exact failure this program exists to end. See §F for what was written instead.

## §F — PR 2 residue routed (2026-09-02)

Each item below was found during PR 2 and is **not** fixed by it. Per `log-pre-existing-issues`,
each names where it is acted on, not merely that it exists.

| # | Finding | Evidence | Routed to |
|---|---|---|---|
| 1 | **`ts-any` counts English prose.** `countAny` (`scripts/governance/gates/ts-any/enforcer.mjs:29-31`) runs its regex over raw file text without stripping comments or strings, so `: any route`, `as any other number` etc. are counted as `any`-casts. All 5 current `silent-growth` findings are this (§E.6). The 18 rows in `gates/ts-any/baseline.txt` were counted the same way and may be overstated. | §E.6 table, 6 sites | Fix = strip comments before counting (the pattern exists at `scripts/ci/check-readiness-reason-codes.mjs:116-148`) then rebalance the baseline. Declared in `gates/ts-any/.changesets/884-ts-any-prose-false-positives.md`, which is attached to the gate itself so the next author of that gate reads it. **Owner: whoever next touches the ts-any gate** — not lane B, which changes three `.ts` files but none of the six flagged sites. **STILL OPEN, now pinned (PR #604):** it is the one remaining kernel red, 5 `silent-growth` findings, and all five were re-read at source and confirmed to be the English word in comments (`MarkdownBlock.ts:154`, `indexingProgress.ts:100`, `searchResultViewModel.ts:51`, plus two test files). Pin `ts-any-gate-counts-english-prose` in `expected-state.v1.json` carries the claim, the evidence and the tracked fix (strip comments using the pattern at `scripts/ci/check-readiness-reason-codes.mjs:116-148`, then rebalance); its exit probe is the gate itself, so the pin deletes itself when the gate goes green. **MOOT 2026-09-05 (tempdoc 932 §4): the `ts-any` gate was retired outright by tempdoc 930 (`534aaf506`) — `enforcer.mjs`, `gates/ts-any/baseline.txt` and its changesets are deleted, and `expected-state.v1.json` went with the pin hooks (`a42d039ac`). ESLint `no-explicit-any` as an error carries the ratchet now, so there is no `countAny` left to fix.** |
| 2 | **`contract-projection/undeclared-consumer` is a substring test, not an import parse.** `modules/ui-web/src/shell-v0/controllers/AgentSessionController.ts` was flagged for a *doc comment* at `:217` naming `generated/schema-types/agent-sessions-response.ts`; its import block contains no such import (verified). | `governance/contract-surfaces.v1.json`, `AgentSessionsResponse.note` | Registered so the gate is green, with the overstatement recorded verbatim in the register's own `note` rather than hidden. Fix = parse imports instead of substrings. **Owner: the FE/kernel lane** — `modules/ui-web` is out of scope for lane B. |
| 3 | **`check-runtime-manifest-closure` is red on `main`.** Two `[sibling-file]` violations, both reading `runtime/api-port.txt`: `packaging/mcpb/server/index.js:33` and `scripts/sandbox/mcp-typed-confirm.mjs:109`. Last touched by PR #468. | Reproduced 2026-09-02; `[closure-check] FAILED - 2 closure-rule violations` | Dated pin `runtime-manifest-closure-sibling-file-api-port` in `scripts/agent-analytics/expected-state.v1.json` (`reviewBy: 2026-09-30`, `exitProbe` = the check itself, so the pin deletes itself the moment the red is gone). Fix = land the API port as a `manifest.json` field, or justify the sibling in `ALLOWED_RUNTIME_ARTIFACTS` per tempdoc 501 §6. **Owner: whoever owns `packaging/mcpb`.** |
| 4 | **13 live ADRs are invisible in `docs/llms.txt`.** `llmstxt-generate.mjs:149` includes a doc only on exact `stable`/`in-progress`/`advisory`; 13 ADRs carry `status: accepted` and 5 carry compound `accepted - …` strings, so 21 of 46 decisions are indexed. This bit PR 2 directly: ADR-0046 shipped as `status: accepted` and did not appear in the index until it was caught. | `for f in docs/decisions/0*.md` status tally: 21 `stable`, 13 `accepted`, 5 compound, 5 retired | The trap is now documented where the next ADR author looks (`docs/decisions/README.md` Conventions, with the `file:line` of the filter). The **sweep** — 13 frontmatter `accepted` → `stable` — is deliberately NOT done here: it is outside PR 2's contract and would bury a 13-file metadata diff inside a governance PR. **Owner: the next ADR-frontmatter pass**, which has a one-command check: `node scripts/docs/llmstxt-generate.mjs` should index 34 decisions, not 21. |
| 5 | **`build-logic/.kotlin/` is not gitignored.** A Gradle build in a worktree leaves it untracked, one `git add -A` from being committed. | `git check-ignore -v build-logic/.kotlin/` matches nothing | Not fixed: `.gitignore` is shared-surface and lane B owns no build config. **Owner: the next build-config change.** Lane B stages explicit paths, so it cannot commit it by accident. |
| 6 | **`setupOperationAdmission` fails open too.** `ApiSecurityFilters.java:114-115` returns silently when `operationLeases == null` — the same shape as the token defect PR 2 closed, in the same `install()` chain, one control over. Not investigated further. | `ApiSecurityFilters.java:114-115` | ADR-0046's own open question. **Owner: whoever revisits the operation-admission control**; ADR-0046 is the record it must be re-decided against. |
| 7 | **`scripts/ci/check-installer-execution-level.mjs` is invoked by nothing** — not the pre-merge table, not any workflow (found by PR 1, §D.3; ADR-0024 stopped using it as a probe as a result). Still true. | PR 1 §D.3 | **Owner: whoever owns installer CI.** Either wire it into `.github/workflows/` or delete it; an uninvoked check is a layer that is dead regardless of its quality. **RESOLVED — wired, not deleted (PR #604):** config half + a new offline test in `ci.yml`, artifact half in `build-installer.yml`'s `installer_verify`. Kept because the artifact byte-scan is the one assertion ADR-0024's `json-path` probe cannot make. A pre-merge table row was not an option: `CLAUDE.md` measures 22321 / 22322 B. |
| 11 | **`git-base` never resolves a PR base.** `scripts/governance/lib/git-utils.mjs:36` documents the strategy as "PR base ref with `HEAD~1` fallback"; `:83-92` implements only `baseline.fallback ?? 'HEAD~1'`. Every `diffStrategy: "git-base"` gate therefore diffs a one-commit window, so a changeset committed earlier in a branch drops out of scope as soon as another commit lands — the gate flips red mid-branch with no change to its findings. | `git-utils.mjs:36` vs `:83-92`; reproduced on this branch: same 117 findings, 0 fail at the changeset commit vs 2 fail at the tip | Not fixed here: it is kernel-wide diff semantics, not lane B's subject, and the merge queue's squash makes `HEAD~1` the true base after merge so the defect is masked at the only moment it would bite CI. Fix = resolve the actual merge-base against the default branch and keep `HEAD~1` as the genuine fallback. **Owner: whoever next touches the discipline-gate kernel's baseline resolution.** **RESOLVED (PR #604):** the ladder is explicit ref → `merge-base(HEAD, default)` → `HEAD~1`, with a merge-base equal to `HEAD` falling through so post-squash behaviour is unchanged. Pinned by `scripts/governance/lib/git-utils.test.mjs` (20 checks; 8 fail without the merge-base rung) and reproduced end-to-end on a two-commit scratch branch. |
| 9 | **Three kernel gates run nowhere.** `dead-code`, `npm-audit` and `module-deps` need inputs no CI job builds (`ci.yml:206-207` says so), so they are registered, baselined and unable to notice anything. Producing the inputs surfaced 27 `dead-code/silent-growth` findings, 23 of them pre-existing. | `ci.yml:206-207`; `gates/dead-code/baseline.txt` last touched 2026-07-16 (#215) | Declared in `gates/dead-code/.changesets/884-surface-projection-plus-preexisting-drift.md`, which enumerates ours vs pre-existing. Fix = wire them into CI with their inputs (or state openly they are local-only), then rebalance the baseline. **Owner: the CI fact-lane owner (ADR-0044) + whoever lands the ui-web work.** **RESOLVED (PR #604), and the set was four, not three:** `dead-code-jvm` had the same defect. The three node producers cost 0.2 s / 2.7 s / 4.5 s measured, so `dead-code`, `npm-audit`, `module-deps` and `config-surface` run in Public-claims after a producer step; `dead-code-jvm` runs in the platform-contracts unit-test lane, which already executed its producer. Baseline rebalanced to measurement (19 new pins, 8 raised, 9 lowered, 5 stale rows deleted; 186 rows, 0 findings). One thing this exposed: `--gate` was last-wins, so the four-gate step would have evaluated one and reported a pass for three. |
| 10 | **The extraction sandbox cannot start on a long path.** All 6 `ProcessExtractionSandboxTest` cases fail in a worktree with `CreateProcess error=206` — the child JVM's classpath crosses the Windows 32k command-line limit. Not load-dependent; reproduces isolated. | Full-suite run 2026-09-02; error text in `modules/worker-services/build/test-results` | Pinned as `process-extraction-sandbox-classpath-too-long` with an exit probe, and folded into **RISK-010** as a second, independent obstacle beyond the missing argv. Fix = argfile or pathing jar for the child classpath. **Owner: decision-review lane C, tempdoc 885 item 14.** |
| 8 | **`CLAUDE.md` has 1 byte of headroom** (22321 / 22322 B) after the invariant #2 rewrite (§E.5). The next always-loaded addition of any size fails `check-always-loaded-budget`. | `node scripts/ci/check-always-loaded-budget.mjs` | **Owner: the next CLAUDE.md editor** — who must shrink before adding. The budget ratchet enforces this mechanically, so this is a warning about *when* the wall arrives, not a request to remember it. |

## §G — PR 2 post-implementation critical analysis (2026-09-02)

### G.1 Wrong-gate check — every new mechanism was made to fail on purpose

Nothing below is inferred from a green run. Each probe was mutated in memory (no file edits) and
re-evaluated through the real `evaluateProbe`:

| Probe | Forced drift | Result |
|---|---|---|
| `adr-0046-loopback-bind-literal` | `expect: 2` | fail — `expected 2 match(es) …, found 1 in LocalApiServer.java (1)` |
| `adr-0046-token-fails-closed` | pinned method renamed | fail — `… no longer declares 'gone'` |
| `adr-0046-token-fails-closed` | test file deleted | fail — `named test file … does not exist` |
| `adr-0038-no-unregistered-fe-mirror` | `registeredIn` pointed elsewhere | fail — names `surface.ts` **plus** `registry.ts` and `diagnostic.ts` as unaccounted |
| `adr-0038-no-unregistered-fe-mirror` | stale exception added | fail — `declared exception(s) … no longer exist` |

The fourth row is the one that matters: it proves `surface.ts` is now green **because it is
registered**, not because its exception is still there. The exception was dropped in the same
change, so the handshake the register's own note promised was mechanical actually was.

`any-of` (ADR-0007) and the risk-instrument resolver were bite-proved by their implementers —
`any-of` through both branches plus all-alternatives-fail in `probes.test.mjs`, and the risk
resolver by stubbing it to always-resolve, which turned 15 `enforcer.test.mjs` checks red
(48 passed / 0 failed after reverting).

### G.2 A defect the bite test found: an invalid probe regex crashed the whole kernel

While forcing a drift on `adr-0046-loopback-bind-literal` the run died with an uncaught
`SyntaxError: Invalid regular expression … Unterminated group` out of `countMatches`
(`probes.mjs:66`). A `pattern` is author-supplied text in a JSON register, so a typo threw out of
`evaluateProbe`, out of the enforcer, out of `run.mjs` — taking **every other gate in the same
invocation** with it. That is exactly the failure class PR 1 already fixed twice: unparseable ADR
frontmatter (§C.2) and an unparseable register (§D.7). The `pattern` field has the same exposure
and was missed both times.

Fixed: the regex is compiled once, up front, inside a `try`; a bad pattern returns `ok: false` with
`probe pattern /…/ is not a valid regular expression: <message>`. Two checks added to
`probes.test.mjs` (52 total, was 50): one asserting `evaluateProbe` does not throw and reports the
failure for both `grep-present` and `grep-absent`, and one guarding the fix's own hazard — hoisting
a `g`-flagged `RegExp` out of the per-file loop makes `lastIndex` persist across files, so a
three-file fixture asserts the count is still 6 rather than truncated by a carried offset.

### G.3 Test precision — did anything pass for the wrong reason?

- **`check-a11y-closure` caught me, which is evidence it works.** The rewritten `surface.ts`
  initially failed it: my doc comment repeated the literal declaration shape, that check takes the
  FIRST regex match in the file, and the prose copy shadowed the real declaration — so it parsed
  zero placements. The comment now describes the constraint instead of restating the syntax. Had the
  check not existed, a 9-placement landmark contract would have gone silently unenforced.
- **The FE typecheck moved red to green for a specific, understood reason.** The first `surface.ts`
  draft derived `SurfaceConsumes` with a `-?` mapped type, which made `conversationShapes` required
  and produced 15 errors across 13 fixture files. That is the type actually being consumed, not a
  no-op re-export: the fix pins the four always-present graphs as required and leaves
  `conversationShapes` optional, with the required set written as a checked union (a renamed Java
  component makes `ConsumesWire[K]` a compile error).
- **`adr-0046-token-fails-closed` is a `test`-kind probe and therefore existence-only** — a gutted
  or `@Disabled` test satisfies it. Stated here so nobody later reads it as "the refusal is proved
  by the gate". What proves the refusal is `ApiSecurityFiltersTest` itself, whose four cases include
  the adverse precondition (`null`, empty, whitespace), a no-over-refusal case (`assertDoesNotThrow`
  for dev mode — without it the test would still pass if the code refused unconditionally and broke
  every dev launch), and a live 401/200 check proving the filter is actually *installed* rather than
  merely constructible.

### G.4 Two coordinator-supplied facts were wrong, and checking them changed the action

Both were routed to me as settled. Neither survived a read:

- **"ts-any silent growth in 2 files."** It is 5 files, and none of them is growth — all six counted
  occurrences are the English word "any" in comments (§E.6). Writing the changeset the instruction
  literally asked for ("classifies the existing growth honestly") would have recorded a falsehood in
  a governance register. The changeset instead states what is true, is classified `merge-import` for
  provenance, and routes the gate's own counting defect.
- **"`AgentSessionController.ts` imports `schema-types/agent-sessions-response` specifically."** It
  does not. Its import block has no such import; the sole occurrence is a doc comment at `:217`, and
  the `contract-projection` check is a raw substring test over whole file text. Registered anyway
  (that was the instruction, and it makes the gate green) but with the overstatement recorded
  verbatim in the register's own `note` rather than hidden.

The pattern is worth naming: both instructions were *directionally* right — there is a red, it needs
handling — and *factually* wrong about the cause. Acting on the summary would have produced two
green gates and two lies.

### G.5 A pin that had been lying for seven weeks

`ui-web-typecheck-ts5101` claimed `npm run typecheck` is RED repo-wide. It exits **0**. Cause
established rather than assumed: `baseUrl` was removed from `modules/ui-web/tsconfig.json` on
2026-07-16 by #214 (tempdoc 742), leaving only a comment explaining the removal. The pin has been
false since then.

Deleting it is not tidiness — it was actively dangerous *to this PR*. The pin's `match` includes
`npm .*typecheck`, so it fires on exactly the command that verifies the `surface.ts` rewrite, and a
real regression introduced here would have arrived pre-labelled "pre-existing, not caused by your
change." Removed, per the register's own doctrine ("A pin whose red is gone is a lie; delete it in
the fixing PR"). 14 pins remain; `expected-state-probe --gate` is clean.

### G.6 Deviations from the PR-2 brief

| # | Deviation | Reason |
|---|---|---|
| 1 | CLAUDE.md invariant #2 grew 100 to 126 B instead of shrinking elsewhere by the same amount | No phrasing names three controls plus the ADR inside 100 B (five candidates measured; the shortest that still names the posture is 111 B), and every available shrink was another lane's prose on shared `main`. Budget still passes (§E.5); the 1-byte residual is routed. |
| 2 | `surface.ts` is a **composing barrel**, not a re-export shim or a deletion | The brief allowed deletion "ONLY if that is a pure import-path change". It is not: the file exports two runtime values (`AUDIENCES`, `PLACEMENTS`, the latter parsed by a CI check), two interfaces with no Java counterpart, and two FE-only fields (`factory`, `splitPairing` — zero Java source). Deletion would have scattered those into consumers. |
| 3 | `Surface.java` gained `implements PreciseWire` — a source change beyond the brief's file list | Without it the generated schema is all-optional/all-nullable and the projection understates a contract the compact constructor already guarantees. Verified no other committed schema shifted (`git status SSOT/schemas/` showed only the new file). The revert criterion was defined before the run and was not needed. |
| 4 | The new `LifecycleReasonCode` is `RetentionClass.TRANSIENT`, not `FAULT` as the brief specified | `RetentionClass`'s own javadoc makes TRANSIENT the documented total-classification default for a code a capability never holds — and this one is never held, since the Head throws before the bind and exits. The brief said to follow the doc if it contradicted. |
| 5 | `GRADLE_USER_HOME` moved from the mandated scratchpad path to `C:\Users\Elias\AppData\Local\Temp\jsgh-B` | The mandated path puts the cached `protoc-gen-grpc-java` exe at 274 chars, over Windows MAX_PATH, and `:modules:ipc-common:generateProto` then fails with an error that reads like a toolchain break. Isolation — the actual point — is preserved. |
| 6 | PR 2's tempdoc sections are §E/§F/§G, not §B/§C | PR 1 already used §B/§C/§D. |
| 7 | The `ui-web-typecheck-ts5101` pin was deleted, which the brief did not ask for | §G.5 — it would have masked a regression in this very PR. |
| 8 | `modules/ui-web` was edited, which the brief put out of scope | The brief scoped out "component logic in modules/ui-web", and none of the three files touched is component logic — but the 207-line hand-authored barrel at `api/types/surface.ts` is a real crossing of that boundary and is named here rather than left implicit. The alternative was leaving the ADR-0038 item undone: retiring a mirror necessarily edits the file that *is* the mirror. `SurfaceCatalogClient.ts` (a cast replaced by a parse boundary, review S1) and the generated projection are the other two. No component, view or controller behaviour was changed. |
| 9 | ADR-0011's `status:` line, its `## Status` body line and its top note-block were REPLACED, not appended | Append-only governs Context / Decision / Consequences, which are untouched. A retirement whose status still reads `stable` and whose banner still says "this remains the design of record" would be a decision that contradicts itself at the top of the file — the one place a reader looks first. `docs/decisions/README.md`'s own procedure names status changes and superseding notes as the edits a retirement makes. Recorded because it is the single place in this PR where existing ADR prose was rewritten rather than added to. |

## §H — PR 2 report-back (lane B, 2026-09-02)

**PRs.** PR 1 = #594 (merged): the probe register, the `adr-coverage` extension, the 45-ADR
frontmatter sweep, `docs/decisions/README.md`'s conventions + re-examination procedure, and
`scripts/governance/run-all-tests.mjs` wired into CI. PR 2 = this branch (`worktree-lane-B2`,
based on `9e43df5f`): everything else in this lane's contract.

### Items done

| Item | What shipped |
|---|---|
| 25 — drifted ADRs | **0015** amended to six tools with the reason browse/runtime_manifest are tools rather than parameters, plus a Reassess trigger to measure the six-tool surface (the +20pp eval measured four; the ADR now says so). **0018** amended: `JUSTSEARCH_LAYOUT_ENABLED` never shipped, routing is default-on, per-extension (7 extensions, not PDFs only), and tier-2 inside a fallback budget; the two stale canonical-prose mentions fixed and the skill mirror regenerated. **0039** narrowed to the one `wire` Category with a reopening trigger. **0011** retired (`rejected - never built`), reasoning preserved, absence probe kept. **0029** kept, not folded — with two new probes over the five shipped instances, so it fails if either idiom's population goes to zero. **0007** amended (facets live, boost fields lane-D-owned) behind a new two-alternative `any-of` probe that passes before *and* after lane D. **0006** trigger check recorded (847/867/869 rebuilt citation marks without citing it). **0001/0002** banner only. **0010/0041** replacement links added; 0010 states plainly that no canonical successor exists rather than inventing one. |
| 25 — risk register | `docs/reference/architectural-risks.md` restored, RISK-001..007 reconciled against `main` and six rows added (008..013). Ten of thirteen instruments resolve mechanically; three say `none - <reason>` and warn. |
| 25 — instrument gate | The `adr-coverage` gate gained a risk-instrument resolver (`gate:` / `check:` / `test:` / `metric:` / `tempdoc:NNN#heading` / `none - reason`), four new rule ids, fixtures, and 48 finding-level checks. No second gate. |
| 25 — review cadence | `world-state.mjs` gains an "ADR review" section (prints `0 of 46 ADR(s) past the 183-day review window`) sharing the gate's arithmetic via a new `review-window.mjs`; `governance/consult-register.v1.json` gains an `architecture-decisions` region; `agent-guide.md` §3.8 makes the same-PR ADR update a written rule. |
| 23 — trust model | **ADR-0046** written: the boundary is the same-user native process; five controls with their sites; why the token bootstrap route is safe; the accepted residual; four reassess triggers; two probes. `threat-model.md` points at it and gains the fail-closed sentence. CLAUDE.md invariant #2 names the posture and points at the ADR. |
| 23 — fail closed | `ApiSecurityFilters` now refuses to construct in prod mode without a token, raising `LOCAL_API_SESSION_TOKEN_MISSING`; `TOKEN_ENFORCEMENT_DISABLED` is gone repo-wide; `ApiSecurityFiltersTest` (4 cases) drives the real constructor; `LocalApiUiTokenPolicyTest`'s hand-rolled double now delegates to the real `install()` with all 10 assertions unweakened; five stale prose sites swept. |
| 0038 | `surface.ts` generated end-to-end (schema from Java, registered, projected) and reduced to a composing barrel; its probe exception dropped; the four `contract-projection` undeclared consumers registered. |

### Items deviated

Seven, each with its reason, in §G.6. The two that change what a reader should expect: `surface.ts`
is a **composing barrel** rather than deleted or a pure re-export (it exports runtime values and
FE-only types that have no Java counterpart), and CLAUDE.md's invariant #2 **grew** 26 bytes rather
than trading bytes elsewhere.

### Items skipped, with reasons

- **The five remaining hand-written FE mirrors** are declared exceptions, each with a named blocker
  and the UI lane as owner (§0038 amendment). One of them (`selection.ts`) is genuinely blocked on
  an emitter that has no discriminated-union support; the others need consumer-side work that
  `modules/ui-web` owns.
- **The 13-ADR `status: accepted` → `stable` sweep** that would put them back in `docs/llms.txt`
  (§F row 4). Outside PR 2's contract; the trap is documented where the next ADR author looks and
  the sweep has a one-command check.
- **The `ts-any` comment-stripping fix** (§F row 1) and **the `contract-projection` import-parsing
  fix** (§F row 2). Both are kernel-gate semantics changes for gates this lane does not own.

### Evidence

Bite proofs in §G.1 (five forced drifts, each with its failure text). Stub-discrimination for the
risk resolver: 15 checks red under a stubbed resolver, 48 passed after reverting. `any-of` covered
through both branches plus all-fail. The fail-closed test covers the adverse precondition, the
no-over-refusal case, and a live 401/200 install check.

### Measurements

- CLAUDE.md **22321 / 22322 B**; always-loaded total **54292 / 54300 B** — pass, ceiling untouched.
- `InferenceLifecycleManager.java` **1,357 lines** (1,053 when RISK-006's own 1,000-line trigger was
  written in 2026-03). The trigger fired and nothing acted on it, because the document holding it
  was deleted a week later. That is this lane's thesis in one number.
- ADRs indexed in `docs/llms.txt`: **21 of 46** (§F row 4).
- `adr-coverage`: 50 findings, 0 fail. Probe register: 39 probes over 46 ADRs.
- ui-web: typecheck clean; 6209/6210 unit tests pass (the one failure is the pinned
  `PluginLoader` full-suite timeout, green in isolation); 40/40 ui-web gates.

### Cross-lane requests raised

- **UI lane** — the five declared mirror exceptions (`conversation-shape.ts`, `selection.ts`,
  `capabilities-types.ts`, `api/domains/indexing.ts`, `api/schemas.ts`), each with its blocker in
  `governance/adr-probes.v1.json` and the ADR-0038 amendment. No FE build wiring was needed: the
  generated projection lands in the existing `generatedDir` and the existing codegen script emits
  it, so nothing in the ui-web build changed.
- **UI/kernel lane** — make `contract-projection`'s undeclared-consumer check parse imports rather
  than substrings (§F row 2), and strip comments in `ts-any`'s counter (§F row 1).
- **Lane D** — RISK-011 has no instrument because lane D has no tempdoc number yet. The row says so
  and must gain a `tempdoc:` instrument when the lane files.
- **Lane A / lane C** — ADR-0047 and ADR-0048 are still reserved and unwritten. When they land they
  need a Decision Log row, a `probes:` entry and `last_reviewed:` — the gate will warn if not.

### Residue routed

Eight items in §F, each with an owner and the place it is acted on. The two that are load-bearing
for the next agent: **CLAUDE.md has 1 byte of headroom**, and **`docs/llms.txt` is missing 13 live
ADRs** because their frontmatter says `accepted` rather than `stable`.

**Settled (2026-09-02) — cross-lane, credited here because the governance kernel is this lane's
subject.** The `store-recoverability.v1.json` register's `pendingDurableClassification` (parked
sites, capped, each naming its blocker — see 885's UL-window §3496-3505, the register's actual
home) is now empty: tempdoc 909 (#613) did the real product work behind all eight parked sites and
promoted each to a READY `durableStores` row (42 rows total; `cap: 8` retained on the now-empty
list). Tempdoc 910 (#611), the governance-kernel residue lane parented on this tempdoc, closed the
register's other open edge in the same wave: a closed `corruptionPolicy` vocabulary (26 values) plus
an external count ratchet (floor + `pendingDurableClassificationCap` ceiling) in the new
`governance/store-corruption-policies.v1.json`, alongside the `dead-code` gate's whole-file masking
fix (§F row 9 above) and `module-deps`/test-efficacy fixes surfaced by the same independent-review
round, and a shared prior-baseline reader so both gates read one baseline-diff helper instead of two.

### What the next lane must know

1. **The register is now the schedule.** Every ADR carries `probes:` and `last_reviewed:`. A tempdoc
   that changes a governed decision updates both in the same PR (`agent-guide.md` §3.8), and editing
   anything under `docs/decisions/` pushes the procedure via the consult register. The whole corpus
   goes stale on the same day (~2026-03-04) — that is the intended first review cycle, and it is the
   data the "should staleness block?" question was deferred to.
2. **A failing probe means amend the ADR, never edit the probe.** Every failure message says so and
   names the procedure. The one legitimate reason to change a probe is that the ADR changed first.
3. **A risk row whose instrument stops resolving is a lane that closed without building what it
   promised.** That is the whole point of the instrument field; do not delete the reference to go
   green.
4. **`test`- and `gate`-kind probes are existence-only.** They notice an enforcement being deleted
   or renamed, not one being gutted. Do not read them as stronger than that.
5. **Two facts handed to this lane as settled were wrong** (§G.4), and both would have produced a
   green gate recording a falsehood. Read the code the claim depends on before acting on a summary —
   including a summary from the orchestrator.

## §I — PR 2 independent-review response (2026-09-02, NEEDS-FIXES → fixed)

An independent review of #597 returned NEEDS-FIXES with four blockers, eleven should-fixes and a
set of nits. All are applied on `worktree-lane-B2`. Where the review's own claim did not survive
verification, that is recorded here rather than silently worked around.

### I.1 [B1] The full kernel was red — `contribution-surface/grandfather-drift`

I had verified three gates individually and never run the whole kernel. `contribution-surfaces.v1.json`
still listed `surface` under `grandfatheredPending`, whose entire purpose is to fail when one of
those endpoints *silently gains a generated schema-types module* — which is exactly what this PR
did. The register was telling the truth and I had not asked it.

`surface` is promoted into `surfaces[]` (`kind: projection`, `wireType: SurfaceWire`,
`projection: generated`, `guard: gate:contract-projection`), and
`modules/ui-web/src/api/types/surface.ts` is added to `scan.barrel` — which subjects it to the
barrel-purity rule, the same governance `registry.ts` and `diagnostic.ts` already carry. That
promotion is what turned up three hand-authored declarations in the new barrel, one of which was a
real design defect (below).

**Lesson recorded as a deviation-of-process, not a footnote:** "three gates pass" is not "the
kernel passes". The full-kernel run is now in this lane's verification list.

### I.2 [B2] ADR-0046 named a route family that does not exist

The ADR said the session token is required on `/api/runs/**`. The real prefix is
`RunRoutes.PATH_PREFIX = "/api/chat/runs"` (`RunRoutes.java:29`), which
`ApiSecurityFilters.java:426` reads directly. The ADR now names the constant rather than
re-spelling a literal, so the doc cannot drift from the code again without the constant moving.
`OPTIONS` is also documented as exempt (`ApiSecurityFilters.java:423-425`), which the original
text omitted.

### I.3 [B3/B4] Every ADR-0046 citation was stale, because the ADR was written before the code changed

ADR-0046 was authored against `ApiSecurityFilters.java` as it stood *before* commit `4cd5d0e0`
added the fail-closed guard — which shifted every line below it. Fourteen citations were wrong.
All are re-verified against `HEAD` by symbol, not by offset: `install()` `:138-145`,
`setupHostValidation` `:193-207`, `isAllowedHost` `:685`, `setupMcpOriginValidation` `:238-241`,
`isAllowedMcpOrigin` `:295`, `setupCors` `:370-397`, `resolveAllowedOrigin` `:708`,
`requiresSessionToken` `:422-430`, `setupSessionTokenEnforcement` `:441`, and the bind at
`LocalApiServer.java:582`. `threat-model.md`'s `POST`/`DELETE /mcp` citation moved `:561-562` →
`:644-645`. B4's "all five controls live in `ApiSecurityFilters`" contradicted the ADR's own
table — control #1 is the bind in `LocalApiServer` — and is fixed.

**This is a general hazard of writing an ADR in the same PR that changes the code it cites**, and
the ordering that avoids it is: land the behavioural change, then cite. Recorded because the probe
register cannot catch it — `file:line` prose is not a probe.

### I.4 [S2 + B1] The generated schema was precise only at the top level

Only `Surface` carried `PreciseWire`, so `SurfaceConsumes`, `SurfaceStateSchema` and `StateBinding`
fell back to the all-optional/all-nullable default. That is why the first barrel had to
hand-restate which keys are required — a hand-written union of Java field names, i.e. a small
mirror smuggled back into the file whose mirror this PR was retiring. The `contribution-surface`
barrel-purity rule flagged it independently, which is the gate doing exactly its job.

The nested types now carry the marker where their constructors justify it, the schema is
regenerated, and the hand-restated union is gone — `SurfaceConsumes` derives from the precise
`SurfaceWire['consumes']`. Two declarations remain hand-authored and are allowlisted with reasons
(`SurfaceCatalog`, the response envelope `RegistryController.handleSurfaces` composes inline via
`writeEnvelope`'s `LinkedHashMap` — a Java `SurfaceCatalog` type does exist but it is the
server-side `DeclarationCatalog<Surface, SurfaceRef>` lookup interface, an incidental name
collision, not this wire shape; and `SurfaceFactory`, a client-only dispatch token that is never
serialized in either direction).

### I.5 [S1] The generated validator had no consumer

`gen-wire-schema-types.mjs` emitted `surfaceWireSchema` and nothing imported it, while
`SurfaceCatalogClient` still cast the payload — `substrate-without-consumer-flavors` in the same
change that created the substrate. A `surfaceCatalogSchema` now sits beside the `registry.ts`
pattern and the client parses through `parseWireContract` like `OperationCatalogClient` does.

### I.6 [S3/S5/S6] Three mechanisms that could not fail

- **Deleting the risk register was silent.** `docs/reference/architectural-risks.md` could be
  `rm`-ed and `adr-coverage` still passed — the same one-command deletability that killed the 2026-03
  register. Absence now fails.
- **`contract-projection` counted a doc comment as a consumer.** PR 2 registered
  `AgentSessionController.ts` as a consumer of a module it does not import, to make the gate green,
  and recorded the overstatement in a `note`. The review is right that a documented lie is still a
  lie: the detection is now anchored on an import statement (a comment-stripping scanner that
  respects string/template literals, plus anchored regexes for static `from`, side-effect and
  dynamic imports, end-anchored on the specifier so `resource` cannot launder `resource-usage`).
  Fixing the matcher surfaced a **second** false entry nobody had seen: `SettingsV2` declared
  `api/schemas.ts` as a consumer, whose only mention of `settings-v2` is the tempdoc-683 tombstone
  comment at `:20-22` recording that those hand schemas were *deleted*. That one is pre-existing on
  `main`, not from PR 2 — the old matcher could not tell a tombstone from an import. Both entries
  are deleted. A whole-tree run (566 FE files x 26 records) found the new parser misses **zero**
  imports the old one caught and drops exactly the two comment-only mentions.
- **A stale probe exception was only detected on file deletion.** §G.1 called dropping
  `surface.ts`'s exception a "mechanical handshake"; it was mechanical only for the delete case. An
  exception whose file exists but is no longer a mirror is now stale too — which is the *success*
  case this probe drives toward, and the one it could not see. Distinguishing that from an entry
  declared **because** its file carries no marker (`api/domains/indexing.ts`, `api/schemas.ts`)
  needed information the register did not carry: a prose `reason` is not machine-readable. Those two
  now carry `"unmarked": true`, and the default is strict — an exception is expected to be marked,
  because an exception exists to account for something the marker scan flagged.

### I.7 [S11] The one review item that did not fit

The review asked for the risk register to be routed in `CLAUDE.md`'s pre-merge table, shrinking a
lane-B-authored line to pay for the bytes. It does not fit: the table row grows 17 B and the only
other lane-B line (invariant #2) cannot shed 17 B without dropping a named control or renaming a
registered rule slug — 7 bytes bought by a `prose-tier-register` retire+register cycle, which is
gaming a budget rather than respecting it.

Routed the better way instead, at zero always-loaded bytes: the `architecture-decisions` region in
`governance/consult-register.v1.json` now watches `docs/reference/architectural-risks.md` as well,
so editing a risk row pushes the same procedure at the agent editing it. That is the mechanism
built for path-triggered routing; the pre-merge table is the always-loaded fallback for things no
register covers.

### I.8 Nits applied, and one refused

Applied: the `threat-model.md` line citation; ADR-0038's `AUDIENCES` justification (it has **zero
consumers** — the load-bearing reason is `PLACEMENTS`, which `check-a11y-closure.mjs` parses);
ADR-0038's "`SurfaceCatalog` has no Java counterpart" made consistent with the file's own header
(the counterpart is a hand-composed envelope built inline by `RegistryController.handleSurfaces`,
not a record); the `OPTIONS` exemption; `Surface.java`'s stale `riskTier` comment; and the
`world-state.mjs` claim — it is the **manual** orientation command `AGENTS.md` points at, not an
automatic session-start hook, corrected in both `agent-guide.md` §3.8 and the consult register's
recipe.

**Refused, with evidence:** the suggested `grep-absent` probe on `0\.0\.0\.0` in
`LocalApiServer.java` would have gone red on day one. That string occurs twice, at `:774` and
`:811`, in javadoc that exists to warn against a wildcard bind — and `evaluateGrep` does not strip
comments. A probe that fails on the prose written to prevent the thing it checks is worse than no
probe. Added instead as `adr-0046-no-wildcard-bind`: `grep-absent` on `start\("0\.0\.0\.0"`, which
pins the *call* rather than the address, currently 0 matches, bite-tested by pointing the same rule
at the literal that is present (2 matches → fail). The register entry carries that reasoning so the
next reader does not "simplify" the pattern back.

**One correction to the review:** its nit says my report claimed `reviewStaleDays` was "dead config
removed" and that this is wrong because the key is live. The claim was past-tense and accurate — it
*was* inert (declared in `adr-probes.v1.json`, never read; the enforcer used a hardcoded 183 that
happened to agree), and this PR made it live. It is live now precisely because the PR wired it.

### I.9 Two consequences of the review fixes that are worth stating plainly

**A behaviour change shipped with S1.** Routing `SurfaceCatalogClient` through `parseWireContract`
is not purely additive: a malformed `/api/registry/surfaces` body used to fall through to an
`Array.isArray(body.entries)` guard, and now hits the parse boundary — throwing in dev (caught by
the pre-existing handler, which retains the cached catalog and engages the first-install retry) and
failing open with a recorded drift in prod. That is exactly `OperationCatalogClient`'s behaviour, so
the two clients now agree, but it is a change and not a refactor.

**One file was edited outside the declared scope, and the reason matters.** Adding the parse
boundary turned three `SurfaceCatalogClient.test.ts` cases red — because their *wire* mock was built
by an FE-shaped fixture (`altitude` / `members` / `riskTier` / `stateSchema` omitted), which the
now-precise generated schema correctly rejects. The fix followed the precedent already set at
`OperationCatalogClient.test.ts:56-70`: keep the FE-shaped builder for the `__seedForTest` path and
add a wire-faithful builder for the boot-fetch path. The mock got *more* faithful; the boundary was
not weakened. Had those tests been "fixed" by loosening the schema, the whole S1 fix would have been
theatre.

**And a limit on the new exhaustiveness guard (S10).** `PLACEMENT_CLOSURE: Record<Placement, true>`
makes a *new* Java placement a compile error — the drift direction that matters, since a zone with
no landmark role is the accessibility hole `check-a11y-closure` exists to prevent. It cannot catch
the reverse (deleting an entry from `PLACEMENTS` while leaving the closure key), because that gate's
regex requires `PLACEMENTS` to stay a bare annotated literal, which erases its element literal
types. Stated rather than left for a reader to discover.

### I.10 [S8] The full suite, and the one red it surfaced

`./gradlew.bat test -PskipWebBuild=true` (the acceptance criterion PR 2 had not run):
**1080 tests completed, 6 failed, 2 skipped**. All six are `ProcessExtractionSandboxTest`, a class
this branch does not touch (last modified by #124).

Cause established rather than assumed, because the pinned worker-services shape would have made it
easy to wave off as load: it is **not** load-dependent — it reproduces isolated with `--tests`. The
real error is
`java.io.IOException: Cannot run program java.exe: CreateProcess error=206, The filename or
extension is too long`. The sandbox passes the entire Worker classpath on the child JVM's command
line, so every entry inherits the checkout prefix; under `.claude/worktrees/lane-B/...` the command
line crosses the Windows 32k limit. The other four failures in the class are the same `IOException`
surfacing as "expected `SandboxExtractionException` but was `IOException`". It is expected to pass
in the main checkout, whose path is ~25 characters shorter per classpath entry — which is why no
one has seen it.

Two consequences, both routed rather than noted:

1. Pinned as `process-extraction-sandbox-classpath-too-long` (`reviewBy: 2026-09-30`, exit probe =
   the isolated run), with the claim naming the 32k cause so the next agent does not re-derive it.
2. **RISK-010 is strengthened, not merely cited.** The row was opened for a missing argv; this is a
   *second, independent* obstacle — as currently invoked the sandbox cannot start at all on a long
   path. The row now says so, and says that any fix shipping an argv must also shorten the child
   command line (argfile or pathing jar). That is the risk register doing the job it was restored
   for: a measurement taken for an unrelated reason landed on the row that owns it.

Everything else in the suite is green, including the two new tests this PR adds
(`ApiSecurityFiltersTest` 4/4, `LocalApiServerFailClosedTest` 2/2). None of the six pinned flaky
shapes fired in this run.

### I.11 [S7] A second fail-closed test, at the layer that could silently undo the first

The review's point was precise: `ApiSecurityFilters` is constructed in `LocalApiServer`'s
constructor, deliberately outside the bind-retry `try`. Wrapping that construction in a `catch`
would restore fail-open with all four `ApiSecurityFiltersTest` cases still green, because none of
them goes through the assembly path.

`LocalApiServerFailClosedTest` drives the real one: a prod-mode `ConfigStore` (saved and restored
per case — `setGlobal` is JVM-wide and `modules/ui` runs many server tests in one JVM) and
`LocalApiServer.builder(...).build()` with no token, asserting the refusal carries the same wire
reason code. Its second case builds the *same* assembly *with* a token and asserts it binds — without
that, the first case would still pass if `build()` had been made to throw unconditionally in prod
mode, which would break the shipped launch path.

The `kind: test` probe pinning it is existence-only, so a third probe now pins the assertion itself:
`adr-0046-token-refusal-asserted` (`grep-present` on `assertThrows(\s*IllegalStateException`, no
`expect` count — the claim is "a refusal is still asserted", not a fixed number of cases). Gutting a
test body to a no-op keeps the name and loses the assertion; that is the gap it closes.

### I.13 Why the tempdoc-number collision was fixed at the scanner, not by renaming

`check-tempdoc-numbers` went red on this PR's two changesets (`gates/ts-any/.changesets/884-*` and
`gates/dead-code/.changesets/884-*`). The obvious remedy is to renumber one of them. It is the wrong
one, and the repo's own contents say so.

The changeset convention is `<tempdoc-number>-<slug>.md`, and **one tempdoc authoring several
changesets is already normal**: `563-*` x3 (`prose-tier-register`), plus `727-*`, `742-*`, `861-*`,
`581-*` and `854-*` pairs. Every one of those is within a SINGLE gate, so their labels
(`worktree:X:gates/<gate>`) matched and the rule never fired. This PR is the first in-flight case
spanning TWO gates. Renaming would also contradict the file's own frontmatter: the changeset loader
requires `tempdoc: 884` on both, so a filename claiming another number would make the name lie about
the body.

`divergentInFlightCollisions`' own comment already said what it meant — *"all from one worktree -> an
intentional single-author batch"*. Only the label granularity disagreed, because a changeset's label
carries its gate. The fix strips that suffix when counting distinct worktrees. Six checks in
`scripts/ci/test-tempdoc-scan.mjs` pin both directions, weighted toward the direction that matters
when loosening a collision rule: the genuine 553 cross-worktree case, two worktrees colliding inside
one gate, and two worktrees colliding across different gates all still fire.

### I.12 The full-kernel run found a gate that has been inert for seven weeks

Running the whole kernel (which B1 forced, and which PR 2 had never done) produced two results, and
the second matters more than the first.

**First, the summary line — which depends on WHERE in the branch you stand, and that is itself a
finding.** Run on the commit that introduced the changesets:
**35 gates evaluated, 0 fail, 117 findings**, exit 0, all 35 self-test fixture pairs expected. Run
again at the branch tip, several commits later: **35 gates evaluated, 2 fail, 117 findings** —
`ts-any` and `dead-code`, the two gates this PR wrote changesets for. The findings are identical; only
the verdict moved.

The cause is not the changesets. Both gates declare `diffStrategy: "git-base"`, whose docstring
promises "PR base ref with `HEAD~1` fallback" (`scripts/governance/lib/git-utils.mjs:36`) — but the
implementation (`:83-92`) is `const candidate = baseline.fallback ?? 'HEAD~1'` and never resolves a
PR base at all. The "fallback" is the only path. So changeset discovery looks at a one-commit window,
and a changeset committed earlier in the same branch has already fallen out of it by the time later
commits land. Recorded with both numbers rather than the flattering one: a summary line that is true
only at one commit is exactly the kind of claim this lane exists to stop.

This does not block the merge, and it is not lane B's to fix (§F row 11). Both gates are red on `main`
independently and neither runs in `ci.yml`; the merge queue squashes, so after merge `HEAD~1` IS the
base and the window is correct by accident.

A bare run without `--produce-inputs` additionally reports 4-5 `kernel/input-missing` failures, which
is the pinned `governance-kernel-inputs-unbuilt` condition, not a red.

**Second, what producing the inputs revealed.** `dead-code` failed with **27**
`dead-code/silent-growth` findings. Four are this branch's (the generated Surface projection, its
barrel re-export, and the composing `api/types/surface.ts` — a generated module exports its full
type surface by construction, which is why the same directory's `index.ts` is already baselined at
45 unused exports). **Twenty-three are not**, spread across `search-v3/*`, `packs.ts`,
`TaskList.ts`, `streams.ts`, `navigationHandler.ts` and a dozen more this branch never opened.

They surfaced together because the gate cannot run: it needs `tmp/knip-report.json`, and
`.github/workflows/ci.yml:206-207` says in its own comment that this job does not build knip / npm
audit / Gradle inputs. Locally the missing input reads as `kernel/input-missing`, which the
expected-state pin correctly calls an unbuilt report. So `dead-code` is registered, wired, baselined
— and has noticed nothing since `gates/dead-code/baseline.txt` was last touched on 2026-07-16 by the
PR that "revived" it.

**This is the same shape this program has now found three times**: `check-installer-execution-level.mjs`
invoked by nothing (PR 1 §D.3), the kernel's own 18 `*.test.mjs` files running nowhere (PR 1, fixed),
and now three kernel gates whose inputs no CI job builds. A gate that cannot fail is not a weaker
gate; it is a *claim* of enforcement, which is worse than none because the register reads as covered.

Handled honestly rather than quietly: one changeset
(`gates/dead-code/.changesets/884-surface-projection-plus-preexisting-drift.md`) classified
`merge-import` — because a changeset applies to the whole gate run, not per file, and
`declared-growth` would assert that lane B knowingly traded away dead-code hygiene in 23 files it
never opened. The changeset enumerates which four are ours and why, names all twenty-three as
pre-existing, and routes the two real fixes: wire these gates into CI with their inputs (or state
openly that they are local-only), then rebalance the baseline. Neither belongs in a governance-loop
PR that already touches the sibling `adr-coverage` gate.
