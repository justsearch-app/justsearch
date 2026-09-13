# C2-6 frozen quarantine evidence reader

2026-09-13, base8cc38ff18 plus the final two sources in [the manifest](settings-quarantine-verification.json).
UiSettingsStore can now read a versioned SHA-256 identity of the complete preserved corruption
sibling set. Sorted UTF-8 length-prefixed names and fixed-width content hashes bind every name
and byte with bounded streaming memory. It requires writable persistence, a proven absent live
file and a nonempty regular non-symlink set. Read failures, changing attributes, set membership
or live-file appearance refuse proof. This read does not authorize reset or clear Health.

The [owning plan](C2-6-plan.md#2026-09-13-recovery-evidence-reader-cut) records the simpler
ownership comparison and next codec/owner seam. No new writer, journal or durable marker is
introduced. The owner still must bind accepted server preparation and revalidate before arming.

## Review correction

Independent review found Windows Path equality could hide a case-only rename at the final
membership check, although the digest hashes exact name bytes. The final validation compares
exact filename strings. A focused production-validation regression supplies snapshots differing
only by case; it does not claim a timed external-filesystem race. The reviewer also required a
stable encoding vector: an independently computed155-byte Python hashlib/struct input pins
`d537ca8993172d84d6299f920a7f90d58f456f0a37f5d274eb9c43939bfe1df3` for two named files.
This protects persisted v1 preparation identity across code changes. These are required fixes,
not waived findings.

## Local proof

- Final1304 executes71 cases/15 suites, zero failures/errors/skips, with main/test PMD and
  format checks passing. Five tests include the exact-name validation and fixed digest vector.
- Negative1305 restores Path equality and changes the persisted domain to v2. Eleven cases
  execute; exactly the case-only validation and fixed-vector assertions fail, zero errors/skips.
  The source is restored byte-for-byte to1304 before final1306.
- Restored1306 passes71 cases FROM-CACHE, no failures/errors/skips, format/PMD green. Its
  source hashes exactly match1304. This is restored-input reuse, not another execution.

Earlier proof, preserved rather than overwritten:

- Quarantine1301 executes70 cases/15 suites, zero failures/errors/skips; affected format and
  main/test PMD pass. Four new tests cover stable identity across restart and enumeration order,
  ignored unrelated files, same-length changed bytes, rename/add/remove, empty/non-regular
  evidence, future/live-file refusal, nonpersistent storage and actual repeated quarantine.
- Negative1302 replaces each content digest with a constant while preserving names/count.
  Seven cases execute; the intended content assertion alone fails, zero errors/skips. Production
  is restored byte-for-byte. This rejects the insufficient names-only proof mechanism.
- Restored1303 passes70 cases/15 suites FROM-CACHE, format/PMD green. Its two source hashes
  equal1301. It is restoration/input proof, not another execution.

1301 command:

```powershell
./gradlew.bat :modules:app-services:spotlessApply :modules:app-services:test --tests '*UiSettingsStore*Test' :modules:app-services:spotlessCheck :modules:app-services:pmdMain :modules:app-services:pmdTest --console=plain
```

1304 uses the same command as1301;1303/1306 omit spotlessApply. Negative1302 runs `:modules:app-services:test --tests
'*UiSettingsStoreRecoveryEvidenceTest.bindsEveryNameAndByteAcrossRestartWithoutDependingOnEnumerationOrder'
--console=plain`.

Negative1305 runs the entire UiSettingsStoreRecoveryEvidenceTest through the same test task.

Independent Sol/high review is clear after the exact-name and golden-vector corrections.

Evidence is accessible under this worktree's tmp directory, with logs, XML/zips, counts and
source bindings in the manifest. Retain through lane acceptance plus30 days and export before
removing the worktree. This Windows pass does not exercise an ACL-denied or symlink fixture;
those refusals are source-reviewed. No atomic snapshot of hostile external filesystem changes,
reset/reconciliation runtime, live, installed or full hosted proof is claimed by this reader cut.
