---
title: "Dev agent-tool surface audit: what agents actually invoke on the justsearch-dev MCP server, what fails, what nobody has ever called, and the two structural reasons 81% of local-API traffic bypasses the surface entirely"
type: tempdocs
status: "OPEN — analysis complete and reproducible (2026-08-18); nothing implemented. Owner decisions pending on D1-D4 (§8). Hot-reload coherence RESOLVED 2026-08-18: verdict INCOHERENT, independently re-verified against source (§5.6). Recommendation REVISED same day from RETIRE to FIX-THEN-SURFACE after owner challenge: the retire estimate did not survive re-examination and the value model (warm models across a code change, not a saved spawn) was never weighed — see §4.2. THEORIZED 2026-08-18 (§11): the endstate thesis is a single run registry as keystone, three tool classes with per-class middleware, and specialization into arbitration over transport; #845 reserved as the candidate registry lane. DIRECTION SET 2026-08-18 (12): the through-line is that the surface asserts what it has not verified (7 instances, 1 property); criterion = a dev tool must not report state it did not verify. 12.3 re-sequences 9 by that criterion and drops P3b (allowlist) + defers P8. IMPLEMENTED 2026-08-18 on branch worktree-844-dev-surface-honesty, NOT merged (see 13 As-built). Shipped: P1 prunes (tool set 16 -> 12), P2 start/preflight truth + DIST_NOT_BUILT, P3a projection honesty, P5 quick_health foreignRuns tri-state, P6 de-fork + check-dev-mcp-doc-sync gate + consult-register, P7 the standing reader. Dropped: P3b. ALL FOUR OWNER DECISIONS NOW LANDED (2026-08-19): D1 done incl. the gitignored main-checkout file; D2 = repair, hot reload made coherent (13.7); D3 = full, jseval registers its backend (13.8); D4 = option (b), the harness-vs-browser rule lives in the ui-check skill. LIVE-VALIDATED 2026-08-19 (13.9): P8 condition 1 is SATISFIED — bytecode pushed into a running Worker, pid unchanged across three reloads, encoders never unloaded, DevReloadManager reconstruction in 434ms. The live pass found four 12.2 violations in the repair itself (structural gate never fired; a failed push reported classes redefined; the identity refusal invented its cause; and start runs assemble, not installDist, so a Java edit never reaches the running Head) — all fixed as F1-F4. It also corrected 13.6 step 6, whose warmth assertion would have produced a false failure. Execution corrected this document three times - 3 was measured on a non-recursive glob that excluded subagents (379 -> 731 calls), 6.3 was a sync generator not a fork, and 5.2 s separator claim is retracted. Adopts 6 inbox conditions (§7) that should be closed by this lane rather than re-logged."
created: 2026-08-18
author: agent session b73007cd (Opus 5, 1M context) — chartered by the owner after a session-opening question about the state of dev-related MCP capabilities
category: agent-process / dev-tooling / mcp
related:
  - 254-mcp-dev-tools-issues              # the last dev-MCP audit (status done, 2026-03-03) — superseded by this doc
  - 305-hot-reload                        # shipped hot reload 2026-03-14, before worktrees/distFrom/leases matured
  - 606-dev-stack-takeover-owner-liveness # the ownership/verdict model, near-total adoption (160/162 starts)
  - 684-dev-workflow-tooling-hardening-batch # sibling surface (scripts/dev), same maintenance neighbourhood
  - 735-agent-surface-seam-consolidation  # owns the PRODUCT MCP surface — explicitly NOT this doc's scope
  - 770-agent-tool-surface-economy-lane   # product tool-surface economy — likewise out of scope here
  - 841-agent-prompt-cache-efficiency     # same corpus, adjacent method; its "no context bloat" finding bounds §6.4
  - 271-backend-lifecycle-isolation       # the ownership/interference model §11.3 extends; already flagged raw runHeadless
  - 542-operation-scoped-lease-taxonomy   # "extending the 271 ownership model" — the op-lease registry §11.3 builds on
---

> **Scope boundary.** This document is about the **dev** agent-tool surface — the
> `justsearch-dev` MCP server that agents use to drive the local stack, plus the
> non-MCP surfaces that compete with it. The **product** MCP surface (JustSearch
> exposing search to external agents) is owned by 735 / 770 /
> `843-deepseek-harness-relevance-and-mcp-interop` and is deliberately untouched here.
> Where the two meet (§5.3: `/mcp` is unreachable from the dev tools), the finding is
> stated as a dev-surface gap, not a product question.

## 1. Method, and what it can and cannot support

Corpus: `~/.claude/projects/F--justsearch-public*/**/*.jsonl` — **892 transcripts** (878 at first
measurement) spanning ~66 main agent sessions plus their subagents, ~6 weeks (the corpus starts
~2026-07-04 and runs to 2026-08-18). This is the same substrate
`841-agent-prompt-cache-efficiency` used.

**Method correction (2026-08-18).** The `**/*.jsonl` glob above is what this section always
claimed; the original ad-hoc extractor did **not** implement it — it read only the top level of
each project directory and so excluded subagent transcripts, undercounting by ~48%. Every number
in §3 is now produced by `scripts/agent-analytics/dev-tool-usage.mjs`, which walks recursively;
§10 is its spec. This is the audit's own §12.2 criterion applied to itself: the method asserted a
scope it had not verified it was reading.

Extraction: parse each transcript, index `tool_use` blocks by `id`, join `tool_result`
blocks by `tool_use_id`. Invocation counts are `tool_use` blocks only — a naive
`grep` over tool names double-counts, because each call appears once as the call and
again as the result reference, and every session's deferred-tool listing mentions all
16 names twice more. Error classification: `is_error === true`, or a result body
matching the literal ok-false or error-object markers.

**Honest limits.**

- Counts are *invocations*, not agents' intent — a tool never called may be unknown, broken,
  or genuinely unneeded, and the three are not distinguishable from counts alone. Each
  zero-use finding below therefore carries a separate cause, evidenced independently.
- The ok-false regex has a small false-positive rate: one `tail_log`-style result was
  classified as an error because the *worker log content it returned* contained the literal
  string. Error counts are therefore an upper bound, ±1 per tool.
- Byte totals in §6.4 are **bytes, not tokens**. Screenshot results are base64 images, which
  tokenize as images (~1-2k tokens each) regardless of their byte size. The byte ordering
  holds; do not convert it to a token claim without re-measuring.
- The corpus rolls. These numbers are re-derivable only while the transcripts survive —
  which is why P7 shipped `scripts/agent-analytics/dev-tool-usage.mjs` as the standing reader
  rather than leaving the analysis in a one-off script. Re-run it; do not trust these figures.

## 2. Inventory: four servers, and three competing non-MCP surfaces

| Server | Configured in | Tools | Note |
|---|---|---:|---|
| `justsearch-dev` | `.mcp.json` -> `scripts/dev/justsearch-dev-mcp/` (4,264 LoC) | 16 | project-owned; the subject |
| `github` | `.mcp.json`, `npx @modelcontextprotocol/server-github` | 26 | PAT is the placeholder literal (§4.1) |
| `claude-in-chrome` | harness | 24 | ungoverned by any repo rule (§6.4) |
| `library-docs` + Gmail/Drive/Calendar | user scope / connectors | 43 | incidental |

The dev surface does not stand alone. The same jobs are served by **`jseval`**
(91 subcommands, including an `ops` group with `dev`, `preflight`, `search`, `logs`,
`log-path`), by **`dev-runner.cjs`** invoked directly, and by **raw `curl`**. §6.1 shows
this is not redundancy-in-name-only: the surfaces cannot see each other's backends.

## 3. Measured usage — `justsearch-dev`, 731 invocations

> **Superseded numbers.** The first draft of this table counted 379 invocations. That extraction
> walked only the top level of each project directory, so it silently excluded **subagent**
> transcripts. Corrected below by `scripts/agent-analytics/dev-tool-usage.mjs` (P7), which walks
> recursively: **731 invocations over 892 transcripts**. Re-scoping that reader to main sessions
> only reproduces the original figures almost exactly (`fetch_api_json` 40, `ai_activate` 35,
> `ingest` 24, `api_call` 22, `preflight` 10, `tail_log` 10, `search_query` 8, `status` 1 — exact),
> so the original was right about what it measured and wrong about what it claimed to measure.
> Double-counting was ruled out directly: a `start` id sampled from a subagent transcript does not
> appear in its parent session's file — subagents genuinely invoke these tools themselves.

| Tool | calls | sessions | err% | 1st-call success | dominant failure |
|---|---:|---:|---:|---:|---|
| `start` | 162 | 17 | 12.3% | 86.0% | `UNHANDLED` x16 — "Head dist not found"; `OWNER_CONFLICT` x2; `INVALID_DIST_FROM` x2 |
| `stop` | 134 | 18 | 0 | 100% | |
| `quick_health` | 132 | 21 | 0 | 100% | |
| `fetch_api_json` | 81 | 9 | 12.3% | 89.2% | `jsonPath` miss; `response_too_large` |
| `ai_activate` | 60 | 8 | **50.0%** | **61.3%** | "Variant not installed: cuda12" |
| `api_call` | 57 | 9 | **42.1%** | **51.4%** | path-not-allowlisted |
| `ingest` | 40 | 10 | 10.0% | 91.7% | `path` vs `paths`; repoRoot confinement |
| `tail_log` | 28 | 8 | 7.1% | 92.3% | (includes the known false positive) |
| `preflight` | 19 | 12 | 0 | 100% | |
| `search_query` | 15 | 7 | 6.7% | 92.9% | |
| `status` | 2 | 2 | 0 | 100% | |
| `acquire_when_free` | 1 | 1 | 0 | 100% | |
| `reload` | **0** | 0 | — | — | registered, never invoked |
| `agent_chat`, `capture_evidence`, `validate_evidence` | **0** | 0 | — | — | never invoked (now unregistered, §4.3-4.4) |

**Totals: 731 calls, 12.4% error rate, 90.1% first-call success.** That last figure is the
pre-change baseline for §12.6's falsifier. The two tools dragging it down are `ai_activate`
(61.3%) and `api_call` (51.4%) — a coin flip on the first attempt.

Parameter adoption on `start` (162 starts): `leaseDurationSec` **160/162** (735 G6 essentially
complete), `distFrom` **125/162** (worktree-staged launch is the dominant mode, 77%),
`skipBuild` **112/162**, `hotReload` **1/162**.

`tools/list` payload: 16.3 KB (~4.1k tokens) + 1.5 KB `instructions`. The five zero-use
tools are 5.0 KB of that — **31% of the schema payload for 0% of the traffic.**

## 4. Prune candidates

### 4.1 `github` MCP server — remove from `.mcp.json` (P1)

One invocation in six weeks, and it **errored**. It cannot work: `.mcp.json` still carries the
literal placeholder PAT, identical to `.mcp.json.example`. Meanwhile agents made 478
`run-gh.mjs` and 347 raw `gh` calls — the `exec-substrate-hint` hook already routes them there.
Cost of keeping it: an `npx -y` package fetch + process spawn on every session start, and 26 tool
names in every prompt, for zero capability. The pinned package is also the archived upstream,
superseded by GitHub's own server.

**Residual manual step — the prune is not fully in effect until this is done.** `.mcp.json` is
**gitignored**, so the committed change lands in `.mcp.json.example` (tracked) and in
`.claude/settings.json`'s `enabledMcpjsonServers`, plus this worktree's own `.mcp.json`. Every
*new* worktree seeded by `prepare-worktree.cjs` therefore gets the clean config — but the **main
checkout's existing `.mcp.json` still registers `github`**, and no tracked change can reach it.
One manual edit there (drop the `github` block) completes D1; until then the server keeps
spawning for sessions started from the main checkout. Recorded here rather than done silently:
`branch-safety.md` reserves writes to the main checkout, and a machine-local config file is the
owner's to change.

### 4.2 Hot reload — FIX-THEN-SURFACE (P8; awaiting D2)

`hotReload: true` on 1 of 162 starts; `reload` invoked 0 times. The §5.6 investigation returned
**INCOHERENT**, and its load-bearing claims were re-verified independently against source (not
taken on the auditor's word); all five held. The *diagnosis* below stands in full.

**The recommendation was revised from RETIRE to FIX-THEN-SURFACE** (owner challenge, same day,
after re-examining the code the retire estimate rested on). Two things did not survive that
re-examination:

- **The estimate.** RETIRE rested on a "multi-day rebuild" figure driven mainly by the
  mixed-classpath item. But the Worker is launched with `-cp <workerLibDir>/*`
  (`WorkerSpawner.java:583-586`), and `addDevHotReloadFlags` already computes the exact classes
  dir 40 lines below (`:944-947`) and passes it as the sysprop nothing reads. Putting that path
  ahead of the jar wildcard, inside the existing `DEV_HOTRELOAD` gate, is the fix — which is what
  305 Phase 2's child classloader was for, reached by classpath ordering instead. Of the six
  repair items, two are trivial (ownership gate, `withStaleness`), two are small (compile root
  from the run record; classes-dir ordering), one is small-medium (per-run JDWP port plus an
  identity check — and the already-computed `DEV_HOTRELOAD_CLASSES_DIR` can serve as the identity
  token), and the sixth (JBR staging for structural changes) should simply be **dropped**:
  method-bodies-only is honest and already reported via `structuralChangeDetected`.
- **The value model.** RETIRE compared hot reload against "a restart" generically. What
  `DevReloadManager` actually preserves is `ModelContext` — embedding service, compat controller,
  NER, SPLADE — across a service reconstruction (`DevReloadManager.java:130-150`). In a repo whose
  warm path is ~40s to worker-ready largely because of ONNX loading, the capability is
  *keeping models warm across a code change*, not saving a process spawn. That was never weighed.

The zero-usage figure cannot arbitrate this: the feature is off by default and its documentation
describes a mode that does not exist, so 0/878 measures visibility, not value.

**Conditions attached to the repair**, so this does not become an open-ended rebuild:

1. **A live regression test is now required.** The audit's "a live test would add nothing" was
   sound *for retiring* — it cannot change an incoherence verdict, and demonstrating the cross-tree
   case means corrupting a peer's stack. It does not survive the switch to repairing:
   `DevReloadManager` has never been exercised in 878 transcripts, and "reconstruct services while
   keeping models alive" is where subtle state bugs live. Per `audit-without-test`, done means a
   green test that edits a method body, reloads, and asserts new behaviour over HTTP with encoders
   still warm.
2. **The ownership gate lands regardless of D2** — §6.2 covers `ingest`, `reindex`, `gc` and
   migration too, and must not ride on the hot-reload decision.
3. **Default it on and set a falsifier.** If the reload path is still unused ~6 weeks after the
   repair ships with `hotReload` defaulted true, retire it then, on clean evidence.

The capability is not merely unused — it is unsafe when used, and its documentation asserts a
behavior the code refutes:

- **The advertised degraded mode does not exist.** `server.mjs:623` tells agents "Without
  hotReload on start, reload still pushes bytecode (method-body changes only)". But
  `WorkerSpawner.addDevHotReloadFlags` returns early unless `EnvRegistry.DEV_HOTRELOAD` is true
  (`WorkerSpawner.java:940-941`), and that same early return guards the `-agentlib:jdwp` flag
  (`:952`). With no JDWP listener there is nothing to push to. 91 of 92 starts are in this state.
- **305 Phase 2 was never built.** `DEV_HOTRELOAD_CLASSES_DIR` is written at
  `WorkerSpawner.java:947` and declared at `EnvRegistry.java:954-955`. Those are its *only two*
  references outside build output — nothing reads it. The child classloader the sysprop exists to
  feed does not exist.
- **`HotSwapPush` has no target identity check.** It sets hostname `127.0.0.1` and a port and
  attaches (`HotSwapPush.java:85-91`). Whatever is listening on 5005 receives the bytecode.
- **`reload` has neither an ownership nor a staleness projection.** `buildOwnershipProjection` /
  `withStaleness` call sites end at `server.mjs:1879` / `:2451`; the reload handler begins at
  `:2551`. Confirms `docs/observations.md:354` at the code level.

**Predictable evasions to reject, in both directions.** If the decision is to retire: "disable it
now, sweep the code later" is the follow-up-that-never-comes pattern `retire-with-a-sweep` names,
and tempdoc 742's ~350-file corpus is what it produces — sweep in one change or keep it. If the
decision is to repair: "ship the fix, add the test after" is the same move wearing the opposite
costume, and condition 1 above exists to pre-empt it.

**Neither path closes §6.2 on its own.** Ownership gates only `start`, so `ingest`, `reindex`,
`gc` and the migration endpoints stay callable against a peer's stack whether `reload` is repaired
or removed. Retiring `reload` removes that hole's sharpest edge, not the hole. §11.4 argues the
class-wide fix is the same work either way.

### 4.3 `capture_evidence` / `validate_evidence` — retire the tools, KEEP the format (P1)

Zero calls, and the cause is recorded: `capture_evidence` **crashes on Windows** with a
libuv fail-fast (`Assertion failed: !(handle->flags & UV_HANDLE_CLOSING), src/win/async.c`)
after capturing api-status/api-health, forcing a fallback to manual HTTP
(`docs/observations.md:580`, 2026-07-07).

**Precision required here.** The EvidenceBundle *substrate* is live:
`scripts/evidence/validate-evidencebundle-v1.mjs` is used by the `installer_verify` job
(`.github/workflows/build-installer.yml:515-545`), which auto-enables bundle capture whenever
`CI` is set in the environment. Pruning the MCP wrappers must not sweep the schema, the
validator, or the CI path. This is the inverse of the usual `retire-with-a-sweep` error:
over-sweeping, not under-sweeping.

### 4.4 `agent_chat` — retire (P1)

Zero calls. Superseded in practice by browser-driven UI validation (1,217 `claude-in-chrome`
calls) and by `jseval` harnesses. No recorded defect; simply unused.

### 4.5 `status` -> fold into `quick_health` (P1)

2 calls vs 132 for `quick_health`, whose own description tells agents to prefer it. Fold as
`quick_health { detail: "full" }` rather than keeping two orientation tools.

### 4.6 Explicitly NOT a prune candidate: `acquire_when_free`

One call in the whole corpus — but it is the documented remedy for `OWNER_CONFLICT`, which
itself fired only twice. It is unused because contention is rare, not because it is wrong. Keep — and
fix the fact that it is missing from every document that lists the tools (§6.3).

## 5. Fix / extend items, ranked by measured pain

### 5.1 `start`'s worktree-dist failure — 16 of 20 `start` errors (P2)

Agents pass `distFrom: <worktree>`; `start` fails with error code **`UNHANDLED`** carrying a
perfectly actionable message ("Head dist not found at ...ui.bat. Make this checkout dev-ready:
`node scripts/dev/prepare-worktree.cjs`"). Two distinct defects:

1. A known, expected, recoverable condition is classified as an unhandled exception, and is
   not among the four admission error codes the reference doc documents.
2. `preflight` advertises that it checks "worker dist built" — but takes no `distFrom`, so it
   checks the *invoking* checkout while `start` checks the *target*. Preflight passes; start
   fails. That is a false green.

Two further failures were `INVALID_DIST_FROM` from passing a bare worktree name
(`"round14"`, `"validate-main"`) instead of a path — resolve those instead of rejecting them.

The fix was already proposed in the inbox on **2026-07-02** (`obs:dev-runner-drift`,
"worth a hook-hint or MCP tool default when sessionId resolves to a worktree cwd") and never
done. It has since produced 7 measured failures.

### 5.2 `ai_activate`'s 43% failure rate (P4)

Most failures are `Variant not installed: cuda12` on fresh worktree data dirs — the same
condition logged in a prior session's shard (`bccfc163`) on 2026-08-14 ("the two provisioning axes
(runtime exe vs. installed package) are not distinguished in the failure message",
`scripts/dev/dev-runner.cjs:457`). Three more are "No chat model configured", whose workaround
(`POST /api/settings/v2` with `llm.modelPath`) has been sitting in the inbox since 2026-06-06
marked "worth a /dev-stack skill note".

This matters more than its call count suggests: CLAUDE.md's `use-every-verification-tier` rule
**mandates** `ai_activate` before declaring an AI-facing feature verified. A mandated tool that
fails 43% of the time on a precondition trains agents to accept `AI_OFFLINE` as a wall — the
exact failure the rule exists to prevent. The precondition belongs in `preflight`/`quick_health`,
not in a post-hoc error.

One error *appeared* to carry a second bug — a "Configured model does not exist" message rendering
the path as `F:justsearch-publicmodelsQwen_Qwen3.5-9B-Q4_K_M.gguf`, with the separators gone.
**Retracted to SUSPECTED-UNCONFIRMED on re-examination, and most likely an artifact of this
audit's own extraction.** The producing site is
`RuntimeActivationService.java:814`, which formats `"Configured model does not exist: " + model`
where `model = Path.of(modelPath.trim())` (`:812`) — `Path.toString()` preserves separators on
Windows, so the Java side looks correct. The transcript stores that string JSON-escaped
(`\\` per separator), and §1's extractor did no unescaping, which is a sufficient explanation for
the observed rendering without any product defect.

Recorded rather than deleted because the distinction matters: a stored `llmModelPath` that really
had lost its separators would produce the identical symptom. Settling it needs one look at a live
`GET /api/settings/v2` — cheap, but not done here. **Do not hand this to an implementer as a bug
to fix.** It is exactly the confirmation trap `interrogate-results` names: the finding matched an
expectation ("Windows path handling is fragile") and was written down before its cause was
established.

### 5.3 The 81% bypass (P3 for coverage; the preference half is §6.1)

268 raw HTTP calls to the local API from Bash vs 138 through the MCP read tools
(`fetch_api_json` 81 + `api_call` 57) — still roughly 2:1 toward the bypass.

| Path | Bash hits | Reachable via MCP? |
|---|---:|---|
| `/mcp` | 43 | NO — not allowlisted |
| `/api/status` | 30 | yes (`fetch_api_json`) |
| `/api/knowledge/status` | 21 | yes (`api_call`) |
| `/api/health` | 19 | yes (`fetch_api_json`) |
| `/api/mcp/token` | 10 | NO |
| `/api/knowledge/search` | 9 | yes (`search_query`) |
| `/api/knowledge/retrieve-context`, `/api/memory`, `/api/knowledge/disposition`, `/v1/models`, `/api/chat/ask`, `/api/ai/install/plan-preview`, `/api/inference/runtime/manifest` | 25 | NO |

Two separable problems. **Coverage**: the NO rows are simply missing, and `/mcp` +
`/api/mcp/token` at 53 combined hits is the single largest gap — agents dogfooding the product
MCP endpoint cannot reach it from the dev tools. Add also `/infra/capabilities` and
`/infra/health`, which **CLAUDE.md Hard Invariant #4 names as the way to verify `host.*`
contract versions** and which the dev surface cannot reach at all (already logged,
`docs/observations.md:356`, 2026-07-16). A Hard Invariant pointing at an endpoint the sanctioned
tooling cannot call is a contradiction that should not survive this lane.

**Preference**: even covered endpoints are hit with `curl`. §6.1 explains most of it.

### 5.4 Projection asymmetry and the expensive-failure default (P3)

- `fetch_api_json` has `jsonPath`; `api_call` does not — an agent tried and got
  `unrecognized_keys`. This matters because search results average 10 KB.
- `fetch_api_json`'s `jsonPath` is a naive dot-path `reduce` (no array indexing), and **on a miss
  it discards the parsed JSON and returns the raw text tail** — the most expensive possible
  failure mode. Returning the available keys at that level instead converts a token bomb into a
  hint. Most of `fetch_api_json`'s errors are this.
- `maxBytes` reads as a truncation budget but is a hard fetch cap that errors `response_too_large`.
  Agents passed `maxBytes: 2000`, and `outputMode: "compact"` with `maxBytes: 3000`, to *reduce*
  output and made it fail instead — twice. Either truncate with a notice, or rename it.

### 5.5 `ingest` vs the mandated scratchpad (P3)

`ingest` rejects any path outside `repoRoot`. The harness *mandates* the session scratchpad for
temporary files. An agent staged a corpus there, as instructed, and was refused
(`ingest path must be under repoRoot`). Two agents also passed `path` instead of `paths`.
Direct rule conflict; allow the session scratchpad, or say why not.

### 5.6 Hot reload — INCOHERENT (verdict 2026-08-18)

The hypothesis in the first draft of this section — that `reload` resolves its *target* from one
root and its *bytecode* from another — is **CONFIRMED**, and the picture is worse than the
hypothesis.

`reload` reads `dataDir` from the global run record under `mainRepoRoot`
(`server.mjs:2573-2576`) but derives `classesDir`, `hotSwapScript` and the Gradle `cwd` from the
caller's `repoRoot` (`:2582-2583`, `:2592`), which is `process.cwd()` frozen at MCP-server launch
(`paths.mjs:29-42`; `.mcp.json` sets neither cwd nor env). `start` honours `distFrom` by swapping
to an effective root (`server.mjs:700-721`); `reload` has no equivalent. Under `distFrom` — 68 of
92 starts — the two roots are different trees **by construction**.

**The three cases:**

| Case | Outcome |
|---|---|
| (a) main-checkout stack, main-checkout caller | works — but only with `hotReload: true`; otherwise silently misfires |
| (b) `distFrom: A`, caller in worktree A | works only if the MCP server's cwd happened to be A *and* `hotReload: true` — coincidence, not design |
| (c) `distFrom: A` by agent A, caller agent B in worktree B | **silently misfires**: B's bytecode is redefined into A's JVM, reported as success |

**Four silent failure modes** (none surfaces as an error to either agent):

1. **Cross-tree injection.** `redefineClasses` matches by class *name* only
   (`HotSwapPush.java:111-118`), so B's divergent bytecode replaces A's in-memory code.
2. **Falsified build stamp.** On `hotSwapOk` the handler copies the *caller's* dist stamp into the
   *owner's* data dir (`server.mjs:2632-2640`), defeating 371's stale-JVM detection for everyone
   afterwards. (Precision: this write *is* gated on `hotSwapOk` — but see #4 for why that gate is
   weak.)
3. **Unauthorized service teardown.** The signal write (`:2645-2652`) is gated only on
   `signalFile` being non-null — **not** on `hotSwapOk`. A deliberate comment at step 3 says
   "Continue to signal write so service reconstruction still happens", so even a *failed* push
   quiesces and reconstructs a peer's Worker services.
4. **False success with zero classes pushed.** If no changed class is loaded in the target VM,
   `HotSwapPush` prints "None … are loaded", exits 0 and still updates its marker file
   (`HotSwapPush.java:121-123, 150`) — so the next call sees nothing changed either, and
   `hotSwapOk` is `true` throughout.

**Mixed-classpath state is reachable even in case (b).** The running Worker's classpath is the
`installDist` jar set; the push comes from `build/classes/java/main`. Only already-loaded classes
are redefined; the remainder later load from the **stale jar** — while the stamp says current.

**Was it ever coherent?** Briefly. 305 (2026-03-14) assumed one checkout, one stack, one agent,
one JDK, and verified exactly that. Four things invalidated it: worktrees + `distFrom` split the
launch tree from the caller tree; the lease/ownership model added an owner concept `reload` never
adopted; 696's JDK pinning hard-selects Temurin ≥24 while structural hot-swap needs a JBR that
nothing in the repo stages, leaving method-bodies-only; and Phase 2's classloader was never built.

**A live test would add nothing.** Every step is statically decidable, and the only way to
demonstrate case (c) is to corrupt a peer's stack. One item is UNVERIFIED and does not need a run:
whether this machine's ambient `JAVA_HOME` happens to point at a JBR build — the repo stages none,
so Temurin is the default.

## 6. Structural findings

### 6.1 Two dev-stack lifecycles that cannot see each other

The MCP tools resolve the backend from the dev-runner's `run.json`. `jseval` starts its own
backend on a hardcoded `33221` (`scripts/jseval/jseval/backend.py:21`; `commands/_common.py:14-22`
documents the two base URLs as deliberately not a dedupe target). Agents mention `jseval` in 1,406
Bash commands against 379 total MCP calls — so during the single most common activity, the MCP
tools are structurally blind and `curl` is the rational choice.

An `apiPort` escape hatch exists on the read tools (`resolveApiBaseUrl`,
`server.mjs:588-592`) and would bridge this today, but it is documented nowhere and was used 8
times in the main-session scope. On the full corpus it is used 58 times across nine distinct
ports — including **33221 three times**, i.e. agents did occasionally point the MCP read tools at
the `jseval` backend. That corrects the first draft of this section, which claimed 33221 was never
targeted. The conclusion is unchanged and arguably sharper: the escape hatch works and is
discoverable enough to be used, but `quick_health` still cannot *see* that backend, so the
orientation tool stays blind to the thing the read tools can already reach.

This has already cost a measurement. A prior session shard (`bccfc163`), 2026-08-14: *"A runHeadlessEval
Head+Worker started outside the dev-runner is invisible to `quick_health` (lease only knows runs
it started), so a 'free' verdict can precede a 100%-GPU neighbour — contaminated a measurement
round."*

Minimum fix: `quick_health` probes for *any* JustSearch backend on the known ports and reports
unowned ones. Better fix: `jseval`'s backend registers in the same run registry, so one authority
answers "what is running".

### 6.2 Ownership gates only `start`

`docs/observations.md:354` (seen: 3, first 2026-06-17): a non-owner can `POST /api/knowledge/ingest`,
`/api/indexing/reindex|gc|migration`, or `reload` against a peer's running stack with no owner
check. Ownership grants the right to *spawn*, not exclusivity over the *mutating* surface. Under
3-4 parallel agents this is a live corruption path, and §5.6 may show `reload` is its sharpest edge.

### 6.3 A sync generator kept a wrong doc faithfully duplicated

**Corrected diagnosis (2026-08-18).** The first draft of this section called the two files a
*fork*. That was wrong about the mechanism, and the truth is a better finding.

`docs/reference/contributing/mcp-dev-tools.md` and `.claude/skills/dev-stack/SKILL.md` shared
**114 identical lines**, including the same tool table and the same *incorrect* endpoint mapping
(`effective_config` -> `/api/config/effective`; the code maps it to `/api/debug/effective-config`).
Those lines were **machine-generated**: the skill carried explicit
`<!-- generated:start ... source: docs/reference/contributing/mcp-dev-tools.md -->` markers
(`SKILL.md:73-241` at the pre-change commit), and `scripts/docs/skills-sync.mjs` inlined the
reference into it from a `SKILLS` manifest entry.

So the two files could never drift *from each other* — they drifted **together, away from the
code**, and a `skills-sync --check` run stayed green throughout. That is the sharper lesson:

> **A generator that syncs one prose file into another provides the *appearance* of a consistency
> guarantee while guaranteeing nothing about correctness.** It kept the copy perfectly faithful to
> a wrong original, and its green check was evidence of nothing.

It also validates the remedy independently of the original (mis)diagnosis: the fix is not
"de-duplicate the prose" but **replace prose-to-prose sync with a code-to-doc gate**. The skill now
links to the reference rather than inlining it, the `dev-stack` entry is gone from the `SKILLS`
manifest, and `scripts/ci/check-dev-mcp-doc-sync.mjs` asserts the reference against the *running
server*. Two prose copies agreeing was never the property worth enforcing.

Tool count as stated: **15** in the reference doc ("exactly these tools"), **15** in the skill,
**15** in the server's own `initialize.instructions` (`server.mjs:611`), **14** in the harness
header (`justsearch-dev-mcp-harness.mjs:7`). Actual: **16**. `acquire_when_free` is registered
(`server.mjs:1857`) and appears in none of them.

**Make that five inventories, not four.** The P6 sweep turned up a fifth in `scripts/README.md`
(§MCP Integration), still advertising the *legacy underscore* names and a
`justsearch_dev_wait_ready` tool that no longer exists at all — the reference doc had already
declared those obsolete. Nothing pointed the two files at each other, so one was retired in prose
while the other kept listing it. Five independent statements of one fact, no two agreeing, none
checked against the code: the register gap below is not an abstraction.

Also drifted out of the doc: `/api/indexing-roots/substrate`, `/api/indexing-roots/preview`,
`/api/indexing-jobs/failed/by-prefix`, `/api/action-ledger` are allowlisted in code and absent
from the reference's allowlist table.

**And a second wrong endpoint this audit missed, which the gate caught.** The doc mapped
`status` -> `/api/knowledge/status` (`mcp-dev-tools.md:76` at HEAD); the code maps it to
`/api/status` (`server.mjs:936`). Those are different payloads, and `status` is the most-used
fetch key on the surface — so the single most consulted row of the table was wrong, and this
prose audit did not notice while checking the row directly beneath it.

That is the argument for P6 in one observation: a careful human pass over a 15-row table found one
of the two errors in it. The gate found both on its first run, and found them by construction
rather than by attention. Prose review does not scale to inventory correctness; a check that reads
the running server does.

**Nothing in `governance/consult-register.v1.json` covers `scripts/dev/`.** This is exactly the
`representation-drift` class the register machinery exists for, in the one place the register does
not reach — so every fix in §5 will silently re-drift unless a sync check lands with it.

### 6.4 Cost is inverted relative to governance

Tool-result **bytes** across the corpus (73.3 MB total): `claude-in-chrome` is **49.7 MB (68%)**
from 647 calls (`browser_batch` 172.9 KB/call, `computer` 39.1 KB/call). All of `justsearch-dev`
is **0.3 MB (0.4%)** from 379 calls. *(Bytes, not tokens — see §1. Images tokenize differently;
the ordering holds, the multiple does not.)*

> **Correction (2026-08-19, pre-merge scan).** The **68%** figure is wrong as stated: its 73.3 MB
> denominator was not all tool-result bytes. Re-measured over the full corpus, total tool-result
> bytes are **244.2 MB**, of which `claude-in-chrome` is **24.8%** — behind `Read` (39.3%) and
> `Bash` (26.1%). What survives unchanged: `claude-in-chrome` is **~98% of MCP result volume**
> (verified 98.5%), `justsearch-dev` is **0.3%** of all tool-result bytes, and the browser is the
> most expensive *MCP* capability by ~87x — so §6.4's argument (cost is inverted relative to
> governance) holds; only the "two-thirds of everything" magnitude does not. The number was caught
> because it was the one sampled claim the shipped instrument could not re-derive:
> `dev-tool-usage.mjs` reports MCP servers only, so a non-MCP denominator has no instrument behind
> it. `.claude/skills/ui-check/SKILL.md` is corrected accordingly.
>
> **Root cause — the same defect as §3's, not a second one.** The 73.3 MB denominator came from the
> original non-recursive extraction that excluded subagent transcripts (§1's method correction).
> Subagent sessions are dominated by `Read` and `Bash`, so excluding them deflated exactly the
> denominators that outweigh the browser, while `claude-in-chrome` — used almost entirely from main
> sessions — was barely affected. One measurement bug therefore produced two wrong numbers in
> different sections, and fixing §3 did not fix §6.4 because the byte totals were never re-derived.
> The lesson generalizes past this document: when a method defect is found, every figure computed
> with that method needs re-deriving, not just the one that surfaced it.

Browser use is concentrated in 17 sessions and is mostly local (`127.0.0.1` dev UI), i.e.
overlapping `jseval ui-shot`'s territory — which writes PNGs to disk and returns a `.measure.json`,
and whose own skill says to judge from the measure facts, not the PNG. The remainder is genuine
external design research (motion, magicui, tailwindui) that `ui-shot` cannot do.

Meanwhile CLAUDE.md and `.claude/rules/*.md` say **nothing at all** about the browser surface.
The most expensive agent capability is the least governed one. This is not an argument to ban it —
it is an argument that the always-loaded layer is allocating its bytes by habit rather than by cost.
Note `841-agent-prompt-cache-efficiency` measured no context bloat overall, so this is a
*composition* question, not a volume alarm.

## 7. Inbox conditions this lane adopts

These are already in the conditions store and should be **closed by this lane, not re-logged**.
Several are at `seen: 3-4` — the repeat counts are evidence the surface has an unworked backlog,
not that the notes were unclear.

| Condition | Lands in | Outcome (2026-08-18) |
|---|---|---|
| `obs:dev-runner-drift` — `distFrom` default drift, hook-hint proposed 2026-07-02 | §5.1 | **closed** — `preflight` takes `distFrom` and shares `start`'s resolver; bare names resolve |
| `obs:server` — ownership gates only `start` (seen: 3) | §6.2 | **open** — not addressed. The §11.4 Class-C middleware is the fix; out of scope here |
| `observations.md:356` — `/infra/capabilities` + `/infra/health` unreachable | §5.3 | **closed as won't-fix**, with reasoning recorded in §12.3 (the invariant names an endpoint, not a transport) |
| `observations.md:580` — `capture_evidence` libuv crash on Windows | §4.3 | **closed** — the crashing tool is gone; the EvidenceBundle format and its CI consumer are untouched |
| `observations.md:355` + `:470` — fresh worktree has no chat model; settings/v2 workaround undocumented | §5.2 | **partly closed** — the workaround is documented; the precondition is still not surfaced pre-flight (§13.2) |
| shard `bccfc163` 2026-08-14 x2 — cuda12 variant message; runHeadlessEval invisible to `quick_health` | §5.2, §6.1 | **the second is closed** — `foreignRuns` reports unowned backends. The cuda12 message is unchanged |

Three of six fully closed, one partly, one deliberately won't-fixed with reasons, one left open and
named. Recorded this way so the next fold does not re-open what was decided, and does not mark
closed what was not.

## 8. Owner decisions

> **All four decided 2026-08-18/19.** D1 **yes** (done, incl. the gitignored main-checkout file).
> D2 **repair** — FIX-THEN-SURFACE, in progress. D3 **full** — `jseval` registers in the run
> registry, which *overrides* the recommendation below; the minimum shipped first regardless, so
> the full version is now an extension of working code rather than a bet. D4 **option (b)** — the
> rule lives in the `/ui-check` skill, not the always-loaded layer.

- **D1 — prune `github` from `.mcp.json`?** Recommend yes (§4.1). No capability is lost; it has none.
- **D2 — hot reload: repair or sweep?** No longer blocked. §5.6 returned INCOHERENT; §4.2 now
  recommends **FIX-THEN-SURFACE** — five scoped items (JBR staging dropped), a required live
  regression test, default-on, and a ~6-week falsifier. Estimated at a focused day with tests,
  not the multi-day rebuild the first pass assumed. The capability being bought is warm models
  across a code change, not a saved process spawn. Choosing RETIRE instead is still defensible if
  the answer to "does anyone iterate on Worker Java against a live stack?" is no — but that is an
  empirical question the falsifier settles, and §11.6 argues the repair is mostly Class-C
  middleware that §6.2 needs regardless.
- **D3 — unify backend discovery?** Minimum (`quick_health` probes unowned backends) vs full
  (`jseval` registers in the run registry). Recommend the minimum first; it is the half that already
  cost a measurement.
  **DECIDED: full.** The minimum shipped (P5, `foreignRuns`), so the port probe is the *fallback*
  and registration becomes the authoritative path — which is the right layering: a register only
  covers what registers itself (§11.3), and the probe is what keeps it honest about the rest.
  Scope: `jseval`'s backend writes a run record the dev-runner's readers already understand, so
  `quick_health` reports identity (tree, owner, GPU-bound) instead of "something is on 33221".
  This is the §11.3 keystone in its smallest useful form — **not** a general registry project;
  §12.4's warning still binds.
- **D4 — govern the browser surface?** A short rule on when `claude-in-chrome` beats
  `jseval ui-shot`. Costs always-loaded bytes, which the 620 ratchet caps — so this is a genuine
  trade, not a free add.
  **DECIDED: option (b) — DONE.** The rule lives in `.claude/skills/ui-check/SKILL.md`
  (`rule:harness-for-assertions`): *use the instrumented harness for anything you will assert on;
  use the browser for things you are only looking at.* Placed there rather than in `CLAUDE.md`
  because the always-loaded budget had 2 bytes of headroom and the skill's own trigger
  ("capturing UI screenshots") fires exactly when the choice is made. The legitimate browser uses
  (external research, unfamiliar flows, live console/network debugging) are named explicitly so the
  rule steers local dev-UI verification rather than discouraging exploration.

## 9. Proposed sequencing

> **Execution order superseded by §12.3.** The table below defines the *items* (P-ids are stable
> references used throughout this document); §12.3 re-orders them by the honesty criterion and
> drops two — P3b (allowlist additions) and, for now, P8. Read §12.3 for what to do and in what
> order; read this table for what each item is.

| # | Item | Depends on |
|---|---|---|
| P1 | Prune: `github` server; `agent_chat`, `capture_evidence`, `validate_evidence` wrappers; fold `status` into `quick_health`. Sweep the count in all four inventories (§6.3) in the same change. | D1 |
| P2 | Fix `start`: real error code for missing dist, `preflight { distFrom }`, bare-worktree-name resolution. | — |
| P3 | Allowlist gap: `/infra/capabilities`, `/infra/health`, `/mcp`, `/api/mcp/token`, `/api/chat/ask`, `/api/knowledge/retrieve-context`, `/api/memory`. Add `jsonPath` to `api_call`; `jsonPath` misses return available keys; `maxBytes` truncates instead of erroring; `ingest` accepts the session scratchpad. | — |
| P4 | `ai_activate` preconditions surfaced in `preflight`/`quick_health` (the runtime-variant check especially). The separator claim is retracted to suspected — see §5.2; not implementer work. | — |
| P5 | `quick_health` probes for unowned backends (§6.1). | D3 |
| P6 | De-fork the doc/skill; add a `check-dev-mcp-doc-sync` gate asserting tool list + endpoint keys + allowlist against `server.mjs`; register `scripts/dev/` in the consult register. | P1-P5 |
| P7 | Repeatable reader in `scripts/agent-analytics/` so §3 is re-derivable after the corpus rolls. | — |
| P8 | Hot reload — act on D2 (§4.2, §5.6). **If FIX-THEN-SURFACE (recommended):** resolve the compile root from the run record (mirror `start`'s swap at `server.mjs:700-721`); put the classes dir ahead of the jar wildcard inside the existing `DEV_HOTRELOAD` gate (`WorkerSpawner.java:583-586`, using the path already computed at `:944-947`); add the ownership gate + `withStaleness` (Class-C middleware, §11.4 — §6.2 needs these regardless); record a per-run JDWP port in `run.json` and have `HotSwapPush` verify the target VM's `DEV_HOTRELOAD_CLASSES_DIR` matches its own source tree before pushing; drop JBR/structural. Then default `hotReload` on, land the live regression test (§4.2 condition 1), and set the ~6-week falsifier. **If RETIRE:** remove in ONE change — tool registration `server.mjs:2549-2662`; `ReloadInputSchema` `schemas.mjs:734-744`; the `hotReload` start param + `--hot-reload` plumbing (`schemas.mjs:101-102`, `cli.mjs:352`, `dev-runner.cjs:154/213/1462-1465`); `scripts/dev/HotSwapPush.java`; `WorkerSpawner.addDevHotReloadFlags` + the `DEV_HOTRELOAD*` `EnvRegistry` rows; `DevReloadManager` + its `KnowledgeServer` gate and sentinel branch (`KnowledgeServer.java:698-702, 1625-1627`); the `worker-services/build.gradle.kts:100-109` doLast hook; the doc rows in `docs/reference/configuration/environment-variables.md` and `runtime-config-ownership-matrix.md`; and the false claim at `server.mjs:623` — then grep the retiree names to confirm zero residue. Either path: full `./gradlew.bat build -x test` + the dev-stack smoke. | D2 |

P6 is the one that must not be dropped: without it, P2-P5 re-drift and this audit gets rewritten
in six months, as it was rewritten from `254-mcp-dev-tools-issues` (2026-03-03, status done).

## 10. Reproducing §3

Parse `~/.claude/projects/F--justsearch-public*/**/*.jsonl`; per file, build a map from
`tool_use.id` to `tool_use.name` for names starting `mcp__`, then join `tool_result.tool_use_id`
back to it. Count calls from `tool_use` blocks only. Classify errors as `is_error === true`, or a
joined result body containing the ok-false or error-object markers, and pull the error code from
the `code` field. For §5.3, scan `Bash`/`PowerShell` `tool_use` inputs for a `127.0.0.1:<port>`
prefix followed by a path, and histogram the path. For §6.4, sum result-content lengths (text
blocks plus `source.data` for images) per tool name.

## 11. Theorization — what the endstate should be

> Not design. This section explores framings, tensions and alternative directions before
> anything is settled. Several ideas here are recorded because they may be useful later even
> if they are not the answer. Where a claim is speculative it is marked as such.

### 11.1 The reframe: three control planes over one machine, with no shared model of it

§2 lists the surfaces as if the problem were redundancy. It is not. `justsearch-dev`,
`jseval` and `dev-runner.cjs` do overlap, but the damage does not come from overlap — it comes
from the fact that **none of them can see what the others started**. MCP knows only runs it
spawned; `jseval` knows port 33221; a `runHeadlessEval` JVM is invisible to both.

Read that way, most of §5 and §6 are one defect wearing six costumes:

| Symptom | Underlying absence |
|---|---|
| §5.1 `start` fails on a dist it never checked | no authority says which tree a run was built from |
| §5.6 `reload` compiles in one tree, pushes into another | same |
| §6.1 `quick_health` reports "free" next to a busy GPU | no authority enumerates unregistered runs |
| §6.2 ownership gates only `start` | no authority to check at the other call sites |
| §5.3 agents reach for `curl` | no authority to point a tool at |

That suggests the endstate is not "fewer tools" or "better tools" but **one authority for what is
running, projected everywhere and re-derived nowhere.**

### 11.2 What is this surface actually *for*? — and a pattern that predicts bypass

Three candidate purposes, and the measured data discriminates between them:

- **Convenience** (saves typing `curl`) — *refuted*. 81% of local-API traffic bypasses it (§5.3).
- **Legibility** (structured, projected state instead of log-grepping) — *partly supported*.
  `quick_health` is the third most-used tool at 0% error.
- **Arbitration** (leases, ownership verdicts, admission control) — *strongly supported*, and it
  is the only purpose nothing else can serve.

Sorting the tools by altitude — in the sense of the `surface-altitude` axis this repo already
applies to product surfaces — the pattern is sharp:

| Altitude | Tools | Calls | Error | Bypassed? |
|---|---|---:|---:|---|
| Arbitration | `start`, `stop`, `quick_health`, `preflight`, `acquire_when_free` | 239 | 0-11% | no — no alternative exists |
| Transport | `fetch_api_json`, `api_call` | 62 | 18-45% | yes, ~4:1 by `curl` |
| Domain | `search_query`, `ingest` | 32 | 13% | yes, by `jseval` |

**Conjecture: tool altitude predicts bypass.** A transport-altitude tool competes directly with
`curl` and loses on composability — it cannot be piped, filtered, retried in a loop, or embedded
in a script. A domain-altitude tool competes with `jseval` and loses on depth. An
arbitration-altitude tool has no competitor, because arbitration requires shared state that only a
long-lived authority can hold.

If that conjecture survives scrutiny, the endstate is: **specialize hard into arbitration and
projection; stop competing on transport.** Note this argues *against* part of §5.3 — adding
allowlist entries may be treating the symptom. See §11.5.

### 11.3 The keystone: a run registry

The single structural idea this document points at. One authority answering, for every
JustSearch process on the machine:

*what is it, who owns it, which tree was it built from, which ports does it hold, what is its
health, when was it last touched, and is it GPU-bound?*

Written by whoever spawns a run (`dev-runner`, `jseval`, `runHeadlessEval`, a bare
`gradlew runHeadless`), read by everything else. Consequences, roughly in order of value:

- `quick_health` reports unregistered neighbours instead of a false "free" (§6.1 — this already
  cost one measurement round).
- `reload` and every other mutating operation resolve their inputs from the run record rather
  than from a cwd frozen at session-inject time (§5.6).
- `preflight` can check the target tree, not the invoking one (§5.1).
- Ownership becomes checkable at every call site, not just `start` (§6.2).
- `ui-shot`'s auto-serve and `serve-worktree-fe` can bind to a known backend rather than sniffing.

This is the same medicine as the `execution-surfaces` register and `553-representation-drift`,
applied to **runtime state** instead of to types: one canonical source, everything else a
projection. That parallel is worth taking seriously — it suggests the register pattern this repo
already trusts for representations generalizes to processes.

**Prior art — this extends an existing lineage rather than starting one.**
`271-backend-lifecycle-isolation` (done, 2026-03-10) established the multi-agent ownership and
interference model, and already named *"raw `:modules:ui:runHeadless` use without full isolation
overrides"* as a risk. `542-operation-scoped-lease-taxonomy` (done, 2026-05-21) — subtitled
"extending the 271 ownership model" — added a Layer-2 op-lease registry at
`tmp/dev-runner/op-leases.json`. Both work: lease adoption is 160/162.

What neither covers is the part this section is about. Both register what the dev-runner *started*;
neither enumerates what it did not — `jseval`'s 33221 backend, a bare `runHeadless`, a stale JVM
squatting a port. That is the gap the shard of 2026-08-14 recorded as a contaminated measurement,
and 271 flagged its seed five months before jseval's backend widened it. So the registry idea is
an extension in the same line 542 already walked, not a new invention — and the fact that 271's
foreign-process risk stayed open across two follow-on lanes is itself mild evidence it needs a lane
rather than another passing mention.

Open question: is registration *enforceable*? A register only covers what registers itself. A bare
`gradlew runHeadless` will always be able to skip it. So the registry probably needs a discovery
fallback (port scan, or process enumeration by command line) to stay honest — which is also how it
would surface the neighbours it was never told about. Speculative; unmeasured.

### 11.4 Three tool classes, with enforced middleware per class

If the registry exists, the tool surface falls out of it naturally:

- **Class A — arbitration.** Owns the registry. `start`, `stop`, `acquire_when_free`,
  `quick_health`, `preflight`. Already the healthiest part of the surface.
- **Class B — read projections.** Cheap, composable, never fail expensively. Must satisfy: a
  missing path returns *available keys*, not a raw dump (§5.4); size limits truncate with a notice
  rather than erroring; every projection carries staleness.
- **Class C — mutations against a run.** `ingest`, `reindex`, `gc`, `migration/*`,
  `worker/restart`, and `reload`. Every one of these should pass the same middleware:
  **run resolution -> ownership check -> staleness projection.** Today each does its own thing or
  nothing at all.

Class C is the interesting one, because it closes §6.2 and §5.6 with a single mechanism instead of
per-tool patches. It also gives a principled answer to a question the current design answers
arbitrarily: *which operations need a lease?* Answer: exactly the ones that mutate a run.

### 11.5 The allowlist may be a tax, not a control

Worth stating plainly because it cuts against §5.3's proposed fix. `api_call` rejects any path not
on an explicit allowlist. Five of its ten observed failures were rejections of **legitimate dev
endpoints**. Meanwhile any agent that wants a non-allowlisted endpoint simply uses `curl`, which
they did 268 times.

**A safety control with a trivial, sanctioned bypass is not a control — it is a tax on the
compliant path.** The allowlist cannot prevent an agent from calling an endpoint; it can only make
the good path worse than the bypass.

If the real fear is *accidental destructive calls against someone else's stack*, then the
mechanism is wrong twice over: the risk is not "which path" but "which run, and whose". That is
Class C's ownership middleware, not a path list. A plausible endstate: **drop path allowlisting;
keep the loopback guard; classify operations as read vs mutating; gate mutations on ownership.**
Fewer moving parts, no maintenance list to drift, and it defends against the thing that can
actually happen.

Counter-argument worth preserving: an allowlist is also documentation — it tells an agent what is
*intended* for dev use. That value is real but could be served by a projection ("here are the
endpoints this stack exposes") rather than by a gate that refuses.

### 11.6 Hot reload is a symptom, not an independent defect

Under §11.3/§11.4, hot reload stops being a special case. Every one of its confirmed failures is
an instance of a class problem: it resolves its root from the wrong place (no registry), it does
not check ownership (no Class C middleware), it falsifies a staleness stamp (no staleness
projection), and it attaches to a port without identity (no run record to identify against).

That reframes the repair. Rather than patching `reload`'s root resolution specifically, give it
the resolution and middleware that every Class C tool should have — and hot reload gets fixed as a
side effect of fixing the class.

It also clarifies what is genuinely *good* about it and worth preserving: `DevReloadManager`
carries `ModelContext` across a service reconstruction, so encoders stay loaded
(`DevReloadManager.java:130-150`). In a repo whose warm restart is ~40s largely because of ONNX
model loading, **preserving loaded models across a code change is the actual value proposition** —
not "avoiding a restart" in the abstract. Any endstate that keeps hot reload should keep it for
that reason; any endstate that drops it should acknowledge that is the capability being dropped.

Unresolved tension worth flagging honestly: CLAUDE.md routes implementation to delegated workers
who mostly verify with unit tests plus a build. If very few agents iterate on Worker Java against
a live stack, hot reload is excellent and almost unused regardless of quality. That is an
empirical question, not an architectural one, and it should be settled by a falsifier rather than
by argument.

### 11.7 Alternative direction: allocate instead of arbitrate

A hidden assumption runs through the whole surface: **one dev stack at a time.** The entire
ownership apparatus — leases, verdicts, takeover policies, `acquire_when_free`, contention
handling, displaced notices — exists to arbitrate access to a singleton.

But *why* is it a singleton? Memory and ports are the stated reasons; GPU is the real one. A
no-AI backend on an allocated port may not need to be exclusive at all. If so, a large part of the
arbitration machinery is solving a self-imposed constraint, and the endstate could be:

- **GPU-bound runs**: arbitrate (the lease model, unchanged — it works, 160/162 adoption).
- **Everything else**: allocate a port, register it, run as many as fit.

This would dissolve rather than solve most contention friction, and it fits the parallel-agent
reality (3-4 sessions) better than a queue does. Speculative and unmeasured — the memory ceiling
on this machine is the obvious thing that could kill it, and nobody has measured how many non-AI
backends fit. Recorded because it is the kind of assumption that goes unexamined precisely because
the machinery around it works well.

### 11.8 A better north star than tool count

Tool deferral changes the economics: names are cheap in the prompt, schemas load on demand. So
"prune to save tokens" is a weak argument — §3's 31%-of-schema-payload figure is real but small.

The dominant cost of a bad tool is not its schema, it is **failure**: a 45%-error tool costs a
round trip, a recovery decision, and often a fallback to `curl` anyway. So the metric the endstate
should optimize is **first-call success rate**, not tool count. That reframes several items:
`api_call` at 45% is a worse problem than five unused tools; `ai_activate` at 43% is worse than
both. It also gives P7's reader a purpose beyond reproducing this audit — it becomes the standing
instrument for the metric.

### 11.9 The browser/harness division

`claude-in-chrome` is 68% of tool-result bytes and is mentioned in no repo rule. The endstate
principle that seems right: **use the instrumented harness for anything you will assert on; use
the browser for things you are only looking at.** An assertion taken from a screenshot is not
reproducible and cannot be gated; `jseval ui-shot`'s `.measure.json` is both. That leaves the
browser owning genuine exploration — external design research, unfamiliar flows, one-off
debugging — which is what a meaningful share of its use already is.

This is a rule about *evidence*, not about cost, which is why it belongs in the always-loaded layer
despite the 620 byte ratchet: it changes what counts as verification.

### 11.10 The dev surface should consume the product surface's own research

The most under-exploited asset here. This project has spent several lanes (725, 732, 735, 770)
measuring what makes an MCP surface legible and cheap for an agent consumer — response shape,
delivery tiers, truncation cliffs, payload bytes that carry no content, actionable errors. **None
of it has ever been applied to the dev surface**, which is an MCP surface with agent consumers.

`fetch_api_json` returning a raw text tail on a `jsonPath` miss (§5.4) is the same defect class
770 measured on the product side. The dev tools' error messages are mostly not actionable, which
is exactly what 725 fixed for product responses. The endstate should treat the dev surface as a
*consumer* of those findings — and, more interestingly, as their **testbed**: it is the one MCP
surface whose agent population is fully observable in transcripts, which the product surface's is
not. That is a genuinely useful asymmetry and it has never been used.

### 11.11 Hidden assumptions, and what would falsify this

- **"Agents should use MCP tools."** Maybe not. For a coding agent with a shell, a CLI may simply
  be the better substrate, and the honest endstate might be a *minimal* MCP surface (arbitration
  only) plus a good CLI. §11.2's altitude conjecture points this way. Falsifier: if transport-tool
  usage does not recover after §5.4's projection fixes, the tools are not the problem — the
  modality is.
- **"Usage reflects value."** Contaminated for anything off-by-default (`hotReload`, 1/162) or
  undocumented (`apiPort`, 8 uses). Zero usage of an invisible feature measures visibility.
- **"The singleton is necessary."** §11.7.
- **"The allowlist protects something."** §11.5 — no evidence of prevented harm; measured evidence
  of friction.
- **"This audit is representative."** One machine, one owner, ~6 weeks, a corpus that rolls.
  Everything here is about *this* development environment.

### 11.12 Candidate follow-up

The run-registry idea (§11.3) is larger than this lane and outlives it — it touches `dev-runner`,
`jseval`, the MCP surface, `ui-shot`'s auto-serve and the ownership model. It would be the third
step in the 271 -> 542 line (see §11.3 prior art), extending ownership from *runs we started* to
*every run on the machine* — not a new concept, and it should be framed that way to avoid forking
authority away from 542's op-lease registry. If it is worth its own lane, **#845 is the next free
number** (verified against `world-state.mjs`; note #843 is already
double-claimed across worktrees, and #840/#842 have live merge-gate collisions). Not created here
— whether the registry is a lane or just this lane's P5 is an owner call, and creating a second
tempdoc speculatively is exactly the kind of residue `retire-with-a-sweep` warns about.

## 12. Direction — the finding under the findings

### 12.1 One missing property, not seven bugs

Every significant defect in this document is the same defect: **the tooling asserts things it has
not verified.**

| Assertion | Reality |
|---|---|
| `preflight`: "worker dist built" | it checked the invoking tree; `start` uses `distFrom`'s |
| `quick_health`: "free" | it can only see runs it started (§6.1) |
| `reload`: `hotSwapOk: true` | true when zero classes were pushed, and when the JVM is not the one it compiled for |
| `reload`: build stamp written | makes a stale JVM report as current (§5.6) |
| tool description: "still pushes bytecode without hotReload" | no JDWP listener exists at all (§4.2) |
| reference doc + skill: "exactly these 15 tools", `effective_config` -> `/api/config/effective` | 16 tools; the endpoint is `/api/debug/effective-config` (§6.3) |
| `start`: `UNHANDLED` | a condition it fully understands, with a remedy in the same message (§5.1) |

Seven items, one property. And the property has a name in this repo already: the product surface
has extensive machinery for never claiming more than it knows — citation-coverage honesty,
degradation reason codes, `searchTraceExplain`, the honest-baseline work, and `verify-don't-guess`
as a Hard Invariant. **The dev surface has none of it.** The tools agents use to verify the
product are held to a lower evidentiary standard than the product.

### 12.2 The criterion

> **A dev tool must not report state it did not verify, and must not report success it did not
> confirm.** Where it cannot verify something, it says so — an explicit unknown beats a confident
> default.

This is a filter, not a project. Its value is as much in what it excludes as in what it selects:
it says *stop*, which a sorted error column never does.

### 12.3 Re-sequencing by the criterion

Applied to §9, the order changes and two items drop out:

| Order | Item | Kills which false claim | Verdict |
|---|---|---|---|
| 1 | **P6** doc/skill de-fork + `check-dev-mcp-doc-sync` gate + consult-register entry | false inventory — and it is the only item that stops every other fix re-drifting | promoted from last to first |
| 2 | **P5** `quick_health` reports backends it did not start | false "free" — the one that already cost a measurement round | promoted |
| 3 | **P2** `start` error code + `preflight { distFrom }` | false green, false severity | unchanged |
| 4 | **P4** `ai_activate` preconditions surfaced in `preflight`/`quick_health` | a mandated tool that surprises 43% of the time | narrowed — the separator half is retracted (§5.2) |
| 5 | **P3a** projection honesty only: `jsonPath` miss returns available keys; `maxBytes` truncates with a notice; `api_call` gains `jsonPath` | dishonesty by omission — a miss currently returns the most expensive possible payload | narrowed |
| 6 | **P7** repeatable reader in `scripts/agent-analytics/` | nothing — but it is the instrument the falsifier needs | kept, and now load-bearing |
| 7 | **P1** prunes (`github` server; `agent_chat`, `capture_evidence`, `validate_evidence` wrappers; fold `status`) | nothing — hygiene, but cheap and it shrinks what P6 must keep true | demoted |
| — | **P3b** allowlist additions | **dropped** — §11.5: a control with a sanctioned bypass is a tax, not a control |
| — | **P8** hot reload | **still D2** — not an honesty fix; blocked on an owner decision |

Two drops matter more than the promotions. Adding allowlist entries would have treated the
symptom of a mechanism §11.5 argues should not exist. And hot reload, despite being the most
interesting thing in this document, is not on the critical path of anything.

**The one loose end the P3b drop leaves, stated rather than buried.** §5.3 argued that
`/infra/capabilities` being unreachable from the dev tools is a contradiction, because CLAUDE.md
Hard Invariant #4 names it as the way to verify `host.*` contract versions. Dropping P3b leaves it
unreachable *by MCP*. That is acceptable, and the reason is §11.5's: the invariant says use the
endpoint, not use a particular transport, and `curl` reaches it today — which is what agents
already do 268 times over. The contradiction was never between the invariant and reality; it was
between the invariant and the assumption that the MCP path is the sanctioned one. §11.2 says that
assumption is what should give way.

If that reasoning is wrong, the fix is not a new allowlist row — it is the §11.5 endstate (drop
path allowlisting, keep the loopback guard, gate mutations on ownership), which is a separate
decision and not smuggled in here. P6's sync gate will at least keep the documented allowlist
honest about what the code actually permits in the meantime.

### 12.4 What not to build

**Not the registry as a project.** §11.3 is the right *shape*, but the honest version is a
fraction of the work: `quick_health` saying *"one run I own; plus an unidentified JVM holding
33221 and the GPU, which I did not start"* is honest, cheap, and captures nearly all the value. A
cross-surface registry with enforced registration is a cathedral for a surface taking ~6 calls per
session.

Keep the proportion in view: `justsearch-dev` is **0.4%** of tool-result bytes (§6.4). The work
actually happens in `bash` and `jseval`. Harm here is not proportional to volume — one contaminated
measurement is expensive — but that argues for cheap honesty, not for infrastructure.

### 12.5 Where the actual upside is

The strongest idea in §11 is §11.10, and it is worth more than the whole fix list: **this is the
only MCP surface whose agent population is fully observable.**

725, 624 and 770 spent real campaign money trying to answer why agents do or do not invoke an MCP
surface, what response shape they tolerate, and where the truncation cliff bites. Those questions
are structurally hard on the product side because external agents' transcripts are not visible.
Here they are — ~66 sessions of ground truth, continuously, for free, over the same protocol with
the same kind of consumer.

That asymmetry has never been used. The direction worth taking after the honesty work is to stop
treating the dev surface as a maintenance cost and start treating it as the instrument that
de-risks product-surface questions *before* they are worth a campaign. P7's reader is the first
step of that, which is why it survives the re-sequencing despite fixing no bug.

### 12.6 Falsifier

**First-call success rate** (`scripts/agent-analytics/dev-tool-usage.mjs` is the instrument, shipped
under P7). Definition: within one session, an invocation is a *retry* iff the immediately preceding
invocation of the same tool in that session errored; the rate is successful non-retry calls over
non-retry calls, so one incident is not double-counted and a successful retry does not masquerade
as a first-try success.

**Pre-change baseline, measured 2026-08-18: 90.1% overall** (731 calls, 12.4% error rate). The two
tools dragging it down are `ai_activate` at **61.3%** and `api_call` at **51.4%**.

If the rate does not move after items 1-5 land, then the tools were never the problem — the
modality is, and the right answer is to shrink the MCP surface to arbitration only and let agents
use the shell for everything else. §11.2's altitude conjecture already predicts that outcome; this
is written down in advance so the result cannot be re-interpreted after the fact.

**One tension this metric exposes, stated rather than smoothed over.** `api_call` is the
second-worst tool by first-call success, and its dominant failure is precisely the one §12.3
*dropped* — path-not-allowlisted. Under the plan as sequenced, `api_call` should therefore barely
improve. That is not an oversight: §11.5 argues the correct fix is deleting the allowlist
mechanism, not extending it, in which case `api_call`'s first-call success would approach 100%
because nothing would be rejected. The drop is a bet, and this metric makes it a **testable
prediction** rather than a preference — if items 1-5 leave `api_call` near 51% while everything
else improves, that is evidence *for* the §11.5 endstate, not against the sequencing.

> **The falsifier is under-specified as written, and must not be run without this caveat.**
> It has a numerator and no denominator. "`reload` still unused after six weeks" has two
> incompatible readings:
>
> - nobody wanted it → retire, correctly; or
> - **nobody was ever in a position to want it** → the measurement said nothing at all.
>
> Those are indistinguishable today, and the second is live: hot reload needs one agent to hold a
> stack *and* edit Worker Java in the same context, while this repo routes implementation to
> delegated workers who are kept off the shared stack by the lease model. Every worker brief in the
> lane that built this feature forbade touching the stack — so the conjunction the capability
> depends on may simply never occur.
>
> **Before judging D2's falsifier, measure the opportunity rate**: how often does a single session
> both hold a live run and edit `modules/worker-services/**/*.java` while that run is active? It is
> computable from the same transcript substrate `dev-tool-usage.mjs` already walks (join
> `start`/`stop` against `Edit`/`Write` on Worker Java paths, per session). If that count is near
> zero, a usage count of zero is uninformative, and the real question is not whether to retire hot
> reload — it is whether a worker should be allowed to drive a stack inside a supervised window it
> holds. That is a §11.4 / lease-model question affecting far more than one tool, and it would be
> its own lane.

## 13. As-built (2026-08-18)

Implemented on `worktree-844-dev-surface-honesty` against the §12.3 order. Nothing merged.

### 13.1 What shipped

| Item | Outcome |
|---|---|
| **P6** doc/skill de-fork + sync gate | `scripts/ci/check-dev-mcp-doc-sync.mjs` spawns the real server and compares `tools/list`, the `fetch_api_json` endpoint map and the `API_CALL_ALLOWLIST` against the reference **in both directions**; co-located test, 25 assertions, including "an empty tool list fails loudly instead of passing". Surface registered in `governance/consult-register.v1.json`; Pre-merge row added |
| **P5** `quick_health` sees foreign backends | `foreignRuns` reports backends the dev-runner did not start, incl. `jseval`'s hardcoded 33221. Tri-state enforced: `null` = did not look, `[]` = looked and found nothing. No subprocess |
| **P2** `start`/`preflight` truth | One shared `resolveDistRoot` used by both, so `preflight` checks the tree `start` will use. `DIST_NOT_BUILT` replaces `UNHANDLED`, classified in `dev-runner.cjs` with path + remedy. Bare worktree names resolve; unknown ones list what exists |
| **P4** `ai_activate` preconditions | Folded into the `preflight`/`quick_health` work above. The separator half was retracted (§5.2) rather than implemented |
| **P3a** projection honesty | `jsonPath` miss names the deepest resolved level + available keys and **withholds the body**; array indexing added; `api_call` gained `jsonPath` (one implementation, two callers); `maxBytes` truncates with an explicit notice instead of failing the call |
| **P7** the instrument | `scripts/agent-analytics/dev-tool-usage.mjs` + test. Baseline recorded in §12.6 |
| **P1** prunes | `github` server, `agent_chat`, `capture_evidence`, `validate_evidence` removed; `status` folded into `quick_health {detail:"full"}`; `acquire_when_free` kept. Tool set: **12** |

Verification: doc-sync gate OK · gate test 25 · 36 unit + 18 live-stdio assertions across two new
test files · `test-ownership-verdict` 34/34 · `test-dev-runner-admission` 6/6 · agent-analytics
33/33 files · `skills-sync --check` OK · `check-premerge-table` and `check-always-loaded-budget`
pass. No Java or Gradle sources changed, so no Gradle build was run — stating that rather than
implying a build verified something it could not.

### 13.2 What did not ship, and why

- **P3b (allowlist additions)** — dropped by §12.3, not forgotten. §11.5's argument stands.
- **P8 (hot reload)** — untouched, pending owner decision **D2**. No `reload`, `hotReload`,
  `HotSwapPush.java` or `DEV_HOTRELOAD` code was modified.
- **D1 is not fully in effect.** `.mcp.json` is gitignored; see §4.1's residual manual step.
- **`ai_activate`'s runtime-variant precondition is only partly addressed.** `preflight`'s models
  and llama-variant checks still resolve against the *invoking* checkout, not `distCheckRoot`.
  Only the dist checks moved. The code says so at the call site rather than letting the reported
  `distCheckedRoot` imply more coverage than it has — but a fresh worktree can still surprise
  `ai_activate`, and that is the residue of §5.2.

### 13.3 What the execution found that the audit had not

Four things, three of which corrected this document:

1. **§3 was measured on the wrong corpus.** P7's reader showed the original extractor read only the
   top level of each project directory, excluding subagent transcripts — a ~48% undercount
   (379 -> 731 calls). §1 now carries the correction. The audit asserted a `**/*.jsonl` scope it
   had not verified it was reading: §12.2's criterion, failing on the audit itself.
2. **§6.3's mechanism was wrong.** The 114 shared lines were machine-generated, not copy-pasted.
   A generator syncing prose into prose kept a wrong doc faithfully duplicated while its `--check`
   stayed green. The remedy survived the correction; the diagnosis did not.
3. **The gate found an error the prose audit missed** — `status` documented as
   `/api/knowledge/status` against `/api/status` in code. A careful human pass over a 15-row table
   caught one of its two errors; the gate caught both, by construction.
4. **A fifth stale inventory** (`scripts/README.md`) still advertised a `wait_ready` tool that no
   longer exists.

### 13.4 Honest record of process failures

Recorded because a lane about tooling honesty cannot be selective about its own.

- **The P8 worker started a dev stack it was explicitly forbidden from starting**, by running
  `scripts/dev/justsearch-dev-mcp-harness.mjs` to check the tool inventory — without reading it
  first. Its header says it does `preflight -> start -> quick_health -> stop`. The stack started
  from this worktree and stopped cleanly (`active.json` absent, all three pids dead, data dir
  removed), and no other agent held the lease, so nothing was damaged — but the harness is not a
  read, and the two safe inventory checks (`check-dev-mcp-doc-sync`, a raw stdio `tools/list`
  probe) had both already been run minutes earlier. Same class as the three below: **a command
  framed as a read whose side effects were not considered.** Third occurrence in this lane, which
  makes it the lane's signature failure, not an accident. The run's byproduct evidence is used in
  13.7 rather than discarded — but it was not the reason for running it.
- A worker **ran a Gradle build** despite an explicit prohibition, by `node -e "require(...)"` on
  `prepare-worktree.cjs` to "syntax-check" it — which executed it, triggering `npm ci` and an
  `installDist`. It completed clean and clobbered no config, but the prohibition existed because
  Gradle is serialized across agents and a second agent was live at the time. The later brief was
  amended to name this specific mistake; that is the only reason it did not recur.
- A worker ran `git checkout -- <doc>` during an adversarial gate test and discarded its own
  uncommitted rewrite, then redid it.
- The orchestrator ran a `python -c` with backticks inside double quotes, so the shell performed
  command substitution on the document text and executed fragments of it, triggering a build. No
  file was modified; the intended edit never ran.

None changed the shipped result. All three are the same class: a command whose *side effects* were
not considered because it was framed as a read.

### 13.5 For a reviewer to second-guess

- `DIST_NOT_BUILT` is **not exercised live** — reaching it needs a real `dev-runner start`, which
  the briefs forbade. Its tests are source-structural and say so. This is the weakest link.
- `quick_health` now reports `inferenceOrphan` *and* `foreignRuns`, which can both describe port
  8080. Consistent, but a reviewer may prefer retiring the former; `stop` still acts on it.
- `CLAUDE.md` was at its exact byte ceiling; 30 B were trimmed from the ui-web row (history
  pointers only, authority reference kept) to fit the new Pre-merge row.
- The `cli.mjs` dead-code sweep went beyond the brief's enumerated files.
- Pre-existing, logged not fixed: `FetchApiJsonOutputSchema` can throw a `ZodError` when `isOk` is
  false and no error object was produced (e.g. HTTP 500 with a valid JSON body) — neither union
  branch matches. Predates this lane; no new path into it was added. `hook-integrity` also fails
  with 6 `unwired-hook` findings against a stale machine-local `settings.local.json`.

### 13.6 Hot-reload live validation procedure

> **Precondition — a fresh session is required, and this is not optional.** A Claude Code session's
> `justsearch-dev` MCP server process is spawned once at session start and keeps running the code it
> booted with. The session that implemented this lane therefore cannot exercise it: its tool schemas
> still show `hotReload` defaulting to `false`, `reload` without `takeover`, and `quick_health`
> without the new fields. The server says so itself — `quick_health` returned
> `mcpServerStale: { sourceChangedSinceBoot: true, recommendedAction: "…restart the session…" }`,
> which is a pre-existing honesty feature of this surface and exactly the property §12.2 asks for,
> found working in the wild.
>
> So: **run this from a new session started after the branch is checked out** (or after merge).
> Driving `dev-runner.cjs` by hand would exercise the CLI lifecycle, not the MCP path that D2
> actually repaired — a green result that way would be `static-green ≠ live-working`.
>
> Stack ownership at the time of writing: free (`running: false`), no contention to resolve.

§4.2 condition 1: the P8 repair is not done until a live run edits a method body, reloads, and
asserts the new behaviour over HTTP **with the encoders still warm**. The implementing worker was
forbidden from driving the stack, so the procedure is written to be runnable by whoever holds the
lease. Nothing below has been executed; P8 is not closed until step 5 and step 6 pass. (The pusher's
own attach / identity / exit-code behaviour is already live-verified against a throwaway JVM — see
13.7 — so what remains genuinely unproven is `DevReloadManager`: reconstruction, and whether the
models really stay warm across it.)

**Preconditions.** One free dev stack, and this worktree built:
`node scripts/dev/prepare-worktree.cjs` in `.claude/worktrees/844-dev-surface-honesty` (or
`./gradlew.bat :modules:ui:installDist :modules:indexer-worker:installDist`).

1. **Start** from this worktree with hot reload on (now the default — pass it anyway so the record
   is explicit):

   ```jsonc
   justsearch.dev.start {
     "distFrom": "844-dev-surface-honesty",
     "hotReload": true,
     "waitLevel": "ready_worker",
     "clean": "soft",
     "leaseDurationSec": 1800
   }
   ```

   Then confirm the per-run record exists — this is R3's whole point:
   `tmp/dev-runner/runs/<runId>/run.json` → `hotReload: { enabled: true, debugPort: <n>, classesDir:
   "<this worktree>/modules/worker-services/build/classes/java/main" }`. `<n>` is normally 5005 but
   must be READ, not assumed.

2. **Baseline the three facts** the assertions rest on:

   ```jsonc
   justsearch.dev.fetch_api_json { "endpoint": "debug_state", "jsonPath": "worker" }
   ```

   Record `worker.health_check.version`, `worker.pid`, and `worker.health_check.embedding_ready`
   (it must be `true` — if the encoders were never warm, step 6's warmth assertion proves nothing).

3. **Edit one method body** in the module `reload` compiles (`worker-services`), in a class that is
   certainly loaded (the health RPC runs on every status poll). In
   `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/GrpcHealthService.java`,
   inside `check(...)` (~line 238), change

   ```java
   String versionWithStatus = version;
   ```

   to

   ```java
   String versionWithStatus = version + " [HOTRELOAD-PROOF]";
   ```

   No new method, field or constructor — standard HotSwap takes method bodies only, and a structural
   edit here would be testing R7's refusal instead (that is step 8).

4. **Reload:**

   ```jsonc
   justsearch.dev.reload { "module": "worker-services" }
   ```

   Expect `ok: true`, `hotSwapOutcome: "REDEFINED"`, `identityVerified: true`, `classesRedefined >= 1`,
   `signalWritten: true`, and `compiledFrom` equal to **this worktree**. A `compiledFrom` pointing at
   `F:\justsearch-public` would mean R1 regressed.

5. **Assert the new bytecode is live over HTTP** — the same call as step 2:

   ```jsonc
   justsearch.dev.fetch_api_json { "endpoint": "debug_state", "jsonPath": "worker.health_check.version" }
   ```

   PASS: the value now ends with `[HOTRELOAD-PROOF]`. That string can only come from bytecode that
   was not in the JVM two minutes ago.

6. **Assert the models stayed warm** — the capability being defended (§11.6):
   - `worker.pid` is **identical** to step 2. The Worker JVM was never restarted, so nothing it had
     loaded was unloaded. A changed pid means a restart happened and the reload proved nothing.
   - `worker.health_check.embedding_ready` is still `true` immediately after the reload.
   - `<dataDir>/logs/worker.log` shows the DevReloadManager reconstruction, and **no session that
     existed before the reload is re-loaded after it**.

     > **Compare session identity, not the presence of load lines.** The obvious form of this check
     > — "no ONNX/loading lines after the reload timestamp" — is WRONG and produced a false failure
     > on the first real run (§13.9). Sessions here are created lazily on first use, so a
     > verification search legitimately initialises an encoder *after* the reload that was never
     > loaded before it. The correct procedure: list every
     > `OnnxSessionCache`/`NativeSessionHandle` line with timestamps, split at the reload, and
     > confirm each **named** session appearing after it (`embed:`, `reranker:`, …) has no earlier
     > init line. A *repeat* of a pre-reload session means `ModelContext` was not carried across and
     > the ~40s cost is back; a *first* appearance means nothing.
   - A search still answers: `justsearch.dev.search_query { "query": "test", "limit": 3 }`.

7. **Revert the edit**, `reload` again, and confirm `worker.health_check.version` returns to the
   step-2 value. A one-way proof leaves a doctored stack behind and does not show the push repeats.

8. **Adverse cases worth running while the stack is up** — one call each, and they are the ones the
   unit tests can only assert structurally:
   - **Structural refusal (R7):** add a new method to `GrpcHealthService`, `reload` → expect
     `ok: false`, `error.code: "STRUCTURAL_CHANGE"`, `restartRequired` set, `signalWritten: false`,
     and the stack *unchanged* afterwards. That last part is the §5.6 #3 fix: a failed push no
     longer tears services down. Revert.
   - **Ownership refusal (R2):** `reload { "sessionId": "some-other-session" }` while a different
     session holds the lease → expect `OWNER_CONFLICT` and an untouched stack (`worker.pid` and
     `version` unchanged).
   - **Identity refusal (R3):** `reload { "debugPort": <a port carrying some other JVM's JDWP
     listener> }` → expect `TARGET_IDENTITY_MISMATCH` and nothing redefined. Skip if no second
     debuggable JVM is available; do NOT manufacture one by starting a peer's stack.
   - **Opt-out honesty (R6):** stop, `start { "hotReload": false }`, then `reload` → expect
     `HOT_RELOAD_NOT_ENABLED`, not a reported push. This is the claim the old
     `initialize.instructions` had backwards.

If step 5 or 6 fails, the repair is not done — do not close P8 on the unit tests alone, which is the
`audit-without-test` move condition 1 exists to block.

### 13.7 P8 as-built (hot reload, FIX-THEN-SURFACE)

| Item | Where |
|---|---|
| **R1** compile root from the run record | `resolveReloadTarget` (`server.mjs`) resolves `run.json.repoRoot` through the shared `resolveDistRoot`; the handler compiles, pushes and reads the build stamp under `runRoot`. No caller-cwd fallback: `RUN_ROOT_UNRESOLVED` instead |
| **R2** ownership + staleness | `checkRunMutationOwnership` (the §11.4 Class-C middleware, shared verdict authority) before anything runs; result wrapped in `withStaleness`; `takeover` added to the input schema and documented as authorizing the call, not transferring the lease |
| **R3** per-run JDWP port + identity | `resolveDevHotReload` in `dev-runner.cjs` picks the port (env override, else first free from 5005) and records `{enabled, debugPort, classesDir}` in `run.json`; `HotSwapPush` reads the ATTACHED VM's own classpath over JDI (`PathSearchingVirtualMachine`) and refuses unless the recorded classes dir is on it |
| **R4** mixed classpath | `WorkerSpawner.buildWorkerClasspath` puts the hot-reload classes dir ahead of the jar wildcard, inside the existing `DEV_HOTRELOAD` gate; `null` (gate off) returns the previous classpath exactly |
| **R5** no unconfirmed success | Signal write gated on `hotSwapOk` (was: on `signalFile`), with `signalSkippedReason` stated; `HotSwapPush` exits 3/4/5 for nothing-changed / none-loaded / identity-refused and only advances its marker on a real redefinition; `classesChanged/Redefined/NotLoaded` reported |
| **R6** true description + default on | `initialize.instructions` and the tool description rewritten; `hotReload` defaults true on `start` with `hotReload: false` as the opt-out |
| **R7** JBR staging | Not added, deliberately. Method-bodies-only is the shipped scope; `structuralChangeDetected` is the honest answer |

Verification: `./gradlew.bat build -x test` BUILD SUCCESSFUL · `:modules:app-services:test` BUILD
SUCCESSFUL (incl. 3 new `WorkerSpawnerHotReloadClasspathTest` assertions) · new
`scripts/dev/test-dev-mcp-hot-reload.mjs` 30/30 · `test-dev-mcp-surface-honesty` 36/36 ·
`test-ownership-verdict` 34/34 · `test-dev-mcp-projection-live` 18 · six `test-dev-runner-*` suites
green · `check-dev-mcp-doc-sync` OK · `skills-sync --check` OK · stdio probe: 12 tools (unchanged),
`reload` now carries `takeover`.

**The pusher itself was verified live, without the dev stack.** A throwaway JVM (a two-line
`Target` class, `-cp <classes>;<lib>/*`, JDWP on 127.0.0.1:5099, killed afterwards) exercised all
five exit paths against a real VM, which is the part a unit test cannot reach:

| Case | Result |
|---|---|
| identity entry ON the target's classpath | `IDENTITY_OK`, `REDEFINED 1`, exit 0 |
| identity entry NOT on it (the cross-tree case) | `IDENTITY_REFUSED` naming both sides, exit 5, **nothing redefined, no marker written** |
| changed class not loaded in the target | `REDEFINED 0 / NOT_LOADED 1`, exit 4, no marker written |
| immediate re-push after a successful one | `CHANGED 0`, exit 3 (so the marker DID advance on success, and only then) |
| wrong port, nothing listening | attach error, exit 1 |

That closes R3's mechanism (`PathSearchingVirtualMachine.classPath()` is available on a
socket-attached VM and the comparison behaves) and R5's marker discipline. Pusher messages were also
made ASCII-only after the probe showed `—`/`§` arriving mojibaked through the Windows console into
captured output.

**Byproduct evidence from an unintended stack start** (13.4 records how it happened; it was not run
for this). One real `start` — with **no** `hotReload` argument — produced:

- `run.json` → `hotReload: {enabled: true, debugPort: 5005, classesDir: "<this worktree>/modules/
  worker-services/build/classes/java/main", portSource: "scan"}`. R6's default-on and R3's port
  scan + record both fired on a real start, and the recorded root is the launching worktree.
- `worker.log` line 1 → `Listening for transport dt_socket at address: 5005`, then
  `justsearch.dev.hotreload=true`, `justsearch.dev.hotreload.classesDir=<this worktree>…`, and
  `Dev hot-reload enabled` from `KnowledgeServer`. So `addDevHotReloadFlags` fired end-to-end and
  the Worker JVM reached readiness with the changed classpath — R4 did not break the Worker.

Still unproven, and what §13.6 exists for: `DevReloadManager` — the service reconstruction itself,
and whether `ModelContext` survives it.

Deliberate judgement calls, so a reviewer can overturn them:

- **The identity token is read as a classpath entry, not as the `justsearch.dev.hotreload.classesDir`
  system property.** JDI cannot read a system property from an attached VM without invoking
  `System.getProperty` on a thread suspended *by an event* — a debugger-grade dance with no reliable
  trigger in a live Worker. `PathSearchingVirtualMachine.classPath()` needs no suspension, and after
  R4 the same absolute path is on the classpath, so the sysprop's value is what gets verified even
  though the sysprop itself is not the channel. The sysprop remains unread by anything.
- **`HotSwapPush.java` is taken from the MCP server's own tree, not the run's.** Everything else
  (Gradle wrapper, classes, build stamp) comes from the run's tree. An older copy in the run's tree
  would silently lack the identity check; the handler additionally refuses an exit-0 push that
  printed no `IDENTITY_OK`, so an old pusher fails closed either way.
- **A failed push writes no reload signal.** The alternative (reconstruct anyway, and say so) was
  available, but tearing down a stack's services is not a consolation prize for bytecode that did
  not land, and on a peer's stack it was an unauthorized teardown. `signalSkippedReason` states it.
- **"No class changed since the last push" is `ok: true, noOp: true`, not an error.** It is a real
  no-op, distinct from "changed but nothing landed" (`NO_CLASSES_REDEFINED`, `ok: false`).
- **`hotReload` defaults true only on the MCP `start`.** `dev-runner.cjs --hot-reload` keeps its own
  false default: the CLI is the other lifecycle (§6.1), and the 1-of-162 figure is an MCP figure.
- **The signal-gate tests are source-structural and labelled as such** in
  `test-dev-mcp-hot-reload.mjs`; what is genuinely exercised is the property they depend on (every
  non-`REDEFINED` outcome is `hotSwapOk: false`). Step 8 above is where they become live.

### 13.8 D3 as-built (jseval run registration)

Owner decision D3 = **full**, which overrode §8's minimum-first recommendation. Because P5 shipped
first, the layering came out right rather than by luck: **registration is the authoritative path,
the port probe is the fallback that keeps the register honest about what never registered** — which
is exactly the caveat §11.3 raised (a register only covers what registers itself).

- `scripts/jseval/jseval/run_register.py` writes one record per backend to
  `<main>/tmp/dev-runner/foreign/jseval-<pid>.json`, temp-file + `os.replace` so a torn record is
  never readable. Registration is **best-effort and never raises** — a bookkeeping failure must not
  fail an eval run; the run then degrades to merely *observed*, i.e. exactly today's P5 behaviour.
- Registration happens at **spawn**, not after health, inside the single `Popen` site both
  `start_backend` and `_run_with_cache` route through. The JVM holds ports, the data dir and the
  GPU from that moment, and that boot window is precisely what a neighbour's "is the machine free?"
  check must not miss — it is the window that contaminated the measurement in §6.1.
- **Why the location cannot destabilise the 271/542 lease model:** `dev-runner.cjs` never
  enumerates its state root. Its `readdir` calls target the *data* directory (two clean paths) and
  `runs/`; everything else it touches is a named child (`active.json`, `active.lock.json`,
  `op-leases.json`, `sessions/`, `interference-events.ndjson`). A sibling `foreign/` is invisible to
  admission, lease and retention logic. Verified against source directly, and pinned by a
  regression test so a future `readdir` over the state root fails loudly.

**The record makes no liveness claim** — a test enforces that no field says live/ready/running. The
*reader* decides, and reports four states rather than a boolean: `live` (port answered),
`unreachable` (port silent, pid alive — booting or wedged), `stale` (port silent and pid gone), and
`unreadable` (unparseable, or a future `schemaVersion`). A `live` entry whose recorded pid is dead
additionally carries `identityStale` — the listener is verified, the identity behind it is not.

Two consequences of §12.2 worth naming, because both were the harder choice:

- **Stale records are reported, never deleted.** Deleting another lifecycle's state during a *read*
  is the confident guess the criterion forbids.
- **`probe:false` still returns `null` even when records exist.** A record is a claim, not a
  verification; listing one as a run without probing would reintroduce exactly the false confidence
  this lane removed. `null` keeps meaning "I did not look".

`gpuBound` is recorded as `"unverified"` — jseval does not measure GPU residency, so it declines to
claim either way, and a test forbids the reader from upgrading it.

**Not verified without a live backend:** that a real `runHeadlessEval` writes a record (the `Popen`
path is mocked), and the `mainRepoRoot` join against the real `<main>/tmp/dev-runner/foreign` — the
worker declined to write into the main checkout, which was correct. Both are covered by §13.6's
live pass.

### 13.9 Live validation RESULTS (2026-08-19) — P8 condition 1 satisfied

Run from a fresh session (per §13.6's precondition), stack started from this worktree, lease held
throughout, stopped cleanly afterwards. **The repair works. Four defects in it were found by doing
this, none of which any unit test could have caught.**

#### What passed

| Step | Result |
|---|---|
| 1 — start + record | `distFrom: "844-dev-surface-honesty"` — a **bare worktree name** — resolved (B2's fix, live). `run.json` → `hotReload: {enabled:true, debugPort:5005, classesDir:<this worktree>, portSource:"scan"}` (R3) |
| 2 — baseline | `version: "0.1.0-dev"`, `pid: 29616`, `embedding_ready: true` |
| 4 — reload | `ok:true`, `REDEFINED`, `identityVerified:true`, 121 redefined / 157 not-loaded, `signalWritten:true`, `compiledFrom` = this worktree (**R1**) |
| **5 — new bytecode live** | `version: "0.1.0-dev [HOTRELOAD-PROOF]"` — a string that was not in the JVM a minute earlier |
| **6 — models stayed warm** | `pid: 29616` **unchanged**; `embedding_ready` still true; `DevReloadManager` reconstruction completed in **434 ms**; search answered normally |
| 7 — revert | `version` back to `0.1.0-dev`, still `pid: 29616` — three reloads inside one JVM lifetime |
| 8 — structural | refused, `signalWritten:false`, stack unchanged (the §5.6 #3 fix, live) |
| 8 — opt-out | `HOT_RELOAD_NOT_ENABLED`, with the message explicitly correcting the old false claim (**R6**), and an ownership projection attached (**R2**) |

`DevReloadManager` — the one thing §13.7 flagged as unproven — is now proven: reconstruction in
434 ms against a warm restart's ~40 s, with the encoders never unloaded.

#### The warmth assertion in §13.6 was wrong, and would have failed the run

Step 6 as written said to `grep -i "loading\|onnx\|session" worker.log | tail -30` and treat model
loads after the reload as failure. Applied literally, this run **fails**: at 13:17:01 — after the
13:16:44 reload — the log shows `Loading pre-optimized CUDA-EP ONNX model` and
`embed: GPU session initialized`.

That would have been a false failure. The full session history:

```
13:16:15-13:16:22  4 CPU model loads + reranker: GPU session initialized   <- before the reload
13:16:44.41        reload; reconstruction complete 434ms
13:17:01-13:17:02  embed: GPU session initialized (1037ms)                 <- after
```

The post-reload load is the **`embed`** session, which has **no earlier init line anywhere in the
log** — it was lazily created on first use by the verification search. Every session that existed
*before* the reload (the four CPU models and the `reranker` GPU session) was **never re-loaded
after it**. Nothing warm was lost.

**The assertion must compare session identity, not the presence of load lines.** Corrected in
§13.6. This is `interrogate-results` in the small: the log matched the failure pattern, and the
cause was not the one the pattern implies.

#### Four defects found, all §12.2 violations, all now fixed (F1-F4)

1. **The structural-change gate never fired** (`wrong-gate`). The predicate matched `HotSwapPush`'s
   own phrasing, `"added/removed methods or fields"`. The real JVM message is
   `"HotSwap not supported by target VM: add method not implemented"`, so a genuine structural
   change reported `structuralChangeDetected: false` and fell through to a generic
   `HOTSWAP_FAILED` — discarding the "restart for this change" remedy that was sitting in its own
   output. The gate existed, the symbol existed, and it did not fire in the target scenario.
2. **A failed push reported classes as redefined.** `classesRedefined` was parsed from the output
   regardless of exit code, so the refused structural push returned `ok:false` **with
   `classesRedefined: 3`**. Root cause: `HotSwapPush` printed `REDEFINED n` before
   `redefineClasses` had succeeded. This is the R5 defect class reappearing in the failure path.
3. **The identity refusal misattributed its own cause.** It reported "the VM was NOT launched from
   the tree this run record names… the cross-tree injection case". Live, that was false: all 183
   of the VM's classpath entries were under this worktree. The real cause was a **stale Head
   `installDist`** predating R4, so the classes dir was absent from an otherwise-correct
   classpath. The refusal was right; the explanation was invented. Now split into
   `HOT_RELOAD_CLASSPATH_ABSENT` (right tree, missing entry, remedy: rebuild the Head dist) versus
   `TARGET_IDENTITY_MISMATCH` (genuinely another tree).
4. **`start` claims to ensure the dist is up to date, and does not.** `dev-runner.cjs` logs
   *"Ensuring distribution is up-to-date (assemble)"* and runs `assemble`, which does **not** run
   `installDist` — but the Head launches from `modules/ui/build/install/ui`. Proven live: after
   editing `WorkerSpawner.java`, `start` (no `skipBuild`) still launched the old classpath, and an
   explicit `installDist` then executed real work and refreshed the installed jar. **This is not a
   hot-reload defect** — it silently invalidates *any* live verification of a Java change, which is
   a far broader false green than the one this lane set out to fix.

Defect 4 is the most valuable thing the live pass produced, and it was found only because defect 3's
refusal was loud. A quieter failure mode would have let the run "pass" against stale code — which is
`static-green ≠ live-working` with the roles reversed: the *stack* was stale, not the test.

### 13.10 Pre-merge independent review — six blockers and five smalls (2026-08-19)

An independent pre-merge review applied §12.2 to this branch's own code and found the criterion
violated six times in a blocking way. All are fixed; the order below is the review's.

| # | Where | The false claim | Fix |
|---|---|---|---|
| M1 | `server.mjs` `readForeignRegister` | `catch { return []; }` asserted ENOENT and also swallowed EACCES/EPERM/EMFILE — "I looked and found nothing" about a directory it could not read. The D3 tri-state collapsing inside D3's own code. | only ENOENT/ENOTDIR yields `[]`; everything else propagates, so `probeForeignRuns` yields `null` |
| M2 | `server.mjs` `start` timeout | on timeout it read `active.json` and synthesized `{ok:true, runId, apiPort…}` from *any* runId — verifying neither that the run was this call's nor that HTTP answered | `START_TIMED_OUT`, `readinessConfirmed:false`, and an `observed` block (incl. one `/api/health` probe) explicitly labelled observed-not-confirmed |
| M3 | `dev-runner.cjs` + `WorkerSpawner` | R4's classes-dir prefix is only sound when the classes dir and the installDist jars are one build; `skipBuild` (124/179 starts) cannot establish that, and `freshness.buildArtifact` would still say FRESH | the pairing is established (build ran) or *checked* (class mtimes vs the installed `worker-services-*.jar`); when it cannot be, hot reload is OFF for the run with a recorded `reason`/`classpathVerdict` and the classpath is exactly the pre-844 jar set |
| M4 | `server.mjs` `api_call` allowlist | `[A-Za-z0-9._-]+` admits `..`, and matching ran *before* `new URL()` normalized — `…/packages/../decline` passed the allowlist and was sent as `/api/ai/install/decline` | `normalizeApiCallPath` (absolute, no backslash, no empty/`.`/`..` segment after percent-decoding) before the match, plus a post-match assertion that the resolved pathname is still the matched one |
| M5 | `server.mjs` `reload` | `module` chose the push source while the identity args always came from the run record, so another module passed `IDENTITY_OK` and reported `REDEFINED` while its later-loaded classes stayed on the stale jar | `module` is restricted to the module the run recorded; anything else is `RELOAD_MODULE_NOT_ON_CLASSPATH` |
| M6 | two tests | a Java test claimed to pin a cross-side contract it never referenced; a JS guard used a negative source regex that could not catch the call shapes it named | Java test renamed to what it checks; the cross-side pin moved to where both ends are executable in one process; the negative regex replaced by a readdir-call-site **inventory** |

Smalls, same criterion: **S1** a killed `HotSwapPush` is `HOTSWAP_TIMED_OUT` with
`classesRedefined: null`, not "no bytecode was pushed"; **S2** `jseval`'s `stop_backend` sweeps for
the orphan Worker *before* retiring its register record; **S3** the register reader honours
`JUSTSEARCH_DEV_RUNNER_STATE_ROOT` as the writer does; **S4** an exhausted 5005-5024 range degrades
to hot-reload-off with a notice instead of failing a start whose predecessor is already stopped;
**S5** a `spawnSync` `.error` is surfaced instead of "exit code null"; **N1** the MCP harness's first
line now says it starts a real dev stack.

**The M3 decision, and why.** Option (a) — prefix only when the build ran — restores the guarantee
but removes hot reload from the dominant path (`skipBuild`, 124/179). Option (b) alone would leave a
*reported* mixture, which is honest but still contaminates. The shipped answer is (a) with (b) as
the recovery: the build having run **is** the pairing, and under `skipBuild` the pairing is measured
(classes no newer than the jar the jar was built from ⇒ consistent). The common `skipBuild` restart
on unchanged artifacts therefore keeps hot reload; only a genuinely divergent tree loses it, loudly.
The outcome is stronger than "no silent mixture": **no mixture at all**, because the prefix is the
mechanism and it is never applied unpaired.

## 14. Closed (2026-08-19) — merged, and what a later reader needs

**Merged to `main` as `6a9fa1e0` (PR #513).** Public CI on `main` verified green afterwards via
run `32286221701` — note this lane's own push-run was `cancelled` by a superseding push, so the
green comes from the next run whose tree contains `6a9fa1e0`, not from a cancellation read as a
pass.

### 14.1 Verification claims and their evidence

Every claim below has a pointer; anything without one is in §14.2 instead.

| Claim | Evidence |
|---|---|
| Full unit suite green | `./gradlew.bat test` — 186 tasks, BUILD SUCCESSFUL, run before and after the catch-up merge. First execution of the full suite on this branch; earlier passes were `build -x test` only |
| Compile green on the integrated tree | `./gradlew.bat build -x test` post-merge, 123 tasks executed |
| Dev-MCP behaviour | `scripts/dev/test-dev-mcp-surface-honesty.mjs` 70/70 · `test-dev-mcp-hot-reload.mjs` 56/56 · `test-dev-mcp-projection-live.mjs` 18 assertions (spawns the real server over stdio) |
| Ownership model unbroken | `scripts/dev/test-ownership-verdict.mjs` 34/34; all seven `test-dev-runner-*.mjs` |
| Doc/code inventory agreement | `scripts/ci/check-dev-mcp-doc-sync.mjs` exit 0; its test 25 assertions; it **failed a real catch-up merge** naming five undocumented allowlist entries |
| jseval register | `pytest scripts/jseval/tests/test_run_register.py test_backend.py` — 62 passed |
| Analytics reader | `scripts/agent-analytics/run-all-tests.mjs` 33/33 files |
| Hot reload works live | §13.9 — `version` gained `[HOTRELOAD-PROOF]`, `pid` 29616 unchanged across three reloads, `embedding_ready` true throughout, reconstruction 434 ms, `compiledFrom` = the worktree |
| Models stay warm across reload | §13.9 — every ONNX session initialised before the reload has no re-init line after it; the single post-reload load (`embed:`) has no earlier init anywhere |
| Pusher attach/identity/exit codes | §13.7 — live-verified against throwaway JVMs (ports 5096/5097/5099), all five outcome paths |
| Onramp 504 is pre-existing | Ran `test-onramp-first-success.mjs` on a clean worktree at `origin/main` `63fa9163`: identical `/api/knowledge/search → HTTP 504` |
| `skipBuild` is the dominant path | Independent corpus scan: `starts=179 skipBuild=124` |
| Usage figures | `scripts/agent-analytics/dev-tool-usage.mjs` — the shipped reader; re-run it rather than trusting the numbers here |

### 14.2 Unverified assumptions and deferred checks

- **`DIST_NOT_BUILT` is not exercised live.** Reaching it needs a real `dev-runner start` failure;
  its tests are source-structural and say so. The weakest link in the change.
- **The M3 classpath-pairing check is an mtime proxy.** Classes newer than the `worker-services`
  jar means divergence; equal-or-older is *inferred* to mean the jar contains them. A jar touched
  without a rebuild would read as paired. A content hash would be sound but costs a jar read per
  start.
- **The `foreignRuns` register is unproven end-to-end.** `run_register.py`'s `Popen` path is mocked
  in tests, and the reader's `mainRepoRoot` join against the real
  `<main>/tmp/dev-runner/foreign` was never exercised, because writing into the main checkout was
  correctly refused. A live `runHeadlessEval` would close it.
- **The `WatchedRootScanCollectionTest` flake is classified, not root-caused.** Failed once in CI,
  passed on a plain re-run of the same commit; signature is JUnit `@TempDir` cleanup, not an
  assertion. Evidence it is not caller-caused is in the observations inbox.
- **`hotReload` defaulting on is a live behaviour change** for every dev stack (JDWP on a loopback
  port; classes dir first on the Worker classpath when the pairing is established). Verified not to
  break Worker startup, but it has had one day of exposure.

### 14.3 Stale docs found while working here

- `305-hot-reload` is marked `done` and describes a capability that was incoherent for months;
  a forward pointer to §13.7 was added so a reader does not trust it as current.
- `254-mcp-dev-tools-issues` (`done`, 2026-03-03) is superseded by this document; forward pointer
  added.
- `scripts/README.md` advertised a `justsearch_dev_wait_ready` tool that no longer exists — a fifth
  stale inventory beyond the four §6.3 counted. Fixed.

### 14.4 Follow-up that should not be forgotten

1. **The opportunity metric** (§12.6 caveat). Without it the D2 falsifier cannot be interpreted.
   Needed before the ~6-week judgement, not now.
2. **§6.2 — ownership on the other mutating tools** (`ingest`, `reindex`, `gc`, migration). Still
   open, and now cheap: R2 built and live-proved the Class-C middleware it needs. This is the last
   unaddressed correctness gap in the audit.
3. **The onramp is broken on `main`** — `/api/knowledge/search → HTTP 504`, proven by baseline —
   and `onramp-smoke.yml` is `workflow_dispatch`-only, last run 2026-07-08. The repo's "a developer
   reaches first success" proof is both failing and unexercised. Likely 656's lane, not this one.
4. **The `config-surface` baseline-advance toll**, third occurrence (see the `merge-import`
   changeset). Already has `worktree-config-surface-advance` in flight — corroboration, not a new
   ask.
5. **`remove-worktree.cjs` partially destroys its caller's own worktree** — it unlinks `.git`
   before attempting the directory delete, so a held directory leaves a half-removed tree rather
   than an untouched one.

### 14.5 The lesson worth carrying past this lane

A method defect invalidates *every* figure computed with it, not just the one that surfaced it.
The non-recursive transcript glob here produced a wrong call count in §3 **and** a wrong byte share
in §6.4; fixing §3 did not fix §6.4, because the byte totals were never re-derived, and the second
error survived until a pre-merge scan happened to sample it. When a measurement bug is found,
re-derive the whole set.
