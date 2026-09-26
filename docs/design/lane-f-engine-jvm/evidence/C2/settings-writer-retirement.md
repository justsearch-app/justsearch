# Settings physical writer retirement (2026-09-14)

C2-6 item4 removes UiSettingsStore.save after all production producers migrated to
accepted attempts. Preparation, strict replacement and recovery-clear notification
remain the commit coordinator's primitives. SettingsWriterArchitectureTest rejects
other callers, including method references, and rejects runtime config publication.
Generic ConfigStore primitives retain their implementation self-calls. Only the exact
HeadlessApp.resolveConfig -> rebuildAfterPostBuildWrites -> ConfigStoreRebuilder.rebuild
boot path may publish discovery before ORT initialization. It writes no settings file.
The owning section0 decision and C2-6 plan record why no new boot mutation is needed.

Fixtures seed explicit prepared replacements. Tests for the removed raw-save refusal
now prove API absence; corruption inspection and accepted-owner recovery assertions
remain. The single-inspection GET test forbids prepare, replace and notification.

Final1500 represents4,187 Java cases across641 suites, four existing skips and zero
failures/errors. App-services executes2,860 cases; UI and launcher reuse their green
1498 results with unchanged sources. PMD, formatting and UI integration compilation
pass. The adjacent manifest preserves exact commands, source hashes, task reuse and
accessible output/XML paths. Documentation regeneration, canonical links157 and the
runtime-config matrix also pass. This does not claim live/model/installed proof.

Independent review found the missing boot-entry negative control. The test now calls
the actual package-private boot wrapper from a different class; it lives in the UI
test source set, where that type is available at compile time. Negative1496 removes
only the boot-entry restriction: the production rule still passes, but the specific
unauthorized-entry assertion fails. Restoring the rule passes all four architecture
cases. This establishes that the restriction actually fires.

Preserved corrections:1490 found stale Javadoc links to save;1491 found the fixture's
checked IOException and an imported type's redundant qualifier. Root initially fixed
the wrong qualifier forms, so1492/1494 retained the same PMD finding until the actual
AtomicReference type was corrected.1494 also exposed the launcher's runtime-only UI
dependency, prompting the test move rather than a new module dependency.1498 caught
an incorrect new assertion that a restarted store owned the earlier instance's
in-memory quarantine notification. It now asserts that field is empty and that the
original quarantined bytes survive; restarted inspection still refuses the sibling.

Focused1495's125 represented cases were not complete settings-package proof: its
app-services selection contained71 other cases and reused them. The integrated
1498/1500 runs execute the missing settings cases. Do not infer coverage from a
green command or a broad-looking filter; use the preserved XML inventory.

Final independent review verified every source hash, the1500 XML counts, the corrected
quarantine assertions and the1496 negative control; no remaining defect was found.

Raw artifacts live in the active worktree tmp as listed in the manifest. Retain through
lane acceptance plus30 days and export before release of the worktree. Hosted proof
of this item and remaining C2 acceptance are still required; merge remains at F.
