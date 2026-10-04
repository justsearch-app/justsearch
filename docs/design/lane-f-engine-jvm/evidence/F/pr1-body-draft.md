# PR 1 title and body draft

Final body for PR #727; #718 is its historical predecessor
([publication lineage](../C2/publication-lineage.md)). Only the title and fenced
body below are PR fields. The map and drafting notes are supporting material.
This file does not authorize merging.

## Proposed title

feat(936): run API and indexing in one supervised Engine JVM

## Proposed body

```markdown
API and indexing now share one Engine JVM. Direct ports replace their gRPC/MMF process boundary; llama-server and sandboxed extraction remain separate children.

The Engine owns ordered shutdown, component readiness and recovery, bounded admission, and recorded operations. Settings writes carry operation keys and witnesses. Live index/model publication retains issued readers and native leases, and restores the serving generation on refused changes.

Health uses schema 2 (api/index/generative). First-party clients follow the new contract; retired worker restart returns 410. Historical module names and updater owner labels remain for compatibility.

Paired measurements against main on one Windows machine: quality within noise, lower peak memory, the index back about 6 s after a crash (main 24 s), and hang recovery inside its bound. They do not pass the default-flip gate: search timeouts and slower indexing while the agent's LLM and the encoders share the GPU, a changed workflow-fixture citation, and soak failures shared with main. Signed upgrade proof waits for the next signed release; D2 is post-merge work.

[Subsystem map and acceptance evidence](docs/design/lane-f-engine-jvm/evidence/F/pr1-body-draft.md#subsystem-map-linked-from-the-body). [Measurements](docs/design/lane-f-engine-jvm/evidence/E/decision.md). [Report-back and follow-ups](docs/design/lane-f-engine-jvm/design.md#19-report-back).

Session-Id: 5ea56bf3-0e32-4013-a422-87723dd808a1
```

## Subsystem map (linked from the body)

Every row's commits land in one squash commit, so they are covered by one managed
review record: the [#727 record](https://github.com/justsearch-app/justsearch/pull/727#issuecomment-5656214902) comment, refreshed on the final head and body.
Per-package independent review provenance (package and challenger labels, verdicts,
accepted commits) is in the
[campaign record](review-fix-campaign-2026-10-02.md#accepted-packages-and-verdicts).
Acceptance links identify proof records and row IDs; they do not extend earlier
receipts to the final head.

| Module or area | Stages that changed it | Managed review record IDs | Acceptance rows |
|---|---|---|---|
| app-engine, app-launcher; direct ports and root ownership | A, B, C1, C2, D1 | [#727 record](https://github.com/justsearch-app/justsearch/pull/727#issuecomment-5656214902) | [A1-A3, A6-A14, A20](../A/a20-suite-and-gates.md); [B4-B6, B17](../B/b17-recovery-proofs.md); [D1-1/2/7/16/17](../D1/acceptance-reconciliation-2026-09-30.md) |
| dev-runner, Tauri; supervisor and registered children | A, B, D1; P1, Q14 integration | [#727 record](https://github.com/justsearch-app/justsearch/pull/727#issuecomment-5656214902) | [B7-B14, B17](../B/hosted-ci.md); [D1 recovery/held-native exit](../D1/installed-round-2026-10-01.md); [campaign CI and readiness residual](review-fix-campaign-2026-10-02.md#hosted-ci-and-limits-of-the-receipts) |
| app-api, app-services, ui/web, MCP; request context and refusals | A, C1, C2, D1; P2/P3, Q1/Q4/Q13 integration | [#727 record](https://github.com/justsearch-app/justsearch/pull/727#issuecomment-5656214902) | [C1-1..11, C1-15](../C1/acceptance-reconciliation.md); [C2 keyed caller fronts](../C2/recorded-force-claims.md); [D1-15/16](../D1/installed-round-2026-10-01.md) |
| operations, jobs.db, grant/receipt stores; durable recovery | B, C2, D1; P8, Q10 integration | [#727 record](https://github.com/justsearch-app/justsearch/pull/727#issuecomment-5656214902) | C2-1..11: [store](../C2/prepared-store.md), [checkpoint](../C2/checkpoint-cadence.md), [pre-walk recovery](../C2/prewalk-recovery-2026-10-01.md), [bulk recovery checkpoint](../C2/connected-bulk-checkpoint-2026-09-21.md); [D1-9/18](../D1/installed-round-2026-10-01.md); [P4 known gap](review-fix-campaign-2026-10-02.md#parked-designs-and-follow-ups) |
| worker-core, worker-services, indexer-worker, adapters-lucene; generations/search | A, C1, C2, D1; P5/P6, S2 integration | [#727 record](https://github.com/justsearch-app/justsearch/pull/727#issuecomment-5656214902) | [C1-2/3/10/12](../C1/acceptance-reconciliation.md); [D1-8..11/14/18](../D1/installed-round-2026-10-01.md); [paging fix and residual](review-fix-campaign-2026-10-02.md#stopping-rule-and-integration-corrections) |
| app-inference, encoder implementations; model/native ownership and GPU policy | A, C1, D1; P7/P10, S3 integration | [#727 record](https://github.com/justsearch-app/justsearch/pull/727#issuecomment-5656214902) | [C1-7/12/13](../C1/acceptance-reconciliation.md); [D1-4/5/12..14](../D1/installed-round-2026-10-01.md); [E policy diagnosis](../E/gpu-yield-diagnosis-2026-10-01.md); [E2/E3 GPU contention](../E/decision.md#e2--foreground-and-agent-response-under-indexing) |
| configuration, settings, installer activation; witnessed publication | A, C2, D1; Q3 integration | [#727 record](https://github.com/justsearch-app/justsearch/pull/727#issuecomment-5656214902) | [C2-6 witness](../C2/settings-witness.md), [public writers](../C2/public-settings-producer.md); [D1-3..6/11/14](../D1/acceptance-reconciliation-2026-09-30.md); [installed activation](../D1/installed-round-2026-10-01.md) |
| extraction, sandbox, watched roots; confinement and input bounds | A, B, C1, C2, D1; Q4, S5/S6 integration | [#727 record](https://github.com/justsearch-app/justsearch/pull/727#issuecomment-5656214902) | [C1-7/14](../C1/parser-containment.md); [C2 pre-walk](../C2/prewalk-recovery-2026-10-01.md); [archive correction and parked root escape](review-fix-campaign-2026-10-02.md#parked-designs-and-follow-ups) |
| event/history/feedback stores; bounded retention and archival capture | C2, D1; Q11, S4 integration | [#727 record](https://github.com/justsearch-app/justsearch/pull/727#issuecomment-5656214902) | [C2-4 history](../C2/history-reader.md), [C2-5 bounded-history review](../C2/review-r5-bounded-history.md); [Q11/S4 verdicts and commits](review-fix-campaign-2026-10-02.md#accepted-packages-and-verdicts); [campaign hosted receipts](review-fix-campaign-2026-10-02.md#hosted-ci-and-limits-of-the-receipts) |
| governance registers, Gradle/CI, architecture gates, generated docs | A, B, C1, C2, D1, F; P9/Q7 integration | [#727 record](https://github.com/justsearch-app/justsearch/pull/727#issuecomment-5656214902) | [A20](../A/a20-suite-and-gates.md); [C1-16](../C1/governance-sweep.md); [F-5](residue-refutation-2026-10-01.md), [F-6](f6-checks-2026-10-01.md); [post-integration fixes](review-fix-campaign-2026-10-02.md#stopping-rule-and-integration-corrections) |
| canonical docs, skills, jseval, analytics; sweep and measurement instruments | F; E instrument work | [#727 record](https://github.com/justsearch-app/justsearch/pull/727#issuecomment-5656214902) | [F-1/2](f1-f2-checks.md), [F-3..5](f345-checks.md), [F-6](f6-checks-2026-10-01.md), [F-7](../../design.md#19-report-back); [E1-E7 manual verdict](../E/decision.md), [generated table](../E/table.md) |

## Drafting checks

The public body carries the narrative and links the four-column map, the E
decision and the report-back, which keeps it inside the squash-message limits.
It has exactly one `Session-Id:` line. Mutable state (authorship, verification,
review findings) lives in the managed review record, not in the body.

Local check on 2026-10-04: `preview-squash-message.mjs` with local PR and
repository JSON (squash settings assumed `PR_TITLE` / `PR_BODY`): body 1,461
characters, 6 nonblank lines; no errors beyond the fixture's placeholder head SHA;
one advisory `public-body-large` warning (above the 1,200-character target, below
the 2,000 limit). The hosted `preview-squash-message --pr 727` and
`pr-review-record check --pr 727` results on the final head are recorded in the
managed review record.
