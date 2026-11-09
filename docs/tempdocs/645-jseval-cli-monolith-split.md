---
title: "Split the jseval cli.py command monolith: every jseval tempdoc edits one ~3.5k-line file holding all CLI commands, making it a structural multi-agent collision hotspot — concurrent edits repeatedly cost effort and once nearly committed another agent's broken WIP into it (untracked-module reference → broken main). Design settled + SHIPPED: conform to jseval's existing self-registering-module registry (the projections/ seam) — per-group command modules each exporting their click.Command(s), assembled onto main by a small registry, guarded by a load-all surface-lock test. Post-impl design recorded (deferred): the registry is the canonical command surface, and every derived view (categorized help, agent JSON catalog, golden inventory, skill/docs command list) is a PROJECTION of it, not a fork — the read-side complement of the write-side split (projection-vs-fork seam, 553/559/646). NOT a new mechanism."
type: tempdocs
status: IMPLEMENTED (as-built 2026-06-30, worktree `worktree-jseval-cli-split`) — split landed + validated; merge to main pending user direction
created: 2026-06-24
author: agent analysis — split out of tempdoc 635 §workflow-lessons #5 (the cli.py collision hotspot)
related:
  - 635-contamination-resistant-eval-corpus      # where the hotspot bit (workflow-lessons #5): the partial-stage near-miss + recipe
  - 644-eval-runs-from-a-worktree                 # sibling root-cause: eval-on-main forces the concurrent edits that make cli.py collide
  - 618-agent-developer-velocity-friction         # Seam C: the isolate-and-reconcile / single-writer-shard principle this conforms to
---

> **Historical projections (2026-11-09):** `bootstrap_ci`, `rank_diff` and `stratified_metrics` generators/tests are retired; references below describe past implementations and captured evidence. **Design settled (2026-06-29).** The idea + purpose (below) stand; the design pass at the foot of this file
> picks the structure: **conform to the registry-of-self-registering-modules seam jseval already runs in
> `projections/`** rather than invent a CLI-specific mechanism. Implementation is deferred to user green-light
> (it touches every command's home — a shared-substrate change, `ask-when-uncertain`). The earlier
> *Investigation* (§) and *Theorization* (§) pass remain as the supporting analysis the design rests on.

# 645 — Split the jseval CLI command monolith

## The idea
`scripts/jseval/jseval/cli.py` is a single ~3,500-line file containing **every** jseval CLI command (run,
gates, corpus-*, release, calibrate, agent-utility, …). Because essentially every jseval-touching tempdoc adds
or edits a command, they all edit this one file. The idea: **decompose `cli.py` into per-command-group
modules** (each registering its commands with the shared top-level group), so unrelated work lands in
different files.

## The purpose / why it matters
- **It removes a recurring multi-agent collision hotspot at its root.** With parallel agents forced to edit
  `cli.py` (compounded by eval-on-main, see 644), collisions are routine: blocked commits, bundled-foreign-work
  commits, and — this session — a **near-miss where a blind stage would have committed another agent's
  uncommitted `recall-profile` WIP** that referenced an *untracked* module, breaking `main` (635
  §workflow-lessons #5). The hand-rolled partial-stage recipe works but is error-prone; the structural fix is
  to stop concentrating all commands in one file.
- **It reduces per-edit cost and improves navigability** — finding/owning a command group, and reviewing a
  diff, is easier in a focused module than in a 3.5k-line monolith.

## Scope boundary (for the design phase, not decided here)
Out of scope for this stub: the **module decomposition** (which command groups, how they register with the
`main` Click group, shared-helper placement), the **migration order** (incremental vs one-shot, keeping the
public `python -m jseval <cmd>` surface identical), and whether this composes with any existing CLI-group
structure already in the file. This file records only the idea + purpose; the design picks the structure.
Until then, the operational mitigation stands (635 §workflow-lessons #5): `git diff cli.py` before editing,
partial-stage by hunk-marker, compile-check the staged subset.

---

## Investigation findings (de-risk pass — 2026-06-29, no design chosen)

Investigation only, per the assignment: premise verified against `main`, constraints surfaced, registration
mechanisms laid out **as options with trade-offs** (the stub defers the choice). No design picked, no code
moved.

### 1. Premise verified — the file is what the stub says, and bigger
- `scripts/jseval/jseval/cli.py` is **3,575 lines / 167 KB** (`cli.py:1`). It defines **59 inline
  `@main.command(...)`** commands plus **2 `main.add_command(...)`** registrations (`qu-spike`, `qu-v3`) =
  **61 commands**, all flat under one `@click.group()` `main` (`cli.py:21`). There are **no Click sub-groups**
  today — every command hangs directly off `main`.
- The package is *already* heavily modularized **for logic** (~80 sibling `.py` files: `run.py`, `gate.py`,
  `release.py`, `perf_gate.py`, …). cli.py is the *command-definition* monolith on top of them: each command
  is a thin-to-medium Click wrapper (~30–60 lines of `@click.option`s + a body that lazily `from . import X`
  and calls into the logic module). So the split is **command-surface decomposition**, not a logic refactor —
  the hard part (logic separation) is already done.

### 2. The proposed pattern already exists in-file — this is low-novelty
The stub's "each registering its commands with the shared top-level group" is **already demonstrated** for two
commands: `qu_spike.py` / `qu_v3_eval.py` define `@click.command("qu-spike")` / `@click.command("qu-v3")` in
their **own modules**, and cli.py does `from .qu_spike import qu_spike; main.add_command(...)`
(`cli.py:2321-2325`; `qu_v3_eval.py:277`). So the mechanism is proven and the public surface
(`python -m jseval qu-v3`) is preserved by it. This de-risks the migration substantially: it's "do the
existing thing 59 more times," not "invent a structure."

### 3. Hard constraints the design must preserve
- **Two entry points pin `main` to `jseval.cli`.** `pyproject.toml [project.scripts] jseval = "jseval.cli:main"`
  **and** `__main__.py: from .cli import main`. Whatever the split, `main` must remain importable as
  `jseval.cli.main`. (cli.py can shrink to a registrar; it can't move/rename `main`.)
- **Lazy in-body imports are deliberate and load-bearing for startup latency.** Command bodies import heavy
  deps *inside* the function (`from . import ingest`, `import ir_datasets`, `from . import agent_retrieval_eval`,
  …) so `jseval --help` and cheap commands don't pay for `ir_datasets`/`inspect-ai`/`onnxruntime`. Any
  registration mechanism that **eagerly imports all command modules at startup** would regress this. This makes
  Click's official **`LazyGroup`** (below) a natural fit, and argues against naïve "import every command module
  in `__init__`."
- **Shared helpers cluster *with* their command group — they are not a tangled web.** The non-command
  top-level `def`s in cli.py partition cleanly: the `run` cluster (`_run_iteration`, `_reset_index`,
  `_check_build_freshness`, `_do_run` — `cli.py:228-422`) serves `run`/`requery`; `_load_run_for_compare` /
  `_first_mode` serve `compare`/`trend`; `_write_bench_output` serves the bench commands; the `_print_*` /
  `_compare_by_decision_kind` set serves the eval-print commands; `_step_surface_map` serves UI. They **move
  with their group**. The only genuinely cross-cutting module-level name is the `_DEFAULT_BASE_URL` constant
  (`cli.py:18`, plus a near-duplicate `_DEFAULT_BASE_URL_EVAL` at `cli.py:1987` — a latent dedup nit) →
  a tiny shared `_cli_common`/existing `_paths` is the obvious home.
- **No test snapshots the full command surface — this is a migration-guard gap.** `tests/test_cli.py` tests
  *individual* commands' `--help` (run, requery, compare, …) but nothing asserts the **set** of 61 commands or
  their names. A move-and-reregister refactor can silently **drop** a command (forget one `add_command`, or a
  lazy-map typo) and every existing test still passes. The design phase should add a surface-lock test
  (`set(main.commands) == {expected…}` or a golden command list) **before** moving anything, so the refactor is
  guarded. This is the `audit-without-test` discipline applied to a mechanical move.

### 4. Natural command groups (evidence, not a decision)
The 61 commands fall into obvious families by name prefix, and the **logic modules already mirror them** — so
the grouping is discovered, not invented: `ui-*` (7: ui-perf/check/shot/a11y-gate/diff/critic/fuzz),
`corpus-*` (4: build/certify/fidelity/probe), `*-gate` (6: gate/relevance/perf/leak/llm/extraction),
`utility-*` (5: compose/run/calibrate/status/judge), `*-eval` (6: extract/ner/retrieval/tier2/agent/rag),
bench (5: bench-concurrency/llm-bench/ingest-bench/engine-bench/knn-bench), calibrate (3:
calibrate/calibrate-drift-baseline/recalibrate-nightly-baseline), and a `run`/compare core
(run/requery/compare/trend/counterfactual/shadow-eval/bisect/diff/…). **Note:** grouping the *modules* must
**not** become grouping the *CLI* — the stub requires `python -m jseval ui-shot` (flat) to keep working, so
the modules group source, but the commands stay flat under `main` (a module is not a Click sub-group here).

### 5. Registration mechanism — three options, deferred (the real design decision)
The collision the tempdoc targets has **two surfaces**: the command *body* (hundreds of lines — fully removed
from cli.py by any split) and the *registration site* (where cli.py learns the command exists). How much
collision survives depends entirely on which registration mechanism is chosen:

| Option | Adding a command touches | Residual cli.py collision | Startup cost | Risk / notes |
|---|---|---|---|---|
| **A. `add_command` (the qu_spike pattern)** | new module **+** one `import`+`add_command` line in cli.py | one line per command, all in cli.py | eager import of the command module at startup (regresses lazy-load unless the module is itself import-light) | lowest novelty (already in use); but **eager import** conflicts with constraint §3 |
| **B. Click `LazyGroup`** (official, confirmed current — `pallets/click` docs/complex.md) | new module **+** one entry in a `lazy_subcommands={"name": "jseval.x.cmd"}` dict in cli.py | one dict entry per command, all in cli.py | **lazy** — module imported only when that command runs (preserves §3) | **best fit for the lazy-import constraint**; map is still a shared dict (same one-line residual as A) |
| **C. Auto-discovery** (iterate a `commands/` subpackage, register any `click.Command`) | **only** the new module — zero cli.py edits | **none** | depends — naïve iteration eagerly imports all (regresses §3); needs a lazy variant | strongest anti-collision, but most machinery; the import-eagerness must be solved, and a missing `git add` makes a command **silently vanish** (the §3 surface-lock test is the guard) |

The honest read: **A and B do not eliminate the hotspot, they shrink it ~100× (one line vs a whole command
block).** Only **C** removes the shared edit entirely — but C is also the variant whose failure mode
(untracked/missing module → command silently disappears) most resembles the original **635 near-miss**
(a registration referencing an *untracked* module broke `main`). So C must ship **with** the §3 surface-lock
test, or it trades a loud merge-conflict for a quiet missing-command. This is the core trade the design phase
must make; the stub correctly left it open.

### 6. Critical assessment
- **645 and 644 are complementary, not redundant — do not let one cancel the other.** 644 (eval-from-worktree)
  removes the *forcing function* that pushes concurrent eval edits onto shared `main`; one could argue that if
  agents work in isolated worktrees the cli.py collision evaporates. It does **not**: worktrees defer the
  conflict to **merge** time, and a 3.5k file every agent appends commands to is a **merge-conflict magnet**
  regardless of where the editing happens. 644 closes the *silent-foreign-WIP* trap (partial-staging on main);
  645 reduces *conflict frequency* + improves navigability. Different benefits, both real. Per
  `structural-defects-no-repeat`, the one documented near-miss is sufficient warrant; neither needs a second
  incident, and "644 will make 645 unnecessary" is exactly the kind of *wait-for-more-evidence* deferral the
  rule names.
- **The stub's own urgency framing is sound but partly unverifiable *in this repo*.** This is `justsearch-public`,
  a **squash-mirror** (`git log` shows one commit, "JustSearch v0.1.0 — initial public release", touching
  cli.py), so the "every jseval tempdoc edits this file" frequency claim **cannot be re-derived from local
  history here** — it lives in the private repo's history (635/644 are the qualitative record). I take the
  premise as given from 635, but flag that any "N tempdocs touched cli.py" metric must come from the private
  repo, not this one.
- **Scope realism / AHA.** The split is worth doing, but the machinery should stay minimal — 61 flat commands
  do not justify a plugin framework. Option B (LazyGroup) is the sweet spot: official, preserves startup
  latency, ~one-line-per-command registration, and the migration is "move body to module, add a dict entry"
  repeated. Reserve Option C only if the design phase decides the residual one-line registration collision is
  itself worth eliminating (it may not be — a one-line append rarely conflicts).

### 7. Doc nit (not blocking)
The stub (and 644) cite **"635 §workflow-lessons #5"**, but 635's *Corpus-design workflow lessons* section
(`635:1277`) is numbered **only #1–#4**; the cli.py-hotspot content actually lives at **635:1300** (lesson #4's
tail) and the near-miss narrative at **635:1157-1163** (the Hardening pass — the 636 agent's concurrent cli.py
edit forced bundling `leak-gate-derive`/`judge-ceiling` because the file "couldn't be partial-staged"). The
substance is real and correctly summarized; only the "#5" pointer is off-by-section. Worth fixing the citation
when this leaves stub state.

### 8. Open questions for the design phase
1. **Registration mechanism**: A vs B vs C (§5) — recommend B unless the one-line residual is deemed worth C's
   extra machinery + surface-lock dependency.
2. **Surface-lock test first**: add the `set(main.commands)` golden-list test *before* moving commands (§3) —
   should this be a precondition gate on the whole migration?
3. **Migration order**: one-shot (all 61 at once — large but mechanical, one merge window) vs incremental
   (group-by-group — smaller diffs, but cli.py stays a hotspot for the duration, and each increment re-touches
   the registrar). Incremental is safer per-step but prolongs the collision window the tempdoc exists to close.
4. **Shared-constant home**: `_DEFAULT_BASE_URL` (+ dedup the `_DEFAULT_BASE_URL_EVAL` near-twin) → `_paths` or
   a new `_cli_common`?
5. **Does this compose with a future sub-group surface?** Today everything is flat; if `jseval ui shot`
   (grouped) is ever wanted, LazyGroup composes recursively (Click docs show nested LazyGroup). Out of scope
   now, but the mechanism choice shouldn't foreclose it.

---

## Theorization (broad pass — 2026-06-29, nothing decided)

Per the assignment: think widely about framings, alternative directions, hidden assumptions, risks, and
whether 645 points at a broader system shape. This is *option-generation*, explicitly **not** a design — some
ideas below are recorded precisely because they may be useful later even if they are not the answer.

### A. Reframings — what problem is this *actually*?
1. **645 is one instance of a recurring shape: the "convergent edit point."** A single file/list every parallel
   contributor must write to. The codebase is full of these and *already has a pattern language for them* —
   surfacing that reframes the whole tempdoc from "split a big file" to "apply the house style for convergent
   edit points to the CLI registry." Confirmed convergent points in-repo: `docs/observations.md` (everyone
   appends notes), `.claude/rules/tier-register.md` (everyone adds rule rows), `governance/registry.v1.json`
   / `execution-surfaces.v1.json` / `logic-seams.v1.json` (everyone registers gates/surfaces/seams),
   `settings.gradle.kts` (module includes), `MEMORY.md` (index lines), `SSOT/catalogs/fields.v1.json`. The CLI
   command list is the same shape.
2. **The house already solved the *worst* version of this shape — and the solution is the strongest hint for
   645's Option C.** `observations.md` was **sharded** (tempdoc 618 Seam C): `note-observation.mjs` writes a
   *per-author shard* under `docs/observations.d/`, `fold-observations.mjs --apply` reconciles them — because a
   shared append could be *silently wiped* by a neighbour's commit. Auto-discovery of one-file-per-command
   (the §5 Option C) is **structurally the same move**: each writer owns a file, a discovery/fold step
   assembles the whole. So Option C is not the exotic high-machinery choice it looked like in §5 — it is
   "do for the CLI what the project already chose to do for observations." That materially changes its standing.
3. **Correctness vs. cost — be honest about which lever 645 pulls.** The shape has two severities. *Silent-loss*
   convergent points (an append vanishes, no git conflict) are **hazards** → must be sharded/discovered. *Loud-
   conflict* convergent points (two edits to one file → git refuses the merge) are **friction**, not hazards —
   git is self-protecting there. cli.py is overwhelmingly the **loud-conflict** kind: its collisions are merge
   conflicts and blocked commits, which are visible and recoverable. The one time it crossed into silent-loss
   territory — the 635 near-miss — the silent-loss came from **partial-staging on `main`** (a *workflow*
   choice), not from the file's structure; that path is what 644 + worktree discipline close. **Implication
   (framing only, not a deferral):** 645's structural justification is *friction-reduction + navigability +
   ownership-locality*, which is real and worth doing — but its most dramatic evidence (the near-miss) is a
   workflow defect, not a file-structure defect. Stating this honestly is required by the "don't smuggle a
   correctness argument into a cost argument (or vice-versa)" discipline; it does **not** argue for waiting
   (that would be the `structural-defects-no-repeat` evasion) — the user owns the priority call.
4. **Collisions are about *where the heat is*, not file count.** "One file → N files" only helps if edits are
   spread across the N. If 80% of cli.py churn is the `run`/`*-gate` clusters, splitting the cold `ui-*`
   commands barely moves the needle. The decomposition should be **collision-frequency-weighted**: split the
   hottest clusters into their own files first (and possibly the single hottest command — `run`, with its 4
   private helpers — into its own). The heat data lives in the **private** repo's `git log` (this public mirror
   is squashed); pulling a churn-by-region heatmap of cli.py there should *precede* picking the cut lines.
5. **The value is ownership-locality, not just smallness.** Splitting implicitly assigns each command family an
   owned region — which is what actually localizes edits and routes review. This is the same benefit the
   governance registers get from per-domain ownership. Worth optimizing the cut for *ownership cohesion*, not
   just line count.

### B. Expanded solution space (beyond §5's A/B/C)
- **D5 — Don't split; make the collision cheap (`.gitattributes merge=union`).** The repo already uses merge
  drivers (`merge=lfs`, `.gitattributes:25-29`). For an **append-mostly** file of independent blocks,
  `cli.py merge=union` auto-resolves concurrent *appends* (both new commands kept) with **zero refactor**.
  This is the lazy cousin of the observations shard-then-fold (union *is* an automatic fold). Honest limits:
  union merge is unsafe for *edits to existing* commands (it keeps both sides → duplicate options / two
  commands with the same name), and it can produce textually-merged-but-semantically-broken results — so it
  needs the §3 surface-lock test as a backstop and is really only sound for the pure-append case. Likely not
  the terminal answer, but a **near-zero-cost interim** that buys time, and a useful baseline to measure the
  refactor against ("does splitting beat just turning on union merge?").
- **D6 — Codegen the registrar (fits the house codegen culture).** Keep commands in modules; **machine-generate**
  the `add_command`/`lazy_subcommands` registry from a directory scan as a build step — exactly like
  `gen-agent-hooks-wiring.mjs`, `skills-sync.mjs`, `gen-token-names.mjs` already do for other "discovered set →
  declared artifact" problems. The registrar is never hand-edited → no human write-collision; regen is
  deterministic; conflicts (if any) are mechanical (re-run the generator). This is a **hybrid of B and C**:
  greppable explicit registry (like B) but maintained by a machine (like C's zero-human-write). Strong
  candidate that §5 missed; composes with the project's existing `*-sync --check` CI guard family.
- **D7 — Reduce the *number* of commands, not just their location (generic dispatch).** The 6 `*-gate` and
  6 `*-eval` commands are near-homogeneous. A data-driven `jseval gate <kind>` / `jseval eval <kind>` runner
  (registry row + function per kind) shrinks the surface instead of relocating it. This is a *different problem
  framing*: command-count is itself a driver of churn. Cost: it changes the public surface
  (`jseval perf-gate` → `jseval gate perf`), so it needs back-compat aliases and a docs/skill sweep. Out of
  scope for a "split" tempdoc, but worth recording as the **longer-horizon** direction that would make 645
  partly moot — and as a caution against over-investing in machinery to manage a command list that maybe
  shouldn't be this long.
- **D8 — Entry-point plugin registration (`importlib.metadata`).** Register each command via
  `[project.entry-points."jseval.commands"]`. Discovery without a central code list — but `pyproject.toml`
  becomes the convergent point (collision merely *moves*), and it needs reinstall to take effect. Recorded for
  completeness; weaker than D1/D6 for this case.
- **Granularity is a dial, not a binary.** per-command (61 files, max isolation, worst "see related commands
  together" + file-count bloat) ↔ per-group (~8–10 files, balance) ↔ status-quo (1 file). The optimum is
  almost certainly **per-group for cohesive families + per-file only for the hottest standalone command(s)**,
  set by collision-heat × cohesion (A4/A5), not a uniform rule. Don't auto-pick the extreme just because it
  maximizes one axis.

### C. Hidden assumptions worth challenging
- **"The flat `python -m jseval <cmd>` surface is a law."** It is a *back-compat constraint* (skills, docs,
  scripts call `jseval ui-shot`), **not** a design law. Sub-groups (`jseval ui shot`) would give each family
  its own namespace (a structural collision reducer in its own right) and Click supports both via aliases. The
  constraint is a *choice to preserve*, and the design can revisit it with an alias shim rather than treat it
  as immovable.
- **"Splitting the file is the right unit of action."** Maybe the unit is the *workflow* (644 + worktree
  discipline) or the *command count* (D7), with the file-split a third-order polish. Ordering the three by ROI
  (workflow > count > structure?) is itself a design question 645 should not assume away.
- **"Lazy loading is strictly better because it preserves startup latency."** It has a real downside (next
  bullet). Eager-but-light registration (thin command modules that defer heavy imports in their bodies, exactly
  as cli.py does today) could keep startup fast *without* lazy indirection — preserving loud import-time
  failure. The latency win of lazy loading must be weighed against the failure-surface change, not assumed.

### D. Risks not yet on the table
- **Lazy loading converts *loud* import errors into *quiet* per-command runtime errors.** Today a syntax/typo
  error in any command breaks `import jseval.cli` → the whole CLI fails at once, impossible to miss. With
  LazyGroup/auto-discovery, a broken command module only fails *when that command runs* — a latent breakage can
  ship green if no test exercises it. **Therefore the §3 surface-lock test must *force-load each command*
  (materialize every lazy import), not merely assert the name set** — otherwise the split silently weakens the
  build's "one broken command = red" guarantee. This is the single most important correctness nuance for the
  design.
- **The migration is itself the largest possible cli.py edit — it collides with every in-flight branch that
  touches cli.py.** The act of de-hotspotting *is* a hotspot event. This argues for a coordinated "freeze
  cli.py → migrate in one window → unfreeze" play, and reframes the one-shot-vs-incremental choice: one-shot is
  one big conflict resolved once; incremental keeps cli.py hot (and re-touches the registrar each step) for the
  whole duration — the opposite of the goal while it's underway.
- **Circular-import / dependency-direction risk.** Commands read `ctx.obj["json"]` (fine, flows through Click)
  but also reach cli.py module-globals (`_DEFAULT_BASE_URL`, `cli.py:18`). The split must enforce a one-way
  dependency: command modules → a shared `_cli_common`, **never** back to `cli.py`. Done carelessly this
  creates a cli.py ↔ command-module cycle.
- **Discoverability regression.** A 3.5k file answers "what commands exist / where's the option for X?" with one
  grep. Post-split that needs the convention or `jseval --help`. The surface-lock golden list should double as
  the human index to offset this.
- **Cross-repo divergence.** public is a squash-mirror of private. The split should land **private-first** and
  flow to the mirror; a direct public-only edit to cli.py would diverge the structures.

### E. Does 645 point at a broader principle / invariant?
Tentatively **yes** — recorded as a *principle-candidate*, not a decision:

> **Convergent-edit-point principle (candidate).** *A file or list that every parallel agent must write to is a
> coordination liability. Prefer — in order of the loss it prevents — per-author shards + fold (for
> silent-loss points), auto-discovery or codegen'd registries (to remove the human write), or at minimum a
> union/auto-merge driver (for loud-conflict append-mostly points), over a hand-edited shared registry.*

This generalizes **tempdoc 618 Seam C** (which solved exactly this for `observations.md`) from a one-off into a
reusable lens, and it slots beside an existing **guard family**: "a discovered/generated set must equal a
declared golden set, and each member must load" — already instantiated by `skills-sync --check`,
`gen-token-names --check`, the hooks-wiring codegen, and (proposed) 645's surface-lock test. Two implications
if the principle holds:
1. **645 becomes a pilot, not a one-off.** The same treatment is a candidate for the *other* convergent points
   (tier-register rows, governance registers, `settings.gradle.kts` includes) — each should be triaged by the
   silent-loss-vs-loud-conflict severity test (A3) before deciding whether it's worth de-sharing.
2. **Severity-tiering keeps it from over-firing (AHA/YAGNI guard).** The principle must *not* be read as "shard
   everything." Loud-conflict points are merely costly; many are fine as-is or need only union merge. Only
   silent-loss points are obligatory to fix. cli.py sits mostly in the "costly, optional" tier — which is the
   honest grounding for its priority (see A3). Whether to lift the principle into `.claude/rules` or an ADR is
   **out of scope here** — flagged for later, not authored.

### F. What to pull/measure before the design is settled
1. **Private-repo churn heatmap of cli.py** (`git log --format= --name-only` won't suffice — need per-region
   churn, e.g. `git log -p -- cli.py` bucketed by command) → target the split at the hot clusters (A4).
2. **A baseline for "is the split worth it over D5":** turn on `merge=union` mentally (or literally, briefly)
   and ask whether residual *edit*-collisions (not appends) still hurt enough to justify the refactor.
3. **Startup-latency budget:** time `jseval --help` today; it is the number any lazy-vs-eager registration
   decision must protect.
4. **The full command inventory as a golden artifact** (the 61 names) — needed for the surface-lock test
   regardless of which mechanism wins, so it can be produced *first*.

---

## Long-term design — settled (2026-06-29, general; not implementation-level)

The investigation found the deciding fact: **jseval already runs the exact pattern this problem needs**, in
its own `projections/` subsystem. The right long-term design is therefore **not** any of the §5/§B options as
a fresh mechanism — it is to **conform to the existing `projections/` registry seam**, extended with the one
guard the CLI case requires. This supersedes the open §5 options (A/B/C) and the §B alternatives; their fates
are recorded under "What this rejects" below.

### The existing seam to conform to (verified, in-package)
`jseval/projections/` solves "many derived units, each landing incrementally from a different tempdoc, all
dispatched from one place" without a monolith (`projections/__init__.py`, `projections/base.py`):
- **one module per unit**, each exporting a known module-level attribute (`PROJECTION`)
  (`projections/__init__.py:98`);
- **a tiny `base.py` registry** — `register()` (drops duplicates, `base.py:76-86`), `registry()`
  (`base.py:89-91`), `run_all()` dispatcher (`base.py:121`), `reset_registry_for_tests()` (`base.py:202`);
- **an explicit module-name list** + dynamic-import loop in `__init__.py`
  (`_PROJECTION_MODULE_NAMES`, `_import_registered_projections()`, `:46-100`) — *deliberately* an explicit
  list, not `walk_packages` (it gives a greppable inventory + tolerant incremental landing);
- **tested via `run_all_discovered`** (`tests/test_projections_bootstrap_ci.py:133-150`).

This is the house answer to 645's shape. The design is "do for `commands` what `projections` already does for
analyses."

### The design (five parts, each conforming to an existing substrate)
1. **`jseval/commands/` subpackage — one module per *cohesive command group*** (e.g. `commands/gate.py`,
   `commands/ui.py`, `commands/corpus.py`, `commands/run.py`). Granularity follows **cohesion + collision-heat**
   (Theorization A4/A5), not a uniform per-command rule: a hot, helper-heavy command (`run`, with its
   `_run_iteration`/`_reset_index`/`_do_run` cluster) earns its own module; cold cohesive families share one.
   Each module **exports its `click.Command` object(s)** — the direct analog of projections' `PROJECTION`
   export. The command *bodies move verbatim*; their private helpers (`_load_run_for_compare`, the `_print_*`
   cluster, `_step_surface_map`, …) **move with their group** (Investigation §3 established they already
   cluster cleanly).
2. **A small command registry** mirroring `projections/base.py`: `register`/`registry` plus a
   `register_all(group)` that imports each listed command module and `group.add_command(...)`s its exported
   commands. Keep the **explicit module-name list** (projections' deliberate, greppable choice) — but **without**
   projections' silent-missing tolerance (see guard #5: a CLI command must never silently vanish).
3. **`cli.py` shrinks to the anchor (~40–60 lines):** it keeps `main` (the `@click.group()` + the global
   `--verbose/--json` callback, `cli.py:21-38`), imports the registry, and calls `register_all(main)`. **`main`
   stays importable as `jseval.cli.main`** — preserving both entry points (`pyproject.toml [project.scripts]`
   **and** `__main__.py`, Investigation §3). The 3,575-line monolith becomes a thin composition root.
4. **Shared cross-group state → `jseval/commands/_common.py`** (or fold into `_paths`): the lone genuinely
   cross-cutting names — `_DEFAULT_BASE_URL` (`cli.py:18`) and its near-duplicate `_DEFAULT_BASE_URL_EVAL`
   (`cli.py:1987`, dedupe while here). **One-way dependency:** command modules → `_common`, **never** back to
   `cli.py` (forecloses the cli.py↔module cycle, Theorization D-circular-import).
5. **The one guard the CLI case adds beyond projections — a surface-lock test that *force-loads every
   command*.** Projections *tolerate* a missing/broken module (incremental landing); a shipped CLI must **not**.
   The test asserts (a) the registered command set **equals** the declared module-name inventory (no silent
   drop — the failure mode that most resembles the 635 untracked-module near-miss), and (b) **every command
   object loads** (materializing each module), so a broken command fails the build **loudly** — restoring the
   "import the CLI ⇒ all commands checked" property that any registry/lazy indirection would otherwise weaken
   (Theorization §D, the single most important correctness nuance). Produce the golden inventory **first**
   (Investigation §3 / Theorization F4) so the refactor is guarded *before* a single command moves.

**Startup latency is preserved by construction:** today cli.py defers heavy imports into command *bodies*
(`from . import ingest`, `import ir_datasets`, …; its 9 top-level imports are all light). The registry imports
only the **thin** command modules at startup; the heavy deps stay deferred in the bodies exactly as now. Lazy
*registration* (Click `LazyGroup`, §5-B) is therefore **not needed** for latency — adopt it only if a measured
`jseval --help` regression appears (Theorization F3), not pre-emptively.

### What this rejects, and why (matching scope to the problem)
- **§5-B (hand-maintained `LazyGroup` map) and §5-A (hand `add_command`) as the *terminal* design** — both leave
  a hand-edited shared list as the only registry; acceptable as the residual (the projections tuple is exactly
  this), but on their own they don't conform to the in-package registry idiom or carry the load-all guard.
  Subsumed: the chosen registry *is* a curated list + dynamic import, i.e. the projections-grade form of B.
- **§5-C (runtime `walk_packages` auto-discovery)** — *rejected as a parallel mechanism.* There is **no**
  runtime package-walk discovery anywhere in the repo (verified: the only dynamic import in `scripts/**/*.py`
  is projections' *explicit-list* loop). It also worsens failure modes (untracked file ⇒ silently vanished
  command — the near-miss class) and drops the greppable inventory. "Conform rather than invent a parallel
  version" → use the explicit-list registry the package already uses.
- **§B-D6 (external codegen of the registrar)** — *rejected as over-reach for this problem.* Codegen+`--check`
  is the house seam for *generated text artifacts* (`gen-token-names`, `skills-sync`), but the registry here is
  *live Python*, and projections proves a hand-curated module-list is the accepted terminal form for this exact
  shape. A generator would add a build step + a CI gate to remove a *one-line, loud, trivially-merged* tuple
  conflict the problem does not require removing.
- **§B-D5 (`merge=union` on cli.py)** — *rejected as a non-fix:* it manages the symptom (merge text) without
  de-monolithing; unsafe for edits-to-existing-commands. (Could be a 1-line interim *until* this lands, nothing
  more.)
- **§B-D7 (collapse `*-gate`/`*-eval` into generic dispatchers)** — *deferred, not rejected:* it reduces command
  *count* rather than relocating bodies, changes the public surface, and is a separate concern. Noted as the
  longer-horizon direction; out of scope for "split the monolith."

### Migration shape (recommendation, execution detail)
**One-shot, in a coordinated quiet window.** The migration is itself the single largest cli.py edit possible
(Theorization §D) — incremental keeps cli.py hot and re-touches the registrar each step, the opposite of the
goal while underway. Land the golden-inventory guard first, then move all groups in one change. Sequencing
specifics are execution, not design.

---

## Reach — principle, scope, and what already (doesn't) violate it

**Principle (named plainly):** *a unit set that many contributors extend incrementally should live as
**one-module-per-unit self-registering shards** assembled by a small registry — never as a single hand-edited
monolith.* This is the **product-artifact slice of 618 Seam C's `isolate-and-reconcile` / single-writer-shard
invariant** (618:1267-1301): 618 covered the *agent-session-state* slice (per-session inbox shards folded at
merge); `projections/` already instantiates the *product-unit* slice (per-unit modules assembled by a registry
at import); **645 is a second instance of that same product-unit slice**, on the CLI.

**This is not a new principle and earns no new shared structure** — exactly the posture 618 modelled. Two
sharp distinctions keep the abstraction from being premature:
- **645 is *not* the "third append-target" trigger 618 was watching for** (618:1297-1301). That trigger was a
  shared **per-session append** whose fix shape matches Seam C's *shard-then-merge-fold* — which would justify
  lifting Seam C into a shared inbox helper. 645's shards are **permanent product modules**, reconciled by a
  **registry at import**, not session notes folded at **merge teardown** — different lifetime, different
  reconcile boundary. So 645 correctly **does not** trigger building a generalized agent-state engine; it
  conforms to the *projections* seam instead.
- **The fix is conformance to an existing in-package seam, not a generalization across seams.** A unifying
  "convergent-registry framework" over projections + commands + the governance registers is the premature
  abstraction to avoid (622's "generalize only on a second matching instance, and only if the fix shape is
  identical" — and across these surfaces the consumers/validation differ).

**Where else the principle applies (candidate scope — RECORD, do not build):**
| Surface | Convergent edit point | Status vs. principle |
|---|---|---|
| `governance/registry.v1.json`, `execution-surfaces.v1.json`, `logic-seams.v1.json` | one shared JSON list everyone appends a gate/surface/seam to | structurally convergent, but **loud** (git-conflict) one-line appends — *costly-optional*, not a hazard; already near the projections-grade residual |
| `.claude/rules/tier-register.md` rows | one shared table everyone adds a rule row to | same — loud, one-row append; the `prose-tier-register` gate already governs *consistency*, accepting the shared file |
| `settings.gradle.kts` module includes | one shared include list | loud, rare, tiny — not worth de-sharing |
| `projections/_PROJECTION_MODULE_NAMES` itself | one-line tuple per projection | **the reference instance** — the house already accepts this as the terminal form |

**Severity tier is the guard against over-firing (Theorization A3):** only **silent-loss** convergent points are
*obligatory* to shard (618 Seam C handled the one such case — observations.md). Everything in the table loses
**loudly** (git refuses the merge), so it is *cost*, not *hazard* — leave it, or shrink it to a one-line
registry append if a monolith forms, but do **not** build machinery to eliminate loud one-line conflicts. cli.py
qualifies for the fix **because it is a 3.5k-line monolith** (high friction + navigability + ownership cost),
**not** because it loses silently — it doesn't. That keeps 645 honest: it's a friction/structure fix conforming
to a proven seam, not a hazard fix, and it builds **no** generalized structure — one `commands/` package, one
small registry, one guard test.

---

## De-risk pass before implementation (2026-06-29)

> Read-only investigation + non-mutating experiments (timing, a full test run) pressure-testing the design's
> load-bearing assumptions. **No feature work** — `cli.py` untouched, no `commands/` package created. Probes
> run in `scripts/jseval/`. Outcome: **5 HOLD, 1 REFINES-DESIGN, 0 BLOCKS** — the design is sound; one concrete
> refinement (a second `_common` member) and the guard-shape is confirmed runnable.

- **P4 golden inventory — HOLDS (artifact produced).** `python -c "from jseval.cli import main;
  print(sorted(main.commands))"` → **exactly 61** commands, and the import **constructs all 61 with zero
  optional deps installed** (decorators are dependency-free). The sorted 61-name list is the golden artifact the
  surface-lock test will lock to (produced *first*, per plan). Includes the 2 registry-based ones (`qu-spike`,
  `qu-v3`) — confirming the registry path and the inline path coexist in one `main.commands`.
- **P1 startup latency — HOLDS.** `python -m jseval --help` = **~0.170 s** (3 runs: .179/.170/.170), of which
  `import jseval.cli` alone is **0.167 s** — i.e. ~all of it is import, and that import already eager-loads the
  `qu_spike`/`qu_v3` command modules, which stays cheap. The **only** heavy import in cli.py (`ir_datasets`,
  `cli.py:1433`) is **in-body** (inside `cmd_materialize`), not module-top. **Conclusion:** splitting into
  thin per-group wrapper modules that keep delegated imports in-body preserves the ~170 ms budget; Click
  `LazyGroup` is **not needed** for latency (adopt only if a measured regression appears).
- **P2 load-all guard runnability — HOLDS (highest-risk item cleared).** The guard force-loads the *wrapper*
  command modules, which (by the in-body-delegation rule) never trigger heavy/optional imports. Verified: the
  sole delegated module that top-imports an optional dep is `agent_utility_inspect.py` (`inspect_ai`,
  `:34-37`), and it is reached **only via an in-body** `from .. import agent_utility_inspect` — so loading the
  `commands/utility.py` wrapper stays clean. Since P4 already proved importing all commands needs no extras, a
  **single base-env load-all test is runnable as-is** — no need for the fallback "assert-by-name + skip body"
  shape the plan held in reserve.
- **P3 helper call-graph — REFINES-DESIGN (the one real find).** Helpers cluster group-locally **except one
  genuine cross-group helper**: `_write_bench_output` is called by **both** the `ui` group (`ui-perf`,
  `ui-check`) **and** the `bench` group (`llm-bench`, `ingest-bench`, `engine-bench`, `knn-bench`) — 8 sites.
  → **`_common.py` must hold `_write_bench_output`** in addition to the expected `_DEFAULT_BASE_URL` (15 sites,
  global) and `_DEFAULT_BASE_URL_EVAL` (`retrieval-eval`/`tier2-eval`; consolidate the near-dup). Everything
  else is local to a natural group: the `run` cluster (`_run_iteration`/`_reset_index`/`_check_build_freshness`/
  `_do_run`) is run-only; `_load_run_for_compare`/`_first_mode` are shared by `compare`+`trend` (same group);
  `_print_summary` by `run`+`requery` (same group); the `_print_*`/`_compare_by_decision_kind` set is
  compare-local; `_step_surface_map` is ui-local. **So the grouping is cohesion-clean with exactly one named
  exception now pinned down — no longer an open assumption.**
- **P5 lint gate + test baseline — HOLDS (lower constraint than feared).** **No Python linter/formatter gate
  exists** (no `.flake8`/`ruff`/`black`/`setup.cfg`; pyproject has only `[tool.setuptools.packages.find]` +
  `[tool.pytest.ini_options]`; the `# noqa: E402` is defensive, unenforced). Moved files need only be valid
  Python + match surrounding style + pass pytest. **Baseline: 1010 passed, 2 failed in 163 s** — both failures
  are `test_correction_probe.py` hitting a **missing data fixture** (`correction-eval-queries.v1.json`,
  untracked/absent in the public mirror), **environmental and unrelated to CLI structure**; `test_cli.py` and
  all command-surface tests are **green**. (Logged to the inbox as a pre-existing env-only failure.)
- **P6 packaging discovery — HOLDS.** `jseval.projections` is an existing `__init__.py` subpackage that imports
  cleanly under the installed `jseval 0.1.0`, so `setuptools packages.find where=["."]` will discover a new
  `jseval.commands` subpackage identically — no packaging change needed.

**Net:** the only design delta is "`_common.py` also holds `_write_bench_output`." The guard is runnable in the
base env (the biggest worry, cleared). Startup, packaging, lint, and inventory all hold. Residual risk is now
almost entirely *mechanical execution* (moving 61 commands + their helper clusters verbatim without a dropped
import) — which the load-all guard + the green `test_cli.py` baseline are precisely positioned to catch.
**Confidence for the remaining implementation: ~8.5/10.** Still genuinely unknown (and non-blocking): the
collision-heat weighting for granularity (private-repo churn data, plan F1) — a refinement to the cut lines,
not a correctness risk.

---

## As-built (IMPLEMENTED 2026-06-30, worktree `worktree-jseval-cli-split`)

The split landed exactly to the settled design — conforming to the `jseval.projections` self-registering-module
seam, no new mechanism. Done in an isolated worktree; **merge to `main` is pending user direction**.

### What shipped
- **`jseval/cli.py`: 3,575 → 36 lines.** Now just the module docstring, the `@click.group() main` + global
  `--verbose/--json` callback (verbatim), and `from .commands import register_all; register_all(main)`. `main`
  stays importable as `jseval.cli.main` — both entry points (`pyproject [project.scripts]` + `__main__.py`)
  preserved.
- **New `jseval/commands/` package** (11 group modules + `_common.py` + registry `__init__.py`):
  `run` (2 cmds) · `analysis` (8) · `bench` (5) · `gates` (9) · `corpus` (4) · `ui` (7) · `eval_cmds` (6) ·
  `utility` (5) · `calibrate` (3) · `release` (2) · `ops` (8) = **59**; plus the 2 pre-existing legacy modules
  `qu_spike`/`qu_v3_eval` (got a `COMMANDS = [...]` list) registered through the same registry = **61 total**.
- **`commands/__init__.py`** — `_GROUP_MODULES` tuple + a registry **mirroring `projections/base.py`**:
  `register(cmd)` / `registry()` / `reset_registry_for_tests()`, and `register_all(group)` which registers each
  module's `COMMANDS` **and** `group.add_command()`s it. Import errors **propagate** (a shipped command never
  silently vanishes — the deliberate divergence from projections' missing-module tolerance), and a **duplicate
  command name raises `ValueError`** (Click's `add_command` would otherwise silently overwrite — stricter than
  projections' last-wins, justified for a shipped CLI).
- **`commands/_common.py`** — the cross-cutting members: `_DEFAULT_BASE_URL`, `_DEFAULT_BASE_URL_EVAL`, and
  `_write_bench_output`. The two base URLs are **kept separate by design, NOT a dedupe target** (correcting the
  design's casual "dedupe while here"): `_DEFAULT_BASE_URL` honors `JUSTSEARCH_API_PORT` (a possibly-relocated
  main API) while `_DEFAULT_BASE_URL_EVAL` is hardcoded `33221` because the eval backend `start_backend` binds
  `33221` hardcoded (`backend.py:19,37`) — **merging either direction reintroduces the tempdoc-635-D3
  port-divergence bug**. The **data-driven helper routing confirmed P3 and caught one more**:
  `_write_bench_output` is used by `bench` **and** `eval_cmds` **and** `ui` → `_common` (P3 had named bench+ui;
  the router added eval_cmds/`rag-eval`). All other 12 helpers cluster to a single group module.
- **Mechanical transform:** every in-body relative import shifted one level (`from . import x` →
  `from .. import x`, `from ._paths` → `from .._paths`, …) since modules are now one package deeper; heavy
  imports stayed **in-body** (startup preserved).
- **New guard `tests/test_command_surface.py`** — locks `set(main.commands) == <golden 61>`, asserts each is a
  well-formed `click.Command`, **force-loads every command module** (restores "import the CLI ⇒ all commands
  checked"), and (post-review) asserts `set(registry()) == golden` + that `register` rejects a duplicate name.
- **New `tests/test_common.py`** — locks `_write_bench_output` writing the JSON + running its `click.echo` path
  (see post-review fix below).
- **External refs updated:** `tests/test_cli.py` import of the moved `_compare_by_decision_kind` →
  `jseval.commands.analysis`; 4 docstring `:func:`jseval.cli.cmd_*_gate`` cross-refs → `jseval.commands.gates`.

### Post-review fixes (2026-06-30, after the conceptual-alignment pass)
A design-alignment review of the split surfaced two deviations and, investigating them, a **third real bug**.
All three resolved:
1. **REAL BUG fixed — `_common.py` was missing `import click`.** `_write_bench_output` calls `click.echo` but
   the hand-built `_common.py` codepath imported only `json/logging/os/Path` → latent `NameError` whenever a
   bench/ui/eval command ran **with `--output-dir`** (the early-return path is why the suite stayed green). Added
   `import click`; locked by the new `tests/test_common.py` write-path test.
2. **Registry now mirrors `projections` `register`/`registry`** (design Part 2) — added with **duplicate-name
   detection** (raises, vs Click's silent overwrite); locked by a new surface test.
3. **`_DEFAULT_BASE_URL_EVAL` confirmed NOT a dedupe target** — merging would reintroduce the 635-D3
   port-divergence bug (eval backend hardcodes `33221`); the design's "dedupe while here" was mis-aimed. Kept
   separate with an explanatory comment in `_common.py`; the earlier "consolidate" follow-up is **withdrawn**.

### Validation (all green)
- **Command surface byte-identical:** the registered 61-name set `diff`s clean against the pre-split golden
  list; `python -m jseval --help` and `{run,gate,ui-shot,corpus-fidelity,compare,bench-concurrency,utility-run}
  --help` are **byte-identical** to the captured baselines.
- **Startup preserved:** `jseval --help` ~0.17–0.19 s (baseline ~0.17 s).
- **No new test failures:** full suite **1017 passed, 2 failed** (after the post-review fixes) — the 2 are the
  same pre-existing `test_correction_probe` env failures (missing public-mirror data fixture); the +7 vs the
  1010 baseline are the new surface + `_common` tests. `test_cli.py` green.
- **Runtime import sweep:** no unshifted `from .` remains in `commands/*.py` (except the legit `._common`); real
  body-executing commands (`datasets`, `modes`, `preflight`, `search`, `logs`) run with only network errors, no
  `ImportError`. Not a user-visible feature → **no browser validation applies** (public CLI surface unchanged,
  proven by the golden-inventory + `--help` parity).

### Notes for merge
- **Doc-reconciliation caveat:** this tempdoc's analysis (Investigation→De-risk) was authored as *uncommitted*
  edits in the main checkout (on branch `codex/unit-test-evidence-contract`) before the worktree existed; the
  worktree now carries the **complete** doc (analysis + this As-built). At merge, the worktree's 645 is the
  authority — discard/ignore the main checkout's older uncommitted 645 copy.
- Follow-ups logged, not done here: the granularity heat-weighting (private-repo churn); the pre-existing
  `σ`-in-`recalibrate`-help vs cp1252-console `--help` crash (pre-existing, unrelated to this split). *(The
  earlier "consolidate `_DEFAULT_BASE_URL_EVAL`" follow-up is withdrawn — see Post-review fix #3: merging is a
  bug, not a cleanup.)*

---

## Opportunity space — what the registry seam unlocks (research/ideation, 2026-06-30)

> **Ideation only — nothing committed.** A research pass (Click docs + CLI-for-agents ecosystem) on what the
> `commands/` registry now makes *cheap*. The app has no users and there's no rush; these are options ranked by
> value × low-effort, not a plan. The unifying lens: **jseval is an agent-operated CLI** (the `/jseval` skill +
> the product's MCP server drive it), so "UX" here means **agent + developer ergonomics**, and the registry is
> the natural engine for *self-discovery* and *safety*. The split is the enabler — before it, the command set
> was opaque text in a 3.5k monolith; now `registry()` is a queryable, group-aware source of truth.

### The framing — why the seam matters now
The registry (`jseval.commands.registry()`) is a single, queryable map of every command **plus its group**. Most
ideas below are "read the registry + a thin Click extension." The strongest are *agent-facing*: the
CLI-for-agents literature converges on **make the CLI its own canonical, machine-readable documentation** (so an
agent discovers commands programmatically instead of burning tokens reading prose), plus **safety rails for
mutations** — both of which the registry now makes near-free.

### A. Flagship (high value × low effort, directly split-enabled)
1. **Machine-readable command catalog — `jseval commands [--json]`.** A new list command (sibling of the existing
   `datasets`/`modes`) that emits every command, its **group**, summary, and options. Click ships
   `Command.to_info_dict()` (since 8.0) which dumps the whole tree; the registry adds the group/owner. **Why it
   matters:** agent self-discovery without reading the skill or the 3.5k of source — the #1 agent-CLI principle.
   It also becomes the source for auto-generated reference docs and can *replace the hand-maintained 61-name
   golden list* in the surface test (see B5).
2. **Categorized `--help`.** 61 flat commands are hard to scan. The registry already knows each command's family,
   so a custom `Group.format_commands` (zero-dep) — or `rich-click` `COMMAND_GROUPS` panels — renders help in
   labelled sections (`run`, `gates`, `corpus`, `ui`, …) that mirror the source modules **for free**. Pure
   presentation; the flat `python -m jseval <cmd>` surface is untouched (keeps the 645 invariant). Note: this
   gives the *grouping* benefit of sub-groups **without** the nesting cost (Click nesting hits ~150 ms at depth
   5; flat-with-sections stays fast).
3. **Static-analysis gate over `scripts/jseval`** (ruff/pyflakes, `--check`). The post-review `_common`-missing-
   `import click` bug was a runtime-silent undefined-name error that *no test caught* and a linter flags at
   ~100%. P5 found **no** Python lint gate exists. Adding one is the repo's own "hooks/gates enforce at 100% vs
   ~70% prose" ethos, and it closes the exact bug class the split risked. (Independent of the registry, but
   surfaced by this work.)

### B. Extend the CLI on the registry (medium value)
4. **Per-command metadata in the registry** — carry `{group, aliases, deprecated, mutating?/agent-safe?}` per
   command (a thin wrapper or sidecar dict). This is the substrate the other ideas read; *born in the registry*,
   not retrofitted.
5. **Auto-synced golden inventory.** Today the surface test hard-codes the 61 names. A tiny generator (the house
   `*-sync --check` pattern: `skills-sync`, `gen-token-names`) could regenerate `EXPECTED` from `registry()` and
   fail on drift — explicit list **and** auto-maintained, removing a hand-edit when a command lands.
6. **Typo suggestions + unique-prefix aliases.** `jseval corpus-fidelty` → "did you mean corpus-fidelity?"
   (`click-didyoumean` / difflib) and `jseval corp…` unique-prefix resolution (Click's `AliasedGroup` recipe).
   Cheap ergonomics that scale with 61 commands.
7. **Shell completion** (Click built-in, bash/zsh/fish) — dev ergonomics; one-line enablement.

### C. Agent-safety & output conventions (repo-specific, from the agent-CLI lens)
8. **`--dry-run` for mutating commands.** Several commands mutate state (`run --clean/--reset`, `corpus-build`,
   index resets). The agent-CLI guidance is "always offer `--dry-run` for mutations." The registry's `mutating?`
   metadata (B4) is exactly the place to enforce/audit this convention.
9. **`--json` coverage audit.** A global `--json` already exists (`cli.py main` sets `ctx.obj["json"]`), but
   coverage across 61 commands is uneven. The catalog (A1) makes it auditable: assert every command honors it →
   structured output is the agent default, terminal formatting the exception.
10. **Auto-generated command reference** (from `to_info_dict`, e.g. `sphinx-click`) feeding the `/jseval` skill +
    docs, so the agent-facing docs never drift from the registry.

### D. Principle / reach (record, do not build)
The registry is the **product-unit slice of the convergent-edit-point / `isolate-and-reconcile` principle**
(618 Seam C; the `projections/` seam) — already named in *Reach*. The A1+A2 pieces (catalog + categorized help
derived from a group-aware registry) hint at a small reusable "command-kit," but per the same discipline:
**name the pattern, don't build the framework** until a second consumer needs it. No generalized structure is
warranted by the present problem; these stay options.

### Honest ranking
- **Do-first if revisited:** A1 (JSON catalog) and A2 (categorized help) — highest agent+dev payoff per line,
  both pure reads over the registry; A3 (lint gate) — cheapest insurance against a proven bug class.
- **Then:** B5 (auto-synced inventory), B6 (did-you-mean), C8 (`--dry-run` convention).
- **Nice-to-have:** B7 (completion), C9 (`--json` audit), C10 (auto-docs).
- All are independent, incremental, and reversible; none touches the public command surface (the 645 invariant).

---

## Long-term design for the opportunity space — settled (2026-06-30, general; not implementation-level)

Stepping back from the 10 ideas: they are **not 10 features** — they are **one architectural shape**. Every one
(categorized help, the JSON catalog, the auto-synced golden inventory, the `/jseval` skill command list, the
auto-docs, did-you-mean, completion) is a **derived representation of the command surface**. The correct
long-term design is therefore not "build 10 things" but a single invariant:

> **The command registry is the one canonical source of the command surface; every derived view is a
> *projection* of it, never a hand-maintained fork.**

This is **not a new idea** — it is the repo's own **canonical-authority-and-projection / projection-vs-fork
seam** (622/623/625; the `execution-surfaces.v1.json` register for `SearchTrace`/`ContextCitation`, 553/559;
applied reflexively to tempdocs in 646; to the public narrative in 650) **applied to the CLI command surface.**
Conform to it; do not invent a parallel CLI-metadata mechanism.

### The canonical source, and the one missing piece of substrate
The canonical source is **the registry + Click's command tree**: `jseval.commands.registry()` (names → commands)
composed with Click's built-in `Command.to_info_dict()` (options, help, types). Together they already describe
*almost* everything the projections need — **except one fact the registry currently discards: each command's
group/family.** `register_all` iterates the group modules but flattens their `COMMANDS` into a name-keyed map,
losing which module each came from (`commands/__init__.py`). So the **single necessary substrate change** is
**group-aware registration** — `register` records each command's owning group, making `registry()` return
*name → (command, group)*. That one addition turns the registry into a *projectable* source; every projection
then reads it:

| Projection (a derived view) | Projects from | Replaces today's fork |
|---|---|---|
| Categorized `--help` (sections by family) | registry group + Click help formatter | (none yet — flat help) |
| `jseval commands --json` catalog | registry group + `to_info_dict()` | (none yet) |
| Surface-lock golden inventory | `registry()` keys | the **hand-typed 61-name list** in `test_command_surface.py` |
| `/jseval` skill + docs command list | the catalog, via the `skills-sync` codegen `--check` family | the **hand-enumerated commands in `SKILL.md`** (a live, drift-prone fork) |
| did-you-mean / completion | registry name set | (none yet) |

**No projection re-derives group membership or bolts on its own parallel map** — that re-derivation *is* the fork
the seam forbids (CLAUDE.md "projection vs fork": *before authoring a new representation of existing data, check
projection vs fork*).

### Scope discipline — conform to 646's posture: settle the principle, defer the machinery
Matching the design scope to the problem the tempdoc *actually* has: **none of the opportunity ideas is a
committed requirement** (the app has no users; 645's own problem — the monolith — is solved). So building the
group-aware substrate or any projection **now** would be adding structure for a case the problem does not yet
include. The disciplined posture is **646's exactly**: *principle settled, machinery deferred to the
rule-of-three / first-consumer trigger.*

- **Recorded, not built:** the group-aware registry + the projections above. They are the *correct* shape when
  pursued — recorded here so that whoever builds the first one **conforms** (reads the registry; does not fork).
- **The trigger:** when the **first** projection is actually wanted (most likely A2 categorized help or A1 the
  JSON catalog — both need the group), *that* is when group-aware registration lands, **sized to that one
  consumer** (just the group; not speculative `mutating?`/`deprecated`/`alias` fields — those arrive only with
  *their* first consumer, e.g. C8 `--dry-run`).
- **The one existing drift to watch (not fix now):** the `/jseval` `SKILL.md` already hand-lists commands — a
  live fork of the registry. It is **loud and low-frequency** (a stale skill is visible, not a silent
  data-loss), so per the severity tier it is *cost, not hazard*; the conforming fix is to make it a
  `skills-sync`-style projection of the catalog **when a drift actually bites**, not pre-emptively.

This keeps the design **minimal and correct**: one substrate fact (group), everything else a projection, nothing
built until a consumer earns it.

## Reach — principle, scope, what already (mildly) violates it

**Principle (named plainly):** *derived representations of a canonical set are **projections** of the one
source, never independently-maintained forks.* This is the **read-side complement** of 645's own write-side
result:

- **645 Part 1 (shipped) solved write-side convergence** — command *authoring* converges into per-group shards
  assembled by one registry (no monolith to collide on). The *convergent-edit-point / single-writer-shard*
  principle (618 Seam C / `projections/`).
- **This design settles read-side convergence** — command *derived views* converge as projections of that
  registry (no forked help/catalog/doc/inventory maps). The *canonical-authority-and-projection /
  projection-vs-fork* principle (553/559, 622/623/625, 646, 650).

They are **two faces of one invariant — "one canonical source for the command surface"** — write and read.
Recognizing this is the whole insight: the registry the split created is not just a *write* convergence point,
it is the *canonical source* that read-side projections must derive from.

**This is an existing seam, so conform — build no parallel version.** The repo already runs the
projection-vs-fork discipline as a *gate* for `SearchTrace` (`execution-surfaces.v1.json`). The CLI command
surface is a **new candidate member of that same discipline**, not a new mechanism.

**Candidate scope — where the read-side principle applies (RECORD, do not build):**
| Surface | Canonical source it should project | Status |
|---|---|---|
| `/jseval` `SKILL.md` command list | the command registry / catalog | **live mild fork** — drift-prone; conforming fix = `skills-sync` projection *when it bites* |
| `test_command_surface.py` golden 61 names | `registry()` keys | **mild fork** — loud/low-risk; auto-sync (B5) is the conforming fix |
| Any future doc enumerating commands/flags | the catalog | candidate — born as a projection, not retrofitted |
| (already conforming) `execution-surfaces.v1.json` → `SearchTrace` | the value model | the **reference** instance of this seam |

**Severity tier keeps it from over-firing (the 645-Reach guard, reaffirmed):** these forks lose **loudly** (a
stale skill/test is visible), so they are *cost, not hazard* — fix by projection when a drift actually costs
something, never by pre-building a generalized "CLI-metadata framework." Only a *silent* drift would oblige an
immediate fix; none here is silent. **Recognizing the principle ≠ building the structure** — recorded so the
insight is not lost, deferred so it does not become premature abstraction (the same separation 646 makes).

---

## De-risk pass on the deferred design (2026-06-30, read-only + throwaway prototypes)

> Pressure-tested the design's load-bearing **technical** assumptions before any consumer is built — pure
> introspection + scratchpad prototypes, **no jseval feature code written**. Outcome: **6 HOLD, 1 REFINES, 0
> BLOCKS** — the design is technically sound; one idea (C9) is rescoped.

- **P1 `to_info_dict()` (flagship A1 catalog) — HOLDS strongly.** `main.to_info_dict()` returns the full tree:
  all 61 commands, each param carrying `opts`/`type`/`help`/`default`/`required`/`hidden`/`is_flag`/`multiple`/
  `envvar` (e.g. `run` has 30 params dumped cleanly). The agent JSON catalog is genuinely near-free; the **only**
  thing `to_info_dict` lacks is the **group** — exactly the one fact the group-aware registry supplies.
  Confirms both the feasibility *and* the necessity of the single substrate change.
- **P2 categorized help (A2) — HOLDS.** A throwaway zero-dep `click.Group` subclass overriding `format_commands`
  to bucket commands by a name→group map rendered sectioned `--help` correctly (~25 LOC, no new dependency).
  Integration is a one-line `cls=CategorizedGroup, command_groups=<registry group map>` on `main`. (It changes
  `--help` *text* only — the surface test locks the command *set*, not help bytes, so no test breaks.)
- **P3 group-attach + legacy edge — HOLDS (one decision pinned).** The module→command map is clean and already
  in `register_all`'s loop, so recording group is trivial. The one glossed edge: the 2 `_LEGACY_MODULES`
  commands (`qu-spike`/`qu-v3`) have no group module → assign an explicit label (e.g. `query-understanding`)
  rather than letting them fall to "Other".
- **P4 `--json` coverage (C9) — REFINES (rescope).** Only ~2 of the 61 command modules reference the global
  `ctx.obj["json"]`; most commands either emit JSON unconditionally (gates/eval) or are human-only. So the
  global `--json` flag is **nearly vestigial**, and C9 is **not a small audit** — it is a meaningful retrofit
  (make every command honor one output contract). **Rescope C9 down**: the agent-output story is better served
  by the **catalog (A1)** + per-command output *shape* consistency than by forcing the global flag everywhere.
  Lower its priority accordingly.
- **P5 latent-bug sweep (A3) — HOLDS.** The validated AST undefined-name checker over the **whole** jseval
  package finds **zero** real undefined-name bugs — every report is a comprehension/lambda target (`k`,`v`,`fam`,
  `f`,`run_dir`), a known checker blind spot, not a defect. So the `_common` `import click` bug was genuinely
  unique; **A3 (a lint gate) is purely *preventive*, not clearing a hidden backlog** (no adoption-blocking
  baseline of pre-existing issues). `ruff`/`pyflakes` aren't installed locally — a real gate would add the tool.
- **P6 skills-sync projection — HOLDS (refined fix shape).** `/jseval/SKILL.md` already has a
  `<!-- generated:start/end -->` region (L29–354) driven by `skills-sync`, and the command examples live
  *inside* it — i.e. the skill is already a **doc-projection**, not raw prose. So the conforming fix for the
  skill "fork" is one level removed: feed the **catalog (A1)** into the skills-sync source chain
  (registry → catalog → doc → skill), not hand-edit the skill. Mechanism confirmed available + small.
- **P7 conformance precision — HOLDS.** `execution-surfaces.v1.json` + its gate are **Java/TS-only** (27 `.ts` /
  23 `.java` refs; the 3 `.py` are the import-invisible declared-surface note). The CLI is **not** in that gate
  and must not be wired into it — the design correctly records **principle-level** conformance (same
  projection-vs-fork discipline), not gate membership. No over-claim.

**Net:** every technical assumption under the flagship pieces (A1 catalog, A2 categorized help, A3 lint gate,
group-aware registry substrate) is **experiment-verified**; the only correction is **C9 rescoped down** (global
`--json` is sparse, so deprioritize forcing it). No blocker. The remaining uncertainty is **product-level** —
*which* projection is worth building first — not technical.

**Critical confidence for the deferred remaining work: ~9/10** for the flagship trio (A1/A2/A3 — mechanisms
proven, substrate trivial, edge case pinned), **~7/10** overall (the softer ideas — C8 `--dry-run`, C9 `--json`,
B6/B7 ergonomics — are larger or vaguer than the design's one-line framing implied; none is committed, and the
"first-consumer trigger" means each gets sized when actually pursued).

---

## As-built — opportunity space, first wave SHIPPED (2026-06-30, worktree `worktree-jseval-cli-split`)

The user electing to build tripped the first-consumer trigger. Shipped the **verified core + cheap wins** as
projections of a now-**group-aware registry** — no forks, no new framework. (Merge pending user direction.)

### Built (all green; startup unchanged ~0.18 s; full suite 1021 passed / 2 pre-existing env fails)
- **Group-aware registry (the one substrate).** `commands/__init__.py`: `register(command, group)`; `registry()`
  returns `name → CommandInfo(command, group)`; new `command_groups()` + `GROUP_ORDER` are the **single source**
  every projection reads. `register_all` records each module's group; the 2 legacy commands get the pinned
  `query-understanding` label.
- **A2 categorized `--help` + B6 did-you-mean** — one `JsevalGroup(click.Group)` (in `commands/__init__.py`,
  adopted via `@click.group(cls=JsevalGroup)` in `cli.py`). `format_commands` renders sections by group in
  `GROUP_ORDER`; `resolve_command` appends a `difflib` "Did you mean: …?" on a miss. Zero new deps. Projects
  `command_groups()` — does not fork it.
- **A1 `jseval commands [--json] [--group X]` catalog** — new command in `commands/ops.py` (sibling of
  `datasets`/`modes`), projecting `main.to_info_dict()` × `command_groups()`. Human = grouped name+summary;
  `--json` = per-command options (opts/type/required/is_flag/default/help) + group; `--group` filters. The
  agent self-discovery surface (CLI-as-canonical-docs). Surface is now **62** commands (added `commands`).
- **B5 auto-synced inventory (de-fork).** `commands/inventory.py` + committed `inventory.generated.json`
  (projected `[{name, group}]`, `--write`/`--check` per the `gen-token-names` family). `test_command_surface.py`
  now **locks against the committed inventory** (drift fails until `inventory --write` is run + committed) —
  removed the hand-typed 61-name list (a fork). Positive + negative checks pass.
- **A3 undefined-name guard (prevent the escaped bug class).** `tests/test_no_undefined_names.py` — a zero-dep
  AST check (a name Loaded but bound nowhere + not a builtin → fail), with a self-test proving it bites. Sweeps
  the **whole** jseval package: clean. Catches the `_common`-`import click` class no test caught before. (CI
  wiring optional — public CI runs eval lanes, not pytest; local-first is primary.)

### Deferred (recorded, with reasons — not silently dropped)
- **C9 `--json` everywhere** — rescoped down (global flag ~2/61, near-vestigial); the catalog serves agents
  better. **C8 `--dry-run`** — needs a per-command `mutating?` metadata slot; add it *with its own consumer*,
  not speculatively. **B7 completion** — Click built-in; a one-line skill doc, not code. **C10 auto-docs / the
  `/jseval` SKILL.md fork** — now cheap (feed the catalog into the existing `skills-sync` generated region,
  L29–354); a natural fast-follow, deferred so this wave stays scoped.

### Validation
`jseval --help` (sectioned, 62 cmds) · `jseval <typo>` (suggestion) · `jseval commands [--json|--group]` ·
`inventory --check` OK · surface-lock + negative-drop check · `test_no_undefined_names` (self-test + package) ·
full `pytest` 1021 passed / 2 pre-existing env fails. Not a web feature → no browser validation (CLI surface,
additive: one new `commands` command; the 645 flat-surface invariant holds).
