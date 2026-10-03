# PR 1 title and body draft

Draft for current PR #727 at `3c4ff74e5`; #718 is its historical predecessor
([publication lineage](../C2/publication-lineage.md)). Only the title and fenced
body below are proposed PR fields. The map and drafting notes are supporting
material. This file does not establish final F-8 readiness or authorize publication.

## Proposed title

Run API and indexing in one supervised Engine JVM

## Proposed body

```markdown
API and indexing now share one Engine JVM. Direct ports replace their gRPC/MMF process boundary; llama-server and sandboxed extraction remain separate children.

The Engine owns ordered shutdown, component readiness and recovery, bounded admission, and recorded operations. Settings writes carry operation keys and witnesses. Live index/model publication retains issued readers and native leases, and restores the serving generation on refused changes.

Health uses schema 2 (api/index/generative). First-party clients follow the new contract; retired worker restart returns 410. Historical module names and updater owner labels remain for compatibility.

The review-and-fix campaign integrated 23 fix branches. Hosted CI is green at 3c4ff74e5 (37092195712), including the Windows-native rerun. Paired quality, load, memory and recovery measurements: <pending E>. Signed upgrade proof is deferred to the next signed release; D2 is post-merge work.

[Subsystem map, review-record slots and acceptance evidence](docs/design/lane-f-engine-jvm/evidence/F/pr1-body-draft.md#subsystem-map-linked-from-the-body). [Report-back and follow-ups](docs/design/lane-f-engine-jvm/design.md#19-report-back).

Session-Id: <final-session>
```

## Subsystem map (linked from the body)

Managed review record IDs are `<pending>` where no current covering record ID is
available in the supplied evidence. Package/challenger labels in the
[campaign record](review-fix-campaign-2026-10-02.md#accepted-packages-and-verdicts)
are independent review provenance, not managed IDs. Before F-8 readiness, populate
and link actual managed IDs covering each mapped commit range; refresh the record
on the final head and body. Acceptance links below identify proof records and row
IDs, without extending earlier receipts to the current head.

| Module or area | Stages that changed it | Managed review record IDs | Acceptance rows |
|---|---|---|---|
| app-engine, app-launcher; direct ports and root ownership | A, B, C1, C2, D1 | `<pending>` | [A1-A3, A6-A14, A20](../A/a20-suite-and-gates.md); [B4-B6, B17](../B/b17-recovery-proofs.md); [D1-1/2/7/16/17](../D1/acceptance-reconciliation-2026-09-30.md) |
| dev-runner, Tauri; supervisor and registered children | A, B, D1; P1, Q14 integration | `<pending>` | [B7-B14, B17](../B/hosted-ci.md); [D1 recovery/held-native exit](../D1/installed-round-2026-10-01.md); [campaign CI and readiness residual](review-fix-campaign-2026-10-02.md#hosted-ci-and-limits-of-the-receipts) |
| app-api, app-services, ui/web, MCP; request context and refusals | A, C1, C2, D1; P2/P3, Q1/Q4/Q13 integration | `<pending>` | [C1-1..11, C1-15](../C1/acceptance-reconciliation.md); [C2 keyed caller fronts](../C2/recorded-force-claims.md); [D1-15/16](../D1/installed-round-2026-10-01.md) |
| operations, jobs.db, grant/receipt stores; durable recovery | B, C2, D1; P8, Q10 integration | `<pending>` | C2-1..11: [store](../C2/prepared-store.md), [checkpoint](../C2/checkpoint-cadence.md), [pre-walk recovery](../C2/prewalk-recovery-2026-10-01.md), [bulk recovery checkpoint](../C2/connected-bulk-checkpoint-2026-09-21.md); [D1-9/18](../D1/installed-round-2026-10-01.md); [P4 known gap](review-fix-campaign-2026-10-02.md#parked-designs-and-follow-ups) |
| worker-core, worker-services, indexer-worker, adapters-lucene; generations/search | A, C1, C2, D1; P5/P6, S2 integration | `<pending>` | [C1-2/3/10/12](../C1/acceptance-reconciliation.md); [D1-8..11/14/18](../D1/installed-round-2026-10-01.md); [paging fix and residual](review-fix-campaign-2026-10-02.md#stopping-rule-and-integration-corrections) |
| app-inference, encoder implementations; model/native ownership and GPU policy | A, C1, D1; P7/P10, S3 integration | `<pending>` | [C1-7/12/13](../C1/acceptance-reconciliation.md); [D1-4/5/12..14](../D1/installed-round-2026-10-01.md); [E policy diagnosis; measurements <pending E>](../E/gpu-yield-diagnosis-2026-10-01.md) |
| configuration, settings, installer activation; witnessed publication | A, C2, D1; Q3 integration | `<pending>` | [C2-6 witness](../C2/settings-witness.md), [public writers](../C2/public-settings-producer.md); [D1-3..6/11/14](../D1/acceptance-reconciliation-2026-09-30.md); [installed activation](../D1/installed-round-2026-10-01.md) |
| extraction, sandbox, watched roots; confinement and input bounds | A, B, C1, C2, D1; Q4, S5/S6 integration | `<pending>` | [C1-7/14](../C1/parser-containment.md); [C2 pre-walk](../C2/prewalk-recovery-2026-10-01.md); [archive correction and parked root escape](review-fix-campaign-2026-10-02.md#parked-designs-and-follow-ups) |
| event/history/feedback stores; bounded retention and archival capture | C2, D1; Q11, S4 integration | `<pending>` | [C2-4 history](../C2/history-reader.md), [C2-5 bounded-history review](../C2/review-r5-bounded-history.md); [Q11/S4 verdicts and commits](review-fix-campaign-2026-10-02.md#accepted-packages-and-verdicts); [campaign hosted receipts](review-fix-campaign-2026-10-02.md#hosted-ci-and-limits-of-the-receipts) |
| governance registers, Gradle/CI, architecture gates, generated docs | A, B, C1, C2, D1, F; P9/Q7 integration | `<pending>` | [A20](../A/a20-suite-and-gates.md); [C1-16](../C1/governance-sweep.md); [F-5](residue-refutation-2026-10-01.md), [F-6](f6-checks-2026-10-01.md); [post-integration fixes](review-fix-campaign-2026-10-02.md#stopping-rule-and-integration-corrections) |
| canonical docs, skills, jseval, analytics; sweep and measurement instruments | F; E instrument work | `<pending>` | [F-1/2](f1-f2-checks.md), [F-3..5](f345-checks.md), [F-6](f6-checks-2026-10-01.md), [F-7](../../design.md#19-report-back); [E1-E7 <pending E>](../E/values.json) |

## Drafting checks and remaining work

The public body carries the narrative and points to the full four-column map to
stay within the squash-message limits. Keep exactly one session declaration in
the public body and replace its placeholder at final publication. Preserve the
`<pending E>` measurement marker until the paired records and verdict exist.

The preview tool supports `--pr-json` and `--repo-json` even though its help lists
the hosted form. Local JSON fixtures extracted from this fenced body can check
its exact text without editing GitHub. Repository squash settings in that fixture
are assumed PR_TITLE/PR_BODY, not a fresh hosted settings verification.

The preview's public-body check does not itself validate final authorship/session
or managed review coverage; a passing preview cannot close those F-8 obligations.

Validation observed during this drafting assignment (2026-10-03):

| Check | Observed result |
|---|---|
| `node scripts/ci/preview-squash-message.mjs --help` | Exit 0; help describes hosted preview. Source also accepts local JSON fixtures. |
| `node scripts/ci/preview-squash-message.mjs --repo justsearch-app/justsearch --pr-json <local-pr-json> --repo-json <local-repo-json> --json` | Exit 0; 0 errors, 1 advisory `public-body-large` warning (above 1200-character target). Body: 1220 characters, 11 total lines, 6 nonblank lines; within hard 2000/32 limits. |
| Draft extraction / session count | Exactly one required session placeholder; body SHA-256 `b75c70e441ae0b49d58938a27a4fd0cac8c4392f0ca54158c5f80d59a1fc53d5`. |
| `node scripts/docs/docs-validate.mjs` | Exit 0; runtime-config-matrix OK (yaml=109, pairs=237, rows=290); no errors. This is the repository's actual docs-validate path. |
| `node scripts/ci/check-tempdoc-size.mjs` | Exit 0; no docs/tempdocs changes between origin/main and HEAD, nothing to check. Draft files are under docs/design. |
| Campaign merge-parent comparison | All 23 accepted tips match the second parent of their documented integration merge. |

These are local drafting checks. No Gradle, managed review refresh, git write,
publication or new E capture was performed.
