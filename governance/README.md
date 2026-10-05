# `governance/` — the discipline-gate kernel

Machine-readable registers and manifests for JustSearch's **discipline gates**: CI checks that
enforce structural rules (class size, module dependency boundaries, contract/wire integrity, the
prose-rule register, and more). The gate **enforcer code** lives in `scripts/governance/gates/`;
the **wiring** is `governance/registry.v1.json` (the gate registry).

These run in CI to keep the codebase disciplined. **You don't need to read this to contribute** —
it is published for transparency and documents *how* we maintain rigor (see
[`/MAINTAINING.md`](../MAINTAINING.md)).
