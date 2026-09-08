---
name: plan
description: >-
  Write an implementation plan for all remaining work in the current tempdoc,
  including teardown of anything superseded and the required verification.
---
Think deeply, investigate, and then write an implementation plan for all remaining work in your tempdoc. First investigate whether existing code, patterns, utilities, tests, gates, or infrastructure already support the plan. Prefer extending usable existing designs over creating new structures unnecessarily. The plan should describe the correct implementation path, including validation. If the tempdoc's design names anything it supersedes or orphans, the plan must include deleting or tombstoning it in the same PR — teardown rides along with the work that makes it dead. If the work contains user-visible features, implementation should only be considered successful after validating the real UI through the browser. You may use the local model and dev server as needed. Give concise progress updates and continue authorized work through checkpoints. A status answer does not end implementation. When you do stop for my input, state the two-list state explicitly (743 P-N, founder-approved 2026-07-17): **BLOCKED ON YOU** (what, why) vs **PROCEEDING** (what continues meanwhile) — never let one pending item read as a full stop. Your entire work should happen in a worktree, unless you are already in one. Publication actions require authorization; preserve any already granted for the named work. Identify bounded portions that would fit subagent work, but delegate only when active system and session rules permit it.

The tempdoc is your contract: every item marked for implementation is work I already judged necessary. You do not get to decide remaining items are "not worth it", "too difficult", or "diminishing returns". Implement every item unless I explicitly say skip; if an item looks infeasible, explain why and ask rather than silently skipping or summarizing-and-suggesting-closure. A tempdoc is complete when all its items are implemented, not when the impactful ones feel done. When asked what to do next, consult the tempdoc's remaining items first and propose those before new work; do not propose switching to a different tempdoc unless this one is fully complete, and if nothing is left, say so explicitly and let me decide.

Use `docs/reference/contributing/agent-workflow.md` for bounded delegation and
acceptance evidence. Map every required proof to its environment, revision, and
retained evidence. Schedule hosted/platform checks early when authorized. Scope
review precedes new coordination mechanisms; record a simpler ownership option.
Do not expand live worker briefs as new findings arrive: decide the dependency
and issue a bounded task or take ownership.
