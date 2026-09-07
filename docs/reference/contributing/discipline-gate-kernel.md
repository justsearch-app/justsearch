---
title: Discipline-Gate Kernel
type: reference
status: stable
created: 2026-05-20
description: "Substrate for ratchet-style hygiene gates (npm-advisory identities, consumer-drift, ssot-catalog-sync, test-efficacy, …). Tempdoc 530. The size/count ratchets (class-size, clone, ui-bundle, exception-count) were removed for go-public — tempdoc 634; five more were replaced by commodity tools — tempdoc 930 chunk F."
---

# Discipline-Gate Kernel

A shared substrate for ratchet-style hygiene gates. Factored from the
`scripts/governance/gates/wire/` (formerly `scripts/contract-governance/`, retired Pass-7 Phase F) kernel (slice 3a-1-8f) per tempdoc 530
after a confidence-calibration pass found that:

- The contract-governance kernel's `truth-table`, `changeset-parser`, and
  SARIF `ruleIdToShortDesc` were saturated with protobuf-evolution semantics
  — the kernel was *not* Category-agnostic.
- The codebase had four bespoke ratchet implementations (`CheckClassSizeTask`,
  `check-npm-audit-ratchet`, `check-ui-bundle-budget`, contract-governance
  itself), each with its own baseline format and escape hatch.
- The class-size ratchet specifically had a silent escape-hatch: any commit
  could bump a pin without justification (the AppFacadeBootstrap audit found
  2 silent / 5 justified bumps across 48h).

## What lives where

```text
scripts/governance/
├── lib/
│   ├── git-utils.mjs              shallow-clone detect, baseline-ref resolution, PR-scope diff
│   ├── sarif-emitter.mjs          SARIF v2.1.0 minimal subset, externalized ruleDescriptions
│   ├── frontmatter.mjs            YAML-frontmatter parser
│   └── changeset-loader.mjs       PR-scope changeset discovery + classification validation
├── run.mjs                         CLI: --mode warn|gate, --gate <id>, --self-test, --rebalance
├── gates/
│   └── <gate-id>/
│       ├── enforcer.mjs            (target, options) → { findings, verdict, ruleDescriptions, rebalanceWrites }
│       ├── truth-table.mjs         (optional) verdict matrix specific to this gate
│       ├── classifications.mjs     ALLOWED set + aggregation policy
│       └── rule-descriptions.mjs   SARIF short-descriptions per ruleId
└── _fixtures/<gate-id>/{positive,negative}/   self-test fixture pairs

governance/registry.v1.json         declares all gates
gates/<gate-id>/.changesets/        per-gate escape-hatch directory (changeset .md files)

                                    The wire-Category gate (tempdoc 530 Pass-7 §F) lives at
                                    scripts/governance/gates/wire/ alongside the other gates.
                                    Its protobuf-specific truth-table, changeset-parser, buf
                                    wrapper, and rule-descriptions are local to that dir.
                                    scripts/contract-governance/ has been retired.
```

## Adding a new gate

1. **Author the gate-class module** at `scripts/governance/gates/<id>/`:
   - `classifications.mjs` exports `ALLOWED` (a Set of valid classification strings) and an aggregation function.
   - `rule-descriptions.mjs` exports a `Record<ruleId, shortDesc>` for SARIF.
   - `enforcer.mjs` exports `enforce(options) → result`. `options` includes
     `repoRoot`, `gate` (the registry entry), `baselineRef`, `mode`,
     `rebalance`, `fixtureMode`, `fixtureRoot`. `result` is
     `{ toolName, toolVersion, findings, verdict, ruleDescriptions, rebalanceWrites? }`.
2. **Register the gate** in `governance/registry.v1.json` with `id`,
   `enforcer` path, `changesetsDir`, `baseline`, `config`, and (optionally)
   `selfTestFixturesDir`.
3. **Author the changeset README** at `gates/<id>/.changesets/README.md`
   listing allowed classifications and required frontmatter fields.
4. **Build self-test fixtures** at `scripts/governance/_fixtures/<id>/`:
   - `positive/` — expected to pass.
   - `negative/` — expected to fail.
   - Optional `_fixture-override.json` per fixture (e.g., `{"ceiling": 30}`
     to lower the threshold for cheap fixture files).
5. **Wire CI** by adding/switching a step in `.github/workflows/ci.yml` to
   call `node scripts/governance/run.mjs --gate <id> --mode gate`.
6. **Optionally wire `./gradlew check`** via an Exec task calling
   `run.mjs --gate <id>` (no kernel gate is gradle-wired today — tempdoc 634
   removed the class-size `verifyGovernanceGates` task).

## Changeset escape-hatch protocol

Universal across gates. Each gate declares its own `ALLOWED` classification
set; the kernel does PR-scope discovery, frontmatter parsing, and aggregation.

```markdown
---
classification: declared-growth
tempdoc: 524
---
Free-form body explaining the context.
```

PR-scope semantics (slice 3a-1-8f §A.18): only `.md` files *added or modified*
in the PR's diff vs. the baseline ref count. Changesets present at baseline
are ignored. In `fixtureMode` (self-test), all `.md` files in the directory
count (no git diff).

### A growth-licensing changeset must re-pin in the same diff (enforced)

**A changeset licenses the PIN ADVANCE, not an unpinned overflow.** If a row's
measured value exceeds its live pin, the gate fails even with a covering
changeset present — with `<gate>/declared-growth-without-repin` (or
`…/declared-regression-without-repin` for the floor-shaped gates) rather than
`<gate>/silent-growth`. The finding names the pin file, the row, the measured
value and the line to write. Advance the pin to at least the measured value in
the **same commit**; keep the changeset, because for the gates that also ratchet
their baseline file the changeset is what licenses that edit.

This used to be advice, and being advice is what made it a recurring incident.
PR-scope discovery means a changeset without a re-pin buys exactly one PR of
silence: at squash-merge the changeset leaves the diff, the live value still
exceeds the pin, and the *next* push to `main` fails `silent-growth` on a row
nobody touched. Observed three times — #517→854 and #595→885 (`config-surface`,
tempdoc 883) and #614→#613/#615 (`dead-code`, tempdoc 910 §E.9) — each found by a
later lane rather than by the lane that caused it. Tempdoc 918 made it a rule.

Where the rule lives: `scripts/governance/lib/declared-growth-repin.mjs` (id,
description, wording, fail decision) called from each gate's live-exceedance
branch. It is a library rather than a runner-level pass because gates emit the
same `<gate>/declared-growth` id from their *baseline-shift* block for a pin
that WAS raised under a changeset — the case the rule exists to permit — so only
the gate can tell its two notes apart. `repin-coverage.test.mjs` asserts every
registered gate whose vocabulary licenses growth either calls the rule or is
listed exempt with a reason.

## CLI

```bash
node scripts/governance/run.mjs --mode warn|gate
                                [--gate <id>]...   (repeatable)
                                [--self-test]
                                [--skip-self-test]
                                [--produce-inputs]
                                [--rebalance]
                                [--out tmp/governance-report.sarif]
                                [--registry governance/registry.v1.json]
```

Exit codes:
- `0` - pass (or warn-mode regardless of findings; a gate `skipped` for a
  missing on-demand input is not a fail)
- `1` - gate-mode + any gate verdict is `fail`
- `2` - runner error (missing registry, missing enforcer, shallow clone,
  producer exited non-zero under `--produce-inputs`)

## Gate inputs (tempdoc 742)

Some gates consume a report artifact from `tmp/` (Knip, npm-audit, module-deps,
the JVM dead-class scan, PIT strength). Historically each enforcer independently
treated a missing report as warn-and-pass. Because that choice was copied by
precedent, the `dead-code` (Knip) gate was **silently inert for its entire life**:
nothing produced `tmp/knip-report.json`, so the gate always passed vacuously.
Vacuous green is a failure state - an enforcement mechanism whose precondition is
absent must fail loudly, not degrade to pass.

**Required inputs are part of the gate contract, enforced by the RUNNER, not each
enforcer.** A gate declares its inputs under `config.inputs` in
`governance/registry.v1.json`:

```json
"config": {
  "reportPath": "tmp/knip-report.json",
  "inputs": [
    { "path": "tmp/knip-report.json",
      "producer": "npm --prefix modules/ui-web run knip:report",
      "class": "required" }
  ]
}
```

- `class: "required"` + absent - in **gate** mode the runner fails the gate
  **without dispatching the enforcer** (verdict `fail`, ruleId
  `kernel/input-missing`), printing the `producer` command as the remedy. In
  **warn** mode the same missing input surfaces as an error-level finding but does
  not gate the build.
- `class: "on-demand"` + absent - the runner **skips** the gate without
  dispatching the enforcer (verdict `skipped`, ruleId `kernel/input-skipped`).
  `skipped` is not a fail and does not gate the build. `test-efficacy` (PIT is
  expensive, not part of the standard local pre-merge path) is on-demand.

The enforcers no longer carry a report-missing branch; a malformed report now
fails closed (`<gate>/report-malformed`, error + `fail`) rather than degrading to
a pass. The single missing-input policy lives in
`scripts/governance/lib/input-contract.mjs`.

**`--produce-inputs`** - before evaluating, the runner runs the declared
`producer` for each selected gate's absent **required** input (via a shell), then
evaluates the gate for real. A producer exiting non-zero is a runner error
(exit 2). On-demand inputs are never auto-produced.

**Self-test on full gate-mode runs (D3).** A full-registry run in `--mode gate`
first runs the `--self-test` fixture pass (so fixture rot is caught where it
bites) and aborts with exit 1 before evaluation if any fixture mismatches. Pass
`--skip-self-test` to bypass it; a gate-scoped run (any `--gate <id>`) never
triggers it. `--gate` is repeatable — `--gate a --gate b` evaluates both, in
**registry order** rather than the order the flags were given, and an id that
resolves to no registered gate aborts with exit 2 rather than being dropped (a
dropped id would report a pass for a gate that never ran).

## Gates currently registered

| Gate id | Baseline | Source | Auto-rebalance |
|---|---|---|---|
| `npm-audit` (historical id) | `scripts/ci/github-advisory-baseline.v1.json` | `tmp/github-advisory-report.json` | yes (removes resolved/improved identities) |
| `consumer-drift` | `gates/consumer-drift/slots.json` | per-slot `includeGlobs` (production callsites) | no (a populated slot's floor is raised by adding consumers, not by editing the baseline) |
| `ssot-catalog-sync` | `gates/ssot-catalog-sync/mirrors.json` | the declared root↔classpath catalog file pairs | no (the invariant is "copies match"; fix by syncing, not editing a baseline) |
| `test-efficacy` | `gates/test-efficacy/strength-baseline.v1.json` | `tmp/pit-strength-report.v1.json` (produced by `scripts/ci/report-pit-strength.mjs --run`) | yes (raises `minStrength`, lowers `maxNoCoverage`) |

(The registry holds further gate kinds — `module-deps`, `adr-coverage`,
`tempdoc-wiring`, `wire`, `dead-code`, `atom-fork-ratchet` and the register
gates — listed in `governance/registry.v1.json`; the table above names the
representative ratchet-file gates.)

### Retired to commodity tools (tempdoc 930 chunk F)

Five gates left the kernel because an off-the-shelf tool already did the job,
and one was dropped outright. Nothing here is a coverage reduction the kernel
still owns — each row names where the property now lives:

| Retired gate | Where the property lives now |
|---|---|
| `ts-any` | ESLint `@typescript-eslint/no-explicit-any: error` in `modules/ui-web/eslint.config.js`, with `modules/ui-web/eslint-suppressions.json` (ESLint bulk suppressions) as the ratchet. The lint parses, so it cannot score the English word "any" in a comment — the false-positive class the gate shipped with. |
| `todo-fixme` | Four surfaces, no gap left. **ui-web**: ESLint `no-warning-comments` in `modules/ui-web/eslint.config.js` (CI `Public claims`, step "Frontend lint", `npm --prefix modules/ui-web run lint`, now `eslint . --max-warnings=0` so an unused `eslint-disable` fails too). **`scripts/**` + `packaging/**` JS**: ESLint `no-warning-comments` in the root `eslint.config.mjs` (step "Script lint", `npm run lint:scripts`), ratcheted by the root `eslint-suppressions.json` — 1 marker pinned. **`*.ps1`**: `scripts/ci/check-ps1-warning-comments.mjs` (same step, `npm run lint:ps1`), which ESLint cannot parse; ratcheted by `scripts/ci/ps1-warning-comments-suppressions.json` — 1 marker pinned. **Java**: the PMD `CommentContent` rule, carried by all three rulesets in `config/pmd/` (`ruleset.xml`, `ruleset-cli-tools.xml`, `ruleset-tests.xml` — kept identical on this rule by `scripts/ci/check-pmd-ruleset-sync.mjs`); `pmdAll` runs in `check`/`build` by default and as the `Static analysis (PMD, all source sets)` step of CI's required `Build (no model blobs)` check. All four parse, so unlike the gate's regex they cannot score the marker word inside a string literal, an identifier or a URL. Follow-up 2 of tempdoc 930 §22.2 wired PMD over `main` (clearing 130 pre-existing violations of the ruleset's other rules); follow-up 3 wired the script and PowerShell surfaces; follow-up 10 extended PMD to every Java TEST source set (clearing 569 more, and finding 2 parked markers there), so no Java surface is left uncovered. |
| `dead-code-jvm` | ArchUnit's own `FreezingArchRule` in `WholeProgramDeadCodeTest`, whose committed store `modules/dead-code-audit/archunit_store/` IS the former `gates/dead-code-jvm/baseline.txt`. It fails in the test task that already builds the classpath instead of in a CI step reading a JSON side effect. |
| `style-literal-ratchet` | `scripts/ci/check-style-literal-ratchet.mjs`, unchanged and still baselined — it moved from the `ui-web-gates` recipe's kernel-gate line to its script line, so `run-ui-web-gates.mjs` still runs it. Only the kernel wrapper went. |
| `test-to-code` | Nothing. A per-module test-LOC/main-LOC ratio was a proxy metric with no defect record behind it; `test-efficacy` (PIT mutation strength) measures the property it was standing in for. |
| `prose-tier-register` | Nothing. The register doc it guarded (the prose-rule enforcement-tier table) was deleted with it; its 18 changesets are preserved under `docs/tempdocs/930-evidence/retired-changesets/prose-tier-register/`. |

`npm-audit` was examined for the same treatment and **kept**: `npm audit
--audit-level=high` exits non-zero on today's lockfiles (11 high advisories in
the root tree), and ADR-0044's 2026-09-04 amendment rejects npm's bulk-advisory
POST transport on measured evidence. The commodity replacement is not available.

The historical **`npm-audit`** gate id reads exact versions from the root, `ui-web`,
shell, runtime-client, and wire-contract package locks and queries GitHub's Global
Security Advisories API with bounded, retryable, paginated GETs. Its baseline is a
set of accepted high/critical GHSA identities plus each accepted severity. A new
identity or upward severity change cannot be cancelled by a different advisory
disappearing, which was possible under the superseded count baseline. Provider or
target unavailability fails closed. Matching workflow installs use `--audit=false`
because npm's install-time advisory POST is duplicate evidence with an independent
transport timeout; `check-workflow-npm-audit-policy.mjs` prevents an implicit audit
from returning. Repository vulnerability alerts and automated security updates
provide ambient monitoring; the kernel gate remains the per-PR lockfile fact.

The **`test-efficacy`** gate (tempdoc 555) ratchets per-seam mutation **test-strength**
(killed/covered, with `TIMED_OUT` as killed) plus a per-seam `maxNoCoverage` ceiling, over the
behavioral-law seams declared in `governance/logic-seams.v1.json`. It is **fail-closed** (a registered
seam absent from an existing report → `seam-not-measured` error) and PIT-driven, so it is not wired
into `check` or the public hosted `CI` fact lanes. Produce fresh evidence manually with
`node scripts/ci/report-pit-strength.mjs --run`, then run
`node scripts/governance/run.mjs --gate test-efficacy --mode gate`. The cheap
`scripts/ci/check-logic-seams.mjs` register-integrity validator runs in the normal gate job
(every CI run + locally) — run it directly at authoring time after editing a registered seam.

The kernel briefly carried a non-ratchet, coverage-style gate, `independent-review`
(tempdoc 550 thesis V), plus a presentation-work sibling `ux-audit-closure`
(tempdoc 559 §6-7). Each declared substrate slices / presentation scopes that
failed the build until they carried an independent review/audit record (reviewer or
auditor ≠ committer, `verdict: approve`, `liveVerified: true`, fresh `coversThrough`).
Both were **retired** (tempdoc 530 §Remediation): they gated merges on a second
actor + a live stack for any touched scope, gave no mechanical signal an automated
check could supply, and false-failed on no-op refactors. The
implementer-≠-validator and static-green-≠-live-working rules remain recommended
honor-system practice (`.claude/rules/slice-execution.md`), no longer gate-enforced.

The **`consumer-drift`** gate (tempdoc 531) generalizes the one-shot C-018
substrate-without-consumer audit (tempdoc 527): each slot in `slots.json`
declares a substrate symbol that must retain `expectedMin` production consumers;
the gate fails on below-min drift (after a declared grace window) unless a
`slot-retraction` / `grace-extension` / `emergency-override` changeset covers
it. Slots are populated by *measuring* current consumer counts (grandfather
from-here).

The **`ssot-catalog-sync`** gate mechanizes the "Classpath catalog drift"
pitfall (CLAUDE.md Common Pitfalls): the root SSOT catalogs and their classpath
copies under `modules/adapters-lucene/src/main/resources/SSOT/catalogs/` must
stay in sync (production loads the classpath copy), or fields are silently
dropped in packaged builds. It converts the advisory `ssot-hint` PostToolUse
hook (~70%) into a ~100% gate over the declared mirror pairs (JSON compared
order-insensitively, text CRLF-normalized).

## Baseline-shift detection (tempdoc 530 §Layer 1 closure)

Every ratchet-file gate also reads its baseline at the PR's baseline ref. That
ref is resolved by the `git-base` ladder in `scripts/governance/lib/git-utils.mjs`:
an explicit ref (`--preflight <ref>`) wins; otherwise `merge-base(HEAD, <default
branch>)`, taking the branch from `GITHUB_BASE_REF` on a CI `pull_request` run and
from `origin/HEAD` locally; otherwise `HEAD~1`. A merge-base equal to `HEAD` means
nothing has diverged, so it falls through to `HEAD~1` — which keeps the
post-squash case on the default branch behaving exactly as before.

Until the wave-1 residue PR the ladder was `HEAD~1` and nothing else, despite the
docstring promising a PR base. Every `git-base` gate therefore diffed a
one-commit window, and a changeset committed earlier in a branch dropped out of
scope as soon as another commit landed (tempdoc 884 §F row 11). If you are
reading an older tempdoc that says to sanity-check a gate with
`--preflight <real base>` before believing a `silent-growth` finding, that
workaround is what it was working around.

If the baseline file itself was
relaxed in the PR — an npm advisory identity or severity was accepted, a
consumer-drift slot's `expectedMin` lowered or a slot removed
(`silent-floor-drop` / `silent-slot-removal`) — the gate fails with the gate's
`silent-<change>` rule unless a classified changeset is present.

This closes the silent escape-hatch class the tempdoc named: "edit the
baseline in the same commit as the change so the gate sees nothing wrong."
Both the *live state* and the *baseline state* are gated.

The complementary direction — a changeset that licenses growth but leaves the
pin where it was — is gated too, by the re-pin rule above. What the kernel
actually enforces today, stated with its gaps rather than as a slogan:

- **A live value over its pin fails everywhere** — `silent-growth` without a
  changeset, `declared-growth-without-repin` with one. No gaps.
- **A pin RAISE without a changeset fails only where a baseline-shift block
  exists** — `config-surface`, `dead-code`, `module-deps`, `npm-audit`,
  `test-efficacy`. It is **not** detected by `atom-fork-ratchet`, which has no
  such block: raising one of its rows by hand yields only `rebalance-available`.
  (Tempdoc 930 chunk F retired four of the five gates that used to sit on this
  gap — `ts-any`, `test-to-code`, `dead-code-jvm`, `style-literal-ratchet` — so
  it now has one occupant, not five.)
- **Even where the block exists, a NEW row is not a raise.** The comparison is
  per row against the same row at the PR base, and `priorPin === undefined`
  skips (`dead-code/enforcer.mjs`) — deliberately, so a genuinely new file is
  not read as "everything grew", but it does mean a row added at any value is
  unchecked baseline-side.

So the closure is complete on the live-value axis and partial on the
baseline-edit axis. Extending baseline-shift detection to the gate that
lack it is tracked in tempdoc 918 §G.

## Truth-table shape contract

Per tempdoc §Open questions "hard" lean: every gate authors a
`truth-table.mjs` sibling exporting at least one `verdict*` function with
the uniform output shape `{ ruleId, status: 'pass'|'fail'|'info', reason }`.
The runner enforces this at gate load time (`scripts/governance/lib/truth-table-runner.mjs#assertTruthTableShape`).
Gates that don't conform fail-fast before they run.

## What this kernel does NOT do

- **It does not gate the wire-Category protobuf contract.** That stays in
  `scripts/governance/gates/wire/enforcer.mjs` with its protobuf-specific
  truth-table and `evolution-rule` changeset vocabulary. Both runners share
  the substrate (`scripts/governance/lib/`) but the kernels are
  semantically distinct.
- **It does not enforce the meta-loop** (every prose rule named in
  `CLAUDE.md` / `.claude/rules/` is tagged with its enforcement tier). The
  `prose-tier-register` gate and its register tried to, and tempdoc 930 §19.3 F3
  retired both: 18 changesets of bookkeeping, no defect the tier tag prevented.
  What survives is the `hook-integrity` gate, which checks the thing the tier
  claim was a proxy for — that a hook actually loads and bites.
- **It is not a substitute for human-judgment audits** (527-style
  substrate-consumer audits). The kernel catches *recurring gross failures*;
  the human still calibrates edges.

## Deliberate softness portfolio

Some verification seams are **intentionally fail-open, opt-in, or advisory**. Each such
choice is locally reasonable, but unrecorded softness silently accumulates: nobody ever
re-examines the *set*. This register makes every deliberate softness visible with its
reason and the condition under which it should flip to strict. It is a record of
decisions, not a to-do list — a row leaves this table by being flipped (with its
enforcement moved to a gate/check) or by its subject being deleted.

Rules for the table: a new deliberately-soft seam gets a row in the same change that
creates it; flipping a row requires meeting its flip condition, not just wanting to;
a row with a met flip condition should be flipped or its condition honestly revised.

| Seam | Softness | Reason | Flip condition |
|---|---|---|---|
| Governance kernel + Gradle `check` in hosted CI | Not run on hosted PR lanes (unit-test subset only) | ADR-0026: self-hosted runner not guaranteed online; kernel gates need local artifacts | A hosted-runnable subset of kernel gates is identified and proves stable for ~a month in warn mode |
| `modules/system-tests` (spawned-process Head↔Worker suite) | Opt-in (`includeSystemTests`), never CI-scheduled (tempdoc 627) | Spawns multiple JVMs; slow/flaky on shared infra; unsuitable for agent sessions | A scheduled runner (nightly self-hosted or hosted with dist caching) demonstrates 5 consecutive green runs |
| `test-efficacy` PIT mutation ratchet | `workflow_dispatch`-only | ADR-0026 runner availability; PIT wall-time | Same as kernel row; or PIT scoped to changed-seams-only proves < ~10 min |
| Wire validation in **production** | Degrades + logs + records drift instead of throwing (dev/CI throw — this branch) | Crashing the user's UI on a field mismatch punishes the user for a developer mistake; FE/BE co-ship so prod skew is rare | Wire-drift telemetry (diagnostics export `feTelemetry.wireDrift`) shows sustained zero drift across releases — then reconsider; or a user-facing error boundary makes throwing safe |
| REST error envelope | Untyped (hand-assembled maps at emit sites; no FE runtime schema) | Zero FE runtime consumers parse it today; typing it means authoring a record + repointing dozens of emit sites for no current reader | A FE feature starts *reading* error-envelope fields programmatically |
| Agent-session snapshot (`/api/chat/sessions/{id}`) | Deliberately loose schema (free-form message/profile maps) | The snapshot intentionally carries evolving free-form shapes; pinning them would freeze exploration | The message/profile shapes stabilize (no schema-affecting change for ~a quarter) |
| Retrieval quality/perf/leak ratchets | Advisory (hook-nudged, not CI-required) | Full eval runs cost real wall-time + GPU; change-filtered nudges match the edit surface | Hosted or scheduled eval infrastructure makes a scoped ratchet run affordable per PR |
| Real-both-ends gRPC search test in default `test` | Only an in-process stub round-trip runs by default (this branch); real `WorkerSearchService` needs worker-services + adapters-lucene on the classpath | Module isolation: app-services cannot see worker classes; a shared-test-fixtures module is not justified by one test | A second cross-process test wants the same fixture module |
| Worker OS priority, `NoCFSRatio`, XXH3 content-hash dedup, tokenizer-parity test, JaCoCo thresholds, zero-spinner rule | Early-project rules that faded without a recorded decision | Superseded in fact by later architecture (locale-invariant analysis, different dedup path, different coverage posture) but never formally retired | Any of them turns out to still matter — then re-derive from measurement, don't restore from memory |
| GPU-only dev inference (no CPU fallback) | Fails closed instead of degrading to CPU | A CPU 9B fallback runs ~10× slower and saturates every core, DOSing concurrent worktrees (tempdoc 656) | Not expected to flip; recorded for visibility |
| `-PskipWebBuild` backend-only builds | FE build skipped in memory-constrained runs | Windows memory pressure on full builds | Not expected to flip; recorded for visibility |

## See also

- tempdoc 530 (class-size ratchet automation) — design tempdoc
- Current gate registry entries describe the active enforcement surfaces.
- `scripts/governance/gates/wire/` — the wire-Category gate (formerly `scripts/contract-governance/`, retired Pass-7 Phase F)
- tempdoc 683 — the hardening batch that introduced the softness portfolio
