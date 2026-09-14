# Frontend settings consumer registration (2026-09-14)

Hosted [CI34798352215](https://github.com/justsearch-app/justsearch/actions/runs/34798352215)
at531128d11 fails Public claims / Hermetic kernel gates: the new settingsAttempt.ts
parse boundary imports the generated settings-v2 projection without declaring its
consumer in governance/contract-surfaces.v1.json. Local1487 reproduces the same
single contract-projection/undeclared-consumer finding at501fcaa63. Register that
actual consumer alongside api/domains/settings.ts; the enforcement remains intact.

Local1488 runs `node scripts/governance/run.mjs --gate contract-projection --mode gate
--out tmp/frontend-contract-projection1488.sarif` with zero failures/findings. The
existing import-detector tests also pass. This is a registry-only correction;
frontend source and the final1485 proof remain unchanged. Hosted proof of this
correction remains required after push.

Hosted correction proof: [CI34800723187](https://github.com/justsearch-app/justsearch/actions/runs/34800723187)
passes all13 jobs at e135acb0b, including Public claims, app-ui and Windows-native
tests; CLA34800721640 also passes. Raw job readback is tmp/hosted-contract1497.json.
This proves the registered frontend checkpoint; later writer/harness changes still
require their own hosted result.

Accessible raw evidence in the active worktree: tmp/hosted-frontend-helper1486.json,
tmp/hosted-frontend-helper1486-failed.txt, tmp/frontend-contract-projection1487.sarif,
tmp/frontend-contract-projection1488.txt/.sarif and tmp/frontend-contract-imports1488.txt.
Retain through lane acceptance plus30 days and export before worktree release.
