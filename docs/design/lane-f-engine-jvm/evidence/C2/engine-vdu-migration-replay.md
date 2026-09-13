# C2-2 real Engine legacy VDU migration replay

September12, Windows/Java25, production b532a56ee plus the new
EngineVduMigrationReplayTest. The fixture boots the real EngineRoot and
KnowledgeServer; operation collaborators remain the existing harness doubles.

## Contract and independent review

Index a Blue source, start and pause migration, then reopen and explicitly index
the same parent into Green while enumeration/cutover are paused. Close, rewrite
the source and seed one frozen legacy VDU_UPDATE row in the isolated jobs.db.
The next paused MIGRATING boot must retain that exact row version. Resume,
observe enumeration and ordinary queue drain, await durable pointer promotion,
then explicitly reopen. Eligible replay removes the committed row. A later
reopen preserves the replacement source and the row remains absent.

Independent review found that an initial SUCCESS_TEXT replay could overwrite the
old Green parent and hide failed source re-enumeration. The final SUCCESS_EMPTY
payload preserves source text. The eligible reopen must find the replacement
marker and must not find the original marker; the later reopen must still find
the replacement. Queue drain alone is not used as proof of successful indexing.
The reviewer reread this correction and found no remaining fixture blocker.

The real Green parent is seeded before the ineligible boot, so premature replay
can succeed and erase the row. The production-guard mutation below proves this
negative capability rather than relying on a missing parent to retain the row.

## Verification

Commands use `gradlew.bat :modules:app-engine:test --tests '*EngineVduMigrationReplayTest'
-PtestParallelism=1 --max-workers=4 --console=plain`. Positive runs additionally
select `:modules:app-engine:pmdMain :modules:app-engine:pmdTest`.

| Run | Result |
| --- | --- |
| 715 | Test executes and passes1 case. Overall command fails PMD for a redundant cleanup assignment. Reuse the fixture's close helper; no suppression. |
| 716 | Initial content-replacing fixture executes and passes1 case plus PMD. |
| 717 | Python's relative Windows batch invocation fails before Gradle starts; no test evidence. Corrected to an absolute gradlew.bat path. |
| 718 | Force KnowledgeServer.vduReplayAllowed to true: initial fixture fails at exact row retention on the paused boot, because the row was consumed. |
| 719 | Final content-preserving fixture executes and passes1 case, zero failures/errors/skips,32s. Test PMD executes and passes; main PMD reuses unchanged results. |
| 720 | Same production-guard mutation with the final fixture:1 executed case,1 intended failure at the paused-boot requireBuffered call (row absent),22s. |
| 721 | Exact production bytes restored: PASS1 case FROM-CACHE719; both PMD tasks reuse unchanged results. |

The production guard itself also has successful hosted CI
[34711067677](https://github.com/justsearch-app/justsearch/actions/runs/34711067677)
at b532a56ee. Named stress714 passes2 cases with both tasks UP-TO-DATE from the
documented prior stress run; it is unchanged-input reuse, not new execution.
The later [checkpoint724](vdu-checkpoint-724.md) passes full integrated verification
and hosted CI34712074865 at55b8aeb15 including this fixture.
Actual-model/installed offline completion remains open.

Explicit fixture restarts do not prove automatic product restart or live service
activation. The generation checks remain observations, not an atomic generation
lease. D1 still owns carry-forward and activation/retirement under accepted writes.

## Evidence access

This worktree's `tmp/c2-2-engine-vdu-replay-{715,716,719,721}*` and
`tmp/c2-2-engine-vdu-replay-negative-{718,720}*` each contain `.txt`, `-xml/` and
`-counts.json`. Launcher failure717 is `tmp/c2-2-engine-vdu-replay-launch-717.txt`.
`tmp/c2-engine-vdu-replay-mutant.py` restores KnowledgeServer's exact original
bytes in finally; its original-byte backup is beside the script. Capture utility:
`tmp/c2-2-capture-generation.py`. Retain through lane acceptance plus30 days;
export before releasing the worktree. XML counts alone do not certify PMD/build
success: consult each corresponding log and the result distinctions above.
