<!-- budget: always-loaded; ceiling in scripts/ci/always-loaded-budget.v1.json (ratchets down) — tempdoc 620. Extract domain-specific lessons to a skill before growing this file. -->

# Agent Lessons — Claude Code Platform

Cross-cutting platform constraints. Project workflow lessons live in canonical docs and skills; substrate-discipline cases live in [`docs/reference/contributing/agent-postmortems.md`](../../docs/reference/contributing/agent-postmortems.md).

## Claude Code platform constraints

- **Subagent CLAUDE.md inheritance is AGENT-TYPE-DEPENDENT** (docs re-checked 2026-08-18): general-purpose, custom, AND **fork** agents receive the FULL `CLAUDE.md` hierarchy + `.claude/rules/*.md` natively (fork inherits the parent's whole context); **only Explore and Plan receive none of it** — for those the `subagent-guide` hook's baseline brief is the ONLY project context, hence its `Explore|Plan` match. Task-specific context was never inherited by any type — the inline task brief stays mandatory regardless. Don't trust a subagent's self-report about its own context over a probe. <!-- rule:subagents-no-inheritance -->
- **Parent session hooks do NOT fire inside subagents** ([#237](https://github.com/anthropics/claude-code/issues/237), [#21460](https://github.com/anthropics/claude-code/issues/21460)). **Verified exception (2026-07-12 probe):** a parent PreToolUse/`Agent` hook DOES fire on a subagent's *nested* spawn — `subagent-model-guard.mjs` blocked a sonnet subagent's unpinned child. Unverified for other tools/events; re-probe first. (Observability caveat, tempdoc 622: subagent *interiors* are visible via native Claude Code OpenTelemetry — the Task subagent's spans nest under the parent's `claude_code.tool` span and its cost attributes carry `query_source:subagent`+`agent.name`, confirmed — so the blind spot is closeable at the OTel layer, not the hook layer.) <!-- rule:parent-hooks-dont-fire-in-subagents -->
- **`additionalContext` from SessionStart hooks is unreliable** for persistent state — use `.claude/rules/` instead.
- **`.claude/agents/` custom agents cannot override built-in agents** ([#8697](https://github.com/anthropics/claude-code/issues/8697), [#18212](https://github.com/anthropics/claude-code/issues/18212), [#16594](https://github.com/anthropics/claude-code/issues/16594)).
- **Agent tool `model` parameter works** — `haiku` for cheap search, `sonnet` for moderate work.
- **`Read` tool has silent truncation layers**: 2000 chars/line, 2000 lines, 25k tokens (varies by model). Use offset/limit explicitly for rules/guardrail content.
- **`Edit` tool validates `~/.claude/settings.json`** against the canonical schema. Probe via Edit if unsure whether a documented setting exists — the validator returns the schema on rejection.
- **Scoop shim junctions are unreachable from this session** (symptom: `Shim: Could not create process …`). Call the binary via its resolved path, e.g. `& "F:\scoop\apps\gh\2.90.0\bin\gh.exe" workflow run ci.yml`. Don't reinstall scoop packages — a session permissions quirk, not corruption.
- **`browser_batch` chaining rapid navigations races the SPA boot** (tempdoc 618 §8). A batch of hash-route navigations + a screenshot can capture a blank page: the app has not mounted. Issue one navigation, poll for readiness (or the `wait` action), then screenshot — act-then-read, not act-act-act-read.
- **After a branch is pushed once, catch up to a moving base with `git merge`, not `git rebase`** (tempdoc 695). Rebasing a pushed branch rewrites remote commits, so updating it needs a force-push, which native `permissions.deny` refuses with no exception (`branch-safety.md`). Recover a bad rebase with `git reset --hard origin/<your-branch>`, then `git merge origin/<default-branch>` instead. Under the live merge queue (829 R4) this is only needed for long-lived branch maintenance: the queue integrates against the moving base, so `strict` up-to-date-before-merge no longer blocks a stale branch. Two `gh` merge/CI-wait quirks live in `agent-guide.md` (History Publication).
- **A command piped through `tail`/`grep`/`head` reports the pipe's exit code, not its own** (tempdoc 618 §10a). A backgrounded `./gradlew build -x test | tail -25` can notify "exit 0" while the build actually FAILED — one step from fast-forwarding `main` on a red build. Run a command whose exit matters bare, `set -o pipefail`, or assert on its output text (`BUILD FAILED`); only the last pipe stage's code surfaces. <!-- rule:piped-exit-masked -->
- **A persistent background server runs as the bare `run_in_background` main process** (tempdoc 618 §11d). Wrapping it cost 3 tries to keep `serve-worktree-fe` alive: `timeout N …` self-kills at N s, `node … | grep &` orphans the inner process. Launch it bare — no `timeout`, no trailing `&`, no pipe.
- **Windows bulk edits corrupt UTF-8 via cp1252 round-trips** (tempdoc 742: a bulk rename mangled non-ASCII in 47 Java files; only 3 assertions could notice). Multi-file-edit briefs MUST mandate Edit/Write or node UTF-8 scripts — never PowerShell `Get-/Set-Content` — and the orchestrator checks the diff adds no unintended non-ASCII (`git diff | grep -P '^\+.*[^\x00-\x7F]'`) and no NUL bytes (`git diff | grep -cP '\x00'` must be 0 — a pasted control character turns a test file into a git binary, 909 review). <!-- rule:utf8-bulk-edits -->
- **Time-to-complete is an architecture signal.** A delegated fix landing in minutes is probably safe; an hour-plus for a "simple" request tells you about the code, not the agent — read the diff and architecture before merging.
- **Tracked background tasks are killed at ~60 minutes, and `TaskStop` does not kill child bash loops** (2026-07-22, certification campaign: caused concurrent-driver corruption). Pattern that held: a detached `Start-Process` driver plus self-terminating (<590s) polls.
- **Session cwd drifts into pub/agent worktrees after `cd`-in-compound-commands and persists across turns** — four incidents in one arc, incl. a worktree removed from inside itself and a possible cause of a locked sibling's mid-run destruction. Remedy: prefix repo-root-dependent commands with an absolute `cd`, and run `remove-worktree` only from the repo root.
- **A class-scanning test that reds with `TimeoutException` under parallel-agent load has not failed yet** (2026-08-31). Whole-classpath scanners — `WholeProgramDeadCodeTest`, `UnreferencedCodeTest`, `AgentGroundingSeamAuditTest`, any ArchUnit importer — are CPU-starved by concurrent worktree builds; a timeout says nothing about the property asserted. Re-run it with `--tests` before believing or acting on it.
- **Bash-tool heredocs corrupt backslashes and apostrophes even with a quoted delimiter** (2026-09-02, four occurrences in one wave: `<<'PY'` halved `\\`, apostrophes ended the command). Multi-line content with either character goes through the Write/Edit tools; heredocs are for plain prose only.
- **Gradle `--rerun` still replays test results from the build cache here** (2026-09-02, tempdoc 885 §UD.6): a suite total that arrives in seconds did not run. `cleanTest --no-build-cache` forces execution; read task outcomes or the result XML before trusting a count.

## Verifying Claude Code claims (evidence chain, best to worst)

1. Runtime probe — Edit-tool schema validation, hook execution with crafted JSON, subagent introspection.
2. Anthropic's [official docs](https://code.claude.com/docs/en/) and [GitHub issues](https://github.com/anthropics/claude-code/issues).
3. [Piebald-AI/claude-code-system-prompts](https://github.com/Piebald-AI/claude-code-system-prompts) — actual subagent prompts extracted from the binary.
4. Anthropic's [`bash_command_validator_example.py`](https://github.com/anthropics/claude-code/blob/main/examples/hooks/bash_command_validator_example.py) and similar examples.
5. Third-party blogs — orientation only, **not for committing to changes**.

## Named substrate-discipline principles

Each handle resolves to a full case paragraph in
[`agent-postmortems.md`](../../docs/reference/contributing/agent-postmortems.md), the authority — read it there. Only the handle list lives here, so the two do not drift:

`audit-without-test` · `wrong-gate` · `substrate-without-consumer-flavors` ·
`independent-review-required` · `static-green ≠ live-working` · `verdict-is-gate` ·
`catalog-verbatim` · `wire-emitter-elision` · `ai-offline-isnt-a-wall` ·
`standalone-capability-stays-stuck` · `unreachable-seed-green` · `green-masked-destructive` ·
`shared-worktree-checkout` · `falsify-restore-from-backup` · `accepted-tracked-skills-no-removal` ·
`edit-reread-cross-root`

- **`subset-isnt-the-suite`** — A hand-picked subset of gates/tests passing is not "the gates passed"; run the full kernel + full suite before declaring done, not at merge. Worked case: postmortem #13. <!-- rule:subset-isnt-the-suite -->
- **`green-masked-destructive`** — When a passing verification depends on an environment precondition, test the adverse precondition too; a green the environment happened to satisfy can hide the destructive branch.
