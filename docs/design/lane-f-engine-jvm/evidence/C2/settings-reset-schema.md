# C2-6 fixed reset preparation schema

2026-09-13 at ae46f6919 plus the helper, tests, catalog binding and consumer registration in
[the manifest](settings-reset-schema-verification.json). SettingsResetPreparation defines
settings-reset-v1 metadata with exactly one full readable-history witness or canonical
quarantine fingerprint. It preserves the reset operation's empty-object public input contract.
Only the runner's actual accepted envelope bound to SETTINGS_APPLY/core.reset-settings/invoke
can decode. Schema/content, exact JSON field types and optional armed SQL marker must agree.
Duplicate JSON fields and trailing roots are rejected at both parser boundaries. Jackson numeric
coercion or missing-field defaults cannot fabricate a zero witness. The catalog consumes the
helper's single operation ID; the helper does not initialize the catalog. No file,
SQL or Health writes occur here; decoding never authorizes execution by itself.

The helper is a declared consumer of the existing operation record, not another state writer.
Register1318 deliberately exposed its missing declaration; after adding the consumer,
Register1319 passes operation-surface/execution-surface/register-guard-resolution with zero
findings. The rule and guard remain enforced.

## Independent correction and final verification

The independent reviewer found duplicate-key collapse and ignored trailing JSON could change
selected authority. Strict duplicate detection and trailing-token refusal now guard both
public arguments and persisted payload. A narrow JacksonException boundary keeps malformed
public input cause-free and bounded. Correction review is clear against the four source hashes
in 1326; no full reset execution is claimed.

- 1322 executes45 cases, one failure: factory parsing leaked Jackson's exception type. The
  implementation now returns the existing validation error; assertions were preserved.
- 1323 executes45 cases/6 suites with PMD/format green. Negative1324 removes the flags and
  exposes duplicate collapse, but Jackson3's default trailing guard stays enabled. Its one
  intended failure is limited proof. Restored1325 reuses45 passing cases.
- Final1326 executes47 cases/6 suites, zero failures/errors/skips, PMD/format green. The three
  malformed payload cases now report individually, beside trailing public input.
- Negative1327 explicitly disables both guards:28 cases/5 suites, exactly four intended
  assertion failures (duplicate root, duplicate witness, trailing payload, trailing public
  input), zero errors/skips. Restore is byte-for-byte.
- Restored1328 passes47 cases FROM-CACHE, PMD/format green; all four source hashes match1326.
  Register1328 passes all three gates with zero findings.

Commands1323/1326 match1319 below;1325/1328 omit spotlessApply. Negative1324/1327 select only
SettingsResetPreparationTest. Logs, XML and manifests retain each failed and restored attempt.

## Earlier verification

- Schema1319 executes43 cases/6 suites, zero failures/errors/skips, main/test PMD and format
  green. Tests build the actual existing prepared envelope, covering normal/recovery decoding,
  wrong kind/ref/mode/schema/marker, malformed/coerced/missing fields, flags in public arguments,
  invalid fingerprints and overflow. Generic key/nonce/content bindings remain covered by
  PreparedInvocationCodecTest and its prior negative proof.
- Negative1320 disables fixed kind/ref/invoke binding and strict witness-field validation.
  Twenty-four cases execute; exactly five fail: wrong kind, wrong operation, undo mode,
  fractional revision and missing witness key. Zero errors/skips. This proves refusals are
  not all accidental envelope-binding failures. Source restores byte-for-byte to1319.
- Restored1321 passes43 cases FROM-CACHE, PMD/format green; all three source/registration hashes
  match1319. This is restored-input reuse, not a new execution.

Command1319:

```powershell
./gradlew.bat :modules:app-services:spotlessApply :modules:app-services:test --tests '*SettingsResetPreparationTest' --tests '*PreparedInvocationCodecTest' :modules:app-services:spotlessCheck :modules:app-services:pmdMain :modules:app-services:pmdTest --console=plain
```

1321 omits spotlessApply. Negative1320 selects only SettingsResetPreparationTest through
`:modules:app-services:test --tests`, with `--console=plain`.

Artifacts remain accessible under this lane worktree's tmp directory (logs/SARIF/XML/zips,
counts and source hashes), retained through acceptance plus30 days and exported before removing
the worktree. No new full, live, installed or hosted reset proof is claimed. Reservation,
file commitment/reconciliation, successful-recovery restart, producer and Health composition
remain required C2-6 work. A fresh origin fetch found no main commits beyond3a3e8e489.

Hosted checkpoint: [CI34778431996](https://github.com/justsearch-app/justsearch/actions/runs/34778431996)
passed all13 jobs at ae46f6919, including the previously failing public-claims and app-ui lanes;
CLA34778430639 also passed. The jobs snapshot is retained in the manifest. This checkpoint
predates the helper and is not hosted proof of this new diff or installed-v5 reset recovery.
