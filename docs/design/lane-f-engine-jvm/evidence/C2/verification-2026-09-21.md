# C2 current verification — 2026-09-21

Production revision: `ba1440624b2fd0b4d0cc1dd4254c87dfe6d29001`.
Subsequent pending changes are documentation and governance citation corrections.
This record reconciles evidence; it does not waive outstanding acceptance items.

| Proof | Result and accessible evidence |
| --- | --- |
| Local full stress2264 | `gradlew.bat -PtestParallelism=1 --continue test -PincludeStress=true`: BUILD SUCCESSFUL in23m53s. 11,329 cases /1,766 suites, zero failures/errors,31 skips;11 unchanged module tasks UP-TO-DATE. `tmp/2264-full-stress.txt`, copied `tmp/2264-full-stress-xml/`, per-task counts/reuse `tmp/2264-full-stress-counts.json`. WholeProgramDeadCodeTest actually executed1/1. |
| Installed matrix2260 | All11 recovery cases passed without skips; all11 owned stops and closed ports independently inventoried. `tmp/2260-installed-junit-matrix*`, `tmp/2260-installed-runtime-inventory.json`. Distribution stamp `e98118e565edd1b9`. |
| Hosted integration2268 | Run35594867134, job106317260240; actual synthetic merge revision `da29d6194017a4f7fbd26bbc4ccc490fb1cf5781` (ba1440624 into b4d972b6b). Windows, Temurin25.0.4+101, Node24.14.0; integration command `./gradlew.bat :modules:system-tests:integrationTest -PskipWebBuild=true --console=plain`. BUILD SUCCESSFUL18m42s;99cases/21suites:57passes,42skips,zero failures/errors. OperationResume11/11 and migration pass; no retry evidence. Full log/XML/skip inventory at `tmp/2268-hosted-ba1440624/`. XML gives no reasons for its42skips; none is a required C2 crash case. |
| Current standard-model2269 | Installed publication build, owned run `a87a74d6-0171-478f-99e7-3d1866e4b533`. jseval tier2-eval against API55819 /llama8082, query `tmp/1925-live-query.json`, one query. Served `Qwen_Qwen3.5-9B-Q4_K_M.gguf`; expected Captain Mortimer Flux, exact1.0,zero errors. `tmp/2269-model-query/tier2-eval.json` and `tmp/2269-model-query.txt`. Functional proof only, not a quality benchmark. Owned stop reports portsClosed:true; post-stop health ABSENT/no foreign runs/no inference orphan. |

Hosted run35594867134 overall failed only Public claims: AGENTS8721bytes exceeded
8573 by148. The pending wording correction preserves obligations at8566bytes,
regenerates CLAUDE, and passes the existing local budget/projection/parity checks.
No ceiling was raised. Hosted proof of that correction remains required. The
workflow support advisory also identifies Gradle9.6.1 superseded by9.7.1; no
unrelated toolchain upgrade is claimed here.

Bulky raw artifacts remain in this worktree's ignored `tmp` through lane acceptance
plus30days; export them before releasing the worktree. Source/test summaries live
in Git; a hash alone is not the evidence destination.
