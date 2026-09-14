# C2-6 installer model and pack producers — 2026-09-14

Installer chat selection, each ONNX acquisition stage, and models-pack import now
capture a candidate with its full witness and accept through SettingsService.
Only successful COMPLETE permits inference or pack completion. Runtime application
samples published effective model/context/GPU, preserving operator precedence.
Unchanged ONNX paths accept no row; pending/failed/skipped/absent packages remain
outside the candidate. A later stage preserves prior committed paths.

All five ONNX model configuration readers already consume ConfigStore. The two
remaining runtime-status path readers now use a captured resolved snapshot while
retaining independent session observations. Five redundant model-path property
writes are removed. ORT native discovery keeps its real direct fallback but no
longer loads settings or republishes configuration; its actual reader and snapshot
identity are covered. No replacement configuration writer or state was added.

Independent production review is clear. Review requested path-specific unresolved
result tests, now present for the shared installer commit helper and pack pipeline.
The adjacent HTTP fixture was migrated to the same SQLite/settings owner; review
caught its missing RECONFIGURE kind before acceptance. Focused1429 executes10
passing cases after correction. Existing HTTP response expectations remain intact,
with durable witness assertions added.

Final1430 represents 4,863 cases across 716 suites,
4 existing skips, 0 failures and 0 errors.
The manifest distinguishes executed from reusable tasks and records exact commands,
source hashes, accessible artifacts and retention. Applicable PMD/Spotless and UI
integration compilation pass. Register guard, execution-surface and operation-surface
gates pass; canonical links and generated runtime-config matrix verify.

Adverse evidence is retained:1424 failed Path-to-String compilation at the runtime
port (conversion corrected);1425 passed142 tests but failed one redundant List
qualifier in PMD (corrected);1426 passed with those test results reused;1427 full
had two old uncomposed HTTP fixture failures;1428 full had eight fixture startup
failures from the omitted second settings kind. These were fixture composition
repairs, without changing expected product responses or weakening gates.

[Exact evidence](installer-model-producers.json). This is local implementation/proof,
not C2 completion. Public settings ingress/witness consumers, all-writer retirement,
and live/model/installed/final-head hosted acceptance remain required before the
later stage cuts and stage-F merge. Activation CI34792718785 was superseded/cancelled
by the component-choice push; that cancellation is not a passing hosted proof.
