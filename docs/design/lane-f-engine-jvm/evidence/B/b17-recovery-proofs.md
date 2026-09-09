# B17 recovery proofs — 2026-09-08

**Hosted-proof correction:** this record establishes the local checkpoint. B9's separate hosted
PR obligation was omitted from the completion claim; [hosted-ci.md](hosted-ci.md) tracks the
first PR run, its concrete failures and their correction before C1. Platform and installer proof
limits remain explicit, including the Windows-only advisory installed tier.

Implementation checkpoint after B15 `cb760f409`, followed by B17 `b8094f9cf` and
main integration `39f598efa` (main parent `f938c4eb2`, accepted PR 0b).

The installed-process JUnit entry point is now `EngineSupervisedRecoveryE2ETest`.
Its five scenarios cover fatal writer/PENDING recovery, migration start/promotion/rollback,
hostile locks before boot and during ingestion, and durable PROCESSING replay. The lock
workload moved from the embedded Engine stress class without relaxing its thread counts,
lock holds, 100-document corpus, accepted-work assertion or 180-second search bound.
An acquisition counter excludes a no-op attack. The intruder releases its handles before
the owned dev-runner cleanup; the old embedded test and stress-policy entry are removed.

The PROCESSING scenario waits for a real queue claim and entry into the existing chaos
extraction child, verifies PID/creation time/full command with the repository identity
helper, and deliberately crashes only that fixture's Engine. The host's test-only cooldown
allows a fresh read-only SQLite observation of PROCESSING after actual death and before
the successor starts. Switching the owned test parser argfile to the real parser lets
startup recovery, DONE and an exact path/content search hit prove replay. The first
attempt stopped before crashing because the test compared a canonical lowercase Windows
path case-sensitively; the corrected five-case run passed in 1m47s. This identity collector
proof is Windows-only; the wrapper declares that limitation rather than bypassing it.

The Tauri conformance actuator now owns no parallel Child or cached port. It uses
EngineHost admission, manifest/current-binding validation, reap, kill, state publication
and local handoff methods; every running publication asserts matching owned/manifest/state
PID and instance. Existing 17 cases all pass through that binding. Both independent
read-only reviews found no remaining blockers. AppHandle setup and graphical event sinks
remain source-reviewed, and signed installer/user-store proof remains in stage E.

At this checkpoint: installed cases 5/5; Tauri adapter 17/17; Rust library 77/77;
build excluding tests passed (325 tasks, 12s); script lint, UI gates 27/27, runtime
closure (3420 files/0), recoverability and readiness/stress policy checks passed.
Producing the license inputs also found thirteen stale notices for removed gRPC
runtime dependencies; regenerated THIRD_PARTY_NOTICES removes that Stage A residue.
The unfiltered eight-set `regen-all --check` now passes.

## Final integrated verification

The independent merge review checked all 19 conflict resolutions against both parents.
It found one obsolete Worker-log diagnostic in the imported fixture; corrected to Engine,
with all 192 workflow-fixture tests passing. The lane's one-Engine launch and ownership
behavior remain intact; PR 0b's accepted fixture and determinism pins are integrated.

- `gradlew.bat test -PincludeStress=true --no-build-cache --rerun-tasks --console=plain`:
  **9,456 tests, zero failures/errors, 25 skips, 34 modules, 1,533 XML files**, 10m25s.
  This includes the extraction chaos test and the opt-in stress tests, without a name filter.
  Exact `test` XML was copied to `tmp/b17-integrated-full-xml` before any subsequent run;
  its summary includes the per-module counts and its inventory hashes every file.
- `gradlew.bat build -x test`: passed, 325 tasks, 1m49s, including Spotless and PMD.
- `:modules:system-tests:integrationTest --tests
  io.justsearch.systemtests.supervision.EngineSupervisedRecoveryE2ETest
  -PskipWebBuild=true --console=plain`: **5/5**, 1m56s, on the integrated distribution
  `7d26950fa9fd473f`. The PROCESSING proof includes the before/after-read assertion that
  no successor incarnation has begun. Each owned run reports `portsClosed:true`.
- Explicitly rebuilt `supervisor-conformance`; `cargo test --lib --locked`: **77/77**;
  both `run.mjs --adapter tauri` and `--adapter dev-runner`: **17/17**.
- Frontend typecheck passed; unit suite **6,462/6,462**, 482 files; UI gates **27/27**.
  Dev-runner tests **10/10**; final script lint passed. Runtime closure, recoverability,
  readiness, stress policy, wire, configuration, docs index/links and skills checks pass.
  Unfiltered `regen-all --check`: **8/8** generated sets match.

The initial integrated installed run was 4/5: boot contention made the manifest temporarily
unreadable, and the fixture dereferenced null before submitting its corpus. The corrected
fixture waits for a readable supervisor/manifest pair with matching PID, instance and port,
and bounds the pre-submission binding read. It preserves the lock attack, accepted-work,
search and health assertions. Independent read-only review cleared that correction; all five
cases then passed. This is a fixture-only change after the full Java run, not a production fix
hidden behind an earlier test total. The failed run remains in the ignored evidence inventory.

Raw logs and exact XML remain in ignored `tmp/`; tracked evidence contains summaries and hashes.

The final development smoke ran from `scripts/jseval`:
`python -m jseval run --dataset scifact --modes lexical --max-queries 3 --no-embedding
--no-splade --no-ce --no-lambdamart --start-backend --fresh-index
--output-dir ../../tmp/b17-jseval-smoke`, with a fresh owned data directory at
`tmp/b17-jseval-smoke-data`. It completed in about 75 seconds: readiness passed, 5,183
corpus documents plus `__jseval_sentinel__.txt` were indexed, three queries completed with
zero errors, and all nine projections ran successfully. The command's existing identity-
scoped cleanup removed the Java process and foreign-run record; final `quick_health` is
ABSENT with no foreign runs. This is a workflow smoke, not the stage-E quality gate or a
throughput comparison. Its cleanup still labels the Engine "Worker" in diagnostics;
that naming residue remains for the final F terminology sweep and changes no ownership.

Final installed runs, all with `portsClosed:true`:

| Scenario | Owned run ID | Ignored directory under `tmp/lane-f-takeover/` |
| --- | --- | --- |
| Writer/PENDING | `6156f452-647d-437b-a724-b291a19f44af` | `writer-junit-ed72f583-4260-4e50-a820-c71026aeab1e` |
| Migration | `57e60acb-8ef3-4401-8d6a-b3b2287fefca` | `writer-junit-68a4cea6-36c5-478e-aeff-fbc067038aa8` |
| Boot locks | `2ec8129d-f53f-4541-9015-fef071918aa3` | `writer-junit-954e4f2c-7180-4792-a240-e707229b62ea` |
| Ingest locks | `8fa743c7-a8f3-4b2d-acfe-f504d2d978ca` | `writer-junit-12dd5283-83ce-445d-ad4a-c4f06397ba9c` |
| PROCESSING replay | `2509c9e8-8783-4df5-816f-107654ba0a57` | `writer-junit-e0dbeceb-a0ec-436b-a353-d7971ffabded` |

## SHA-256 inventory

| Ignored artifact | SHA-256 |
| --- | --- |
| `tmp/b17-integrated-full-stress.txt` | `d9afaf01437cc2233d965efc8756af7fbeb1b80034af42a7941474c9009c6c54` |
| `tmp/b17-integrated-full-xml/summary.json` | `cf9b53e2c16fb5e5329b7f86a499aa4d92e166babc5d190ea0cb3bf923d5378c` |
| `tmp/b17-integrated-full-xml/inventory.json` | `34da6ef83f6da8f95cba4b07019664e38a727f9d1882f14c14a2c40e35204c5c` |
| `tmp/b17-integrated-build.txt` | `5e267c35d13776571fbab8cbb966639522042cb4d6461e57577ce0317f7cab38` |
| `tmp/b17-final-installed.txt` | `ae82d4fabe4f86d34cbadeda81b1a4c92f9f832dfe0a9af756564377511c529e` |
| `tmp/b17-final-installed-initial-failure.txt` | `c7fa62fe3c9959fb094b3bf10816e51240abf5cd7e726f9c5db8821c7494a81d` |
| `tmp/b17-installed-xml/TEST-io.justsearch.systemtests.supervision.EngineSupervisedRecoveryE2ETest.xml` | `5d8041f5d4f58bb7c87b4ed1a55a9aa462a0160200b5b61b6d03a8d48ec6054b` |
| `tmp/b17-final-host-bin.txt` | `385824e0e40cadfc44da35e7de598ffadb003700bbe5819c95916b204f2c4016` |
| `tmp/b17-final-rust-lib.txt` | `10f1f541d91acab830387e0d9264083057ec35e9e60cd4dbdd11f3c93780c4db` |
| `tmp/b17-final-tauri-conformance.txt` | `d4917f0c82cc87e4ed56ddaa18a410f9e46f56d0e5c8620ddda28279065f059b` |
| `tmp/b17-final-dev-conformance.txt` | `6a804ad96bc69c8899d023f92493c79f67d4c6d873efe6b7dbb78ce5988bb43c` |
| `tmp/b17-ui-typecheck.txt` | `596373b383c6e3421b378952b2fbf2d04b8a1bcef36fd19dc1812d88a565e100` |
| `tmp/b17-ui-unit.txt` | `35c813d1856b7e7a862b990bd430fdb4cac68825e64b8a0019077de96c20105c` |
| `tmp/b17-ui-gates.txt` | `dd9992b5cd376088a15ae48c018dc2462f549f4bebee4f1c8809e2f07d6f233c` |
| `tmp/b17-fixture-merge-tests.txt` | `163fb5fbbb5a38087322ef3c0c53b15fe838295bda592324a32c219fd0910897` |
| `tmp/b17-dev-runner-final.txt` | `7c9d4b521197f0a9fc79368337a7b8c4c707d0dbf4799b3901a3688a3f072d60` |
| `tmp/b17-final-script-lint.txt` | `0499c0cd096a7613383d8185614c7aaec2c0dafc11cefc899943494a4778f9c7` |
| `tmp/b17-integrated-regen.txt` | `1d8a655ccd2e35decd38e61cddd305fb60e2ef05946326d1ad9bbd7a7566f4d5` |
| `tmp/b17-integrated-wire.txt` | `9227bd322ae1fe365d6f44d9ceec455c5aa586189b85a9dae0e00a3546789382` |
| `tmp/b17-merge-config-gate.txt` | `b06517fde1729fea78ca6bf1ac28a6444219ac0af2b093c96df8453392210ccd` |
| `tmp/b17-jseval-smoke.txt` | `e32b5b3dd71ae8bdb232733e90dadd8769736e951fe77e233cfd6bc3649005e9` |
| `tmp/b17-jseval-smoke/20260908T190852_scifact/summary.json` | `aa9c0692f873c1fdff2fdadea7e5c1c8885034cfc8133f55e3a45e9e181d9867` |
| `tmp/lane-f-takeover/writer-junit-ed72f583-4260-4e50-a820-c71026aeab1e/fixture-output.txt` | `e92b3f1df9c244b2a566d816b8e7fbc5ce133e01ce96599ec226d1bfd2d01de0` |
| `tmp/lane-f-takeover/writer-junit-68a4cea6-36c5-478e-aeff-fbc067038aa8/fixture-output.txt` | `71bd69cc89f8d018b7849b9c39739e14588ac97b096df55de99441004cd6865e` |
| `tmp/lane-f-takeover/writer-junit-954e4f2c-7180-4792-a240-e707229b62ea/fixture-output.txt` | `50e57e475283233b0a6abee1f518f8f0585baf1c554e262fd420741b17df47b5` |
| `tmp/lane-f-takeover/writer-junit-12dd5283-83ce-445d-ad4a-c4f06397ba9c/fixture-output.txt` | `f0b7a77e87148f99eb4b6da60966a4020a52887b375d2b839e58946ca9bd517e` |
| `tmp/lane-f-takeover/writer-junit-e0dbeceb-a0ec-436b-a353-d7971ffabded/fixture-output.txt` | `809db11af5a3246f4e35a660bcc4425d33f84096c9f7c1a307e2aceda3b9365e` |
## C1 correction to hostile-lock injection duration (2026-09-09)

The B17 measurements below remain historical proof of their tested revisions. Integrated C1
run221 exposed repeated fatal reinjection exhausting all successor attempts. Design §16 now
requires the original attack through acceptance of all 100 documents, then ends injection only
after a counted exit at or after that submitting incarnation. The existing JUnit release
acknowledgement precedes recovery probing; the 180-second bound and real acquisition assertions
remain. Healthy runs continue under attack. See the [C1 phase correction and evidence](../C1/hostile-lock-phase.md).
