# Lane F workflow retrospective, 2026-09-22

Status: evidence-based analysis and documentation repair. This is not runtime
implementation, a new acceptance waiver, or proof that the proposed workflow
improvements work. Model/provider changes are outside scope.

## Conclusion

The strongest current-session problem is weak convergence around a connected
deliverable: coupled implementation and test work kept generating partial
corrections, while coordination and context maintenance continued growing.
The useful remedy is earlier agreement on the real consumer path, explicit
ownership of coupled changes, and collecting independent failures together.
Removing verification would conceal defects rather than improve throughput.

Many appropriate rules already existed and were restated during the session.
Their presence did not ensure execution. Prefer changes to the work product and
tool operation over additional always-loaded prose. Keep required integration,
platform, live-model and negative-control proof.

## Sources and limits

Three private research documents were supplied: `transcript-efficiency-audit.md`,
`deep-research-report.md`, and `deep-research-report (1).md`. The latter two largely
interpret the first audit; they are not independent replications. Its historical
Claude transcript aggregates were not independently recalculated in this review.

The current root transcript was separately inspected up to, but excluding, the
user's usage-limit handoff request. The evidence-only
[counter](workflow-retro-counts-2026-09-22.py) and
[result](workflow-retro-counts-2026-09-22.json) reproduce descriptive counts for
that fixed prefix. The private transcript is not published. Its hash establishes
identity, not public access; aggregate counts cannot replace inspection of the
episodes. Repository evidence below remains usable without that transcript.

| Current root record | Observation | Interpretation limit |
| --- | --- | --- |
| Child dispatches | 20 spawn calls | Not simultaneous concurrency or complete descendant count |
| Coordination | 454 messages plus 301 follow-up calls | Not 755 correction rounds; many messages were useful |
| Highest message target | 143 to the D1 owner reviewer | Long-lived target spans multiple deliverables |
| Compaction | 35 records | Not evidence every reset lost useful information |
| Tool results | 340 of 4,917 contain a truncation warning | Some markers may be nested; not all discarded text was needed |
| Before first explicit self-audit follow-up | 149 of 1,885 results contain that warning (7.9%) | Descriptive, unequal task populations |
| After that follow-up | 191 of 3,032 (6.3%) | Recurrence remains; no causal improvement claim |

Only direct `response_item` calls/results are counted; event mirrors are excluded.
Nested operations inside `exec`, child transcript work, prices and token totals
are not inferred. No savings percentage is established for this current session.

## Findings

### 1. Stopping and waiting are distinct execution failures

The root issued a final status answer while explicitly listing unfinished work,
then required the user to request continuation. The next response acknowledged
there was no blocker but again ended the turn. This was a voluntary stop, unlike
the later usage-limit interruption. Existing continuation policy already forbade
it. A status request did not withdraw the original authorization.

Historical audit E1 instead classified 1,873 idle calls, associated with 13.29%
of recorded input exposure; three children accounted for 99.51% of that idle-input
bucket. Those are supplied audit findings, not measurements of this Codex run,
guaranteed monetary savings, or critical-path time saved.

This root used six direct sleep calls and existing wait APIs. A sampled early
build interval mixed useful preparation with redundant log-tail reads and short
process polls. That warrants better use of the existing process wait, not a
claim that the historical 1,873-call pathology has been reproduced here.

Use commentary to answer an interrupting status question and continue the next
authorized action. When genuinely waiting, use the owning process/agent wait
primitive and respond to output or completion. Periodic user updates need not
be accompanied by duplicate status probes. A new runtime controller is justified
only if a controlled reproduction shows the existing primitive cannot suspend
and resume correctly. A shell-echo prohibition alone would leave many equivalent
idle actions available.

### 2. A task needs an acceptance endpoint and an owner for coupled corrections

The [resumption contract](C2/resume-2026-09-21.md) already requires acceptance-first
work, consolidated feedback and reassessment after two substantive correction
rounds. Repeated reminders did not consistently produce those behaviors.

A connected slice should name its observable behavior, affected owners, required
proof and non-goals before dispatch. New findings must be classified as a defect
in that slice, a necessary prerequisite, or work already assigned to another
stage. A required defect stays open until fixed. An unrelated finding goes to
its existing owner rather than silently enlarging the current correction batch.

Count correction rounds by a frozen deliverable plus consolidated feedback,
not by messages. Repeated discoveries of lifecycle/ownership ambiguity mean the
parent must settle the contract before another implementation round. File-level
separation alone does not make two changes independent when both depend on the
same publication, lifecycle or recovery semantics.

Two independently examined episodes sharpen the distinction. Admission review
found a cancellation gap across lock/listener/handler entry; guard-removal
controls established the mechanism, while actual agent/workflow forwarding
remained separately required. This was valuable delegation, not overhead to
eliminate (see the resumption contract, admission section).

The launcher correction was dispatched as bounded implementation, but the worker
correctly stopped before editing: the owning EngineRoot registry was inaccessible
through the allowed launcher module dependency, and a local registry would create
a second owner. Root then had to investigate the missing composition seam. The
worker escalation worked; root's brief was premature. Check owner identity and
module access before assigning an apparently small cross-module fix. The
[component plan](D1/component-plan-2026-09-21.md) records the resulting launcher
process-resource ownership correction.

Two children also delivered changes to the same bulk-coordinator test file.
Those messages alone do not prove simultaneous edits or lost work; they are a
reason to verify assignment handoffs, not grounds to report a proven collision.
Some outgoing collaboration payloads in the raw transcript are opaque, limiting
reconstruction of the original briefs. The count remains observable while the
semantic quality of every coordination message does not.

### 3. Earlier failure collection can reduce serial verification

[Owner wiring evidence](D1/owner-wiring-2026-09-21.md) records runs2292-2294:
format/static failures arrived serially, and an additional UI PMD failure stopped
the remaining integrated tasks after 1,024 cases had already passed. The existing
correction, `spotlessCheck pmdAll --continue`, collects independent static failures
together. Run2299 subsequently exercised that collection pass; it is evidence of
execution, not a measured general speedup.

[Candidate-context evidence](D1/candidate-context-plan-2026-09-22.md) records a
different gap: run2459 passed359 inference cases, but integrated2460 exposed dead
callback wrappers and obsolete configuration-access allowlist entries. The same
failures reached hosted CI. Correcting those exposed a missed test method
reference at2462;2464 passed453 represented cases after correction, including94
valid reused audit cases. A corrected full integrated pass remains missing.

The lesson is to include the migration's affected consumers and audit owners in
the focused check set. Before deleting/changing a method, inspect both ordinary
calls and `Type::method` references, test seams, and governed allowlists. Compile
affected tests and run the specific relevant audits before the next expensive
integrated boundary. Do not add every audit to every trivial edit.

### 4. Valuable verification must survive the efficiency changes

[Publication-seam proof](D1/publication-seam-verification-2026-09-21.md) records a
review finding where exceptions in another thread or a callback timeout could
allow a misleading pass. Capturing those failures repaired the test. Removing
the full-registry precondition then caused the intended stale-publication failure;
the original source was restored byte-exactly. These iterations established
causality and cannot reasonably be counted as waste merely because they added
turns.

The candidate-context review also exposed an actual product defect: during
APPLY_ONLY, vision capability followed desired configuration instead of the
serving process. Run2457 demonstrated failure in both boolean directions before
the serving-context correction. Runs2451-2455 separately removed owner fencing,
post-close protection, transition locking, crash-budget advancement and clean-exit
recovery, each producing its intended failure. These checks are strong evidence
against treating all review-driven iterations as unnecessary expansion.

Preserve independent refutation, real consumer composition, wrong-reason-pass
checks, exact source inventories and test-level results. Reuse valid evidence
when code and assumptions remain applicable; keep fresh execution distinct from
cache reuse. The2464 report correctly did this. Never optimize for fewer failed
tests, fewer reviewers or a superficially green aggregate.

### 5. Context preservation needs a current-state view, not another history

The handoff again contained old next-run numbers and a historical section titled
"Next coherent work", alongside the actual next run2466. Its final prose already
said history should live separately. This is a concrete case where compliance
can be improved by editing the artifact rather than strengthening the rule.

The retrospective moved8,421 characters of checkpoint chronology verbatim to
[checkpoint history](../handoff-checkpoint-history-2026-09-22.md). The handoff
shrunk from17,299 to9,476 characters before adding its retrospective pointer.
Current proof limits and remaining D1/D2/E/F obligations were retained. This
reduces conflicting instructions; it does not establish that future agents will
re-read less or make fewer mistakes.

Oversized tool output also continued after the instruction was recorded. Bound
the aggregate response: choose symbols first, read the necessary ranges, and
reduce large structured results to the needed fields before returning them.
If truncation occurs, retrieve the missing relevant range rather than repeat the
entire query. This retrospective itself initially repeated oversized combined
reads, further evidence that the habit is not solved.

### 6. Measurement must include outcomes and the whole assignment

Existing analytics already include a neutral ledger and spawn-economics tooling.
Do not build a second telemetry framework or revive retired scoring layers.
The older transcript-spine utility expects Claude-style top-level messages and
does not by itself extract current Codex `response_item` content; inspect actual
schema support before trusting a report's apparent absence of activity.

Current `lib/ledger/codex-adapter.mjs` already reads explicit parent edges, and
`spawn-economics.mjs` groups calls by child session. That is useful existing
infrastructure, but per-child rows do not yet constitute a recursive root-task
outcome report. If the pilot needs tree accounting, extend that reader with
root/depth attribution and the existing acceptance-record link; preserve unknown
lineage explicitly and include resumed children. `context-residency.mjs` already
provides context/compaction diagnostics. Neither its distributions nor a wait-role
classification proves waste. No machine-wide statistics are substituted for this
session's fixed-prefix result.

The supplied research's precise context caps, turn budgets and percentage targets
are proposed policies, not demonstrated optima. Strict caps can increase resumed
tasks and repeated discovery. Measure the full descendant tree, including parent
corrections and restarted work, before calling a local reduction a gain.

## Concrete trial and decision rules

Apply these to the next three connected implementation slices, while retaining
the existing acceptance requirements. Three slices are a diagnostic pilot, not
a statistically powered causal study. Do not restart Lane F implementation as
part of this retrospective.

| Change to trial | Observe | Failure/refutation condition |
| --- | --- | --- |
| One observable endpoint and proof map before dispatch | Acceptance closed; prerequisites surfaced before edits | Hidden required work repeatedly appears only at integration |
| Frozen worker delivery, one consolidated correction list | Substantive rounds, parent intervention, independent defects found | Lower message count but more escaped defects or parent rewrite |
| Targeted caller/test/audit preflight, then integrated boundary | Late static/compile/audit failures; executed versus reused tasks | Preflight costs more while failures merely move to later steps |
| Existing waits, no duplicate unchanged probes | Non-progress calls during a controlled wait; completion/timeout/cancel | Lost wakeup, repeated resume or failure to handle cancellation |
| Current-state handoff and bounded reads | Repeated discovery after reset; truncation-driven rereads; stale decisions | Shorter context causes wrong assumptions or repeated source recovery |

Record accepted deliverable elapsed time, correction rounds, avoidable invalidated
verification, repeated discovery, platform/usage interruptions and escaped
defects. Separate active work, resource waits and user-requested pauses where
evidence permits. Total elapsed time is not the sum of concurrent child durations.
Keep the short outcome beside the existing acceptance record, not in a parallel
status database. Expand measurement only when a decision requires it.

## Changes made and boundaries

- Preserved this analysis and a content-free, reproducible root-record count.
- Removed historical continuation instructions from the active handoff while
  retaining their evidence and every open obligation.
- Left product code, required checks, model settings, hooks and canonical policy
  unchanged. The existing rules already cover the observed behavioral failures.
- No user prompt was found to justify the unauthorized stop or serial correction
  pattern. The autonomous-continuation instruction was explicit. Requiring the
  user to append "continue afterward" to status requests would shift responsibility
  rather than fix the agent's behavior.

The lane remains handed off at runtime code22800c842. Corrected integrated,
installed standard-model and hosted proof remain unverified in this analysis.

## Validation and retention

The fixed-prefix counter was run twice and produced identical JSON, including
the prefix hash. Relative links in the changed Markdown resolve. The moved
checkpoint section was compared with Git HEAD and is preserved verbatim; the
remaining obligation/owner-map tail was retained, with one new retrospective
link. `git diff --check` passes. No product build or runtime test was needed for
this evidence/documentation change, and none was credited.

Closeout sweep reaped nothing: it retained two stale ui-shot records whose PIDs
were absent and reported the intentionally persistent OTLP sink. Existing lane
worktree hold remains in place. Main's unrelated dirty files were untouched.

Raw private transcript access is limited to the local session store; research
documents remain user-supplied local inputs. Publicly reviewable repository
evidence supports the technical examples. The aggregate JSON and reproducer
are retained with this report; no private transcript excerpts are published.
