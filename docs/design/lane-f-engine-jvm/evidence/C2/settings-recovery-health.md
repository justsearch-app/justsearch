# C2-6 settings recovery Health projection

2026-09-14. HeadlessApp subscribes to the fixed owner's sticky recovery future after
Health composition. The existing ConditionStore receives one ERROR condition,
settings.recovery_required, with four typed reasons: SettingsWitnessUnreadable,
SettingsWitnessContradictory, MultipleSettingsCommits and SettingsPersistenceDisabled.
Messages expose no paths or payloads. Ordinary corruption-notice clearing uses another
exact condition key, so unresolved recovery remains visible until successor boot.
This adds no durable marker, second Health writer or lifecycle observer registry.

Independent review is clear on unchanged source. Focused1378 executes235 cases/49 suites,
no skips/failures/errors, with PMD/format/UI integration compile passing. Full build1379
passes333 tasks (106 executed,2 cached); its integration coverage is30 cases/10 suites,
10 existing skips. Readiness vocabulary and operations/engine-port register gates pass.

Live1384 runs the installed Gradle distribution in an isolated, writable, production-token
profile with inference intentionally disabled in the fixture. The fixture uses the real
store APIs to seed one armed reset at expected revision0 beside live settings revision2
with another key. The Engine serves HTTP health200; its initial negotiated Health SSE
snapshot contains exactly the expected SettingsWitnessContradictory ERROR. Afterward,
the row remains RUNNING with the SQL expected marker0 (historically named
accepted_settings_revision), and live settings remain revision2 with another key.
Authenticated lifecycle shutdown returns202. Subsequent dev-stop finds the API already
unavailable and cleans the owned processes/ports; full ordered shutdown completion is
not claimed. Final quick_health reports no active run.

The app-attached MCP server was launched from main and still checked for the retired
Worker distribution. A revision-local client used the actual .codex/config.toml launcher
from this worktree, preserving shared ownership. Preflight then passes with only headDist.
Retain the client for the entire live capture: first1381 reached HTTP-ready, then the
later call found its process gone; no product crash cause was established.1382 kept the
client but its early Health fetch failed the success predicate (status was not retained).
1383 waited for worker readiness and passed HTTP health, but omitted the SSE Accept header;
Javalin correctly did not upgrade, as SlowRequestStreamExemptionTest documents.1384 uses
that existing SSE contract and passes. These failed harness attempts remain explicit.

[Exact proof commands, source hashes, live capture and retained artifacts](settings-recovery-health.json).
This proves the live Health projection only. Other settings writers, atomic snapshot/witness
wire, named public A/B/C retry and installed successor-bootstrap acceptance remain required.

The initial staged scan flagged the generated fixture operation identifier as a generic
API key. The public summary omits that unnecessary identifier; the private capture and
its hash remain available. No credential or secret-scanner exemption was added.
