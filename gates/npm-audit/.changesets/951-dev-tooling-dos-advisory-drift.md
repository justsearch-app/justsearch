---
classification: declared-regression
tempdoc: 951
---
Advisory drift, not a dependency change: PR #721 touches no lockfile. Two
high-severity GitHub advisories were published after the last green `main`
run (2026-09-08), both denial-of-service via crafted input in dev-only tooling:

- `runtime-client`: GHSA-2883-xcg3-v3hh, `js-yaml` 4.3.1 pulled only by the
  `orval` client generator (devDependency). Vulnerable range `< 4.3.2`; the root
  workspace already resolves 4.3.2. No production path parses untrusted YAML
  through this tree.
- `ui-web`: GHSA-7w5x-hrqm-74c2, `smol-toml` 1.6.1 pulled only by `knip`
  (devDependency). GitHub lists no patched version yet. `knip` runs against the
  repository's own TOML/config files during lint, never on user input, and
  ships nowhere.

Accepted as declared regressions until a dependabot or manual bump of `orval`
and `knip` lands; the kernel auto-rebalances on `severity-decrease` at that
point. Both identities are pinned in
`scripts/ci/github-advisory-baseline.v1.json` in this same change.
