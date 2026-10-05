---
title: "ADR-0045: Public main history publication"
type: decision
status: stable
description: "Public main receives curated squash commits from pull requests while branch commits remain workspace history."
date: 2026-06-28
probes:
  - adr-0045-history-policy-checked
  - adr-0045-review-record-separated
  - adr-0045-review-record-regression-invoked
  - adr-0045-agent-enqueue-validated
last_reviewed: 2026-09-05
---

# ADR-0045: Public main history publication

## Status

Accepted.

## Context

The public repository is now the canonical JustSearch development repository.
That changes the audience for `main`: it is no longer just a private working
log for one maintainer and their agents. It is a public project history read by
contributors, users, reviewers, and future agents.

Agent branches and maintainer worktrees often contain checkpoint commits,
investigation commits, retry commits, and temporary documentation edits. Those
commits are useful while work is active, but preserving every intermediate step
on public `main` turns the default branch into an execution transcript instead
of a durable project record.

The repository already treats some public facts as projected surfaces. Public
CI exposes stable fact-lane names rather than workflow internals, and branch
protection verifies those names through a small maintainer-run script. Public
Git history needs the same shape: working evidence can stay detailed, but the
published history should be intentional.

## Decision

Public `main` is a projected history surface. Ordinary changes land through a
pull request as one curated squash commit. The squash commit title and body come
from the PR title and PR body, not from the raw branch commit list.

Branch commits remain workspace history. Agents and maintainers may checkpoint,
experiment, and recover freely on branches. Review detail, test evidence,
discussion, and implementation notes live in the PR, checks, artifacts, and
working docs. The public commit on `main` records the durable project change.

The repository settings for the public repo should enforce this default:

- allow squash merging;
- disable routine merge commits;
- disable routine rebase merging;
- delete source branches after merge;
- use the PR title as the squash commit title;
- use the PR body as the squash commit body;
- require PR publication for `main` with zero required approving reviews;
- enforce branch protection for administrators.

Rare exceptions require an explicit maintainer action and reason. A
topology-preserving merge or curated multi-commit publication is possible only
when the intermediate commits are themselves durable public review units or the
branch topology has independent public meaning.

A small maintainer-run verifier records the expected settings in
`scripts/ci/repo-history-policy.v1.json` and checks them with
`scripts/ci/check-repo-history-policy.mjs`. The verifier is not a CI gate in
this slice because normal pull-request tokens cannot reliably read all
repository and branch-protection settings, and the native GitHub settings are
the enforcement mechanism.

## Consequences

Positive:

- Public `main` stays readable as project history rather than agent session
  history.
- Contributors do not need to curate local commit history before opening a PR.
- Dependency-update PRs, agent PRs, and normal maintainer PRs all have a simple
  default publication path.
- Branch and PR evidence is preserved without making every checkpoint a public
  mainline commit.

Negative:

- Maintainers must keep PR titles and bodies suitable for the resulting squash
  commit.
- A deliberately curated multi-commit change needs an explicit exception
  instead of using rebase merge casually.
- Repository settings become another maintainer-owned public fact to verify
  when GitHub configuration changes.

Neutral:

- Existing public history is not rewritten.
- This decision does not change CI fact lanes or required status-check names.
- The verifier is maintainer-run, similar to branch-protection verification,
  and can be promoted later only if drift proves that necessary.

## Alternatives Considered

### Keep all merge methods enabled and document a preference

Rejected. A soft preference would still allow the next routine agent branch to
land as a merge commit or rebased checkpoint transcript. The problem is a
publication boundary, so native repository settings should carry the default.

### Require linear history only

Rejected. Linear history prevents merge commits, but it still allows rebase
merges. Rebase merge preserves checkpoint commits on `main`, which is the
primary history-shape problem.

### Allow rebase merge for curated series by default

Rejected for the first public-history policy. A curated multi-commit series is
a valid exception, but the current repository does not have a recurring need
for a standing multi-commit lane. Keeping rebase enabled would make the
exception too easy to use as the default.

### Add a CI gate for repository settings

Rejected for this slice. The relevant GitHub settings are native repository and
branch-protection configuration. A maintainer-run verifier records the policy
without adding a CI job that may lack permissions on pull-request tokens.

## Amendment: separate mutable review evidence — 2026-09-04

The complete PR title and body remain the native squash title/body source, but
they must be commit-safe throughout publication. Mutable scope, risk,
verification evidence, and review state live in exactly one managed PR issue
comment, identified by the `justsearch-review-record:v1` marker. The comment is
bound to the current PR number, head SHA, and public-body SHA-256. Branch check
summaries and artifacts may hold reproducible detail, but they are linked
evidence rather than the durable review index.

Publishers manage and validate that comment with
`scripts/ci/pr-review-record.mjs`, then preview the public title/body with
`scripts/ci/preview-squash-message.mjs` before entering the ordinary merge
queue. No publication step temporarily moves rich review text through the PR
body or restores it after enqueue. The command is maintainer-run; this amendment
does not add a write-capable workflow, required check, or CI permission.

The command re-reads the PR, actor permissions, and managed comment immediately
before a mutation, then performs one exact read-back. GitHub's issue-comment
update endpoint has no compare-and-swap precondition, however. For updates, the
authenticated comment owner must therefore remain the sole writer from dry-run
through read-back; a transport or verification ambiguity is reported as
unknown and is never retried blindly.

The landed-message promise is semantic, not byte-exact. GitHub may reflow long
lines and append its own attribution material. Verification proves that the
durable public content landed and review-only content did not; public bodies
avoid formatting whose meaning depends on exact line wrapping.

The managed comment is the chosen durable surface because PR comments remain
on the Conversation timeline and have stable links and visible edit history.
Check runs are not the authority because GitHub archives them after 400 days
and deletes them ten days later. A future check may project freshness and link
to the comment, but only after its pull-request and merge-group event behavior
is proven. See GitHub's documentation for [issue comments](https://docs.github.com/en/rest/issues/comments)
and [check retention](https://docs.github.com/en/pull-requests/reference/status-checks#retention-of-checks).

## Amendment: validate the agent merge effect — 2026-09-05

Repository-owned agent CLI publication now enters the merge queue through
`scripts/dev/run-gh.mjs enqueue <PR>`. The gateway runs the squash preview and
managed-review check against live GitHub state and refuses to invoke `gh pr merge`
when either check or its process boundary fails. A shared pre-tool hook blocks the
ordinary direct `gh pr merge` and generic `run-gh.mjs pr merge` spellings so the
documented Claude and Codex paths converge on the gateway.

This is a last-mile agent guard, not a claim of universal GitHub enforcement. The
GitHub UI, ad-hoc API mutations, and deliberately obfuscated shell commands remain
maintainer-controlled bypass surfaces. The check-to-enqueue interval also is not
atomic: GitHub exposes no compare-and-swap precondition for PR metadata or issue
comments, so the authenticated owner must avoid concurrent edits during that short
window.

A hosted required check remains deferred. `issue_comment` and
`pull_request_target` workflows run at the default-branch SHA, while a merge-queue
required check must also report on the synthetic `merge_group` SHA. GitHub does not
document a complete PR-membership list in the merge-group event. Do not create a
write-capable Checks projection until metadata refresh and every grouped PR can be
bound to those SHAs without relying on queue-ref naming conventions.

## Amendment: agent-system replaces the merge-guard hook — 2026-10-05

JustSearch adopted agent-system and retired its own agent layer, including the shared pre-tool hook
that refused direct `gh pr merge` spellings and its regression. The gateway itself is unchanged:
`scripts/dev/run-gh.mjs enqueue <PR>` still validates the live squash and managed review records
before requesting the merge queue, and its regression stays in hosted CI. What replaces the hook is
agent-system's publication rule (builders never publish; pushing, opening or merging a pull request
needs the user's request) and project knowledge naming the gateway as the only merge path. The
probe for the retired hook is removed; the gateway probe remains.
