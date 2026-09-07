---
title: "Retire the deterministic pins: fix the pre-existing reds that expected-state.v1.json remembers instead of anyone fixing"
type: tempdocs
status: "IMPLEMENTED (2026-09-05); REBASED onto a moved main (2026-09-06) — items 2a/2b/2c, 3, 6 and 7 ship here; items 1, 4 and 5 were overtaken on `main` and are recorded as landed-elsewhere in §4"
created: 2026-09-05
updated: 2026-09-06
author: Claude Fable 5.1 session 01UruFixcpQDZ4ciN6YUaRu8
category: agent-process / governance / test-hygiene
related:
  - 680-expected-state-pins
  - 872-retire-observations-store
  - 918-wave2-kernel-residue-repin-enforcement-and-ci-gate-wiring
---

# Retire the deterministic pins

## §0 Why this, why now

`scripts/agent-analytics/expected-state.v1.json` carries 19 pins. The `known-state-hint` hook
delivers each one to every session whose command matches, so every agent re-reads "this red is
pre-existing" instead of anyone fixing it. The pin file's own rule (CLAUDE.md
`log-pre-existing-issues`) is: *main being red is a defect, not a fact to remember* — a pin is a
dated exit plus a tracked fix. 872 §6 routed the fixes to their owning modules; none has an owner.

The 19 split into two classes:

- **Load flakes** (wall-clock budgets on a box running several agents' Gradle at once). The pin
  is the honest state; the fix is the one-Gradle-at-a-time convention, not code. They stay:
  `app-services-integrationtest-wallclock-flaky`, `worker-core-onnx-longdoc-forensic-timeout`,
  `worker-services-30s-timeout-under-load`, `ui-readiness-trigger-composition-5s-wait-under-load`,
  `adapters-lucene-batchupdate-rmw-coordinator-load-flake`,
  `agent-analytics-suite-wallclock-flaky-under-load`.
- **Environment facts** with a documented remedy the pin already names. They stay:
  `vdu-pdf-fixtures-local-env`, `jseval-pytest-missing-optional-deps-local-env`,
  `wire-gate-buf-cli-missing`, `governance-kernel-inputs-unbuilt`,
  `ui-a11y-gate-settings-dialog-capture-timeout` (needs a live browser; out of this tempdoc's
  no-stack scope).
- **Deterministic defects** — the code is wrong and a bounded fix exists. These are this tempdoc.

## §1 Items

Each item = the fix + a test that would have caught it + deleting the pin in the same PR
(the pin's own exit rule). Evidence lines cite the pre-fix state verified 2026-09-05 on main
`8da7a24d`.

| # | Pin | Defect (verified) | Fix |
|---|---|---|---|
| 1 | `ts-any-gate-counts-english-prose` | `countAny` (`scripts/governance/gates/ts-any/enforcer.mjs:31`) runs `/\bas\s+any\b\|:\s*any\b\|<\s*any\s*>/g` over raw file text; the English word "any" after a colon in a comment scores as a cast. 5 false findings on main. | Strip `//` and `/* */` comments and string/template literals before counting; unit test with prose-vs-cast fixtures; rebalance the baseline downward. |
| 2a | `ui-web-envelopestream-heartbeat-flaky` | `EnvelopeStream.test.ts` watchdog cases wait REAL time (`wait(70)` against a 40 ms watchdog) and assert `sources.length >= 2`; parallel vitest load starves the timer. | Drive the watchdog/reconnect timers with `vi.useFakeTimers()` + `advanceTimersByTimeAsync`, so the assertion is about timer semantics, not scheduler luck. |
| 2b | `ui-web-pluginloader-module-mode-timeout` | `PluginLoader.test.ts:141` module-mode case does a cold dynamic import chain inside the 5000 ms default (330 ms isolated). | Warm the import graph once in `beforeAll` or set an explicit per-test timeout with a comment naming the cold-transform cause. |
| 2c | `ui-web-resourceregistry-defaults-timeout` | `resourceRegistry.test.ts:243` calls `vi.resetModules()` then re-imports the registry + defaults (a renderer import graph) inside one 5000 ms budget. | Same shape as 2b; same remedy. |
| 3 | `app-inference-external-server-config-store-flaky` | `InferenceLifecycleManagerExternalServerTest.startLlamaServerCanAdoptHealthOnlyWhenExplicitlyEnabled` reaches `ConfigStore.global()` (`InferenceConfig.java:108` / `LlamaServerOps.java:249`) with no global set; passes when a sibling test in the same JVM happened to set it first. Order-dependent. | The test sets (and restores) its own `ConfigStore` global in `@BeforeEach/@AfterEach`, as `InferenceConfigServerExeTest` and `OnlineAiServiceImplTest` already do; then find and fix any sibling that leaves the global mutated. |
| 4 | `runtime-manifest-closure-sibling-file-api-port` | `packaging/mcpb/server/index.js:33` and `scripts/sandbox/mcp-typed-confirm.mjs:109` still read `runtime/api-port.txt`, which tempdoc 501 Phase 18 removed; the closure check's own comment says all consumers read `manifest.json`. | Read the port from `manifest.json` via the existing `scripts/lib/platform-paths.mjs` / `scripts/prod/justsearch-mcp/discovery.mjs` pattern; `check-runtime-manifest-closure` goes green. |
| 5 | `docs-validate-heading-case-repo-wide` | `scripts/docs/docs-validate.mjs:66-92` enforces Title Case on H1/H2 as an `error`; the repo writes sentence case, so 6751 findings and the exit code is noise. 872 §6 also lists 30 tempdocs whose front matter does not parse. | Delete the Title Case rule (a convention the repo never adopted; no contributing doc names it). Fix the 30 front-matter parse failures in place (quote the offending value). Goal: `docs-validate` exit code becomes a signal; remaining `warn`s (tags/aliases) stay warnings. |
| 6 | (no pin; 872 §6) | `scripts/dev/run-gh.mjs checks-wait` reports PASS when only `cla-assistant` has registered and the `CI` workflow run is still pending. `isUnregistered` treats "any check present" as registered. | Pre-poll until a check from the `CI` workflow is present (or `--required-only` semantics resolve it); test with a fixture of the CLA-only rollup. |
| 7 | `app-services-watched-root-scan-collection-flaky` | `WatchedRootScanCollectionTest` fails under the full suite only (JUnitException/IOException in the in-process gRPC scan). Cause not yet known. | Investigate; fix if the cause is deterministic (a race the test itself creates). Otherwise keep the pin with a dated note of what was ruled out. |

Not in scope: wiring the ui-web vitest suite into a CI lane (872 §6 owner call — this tempdoc
removes the flake blocker, the owner decides the lane).

## §2 Execution

Worktree `932-pin-retirement` off main `8da7a24d`. Items 1, 2, 4, 5, 6 are node/npm-only and run
as parallel sonnet subagents on disjoint files; items 3 and 7 need Gradle and run sequentially
(one Gradle at a time across agents). Pin deletions are made by the orchestrator at the end, one
edit, then `node scripts/agent-analytics/run-all-tests.mjs`.

Verification before PR: `./gradlew.bat build -x test`, `./gradlew.bat test` (full), ui-web
`npm run typecheck && npm run test:unit:run` three times in a row (the flakes were load-shaped),
`node scripts/governance/run.mjs --gate ts-any --mode gate`,
`node scripts/ci/check-runtime-manifest-closure.mjs`, `node scripts/docs/docs-validate.mjs`,
`node scripts/agent-analytics/run-all-tests.mjs`.

## §3 Outcome (2026-09-05)

All seven items landed; eight pins deleted (`expected-state.v1.json` 19 → 11 entries). Every fix
carries a test that fails without it (precision checked by reverting the fix and re-running).

| # | What actually was wrong | What shipped |
|---|---|---|
| 1 | As diagnosed. The 884 changeset had already licensed the false positives as `merge-import` and routed the fix to "whoever next touches the gate". | `stripCommentsAndStrings()` before `countAny` (`scripts/governance/gates/ts-any/enforcer.mjs`), `countAny.test.mjs`, baseline rebalanced (`--rebalance`: 6 rows were 100 % prose, one row 2→1, one stale row for a deleted file removed by hand — rebalance cannot drop a row whose file no longer exists, a gap left as-is). 884 §F row 1 marked closed. |
| 2a | As diagnosed; the watchdog and backoff are plain `setTimeout` (`EnvelopeStream.ts:323,357`). | Fake timers in the re-establishment describe; `>= 2` reconnect assertions tightened to exactly 2 now that it is provable. |
| 2b | `PluginLoader.ts:354` lazily imports `@endo/module-source`; the cold transform landed in the first module-mode case. | Static `import '@endo/module-source'` in the test file (collection time has no per-test budget). 330 ms → 15 ms. |
| 2c | `vi.resetModules()` + re-import re-transformed five Lit components per run; the reset existed only because the registry is a singleton. | Static import of the defaults, snapshot captured once, describe re-registers it in `beforeEach`. No reset. |
| 3 | **Misdiagnosed by the pin.** The "ConfigStore not initialized" message is a symptom: adoption's single 1 s `/health` probe missed under load, `startLlamaServer` fell through to the launch path and hit `ConfigStore.global()` there. The test never owned a global, so a missed probe surfaced as an unrelated exception. | (a) test class publishes/restores its own `ConfigStore` (truthful failure); (b) `LlamaServerOps.isExistingServerHealthy()` retries a probe that *timed out* up to 3× (connection refused still returns at once) — a live-but-busy llama-server is adopted instead of double-spawned, which is the restart-loop the class exists to prevent. New case: slow first `/health` → still adopted. The now-unreferenced `isServerHealthy(Duration)` was deleted (UnreferencedCodeTest caught it in the full run). |
| 4 | As diagnosed. `HeadlessApp` does not write the file either (the closure check's SKIP_PATHS comment was wrong). | Both bridges read `manifest.json` → `head.apiPort` (inline read — both ship standalone and cannot import the ESM readers). `server.json` hash resynced; README / `mcp-production-server.md` / mcpb README corrected. |
| 5 | Two false-authority rules, not one: Title Case (7164) and "H1 == front-matter title" (400; ADRs carry `ADR-NNNN:`, explanation docs `NN.`). Plus 33 tempdocs with unparseable front matter (872 listed 30; 884/885/904 newer) and 4 canonical docs with no H1. | Both rules deleted with a why-comment; fenced code stripped before heading checks; tempdocs get front-matter checks only (freeform contract per their README; one U+FFFD there is deliberate evidence). 33 front-matter fixes value-identical after re-parse; 811's blockquote-inside-front-matter moved below the H1 verbatim. 4 H1s added. `docs-validate` now exits 0. Dormant `normative` rules noted, not touched. |
| 6 | As diagnosed; `gh pr checks --json name,workflow,state` exposes the workflow name, verified live (CLA row is workflow "CLA Assistant", CI rows "CI", also under `--required`). | Pre-poll requires a `CI` row; `checksWait` made injectable; regression fixture CLA-only → CI-pending → CI-pass must poll 3×. |
| 7 | **Root-caused.** The failure was `@TempDir` cleanup, not an assertion (881 evidence). `RemoteKnowledgeClient.close()` called `walkExecutor.shutdownNow()` and returned; the walk thread was still persisting `watched_roots.json` under the temp data dir when JUnit deleted it — a Windows open-handle failure whose window widens under load. Production shape: shutdown raced the last state write. | `close()` joins the walk thread (bounded 5 s, self-join guarded). `RemoteKnowledgeClientCloseJoinsWalkTest` holds a ScanRoot RPC open and asserts the executor is TERMINATED when `close()` returns; fails at that line without the join. The `isWalkExecutorTerminated()` seam is registered in `UnreferencedCodeTest.KNOWN_UNREFERENCED` (test-only caller). |

Kept pins (11): the six load flakes and five environment facts listed in §0.

Out-of-scope findings routed: the rebalance-cannot-drop-deleted-file gap (noted in row 1, one-line
manual fix applied); the ui-web recipe in `consult-register.v1.json` spelled the kernel gates as `--gate a,b,c`, which
`run.mjs` does not split (a subagent ran them one by one) — reworded to repeated `--gate <id>`, and
`run-ui-web-gates.mjs`, whose expander parsed the comma form, now accepts both; the ui-web vitest
CI lane (872 §6) is now unblocked and remains the owner's call.

## §4 Rebase onto a moved main (2026-09-06)

The original branch was cut from `8da7a24d`. By the time it was rebuilt on `origin/main`
(`7b34390bd`), three of the seven items had been overtaken by work that landed independently, so
they are dropped from this change rather than re-applied:

- **Item 1 (`ts-any`) — moot.** `governance(930): commodity ratchets replace six gates`
  (`534aaf506`) deleted the gate outright: `scripts/governance/gates/ts-any/enforcer.mjs`,
  `gates/ts-any/baseline.txt` and its changesets are gone, and ESLint `no-explicit-any` as an
  error carries the ratchet. `countAny` no longer exists, so neither does the comment-stripping
  fix or its `countAny.test.mjs`. The 884 §F row 1 note is updated to say this instead of
  claiming a fix to a deleted file.
- **Item 4 (runtime-manifest closure) — already landed.** `packaging/mcpb/server/index.js` and
  `scripts/sandbox/mcp-typed-confirm.mjs` on `origin/main` already read `head.apiPort` from
  `runtime/manifest.json`, and README / `docs/reference/mcp-production-server.md` /
  `packaging/mcpb/README.md` already say so. `server.json`'s `fileSha256` keeps `main`'s value
  (it matches `main`'s repack, not this branch's).
- **Item 5 (`docs-validate`) — already landed, more thoroughly.**
  `docs(930): docs-validate exits 0 and runs on PRs` (`6f4206dd3`) removed the Title Case and
  `H1 == title` rules, stripped fenced code before heading checks, scoped heading rules off
  `docs/tempdocs/**`, repaired 96 docs and re-quoted the same 33 tempdoc front matters. Every
  docs file this branch touched is byte-identical to `main`'s, so all of item 5 is taken from
  `main`.

**The pin file itself is gone.** `hooks(930): retire bash-guard and hint hooks; native deny`
  (`a42d039ac`) deleted `scripts/agent-analytics/expected-state.v1.json` along with the
  `known-state-hint` hook that read it, so "delete the pin in the same PR" is satisfied by
  construction for all eight. What survives here is the half that has no other home: the four
  code defects the pins remembered (2a/2b/2c ui-web determinism, 3 `LlamaServerOps` adoption
  retry, 6 `checks-wait` CI-workflow registration, 7 `RemoteKnowledgeClient.close()` joining its
  walk thread) and their regression tests.
