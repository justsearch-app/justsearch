---
classification: declared-regression
adr: 0044-public-hosted-ci-fact-lanes
---

Accept high-severity GHSA-FC5Q-39XC-VJPF for `picomatch` 2.3.2 and 4.0.7
in the root lockfile and for 4.0.7 in both `ui-web` and `runtime-client`.
It is a development-only dependency, not shipped.

GitHub lists no patched version, and npm's latest release, 4.0.7, is affected.
The advisory identity and severity are pinned for these three lockfiles in
`scripts/ci/github-advisory-baseline.v1.json`.
