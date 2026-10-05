<!-- Agent System entry, core 0.8.1, project justsearch; owned by the core. Source: SYSTEM. -->
# Working agreement

Entry canary: OPUS-LEAN-ENTRY-1.

This is the project's only agent layer. The developer owns these rules; project knowledge and preferences may specialise, never weaken them. All but the invariants are defaults: depart only with a reason in the task record.

## Start, takeover, core change

1. Launched by `agentsys build`/`review` or as a builder subagent: follow your role contract; skip these steps and orchestration below. Orchestrator: run `python "$AGENTSYS_TOOL" check`. Unless `pass`, stop dependent work and report the failure and fix. If `AGENTSYS_TOOL` is unset, this folder is not set up here: say so, ask the user to run the installed core's `agentsys refresh` or start `claude` in the project root, and stop.
2. Run `python "$AGENTSYS_TOOL" selfcheck`, again at each stage boundary. On a material model mismatch, tell the user and stop. For compaction and when to rotate, see `guides/takeover.md`.
3. `python "$AGENTSYS_TOOL" locate` gives guides, knowledge, task records and preferences; private records never go into this repository.
4. Read the project goals and applicable preferences. Search the task index; one record per outcome.

## Invariants

1. The builder is never the only judge of its work. Above the depth floor, the orchestrator does not build.
2. Above the floor, agree scenarios, implied cases as well as stated ones, before dependent implementation.
3. The verifier receives the user's intent, both scenario lists, the exact candidate, the environment and the applicable rules. Never the builder's rationale, self-report or conversation, and never the whole task record.
4. A check of new or repaired behavior fails before the change, for the reason under test, and passes on a known-good version where one exists. Preservation checks pass before and after. A skipped, unavailable or merely configured check is not a pass.
5. A reported boundary failure is verified on the real interface and the running system where it occurs.
6. Proof links every agreed scenario to evidence or marks it unverified.
7. Every delivery asks for the user's verdict on behavior. Silence is not acceptance.

## Depth: decide before editing or delegating

Inspect the affected experience, callers, state owners and checks; read `guides/depth.md`. A task is **above the floor** if any holds:

- **Boundary failure:** a reported wrong result crosses an execution or state boundary, or differs between paths such as live and reloaded, or first and later processing. The root cause need not be known.
- **Saved state:** it alters the meaning, defaults, identity, ownership, retention or recovery of data the product or its operation keeps between runs. Code, instructions and configuration count only through that behavior.
- **Transition:** it changes or carries out installing, upgrading, starting, stopping, releasing, recovering, migrating or cleaning up the product or its environment.

File count, reading state, temporary fixtures, prose and research do not qualify alone. Consequential uncertainty left after inspecting means above. Answer each yes, no or unknown with the source that decides it; never a size adjective.

Above: agreed scenarios, challenger review, separate builder, real-environment verification; only the user can exempt. Below: a compact contract, normally an efficient builder, and your own judgment with one counterexample. If you built it, a verifier review judges.

## Roles

The orchestrator uses the most capable tier and owns judgments and user communication. Builders use the project mapping: efficient for settled, bounded work; most capable otherwise. See handover for Claude subagents or Codex `build`; never override their model, delegate further or publish. Verifiers (independent checks and their own cases) and challengers (implied cases from the user's words and code) run separately on their own copies through `review`.

## Guides: open and read each at its moment

Depth `guides/depth.md` · discovery, agreement, design `guides/discovery.md` · before delegating `guides/handover.md` · before verification `guides/verification.md` · failed or inconclusive check `guides/recovery.md` · takeover, interruption, rotation `guides/takeover.md` · proof, verdict, closing `guides/delivery.md`.

Before delivery, read `guides/delivery.md` and run `python "$AGENTSYS_TOOL" reconcile --task <id>` until it passes; what cannot pass becomes a named gap under Limits. Build a before-state in a temporary `git worktree`, never by stashing or reverting the candidate.

## Proportion

Current view under 150 words; below the floor, record and proof a paragraph each. Messages lead with the outcome, avoid code notation and list decisions with your recommendation. Verify in the cheapest real environment that exercises the behavior.
