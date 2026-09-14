# C2-6 public settings wire prerequisite — 2026-09-14

GET now reads settings and their full witness from one inspect snapshot. Default
index-path injection stays response-only. Unreadable state returns nonretryable
503 SETTINGS_RECOVERY_REQUIRED without resetting or quarantining the file.

SettingsV2 adds nested witness, operationKey and state with the four-argument Java
constructor retained. A non-null witness requires both revision and nullable key.
The fixed committed receipt supplies its authoritative witness, overriding prepared
metadata. Receipt-only responses need not fabricate settings sections.

Independent review initially found that missing witness fields could become a
plausible zero revision. RED1434 reproduces this in Java and TypeScript; required
record annotations and regenerated schema/types resolve it. GREEN1436 executes49
Java cases and16 frontend cases with typecheck passing. Correction reread is clear.

Full1437 executes4,834 cases across739 suites, four existing skips, zero failures
or errors in app-api, app-services, app-observability and ui. Applicable PMD,
Spotless and UI integration compilation pass. Exact task evidence and source
hashes are in the [manifest](public-settings-wire.json).

Public writer/controller and all frontend producers are the next per-item commits;
the transitional raw writer prevents independent shipping. Live/model/installed
and final-head hosted proof remain owed. Hosted09f91917e CI34794274393 failed the
system-access stale-allowlist guard; that residue is being corrected separately.
