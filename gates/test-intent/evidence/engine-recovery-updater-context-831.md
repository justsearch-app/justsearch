# Entry 10 before-state patch context refresh

Base: `0c826d98efd5bcc3f25e976f28af9024a92959be`.
Candidate: merge commit `831dd64d1` plus evidence-only working-tree changes.

`engine-recovery-updater-before.patch` previously anchored its end-of-file insertion after
`#[cfg(test)]`, `#[path = "updater_actor_hold_witness_tests.rs"]`, `mod actor_hold_witness_tests;`
at line 2983. Main's #831 adds the Linux-only `connection_tests` module after that declaration.
The patch now anchors the same insertion after `#[cfg(all(test, target_os = "linux"))]`,
`#[path = "updater_connection_tests.rs"]`, `mod connection_tests;` at line 3517.
Only those three context lines and the hunk's line numbers changed. No signature change was needed.

Every added Rust line, including the evidence-only helper, is byte-identical to the previous patch.
SHA-256 of the ordered added lines (including each `+` and LF):
`a23e6e58eb9e48000e7a8e558f29394b13e50ccd366d1a33efbeeafab778ac01`.
The helper still just exposes main's inline transport-error propagation, digest/size checks and
MZ check. It does not change production callers, mutate the coordinator, introduce recovery behavior,
or seed a failing result. Main's coordinator remains `downloading` after those errors; the unchanged
candidate assertion requires `error`. The repaired candidate continues to set that state and preserve
usable recovery actions. Neither the test nor any product code changed in this follow-up.

`git apply --check --cached` against a temporary index of the exact base exited 0.
The unmodified evidence runner uses a shared candidate clone under TMPDIR, so its temporary
before-worktree metadata does not write into the source repository. The existing cargo wrapper
selects these same two populated target directories, retained across rounds:

- Candidate: `/home/elias/dev/justsearch/.claude/worktrees/engine-recovery/modules/shell/src-tauri/target`
- Before: `/home/elias/.cache/justsearch-private-folders-builder/repair-evidence-cargo`

No fresh target, cargo clean, Gradle run or broad suite is used in this refresh.
The older interrupted setup is recorded separately in the changeset's preceding history.

## Observed execution

Command (inside the temporary shared candidate clone, using the existing cargo-cache selector):

```sh
PATH="$TMPDIR/evidence-merge-831-841/bin:$PATH" node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/engine-recovery-merge-831-841-updater.json \
  --check modules/shell/src-tauri/src/updater.rs#updater::tests::download_failures_and_rejections_return_to_actionable_error \
  --before 0c826d98efd5bcc3f25e976f28af9024a92959be \
  --before-root "$TMPDIR/evidence-merge-831-841" \
  --before-patch gates/test-intent/fixtures/engine-recovery-updater-before.patch
```

Runner exit: 0. Candidate cargo exit: 0; one check executed and passed, none skipped.
Before cargo exit: 101; one check executed and failed by assertion, not compilation or infrastructure:

```text
updater::tests::download_failures_and_rejections_return_to_actionable_error:  ---- updater::tests::download_failures_and_rejections_return_to_actionable_error stdout ----

thread 'updater::tests::download_failures_and_rejections_return_to_actionable_error' (500685) panicked at src/updater.rs:3579:13:
assertion `left == right` failed: transport left recovery actions disabled
  left: "downloading"
 right: "error"
```

Raw runner log: `tmp/builder/evidence-merge-831-841/entry-10-refreshed-context.log`.
The script-produced JSON is copied back byte-for-byte. Historical artifacts remain unchanged.

Final gate: `node scripts/governance/gates/test-intent/cli.mjs --pr 826` exits 1 with only
`test-intent/acceptance-stale`; all thirteen items are covered and no evidence is invalid.
The nonblocking audit warning is unchanged: 1 of 9 audited PRs (11.1%) had third-reviewer tie-breaks.
`git diff --check` passes. Product code/tests and every appended record are unchanged.
Temporary checkouts are removed; both existing cargo targets remain for reuse. No new commit.
