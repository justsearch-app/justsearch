---
title: "Agent workflow and instruction delivery"
type: reference
status: stable
description: "Continuity, bounded delegation, scope decisions, acceptance evidence, and Codex/Claude instruction delivery."
---

# Agent workflow and instruction delivery

`AGENTS.md` owns shared project policy. `CLAUDE.md` contains its complete generated
projection plus Claude-specific guidance. This reference explains the workflow;
it does not grant permissions or turn prose into executable enforcement.

## Continue the assignment

A checkpoint records progress within an assignment. After committing, reviewing,
publishing a component, or answering a status question, continue the next
authorized step. Use session-closeout when the requested scope is complete, the
user pauses or requests a handoff, or every useful next action depends on an
external decision or unavailable resource. An internal defect to investigate is
remaining work, not automatically a reason to wait for the user.

Record explicit user decisions once in the active work record, including their
scope. Preserve them across compaction; do not re-request existing authorization.
Do not infer permission to merge or release from ordinary implementation work.
A pending approval blocks only dependent actions. When blocked, identify that
dependency and continue independent authorized work. A platform interruption is
not a voluntary stop and must not be reported as one.

Match the requested artifact. An explanation or list of reference files should
not acquire instructions assigning another agent work unless that was requested.

## Keep delegation bounded

Before spawning, settle the deliverable and give the child a self-contained brief:

- Objective and acceptance items, including the required verification tier.
- Exact worktree, branch, assigned files, and ownership of shared resources.
- Governing instructions and design, including scoped migration amendments.
- Constraints, evidence format, and conditions requiring parent reassessment.

Task scope and file ownership remain binding even when the child inherits the
parent's conversation. Fresh agents explicitly read the governing instructions;
file access alone is not evidence that instructions entered their context.
Context isolation does not isolate the filesystem. Preserve other agents' edits,
and reserve repository-wide regeneration and shared-state decisions for the parent.

Send one coherent set of review corrections rather than a stream of speculative
changes while a worker is implementing. Material scope growth, a newly exposed
lifecycle/concurrency contract, or unclear ownership returns to the parent before
the worker continues. After two substantive correction rounds, reassess the
brief, design, and owner: take over, assign an appropriate complex worker, or
issue a newly bounded task. This threshold triggers reassessment; it neither
waives review findings nor requires discarding useful context. Escalate earlier
when the problem already exceeds the role. Report progress by acceptance items
and unresolved causes, not message or commit counts.

A persistent helper (the Codex `companion` role) is still a bounded child. It
holds supporting context across several related questions and returns
revision-stamped findings that separate stable facts from mutable state; it
holds no authority over architecture, root cause, or acceptance, and the parent
still reads the evidence behind any consequential decision. Open one only when
repeated context exists, address it by a stable Task ID with delta-only
follow-ups, and stop using it when the repetition ends. A Codex child inherits
the parent turn's sandbox and approval policy; a role file cannot narrow them.

## Decide scope before adding mechanisms

A proven defect needs a correction without waiting for repeat incidents. Before
adding another state machine, persistent marker, writer, or cross-language
contract, establish which acceptance item requires it. Compare a simpler
ownership arrangement and record why the chosen design fits. If the defect
blocks the current stage, bound the prerequisite explicitly; keep unrelated
capabilities in their owning stage. The agent cannot silently defer a required
item because it is difficult.

For an authorized architecture migration, identify the shipped statement being
replaced, the target behavior, the affected scope, and the proof required. Keep
shipped canonical docs accurate until behavior changes; the active design can
describe the authorized target. A migration of process topology does not waive
the local API trust boundary or authorize modifying another session's work.

## Reconcile acceptance with evidence

The existing task checklist owns requirements. Keep its evidence beside it or
link to the existing managed PR review record; do not create a parallel status
database. Before declaring a stage complete, walk every acceptance item and
record this information in a compact table:

| Acceptance item | Required environment / tested revision | Actual result and evidence | Open limit or authorized deferral |
| --- | --- | --- | --- |
| Name the requirement | Local or hosted, platform, commit and any uncommitted delta | Command/test or run URL, observed outcome, retained artifact | Missing proof, or decision and destination |

Implementation, local verification, hosted verification, and explicit deferral
are different states. A configured CI step, skipped test, advisory job, or green
aggregate cannot substitute for a required successful run. Check trigger, job,
platform, and tested revision. Report advisory failures on their own merits.
Deferral requires authorization, a destination stage, and a remaining acceptance
item; it is never converted into a passing check.

Run focused checks during implementation, then integrated suites at coherent
boundaries. Start required hosted/platform checks early when authorized so local
success does not conceal platform assumptions. Reuse evidence only when the
tested revision or reviewed content equivalence and relevant assumptions still
apply. A new code change, failure, environment difference, or unresolved concern
can justify rerunning; a documentation checkpoint alone does not invalidate an
unrelated passing test. Complete every required tier without redundant reruns.

Preserve suite output and result files before targeted reruns can overwrite them.
Capture exit status on the first run. Keep concise summaries and reproducible
commands in Git. Store bulky logs in retained CI artifacts or an explicitly
identified local evidence directory; disclose local-only access and retention
limits. A hash establishes identity but does not make a missing artifact
inspectable. Never put private transcripts, credentials, or machine-local state
in a public review record.

Independent review checks both code and the acceptance-to-evidence mapping,
including a counterexample to the completion claim. Prose checks cannot prove
that the checklist itself is exhaustive; the reviewer must compare it with the
governing specification and the actual user-visible behavior.

## Keep current decisions discoverable

Edit the owning design section when a decision changes. Maintain a short dated
index linking to the rationale, alternatives, and superseded decision. Do not
make readers replay a growing amendment log to reconstruct current truth.
Historical experiments can remain separately labeled as history. Handoffs name
the current revision, completed items, remaining items, decisions, evidence
limits, and next action; private conversation history is optional background.

## Route findings at discovery <!-- rule:log-pre-existing-issues -->

There is no observations inbox. Fix a verified, small documentation correction
in place when it fits the current change. Route a product defect outside the
assignment to its owning tempdoc's open items or domain register, then return to
the assignment. Issues caused by this change remain this task's responsibility.
Red or flaky verification on main needs a fix or a tracked, explicitly isolated
flaky-test runner; do not suppress the failure. Route a platform/process lesson
to a hook when it requires enforceable behavior, otherwise to the relevant
agent rules. Preserve file ownership and authorization while doing so.

## Keep instruction changes scoped <!-- rule:before-appending-to-rules -->

Before adding always-loaded text, check whether an agent on a different task
needs it. Search for existing guidance and edit its owner instead of duplicating
it. Put domain procedures in skills, platform lessons in scoped rules, and named
historical cases in agent-postmortems. Use the smallest applicable surface and
stay within the existing prompt budget. Consider a tested hook or gate for a
load-bearing prohibition; prose and hook configuration alone do not prove
enforcement. Name a concrete evasion when it helps agents apply the rule.

## Instruction delivery by client

The following separates documented client behavior from repository output tests.
Official documentation was checked on 2026-09-08; recheck it and record the client
version when a runtime probe disagrees or a client update changes delivery.

| Surface | Project instructions | Conversation and skills | Hooks and permissions |
| --- | --- | --- | --- |
| Codex main session | Discovers global/project AGENTS files along the startup directory chain | Current conversation plus relevant loaded skills | Active runtime policy and configured hooks |
| Codex child | Project roles explicitly require reading AGENTS and governing docs; verify fresh delivery | This app's `fork_turns` selects full, partial, or no history; do not generalize that schema to every client | Runtime overrides can supersede role defaults; read-only prose alone is not a sandbox |
| Claude general-purpose/custom child | Loads the applicable CLAUDE hierarchy and project rules | Fresh delegation context; custom agents may preload named skills | Session-wide tool hooks apply; actual matching/trust/configuration still matters |
| Claude Explore/Plan | Omits the CLAUDE hierarchy; this repo supplies a SubagentStart baseline | Fresh task brief | Session-wide hooks apply; the baseline is guidance, not an enforcement boundary |
| Claude fork | Inherits the parent's context at spawn | Includes conversation and loaded context | Uses the inherited session environment; verify actual permissions |

Sources: [Codex AGENTS discovery](https://learn.chatgpt.com/docs/agent-configuration/agents-md),
[Codex subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents),
[Codex hooks](https://learn.chatgpt.com/docs/hooks),
[Claude memory](https://code.claude.com/docs/en/memory), and
[Claude subagents](https://code.claude.com/docs/en/sub-agents).

Claude does not natively load AGENTS.md. This repository generates its complete
contents into CLAUDE.md instead of using an import. Generation verifies textual
parity; it does not prove that a client loaded the file or followed it. Skills
remain separate tool-specific authorities. Never infer model, permission, or
hook parity from matching Markdown.

## Delivery checks and runtime probe

`agent-instructions-sync --check` checks the complete shared projection. The
analytics suite tests marker failure and non-invariant drift. Leaf-script
subprocess tests check both harness branches and the guidance size budget. A
separate Codex adapter subprocess test exercises the manifest matcher, selected
handler, and emitted SubagentStart context, including a nonmatching role. Its
lifecycle matchers use agent type, session source, or compaction trigger as
appropriate. These are repository tests, not proof of client loading or trust.

When verifying a client upgrade or suspected missing instructions:

1. Use a disposable project with a unique, harmless marker in its AGENTS/CLAUDE
   instruction file. Record client version, startup directory, role, history
   mode, and trust settings. Do not modify production instruction files to probe.
2. Start a fresh session and child, asking for a small read-only task. Inspect
   the recorded loaded-instruction/tool events or transcript; an agent saying
   "I inherited it" is insufficient. A child explicitly reading the file proves
   retrieval, not automatic injection.
3. Configure a harmless tool hook in that fixture that appends a unique marker to
   a local log. Trigger the matching read-only tool in the parent and child and
   inspect the log. Distinguish hook configuration, hook invocation, and a
   deliberate deny-path result if testing enforcement.
4. Repeat only the relevant roles/modes. Record observed results and unavailable
   cases; do not extrapolate one successful role to all roles or clients.
5. Re-run step 3 for one child role whenever the Codex client changes
   major/minor version, and record the result against that version. As of
   0.153.x a child's sandbox is the parent turn's regardless of the role file
   (Codex #39299; 160 of 160 observed children, tempdoc 951 R2).

Run the probe through the actual available client. A missing tool or inaccessible
client is an explicit limit, never grounds for inventing a successful probe.
