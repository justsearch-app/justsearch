# Stage F residue inventory (2026-09-30)

Read-only inventory by Luna (gpt-6-luna) at 871e688a7. Verbatim. Counts are raw matching lines for the F.md terms.

## Result

The tree does **not** satisfy Stage F’s “nothing on the branch names the Worker as a process” goal. The clearest current-prose residue is in canonical docs, both skill trees, jseval tooling, governance, and tests. `scripts/ci/check-lane-f-residue.mjs` and `evidence/F/residue-grep.txt` are absent, so there is no executable F-1 grep or saved checkpoint output to report.

The grep counts below are **raw matching lines** for F.md’s terms, excluding `docs/tempdocs/` and `docs/design/lane-f-engine-jvm/` as its introduction specifies. They include broad `gRPC` and `SupervisionPolicy` matches; F.md says those count only when presented as current, so these are candidate-hit counts, not a claim that every match is residue.

## Commands run

I read `docs/llms.txt`, all of `docs/design/lane-f-engine-jvm/stages/F.md`, and the residue section of `docs/tempdocs/917-lane-f-derisk-and-consumer-audit.md` (including its §8 term-count table). The residue-section lookup was:

```powershell
rg -n "residue" docs/tempdocs/917-lane-f-derisk-and-consumer-audit.md
```

I then ran this `rg` command once for each path in the table below:

```powershell
rg -n --color never 'MMF|heartbeat|suicide|breath|OFFSET_|WorkerSpawner|ORDINAL_WORKER_SNAPSHOT|WORKER_FORWARDED_PROPS|MainSignalBus|MmfWorkerSignalBus|MmfWorkerSignalLayoutV1|WorkerLivenessDecision|SupervisionPolicy|main_gpu_active|ForegroundLoadInterceptor|WorkerProcessManager|RemoteKnowledgeClient|worker\.log|head\.log|core\.worker-log|core\.head-log|Head/Worker|two JVMs|gRPC|io\.grpc|worker-config-snapshot|restart-worker|Worker restart|WORKER_|owner: "WORKER"|Worker process|Worker JVM' '<path>'
```

| Path | Matching lines |
|---|---:|
| `docs/explanation/` | 114 |
| `docs/reference/` | 49 |
| `docs/how-to/` | 3 |
| `docs/decisions/` | 97 |
| `.claude/skills/` | 29 |
| `.agents/skills/` | 29 |
| `.claude/rules/` | 0 |
| `AGENTS.md` | 0 |
| `CLAUDE.md` | 0 |
| `governance/` | 60 |
| `scripts/` | 244 |
| `modules/**/src/main/` | 268 |
| `modules/**/src/test/` | 97 |
| `SSOT/` | 1 |
| `contracts/` | 2 |

These counts are matching lines, not distinct files. I used the `modules/` result only for paths under `src/main` and `src/test`. The 917 table describes its counts as files containing terms; these counts instead follow the requested hit count as matching lines.

## Classification

**RESIDUE — rewrite these current claims or behaviors:**

- **Docs:** `docs/explanation/07-ui-host-architecture.md:18` says the Head manages a Worker process; `03-knowledge-server.md:400,425` and `05-ai-architecture.md:14` describe a Worker JVM; `23-search-pipeline-overview.md:186` says the pipeline runs in the Worker process; `24-worker-inference-composition.md:10` starts from a Worker process. In `12-desktop-installer-and-sandbox-setup.md:41`, the updater still names Head/Worker shutdown. In `docs/reference/configuration/environment-variables.md:179,208`, extraction modes refer to a Worker JVM and the worker deadline is a Head-to-Worker RPC. Other clear references: `docs/reference/model-inventory.md:19`, `docs/reference/jseval-pipeline-reference.md:1076,1115`, and `docs/reference/contributing/common-workflows.md:72`.
- **Skills:** stale passages in `.claude/skills/{installer,jseval,inference-runtime,search-quality}/SKILL.md` and their `.agents/skills/` counterparts repeat installer shutdown, Worker JVM, and orphan-Worker cleanup claims. `search-quality` also repeats the stale pipeline statement.
- **Jseval/dev tooling:** `scripts/jseval/jseval/backend.py:570,621,741,847,865` still describes or implements orphan-Worker handling; `metrics_reader.py:5`, `readiness.py:325`, and `duplicate_prevalence_production.py:450` retain process-era descriptions or health wording. `scripts/jseval/serve-eval-backend.py:15` and `scripts/jseval/tests/test_preflight.py:77` also describe a Worker JVM/process. These match F-4’s identified cleanup areas.
- **Governance:** `governance/sandbox-coverage.v1.json:76` still instructs readers to read the Worker log. `governance/runtime-state.v1.json:21` retains the `WORKER` GPU-lease holder that F-5 explicitly plans to rename.
- **Module source and tests:** current-sounding claims include `modules/app-api/src/main/java/io/justsearch/app/api/{WorkerServices.java:6,HealthNodeView.java:8,ServiceGraph.java:12}`, `modules/app-api/src/main/java/io/justsearch/app/api/runtime/RuntimeManifest.java:163`, and `modules/ui/src/main/java/io/justsearch/ui/api/PreviewController.java:30`. `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/AnswerSegmentationTest.java` contains a model prompt asserting separate Worker ownership and gRPC delegation (lines 95–115, 209–218, 299–317, 340–348); this is a fixture that states obsolete architecture, not a retained legacy test. Additional stale test descriptions occur in `modules/app-engine/src/test/java/io/justsearch/app/engine/EngineExtractionSandboxChaosTest.java:38,75`.

**UNSURE — inspect before deciding whether to rewrite:** `docs/explanation/03-knowledge-server.md:400` calls the in-process indexing side the Worker JVM while distinguishing it from the extraction sandbox child; `docs/explanation/23-search-pipeline-overview.md:95` does likewise. These may mean the logical indexing component rather than a separate JVM, but the wording conflicts with the one-Engine rule. `modules/ui/src/main/java/io/justsearch/ui/api/StatusLifecycleHandler.java:115,1771,1795` and `modules/app-api/src/main/java/io/justsearch/app/api/status/StatusMeta.java:8` retain Worker/gRPC observation language; verify against the current health contract before rewriting.

**LABELLED-HISTORY:** `docs/explanation/02-process-coordination.md` has a historical description and lane-F banner, but still contains extensive old present-tense Head/Worker mechanics (for example lines 74–186 and 211). It is allowed by the grep’s label rule, yet F-2 still explicitly requires rewriting it as Engine coordination with a labelled historical appendix. ADR-0001/0002 bodies, superseded ADR prose, stage-A/item-tagged passages, and the `worker-config-snapshot` retirement notes are also labelled history.

**IDENTIFIER:** retained module/package names such as `worker-services`, `indexer-worker`, and `io.justsearch.indexerworker` are allowed under F.md §9. The `health-events.worker.*` ID is also retained vocabulary; proto fields preserved without their own contract migration are identifiers, not separate-process claims.

## Generated files

The stale passages in `.claude/skills/` lie in generated skill content; `scripts/docs/skills-sync.mjs` owns those regions. Rewrite the canonical source, then regenerate the Claude skills. The matching `.agents/skills/` files are hand-maintained copies and need their own updates.

`regen-all.mjs` lists eight sets: agent-hooks wiring, Codex hooks, API client, field constants, liveness constants, wire-schema types, notices, and shape handlers. None is the source for the listed prose. `CLAUDE.md` has no residue hits; F.md identifies `AGENTS.md` as its generated source. The missing F-1 script means I could not verify its own generated or saved output.

## Checklist status

- **F-1 — residue script and grep: Not started.** Script and `evidence/F/residue-grep.txt` are absent.
- **F-2 — canonical docs: Partially done.** The Engine overview and historical banners exist, but the Worker-process wording above remains; §19’s module rings, §24 retitling, the search pipeline, and API contract map still need attention.
- **F-3 — both skill trees: Partially done.** Claude generated regions and Codex copies exist, but both contain stale passages. F.md’s requested content-parity addition is not demonstrated by the current hits/evidence.
- **F-4 — subagent guide, jseval and dev tooling: Partially done.** Jseval residue remains. F.md says `scripts/agent-analytics` has no subject; this grep found no matching residue there. The other named edits and verification results are not evidenced here.
- **F-5 — governance/contracts: Partially done.** The store-recoverability row has a retirement note; the sandbox instruction and runtime lease enum remain. `api-contract-map.md` still documents old process-based phases and worker-oriented shapes.
- **F-6 — always-loaded docs: Partially done.** The requested invariant is present in the repository instructions; both files have zero grep hits. The architecture/common-pitfalls re-read and acceptance checks are not evidenced.
- **F-7 — report-back: Not started.** `design.md` §19 says “Not started.”
- **F-8 — PR 1 ready: Not established.** The tree inventory cannot prove PR review, suite, hosted CI, or merge readiness; no F report/evidence records were found.

## Proposed rewrite batches

1. **Canonical docs and API map** — roughly 400–600 lines, led by the long `02-process-coordination.md` rewrite plus §19, §24, pipeline, installer, environment and contract-map edits.
2. **Skills** — roughly 150–250 lines across Claude generated regions and Codex hand-maintained copies; update canonical sources first.
3. **Jseval, dev tooling and fixtures** — roughly 250–400 lines, including backend orphan sweep, metrics/readiness, prompts and test fixtures.
4. **Governance, always-loaded docs and report-back** — roughly 100–200 lines plus §19 report content; update the lease enum and sandbox guidance, then record evidence and remaining labelled hits.