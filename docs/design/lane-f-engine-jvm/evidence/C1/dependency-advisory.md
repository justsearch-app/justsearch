# Newly published Knip TOML parser advisory

CI34387563666 at5c2f0ef3b passes every job except Public claims. Its npm-audit gate identifies
GHSA-7w5x-hrqm-74c2, published2026-09-09T18:07:11Z during this verification campaign.
The high-severity malformed-TOML denial of service affects smol-toml<=1.7.0; the first patched
version is1.7.1. [Upstream advisory](https://github.com/squirrelchat/smol-toml/security/advisories/GHSA-7w5x-hrqm-74c2).
Raw: `tmp/c1-hosted-public-claims-426.txt`, `tmp/c1-high-advisory-detail-429.json`,
`tmp/c1-github-advisory-report-428.json`, `tmp/c1-advisory-producer-428.txt`.

The frontend lockfile carried1.6.1 through the development-only Knip6.20.0 dependency (^1.6.1).
The root lockfile already pins patched1.7.2. The correction copies that existing package entry
into the frontend lockfile and independently verifies its registry tarball/integrity. It changes
one transitive package entry, adds no override/direct dependency and leaves the advisory baseline
untouched. The package-manager update initially proposed1.8.0 and removed unrelated optional
entries; those unrelated changes were discarded in favor of the verified existing1.7.2 pin.

## Verification

Windows11 / Node24.12 / npm11.6 / Temurin25.0.2, base5c2f0ef3b plus this lockfile change:
- Registry431 matches the existing1.7.2 integrity; clean install436 and typecheck437 pass.
- Knip438 regenerates its report. Current advisory producer432 removes the one high identity;
  the unchanged gate passes. Remaining reported identities are low/moderate under existing policy.
- Root mistakenly ran unit439 alongside build440, whose installWebDependencies task reinstalled
  the same node_modules tree. Windows EPERM and missing Vitest modules invalidate those runs.
  The owned invalid Vitest process was stopped after exact command-path/PID verification.
- Sequential build441 restores dependencies and passes in11s. Unit442 passes6474/6474 cases
  across483 files in32.07s. All stderr is captured in442.
- Gates444 pass all five lockfile edge graphs, npm-audit, dead-code, module-deps and notices.
  npm ls verifies Knip resolves smol-toml1.7.2. No runtime Engine Java or model input changes.

Raw: `tmp/c1-smol-toml-update-430.txt`, `tmp/c1-smol-toml-registry-431.json`,
`tmp/c1-advisory-restored-432.txt`, `tmp/c1-advisory-restored-432.sarif`,
`tmp/c1-github-advisory-report-restored-432.json`, `tmp/c1-smol-toml-install-436.txt`,
`tmp/c1-smol-toml-typecheck-437.txt`, `tmp/c1-smol-toml-knip-438.txt`,
`tmp/c1-smol-toml-unit-439.txt`, `tmp/c1-smol-toml-build-440.txt`,
`tmp/c1-smol-toml-build-restored-441.txt`, `tmp/c1-smol-toml-unit-restored-442.txt`,
`tmp/c1-smol-toml-gates-444.txt`, `tmp/c1-smol-toml-gates-444.sarif`.

The full418 Engine/stress result is unchanged-source evidence; it predates this development
lockfile update. A fresh current hosted run is required. The hosted support-policy check also
emits the existing Gradle9.6.1 successor9.7.1 warning; it is not a newly green support assertion.
