---
classification: declared-regression
adr: 0044-public-hosted-ci-fact-lanes
---

Accept root GHSA-VFJ7-8CJW-P6XM at high severity in the advisory identity
baseline. The root lockfile contains fast-glob 3.3.3 -> micromatch 4.0.8 ->
braces 3.0.3, all development dependencies. As of 2026-10-03 the
[GitHub advisory](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm)
lists affected versions <=3.0.3 and no patched version; npm audit reports
fixAvailable: false and the npm registry's latest braces is 3.0.3.

Deeply nested brace patterns can exhaust the recursive AST walkers and crash
the Node process with an uncaught RangeError. Repository consumers are the
documentation validator, anchor audit and tempdoc staleness scanner under
scripts/docs, via fast-glob. Their patterns are fixed repository globs except
the anchor audit's operator-supplied --root, which is interpolated into its
patterns. A hostile pattern can therefore interrupt developer/CI documentation
tooling; this is an accepted availability exposure, not a claim that the
vulnerable package is unreachable. This dependency chain is not part of the
desktop application or the SDK/Zod dev-MCP runtime entry.

Accept only this observed root advisory identity and severity, with the pin
and declaration in the same change as required by the governance kernel.
Upgrade the chain and remove the accepted identity when an upstream patched
release becomes available. No package or lockfile edit can supply a fixed
version today.
