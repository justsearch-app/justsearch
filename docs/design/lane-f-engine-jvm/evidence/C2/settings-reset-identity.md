# C2 accepted reset identity after SQLite canonicalization

2026-09-13: at001f7e20f plus the four source files in
[the manifest](settings-reset-identity-verification.json).

The first real SQLite reset-owner test exposed that persisted identity member order differs
from the original envelope. Raw OperationDescriptor equality rejected a valid accepted row.
OperationDescriptor.hasSameIdentity preserves exact kind/ref and compares identity through the
existing canonical argument digest. This uses SQLite's recursive map-order equivalence without
rewriting persisted envelopes or introducing another JSON representation. Codec reconstruction
still requires invoke/undo and the exact arguments digest; the fixed reset requires its own
kind/ref/invoke binding. Extra fields, changed values and types remain distinct.

The independent reviewer checked SQLite canonicalIdentity against the existing digest and found
no actionable issue. This is a mechanism correction within the decided canonical keyed-retry
contract; no stage or merge placement changes.

## Verification

- Owner1329 ran184 cases/24 suites and failed9 new integration cases at the real accepted-row
  boundary. It also exposed two harness issues corrected before the next run: the actual SQL
  marker column is accepted_settings_revision, and runner precommit failures propagate after
  terminalizing FAILED. Existing assertions remain enforced. Its UI selector was corrected to
  the existing SettingsController and SettingsV2 test names before UI execution.
- Owner1330 executes192 cases/26 suites across runner/settings/UI with zero failures/errors/skips.
  All source inputs, including the separate uncommitted reset-owner item, are hashed in its
  source manifest. This is not clean-checkout or hosted proof of that owner item.
- Negative1331 restores raw equality in the codec expected-row check and fixed reset check.
  Seven cases/5 suites execute; the real SQLite roundtrip alone fails at bounded decoding.
  It first proves raw JSON member order differs. Both sources restore byte-for-byte to1330.
- Focused1332 executes48 cases/6 suites, zero failures/errors/skips. PMD and formatting pass for
  app-api, app-observability, app-services and UI affected source sets. The full ambient source
  manifest is retained. Independent source review is clear against the four correction hashes.

Exact commands are retained in each counts JSON. Logs/XML/zips/source hashes are accessible
under this lane worktree's tmp directory through acceptance plus30 days, exported before the
worktree is released. No full/live/installed/hosted reset completion is claimed. Owner reset
integration, every producer, public wire, Health and installed-v5 proof remain required C2-6 work.
