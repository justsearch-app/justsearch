---
title: "Lane F stage F — prose sweep and report: implementation checklist"
stage: F
created: 2026-09-09
base: be47faa40
status: "draft, code-verified at the stage-B head; the residue grep is re-run at stage start"
updated: 2026-09-09
---

# Lane F stage F — prose sweep and report: implementation checklist

Written from 17.3 row F, `verified-facts.md` ("Docs the sweep must correct"), tempdoc 917 §8's
residue term list, and a code-verified grep at `be47faa40` (2026-09-09, read-only; counts
exclude `docs/tempdocs/` and `docs/design/lane-f-engine-jvm/`). `design.md` cited by section;
files by `file:line` at that commit. Stages C1 to E move things first; the grep is re-run at
stage start and the counts below are the starting point, not the acceptance.

Stage F in one line (17.3 row F): **`CLAUDE.md` invariant 1, `AGENTS.md`, the subagent baseline
brief, skills, postmortems, `19-module-architecture.md` rewritten on the rings, jseval and
`scripts/agent-analytics` parsers, governance registers and contract-surface registrations, the
docs listed in `verified-facts.md`; the report-back (19). Branch state after: nothing on the
branch names the Worker as a process. Checkpoint proof: the 917 §8 residue grep returns only
labelled hits; `docs-validate` and the regen set green.**

---

## 0. Corrections to inherited facts

1. **Most of the prose sweep already happened in A and B.** ADR-0001 and ADR-0002 are
   `superseded_by: ADR-0049` with banners; `02-process-coordination.md:9-27` carries a
   "largely historical as of lane F stage A" banner; `01-system-overview.md` is rewritten
   (`## The process model` `:14`, `### 1. The Engine (one JVM)` `:51`; its residue at `:102-113`
   sits under a labelled "what this replaced" subsection); `CLAUDE.md:13` and `AGENTS.md:16-17`
   already carry the new invariant 1 (the `CLAUDE.md` block is **generated** from `AGENTS.md`
   by `scripts/docs/agent-instructions-sync.mjs`, so `AGENTS.md` is the edit point); the
   `adr-probes.v1.json` rows are retargeted; `supervision-contract.v1.json`'s Worker row is
   `retired` (`:87-90`). `verified-facts.md`'s "Docs the sweep must correct" bullets about
   `CLAUDE.md`/`AGENTS.md`/ADR probes and the "102 files import `io.grpc`" fact describe a
   pre-A14 state.
2. **What survives is not prose; it is a live wire vocabulary, plus module names.** The
   unlabelled residue at `be47faa40` is: `LifecycleSnapshotV1.Components(head, worker, inference)`
   (`:55`, "stable (API contract)", served on `/api/health`); `ReadinessDimension.WORKER_CONTROL_PLANE`
   and the `worker_status`/`worker_health_check`/`head_*` source strings (`:15-21`, consumed by
   `ApiExplorerView.ts:107` and two fixtures); the 16 `WORKER_*` members of `LifecycleReasonCode`
   with `worker.*` wire values (`:19-63`); `docs/reference/api-contract-map.md:53-54,65,94-165,200,241`
   documenting those shapes; `scripts/jseval/jseval/duplicate_prevalence_production.py:446-451`
   asserting `components.head/.worker`; `runtime-state.v1.json:21`'s GPU-lease holder enum
   `WORKER`; 21 generated `requiredCapabilities: ["WORKER"]` entries in `apiRoutes.ts`; and the
   module names `worker-services`, `indexer-worker`, `worker-core`, which every register cites
   as paths. **D1-12 and D1-14 rename the readiness vocabulary and the snapshot's slots**
   (decided 2026-09-09: `/api/health` moves to `schema_version 2` with `api/index/generative`,
   every in-repo reader updated in the same commit); F sweeps what D1 leaves.
3. **The bar is "only labelled hits", not "empty".** The brief (`917-evidence/lane-F-brief-v2.md:181-182`)
   says the residue grep must be empty; 17.3 row F says only labelled hits. Decided 2026-09-09:
   17.3's wording stands — module names are not renamed in lane F (a rename cascades through
   every register path and every build file for no behaviour), and capability names
   (`WORKER` in `requiredCapabilities`) are authorisation tokens clients carry and stay, labelled.
4. **`00-program-overview.md` does not exist in the repo** (cited by `design.md` 19 and
   `handoff.md`; the external brief directory is not reachable). The report-back's shape is
   taken from the sibling lanes' de-facto sections (`docs/tempdocs/915-evidence/report-back.md`
   and 917's own report-back): *Cross-lane requests; PRs; Items done, deviated, skipped;
   Evidence; Measurements; Residue routed; What the next lane must know*, plus lane F's two
   additions: the measurement table (E's `table.md`) and the final residue grep output.
5. **`scripts/agent-analytics` has no subject**: zero hits for `worker.log`, `head.log`,
   `core.worker-log`, `core.head-log`; its `worker` hits are orchestrator vocabulary. The 17.3 row
   names it; F records "nothing to do" with the grep.
6. **Skills are two trees with two mechanisms.** `.claude/skills/*/SKILL.md` have a generated
   region from canonical docs (`scripts/docs/skills-sync.mjs`, manifest `:27-67`);
   `.agents/skills/*/SKILL.md` are hand-maintained Codex copies, and `check-codex-agent-parity.mjs:101-107`
   proves only that they are committed. Three pairs have already diverged in opposite directions
   (`installer` `.agents:47` stale while `.claude:44` is current; `search-quality` `.claude:3574`
   stale while `.agents:3579` is current, with the canonical source `23-search-pipeline-overview.md:10`
   stale too; `dev-stack:129` stale in both).
7. **Docs the design's list omits:** `docs/explanation/24-worker-inference-composition.md`
   (titled "Worker Inference Composition", 14 hits, pushed by `consult-register.v1.json:90-96`);
   `governance/sandbox-coverage.v1.json:76` (live instructions to "read the Worker log", which no
   longer exists); `scripts/jseval/jseval/backend.py:740-870` (live orphan-Worker process sweep
   code matching a Worker command line, below a comment saying the mechanism no longer applies);
   `metrics_reader.py:1-8`, `readiness.py:325`, `916_chunk_sweep.py:482`, `ui_check.py:1267`
   (a chat fixture prompt about "the worker hand[ing] its results back to the head process"),
   two jseval test fixtures; `dev-runner.cjs:21`; `postmortems.md` has **nothing** to rewrite
   (its two hits are a dated incident and a WHATWG `Worker`).

### 0.1 Corrections found while sweeping (appended per item)

*(empty at stage start.)*

---

## 1. Inheritance ledger

| # | inherited state | item |
|---|---|---|
| I1 | The residue counts of §0.2 at `be47faa40`, re-run at stage start after C1 to E (D1-11 retires `restart-worker` (32 files), D1-14 renames the `worker.*` codes, D1-12 the snapshot slots; C2-1 labels the `owner: "WORKER"` register rows since their identity cannot change). | F-1 |
| I2 | E's record (`evidence/E/table.md`, `decision.md`) and every stage's `evidence/<letter>/`. | F-7 |
| I3 | The always-loaded budget at its ceiling (`AGENTS.md` 8296/8296 B); any byte added to an always-loaded doc reds `check-always-loaded-budget`. | F-6 |
| I4 | The squash-message gate's limits (body ≤ 2000 characters, ≤ 32 lines, no banner, one `Session-Id:` line) and the managed review record (`pr-review-record check`) on PR 718. | F-8 |

---

## 2. The residue grep (F-1's subject)

Term list: 917 §8's fifteen (`MMF`, `heartbeat`, `suicide`, `breath`, `OFFSET_`, `WorkerSpawner`,
`ORDINAL_WORKER_SNAPSHOT`, `WORKER_FORWARDED_PROPS`, `MainSignalBus`, `MmfWorkerSignalBus`,
`MmfWorkerSignalLayoutV1`, `WorkerLivenessDecision`, `SupervisionPolicy`, `main_gpu_active`,
`ForegroundLoadInterceptor`) plus the lane's additions (`WorkerProcessManager`,
`RemoteKnowledgeClient`, `worker.log`, `head.log`, `core.worker-log`, `core.head-log`,
`Head/Worker`, `two JVMs`, `gRPC`, `io.grpc`, `worker-config-snapshot`, `restart-worker`,
`Worker restart`, `WORKER_`, `owner: "WORKER"`, `Worker process`, `Worker JVM`). A hit is
**labelled** when its line or its enclosing heading carries one of: `historical`, `retired`,
`superseded`, `lane F`, an item id (`A11`, `B14`, …), `no longer`, `replaced`, or sits in a
`docs/decisions/00{01,02}` body, a `retiredNote`, a `git`-history citation, or a test fixture
whose name says legacy. `SupervisionPolicy` and `gRPC` are **not residue terms any more** on
their own: `EngineSupervisionPolicy`/`BrainSupervisionPolicy` are the live contract, and `gRPC`
appears in 175 module files as history and in ADR text; the grep for those two counts only
lines that present them as current. The script `scripts/ci/check-lane-f-residue.mjs` (F-1)
encodes the term list and the labelling rule and prints unlabelled hits; it is the checkpoint
proof, run once at F's end and recorded.

---

## 3. Checklist items

### F-1 — the residue script and the grep

Land `scripts/ci/check-lane-f-residue.mjs` with §2's terms and labelling rule and a self-test
(a fixture with one unlabelled and one labelled hit); run it; label or delete every unlabelled
hit that the later items do not own. **Acceptance:** the script exits 0 on the branch at F's
end; its output is committed to `evidence/F/residue-grep.txt`; the self-test reds on the
unlabelled fixture.

### F-2 — canonical docs rewritten

- `docs/explanation/02-process-coordination.md`: the largest rewrite — from `:29` to `:394`
  present-tense two-process prose under a historical banner; rewrite as "process coordination
  inside the Engine" (the ordered shutdown, the supervisor contract, the child registry, the
  in-process signal bus) with the gRPC appendix kept as history in one labelled section.
- `docs/explanation/19-module-architecture.md`: rewritten on 3.2's rings; `### Worker Processes`
  (`:46`) goes; the `app-search` reference (`:109`) goes; the module names are explained as
  predating the merge (§0.3).
- `docs/explanation/24-worker-inference-composition.md`: retitled and rewritten as the Engine's
  inference composition (D1-1's registry, D2-2's profiles); `consult-register.v1.json:90-96`
  re-pointed.
- `docs/explanation/23-search-pipeline-overview.md:10` ("spans two processes (Head and Body)")
  corrected, which fixes the `search-quality` skill's generated region.
- `docs/reference/api-contract-map.md`: the live lines (`:53-54,65,94-165,200,241`) updated to
  the D1-12/D1-14 shapes; `GET /api/boot/phases?process={head|worker|brain}` (`:200`) to the
  Engine's phases.
- `docs/explanation/01-system-overview.md`: unchanged unless the grep names a line.
- `docs/explanation/08-observability.md` and `12-desktop-installer-and-sandbox-setup.md`: the
  `engine.log` and installer sentences re-read against the shipped state.

**Acceptance:** `node scripts/docs/docs-validate.mjs` green; `regen-all --check` green (the
skills' generated regions follow); F-1's grep clean on `docs/`.

### F-3 — skills, both trees

Regenerate `.claude/skills/*/SKILL.md` (`node scripts/docs/skills-sync.mjs`) after F-2; hand-edit
the `.agents/skills/*/SKILL.md` copies for the same sentences (`installer:47`, `jseval:130`,
`dev-stack:129`, `module-arch`, `search-quality`, `inference-runtime`'s citations labelled as
history); add a **content** parity check for the sentences both trees must share (a small
allow-listed diff in `check-codex-agent-parity.mjs`, so the divergence §0.6 found cannot recur
silently). **Acceptance:** `skills-sync --check` green; `check-codex-agent-parity` green with the
new content check; F-1's grep clean on both trees.

### F-4 — the subagent baseline brief, jseval and dev tooling

`scripts/agent-analytics/hooks/subagent-guide.mjs:64-71`'s fallback text (`:65` "Head process
never performs Lucene index IO directly") rewritten to the current invariant; jseval:
`backend.py:740-870`'s orphan-Worker sweep deleted (the dev-runner reaps registered children;
B11's registry is the authority), `metrics_reader.py:1-8` reframed (two metric files remain,
neither is a process), `readiness.py:325`, `916_chunk_sweep.py:482`, `ui_check.py:1267`'s
fixture prompt, the two test fixtures; `dev-runner.cjs:21`; `duplicate_prevalence_production.py:446-451`
to the D1-12 shape. `scripts/agent-analytics`: nothing (§0.5, recorded). **Acceptance:** the
jseval suite green (`cd scripts/jseval && python -m pytest`); `node scripts/agent-analytics/run-all-tests.mjs`
green; `npm run lint:scripts` green.

### F-5 — governance registers and contract surfaces

`store-recoverability.v1.json`: the five `owner: "WORKER"` rows (`:609,665,709,740,1152`) get a
`note` stating the owner label is a pre-merge identity kept because the installed updater
compares it (C2-1); `sandbox-coverage.v1.json:76`'s "read the Worker log" instructions
rewritten; `runtime-state.v1.json:21`'s `WORKER` lease holder renamed to `INDEXING` with
`RuntimeGpuLease.Holder` and its status projection (a wire enum on `/api/status`; the FE
consumer updated; `regen-all --check`); `supervision-contract.v1.json`'s remaining
`WORKER_RESTART_EXHAUSTED` mentions labelled; `readiness-reason-codes.v1.json:4`'s count
corrected if D1-14 did not; the `execution-surfaces` and `operation-surfaces` rows keep their
module paths (§0.3) with the register-level note. **Acceptance:** every gate in the kernel
green (`node scripts/governance/run.mjs --mode gate`); `check-store-recoverability` green with
no identity change.

### F-6 — the always-loaded docs

`AGENTS.md` and `CLAUDE.md` invariant 1 are done; F re-reads the Architecture table and the
Common Pitfalls for Worker-era sentences, regenerates the invariants block, and keeps the budget
at its ceiling (`check-always-loaded-budget`, `check-codex-agent-parity`). **Acceptance:** both
checks green; no byte added to an always-loaded doc without an equal trim.

### F-7 — the report-back (design 19)

Fill `design.md` section 19 with the sections of §0.4: cross-lane requests (18's two owner edits,
the inference lane's host contract, lane D's register); PRs (708, 717, 718 and any in-lane
follow-ups); items done, deviated, skipped per stage (from each `stages/<letter>.md` §0.1 and
§9); evidence (the `evidence/` tree with hashes); measurements (E's `table.md` verbatim, the
collector and hang parameters chosen, the bounded conclusion of 16's last paragraph); residue
routed (F-1's output and where each labelled hit lives); what the next lane must know (the
named gaps: Linux recovery, the floor if simulated, the signed installer round if carried, the
module names, the `WORKER` owner labels). **Acceptance:** section 19 non-empty; every claim in
it points at a file under `evidence/`; `docs-validate` green.

### F-8 — PR 1 to green-and-ready

Merge `origin/main` once more (17.6, before the final proof), the full suite with stress, both
supervisor adapters, the installed-process tier, the kernel, ui-web gates, `regen-all --check`;
the PR title and body under the squash-message gate's limits with the public narrative (17.6:
"the PR body written at stage F carries the public narrative"), the review record refreshed on
the final head, the independent review of the F range recorded. **Merge waits for the owner's
go-ahead per handoff rule 1; the delegation of 2026-09-08 covered merges of the split PRs, and
the orchestrator asks once more for PR 1.** Acceptance: `preview-squash-message` and
`pr-review-record check` green; hosted CI green on the PR head.

---

## 4. Commit plan

`docs(936): F-n — …` per item; F-1 first (the script), F-8 last. F-2 and F-4 are the two large
commits and carry a file list in the body.

## 5. Contract changes and their gates

| change | gate |
|---|---|
| `RuntimeGpuLease.Holder.WORKER` → `INDEXING` (wire enum on `/api/status`) | `regen-all --check`; `run-ui-web-gates`; the status schema test |
| `GET /api/boot/phases?process=` values | `regen-all --check` |
| nothing else on the wire: D1 owns the readiness shapes | — |

## 6. Section 16 rows F touches

positive benefit (the deletion count and the three representative changes are reported, not
re-measured); the checkpoint proof is the grep, `docs-validate` and the regen set.

## 7. Harness

`scripts/ci/check-lane-f-residue.mjs` (F-1) and the existing docs/regen checks. No stack.

## 8. Governance changes

The rows named in F-5; `governance/consult-register.v1.json` re-pointed for the retitled doc;
`check-codex-agent-parity.mjs` gains the content check (F-3).

## 9. What is allowed to remain, labelled, and nothing else

1. Module names `worker-services`, `indexer-worker`, `worker-core` and their register paths.
2. `owner: "WORKER"` on five register rows (identity frozen by the installed updater).
3. `requiredCapabilities: ["WORKER"]` (an authorisation token clients carry).
4. ADR-0001/0002 bodies, the gRPC appendix of `02-process-coordination.md`, tempdoc history.
5. The gaps E named, listed in section 19.

Not allowed: any unlabelled hit from F-1; `docs-validate` or a regen check red; the PR body
over the gate's limits.

## 10. Stop rule (17.8)

If the grep at stage start shows a live wire vocabulary D1 did not rename (a `worker.*` code
still emitted, the snapshot slots still `head/worker`), stop: that is D1's unfinished item, not
F's sweep; record it in D1's §0.1 and return there before labelling anything.

## 11. Decisions recorded for F (design section 0, 2026-09-09)

- The residue bar is 17.3's "only labelled hits"; module names and capability tokens are
  labelled, not renamed.
- `00-program-overview.md` is absent from the repo; the report-back uses the sibling lanes'
  sections plus the measurement table and the residue grep.
- `RuntimeGpuLease.Holder.WORKER` is renamed at F; the readiness vocabulary is D1's.

## 12. Implementation batches

| batch | items | verification |
|---|---|---|
| **1 — the script and the docs** | F-1, F-2, F-6 | `docs-validate`; `regen-all --check`; `check-always-loaded-budget`; F-1 clean on `docs/` |
| **2 — skills, tooling, registers** | F-3, F-4, F-5 | `skills-sync --check`; `check-codex-agent-parity`; jseval pytest; `run-all-tests.mjs`; `lint:scripts`; the kernel; `run-ui-web-gates` |
| **3 — the report and PR 1** | F-7, F-8 | the full suite with stress; both adapters; the installed tier; `preview-squash-message`; `pr-review-record check`; hosted CI on the PR head |

---

*End of the stage-F checklist. Drafted 2026-09-09 at `be47faa40`; the grep is re-run at stage
start. Corrections found while sweeping go to §0.1.*
