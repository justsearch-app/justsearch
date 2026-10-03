# C2-6 accepted reset producer

2026-09-14. The reset handler freezes readable settings witness or proven absent-history
quarantine metadata through SettingsServiceImpl, supplies approval text from that frozen
record, and executes only with the runner's accepted handle. It has no raw execute path.
The real accepted decoder independently binds the row, key, nonce, descriptor and SQL
marker; the handler's pure validation does not grant mutation authority.

ServicePhase injects the store and runner directly. HeadlessApp composes the fixed owner
before runner boot reconciliation and the index startup fork, using its existing ordered
restart action and the shared SettingsV2 projection. The controller reset method and
settingsResetFn late-binding holder are removed. The catalog classifies reset as
SETTINGS_APPLY. Launcher retains IN_MEMORY settings and refuses preparation without
inventing a restart owner. Its full module tests pass.

Focused1372 executes71 cases,3 pre-existing skipped architecture placeholders. Final1376
passes the same71 from cache after comment/import cleanup, plus PMD/format and UI
integration compilation. Adjacent1375 executes3175 cases/505 suites across app-api,
app-launcher, app-services and selected UI settings/Headless/restart tests;3 existing
skips, no failures/errors. The real dispatcher test proves the declared catalog kind,
acceptance before effect, a completed retry bypassing service/preparation, and a frozen
preview refusing VERSION_CONFLICT after a later reset advances the witness. Recovery
producer tests prove preparation preserves corruption evidence and only completed reset
requests restart; this callback test is not installed successor-bootstrap proof.

Initial1370 tests passed but PMD caught a redundant qualifier;1371 passed after its removal.
The first adjacent invocation1374 failed task selection because the Windows launcher
expanded the unqualified Settings wildcard to settings.gradle.kts;1375 uses qualified
class patterns. Neither failed invocation is claimed as a successful check.
The operation-surface gate caught the newly introduced consumer before registration;
its declared runner-port projection passes1374 with unchanged enforcement. Engine-port,
canonical links, regenerated index and skill-sync checks pass.

Independent review found a stale canonical consumer count and obsolete controller
references. Root corrected the count to one actual ResetSettingsHandler consumer and
removed the residual import/comments; the guard cap was not weakened. Final independent
review is clear. Reviewer read the final source and parent evidence and did not run Gradle.
[Exact commands, final source hashes and retained output](settings-reset-producer.json).

Remaining: migrate every other writer with captured full-witness CAS, expose atomic
snapshot/witness GET and keyed frontend ingress, project sticky recovery Health, and run
named public-wire and installed successor-bootstrap scenarios. D1/D2/E/F remain open.
