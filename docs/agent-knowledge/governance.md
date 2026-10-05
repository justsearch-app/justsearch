---
description: "TRIGGER when: editing a gate baseline (scripts/ci/github-advisory-baseline.v1.json, gates/<id>/baseline.txt), authoring changesets under gates/<id>/.changesets/, running the discipline-gate kernel, or seeing a SARIF ruleId from npm-audit / consumer-drift / ssot-catalog-sync / config-surface and unsure what to do. Loads the kernel's protocol + classification grammar + CLI subcommands."
user-invocable: true
---

# Discipline-gate kernel (tempdoc 530)

The kernel that gates ratchet-style hygiene metrics across the repo.
**`governance/registry.v1.json` is the authority — 29 gates as of 2026-09-05; read it
rather than trusting any list in prose.** The frequently-hit ones: `npm-audit`,
`consumer-drift` (tempdoc 531 — substrate consumer-floor enforcement),
`ssot-catalog-sync` (root↔classpath catalog mirror), `config-surface` (dead settings
+ surface ratchet), and the count-ratchets `dead-code`, `module-deps`,
`atom-fork-ratchet`.

(Retired, so do not go looking for them: `class-size`, `clone`, `ui-bundle`,
`exception-count` for go-public, tempdoc 634; the human-audit gates `independent-review`
and `ux-audit-closure`, tempdoc 530 §Remediation; and — tempdoc 930 chunk F — `ts-any`,
`todo-fixme`, `dead-code-jvm`, `style-literal-ratchet`, `test-to-code` and
`prose-tier-register`, whose properties moved to ESLint, PMD, ArchUnit's
`FreezingArchRule` and a plain `scripts/ci` lint respectively. That table lives in
`docs/reference/contributing/discipline-gate-kernel.md` §"Retired to commodity tools".)

## Quickstart

```bash
# What would fail if I committed right now?
node scripts/governance/run.mjs --mode warn

# Just one gate
node scripts/governance/run.mjs --gate npm-audit --mode gate

# What gates does my diff affect?
node scripts/governance/run.mjs --preflight HEAD~1

# I see ruleId X — what does it mean + how do I fix it?
node scripts/governance/run.mjs --explain npm-audit/silent-baseline-shift

# Auto-author stub changesets for predicted-fail gates
node scripts/governance/run.mjs --suggest-changeset

# Apply auto-shrink rebalance writes (only-shrinks)
node scripts/governance/run.mjs --gate npm-audit --rebalance
```

## The changeset protocol

When a gate fails with a `*/silent-*` rule, the author owes a *classified
changeset* — a markdown file under `gates/<gate-id>/.changesets/<id>.md`
declaring why the failure-shaped change is acceptable.

```markdown
---
classification: declared-regression   # see allowed values below
tempdoc: 524                          # or adr: 0026 — one of these is required
---
A new high-severity advisory landed in a transitive dep with no patched
version yet; tracked upstream, accepted until the fix ships.
```

Without a `tempdoc:` or `adr:` field, the changeset-loader throws (Pass-5
discipline). The body is free-form; explain context not obvious from git.

**A growth-licensing changeset must advance the pin in the SAME commit** (tempdoc 918).
The changeset licenses the pin *edit*; it does not license a row measuring above an
unchanged pin. Write the changeset AND set the row's new value in the gate's baseline
file, together — otherwise the gate fails with `<gate>/declared-growth-without-repin`
(the finding names the pin file, the row, the measured value and the exact line to
write). Predictable evasion: "the changeset covers it, the pin can move later." It
cannot: discovery is PR-scoped, so at squash-merge the changeset leaves the diff and
the next push to `main` fails `silent-growth` on a row nobody touched — three observed
incidents (#517→854, #595→885, #614→#613/#615). This applies to `declared-growth`,
`declared-regression`, `merge-import`, `emergency-override`, `lockfile-import`,
`test-wired-infra` and `strength-regression`; the shrink/renormalization words
(`unused-export-shrink`, `unit-renormalization`, `monotonic-shrink`, `dep-shrink`,
`severity-decrease`, …) are unaffected.

## Allowed classifications per gate

Vocabularies are **per gate** — using another gate's word fails the loader.

| Gate | Classifications |
|---|---|
| `npm-audit` | `declared-regression` · `lockfile-import` · `emergency-override` · `severity-decrease` |
| `runtime-state` | `new-rule-registered` · `tier-change` · `rule-retired` |
| `consumer-drift` | `slot-retraction` · `grace-extension` · `emergency-override` (frontmatter needs a `slot:` field naming the affected slot) |
| `ssot-catalog-sync` | `intentional-divergence` · `mirror-retirement` · `emergency-override` (frontmatter needs a `mirror:` field naming the affected mirror) |
| `test-efficacy` | `strength-regression` · `seam-retraction` · `emergency-override` |
| `register-guard-resolution` | `guard-downgrade` |
| `tempdoc-wiring` | `emergency-override` |
| **count-ratchets** — `atom-fork-ratchet` · `dead-code` · `module-deps` · `adr-coverage` · `config-surface` | the shared growth family: `declared-growth` · `declared-regression` · `merge-import` · `emergency-override` |

Read the gate's own README for nuances — `gates/<id>/.changesets/README.md` — and note that
not every gate ships one; when it doesn't, `run.mjs --explain <ruleId>` is the fallback.

## What the kernel catches (silent-bypass closures)

The kernel closes the silent escape-hatch classes the Pass-3+5 work
documented:

1. **Silent baseline-shifts** — relaxing the npm-audit baseline without a
   changeset → `npm-audit/silent-baseline-shift`.
2. **Silent pin raises** — advancing a gate's baseline without a changeset →
   `<gate>/silent-baseline-shift` (the gates carrying that block are listed in
   `discipline-gate-kernel.md`).
3. **Growth licensed but not re-pinned** — a changeset without the matching pin
   advance in the same diff → `<gate>/declared-growth-without-repin`.

If you're authoring a prose rule, still anchor it with `<!-- rule:<slug> -->` —
the anchors are read by hooks and by `check-always-loaded-budget.mjs`. There is no
longer a tier register to add a row to (tempdoc 930 chunk F).

## See also

- `docs/tempdocs/530-class-size-ratchet-automation.md` — the design tempdoc (historical).
- `docs/reference/contributing/discipline-gate-kernel.md` — substrate reference.
- `governance/registry.v1.json` — gate registry (read-only at runtime).
