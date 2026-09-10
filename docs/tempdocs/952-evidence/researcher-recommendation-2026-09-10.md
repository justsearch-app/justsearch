# Owning worktree and branch termination

## 1. Cause and recommendation

**The cause is confirmed:** mandatory creation is paired with discretionary, ownerless termination. Unknown ownership then becomes permanent retention. The reported change from executing teardown to previewing it reinforces that mechanism, although the supplied brief—not an independent repository audit—is the evidence for the local history.[^brief]

**Assign termination to a repository-owned, deterministic lifecycle controller, not to the creating agent.** Extend the existing safe-removal script and world-state ledger. Sessions own exclusive use; the controller owns eventual disposition. Merge and exit events accelerate reconciliation; neither is required for correctness.

## 2. Lifecycle model

Adopting the policy requires explicit human authorization for this narrowly scoped janitor. It does **not** authorize publishing, merging, or deleting arbitrary remote branches. Those permissions remain unchanged.

| Ownership precedent | What to adopt—and its limitation |
|---|---|
| Creating session / harness | Fast release signal, not final responsibility: crashes and delayed merges outlive sessions. |
| Ephemeral CI | Externalize valuable state before disposal. GitHub deregisters an ephemeral runner after one job; separate automation still owns wiping it.[^ci] |
| Preview deployments | Pair creation with a stop operation, plus expiry reconciliation. GitLab combines lifecycle events with `auto_stop_in`; missing or unrunnable stop jobs remain a failure mode.[^preview] |
| Kubernetes | Keep a durable termination obligation until cleanup succeeds. Finalizers gate deletion; TTL-after-finished starts after completion, not merely after inactivity.[^k8s] |
| Cloud owner/expiry tags | Make ownership and retention machine-readable; metadata still needs an enforcing actor. AWS emphasizes explicit ownership and programmatic tagging.[^tags] |
| Squash-aware Git pruning | Useful candidate detection, not session ownership. `git-delete-merged-branches` supports squash detection through `git cherry`-based analysis; that cannot establish safe directory deletion.[^pruner] |

**Minimum shared contract:** plain commands for `create/acquire`, `renew`, `release`, `pin`, and `reconcile`. Before Git creation, durably reserve resource identity, canonical repository/path, branch references, session/parent identities, process incarnation, lease/generation, retention policy, actual fork SHA, and a trusted `origin/main` anchor. Preserve that anchor through sub-lanes; their immediate parent may contain unlanded work. Store records outside disposable worktrees, and track branches independently of directories.

Use a launcher—not agent prompts—to renew leases. Proposed defaults: 60-second heartbeat, 15-minute suspicion threshold, hourly reconciliation plus startup/logon, and immediate reconciliation after release or merge observation. Windows Task Scheduler supplies the independent trigger; no new always-running service is necessary.[^scheduler]

| Trigger / actor | Worktree transition | Branch disposition |
|---|---|---|
| Launcher registers, then creates | `PREPARING → ACTIVE` | Register every owned ref; reconcile partial creation after a crash. |
| Session releases; writers have stopped | `ACTIVE → RELEASED → FINALIZING → REMOVED` | Retain for an open PR, live dependent worker, or explicit handoff hold. Otherwise retire after preservation. |
| Heartbeat expires / controller | `ACTIVE → SUSPECT` | No deletion authorization. |
| Controller verifies all writers stopped | `SUSPECT → ORPHANED` | After a proposed 24-hour recovery grace, archive and retire unless legitimately held. Intent remains unknown. |
| Actual merge observed / controller | Record landing; never remove an active tree | Retire the exact covered head after release and dependency checks. |
| Explicit abandonment / owner | Finalize after preservation | Archive, then retire; do not automatically close a PR. |
| Unknown owner, foreign lock, unstable state, failed archive | `QUARANTINED` in place | Preserve; report a named decision owner and reason. |
| Controller crashes during termination | Remain `FINALIZING` | Retry recorded phases; completion requires both worktree and branch obligations resolved. |

**Abandonment is not inferable from inactivity.** A live process or worker overrides a stale heartbeat. PID plus start identity, a launcher-held operating-system lock, and an enforced start/resume generation distinguish active ownership from stale records. Require all writers to be quiescent before takeover; uncertainty means quarantine. Parent-session death does not terminate a live worker’s ownership. Last-commit age, missing remote branches, PR closure, and lock-file absence are only hints. Never expire a foreign/manual lock automatically; clear a stale controller-owned Git lock only after verified owner death and exclusive takeover. Windows Job Objects can track a supervised process group, but escaped children must not be silently ignored.[^jobs]

**Finalization is restartable:** claim exclusive cleanup ownership, fence new starts/resumes, revalidate identity and state, preserve, verify, recheck head/index/files against the snapshot, remove through the existing Windows-safe path, retire eligible refs, then record completion. Serialize these operations with all managed acquisition/ref-switch operations. Recheck that no worktree uses a ref; delete only its expected SHA. Git supports conditional ref deletion and ref transactions.[^refs]

Archive every retiring committed tip, including detached heads, under `refs/archive/<resource>/<head>` or in a verified bundle. A Git ref/bundle does not preserve dirty files or the index.[^bundle] Preserve staged/unstaged state, untracked files, valuable ignored files, and necessary nested-repository/LFS payloads separately; exclude only explicitly declared reproducible caches. Archive failure means no removal. Unproven archives never expire merely because time passes; report their storage and periodically test restoration.

## 3. Landed-ness tests

Distinguish **historically landed** from **redundant in the latest tree**. Later refactoring—or an intentional revert—can invalidate present-day equality without undoing the original integration.

For each exact local head `H`, record a content-verified receipt containing repository, PR, merged PR head, trusted anchor `B`, and actual integration commit `S`. GitHub’s `merge_commit_sha` denotes the squash commit only after merging; a queue’s temporary test SHA is not sufficient.[^pr][^queue] A receipt covers only its recorded `H`; an implementation branch differing from the published branch needs its own full-content check.

First try exact tree-entry equality on the complete `B..H` change set against `S`. Otherwise, a conservative mechanical subsumption check is:

```text
git merge-tree --write-tree --merge-base=B S H
accept only exit status 0 AND returned tree == S^{tree}
```

Use a validated common anchor and a controlled merge environment; reject unverified bases, custom/discarding drivers, conflict-favoring options, and unsupported special cases. Git documents both configurable merge drivers and the risks of overriding merge bases.[^merge][^attributes] This establishes mechanical content coverage, not arbitrary semantic equivalence. Any uncertainty remains `UNKNOWN`, never an instruction to discard.

Cache the receipt for the same `H`; require `S` to remain reachable from freshly fetched `origin/main`. That reachability validates the historical target receipt—it is **not** branch-ancestry proof of squash landing. A target-history rewrite or unavailable evidence suspends certification. Without a receipt, apply the same conservative check against the fetched target tip and label success `REDUNDANT_NOW`. Preservation remains mandatory either way.

| Candidate test | Strength | False-positive / false-negative modes |
|---|---|---|
| Touched-file comparison | Cheap exact coverage when all changed tree entries match; include modes, deletions, and both rename endpoints. | Narrowing to session-only paths misses inherited work. Later edits to the same files cause false negatives. Compare Git objects, not rendered/text-converted diffs. |
| `git cherry` / patch IDs | Good candidate ranking; aggregate `B..H` versus the squash delta can recognize many-to-one integration. | Per-commit matching misses multi-commit squashes. Whitespace stripping can equate different content; historical matches survive later reverts. `--verbatim` addresses whitespace, not semantic coverage.[^patch] |
| `merge-tree` no-op | Avoids checkout/index mutation and tolerates some unrelated evolution.[^merge] | Successful merge alone proves nothing: require target-tree equality. Custom drivers can discard unique content; overlapping later edits can produce conflicts despite prior landing.[^attributes] |
| GitHub merged + exact head | Locates the authoritative historical integration and avoids branch-name guesses.[^pr] | Alone does not cover extra local commits, dirty files, separate implementation branches, or prove the intended content survived integration. Combine with content verification. |

## 4. Minimum changes

| Location / change type | Required change |
|---|---|
| Repository creation script — **default** | Finish the `origin/main` fix; record its immutable SHA. Never silently fall back to local HEAD. Explicit sub-lane forks retain inherited provenance. |
| Repository teardown — **default** | Retire eligible branches by default, not opt-in. `keep` requires a recorded reason, responsible owner, and review/expiry date; expiry triggers reconsideration, not blind deletion. |
| Repository lifecycle script / Windows task — **new mechanism** | Extend the existing ledger/report into the reconciler above. Census both worktrees and refs, including missing-directory leftovers. Alerts are obligations, not merely classifications. |
| Repository archive/remover — **new mechanism** | Add a verified-archive removal path for orphaned dirty trees, not generic `--force`. Preserve junctions as links, never traverse/delete their targets; validate long-path and partial-failure behavior on Windows.[^windows] |
| Rule text — **explicit safety amendment** | Authorize only the registered janitor to terminate released or verified-inactive managed resources after preservation. Unknown resources remain protected. Run from a protected maintenance checkout outside shared `main`; never reset/clean/restore the main checkout. |
| Harness integration — **default + adapters** | Use repository-created worktrees for both harnesses and workers. Hooks supply registration/release hints, not an independent deletion path. Claude’s `WorktreeRemove` cannot veto deletion; a failed archive hook is therefore not a safety gate.[^hooks] |
| Workflow shape — **default** | One worktree per concurrent writer/session; one branch per coherent change/PR. Publish the implementation branch unless separation is genuinely necessary. Read-only investigations use immutable Git content without a new worktree; writing investigations return an archived/committed artifact, not an ownerless directory. Handoffs transfer refs/artifacts. Reuse within one session must explicitly retire/register refs; never share a tempdoc worktree across sessions. |
| GitHub / fetch settings — **retain existing + optional default** | Keep automatic remote-head deletion; optionally prune standard remote-tracking refs. Neither retires local branches or directories, and custom refspecs require care.[^prune] |

The brief’s “Codex has no lifecycle” is valid for its stated **manual workflow**, not the whole current product: official app documentation describes managed-worktree cleanup and snapshots.[^codex] That does not establish coverage for this repository’s manually created trees. Installed harness versions and the existing remover’s implementation were not inspected.

## 5. Rejected standalone fixes

| Fix | Why it does not own the lifecycle |
|---|---|
| Delete branch by default on teardown | Necessary for eligible refs, but ineffective when teardown never runs; unconditional deletion destroys unpublished work. |
| Session-end hook | Missing after crashes; session end can precede merge; turn completion is not session termination. |
| Cron/TTL deletion | Scheduling is useful; age-based ownership or abandonment inference is unsafe. |
| Tell agents to run world-state and act | Recreates discretionary cleanup and cross-session races instead of a durable owner. |
| GitHub branch deletion plus local prune | Ordinary fetch pruning removes remote-tracking refs; worktree pruning removes stale administrative records for missing directories—not live checkouts.[^prune][^worktree] |
| Human batch approval for everything | Appropriate for exceptions, but makes routine disposal depend on another unbounded queue. |

## 6. Steady-state acceptance criteria

Measure the post-adoption cohort separately from the existing backlog.

| Indicator | Target and measurement |
|---|---|
| Ownership coverage | 100% of new worktrees and branches registered or explicitly protected; reconcile Git census against the durable ledger each sweep. |
| Termination latency | At least 99% of released, unheld, preservation-complete resources retired within 24 powered-on hours; join ledger, actual Git inventory, and PR receipts. Report blocked counts/ages separately so exclusions cannot hide failure. |
| Preservation integrity | Zero deletions without verified preservation; zero failed sampled restores. Measure archive manifests and restore checks, and expose archive bytes/age. |

## 7. Sources and validation

Official documentation and maintainer references were consulted on September 10, 2026. The lifecycle design, thresholds, and transfer of these patterns are recommendations, not existing repository behavior. No blog claims are relied upon.

Disposable Git 2.47.3 experiments on Linux confirmed the following. They test Git logic, **not Windows filesystem safety**.

| Experiment | Observed result |
|---|---|
| Two-commit squash, followed by an unrelated edit to the same file | `git cherry` reported both commits unmatched; file equality failed; `merge-tree` returned the target tree. |
| Later edit overlapping landed content | Current-target simulation conflicted; simulation against historical squash `S` still returned `S`’s tree. |
| New local commit after the merged head | Head identity changed; historical simulation added content and did not return `S`’s tree. |
| Custom merge driver that preserves its current side and exits successfully | Simulation returned the target tree despite different, unique branch content: an unsafe false positive without driver controls. |
| Branch inherited an unlanded file before its session-specific changes | Session-only path equality passed; whole-branch simulation exposed the omitted inherited content. |
| Ignored file inside an otherwise clean linked worktree | `status --porcelain --untracked-files=all` was empty; ordinary `worktree remove` succeeded and deleted the ignored file. |

[^brief]: Supplied *Research brief: ending the lifecycle of agent-created git worktrees and branches*, `Pasted markdown(1).md`, §§1–3 and §§5–7, especially lines 7–20 and 26–38. Local measurements and history are accepted as the brief’s evidence; no repository access was used.
[^ci]: GitHub, [Self-hosted runners reference — Ephemeral runners for autoscaling](https://docs.github.com/en/actions/reference/runners/self-hosted-runners#ephemeral-runners-for-autoscaling).
[^preview]: GitLab, [Environments — Stopping an environment; stop after a certain time period](https://docs.gitlab.com/ci/environments/#stopping-an-environment).
[^k8s]: Kubernetes, [Finalizers](https://kubernetes.io/docs/concepts/overview/working-with-objects/finalizers/) and [Automatic Cleanup for Finished Jobs](https://kubernetes.io/docs/concepts/workloads/controllers/ttlafterfinished/).
[^tags]: AWS, [Building your tagging strategy](https://docs.aws.amazon.com/whitepapers/latest/tagging-best-practices/building-your-tagging-strategy.html). Owner/expiry enforcement for worktrees is the proposed application of this pattern, not a claim that AWS tags automatically delete resources.
[^pruner]: Sebastian Pipping / project maintainers, [`git-delete-merged-branches` README — Features and Safety](https://github.com/hartwork/git-delete-merged-branches). Maintainer primary reference, not an independent audit or recommendation to deploy this tool.
[^scheduler]: Microsoft, [Task Scheduler for developers](https://learn.microsoft.com/en-us/windows/win32/taskschd/task-scheduler-start-page).
[^jobs]: Microsoft, [Job Objects — Managing Processes in Jobs](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects).
[^refs]: Git, [`git-update-ref`](https://git-scm.com/docs/git-update-ref), conditional deletion and transactions.
[^bundle]: Git, [`git-bundle`](https://git-scm.com/docs/git-bundle), limitations on preserving state outside reachable Git objects.
[^pr]: GitHub, [REST API endpoints for pull requests — Get a pull request](https://docs.github.com/en/rest/pulls/pulls#get-a-pull-request), including post-merge `merge_commit_sha` semantics.
[^queue]: GitHub, [Managing a merge queue](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/configuring-pull-request-merges/managing-a-merge-queue), temporary queue branches and integration behavior.
[^merge]: Git, [`git-merge-tree`](https://git-scm.com/docs/git-merge-tree), `--write-tree`, `--merge-base`, output, and exit-status requirements.
[^attributes]: Git, [`gitattributes` — Performing a three-way merge](https://git-scm.com/docs/gitattributes#_performing_a_three_way_merge), configurable merge drivers.
[^patch]: Git, [`git-cherry`](https://git-scm.com/docs/git-cherry) and [`git-patch-id`](https://git-scm.com/docs/git-patch-id), patch equivalence and whitespace handling.
[^windows]: Microsoft, [Maximum Path Length Limitation](https://learn.microsoft.com/en-us/windows/win32/fileio/maximum-file-path-limitation) and [Reparse points](https://learn.microsoft.com/en-us/windows/win32/fileio/reparse-points). The supplied brief is the basis for the existing remover’s claimed protections; native Windows regression validation remains an implementation requirement.
[^hooks]: Anthropic, [Hooks reference — WorktreeRemove](https://code.claude.com/docs/en/hooks#worktreeremove) and [Run parallel sessions with worktrees](https://code.claude.com/docs/en/worktrees), including the exclusion of manually created worktrees from the periodic sweep.
[^prune]: Git, [`git-fetch` — Pruning](https://git-scm.com/docs/git-fetch#_pruning); GitHub, [Managing the automatic deletion of branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/configuring-pull-request-merges/managing-the-automatic-deletion-of-branches).
[^worktree]: Git, [`git-worktree`](https://git-scm.com/docs/git-worktree), `remove`, `prune`, and locking behavior.
[^codex]: OpenAI, [Worktrees — Worktree cleanup](https://learn.chatgpt.com/docs/environments/git-worktrees#worktree-cleanup), reached through the official Codex documentation route.
