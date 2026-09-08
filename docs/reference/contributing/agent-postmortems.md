---
title: Agent Postmortems — Named Reference Cases
type: reference
status: stable
description: "Indexed reference cases (handle → paragraph) that lessons in CLAUDE.md and .claude/rules/agent-lessons.md cite. Principles live in those files; narratives live here so they don't bloat session context."
---

# Agent Postmortems — Named Reference Cases

Indexed reference cases that lessons in `CLAUDE.md` and `.claude/rules/agent-lessons.md` point to by handle. The principles live in those files; the narratives live here. New entries: keep each to one paragraph + one source citation.

---

## Handles → cases

| Handle | Section | Slice / tempdoc |
|---|---|---|
| `audit-without-test` | [§1](#1-audit-without-test--tempdoc-403-tier-c) | tempdoc 403 |
| `wrong-gate` | [§2](#2-wrong-gate--tempdoc-403-tier-b) | tempdoc 403 |
| `substrate-without-consumer-flavors` | [§3](#3-substrate-without-consumer-flavors--slice-447-x11) | slice 447 §X.11 |
| `independent-review-required` | [§4](#4-independent-review-required--slice-447) | slice 447 |
| `static-green-not-live-working` | [§5](#5-static-green--live-working--slice-447-x12) | slice 447 §X.12 |
| `verdict-is-gate` | [§6](#6-verdict-is-gate--slice-481) | slice 481 |
| `catalog-verbatim` | [§7](#7-catalog-verbatim--slice-486-27) | slice 486 §27 |
| `wire-emitter-elision` | [§8](#8-wire-emitter-elision--slice-447-x115-phase-3) | slice 447 §X.11.5 |
| `ai-offline-isnt-a-wall` | [§9](#9-ai-offline-isnt-a-wall--slice-497-v11) | slice 497 V1.1 |
| `standalone-capability-stays-stuck` | [§10](#10-standalone-capability-stays-stuck--tempdoc-521-merge-t25) | tempdoc 521 merge T2.5 |
| `native-callable-receiver` | [§11](#11-native-callable-receiver--tempdoc-560-28g) | tempdoc 560 §28.G |
| `unreachable-seed-green` | [§12](#12-unreachable-seed-green--tempdoc-618-10b) | tempdoc 618 §10b |
| `subset-isnt-the-suite` | [§13](#13-subset-isnt-the-suite--tempdoc-618-10c) | tempdoc 618 §10c |
| `green-masked-destructive` | [§14](#14-green-masked-destructive--tempdoc-618-3) | tempdoc 618 §3 |
| `dont-surface-orphaned-work` | [§18](#18-dont-surface-orphaned-work--session-2026-07-09) | session 2026-07-09 |
| `check-own-decisions-before-novel-gap` | [§19](#19-check-own-decisions-before-novel-gap--2026-07-teardowns) | 2026-07 teardowns |
| `deep-research-cost-gating` | [§20](#20-deep-research-cost-gating--2026-07-13-run) | 2026-07-13 run |
| `vague-greenlight-isnt-authorization` | [§21](#21-vague-greenlight-isnt-authorization--tempdoc-667) | tempdoc 667 |
| `premature-infeasible-verdict` | [§22](#22-premature-infeasible-verdict--tempdoc-668) | tempdoc 668 |
| `measurement-config-mismatch` | [§23](#23-measurement-config-mismatch--tempdoc-767) | tempdoc 767 |
| `delegated-wait` | [§24](#24-delegated-wait--session-2026-07-21) | session 2026-07-21 |

---

## 1. `audit-without-test` — tempdoc 403 Tier C

A subagent audit concluded `analyzerRegistry` was the only restart blocker in the (now-deleted) `LuceneLifecycleManager`. The partial fix shipped on that basis. A regression test for the restart path then revealed two more blockers: the state machine and `indexingCoordinator`. The audit was wrong; a runnable test would have caught it in minutes. **Principle**: a static audit is a hypothesis; the regression test is truth. The design that resulted was the phase-typed values + consumer-as-holder lifecycle pattern.

**Measurement flavour (tempdoc 841 §§10–11; figures below are recorded there with their methods — the corpus-wide ones are reproducible via `scripts/agent-analytics/cache-efficiency.mjs`, the duplicate-read ones came from a scratchpad probe 841 §11 deliberately did not ship and documents instead).** The same asymmetry holds when the artifact is a *number* rather than a code claim: **a scratchpad probe and a productized reader are not the same evidence tier.** Four findings in one session survived hand-probes and died on being rebuilt as a reader that had to run over the whole corpus using the repo's own established semantics — including two already reported to the founder. One inverted outright (subagents were measured at 2.6× *worse* context-efficiency than the main loop; applying the last-wins streaming-partial dedup that tempdoc 745 had already documented made them ~2× *better*). Another was a "corpus-wide" count taken from a probe that terminated early after five samples, so it described one file. **Two sub-principles worth naming:** (a) a case study with known ground truth is still n=1 — a cwd-relocation cause looked airtight in a session whose history was fully known, then died at n=2,900; (b) **the most convincing version of a finding is the one that agrees with an existing rule.** A duplicate-file-read result measured 10.2% of all context and pointed at exactly the files `context-efficiency.md` already warns about as "known large files"; that agreement is what stopped the search for a confound. Splitting by the requested `offset`/`limit` window showed 94% of those re-reads asked for a *different region* — the real figure was 0.3%. Agreement with an existing rule is when to look hardest, not least.

## 2. `wrong-gate` — tempdoc 403 Tier B

A code change keyed off `automationEnabled` when the intended gate was `justsearch.eval.mode`. Compilation + unit tests passed. The change would have had zero effect in the target scenario because the gate it checked is never set there. Caught only by the post-implementation critical-analysis pass. **Principle**: `grep` the set-site of any flag you depend on; symbol-exists ≠ gate-fires.

## 3. `substrate-without-consumer-flavors` — slice 447 §X.11

C-018 ("substrate-without-consumer") was being applied to type-system refactors (`OperationId` → `OperationRef`, record partitions, sealed-interface introductions). These do not introduce a new field for nobody to read — they relabel or regroup existing fields whose readers already exist. C-018 governs **new** substrate slots, not refactors of existing ones. The §X.11.5 follow-up Phases 1+3 proved the reframe by shipping the previously-deferred refactors. **Principle**: "no NEW consumer" ≠ "no consumer at all." If `grep -rn "fieldName"` shows >0 hits, the field has consumers.

## 4. `independent-review-required` — slice 447

Independent static review was deferred on 4 of 5 substrate-shipping slices in an autonomous run, each time with a "mechanical / narrowly-scoped" justification. When the deferred review was finally dispatched (447-followup/1.1) it caught: 3 substrate-without-consumer instances, a phantom field, a phantom schema URL, and a soft deferral. **Principle**: "small" or "mechanical" substrate-shipping commits are exactly when violations slip through. A type rename can ship two phantom records and still compile.

Internal long-form: `docs/reference/contributing/slice-execution.md` §"Pass 8 — Mandatory second-agent verification on substrate-shipping commits".

## 5. `static-green ≠ live-working` — slice 447 §X.12

Tier 1 (compile + unit tests) and Tier 2 (independent static review) were both green. Tier 3 (live-stack against a running dev stack) caught: a `HealthLitView`-vs-`HealthSurface` mix-up, a missing SSE subscription on `HealthSurface`, and a missing `mergePluginRecoveryOverlays` install path. **Principle**: each tier catches a defect class invisible to the others. Substrate with a user-facing reader must include Tier 3; pure-internal substrate may skip it.

## 6. `verdict-is-gate` — slice 481

Slice 481 (commit `d3ca31e3c`) shipped 3,217 LOC of substrate to `main` against a `requires-pass-3` reviewer verdict, citing a user mandate ("don't defer for substantial work") as override. Result: `Audience` and `ConsumerHook` fields landed without FE filter / NonEmpty enforcement consumers — the exact C-018 pattern the slice claimed to dissolve. **Principle**: an independent reviewer's verdict is a merge gate, not a discussion item. The mandate axis (scope + ambition) is orthogonal to the verdict axis (verification readiness). Override requires three artifacts: commit message naming the verdict + quoting authorization, CONFLICT-LEDGER row capturing the override + a named follow-up slice, and a committed slice ID for the deferred verification.

## 7. `catalog-verbatim` — slice 486 §27

The agent shipped two G36-widening features (per-pin run history; date-filtered saved searches) under IDs G34 and G37, but slice 486 §15.2 actually defines G34 = "profile-aware indexing" and G37 = "clipboard event stream." The agent read "next item" from a running summary instead of the catalog itself. **Principle**: when picking up a feature ID from a candidate-catalog slice, open the catalog row and read both the "Idea" and "Data dependency" columns verbatim before scoping. Misalignment = pick a new label and document why.

## 8. `wire-emitter-elision` — slice 447 §X.11.5 Phase 3

The Phase 3 partition added `availability` and `lineage` components to `Operation`, the schema baseline regenerated cleanly, and tests passed. But `UIOperationEmitter.toUIEntry` manually elided both — they were wire-invisible until `9c89d5b7f`. **Principle**: for every newly-added component on a wire-emitted type, verify the production emitter (e.g., `UIOperationEmitter`, `AgentOperationEmitter`) serializes it. One `git grep` query suffices.

## 9. `ai-offline-isnt-a-wall` — slice 497 V1.1

Three rounds of "live verification" stopped at `AI_OFFLINE` and declared "can't test end-to-end." `ai_activate` was one MCP call away (~11s warm cache). **Principle**: before declaring any verification tier unavailable, check whether you have a tool that provides it. The current environment state is not necessarily an immutable constraint.

## 10. `standalone-capability-stays-stuck` — tempdoc 521 merge T2.5

`AppFacadeBootstrap` constructed with `knowledgeServer=null` (the async-start path used by every dev-runner launch) created a standalone `WorkerCapability` at default state (`PENDING` / "Worker not yet connected"). When `connectKnowledgeServer` later ran, the KS bootstrap held its OWN distinct `WorkerCapability` instance which it then transitioned through `PENDING → READY` correctly — but never the AppFacadeBootstrap's standalone copy. Result: `/api/chat/agent`'s capability gate consulted the bootstrap's stuck `WorkerCapability` and rejected every request with 503 "Worker capability unavailable", even when the worker was functionally healthy (gRPC responsive, `ai_ready=true`, `embedding_ready=true`, `indexedDocuments=109`). Caught only by live verification of the 507/508 merge follow-up; static green + unit tests had nothing to catch because no test exercised the late-bind + cross-instance capability path. **Principle**: when a class constructs a Capability lazily because a dependency isn't ready yet, AND that dependency is later supposed to drive the Capability's state, AND the dependency holds its own Capability instance — the late-bind step must bridge the two via `addListener` (mirror initial state synchronously, then forward transitions). "I created the right object" ≠ "the right object's state will be updated." See `HeadAssembly.connectKnowledgeServer` (`AppFacadeBootstrap` at the time) (the bridge) and `WorkerCapabilityBridgeTest` (the regression test).

## 11. `native-callable-receiver` — tempdoc 560 §28.G

`RemoteTrustChannel` stored the native `fetch` as an instance field (`private readonly fetchImpl: typeof fetch = fetch`) and called it as `this.fetchImpl(...)`. A method call sets the receiver to the `RemoteTrustChannel` instance, and WHATWG `fetch` rejects a non-`Window`/`Worker` receiver with `TypeError: Illegal invocation`. `verify()`'s `catch` swallowed the synchronous throw → `untrusted('backend unreachable')`, and because the throw is synchronous **the verify POST never left the page** — so every URL-loaded plugin was silently forced UNTRUSTED and the operator allowlist could never take effect. The unit tests all injected `vi.fn()` doubles, which tolerate any receiver, so none exercised the native path; happy-dom's `fetch` likewise doesn't enforce the rule. Only the live browser surfaced it (a `static-green ≠ live-working` instance, §5). **Principle**: a native callable held as a field and invoked as `this.field(...)` loses its required global receiver — bind it once (`field.bind(globalThis)`, the codebase idiom in `CapabilitiesHandshake` / `ActionLedgerClient` / `OperationClient`), and because `vi.fn()` can't catch receiver loss, assert the binding *structurally* (the receiver handed to the underlying call is the global realm, not the instance) or live-verify. Fix + regression test: commit `06504087f` (`TrustChannel.ts` / `TrustChannel.test.ts`).

## 12. `unreachable-seed-green` — tempdoc 618 §10b

A reload-render unit test seeded the thread with `shapeId: 'core.rag-ask'`, but the real auto-restore path seeds the placeholder `core.free-chat`. The test passed on a seed the producer never emits — meanwhile the live UI mislabelled a Document-Q&A answer as "Chat — this mode does not search your documents." Only the live browser caught it. **Principle**: a hand-crafted test seed that does not mirror the *actual producer's* data flow yields confident-but-wrong green — it is the unit-tier face of `static-green ≠ live-working` (§5). Mirror the producer's real seed (read the set-site, don't invent a plausible one), or live-verify reload/persistence behaviour, which the project already requires for that tier.

## 13. `subset-isnt-the-suite` — tempdoc 618 §10c

Running the FE discipline gates *individually* (composition / run-renderers / steering / …) felt thorough but missed `execution-surface` — an extracted file had become an unregistered evidence referencer, invisible until the full `verifyGovernanceGates` ran at merge; likewise the full `npm run test:unit:run` was deferred to the review pass (it happened to be clean — luck-adjacent). **Principle**: a chosen *subset* of gates/tests passing is not "the gates passed." The full kernel + full suite are the only honest "done" — run them *before* declaring complete, not at merge, because a subset cannot see a finding that lives in the gate you skipped.

## 14. `green-masked-destructive` — tempdoc 618 §3

The 618 §3 dev-runner native-bin staging passed a live `ai_activate` → "GPU runtime activated" — green. But the activation only succeeded because the build had *already downloaded* the cuda12 variant; that green masked a destructive variant-only path that overwrote/pruned an Install-AI'd `variants/cuda12` runtime on a machine without the build's download. A second agent's review (and a re-read against the adverse precondition) caught it; the regression hard-breaks GPU activation with no recovery short of a ~3 GB re-install. **Principle** (an `interrogate-results` corollary): when a passing verification depends on an environment precondition, confirm the result holds *for the reason you think* by testing the **adverse** precondition (here: a variant-only Install-AI'd native-bin), not just the happy one — a green the environment happened to satisfy can hide the branch that fails everywhere else. Fix + guard: 618 Implementation (`hasAnyLlamaRuntime` read-only guard; the pruning `stageLlamaToDevNativeBin` Sync task removed).

## 15. `instrument-kills-spend` — tempdoc 624 twenty-first pass

Two healthy certified eval runs were aborted mid-spend because the live-monitoring projection
(`utility-status`/loss-accounting) computed exclusions as `planned − completed` — arithmetic correct
on a finished log, systematically wrong on a partial one, so every in-flight cell read as "excluded"
and phantom 40% exclusion alarms fired on runs that were in fact clean. The instrument had never been
validated on the partial-input case it was being trusted to judge. **Principle**: a monitoring or
verification instrument must itself be validated on the input regime it will actually see *before*
its verdicts are allowed to trigger destructive actions (aborts, spend cuts, rollbacks) —
`interrogate-results` applies to your own instruments first, because a wrong instrument converts
healthy work into losses with the full authority of "the data said so." Predictable evasion to
pre-empt: "the projection is simple arithmetic, it can't be wrong" — the arithmetic was correct; the
input contract was not.

## 16. `retyped-hash` — tempdoc 624 certified-run session

An orchestrating agent printed only the first 16 characters of a config-cohort hash for display, then
*retyped* a full-length value into the next command — fabricating the tail — and a record briefly
carried a fake identity key before self-review caught it one command later. **Principle**: values
whose exactness the record depends on (hashes, cohort keys, signatures, SHAs) are **piped, never
retyped** — capture into a variable or file and pass it mechanically; any content-bearing identifier
that transits through free text is presumed corrupted. Predictable evasion: "I can see the value
right there in the output" — the failure was not misreading, it was the truncated display plus the
fluency of plausible continuation.

## 17. `offered-not-asserted` — tempdoc 624 twenty-third pass

Every battlefield-era agent-utility run executed its with-tool arms against a **silently dead** MCP
config (a `url`-without-`type` server entry the CLI drops without warning): the harness empirically
asserted the *disallowed* tools per cell but never asserted the *expected* tool surface was actually
offered, so 260 certified "with-tool" cells contained zero tool invocations and a governed,
`comparable=True` record measured an A-vs-A replication. Five review passes, live smokes of adjacent
paths, and a populated tool-surface hash (computed from *outside* the cells) all failed to catch it.
**Principle**: for any treatment-vs-control measurement, assert the *presence and delivery of the
treatment itself* inside each treated unit, from the treated unit's own runtime disclosure — config
passed ≠ capability delivered, and negative guards (nothing forbidden happened) prove nothing about
the positive precondition (the thing being measured was there). Predictable evasion: "the config is
in the cohort identity, so it's covered" — identity recorded an *intention*, not a delivery.

## 18. `dont-surface-orphaned-work` — session 2026-07-09

Asked to "investigate main and plan the merge" with no diff of their own pending, the agent found 31 unpushed local commits on the shared main checkout (including CodeQL security work and executor-v2 design docs) belonging to other concurrent sessions, and surfaced this as a significant finding via `AskUserQuestion`. The user's answer was "leave it alone, don't even note it" — declining even a documentation-only record. A routine "check main before merging" step had turned into an unsolicited audit of other sessions' in-flight work. **Principle**: noticing another session's orphaned work is fine as far as confirming it isn't blocking you, but don't catalog it as a deliverable — "leave it alone" covers documentation too, not just action.

## 19. `check-own-decisions-before-novel-gap` — 2026-07 teardowns

In competitor/gap research, several items framed as "gaps we lack" turned out to be already-decided internal design space: an entailment citation scorer was ADR-0006 Alt B ("deferred") by name, context-prepend was tempdoc 636 P1a, and separately int8 quantization was recommended as new despite already being built. The error was systemic across both the engine and RAG teardowns, and only a later hardening pass — explicitly told to hunt already-built/already-decided work — caught it. Root cause traced to the brief: teardown agents were handed the competitor's feature set as ground truth with an instruction not to re-derive our own side, which steered them away from checking our own ADRs and tempdocs first. **Principle**: before framing anything as a novel gap, `grep` `docs/decisions/` and the relevant tempdocs for the mechanism and put what you find into the brief, asking "has parked path P's reassessment trigger fired?" rather than "do we lack X?".

## 20. `deep-research-cost-gating` — 2026-07-13 run

The built-in `deep-research` skill is expensive by design, not by bug: a measured run used 109 agents, ~2.7M tokens, 480 tool calls, and ~11.5 minutes, all on Opus, because the harness fans out multiplicatively (1 scope + 5 search + up to 15 fetch + up to 25 claims × 3-vote verify + 1 synth) with `MAX_FETCH`/`MAX_VERIFY` fixed rather than `args`-configurable. The actual failure was gating: the user's real request was a one-line casual question, never a request for a deep multi-source report, but the agent pattern-matched the skill's trigger text, self-fired the ~100-agent harness, and inflated the one-liner into a 6-part brief that multiplied the fan-out further. **Principle**: require genuine explicit opt-in in the user's own words before invoking `deep-research` or any `Workflow` — topical phrasing like "research this" is not opt-in, and if ambiguous, surface the expected cost/shape and ask.

## 21. `vague-greenlight-isnt-authorization` — tempdoc 667

The user said "work on making CI better" and asked which tempdoc to pick. After picking one and doing read-only measurement, the agent kept going without a check-in — editing `build.gradle.kts` (`maxParallelForks`) and running repeated local Gradle loops — and only reverted after being told "when did I tell you to proceed to implementation? revert your changes." The `/takeover` skill already encodes a read-only-investigation-then-verdict workflow, but this case is specifically about where the authorization boundary sits within that arc. **Principle**: picking or reopening a tempdoc and doing read-only investigation needs no permission, but the moment an action would change a file other than the tempdoc itself, stop and wait for an explicit instruction — a broad "work on X" is not authorization for the full pick-measure-implement arc.

## 22. `premature-infeasible-verdict` — tempdoc 668

On CI-latency work the agent repeatedly under-called the payoff and stopped too early: it claimed a "~2.8x" win from a partial run, then declared a full migration "blocked / pervasively Windows-coupled" based on truncated `--log-failed` summaries. When the user pushed back, pulling the actual test-results artifacts showed the "pervasive coupling" was mostly one attribute-read bug cascading across roughly 30 tests; the full migration then shipped a measured ~41% cut in CI wall-clock time. **Principle**: before concluding a lever is infeasible or reverting it, pull primary evidence — full stack traces and artifacts, not truncated summaries, and real measurements, not one partial run — and count root causes, since a scary failure list often collapses to a few shared ones.

## 23. `measurement-config-mismatch` — tempdoc 767

Preparing a paid + GPU certification run, an agent compared a fresh pilot measurement against a pre-registered threshold and read the shortfall as a property of the corpus. The certified `union_recall` for `en-legal-clerc-1k-verbose` was 0.75; the pilot returned 0.48 against a 0.65 floor, and the working explanation was that the rebuilt corpus had gotten harder. It had not: the pilot ran `--modes lexical,vector,hybrid` while the threshold was derived from a run whose leg set also included `splade`, and the `staged_recall_accounting` projection computes its union only over legs actually present (`scripts/jseval/jseval/projections/staged_recall_accounting.py:76`, `:240`) — an omitted leg is silently absent, not an error. Arithmetic settled it before any spend: 0.75 is unreachable from vector 0.5952 ∪ lexical 0.0193 (≤ 0.6145 even if disjoint), so a third leg must have contributed, and the pilot's union equalling its vector recall exactly showed only one leg was contributing. Left uncaught, every cell would have failed after the full spend, presenting as corpus regression and inviting a post-hoc threshold re-derivation. **Principle**: before comparing a measurement against a pre-registered threshold, verify the measurement *configuration* matches the one the threshold was derived under — and note that a structural "enough inputs present" check returning `ok` is evidence the run was well-formed, never evidence it was configured the same way.

## 24. `delegated-wait` — session 2026-07-21

A large MIRACL dataset fetch was handed to a subagent, which supervised it by polling in a loop. The agent produced four completion notifications, each reporting essentially a byte count, at roughly 240k tokens apiece — approaching a million tokens for no output beyond "still downloading". Meanwhile the download itself ran as a detached OS process that required no supervision at all: the wait was external, the progress was already durable on disk, and a single `ls -l` at the end would have carried the same information. The cost was structural, not a mistake in the brief — any agent asked to watch a long external operation will spend context proportional to how long it watches. **Principle**: launch long external work detached and check back with one command; delegate *decisions*, not waits — if the only thing a subagent will do is wait, there is nothing to delegate.

## 25. `parked-subagent` — five instances, 2026-07-21/22

Distinct from `delegated-wait` (#24), which is about the *cost* of watching: this is about the campaign *stopping*. Subagents park mid-campaign to "wait for a monitor/event" and stall silently — a stopped agent receives no events, so nothing resumes it and the campaign halts until the orchestrator notices. Nothing errors; the work simply stops. **Working remedy**: the brief mandates synchronous end-to-end execution with bounded in-turn condition-polls, and the orchestrator resumes any parked worker with that correction. Structural fix candidate: a standing line in the `subagent-guide` baseline brief.

## 26. `probe-reports-own-leak` — 2026-07-22

A title-prepend A/B declared SIGNAL on an arm that was prepending a gold-only title field — i.e. the answer key. The parallel probe, which carried pre-registered validity checks, caught the identical leak and discarded the arm. The difference was not skill or model but whether the validity rules existed *before* the numbers did. **Principle**: a measurement brief carries validity/leak checks for the probe itself, decided before results are seen — otherwise a probe can report its own contamination as a win, and the more favourable the result, the less likely anyone looks.

## 27. `chrome-tab-exhaustion` — live UI validation

Too many open tabs hang the claude-in-chrome extension during live UI validation; a restart clears it. There is no Chrome-process restart tool, and killing `chrome.exe` would destroy the user's entire browser session, so avoid both. **Recovery**: drain the MCP tab group instead — close its tabs with `tabs_close_mcp` one at a time (parallel closes race; verified, they leave the group in an inconsistent state), then recreate a clean group with `tabs_context_mcp { createIfEmpty: true }`.

## 28. `shared-worktree-checkout` — tempdoc 882, 2026-09-01

Three workers edited disjoint file sets in one worktree. One was briefed "no git command that writes" and "touch only the listed files". A docs-regen hint fired on its own doc edit; it ran the repo-wide regen script, saw its siblings' dirty files in `git status`, assumed it had caused them, and ran `git checkout --` on seven files it did not own. Three sibling edits were lost and had to be redone by the orchestrator. Two structural facts made the brief the only guard: git itself never blocks a single-file `git checkout -- <path>` (worktree or main), and parent-hook coverage was not established in that incident. Current Claude documentation says session-wide hooks apply inside subagents; verify actual event coverage (see [agent workflow](agent-workflow.md)). **Principle**: a brief for a worker sharing a worktree names `git checkout --`, `git restore` and `git stash` as forbidden outright, tells the worker that unexpected dirty files belong to siblings and are to be reported, never cleaned, and reserves repo-wide regen scripts for the orchestrator to run once at the end.

## 29. `falsify-restore-from-backup` — tempdoc 915, 2026-09-03

A falsification driver broke one guarded behaviour at a time, ran the tests that should notice, and
restored each file with `git checkout -- <file>`. Falsification runs against work that is by
definition **uncommitted**, so every restore reverted to `HEAD` and deleted the change being
falsified: five files in one pass, silently, and the driver's own end-of-run check reported the tree
clean because `git status` agreed with `HEAD`. Two further harness defects rode along in the same
script and both read as *results* rather than as bugs. A `str.replace` whose anchor no longer matched
applied no break at all, and the green run that followed was recorded as `NO FAILURE OBSERVED` — the
signature of a weak test. And the failure reporter walked every module's `build/test-results`, so one
case's failures were re-reported as every later case's, making seven cases look caught when one was.
**Remedy**: a break-and-restore driver restores from a byte copy taken immediately before the
break (`cp` aside, `cp` back) and diffs against that copy at the end — never `git checkout --`,
`git restore` or `git stash`, all of which are defined against `HEAD` and therefore against the
very work being falsified. Commit a WIP first as well when the campaign is long: a checkpoint
commit makes even a restore bug recoverable, which a backup file in `tmp/` does not if the driver
itself is what deletes it. The driver also asserts the break actually matched before it trusts
the run, and wipes the result directories per case. The general form — `NO FAILURE OBSERVED` is a
claim about the harness before it is a claim about the test, and it must be diagnosed as such.

**Follow-up, same tempdoc, round 5.** The rewritten driver reported `NO FAILURE OBSERVED` for all
nine cases on its first run. Nothing had run at all: `execSync` goes through `cmd.exe`, where
`./gradlew.bat` does not resolve, and the driver had just wiped the result directories — so "no
failures found" and "no tests found" were the same observation, exactly as in the original incident.
Per-case result wiping is necessary and it manufactures this ambiguity, so it must be paired with
its complement: **zero result XMLs for a case is a DRIVER ERROR, never a survived break.** With that
assertion the next run named nine driver errors instead of nine false negatives. (Two adjacent
Windows traps, both worth knowing: `.\gradlew.bat` inside a JS template literal silently becomes
`.gradlew.bat`, since `\g` is not an escape sequence — build the command from an absolute path
instead; and the same round found a *test* that survived its break because a null constructor
argument routed it down a different branch, which is the same lesson one layer up.)

## 30. `accepted-tracked-skills-no-removal` — PR #151, 2026-07

The four orchestration skills tracked on public `main` (`.claude/skills/{design,plan,takeover,theorize}`) were leaked once by a `git add -A`, and the owner has since accepted them as tracked. Do not open a PR to remove them; repeated removal PRs (e.g. #151) are unwanted. The same `git add -A` move is why `models/.gitignore` ignores the weights explicitly. Relocated from `branch-safety.md` by tempdoc 949 §5 (0 citations in 30 days, no enforcer).

## 31. `edit-reread-cross-root` — tempdoc 618 §11e / 727 F-7a

A worktree copy and a main-checkout copy of the "same" file do not share the `Edit` tool's read-state. Re-read the exact worktree-qualified path before editing, not "a" copy with the same basename; the failure reads as "file has not been read yet" or "modified since the last Read" (`scripts/agent-analytics/signature-census.mjs` classifies it). Relocated from `agent-lessons.md` by tempdoc 949 §5 (0 citations in 30 days, no enforcer).
