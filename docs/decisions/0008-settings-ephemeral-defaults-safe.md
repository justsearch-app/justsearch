---
title: "ADR-0008: Settings Are Ephemeral, Defaults Are Safe"
type: decision
status: accepted - implementation diverged; owner review open
description: "Settings are versioned and forward-migrated. An unreadable file is preserved as a timestamped .corrupt- sibling, replaced with defaults, and surfaced as a lifecycle condition; a newer schemaVersion refuses to load. Owner review of the original ephemeral-settings decision remains open."
date: 2026-02-10
probes:
  - adr-0008-settings-reset-surfaced
  - adr-0008-reset-reason-code
last_reviewed: 2026-10-20
---

# ADR-0008: Settings Are Ephemeral, Defaults Are Safe

## Status

Historically accepted; owner review remains open. The implementation now versions and
forward-migrates settings, preserving legacy backups and refusing newer schema versions.
The original no-migration premise below no longer describes the code. See the
2026-10-20 amendment for the shipped schema-v5 behavior and fired revisit trigger.

Amended 2026-09-01 (tempdoc 882 item 24): the implementation had drifted to fail-loud-and-exit (register policy `FAIL_LOUD_AND_PRESERVE`, tempdoc 617); restored to preserve-then-default with a visible condition.

## Context

`UiSettingsStore` loads and saves `settings.json` via Jackson. The file has no `schemaVersion` field and no migration hooks. `FAIL_ON_UNKNOWN_PROPERTIES` is disabled, so older versions can read newer settings files without crashing (unknown fields are silently ignored).

All settings changes to date have been additive: new fields get default values when absent. But structural changes (renaming a field, changing a type, splitting a setting into sub-settings) would silently drop user preferences with no recovery path.

Index data has a robust migration mechanism: blue/green schema migration with fingerprinting, durable switch buffer, and rollback safety (see `docs/explanation/11-index-schema-migration.md`). User settings have no equivalent.

The question: should settings survive structural changes across upgrades, or is it acceptable to reset to defaults?

## Decision

**Settings are ephemeral. Defaults are always safe.**

If a user's `settings.json` becomes incompatible with the current version, it is moved aside to a timestamped `.corrupt-` sibling, replaced with defaults, and the reset is surfaced as a lifecycle condition (`settings.reset_from_corrupt`). This is acceptable because:

1. Settings control UI preferences (theme, layout, sort order, view state) — not user content.
2. User content lives in the Lucene index, which has its own migration mechanism.
3. Source file paths (the most valuable user configuration) are stored in the index metadata, not in `settings.json`.
4. Resetting preferences is a minor inconvenience, not data loss.

## Consequences

- Any structural settings change may silently drop user preferences. This is acceptable for the current state where settings control only UI behavior.
- Agents working on settings code should treat fields as **additive-only**: add new fields with sensible defaults, never rename or change the type of an existing field.
- If settings begin to control critical behavior (e.g., exclusion rules, privacy settings, API keys), this decision must be revisited. At that point, implement schema versioning (M1) and a migration hook chain (M2) as described in tempdoc 179 §7.
- Jackson's `FAIL_ON_UNKNOWN_PROPERTIES = false` remains the correct setting — it provides forward compatibility (old settings files work with new code) for free.
- **`IN_MEMORY` operational mode** (tempdoc 368): In eval/ephemeral contexts, the settings store operates in-memory with no persistence. The API declares this via `settingsMode: "in_memory"` in the `GET /api/settings/v2` response. `POST /api/settings/v2` returns 409 with error code `SETTINGS_READ_ONLY` when in this mode. The frontend detects `settingsMode === "in_memory"` and hides the "Reset to Defaults" button, skips network flushes, and applies changes locally for the session only. This is an operational mode (not an upgrade scenario) and does not conflict with the ephemeral-defaults principle — settings are still not migration-protected.

## Alternatives Considered

**"Settings must survive upgrades"** — would require:

- Adding a `schemaVersion` field to `settings.json` (M1, ~30 min)
- Implementing a sequential migration chain: on load, check version, apply migrations 1 to 2 to 3 (M2, ~2-4 hours)
- Total: ~3-4 hours of implementation

This was deferred because the cost-benefit ratio is unfavorable while settings control only UI preferences. The migration infrastructure would be straightforward to add later if needed.

## Amendment 2026-10-20: settings are versioned and migrated

The original Context, Decision and Consequences record the 2026-02 choice. Their
no-versioning, no-migration and UI-preferences-only premises no longer hold.
`UiSettings` now persists `excludePatterns`, `indexBasePath`, `chatEnabled` and
`apiPort` as well as presentation preferences. The exclusion-rule revisit trigger
has fired. This amendment records the implementation; the owner review named in
Status remains open.

`UiSettingsStore.CURRENT_SCHEMA_VERSION` is **5**. `parseOrThrow` accepts legacy
versions 0–4 (0 is the unversioned file), then calls the idempotent `migrate` path.
It maps the old default context length 4096 to 0 = auto for versions below 2,
and the old automatic GPU-layer zero to null for versions below 4. Migration is
applied on load; the next accepted save writes the current envelope.

In writable mode, the original legacy bytes are preserved once per source version
as `settings.vN.bak.json`. An existing regular backup is retained. Backup failure
warns and migration continues; backup success is not a prerequisite for loading.
A newer schema version refuses to load through `StoreFormatVersions.requireReadable`
and is not treated as corruption or silently reset.

The unreadable-file policy remains preserve-then-default: a timestamped
`settings.json.corrupt-<UTC>` sibling keeps the bytes and the reset is surfaced as
`settings.reset_from_corrupt`. If the preservation move fails, the load fails
instead of claiming the file was preserved. `IN_MEMORY` remains an operational
mode without persistence; it does not imply that persisted settings lack migration.

Evidence: `modules/app-services/src/main/java/io/justsearch/app/services/settings/UiSettingsStore.java`
(`CURRENT_SCHEMA_VERSION`, `parseOrThrow`, `preserveLegacySettings`, `migrate`,
`quarantineCorruptFile`) and
`modules/app-api/src/main/java/io/justsearch/app/api/UiSettings.java`.
