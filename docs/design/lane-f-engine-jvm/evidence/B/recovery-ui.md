# Recovery UI before API binding (2026-09-08)

Reviewed subset above `74c0bc761`, implemented by the orchestrator and independently
reviewed read-only by `review_b14_java`. B13's replacement ownership and installer
proof, and B14's remaining host liveness/stability work, are still open.

The desktop mounts recovery and subscribes to host state before API discovery.
The host exposes its current in-memory supervisor record, covering exhaustion
before subscription; a later snapshot cannot overwrite an event already received.
Native development boot no longer mistakes the browser proxy for an Engine binding.
Recovery uses the existing update commands and presents returned or thrown errors
with a retry path. The component is reserved against authorable presentations.
Catalog requests start explicitly after a real API binding; local strategy
registration remains synchronous and the existing backend-ready retry survives.

Final verification: 6,462 frontend tests in 482 files, zero failures (38.70 s),
typecheck, production build, all 27 UI gates, and 59 Rust library tests passed.
Rust unit compilation used the existing CI headless-resource placeholder; it is
not a packaged payload test. The UI step-index tests passed 12 cases. Canonical
docs/index/skill synchronization and link checks passed. Existing build warnings
and test-environment teardown diagnostics remain; exit status and test totals
are the verification claim.

Independent review found and closed two defects: authorable layouts could mount
the host recovery controls, and the real i18n import started discovery too early.
The reservation tests failed before the exclusion and passed after it. The real
entry regression now loads i18n and observes mount plus subscription at the first
endpoint call: both were false before the fix and true afterward. Removing the
event-before-snapshot guard also failed its race regression; restoration passed.

The `jseval ui-shot engine-recovery --fixtures` capture reloads the production
entry with a deterministic native boundary and no API port. Visual inspection at
1280×720 found readable controls, no overflow and zero axe violations. Its one
console error is the expected unresolved-API message. This proves the frontend
path, not installed Tauri update execution. The registered preview was stopped
through the identity-checked repository sweep; no backend stack was started.

Raw artifacts remain ignored in the B14 worktree. SHA-256 inventory:

| artifact | SHA-256 |
|---|---|
| `tmp/recovery-ui-final-full.txt` | `24559859df75421bd8b21ebacc1704ff372aab246b2dc84376b8818277e34521` |
| `tmp/recovery-ui-final-typecheck.txt` | `596373b383c6e3421b378952b2fbf2d04b8a1bcef36fd19dc1812d88a565e100` |
| `tmp/recovery-ui-final-gates.txt` | `0e359b93b2bd28c8e474dff497eb51a42589eb7207570ef8138030dc76fb9b6b` |
| `tmp/recovery-ui-final-build.txt` | `d436fc6a0cf869bbd46c8e56e382395851472a27681519d67fb678b138294a31` |
| `tmp/recovery-ui-rust-restored.txt` | `98c871267400b10c893e947c5ee75dc3eba0c5777ed162289595f535942c6c11` |
| `tmp/recovery-ui-i18n-negative.txt` | `02c7964009218bfda8dd18333485df6d0423e601d761f2dcdc8d793d924c7722` |
| `tmp/recovery-ui-i18n-restored.txt` | `c7494aae04dae89d6535d8e8d28eaccdd23442d775b601827a82ee041c828295` |
| `tmp/recovery-ui-reservation-negative.txt` | `e326f3991b696ced74d573cff7af149711551daf0aef514a480478f3a80b23a6` |
| `tmp/recovery-ui-reservation-restored-final.txt` | `1e720b58d0f2a821b15eb58b6f6d41bd04a2bedb5dced9f7f85451d727b62742` |
| `tmp/recovery-ui-snapshot-negative.txt` | `a221884c0fc518c75316a94acd464fc5186dd966caa546185db0ad901d0c516a` |
| `tmp/recovery-ui-snapshot-restored.txt` | `02677902042aa2dc78069653ca5f406c2b1ac3e198999601dbf2c9b334ea29bb` |
| `tmp/recovery-ui-harness-tests.txt` | `bc44e3590a0cdb167058d6fe655bae0b14ee56df74287204de8ec15363b13683` |
| `tmp/ui-shot/engine-recovery.png` | `8807720b9c4852d4df839a76534d89d224a6e584037e0b589b0f39cb8dc7ba9b` |
| `tmp/ui-shot/engine-recovery.measure.json` | `bc2af224a9b0f71ab7f6409d0a985900221dd175bd0b5ffd309e3ec84a0cb59d` |
