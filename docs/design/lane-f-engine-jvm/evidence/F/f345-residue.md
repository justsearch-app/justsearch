# F-3/F-4/F-5 scoped residue - 2026-09-30

Checker result: exit 1. These are lexical hits, not all stale architecture.

Full exact output: `tmp/f345-checks/residue-final.txt` in this worktree (retain 14 days).

| Area | Hits |
| --- | ---: |
| .agents | 17 |
| SSOT | 1 |
| contracts | 2 |
| governance | 18 |
| scripts | 191 |

Every remaining hit and its disposition:

| Source | Why retained or blocked |
| --- | --- |
| `.agents/skills/dev-stack/SKILL.md:112` | BLOCKED: stale hand-maintained skill; protected ACL prevents writes. Apply the F-3 patch; this is not accepted residue. |
| `.agents/skills/inference-runtime/SKILL.md:80` | BLOCKED: stale hand-maintained skill; protected ACL prevents writes. Apply the F-3 patch; this is not accepted residue. |
| `.agents/skills/inference-runtime/SKILL.md:665` | BLOCKED: stale hand-maintained skill; protected ACL prevents writes. Apply the F-3 patch; this is not accepted residue. |
| `.agents/skills/inference-runtime/SKILL.md:699` | BLOCKED: stale hand-maintained skill; protected ACL prevents writes. Apply the F-3 patch; this is not accepted residue. |
| `.agents/skills/inference-runtime/SKILL.md:740` | BLOCKED: stale hand-maintained skill; protected ACL prevents writes. Apply the F-3 patch; this is not accepted residue. |
| `.agents/skills/installer/SKILL.md:87` | BLOCKED: stale hand-maintained skill; protected ACL prevents writes. Apply the F-3 patch; this is not accepted residue. |
| `.agents/skills/installer/SKILL.md:166` | BLOCKED: stale hand-maintained skill; protected ACL prevents writes. Apply the F-3 patch; this is not accepted residue. |
| `.agents/skills/jseval/SKILL.md:859` | BLOCKED: stale hand-maintained skill; protected ACL prevents writes. Apply the F-3 patch; this is not accepted residue. |
| `.agents/skills/jseval/SKILL.md:1120` | BLOCKED: stale hand-maintained skill; protected ACL prevents writes. Apply the F-3 patch; this is not accepted residue. |
| `.agents/skills/jseval/SKILL.md:1159` | BLOCKED: stale hand-maintained skill; protected ACL prevents writes. Apply the F-3 patch; this is not accepted residue. |
| `.agents/skills/jseval/SKILL.md:1176` | BLOCKED: stale hand-maintained skill; protected ACL prevents writes. Apply the F-3 patch; this is not accepted residue. |
| `.agents/skills/module-arch/SKILL.md:36` | BLOCKED: stale hand-maintained skill; protected ACL prevents writes. Apply the F-3 patch; this is not accepted residue. |
| `.agents/skills/module-arch/SKILL.md:224` | BLOCKED: stale hand-maintained skill; protected ACL prevents writes. Apply the F-3 patch; this is not accepted residue. |
| `.agents/skills/search-quality/SKILL.md:1348` | BLOCKED: stale hand-maintained skill; protected ACL prevents writes. Apply the F-3 patch; this is not accepted residue. |
| `.agents/skills/search-quality/SKILL.md:3620` | BLOCKED: stale hand-maintained skill; protected ACL prevents writes. Apply the F-3 patch; this is not accepted residue. |
| `.agents/skills/search-quality/SKILL.md:3663` | BLOCKED: stale hand-maintained skill; protected ACL prevents writes. Apply the F-3 patch; this is not accepted residue. |
| `.agents/skills/search-quality/SKILL.md:3754` | BLOCKED: stale hand-maintained skill; protected ACL prevents writes. Apply the F-3 patch; this is not accepted residue. |
| `SSOT/messages/errors.en.json:149` | Retained emitted recovery/message contract identifier; renaming requires its Java producer and clients together. |
| `contracts/wire/contract_events.proto:16` | Retained wire-event vocabulary or explanatory note; event semantics remain current independently of JVM topology. |
| `contracts/wire/stream.proto:40` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `governance/adr-probes.v1.json:44` | Negative guard for a retired mechanism/import: literal spelling is required to detect its resurrection. |
| `governance/adr-probes.v1.json:584` | Negative guard for a retired mechanism/import: literal spelling is required to detect its resurrection. |
| `governance/adr-probes.v1.json:586` | Negative guard for a retired mechanism/import: literal spelling is required to detect its resurrection. |
| `governance/adr-probes.v1.json:588` | Negative guard for a retired mechanism/import: literal spelling is required to detect its resurrection. |
| `governance/consult-register.v1.json:290` | Historical rationale or retained store/register identity, not a current separate-process claim. |
| `governance/engine-ports.v1.json:40` | Historical rationale or retained store/register identity, not a current separate-process claim. |
| `governance/engine-ports.v1.json:72` | Historical rationale or retained store/register identity, not a current separate-process claim. |
| `governance/engine-ports.v1.json:191` | Historical rationale or retained store/register identity, not a current separate-process claim. |
| `governance/inflight-liveness-projections.v1.json:3` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `governance/inflight-liveness-projections.v1.json:10` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `governance/inflight-liveness-projections.v1.json:32` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `governance/observed-happening.v1.json:22` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `governance/observed-happening.v1.json:24` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `governance/observed-happening.v1.json:140` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `governance/store-corruption-policies.v1.json:46` | Retired durable-store identity/reconciliation and updater regression fixture; identity must remain comparable. |
| `governance/store-recoverability.v1.json:37` | Retired durable-store identity/reconciliation and updater regression fixture; identity must remain comparable. |
| `governance/store-recoverability.v1.json:41` | Retired durable-store identity/reconciliation and updater regression fixture; identity must remain comparable. |
| `governance/worktree-lifecycle.v1.json:8` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/agent-analytics/hooks/dispatch.mjs:106` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/ci/check-readiness-reason-codes.mjs:172` | Synthetic enum/parser regression or historical counterexample; old enum spellings test exact token matching. |
| `scripts/ci/check-readiness-reason-codes.mjs:173` | Synthetic enum/parser regression or historical counterexample; old enum spellings test exact token matching. |
| `scripts/ci/check-readiness-reason-codes.mjs:192` | Synthetic enum/parser regression or historical counterexample; old enum spellings test exact token matching. |
| `scripts/ci/check-readiness-reason-codes.mjs:233` | Synthetic enum/parser regression or historical counterexample; old enum spellings test exact token matching. |
| `scripts/ci/check-readiness-reason-codes.test.mjs:111` | Synthetic enum/parser regression or historical counterexample; old enum spellings test exact token matching. |
| `scripts/ci/check-readiness-reason-codes.test.mjs:114` | Synthetic enum/parser regression or historical counterexample; old enum spellings test exact token matching. |
| `scripts/ci/check-readiness-reason-codes.test.mjs:122` | Synthetic enum/parser regression or historical counterexample; old enum spellings test exact token matching. |
| `scripts/ci/check-readiness-reason-codes.test.mjs:123` | Synthetic enum/parser regression or historical counterexample; old enum spellings test exact token matching. |
| `scripts/ci/check-readiness-reason-codes.test.mjs:161` | Synthetic enum/parser regression or historical counterexample; old enum spellings test exact token matching. |
| `scripts/ci/check-readiness-reason-codes.test.mjs:162` | Synthetic enum/parser regression or historical counterexample; old enum spellings test exact token matching. |
| `scripts/ci/check-readiness-reason-codes.test.mjs:164` | Synthetic enum/parser regression or historical counterexample; old enum spellings test exact token matching. |
| `scripts/ci/check-readiness-reason-codes.test.mjs:182` | Synthetic enum/parser regression or historical counterexample; old enum spellings test exact token matching. |
| `scripts/ci/check-readiness-reason-codes.test.mjs:184` | Synthetic enum/parser regression or historical counterexample; old enum spellings test exact token matching. |
| `scripts/ci/check-runtime-manifest-closure.mjs:48` | Retired durable-store identity/reconciliation and updater regression fixture; identity must remain comparable. |
| `scripts/ci/check-store-recoverability.test.mjs:64` | Retired durable-store identity/reconciliation and updater regression fixture; identity must remain comparable. |
| `scripts/ci/check-store-recoverability.test.mjs:68` | Retired durable-store identity/reconciliation and updater regression fixture; identity must remain comparable. |
| `scripts/ci/check-store-recoverability.test.mjs:974` | Retired durable-store identity/reconciliation and updater regression fixture; identity must remain comparable. |
| `scripts/ci/check-store-recoverability.test.mjs:975` | Retired durable-store identity/reconciliation and updater regression fixture; identity must remain comparable. |
| `scripts/ci/derive-release-sequence.test.mjs:239` | Retired durable-store identity/reconciliation and updater regression fixture; identity must remain comparable. |
| `scripts/ci/derive-release-sequence.test.mjs:243` | Retired durable-store identity/reconciliation and updater regression fixture; identity must remain comparable. |
| `scripts/codegen/gen-liveness-constants.mjs:7` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.mjs:17` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.mjs:18` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.mjs:59` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.mjs:67` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.mjs:71` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.mjs:78` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.mjs:81` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.mjs:87` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.mjs:89` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.mjs:92` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.mjs:94` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.mjs:97` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.mjs:109` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.mjs:122` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.mjs:133` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.mjs:134` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.mjs:139` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.mjs:145` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.mjs:155` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.test.mjs:6` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.test.mjs:31` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.test.mjs:45` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.test.mjs:59` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.test.mjs:65` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.test.mjs:67` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.test.mjs:72` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-liveness-constants.test.mjs:74` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:4` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:8` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:9` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:23` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:41` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:42` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:43` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:44` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:46` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:58` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:59` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:64` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:66` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:69` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:71` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:74` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:86` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:91` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:104` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:105` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:113` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:118` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:119` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:125` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/codegen/gen-stream-liveness-constants.mjs:126` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/HotSwapPush.java:25` | Historical launch/classpath comment; Java edits excluded by assignment. |
| `scripts/dev/dev-runner.cjs:598` | Historical log-rotation/removed-log comparison or retained fixture spelling; current Engine log is already named. |
| `scripts/dev/dev-runner.cjs:599` | Historical log-rotation/removed-log comparison or retained fixture spelling; current Engine log is already named. |
| `scripts/dev/dev-runner.cjs:602` | Historical log-rotation/removed-log comparison or retained fixture spelling; current Engine log is already named. |
| `scripts/dev/dev-runner.cjs:730` | Historical log-rotation/removed-log comparison or retained fixture spelling; current Engine log is already named. |
| `scripts/dev/dev-runner.cjs:760` | Historical log-rotation/removed-log comparison or retained fixture spelling; current Engine log is already named. |
| `scripts/dev/dev-runner.cjs:1133` | Historical log-rotation/removed-log comparison or retained fixture spelling; current Engine log is already named. |
| `scripts/dev/dev-runner.cjs:1149` | Historical log-rotation/removed-log comparison or retained fixture spelling; current Engine log is already named. |
| `scripts/dev/dev-runner.cjs:1945` | Historical log-rotation/removed-log comparison or retained fixture spelling; current Engine log is already named. |
| `scripts/dev/justsearch-dev-mcp/schemas.mjs:48` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/justsearch-dev-mcp/server.mjs:990` | Historical removed log-resource contract discussion; current operator trace remains Engine-owned. |
| `scripts/dev/justsearch-dev-mcp/server.mjs:992` | Historical removed log-resource contract discussion; current operator trace remains Engine-owned. |
| `scripts/dev/run-watcher.mjs:3` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:21` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:24` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:34` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:35` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:36` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:38` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:106` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:110` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:112` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:113` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:114` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:120` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:129` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:133` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:135` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:139` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:148` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:149` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:156` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:162` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:167` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:170` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:177` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:178` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.mjs:179` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.test.mjs:69` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.test.mjs:72` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.test.mjs:76` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.test.mjs:79` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.test.mjs:82` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.test.mjs:84` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.test.mjs:87` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.test.mjs:90` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.test.mjs:95` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.test.mjs:96` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.test.mjs:99` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.test.mjs:102` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.test.mjs:114` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.test.mjs:123` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.test.mjs:138` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.test.mjs:150` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.test.mjs:151` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.test.mjs:196` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/run-watcher.test.mjs:200` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/test-dev-runner-admission.mjs:29` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/dev/test-dev-runner-death-observability.mjs:15` | Historical log-rotation/removed-log comparison or retained fixture spelling; current Engine log is already named. |
| `scripts/dev/test-dev-runner-death-observability.mjs:16` | Historical log-rotation/removed-log comparison or retained fixture spelling; current Engine log is already named. |
| `scripts/dev/test-dev-runner-head-java-opts.mjs:11` | Historical log-rotation/removed-log comparison or retained fixture spelling; current Engine log is already named. |
| `scripts/governance/_fixtures/observed-happening/negative/governance/observed-happening.v1.json:36` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/governance/_fixtures/observed-happening/positive/catalog/FixtureDiagnosticChannelCatalog.java:5` | Synthetic gate fixture tests channel classification, not shipped process topology; Java fixture edits are excluded. |
| `scripts/governance/_fixtures/observed-happening/positive/catalog/FixtureDiagnosticChannelCatalog.java:7` | Synthetic gate fixture tests channel classification, not shipped process topology; Java fixture edits are excluded. |
| `scripts/governance/_fixtures/observed-happening/positive/governance/observed-happening.v1.json:3` | Synthetic gate fixture tests channel classification, not shipped process topology; Java fixture edits are excluded. |
| `scripts/governance/_fixtures/observed-happening/positive/governance/observed-happening.v1.json:31` | Synthetic gate fixture tests channel classification, not shipped process topology; Java fixture edits are excluded. |
| `scripts/governance/_fixtures/observed-happening/positive/governance/observed-happening.v1.json:32` | Synthetic gate fixture tests channel classification, not shipped process topology; Java fixture edits are excluded. |
| `scripts/governance/_fixtures/surface-altitude/positive/catalog/CoreSurfaceCatalog.java:14` | Synthetic gate fixture tests channel classification, not shipped process topology; Java fixture edits are excluded. |
| `scripts/governance/gates/config-surface/enforcer.mjs:16` | Negative guard for a retired mechanism/import: literal spelling is required to detect its resurrection. |
| `scripts/governance/gates/engine-port/rule-descriptions.mjs:11` | Historical comparison explains why an in-process port binding must be registered; guard behavior stays intact. |
| `scripts/governance/gates/engine-port/truth-table.mjs:104` | Historical comparison explains why an in-process port binding must be registered; guard behavior stays intact. |
| `scripts/jseval/624-run-2026-07-18-confirmatory/chain-confirm.bat.txt:23` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/jseval/707-corpora/en-email-enron-raw/entity-bank/entity-bank.v2.json:7267` | Opaque corpus evidence payload: an incidental term inside encoded data; changing bytes would corrupt the fixture. |
| `scripts/jseval/707-corpora/en-email-enron-raw/entity-bank/entity-bank.v2.json:7293` | Opaque corpus evidence payload: an incidental term inside encoded data; changing bytes would corrupt the fixture. |
| `scripts/jseval/707-corpora/en-email-enron-raw/entity-bank/entity-bank.v2.json:7314` | Opaque corpus evidence payload: an incidental term inside encoded data; changing bytes would corrupt the fixture. |
| `scripts/jseval/707-corpora/en-email-enron-raw/entity-bank/entity-bank.v2.json:7367` | Opaque corpus evidence payload: an incidental term inside encoded data; changing bytes would corrupt the fixture. |
| `scripts/jseval/707-corpora/en-email-enron-raw/entity-bank/entity-bank.v2.json:7511` | Opaque corpus evidence payload: an incidental term inside encoded data; changing bytes would corrupt the fixture. |
| `scripts/jseval/707-corpora/en-email-enron-raw/entity-bank/entity-bank.v2.json:7593` | Opaque corpus evidence payload: an incidental term inside encoded data; changing bytes would corrupt the fixture. |
| `scripts/jseval/707-corpora/en-legal-clerc/entity-bank/entity-bank.v2.json:7592` | Opaque corpus evidence payload: an incidental term inside encoded data; changing bytes would corrupt the fixture. |
| `scripts/jseval/707-corpora/en-legal-clerc/entity-bank/entity-bank.v2.json:7595` | Opaque corpus evidence payload: an incidental term inside encoded data; changing bytes would corrupt the fixture. |
| `scripts/jseval/707-corpora/en-legal-clerc/entity-bank/entity-bank.v2.json:7597` | Opaque corpus evidence payload: an incidental term inside encoded data; changing bytes would corrupt the fixture. |
| `scripts/jseval/707-corpora/en-legal-clerc/entity-bank/entity-bank.v2.json:7604` | Opaque corpus evidence payload: an incidental term inside encoded data; changing bytes would corrupt the fixture. |
| `scripts/jseval/707-corpora/en-legal-clerc/entity-bank/entity-bank.v2.json:7614` | Opaque corpus evidence payload: an incidental term inside encoded data; changing bytes would corrupt the fixture. |
| `scripts/jseval/707-corpora/en-legal-clerc/entity-bank/entity-bank.v2.json:7668` | Opaque corpus evidence payload: an incidental term inside encoded data; changing bytes would corrupt the fixture. |
| `scripts/jseval/707-corpora/en-legal-clerc/entity-bank/entity-bank.v2.json:7767` | Opaque corpus evidence payload: an incidental term inside encoded data; changing bytes would corrupt the fixture. |
| `scripts/jseval/707-corpora/en-legal-clerc/entity-bank/entity-bank.v2.json:7899` | Opaque corpus evidence payload: an incidental term inside encoded data; changing bytes would corrupt the fixture. |
| `scripts/jseval/707-corpora/en-legal-clerc/entity-bank/entity-bank.v2.json:8361` | Opaque corpus evidence payload: an incidental term inside encoded data; changing bytes would corrupt the fixture. |
| `scripts/jseval/707-corpora/en-legal-clerc/entity-bank/entity-bank.v2.json:8585` | Opaque corpus evidence payload: an incidental term inside encoded data; changing bytes would corrupt the fixture. |
| `scripts/jseval/748-corpora/de-miracl/entity-bank/entity-bank.v2.json:3044` | Opaque corpus evidence payload: an incidental term inside encoded data; changing bytes would corrupt the fixture. |
| `scripts/jseval/916-corpora/rag-qa-v1/generate.py:59` | Evidence-offset schema/constant, unrelated to the retired MMF offsets; producer and consumer must retain this identifier. |
| `scripts/jseval/916-corpora/rag-qa-v1/generate.py:250` | Evidence-offset schema/constant, unrelated to the retired MMF offsets; producer and consumer must retain this identifier. |
| `scripts/jseval/916_launch_phase.ps1:8` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/jseval/chain-confirm-v5.bat:31` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/jseval/chain-confirm.bat:23` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/jseval/chain-phase2.bat:17` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/jseval/jseval/ui_check.py:1285` | Retained frontend projection identifier; logical index states do not imply a separate process. |
| `scripts/jseval/jseval/ui_fixtures.py:654` | Retained frontend projection identifier; logical index states do not imply a separate process. |
| `scripts/jseval/jseval/workflow_fixture.py:111` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/jseval/jseval/workflow_fixture.py:233` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/jseval/lane-f-workflow-fixture.v1.json:17` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/jseval/lane-f-workflow-fixture.v1.json:127` | Frozen historical workflow/corpus/campaign evidence; changing captured prompts/results would invalidate its identity. |
| `scripts/jseval/lane-f/head-flag-run.sh:168` | Frozen historical workflow/corpus/campaign evidence; changing captured prompts/results would invalidate its identity. |
| `scripts/jseval/tests/fixtures/recorded/justsearch_search_structured.json:11` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/jseval/tests/test_index_cache.py:33` | Historical log-rotation/removed-log comparison or retained fixture spelling; current Engine log is already named. |
| `scripts/jseval/tests/test_workflow_fixture.py:1975` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/jseval/tests/test_workflow_fixture.py:1996` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/jseval/tests/test_workflow_fixture.py:2042` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/jseval/tests/test_workflow_fixture.py:2044` | Current job, SSE, watcher or lease liveness vocabulary; not the retired cross-process MMF liveness mechanism. |
| `scripts/release/app-release-assets.test.mjs:24` | Retired durable-store identity/reconciliation and updater regression fixture; identity must remain comparable. |
| `scripts/release/app-release-assets.test.mjs:28` | Retired durable-store identity/reconciliation and updater regression fixture; identity must remain comparable. |
| `scripts/release/app-release-assets.test.mjs:182` | Retired durable-store identity/reconciliation and updater regression fixture; identity must remain comparable. |
| `scripts/release/app-release-assets.test.mjs:198` | Retired durable-store identity/reconciliation and updater regression fixture; identity must remain comparable. |
| `scripts/sandbox/sandbox-environment.md:123` | Historical log-rotation/removed-log comparison or retained fixture spelling; current Engine log is already named. |
| `scripts/sandbox/sandbox-start-SKILL.md:122` | Historical log-rotation/removed-log comparison or retained fixture spelling; current Engine log is already named. |
| `scripts/sandbox/sandbox-start-SKILL.md:124` | Historical log-rotation/removed-log comparison or retained fixture spelling; current Engine log is already named. |
| `scripts/supervisor-conformance/component-recovery-scenario.mjs:247` | Retained emitted recovery/message contract identifier; renaming requires its Java producer and clients together. |
| `scripts/supervisor-conformance/real-writer-recovery.mjs:333` | Retained configuration key consumed by Java; a rename is outside this prose/sweep batch. |
