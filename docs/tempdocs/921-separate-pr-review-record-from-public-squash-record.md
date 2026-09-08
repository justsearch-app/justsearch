---
title: "Separate the PR review record from the public squash record"
type: tempdocs
status: "PUBLISHED VIA PR #632 (2026-09-04) — commit-safe PR body plus one managed review-record comment; 30-merge audit run in tempdoc 948"
created: 2026-09-03
updated: 2026-09-07
charter: "make rich agent PR evidence compatible with concise, durable public main history"
supersedes: "ADR-0045 / tempdoc 653 only where they make the whole PR body the commit body; preserves squash-only publication and docs ride-along"
related:
  - 653-public-main-history-hygiene
  - 829-publish-workflow-develocity-analysis
  - 856-merge-attribution-authority
  - 858-analytics-lane-liveness
---

# 921 — Separate the PR review record from the public squash record

## Briefing for the implementing agent

Read this file, then ADR-0045, tempdoc 653 from §Long-term design settlement onward,
tempdoc 856 §3/§6, both publish skills, `scripts/ci/preview-squash-message.mjs`, and
the history-publication section of `docs/reference/contributing/agent-guide.md`.
Sections 0–18 are historical design and proof evidence. Sections 19–23 are the current
authority: do not retry either custom queue transport, do not temporarily swap and restore
the PR body, and do not implement the co-located `## Public commit` / `## Review record`
schema described earlier in this file.

This is a forward-only design. Do not rewrite existing `main` history. Preserve the
useful `Session-Id:` authority until tempdoc 856's measured retirement condition is
actually met. Do not reintroduce the retired process-hygiene score from tempdoc 858:
the proposed checks validate publication structure and exact projection, not prose,
developer behavior, or an agent-quality score.

## 0. Decision in one page

The current policy confuses two different records:

- the **PR review record** is allowed to be rich, operational, and time-varying;
- the **public squash record** must be concise, durable, and intelligible without the
  review conversation.

Today GitHub copies the first record verbatim into the second. That role collision is
the root defect. Squashing already keeps branch checkpoints off `main`; it does not
keep review-round transcripts, reviewer directions, raw test logs, provider footers,
or open-item routing out of the one surviving commit.

The replacement contract is:

1. The PR title remains the public commit subject.
2. The PR body contains one explicit `## Public commit` section followed by a
   `## Review record` section.
3. Publication extracts only the content of `## Public commit`, validates it, shows
   GitHub's live projected subject plus the exact body, and passes that body explicitly
   to the merge queue.
4. The rest of the PR body remains on the PR for reviewers and is never copied to the
   commit.
5. GitHub's repository default eventually becomes a safe empty body, so a bypassed
   publication loses optional detail rather than publishing the whole review transcript.
6. Machine-checkable shape is enforced; prose quality remains maintainer judgment.

The smallest useful template is:

```markdown
## Public commit

Why this durable change was needed.

- One to five observable outcomes.

Session-Id: <authoring-session-uuid>

## Review record

Authorship: agent | human | mixed | trusted-bot

### Scope and risk

### Verification evidence

Commands/checks and results, or `Not run: <honest reason>`.

### Review state

### Maintainer note
<!-- Reserved for a human maintainer. Agents must not write or alter this section. -->
```

`Session-Id:` may occur more than once when more than one session authored the change.
It identifies authoring sessions, not the person or session that enqueued the merge.
Human-only and trusted-bot PRs do not invent a session id.

## 1. Current evidence

### 1.1 The part that works

ADR-0045's main architectural choice is sound. The latest 100 `main` publications in
the audit were all single-parent squash commits. Branch checkpoint history is not
leaking into `main`, and the live repository settings match the declared squash-only
policy. This design keeps that mechanism.

Tempdoc 653's second axis also stands: tempdoc archaeology should ride along with its
implementation or be batched. A cleaner message cannot make an undersized PR into a
worthwhile public history unit.

### 1.2 The part that failed in use

Audit date: 2026-09-03. The history sample is the latest 100 merged PRs and their
resulting commits from `justsearch-app/justsearch`; the broader policy sample covers
573 merged PRs since the 2026-06-28 policy landed. HTML comments and known generated
blocks were removed only for the separately labelled "visible body" measurement.
These are dated session observations from GitHub PR/commit API responses, not canonical
repository metrics; the one-off query output was not checked in and the values should be
recomputed rather than reused for a later policy decision.

| Signal | Current result | What it means |
|---|---:|---|
| PR-title median | 92 characters | The future commit subject starts long. |
| PR titles over 72 characters | 79 / 100 | The prevailing subject style is not scan-friendly. |
| Visible PR-body median | 4,966 characters | The review packet is being published almost whole. |
| Visible bodies in the 4,800–5,000 band | 43 / 100 | The 5,000-character advisory has become a target, not a hygiene boundary. |
| Resulting `main` subject median | 99 characters | GitHub's space-plus-`(#N)` suffix makes the public subject longer still. |
| Resulting commit-body median | 5,028 characters | The role collision reaches permanent history unchanged. |
| Commit bodies over 5,000 characters | 54 / 100 | The existing warning has not controlled the durable artifact. |
| Bodies carrying review-round history | 32 / 100 | Event history is being rewritten into current-state prose, then committed. |
| Bodies carrying open/routed/not-in-PR work | 20 / 100 | Non-outcomes survive in the outcome record. |
| Bodies carrying raw commit SHAs | 15 / 100 | Branch mechanics survive the squash boundary. |
| Bodies carrying base/stack logs | 5 / 100 | Worktree orchestration leaks into public history. |
| Bodies carrying a Claude-generated footer/session URL | 86 / 100 | Provider-specific execution metadata dominates provenance. |

The verification story is materially better than the existing checker says. Ninety-
seven of the 100 PRs had a Testing or Verification section and another used a
`Verification:` label; only two were clear misses. The checker accepts 84 because it
recognizes only an exact `## Testing` section or a top-level label. That is evidence
against policing heading spelling and evidence for validating the explicit public
projection instead.

The `Session-Id` mechanism remains useful but incomplete. A current run of
`merge-links.mjs --range origin/main --since 2026-08-19 --json` found 75 of 91
in-scope squash commits (82.4%) with a valid id, over a sufficient denominator. The 15
missing PR bodies found through the GitHub API were maintainer-authored rather than
Dependabot-authored. This is below tempdoc 856's ≥95% for 30 days retirement bar, so
the design retains the line and the teardown fallback.

The sixteenth missing commit is more informative than another omission.
[PR #611](https://github.com/justsearch-app/justsearch/pull/611)'s
timeline shows `added_to_merge_queue` at 18:13:45Z; its body edit added the valid
`Session-Id` at 18:16:36Z; it merged at 18:17:20Z; the landed commit has no session
line. The merge queue captured the message at enqueue and did not reread the edited PR
body. That is live evidence that publication input becomes immutable at enqueue and
that a preview/fix performed afterward is too late.

### 1.3 Comparison with projects whose history is easier to read

The same latest-100 sampling method was applied on 2026-09-03. These measurements are
a comparison aid, not a universal league table: repositories differ in merge policy,
PR type, and generated-template content.

| Repository | PR title median | PR titles >72 | Visible PR body median | `main` subject median | `main` body median |
|---|---:|---:|---:|---:|---:|
| JustSearch | 92 | 79% | 4,966 | 99 | 5,028 |
| Kubernetes | 56 | 12% | 847 | 64 | 56 |
| Home Assistant | 42 | 3% | 3,100 | 56 | 0 |
| Django | 70 | 46% | 1,459 | 69 | 0 |
| OpenAI Codex | 48 | 0% | 686 | 57 | 693 |
| OpenHands | 58 | 26% | 3,015 | 67 | 50 |
| Aider | 48 | 5% | 298 | 47 | 0 |

The common pattern is not "short PR bodies." Home Assistant and OpenHands retain rich
review packets. The pattern is **separate review evidence from the durable commit**.

- Kubernetes asks commits to explain what/why with a short imperative subject, while
  its PR template separately carries issue, reviewer, release-note, and AI-disclosure
  concerns. Its AI policy also makes the human submitter responsible for the result.
  ([commit guidance](https://github.com/kubernetes/community/blob/master/contributors/guide/pull-requests.md#commit-message-guidelines),
  [AI guidance](https://github.com/kubernetes/community/blob/master/contributors/guide/pull-requests.md#ai-guidance))
- Django gives the merger an explicit commit-curation duty: a ≤72-character subject
  and a body about why, not a verbatim copy of the PR checklist and discussion.
  ([committing code](https://docs.djangoproject.com/en/dev/internals/contributing/committing-code/))
- Home Assistant deliberately puts human accountability and a detailed checklist in
  the review plane; merged commits commonly remain one-line public records.
  ([AI policy](https://developers.home-assistant.io/docs/ai_policy/),
  [review process](https://developers.home-assistant.io/docs/review-process/))
- OpenHands is the most relevant agent-native example. Its template reserves a HUMAN
  section that agents must not modify, then asks the agent for why, a 1–3 bullet
  summary, and reproducible end-to-end evidence. Its commits remain much smaller than
  its PRs. This is evidence for role-labelled review structure, not evidence that
  JustSearch should copy OpenHands' human-review policy wholesale.
  ([template](https://github.com/OpenHands/OpenHands/blob/main/.github/pull_request_template.md),
  [agent-created example](https://github.com/OpenHands/OpenHands/pull/17011))
- Aider's repository code supports Git-native co-author attribution, and its recent
  agent-marked commits are generally concise. Provenance can be a compact machine line;
  it does not require publishing a provider banner or session URL.
  ([Git integration](https://github.com/Aider-AI/aider/blob/main/aider/repo.py))
- OpenAI Codex uses small `What changed` / `Testing` review bodies and concise durable
  messages. Its contribution model is not JustSearch's, so it is a writing comparison,
  not a governance precedent.
  ([contribution policy](https://github.com/openai/codex/blob/main/docs/contributing.md))

Agent-native here means that the project visibly supports or records agent-authored
work. It does not claim that every cited repository is mostly or fully autonomously
operated. The transferable finding is narrower: agent-generated evidence needs an
explicit publication boundary because agents reliably produce more process narrative
than a commit needs.

## 2. The model: three records, not one mutable essay

| Record | Owner | Lifetime | Contains | Excludes |
|---|---|---|---|---|
| Branch/worktree | authoring session(s) | while work is active | checkpoints, experiments, recovery commits | no promise of public readability |
| PR review record | PR + checks + comments/artifacts | durable review history | public-commit projection, scope/risk, reproducible evidence, review state, human note | secrets, local-only paths, pasted logs better owned by CI |
| Public squash record | resulting commit on `main` | permanent project history | why, durable outcomes, necessary references, attribution key, exceptional durable verification | routine verification, review rounds, commands/logs, branch/base/stack mechanics, open-item routing, provider banners |

The PR body is a **current review index**, not an append-only review event log. When a
review round changes the design, update the current summary and let GitHub's review
conversation retain the event. Do not append `Review round 4`, `Review round 5`, and so
on to the body. Large evidence belongs in a check artifact or comment and is linked from
the current `### Verification evidence` section.

Out-of-scope findings follow tempdoc 872's routing rule: fix locally when small or route
to the owning tempdoc/register. The PR may link the destination; it does not reproduce
the whole backlog in the public commit.

## 3. Public projection contract

### 3.1 Subject

- Source: GitHub's live `viewerMergeHeadlineText(mergeType: SQUASH)` projection,
  which is derived from the final PR title under the retained `PR_TITLE` setting.
- Transport: do **not** pass `--subject` on the ordinary path. Edit the PR title, then
  let GitHub own its documented/default space-plus-`(#N)` transformation.
- Target: ≤60 characters before GitHub's PR-number suffix.
- Hard limit: the live projected subject, including the space-plus-`(#N)` suffix, must
  be ≤72 characters.
- Shape: imperative or outcome-naming, one durable reason to change, no tempdoc status
  narration, review-round number, base/stack state, or WIP marker.
- Conventional prefixes are allowed but not required. The checker validates length and
  known process markers, not English style.

The preview must render the **live GitHub-projected subject**, not just echo the PR
title. Read-only validation against open PR #59 returned
`build(deps): bump actions/download-artifact from 7 to 8 (#59)`, proving that this
field already exposes the suffix the current preview hides. If the field is unavailable
on an older GitHub host, fail closed rather than guess the permanent subject.

### 3.2 Body

The source is exactly the content under the single level-two `## Public commit`
heading, ending at the next level-two heading or end of body. The heading itself and
template comments placed before it are not published; an HTML comment inside the slice
is invalid rather than silently stripped.

Allowed shape:

- an empty body when the subject fully explains a small human/bot-authored change;
- otherwise, a why/outcome paragraph;
- zero to five outcome bullets (prose-only bodies remain valid for a tiny change);
- every valid `Session-Id:` line present in the section is preserved;
- issue, ADR, and tempdoc references only when they help a future reader understand the
  durable change.

Routine test commands and `Not run: docs only` belong in `### Verification evidence`
on the PR, not in every permanent commit. Include verification in the public projection
only when the fact itself is durable context—for example a compatibility boundary or a
migration property a future maintainer must understand.

Mechanical bounds:

- warn above 1,200 characters or 20 nonblank lines;
- refuse publication above 2,000 characters or 32 nonblank lines;
- refuse duplicate/missing `## Public commit` sections, visible checklist syntax,
  `<details>`, WIP/do-not-publish markers, raw stack/base logs, or malformed `Session-Id`
  declarations;
- warn, but do not fail, on raw SHA-looking tokens because a pinned source or migration
  reference can be legitimate;
- never score writing quality, count adjectives, or require a particular number of
  bullets.

The 1,200/2,000 bounds are initial repository policy, not a claimed industry standard.
They put the warning well above the OpenAI Codex comparison median while cutting the
current JustSearch median by more than half. Re-evaluate after 30 projected merges; do
not silently ratchet the limits to make the check green.

### 3.3 Attribution

`Session-Id:` stays inside the public projection because Git is the authority for the
session→merge join (tempdoc 856). The reader already accepts multiple lines and scans
the whole commit body because GitHub-appended co-author paragraphs displace Git trailers.

Rules:

- carry every distinct authoring session that materially contributed;
- never substitute the enqueueing/merging session merely to satisfy the check;
- require a self-declared review-plane `Authorship: agent | human | mixed | trusted-bot`;
- require at least one valid session id for `agent`/`mixed`, and omit it for
  `human`/`trusted-bot` unless an agent materially changed that work (which makes it
  `mixed`);
- accept `trusted-bot` only when the GitHub actor is in the repository's explicit bot
  allowlist; it is not a free-form self-exemption;
- remove `Generated with Claude Code`, provider session URLs, and equivalent banners
  from the public projection; they may appear in review metadata if genuinely useful;
- preserve GitHub's own co-author lines. They identify authorship and are not review
  transcript noise.

The coverage report must use this **self-declared** authorship class and label that
provenance honestly; GitHub actor identity cannot distinguish human and agent work when
both use the maintainer account. `agent` and `mixed` form the adoption denominator;
`human` and `trusted-bot` are reported exclusions. Deriving `agent` from the presence of
`Session-Id` would make the metric tautological, while counting every maintainer PR
would count work that may have no session. Both are rejected.

## 4. PR review-record contract

The PR body may be long when the change needs it. Hygiene comes from ownership and
navigation, not a blanket character cap.

Required sections for normal authored PRs:

- `## Public commit` — exact durable projection;
- `## Review record` — current review index;
- `Authorship: agent | human | mixed | trusted-bot` — self-declared work origin,
  explicitly not a claim of human approval;
- `### Verification evidence` under the review record — commands/checks and results,
  with fail-before/pass-after evidence for a regression where practical;
- `### Scope and risk` — affected surfaces, migrations, compatibility, and meaningful
  omissions;
- `### Review state` — unresolved decisions only. Resolved round-by-round history stays
  in comments/reviews.

Optional:

- screenshots/video for UI changes;
- an artifact link for large logs or generated reports;
- `### Maintainer note`, reserved for a human. Agents must neither manufacture a human
  attestation nor rewrite an existing one.

Trusted automation may use a smaller review record, but it still needs a curated public
projection before publication. Dependabot release notes can stay in the PR review plane;
they are not the commit body. Existing open PRs are migrated at enqueue time rather than
closed or recreated.

## 5. One implementation seam, two checks

Extend the existing squash-preview seam; do not build a competing publication system.

### 5.1 Projection library

Extract the pure parser/validator/rendering functions from
`preview-squash-message.mjs` into a small sibling library, for example
`scripts/ci/lib/squash-message-projection.mjs`. The command remains the supported UX
and consumes the library. Tests drive Markdown fixtures, not live GitHub state.

Do not locate sections with the current line-regex approach. A pressure-test containing
fake `## Public commit` headings in a fenced block, blockquote, and raw HTML showed that
`markdown-it` token maps distinguish the real top-level H2 (`tag=h2`, `level=0`) from
all three. `markdown-it@14.2.0` is already locked transitively through
`markdownlint-cli`; declare it as a direct dev dependency before importing it so this
checker does not depend on an accidental transitive edge. Use top-level H2 token maps
to slice the original lines, preserving the exact body rather than re-rendering
Markdown. Put template guidance comments before the public heading and reject HTML
comments inside the projected slice.

The projection result is one versioned value. GitHub owns the subject projection; this
tool owns only validation and the explicit body input:

```text
kind: justsearch-squash-message-projection.v2
source: { prNumber, headSha, updatedAt }
expectedLandedSubject: live viewerMergeHeadlineText(SQUASH)
body: exact merge body
warnings: advisory findings
errors: publication-blocking shape defects
```

Record `headSha` and PR `updatedAt`, but do not pretend both can be enforced. The CLI's
`--match-head-commit` provides a real optimistic lock for code. GitHub exposes no
equivalent precondition for PR-body `updatedAt`. Therefore preview output is never an
enqueue input: the publication command fetches and recomputes immediately before its
single merge mutation. A previously saved body/manifest is diagnostic only and cannot
be reused by `/publish`.

### 5.2 Review-readiness check

A dedicated lightweight workflow validates only the body structure and projection
grammar. Do not add `edited` to the existing `ci.yml`: that would rerun the full public
CI suite on prose edits and recreate tempdoc 829's velocity tax.

Use the repository's existing CLA workflow shape:

- `pull_request_target` with activity types `opened`, `edited`, `reopened`,
  `synchronize`, and `ready_for_review`;
- minimum read-only permissions;
- execute the validator from the trusted base branch and read untrusted PR text only
  from `GITHUB_EVENT_PATH`; never check out or execute the PR head;
- `merge_group: checks_requested` with a same-name no-op job, because the publication
  contract was checked on the PR and the merge-group event has no PR body;
- register the workflow/check in `workflow-signal-policy.v1.json` and branch protection.

This is the exact merge-queue pattern already used successfully by `cla.yml`; GitHub's
documentation confirms every required Actions check must also report for
`merge_group`.

The PR-side check validates:

- exactly one public projection;
- required review headings for normal authored PRs;
- non-empty verification evidence or an explicit reason it was not run;
- valid length/forbidden-marker rules;
- declared-authorship/session-id consistency.

The check does **not** certify the reserved human section. A body line cannot prove who
wrote it when agents and maintainers share GitHub credentials, and a non-empty section
must never be converted into a `human-approved=true` fact. When human approval is a real
requirement, GitHub's review actor/state is the authority; the section is explanatory
prose only.

This is not the old advisory linter promoted unchanged. The exact-heading false positive
and 5,000-character target are retired. Start the new check in report-only mode on the
open PR set; make it required only after all legacy PRs are migrated or explicitly
grandfathered and the fixture suite covers human, agent, and trusted-bot shapes.

### 5.3 Publication check and enqueue

The publication command fetches the PR again, builds the v2 projection, prints the full
expected landed subject and exact body, and refuses to enqueue on errors. After the
authorized maintainer or agent invokes the merge action, it calls the native queue path
with the exact body and the head SHA fetched in that same operation:

```text
gh pr merge <N> --match-head-commit <fetched-head-sha> --body-file <exact-body-file>
```

The body file is created in a scoped temporary directory and contains only the extracted
projection (including a deliberately empty body). Keep it until post-merge equality is
checked, then delete it in a `finally` path after success, rejection, or timeout. Do not
place it in the repository or a shared fixed filename. The mutation remains separate
from read-only preview so viewing a PR can never enqueue it. Existing
`no-merge-without-authorization` remains unchanged.

If the PR is already queued, stop. GitHub CLI v2.90.0 returns early without applying new
subject/body inputs. Updating the publication record therefore requires an explicit,
authorized dequeue, recomputation, and re-enqueue; `/publish` must never report the
already-queued no-op as a successful update.

GitHub CLI documents `--body-file` and `--match-head-commit` on `gh pr merge`; its
v2.90.0 source carries `commitBody` and `expectedHeadOid` into the same
`enablePullRequestAutoMerge` input used for a queue. Server-side preservation through
this repository's queue is the remaining live proof.
([GitHub CLI manual](https://cli.github.com/manual/gh_pr_merge))

### 5.4 Safe repository default

Only after the live queue proof succeeds, change
`squash_merge_commit_message` from `PR_BODY` to `BLANK` and update the history-policy
fixture/verifier. Keep `squash_merge_commit_title=PR_TITLE`.

Why `BLANK`: the explicit publication command supplies the curated body. If somebody
bypasses it through the UI, the safe degradation is a concise title-only commit, not a
5,000–65,000-character review transcript. Post-merge verification reports the missing
projection/session attribution so the bypass is visible.

GitHub exposes the squash default as repository configuration; ADR-0045 already treats
that setting as maintainer-owned policy.
([GitHub squash configuration](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/configuring-pull-request-merges/configuring-commit-squashing-for-pull-requests))

## 6. Post-merge proof

Publication is not complete when the queue accepts the PR. After merge:

1. fetch the merge commit named by the PR;
2. compare its subject and pre-GitHub body to the saved projection;
3. allow only documented GitHub-added material such as PR suffix and co-author block;
4. confirm every projected `Session-Id` remains discoverable by `merge-links.mjs`;
5. report a mismatch as a publication defect before cleanup.

The comparison must be fixture-driven for all observed GitHub suffix/co-author shapes.
Do not use `git interpret-trailers`; tempdoc 856 proved why that parser loses the session
line on this squash path.

## 7. Alternatives considered

### Keep PR_BODY and improve the warning

Rejected. The repository already did this. Forty-three percent of recent bodies sit just
under the warning threshold and more than half of resulting commit bodies exceed it.
An advisory on the wrong source cannot resolve the role collision.

### Make every PR body commit-short

Rejected. It improves `main` by deleting review utility. UI evidence, regression
reproduction, migration risk, and agent/human accountability belong in the PR. Mature
projects with good history often keep rich PRs and short commits.

### Put the commit message in a tracked side file

Rejected. It adds repository litter, requires a PR number that may not exist when work
starts, and creates another maintained copy. The PR already owns the publication input;
one delimited section is sufficient.

### Generate the commit body automatically from the diff

Rejected. A diff can show what changed but not reliably why, which risks were accepted,
or which verification result matters. Agents may draft the projection, but the final
text remains an explicit reviewable artifact.

### Publish no body at all

Rejected as the normal path, retained as the safe fallback. Many small commits need no
body, but architectural changes benefit from a concise why and durable verification
summary. A blanket empty-body rule throws away useful public context.

### Copy OpenHands' mandatory human section and autonomous-contribution policy

Rejected. Its role-labelled template is useful evidence, but JustSearch intentionally
allows zero-review autonomous publication after explicit owner authorization and green
checks. Borrow the clearly reserved human section while stating that it is not
machine-verifiable; do not import a governance boundary the owner did not choose.

## 8. Rollout and acceptance

### 8.1 PR 0 — prove the merge-queue transport

Use one low-risk, explicitly authorized PR with a unique body sentinel. Read
`viewerMergeHeadlineText(SQUASH)`, then enqueue it with `--match-head-commit` and
`--body-file` but no `--subject`. Record the default subject, body sent, head SHA, `gh`
version, and landed commit. Acceptance:

- queue accepts the PR without bypassing required checks;
- the resulting subject matches `viewerMergeHeadlineText(SQUASH)` and the body matches
  the supplied projection apart from enumerated GitHub additions;
- `Session-Id` remains discoverable;
- the proof is recorded here with PR, commit, CLI version, and observed transformation.

If any item fails, stop. Keep `PR_BODY`, record the exact queue behavior, and redesign the
transport. Do not switch the default on documentation alone.

#### Live result — failed on PR #625 (2026-09-03)

The authorized experiment refuted the proposed transport:

| Fact | Observed value |
|---|---|
| GitHub CLI | `gh` 2.90.0 (2026-04-16) |
| PR / verified head | #625 / `854e55347bea6a4e51296947617f0d9c7de707d2` |
| Queue invocation | `gh pr merge 625 --match-head-commit <head> --body-file <scoped-file>`; no subject override |
| Live projected subject | `fix(ci): align branch strictness with merge queue (#625)` |
| Sent body | 387 characters; SHA-256 `0A5654681CA06B65159A0345764D66ED1AD51407BF4239C7D62F674D5FCA75F3` |
| Unique sentinel | `Publication-Proof: tempdoc-921-pr625-body-v1` |
| Merge-group CI | Run `33781022045`, success |
| Landed commit | `ab6a0150b5fb597e213d903f7051b9c8ad2c0dbc` |
| Subject equality | Pass: landed subject exactly matched `viewerMergeHeadlineText(SQUASH)` |
| Body equality | **Fail:** landed body was the 1,153-character PR review body; the 387-character supplied body and sentinel were absent |
| Attribution | Pass: `merge-links.mjs` found the projected `Session-Id` in the landed PR body |

The exact supplied body was:

```text
Align branch-protection verification with the active merge queue.

- Declare that pull-request branches need not be updated before merging.
- Validate GitHub's live strictness against policy instead of assuming strict mode.
- Keep ADR-0044 aligned with the enforced repository configuration.

Publication-Proof: tempdoc-921-pr625-body-v1

Session-Id: 01a06701-37fd-7670-9c2c-5497ef806031
```

The queue accepted the invocation and ran against the expected head, but the resulting
commit used the PR body. Therefore the end-to-end queue path does not preserve this CLI
body input, regardless of the lower-level source fields found during derisking. The
repository setting remains `PR_BODY`; PRs 1–3 are stopped pending a replacement
transport design. Do not implement the extractor or switch the default to `BLANK` on
the assumption that `--body-file` is authoritative for a queued merge.

### 8.2 PR 1 — parser, preview, template, and report-only check

- implement the pure v2 projection parser and fixtures;
- declare `markdown-it` directly rather than consuming its transitive installation;
- update the PR template;
- update preview output to show the whole exact projection and live projected subject;
- add the separate report-only `pull_request_target` / `merge_group` readiness workflow
  and register it without making it required yet;
- correct `check-branch-protection.mjs`'s unconditional `strict=true` assumption: live
  protection is intentionally `strict=false` under tempdoc 829's active merge queue, so
  the checker currently fails against the policy it is meant to verify;
- migrate open PR bodies opportunistically, including Dependabot examples;
- retain v1 JSON compatibility only if a real consumer exists; otherwise retire it in
  this PR rather than carry two schemas indefinitely.

Acceptance fixtures: concise/empty human projection, one/multiple agent sessions,
trusted bot without session, docs-only verification in the review plane,
duplicate/missing projection, fake headings in fences/quotes/raw HTML, oversized
projection, forbidden review transcript, reserved human note, HTML comments, GitHub
co-author append, and a body edit after preview.

### 8.3 PR 2 — authorized enqueue and post-merge equality

- add the explicit enqueue path to the existing publish workflow;
- bind code to current head with `--match-head-commit`, recompute the body immediately,
  and reject an already-queued PR;
- add safe temporary-file handling;
- verify the landed commit before cleanup;
- live-test once more through the queue.

Acceptance: the ordinary `/publish` path cannot enqueue a malformed or stale projection,
and the read-only preview still cannot mutate GitHub state.

### 8.4 PR 3 — safe default and required structural gate

- amend ADR-0045 and the declared repository policy;
- change the GitHub squash-body default to `BLANK`;
- make the structural PR check required after legacy migration;
- update branch protection/ruleset evidence and merge-group behavior;
- run the first 30-merge health audit.

Success after 30 projected merges:

- 100% single-parent ordinary publications;
- 0 review-round/base-stack/provider-banner blocks in projected bodies;
- 95% of live projected subjects ≤72 characters, with every exception explained;
- projected body median ≤1,200 and 100% ≤2,000;
- ≥95% exact post-merge projection matches;
- `Session-Id` coverage reported by actor class, not against an impossible universal
  denominator, with the class labelled self-declared;
- no more than one legitimate false block in 30 PRs. More means revise the grammar
  before defending the gate.

## 9. Supersessions, orphans, and same-slice cleanup

This list is part of the design, not optional follow-up.

| Existing thing | Disposition |
|---|---|
| ADR-0045 lines saying PR body is the squash body | Amend to explicit public projection; keep squash-only main and PR-title subject. |
| Tempdoc 653 §1358–1365 publication source/preview | Mark superseded by 921; preserve its projection principle and axis-2 design. |
| `repo-history-policy.v1.json` expectation `PR_BODY` and its prose note | Replace with `BLANK` only after queue proof; bump schema/version if consumers rely on semantics. |
| `check-repo-history-policy.mjs` success text and fixtures | Update to distinguish native squash method from explicit body projection. |
| `preview-squash-message.mjs` v1 whole-body projection | Replace with v2 section extraction; keep the command name unless usage evidence justifies a rename. |
| Exact `## Testing` detector | Delete; verification has one semantic rule in the v2 projection/review grammar. |
| 5,000-character whole-PR warning | Delete; it rewards threshold packing and constrains the wrong record. |
| HTML-comment warning over the whole PR | Delete; comments are legitimate template guidance outside the projection. Strip/reject them only inside the public projection. |
| `.github/PULL_REQUEST_TEMPLATE.md` Summary/Changes/Testing/Related Issues skeleton | Replace with Public commit / Review record contract. |
| Publish-skill instruction "body is the squash message" | Replace with extract-preview-enqueue-verify sequence; retain authorization and merge-queue rules. |
| Agent-guide and MAINTAINING text that equates PR body with commit body | Amend in the same documentation slice. |
| `Generated with Claude Code` and provider session URLs in public commits | Retire from the projection contract; do not rewrite historical commits. |
| `Session-Id` commit line and `merge-links.mjs` whole-body reader | Keep. It is load-bearing and below its retirement threshold. |
| `recordMergeLink()` teardown fallback | Keep as tempdoc 856's tombstone candidate; this design does not weaken its retirement condition. |
| Process-hygiene scoring from tempdoc 858 | Remains retired; do not reuse the new check as an agent score. |
| Open legacy PRs and Dependabot bodies | Migrate in place or grandfather explicitly; do not close/recreate merely for template compliance. |

No new long-lived commit-message side file, bot database, or duplicate policy register is
created. The versioned projection value is computed from the PR at the publication moment.

## 10. Risks and falsifiers

- **Queue override is ignored.** The live proof is the gate; no settings change before it.
- **The projection duplicates work and agents paste the same essay twice.** Measure the
  projection/whole-body ratio and review fixtures. If duplication dominates, reduce the
  review template rather than expand the public section.
- **A hard limit removes essential rationale.** A future reader may follow a linked ADR or
  PR. If 2,000 characters repeatedly cannot carry the durable why, the limit is wrong;
  change it from sampled evidence.
- **The human-only section is forgeable.** The rule is a social and review boundary unless
  GitHub authorship can prove edits. Never emit a false automated "human approved" fact.
- **Agent attribution becomes surveillance clutter.** `Session-Id` stays only while named
  analytics consumers use it and 856's authority argument holds. Provider URLs do not.
- **Required checks block automation PRs.** Trusted-bot fixtures and transition handling
  are acceptance criteria, not an afterthought.
- **PR comments become an unsearchable review dump.** The body remains the current index;
  artifacts/comments are linked and owned, not merely displaced.

## 11. Reach judgment

### Candidate principle

> A review surface and a publication surface may share a source, but they must not share
> an undifferentiated payload when their readers, lifetimes, and evidence needs differ.

This is a narrower, earned form of tempdoc 653's publication-artifact projection. The
instance has evidence: branch squashing is working, yet a median 5,028-character review
packet still reaches permanent history; a threshold advisory was optimized around rather
than obeyed; and agent-native comparison projects keep substantially more detail in PRs
than commits.

### Candidate scope

Keep the principle local to irreversible or externally durable projections with a real
role collision:

- PR review packet → squash commit;
- release worklog → release notes;
- migration execution log → durable migration decision;
- generated research trace → outward-facing claim summary.

Do not generalize it to every pair of verbose/short representations, and do not create a
framework. A local extractor plus exact preview is enough here.

### Evidence it earns its keep

After 30 merges, the public record becomes materially shorter and free of review-process
debris while PR verification evidence and reviewer utility do not decline. Concretely,
the §8.4 metrics pass and reviewers do not need to ask for evidence that the new template
removed.

### Retirement condition

Retire the explicit projection section and return to the simpler PR-body-as-commit policy
if either of these holds over two consecutive 30-PR windows:

- whole PR bodies are already commit-safe (≤2,000 characters and no review-only debris)
  in at least 95% of PRs, making extraction ceremony redundant; or
- the projection gate creates more legitimate false blocks or manual repair work than
  the history defects it prevents.

If the queue cannot carry an explicit body reliably, retire this transport design, not
the separation principle: keep the review/public distinction and redesign the native
publication seam from observed behavior.

## 12. Derisk pass — 2026-09-03

This pass followed `.claude/skills/derisk/SKILL.md`: identify assumptions, gather
read-only/source-level evidence, refute the weak parts, and stop before feature
implementation. No PR was enqueued, no repository setting changed, and no production
script/workflow was implemented.

### 12.1 Evidence gathered

| Question | Evidence | Verdict |
|---|---|---|
| Does the installed CLI carry a custom body into the queue request? | Installed `gh` is v2.90.0. Its tagged `pkg/cmd/pr/merge/merge.go` puts `Body`, `BodySet`, and `MatchHeadCommit` into one payload before the queue branch flips `auto=true`; `http.go` puts those values into `EnablePullRequestAutoMergeInput`. | **High source confidence; still requires one server-side live proof.** |
| Can publication reject a code change after preview? | `--match-head-commit` maps to GraphQL `expectedHeadOid` on the same input. | **Yes for the head SHA.** |
| Can publication atomically reject a PR-body edit after preview? | Neither the CLI path nor the API input exposes an expected PR `updatedAt`. | **No. Refuted the original two-field binding claim. Recompute immediately and never reuse preview output.** |
| Can the subject be previewed without guessing GitHub's suffix? | Live GraphQL on open PR #59 returned `viewerMergeHeadlineText(SQUASH)` with the space-plus-`(#59)` suffix already applied. | **Yes. Do not override the subject on the ordinary path.** |
| Can a required metadata check work with the merge queue? | GitHub requires every required Actions check to run on `merge_group`; this repo already uses a no-op merge-group branch in `cla.yml`. | **Yes, as a separate lightweight workflow.** |
| Will body edits trigger today's CI? | Bare `pull_request:` defaults to opened/synchronize/reopened, not `edited`. Adding `edited` to `ci.yml` would rerun the full suite. | **Use a dedicated `pull_request_target` workflow, not `ci.yml`.** |
| Can a line regex find the real publication section safely? | A fixture with fake H2s in a fence, blockquote, and raw HTML was parsed with the locked `markdown-it@14.2.0`; token `level`/`map` isolated only real top-level H2s. | **Use Markdown tokens and original-line slicing.** |
| Can GitHub actor identity classify human vs agent work? | The 15 recent missing-Session-Id PRs and the 75 attributed PRs use the same maintainer account. | **No. Add explicitly self-declared authorship; do not label it independently verified.** |
| Does the queue reread a PR body changed after enqueue? | PR #611 entered the queue at 18:13:45Z, gained its valid session line at 18:16:36Z, and merged at 18:17:20Z without that line in commit `31a26b0d`. | **No. The enqueue snapshot is authoritative; already-queued edits require dequeue/re-enqueue.** |
| Is the branch-protection verifier ready for a new required check? | Live protection intentionally has `strict=false` under the merge queue, while `check-branch-protection.mjs` unconditionally fails when strict is false. Tempdoc 829 records false as the intended state. | **No. This stale verifier premise is a prerequisite correction.** |
| What is the transition population? | 10 open PRs: 8 Dependabot and 2 maintainer PRs; none uses the new contract. | **Small but bot-heavy; migrate or explicitly grandfather before requiring the check.** |

Primary source anchors:

- GitHub CLI v2.90.0 queue payload:
  [`merge.go`](https://github.com/cli/cli/blob/v2.90.0/pkg/cmd/pr/merge/merge.go) and
  [`http.go`](https://github.com/cli/cli/blob/v2.90.0/pkg/cmd/pr/merge/http.go)
- GitHub merge-queue check contract:
  [Managing a merge queue](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/configuring-pull-request-merges/managing-a-merge-queue)
- GitHub event activity defaults:
  [Events that trigger workflows](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows)

### 12.2 Corrections made to the design

1. **Subject override removed.** Use live `viewerMergeHeadlineText(SQUASH)` and keep
   `PR_TITLE`; only the body is overridden.
2. **Routine verification removed from the commit contract.** It remains required and
   detailed in the PR review record. Permanent commits carry it only when it is durable
   explanatory context.
3. **Empty public bodies allowed.** A small human/bot change may need only its subject;
   agent/mixed work still carries its attribution line.
4. **Stale-preview promise narrowed.** Head SHA is enforced; body is recomputed at
   enqueue; saved preview output is never accepted as publication input.
5. **Already-queued PR made an explicit stop.** The installed CLI applies no updates in
   that state, and PR #611 proves GitHub does not reread later body edits; dequeue /
   re-enqueue needs a separate authorized action.
6. **Parser upgraded from regex design to Markdown token maps.** The dependency must be
   direct, even though it already exists transitively.
7. **Required check split from full CI.** Body edits receive a cheap trusted-base check;
   merge groups receive the same named no-op, following the live CLA precedent.
8. **Attribution denominator made honest.** `Authorship` is self-declared and checked
   for internal consistency; it is not inferred from the field whose coverage is being
   measured.
9. **Stale branch-protection checker added to prerequisite scope.** A new check cannot
   be safely registered while the verifier contradicts the intentional queue setting.

### 12.3 Transport uncertainty resolved by the live experiment

PR #625 performed §8.1's minimum experiment after explicit authorization. The queue
accepted the expected head, merge-group CI passed, and the subject matched GitHub's
live projection. The body did not: the merge commit contains the full PR body and none
of the supplied 387-character body or its unique sentinel. This refutes the transport
assumption and reduces implementation confidence until a replacement publication seam
is designed and proved.

Two non-blocking observations should be measured during rollout rather than guessed:

- whether GitHub treats the latest `pull_request_target` run after a same-SHA body edit
  as the authoritative required check in every observed path; `/publish` remains the
  final local validator even if that UI behavior is surprising;
- whether 1,200/2,000 characters are the right bounds after agents write 30 real
  projections. They are policy starting points, not truths to defend.

### 12.4 Confidence and implementation recommendation

**Original derisk confidence: 8/10; post-experiment transport confidence: 2/10.** The
parser seam, secure workflow shape, title projection, and head-SHA lock retain direct
evidence. The intended body transport is refuted by PR #625, so the overall rollout is
stopped until redesign. Same-SHA body-edit check selection also remains unmeasured.

**Difficulty: 7/10 (moderately hard).** The parser itself is small. The difficulty is
coordinating a GitHub metadata check, merge-group no-op, branch-protection policy,
authorization boundary, queue mutation, post-merge comparison, legacy PR transition,
and analytics semantics without producing a bypass or a dead required check.

**Model/effort recommendation:** use **Opus at high effort** for PR 0 and the enqueue /
required-check / settings slices (PRs 2–3), where a wrong assumption can stall the merge
queue or publish an irreversible message. **Sonnet at high effort** is sufficient for
the pure parser/template/fixture slice once PR 0 records the server behavior. Keep the
same agent on each transport slice through live verification; handoff between mutation
and proof is the main avoidable context risk.

## 13. Implementation progress — 2026-09-03

The independent branch-protection prerequisite from §8.2 is implemented on branch
`codex/921-pr-publication-record` in an isolated worktree based on `origin/main`:

- `workflow-signal-policy.v1.json` now declares
  `branchProtection.requireBranchesUpToDateBeforeMerging=false` explicitly;
- `check-branch-protection.mjs` validates live `required_status_checks.strict` against
  that declared value instead of assuming `true`;
- the checker fails closed when the policy omits or mistypes the declaration;
- ADR-0044 now records why the active merge queue owns current-`main` integration and
  why a second per-branch up-to-date requirement would restore the serial re-CI tax.

Verification evidence:

- `node scripts/ci/test-check-branch-protection.mjs` — pass;
- `node scripts/ci/check-branch-protection.mjs --json` — live pass with
  `expectedStrict=false`, `actualStrict=false`, and all ten declared check contexts;
- `node scripts/ci/check-workflow-triggers.mjs` — pass;
- `node scripts/ci/check-tempdoc-numbers.mjs` — pass, 624 distinct numbers across
  17 worktrees and `origin/main` with no collisions;
- `node scripts/docs/llmstxt-generate.mjs --check` — pass, 116 docs indexed;
- `node scripts/docs/skills-sync.mjs --check` — pass, 5 generated skills / 9 sources;
- `node scripts/docs/verify-canonical-doc-links.mjs` — pass, 157 files.

The prerequisite merged through PR #625 as commit
`ab6a0150b5fb597e213d903f7051b9c8ad2c0dbc`. Its PR checks and merge-group run
`33781022045` passed. The live body-transport proof failed exactly as recorded in §8.1:
the subject and `Session-Id` survived, but the queue published the full PR body instead
of the explicit `--body-file` input. The repository default was not changed.

## 14. Post-failure research and theorization — 2026-09-03

This section is an investigation and option space, not a settled replacement design.
It follows the failed PR #625 proof and deliberately stops before implementation or
another GitHub mutation. No external code or text was copied into the repository.

### 14.1 What the failed transport path actually was

The failure was not a network or local-checkout failure. It was a semantic break across
two server operations that the installed CLI presents as one command:

1. `gh pr merge --body-file ... --match-head-commit ...` accepted the intended body and
   head SHA locally.
2. GitHub CLI v2.90.0 built a `mergePayload` containing `commitBody`, `setCommitBody`, and
   `expectedHeadOid`.
3. Once the CLI detected a merge queue, it set `payload.auto = true`.
4. `http.go` routed every `auto=true` payload through GraphQL
   `enablePullRequestAutoMerge`, not through the queue's `enqueuePullRequest` mutation.
5. GitHub later enqueued and squashed the PR, but the queue used the repository's
   `PR_BODY` default instead of the auto-merge mutation's explicit body.

The tagged CLI source makes steps 2–4 explicit in
[`merge.go`](https://github.com/cli/cli/blob/v2.90.0/pkg/cmd/pr/merge/merge.go)
and
[`http.go`](https://github.com/cli/cli/blob/v2.90.0/pkg/cmd/pr/merge/http.go).
An open GitHub CLI bug independently identifies the same queue/auto-merge routing seam:
[`gh pr merge` fails with merge queue when `allow_auto_merge` is disabled](https://github.com/cli/cli/issues/13398).

A read-only live GraphQL schema query sharpened the boundary. On 2026-09-03,
`EnablePullRequestAutoMergeInput` exposed `commitHeadline`, `commitBody`, `mergeMethod`,
and `expectedHeadOid`, while `EnqueuePullRequestInput` exposed only
`pullRequestId`, `jump`, and `expectedHeadOid` (plus `clientMutationId`). Therefore a
direct GraphQL enqueue cannot carry the public projection. PR #625 supplies the missing
server-side observation: values accepted by the auto-merge input are not preserved when
the later queue operation constructs the squash commit.

This explains why source inspection originally produced false confidence. It proved that
the CLI serialized the field, not that the irreversible queue request owned or persisted
the field.

### 14.2 Newly available provider primitive

GitHub's current REST API documents
`PUT /repos/{owner}/{repo}/pulls/{pull_number}/merge-async`. Its request has all five
pieces the publication boundary needs:

- `commit_title`;
- `commit_message`;
- expected head `sha`;
- `merge_method`, including `squash`;
- `merge_action`, including an explicit `merge_queue` value.

The endpoint returns a UUID for a corresponding result endpoint. A pending result
reports the merge method, merge action, and expected head SHA. A direct merge may finish
with a landed commit, but a queue-routed request may instead finish this first phase as
`enqueued`; the UUID does not then replace ordinary merge-queue/PR completion monitoring.
GitHub documents `200` for an already merged or already queued PR, `202` for a newly
accepted asynchronous request, and `409` when another asynchronous request already
exists. See
[Merge a pull request asynchronously](https://docs.github.com/en/rest/pulls/pulls?apiVersion=2026-03-10#merge-a-pull-request-asynchronously)
and
[Get the result of an asynchronous merge](https://docs.github.com/en/rest/pulls/pulls?apiVersion=2026-03-10#get-the-result-of-an-asynchronous-merge).

This is a qualitatively better candidate than `gh pr merge --body-file`: title, message,
head lock, merge method, and queue routing are members of one request, and the server
returns a receipt for that request. GitHub's own
[`github/gh-stack` client](https://github.com/github/gh-stack/blob/main/internal/github/merge_async.go)
also uses the asynchronous endpoint and explicit merge action, which is evidence that
this is an intended native path rather than an undocumented workaround. Its current
implementation does not send a custom message, so it is not evidence that the queue
preserves one.

The endpoint documentation says `commit_message` is extra detail for the automatic
commit message, but does not explicitly promise byte-for-byte persistence after a merge
queue processes the request. The API shape raises the transport hypothesis from 2/10 to
6/10; only a live queued squash can raise it to implementation confidence.

### 14.3 Possible solution directions

| Direction | What it buys | Main uncertainty or cost | Current posture |
|---|---|---|---|
| REST asynchronous merge with an explicit queue action and message | Keeps the rich PR body, native queue, SHA lock, and concise squash body; adds a pollable receipt. | The queue may still discard or transform `commit_message`; API availability and response recovery need fail-closed handling. | **Leading hypothesis; prove first.** |
| Make the PR body itself commit-safe and move review evidence to a bot-managed PR comment or check summary | Works with GitHub's observed `PR_BODY` queue behavior and removes transport dependence. | The PR description stops being the single review index; comments/checks are less prominent and ownership/editability must be clear. | **Preferred fallback if REST proof fails.** |
| Temporarily replace the PR body with the public projection, enqueue, then restore the review body | PR #611 suggests the queue snapshots the body at enqueue, so the mechanism could work. | No conditional body update binds `updatedAt`; concurrent human edits can be clobbered, crashes leave the wrong body, and it requires multiple external mutations around an irreversible action. | **Experimental recovery only; reject as the normal path.** |
| Change the repository default to `BLANK` and publish subject-only commits | Safely prevents review debris even when publication bypasses custom tooling. | Loses durable rationale and today's `Session-Id` attribution until another authority replaces it. | **Safe degradation option, not equivalent to the charter.** |
| Bypass the queue with a direct/admin merge carrying the message | The ordinary REST/GraphQL merge interfaces support custom messages. | Abandons the native queue and its current-main integration/required-check semantics. | **Reject for routine publication.** |
| Rebuild queue behavior in a custom service or rewrite the landed commit afterward | Full control over commit construction. | Security and operational burden, or history rewriting and changed commit identities. | **Reject.** |

Two seductive non-solutions remain excluded. HTML comments do not separate records
because their bytes still enter the commit, and another heading convention cannot help
unless the mutation that crosses the publication boundary can select that heading.

### 14.4 A safer fallback shape if projection transport is impossible

If the REST hypothesis is refuted, preserve the *separation principle* by separating
storage rather than repeatedly editing one mutable field:

- PR title: durable squash subject;
- PR body: concise, commit-safe public record, including any still-required
  `Session-Id` lines;
- one bot-managed top-level comment or check summary: rich verification and review-state
  record, with stable anchors and clear ownership;
- linked artifacts: raw or bulky evidence whose retention and access are explicit.

This reverses the original source/projection relationship: the commit-safe record is the
provider-native source, while detailed evidence lives on review-only surfaces. It is
less elegant for reviewers but more honest than depending on a transport the provider
does not guarantee. Before choosing it, investigate whether one editable comment or one
check summary gives better discoverability, edit history, permissions, and retention;
do not scatter evidence across unowned comments.

### 14.5 Falsifiable next experiment for the leading hypothesis

The next publication experiment requires separate merge authorization. It should be one
benign, single-purpose PR and one request; do not combine it with PR 1–3 implementation.

1. Use a unique sentinel in a short public body and a different unique sentinel in the
   PR-only review body.
2. Read GitHub's live squash subject projection and the exact PR head SHA immediately
   before mutation.
3. Submit the asynchronous request with API version `2026-03-10`, explicit
   `merge_action=merge_queue`, `merge_method=squash`, the expected `sha`, the exact live
   subject as `commit_title`, and the public projection as `commit_message`.
4. Require a new `202` response and persist its UUID. Treat `200` as an already-
   merged/already-enqueued stop. A `409` body can disclose an existing UUID, method,
   action, and head SHA, but not its custom title/body; observe that request if useful,
   but do not certify it as the reviewed publication or silently submit another.
5. Poll the UUID with bounded waits only through the asynchronous submission phase. On
   `enqueued`, switch to ordinary PR/merge-queue and merge-group monitoring. Treat
   transport loss or timeout as unknown state, query before retrying, and never submit a
   second irreversible request merely because the client missed the first response.
6. After the queue lands, compare subject and body byte-for-byte with the sent values;
   assert the public sentinel is present and the review sentinel absent; then verify the
   merge-group and post-merge CI run.

The experiment passes only on exact landed output. A `202`, an `enqueued` result, or a
successful CI run proves request acceptance, not message preservation. If the endpoint
returns `404`, the current GitHub host/token cannot use the candidate and publication
must fail closed rather than fall back to direct merge.

### 14.6 Broader principles worth carrying forward

- **Bind the projection to the irreversible operation.** A field on a preparatory or
  adjacent operation is not publication authority.
- **Receipts beat inferred state.** Asynchronous mutations should return an identity that
  can be polled after timeouts and process restarts.
- **Separate records by storage when transport cannot separate them.** Temporal swapping
  of one mutable record is concurrency control disguised as formatting.
- **Distinguish serialization proof from persistence proof.** Client source, request
  acceptance, queue entry, and landed bytes are four different claims.
- **Fail closed at ambiguous queue states.** Retrying an irreversible request after a
  lost response is less safe than reconciling by request ID and PR state.

### 14.7 Theorization checkpoint

Do not settle the final design yet. The REST asynchronous endpoint is sufficiently
promising to justify one controlled proof and sufficiently new/underspecified to forbid
building PRs 1–3 on assumption. If it passes, redesign the publication seam around the
single asynchronous request and its UUID rather than around `gh pr merge`. If it fails,
prefer durable storage separation over PR-body swapping. Repository `PR_BODY` remains
unchanged until one of those paths is proved end to end.

## 15. Derisk pass for the replacement implementation — 2026-09-03

This pass followed `.agents/skills/derisk/SKILL.md`. It investigated the REST fix and
the implementation seams without adding feature code, changing repository settings,
opening a PR, or invoking a merge endpoint. All GitHub calls were read-only, including
the request/result schema queries and a deliberately unknown result UUID.

### 15.1 Confidence-building plan

1. Prove the current host, API version, credentials, repository merge method, and merge-
   queue state can reach the documented asynchronous surface.
2. Derive the actual response state machine from GitHub's OpenAPI and an official client,
   not from the happy-path prose alone.
3. Exercise the installed `gh` process boundary: JSON through standard input, HTTP status
   plus body capture, non-2xx body retention, and cross-platform binary resolution.
4. Identify which source fields can be read together, which have an optimistic lock, and
   which metadata races remain structurally unavoidable.
5. Pressure-test post-merge equality against GitHub's line wrapping and generated
   co-author material.
6. Map the repository files, dependency changes, workflow rollout, tests, and fallback
   needed after the live transport proof.

### 15.2 Evidence and conclusions

| Question | Evidence gathered | Conclusion |
|---|---|---|
| Is the endpoint real and reachable on this host? | A request using `X-GitHub-Api-Version: 2026-03-10` selected that exact version. `GET .../merge-async/<unknown-uuid>` returned the endpoint-specific documentation URL. GitHub's 2026-03-10 OpenAPI marks both submit and result operations as not cloud-only and enabled for GitHub Apps. | **High confidence.** Only a real `PUT` can prove submit permission, but route/version availability is no longer speculative. |
| Does the repository satisfy the transport prerequisites? | The live repository reports squash merging enabled with `PR_TITLE` / `PR_BODY`; open PR #622 reports `isMergeQueueEnabled=true`. The authenticated session passed the read-only prerequisite probes; submit permission remains deliberately unproved. | **High confidence, pending the first submit.** |
| Can one read produce a coherent publication snapshot? | Live GraphQL exposes PR title/body, `headRefOid`, `updatedAt`, `viewerMergeHeadlineText(SQUASH)`, `isInMergeQueue`, `mergeQueueEntry`, and `autoMergeRequest`. | **Yes.** Fetch them in one query immediately before mutation. |
| What can be locked? | The REST `sha` field cancels the request if the PR head changes. Neither REST nor the live GraphQL inputs expose an expected PR `updatedAt` for the merge request. | **Code is locked; title/body are a snapshot, not an atomic metadata lock.** Print and send the same in-memory value with no saved-preview reuse. |
| Can the installed CLI transport exact JSON safely? | `gh api --input -` accepted a JSON buffer through standard input in a read-only GraphQL probe. `--include` returned status, headers, and JSON; on a real `404`, `gh` exited nonzero but retained the response body on stdout. A scratch parser passed documented `200`, `202`, and `409` fixtures plus the live HTTP/2 response. | **Yes.** Use `spawnSync` with an argument vector and a UTF-8 `Buffer`; do not pipe JSON through Windows PowerShell or put the body in argv. |
| Does the UUID track the whole merge? | OpenAPI defines `pending`, `enqueued`, `merged`, and `failed`. GitHub's `gh-stack` stops UUID polling at `enqueued` and tells the user the queue will finish later. | **No.** Implement two phases: async-request polling, then existing PR/merge-group completion monitoring. |
| Can a lost response be reconciled safely? | A documented `409` includes UUID, method, action, and expected SHA, but omits commit title/message. A `200 enqueued` response can omit the UUID entirely. | **Only partially.** Recover observation of the existing request, but never claim it carries the reviewed body. No blind retry or automatic direct-merge fallback. |
| Is `viewerMergeBodyText` an exact-body oracle? | On open PR #622 the PR body was 10,835 characters and the viewer body 10,903; the first difference was line wrapping at character 61, followed by a generated co-author block. In the latest 100 `main` commits, 90 had the same trailing separator/co-author shape. | **No.** The extracted projection is the sent-body authority. Use a single-author proof PR; design production verification around a closed, separately classified provider suffix only after the proof records its exact behavior. |
| Is the Markdown parser dependency stable? | `markdown-it@14.2.0` is installed only below `markdownlint-cli`, not declared directly. The earlier token-map experiment already proved it distinguishes real top-level H2s from headings in fences, blockquotes, and raw HTML. | **Parser approach is sound; add `markdown-it` as a direct dev dependency before import.** |
| Can the metadata workflow safely become required immediately? | GitHub documents that required checks attach to the latest commit SHA, but the same-SHA ordering behavior after a PR-body-only `edited` event remains unmeasured here. | **No immediate hard gate.** Retain the report-only rollout and make the local publication validator authoritative until live same-SHA evidence exists. |

Official primary sources used in this pass:

- [GitHub REST 2026-03-10 asynchronous merge API](https://docs.github.com/en/rest/pulls/pulls?apiVersion=2026-03-10#merge-a-pull-request-asynchronously)
- [GitHub REST API description](https://github.com/github/rest-api-description/blob/main/descriptions-next/api.github.com/api.github.com.2026-03-10.yaml)
- [GitHub's `gh-stack` async client](https://github.com/github/gh-stack/blob/main/internal/github/merge_async.go)
  and [two-phase caller](https://github.com/github/gh-stack/blob/main/cmd/merge.go)
- [GitHub required-status-check troubleshooting](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks)

### 15.3 Corrected publication state machine

```text
one GraphQL snapshot
  -> validate and render exact title/body/head
  -> authorized PUT returns 202 + UUID
  -> poll UUID while pending
       -> failed: stop and report
       -> merged: reconcile PR and verify commit (unexpected queue shortcut)
       -> enqueued: switch observers
  -> watch mergeQueueEntry + PR state + merge_group run
       -> PR open and entry gone: queue rejection; investigate
       -> PR merged: fetch landed commit
  -> verify subject, public-body bytes, forbidden review sentinel, and final main CI
```

Preflight `isInMergeQueue=true`, submit `200 enqueued`, submit `409`, missing UUID,
unexpected method/action/SHA, `404`, and malformed JSON are all fail-closed branches.
The implementation must preserve the raw response in its diagnostic output without
printing credentials or the full rich PR review body.

### 15.4 Implementation shape after the proof

Keep the mutation separate from preview. The likely implementation boundary is:

- a pure v2 projection/parser library consumed by the current preview and tests;
- a small async-response parser/state classifier tested with every documented status and
  malformed/duplicate-header fixtures;
- an authorized enqueue command that imports `resolveGhBin`, performs one GraphQL
  snapshot, writes the request JSON as a UTF-8 buffer to `gh api --input -`, and never
  invokes a shell;
- the existing required-check watcher before enqueue and the existing PR/merge-group
  completion logic after `enqueued`;
- report-only PR-body workflow rollout before branch-protection registration;
- both `.agents/skills/publish/SKILL.md` and `.claude/skills/publish/SKILL.md`, plus
  canonical publication documentation, updated together when behavior actually changes.

Required transport tests before a production mutation:

- `202 pending` with UUID and exact method/action/SHA;
- `pending -> enqueued`, `pending -> failed`, and bounded timeout;
- `200 enqueued`, `200 merged`, `409 pending`, `400`, `403`, `404`, `422`;
- missing/mismatched UUID, SHA, method, or action;
- HTTP/1.1 and HTTP/2 header parsing with CRLF/LF and a non-2xx JSON body;
- multiline Unicode title/body passed as a UTF-8 stdin buffer;
- already-queued preflight and head movement between snapshot and submit;
- landed body with no provider suffix and with only the exact allowed provider suffix;
- proof that a review-only sentinel cannot pass post-merge verification.

The live proof should ride with a legitimate independent change rather than create a
standalone one-file tempdoc PR. Use a single-author, single-commit candidate so the first
transport verdict is not confounded by GitHub's generated co-author suffix. Require a
fresh `202`; an already-existing request does not test the intended payload.

### 15.5 Remaining risks and confidence

| Risk | Residual severity | Why it remains |
|---|---|---|
| Queue discards or transforms explicit `commit_message` | **Critical** | Only a live queued squash can settle the core hypothesis. |
| Title/body edit in the snapshot-to-submit interval | **Medium** | There is no metadata optimistic lock; the sent snapshot remains coherent, but PR display can move. |
| Ambiguous pre-existing async request | **High** | Response metadata cannot prove its custom body. Fail closed rather than merge on inference. |
| Provider-generated body suffix changes | **Medium** | The observed shape is stable but not documented as an API contract. Keep it a closed parser with post-merge diagnostics. |
| Same-SHA required-check selection after body edits | **Medium** | Official documentation guarantees the SHA, not which same-context run GitHub selects. Report-only rollout still needed. |
| API version/host drift | **Low for GitHub.com, medium for other hosts** | Explicit versioning and endpoint-specific failures make drift visible. |

**Implementation confidence: 6/10 before the live proof; 8/10 if §14.5 lands the exact
public body and excludes the review sentinel.** The route, client transport, response
parser, preflight fields, queue state machine, and repository integration seams now have
direct evidence. The one remaining critical unknown is the provider behavior that caused
the original failure.

**Difficulty: 8/10 (hard).** The pure Markdown work is ordinary. The difficulty is the
irreversible two-stage state machine, partial recovery metadata, provider-added commit
material, same-SHA metadata checks, and coordinated policy/skill/documentation rollout.

**Model recommendation:** use the strongest-capability class, specifically
`gpt-5.6-sol` at `xhigh` reasoning, for the live proof and mutation/state-machine slice.
After that proof passes and the transport contract is frozen in fixtures,
`gpt-5.6-terra` at `high` is sufficient for the parser, workflow, template, and
documentation slices. Keep the root agent responsible for the authorized merge action.

## Status

The stale branch-protection prerequisite is merged and verified. The `gh pr merge`
auto-merge transport is explained and refuted. The REST replacement is now derisked at
the route, API-version, CLI-process, response-parser, preflight, and queue-state-machine
levels, but custom-body persistence is still unproved. Keep `PR_BODY`, do not start PRs
1–3, and do not change repository settings until §14.5 passes or the storage-separation
fallback is selected and designed.

## 16. Implementation plan — 2026-09-03

This plan followed both repository projections of the `plan` skill. It separates local,
reversible implementation from GitHub mutations because implementation authorization is
not publication authorization. The current branch remains a dedicated worktree; the root
agent owns all shared-state and publication decisions. A bounded read-only subagent was
asked to challenge the integration seams and test matrix, while code changes remain with
the root agent.

### 16.1 Slice A — pure publication projection

- [x] Declare `markdown-it` as a direct development dependency; do not rely on
  `markdownlint-cli`'s transitive installation.
- [x] Extract a pure v2 projection module that parses top-level Markdown structure with
  token maps and slices the original normalized source, preserving public-body bytes
  apart from documented boundary-newline normalization: convert the GitHub body to LF,
  remove only blank lines adjacent to the section boundaries, and preserve all interior
  characters and lines, including trailing spaces.
- [x] Make `## Public commit` the only landed-body source and `## Review record` the
  review-only source. Reuse the canonical session-ID helpers instead of defining another
  session syntax.
- [x] Validate subject, section cardinality/order, public-body size and a closed set of
  forbidden public markers (`Session-Id:`, Markdown task boxes, HTML comments, and exact
  fenced-log labels defined in fixtures), review authorship, verification/scope/review
  evidence, and the publication-specific `dependabot[bot]` trust case. Treat uncertain
  transcript/log prose as a warning rather than inventing a broad scoring regex. Return
  structured errors and warnings rather than exiting inside the module.
- [ ] After the live proof, replace preview v1's exact-`Testing` and whole-body heuristics
  with the v2 projection in the atomic activation slice. Preserve a human-readable full
  preview and provide a versioned JSON result for scripts; no live consumer requires the
  v1 result shape. The proof-substrate PR deliberately retains v1 compatibility.
- [x] Add adversarial fixtures for fenced, quoted, and raw-HTML headings; Unicode and
  multiline content; duplicate/missing/reordered sections; checklist/log/comment
  leakage; malformed session IDs; every authorship class; and warning/error thresholds.

### 16.2 Slice B — asynchronous merge transport, inert by default

- [x] Add a pure parser/classifier for `gh api --include` responses. Cover final HTTP/1.1
  and HTTP/2 blocks, CRLF/LF, non-2xx JSON bodies, malformed/duplicate headers, missing
  fields, and all documented asynchronous states.
- [x] Add an enqueue command that performs one GraphQL PR snapshot, builds the v2
  projection in memory, validates repository/PR/head/queue preconditions, and displays
  the exact subject/body plus a SHA-256 body fingerprint.
- [x] Keep dry-run as the default. Require an explicit execute flag plus matching expected
  head SHA and expected body fingerprint before any mutation. Never accept a saved body
  file as the source for a later submit.
- [x] Submit an authorized request as a UTF-8 standard-input buffer to `gh api`, using the
  shared `resolveGhBin` helper, an argument vector, API version `2026-03-10`,
  `merge_method=squash`, `merge_action=merge_queue`, and the same in-memory subject/body.
- [x] Require a fresh `202 pending` response with UUID and exact method/action/SHA. Treat
  `200`, `409`, malformed responses, mismatches, and all other statuses as fail-closed
  diagnostics; never retry blindly or fall back to direct merge.
- [x] Poll only the request phase (`pending -> enqueued|merged|failed`) with a bounded
  timeout and dependency-injected process/time boundaries. At `enqueued`, stop with an
  explicit handoff to the repository's currently manual PR/merge-group procedure rather
  than claiming that prose-only procedure is existing reusable observer code.
- [x] Add unit/integration-fixture coverage for the full §15.4 matrix, including exact
  Unicode bytes through stdin, pre-existing queue state, moved head, timeout, and proof
  that the rich review record never enters the request payload or diagnostic output.

### 16.3 Live proof — blocked on publication authorization

- [ ] Select a legitimate single-author, single-commit change whose publication is useful
  independently of this experiment; do not create a one-tempdoc proof PR.
- [ ] Open/update that PR with unique public and review sentinels, obtain required checks,
  and run the enqueue command with a fresh `202`. These are remote mutations and require
  explicit per-action authorization.
- [ ] Observe the UUID only to `enqueued`, then the queue entry, `merge_group` checks, PR
  merge, landed commit, and final `main` CI.
- [ ] Prove exact subject and public-body preservation and prove review-sentinel absence.
  Record the raw status/state evidence without credentials or the rich PR body.
- [ ] If the proof fails, stop rollout and design the storage-separation fallback. Do not
  weaken equality, silently accept provider transformations, or resurrect direct merge.

### 16.4 Rollout only after the proof passes

- [ ] Add the PR template's explicit `Public commit` and `Review record` schema and a
  report-only metadata workflow on `pull_request`, `pull_request_target`, and/or
  `merge_group` only after validating the same-SHA event behavior.
- [ ] Update both publish skills together, retaining each harness's native interaction
  style while replacing `gh pr merge --body-file` with the proved enqueue command and
  two-phase observer. Before editing `.agents/skills`, resolve the conflict between the
  current injected instruction that calls it generated and the checked-in prompt-surface
  governance document that calls it independently hand-authored; do not guess a missing
  generator.
- [ ] Update canonical publication guidance in the agent guide, `MAINTAINING.md`, and
  `CLAUDE.md`; remove superseded CLI-body instructions and old template prose in the same
  change.
- [ ] Amend ADR-0045 and `docs/decisions/README.md` when the shipped publication behavior
  changes. Update the active risk register/temporary history rather than presenting the
  pre-proof design as current truth.
- [ ] Add a forward supersession note to tempdoc 653's whole-PR-body source assertion.
- [ ] Regenerate governed/derived documentation and skill projections using the
  repository maintenance workflow; never hand-edit generated regions.
- [ ] After report-only evidence, change the repository squash default from `PR_BODY` to
  `BLANK`, update the history-policy source/check/tests, register the required check, and
  audit at least 30 subsequent landed commits before closing the migration.

### 16.5 Teardown and verification contract

- [ ] Delete preview v1's exact-`Testing`, 5,000-character, whole-body-comment, and
  review-in-commit assumptions when v2 replaces it. Do not keep dual schemas or a hidden
  v1 compatibility path with no consumer.
- [ ] Remove every live `gh pr merge --squash --body-file` publication instruction when
  the new route ships. Keep historical tempdocs intact and mark superseded plans as such.
- [ ] Either promote proof-only fixtures/helpers into maintained production tests or
  delete them in the rollout change; no orphan experimental transport remains.
- [ ] Run the focused Node tests for projection, preview, transport, workflow/policy, and
  skill/documentation governance after each slice, and explicitly register every new
  Node test in CI rather than assuming file discovery. Then run the repository's complete
  agent/governance verification sequence and inspect the diff for secrets, generated
  drift, stale instructions, and unsourced claims.
- [ ] Do not claim the feature complete while the live proof, rollout, settings migration,
  and post-merge audit remain unchecked. Local Slice A/B completion is an implementation
  milestone, not publication success.

### 16.6 Current execution boundary

Proceed now with Slices A and B in this worktree. They are local and reversible. The live
proof, PR creation/update, push, merge, branch-protection changes, and repository settings
changes remain blocked on separate explicit authorization. Nothing else is blocked on the
user: implementation and local verification continue autonomously.

## 17. Superseded local implementation evidence — 2026-09-03

This section records the pre-proof implementation milestone. It is dated evidence, not
the current shipped design: §19's authorized live request refuted the transport, and the
async client, snapshot adapter, enqueue command, tests, and `checksWait` export described
below were deleted before merge. The v2 parser/projection and its focused CI test are the
only implementation retained from this milestone.

### 17.1 Superseded implementation snapshot

- `scripts/ci/lib/squash-message-projection.mjs` owns the v2 Markdown projection. It
  normalizes GitHub CRLF input to LF, trims only blank boundary lines, preserves interior
  content, accepts headings only from Markdown token maps, and keeps the rich review body
  out of its returned PR summary.
- Root-level `Session-Id:` declarations reuse `merge-links.mjs` validation. Declarations
  in fences, raw HTML, blockquotes, lists, or indented code are rejected and cannot satisfy
  agent/mixed attribution, so a valid projected commit cannot disagree with the later raw
  history reader.
- `scripts/ci/lib/github-publication-snapshot.mjs` owns the live GraphQL snapshot used by
  the queue client. The existing v1 `preview-squash-message.mjs` remains supported until
  the live proof permits the template/workflow/skill activation slice; the queue client's
  dry-run is the exact v2 subject/body preview for proof PRs.
- `scripts/ci/lib/github-async-merge.mjs` owns request construction, HTTP response parsing,
  sanitized diagnostics, receipt metadata validation, request-state classification, and
  bounded polling. Expected PR head and landed merge-commit SHA are distinct fields.
- `enqueue-squash-message.mjs` is dry-run by default. Execution first waits for required
  checks, then takes a fresh GraphQL snapshot. Mutation requires exact head, body, and full
  request fingerprints. JSON goes to `gh api` as a UTF-8 buffer, never argv or a shell.
  Every API child process has a bounded timeout; an ambiguous PUT timeout reports unknown
  external state and explicitly forbids retry.
- The command accepts only a fresh `202 pending` receipt with a valid UUID and matching
  method/action/head. `200`, `409`, every other non-202 status, response mismatch, malformed
  data, and already-queued/auto-merge preflight state stop without retry or direct-merge
  fallback. Receipt polling stops at `enqueued`, `merged`, or `failed` and hands later
  reconciliation to the still-manual publication procedure.
- Both focused tests are wired into the existing CI fact lane; `checksWait` is exported
  from the established `run-gh.mjs` substrate rather than duplicated.

### 17.2 Refute-first corrections

The bounded read-only subagent review found and the implementation corrected:

1. a title-edit race not covered by head/body confirmation — fixed with a full request
   fingerprint and regression fixture;
2. unbounded child processes around an irreversible request — fixed with per-call
   deadlines and an explicit unknown-state/no-retry diagnostic;
3. opaque-block session examples satisfying authorship — fixed with root-content
   classification and fence/raw-HTML/blockquote fixtures;
4. merged commit SHA being mislabeled as expected head SHA — fixed with separate fields;
5. a stub-only process test — fixed with an injected fake spawn boundary that asserts
   binary/argv/stdin/timeout behavior and transport failure classification;
6. insufficient one-shot failure evidence — fixed by proving 400/403/404/409/422 each
   cause one PUT and zero polls/retries;
7. unchecked head shape — fixed with a local 40-hex object-ID precondition.

The remaining review warning was resolved before publication preparation: the supported
v1 preview and its tests were restored, the GraphQL snapshot moved to a queue-client
library, and the v2 projection retained its own focused test. The later live refutation
means the queue-client library and command do not ship; only the parser/projection remains
as inert evidence. Template/workflow/skill/ADR activation remains deferred.

### 17.3 Historical pre-proof verification

- `npm ci` — passed from the lockfile.
- `node scripts/dev/run-gh.test.mjs` — all 24 checks passed.
- `node scripts/ci/test-preview-squash-message.mjs` — passed.
- `node scripts/ci/test-squash-message-projection.mjs` — passed.
- `node scripts/ci/test-enqueue-squash-message.mjs` — passed.
- `node scripts/ci/check-workflow-triggers.mjs` — passed; workflow policy remains aligned.
- `npm run test:governance` — all 28 governance test files passed.
- `npm run test:agent-analytics` — all 65 agent-analytics test files passed.
- `node scripts/docs/skills-sync.mjs --check` — passed; generated Claude skill regions
  remain aligned.
- `node scripts/docs/llmstxt-generate.mjs --check` — passed; the 116-document index is
  current.
- Root npm audit ratchet — passed at 11 high / 0 critical against a 16 high / 0 critical
  baseline. The broader report warned that its `ssot-tools` audit subprocess could not
  spawn `cmd.exe`; that unrelated target was not used as evidence for this implementation.
- Live read-only GraphQL/preview against PR #622 returned its projected subject, head SHA,
  queue state, and the expected v2 contract errors. The enqueue command's default dry-run
  repeated that result without invoking `PUT`.

Two verification batches had invocation errors: the first named the existing `run-gh`
test incorrectly and stopped before the implementation tests ran; a later static search
used a Unix-style wildcard that Windows rejected after the syntax checks passed.
Repository CI triage identified the actual `scripts/dev/run-gh.test.mjs`, and the search
was rerun with `rg -g`. The corrected commands and all intended checks passed. Neither
incident was a suppressed test failure.

### 17.4 Historical closeout state

Local implementation commit: `610311e2 feat(921): implement fail-closed queue publication
client`. It is intentionally unpushed because implementation authorization did not grant
publication authorization. World-state at `2026-09-03T18:02:49Z` reported this worktree
clean, four commits ahead of `origin/main`, zero behind, and not pushed; this closeout
note is the fifth local commit.

The session-closeout helper sweep deleted nothing. It left another session's live
`ui-shot` lease as contention and reported the ownerless `otlp-sink` singleton, whose
purpose is to outlive sessions. Neither process belonged to this worktree, so both were
correctly left alone.

**BLOCKED ON YOU:** none. The user explicitly authorized publication after this closeout,
including the ordinary branch push, PR, and one fresh queue request needed for §16.3.

**PROCEEDING / DONE:** the local v2 projection, preview, fail-closed enqueue client,
required-check preflight, response parser/state machine, process boundary, CI wiring,
tests, and implementation evidence are complete. Template/workflow/skill/ADR activation,
repository-default changes, required-check registration, and the 30-merge audit remain
deliberately deferred until the live proof passes.

## 18. Publication preparation — 2026-09-03

The user explicitly authorized publication. The candidate remains inert proof evidence:
it does not change the PR template, publish skills, repository merge default, branch
protection, or ADR-0045's shipped contract. The existing v1 preview and its test are
byte-identical to `origin/main`; the retained v2 parser/projection has a focused test.

`git fetch origin` found the candidate zero commits behind `origin/main`. Before the first
push, the caught-up candidate passed:

- `./gradlew.bat build -x test --console=plain` — `BUILD SUCCESSFUL` (251 tasks);
- `./gradlew.bat test --console=plain` — `BUILD SUCCESSFUL` (186 tasks);
- root `npm ci`, 65/65 agent-analytics test files, 28/28 governance test files,
  workflow-trigger policy, skill sync, and `llms.txt` generation checks;
- UI `npm ci`, TypeScript typecheck, and 468/468 files / 6,267/6,267 unit tests;
- v1 preview, v2 projection, async enqueue, and `run-gh` focused tests;
- `git diff --check` and a six-commit `gitleaks` scan.

The read-only subagent audit found no secret value, machine-local path, or internal-only
URL in the public diff. It did identify unnecessary disclosure of credential type/scope;
that metadata was removed. The history-comparison counts are now explicitly labelled as
dated session observations whose one-off API output is not a canonical metric.

The first Gradle attempt correctly stopped before starting because other worktrees held
the shared build slot. Verification resumed only after the wrapper-process check reported
the slot free; no foreign process was interrupted.

## 19. Authorized live proof result — 2026-09-03

PR #627 supplied distinct public/review sentinels and passed all required PR checks. The
v1 preview reported zero warnings; the v2 dry-run produced:

- head `d3df1b097756e3a35e8695ef14389c9af0ce4324`;
- body SHA-256 `ef0ad6b6147f8a1759cb3e173f19ee59ea594a26fcdfe790934d67e664510ca8`;
- request SHA-256 `c8fd40bb33b4a0677bd56ef952345d930b9e8a602abaa44b38b816797afdcc7a`.

After the required-check watcher passed, the command recomputed the same snapshot and sent
one authorized request. GitHub returned HTTP `422` with:

> Custom merge params (merge_method, commit_title, commit_message) are not supported with
> the merge_queue merge action.

The command stopped exactly as designed. No queue entry, merge, retry, or direct-merge
fallback occurred. This refutes §14.3's central hypothesis despite the versioned API
schema accepting all of those request fields individually. The asynchronous custom-message
transport must be deleted rather than published as dormant production code.

### 19.1 Unsafe temporal swap rejected; permanent separation selected

A refute-first review rejected the proposed archive/swap/restore client. GitHub exposes no
conditional PR-body update, so a fresh read followed by `PATCH` cannot prevent overwriting
a concurrent edit. Queue membership can also disappear transiently before the PR reports
`MERGED`; interpreting one absent snapshot as rejection could restore the rich body while
the queue is still constructing the commit. A nonzero local `gh pr merge` result is not
proof that no remote enqueue occurred. Crash recovery would additionally require a durable
transaction journal, exact archive verification, queue timeline evidence, guarded resume,
and conflict handling. That machinery would only make a temporary dual-use field less
unsafe; it would not remove the underlying race.

The normal design is therefore §14.4's permanent storage separation:

- the PR title/body are commit-safe from the point they become publication inputs;
- rich verification and review state live in a durable PR comment or check summary;
- the ordinary merge queue remains the sole publication path;
- no automation restores rich review text to `PR_BODY` after enqueue or merge.

For PR #627 itself, the already-reviewed rich body will be preserved once as a PR comment,
then the PR body will be replaced permanently by its validated public projection before
ordinary guarded enqueue. This is a one-way migration of the proof PR, not the rejected
temporal-swap mechanism. The branch ships only the parser/projection and historical
evidence; workflow/template/skill/ADR activation requires a separate design and proof of
the permanent review-record surface.

## 20. Publication outcome and landed-shape correction — 2026-09-03

PR [#627](https://github.com/justsearch-app/justsearch/pull/627) preserved its reviewed
body in a durable issue comment, permanently replaced the PR body with the commit-safe
projection, and entered the ordinary protected merge queue. Merge-group run
`33792087109` and post-merge `main` run `33792401990` passed. The queue landed commit
`6f097583e199e01f4ede82cf303f1d715bb17ca4`; the public sentinel is present and the
review sentinel is absent.

The landed message is semantically the public PR body, but GitHub hard-wrapped long
Markdown lines. Therefore raw byte equality is not a valid publication promise. The
durable contract is canonical content and meaning: no review-record fields, provider
banners, task state, or operational logs may cross the boundary, while GitHub-owned line
reflow and co-author material are allowed. Public bodies should avoid tables and deeply
nested structures whose meaning depends on exact wrapping.

## 21. Permanent review-record design — 2026-09-03

### 21.1 Decision

The PR title and complete PR body are commit-safe from creation through merge. One managed
PR issue comment is the current rich review record. It uses a unique versioned marker,
records the PR number, covered head SHA, and public-body SHA-256, and contains exactly one
`## Review record` with:

- one `Authorship: agent | human | mixed | trusted-bot` declaration;
- one non-empty `### Scope and risk` section;
- one non-empty `### Verification evidence` section;
- one non-empty `### Review state` section.

The comment is a bounded current-state index, not an append-only transcript. Bulky logs
remain in reproducible checks or explicitly retained artifacts and may be linked from the
comment. The command updates only a comment authored by the authenticated actor, fails on
duplicate markers or ownership mismatch, and verifies the exact returned/read-back body.
The GitHub update endpoint has no compare-and-swap precondition, so the authenticated
owner must be the sole writer from dry-run through exact read-back. The command detects
changes visible at final preflight or read-back, but does not claim atomic exclusion of a
same-owner manual edit between those operations.
PR bodies do not carry a review-comment URL: the PR Conversation already exposes the
comment, the squash commit links back to its PR, and copying the URL into every public
body would add review-process metadata to the durable history.

The ordinary merge queue and repository `PR_TITLE` / `PR_BODY` setting remain unchanged.
No automation restores review text to the body. A check summary may later project
freshness plus the stable comment URL, but it is never the evidence authority.

### 21.2 Evidence and rejected alternatives

- GitHub issue comments have stable PR-timeline URLs, a create/update API, visible edit
  history, and no documented scheduled expiry. PR comments cannot be pinned.
- Check runs are tied to a commit SHA and are archived after 400 days, then deleted ten
  days later. They are unsuitable as the only durable review record and add Checks-write,
  fork-association, and merge-group behavior.
- A future required `Publication record` check would need a proven trusted-base event path
  for comment edits plus a same-name `merge_group` result. That proof does not exist, so
  this slice does not add a workflow or broaden CI permissions.
- PR #627 already proves that a member-authored review comment survives the one-way body
  migration and remains readable after merge.

General principle: when one host field is projected into a durable public artifact, keep
that field permanently safe for the projection and move mutable operational evidence to a
separate host object. Do not simulate atomicity with reversible mutations across objects
when the host exposes no transaction or compare-and-swap primitive.

This repository-local representation earns its keep while one managed comment stays
current and substantially richer review evidence remains discoverable without polluting
commit history. Retire or redesign it if duplicate/stale-comment repair becomes more
expensive than the history defects it prevents, or if GitHub introduces a durable native
review-evidence object with atomic merge-queue projection controls.

## 22. Implementation and verification plan

### 22.1 Contract and tooling

- [x] Replace the inert co-located v2 projection with a split-input parser: the whole PR
  body is public input and the managed comment is review input. Retain Markdown-token
  parsing, body bounds, provider/process rejection, authorship, trusted-bot, and
  `Session-Id:` validation.
- [x] Add a fail-closed `pr-review-record` CLI over the REST issue-comment endpoints.
  Default to dry-run; require a fingerprint confirmation for create/update; paginate all
  comments; bind the operation to PR number, head SHA, public-body hash, actor, and review
  file; reconcile an ambiguous mutation once by read-back and never retry blindly.
- [x] Cover parsing, duplicate/ownership failures, Unicode standard-input transport,
  create/update/read-back, moved head/body, malformed responses, and ambiguous outcomes
  with injected-boundary tests. Register every focused test in CI.
- [x] Replace the PR template with public-only durable prose and add a separate copyable
  review-record template. Update the preview to reject review/template/process residue and
  report the concise public record without requiring verification detail in the commit.

### 22.2 Workflow and documentation activation

- [x] Update both hand-authored publish skills so publishers maintain the comment, run the
  strict check, preview the public body, then use the ordinary merge queue. Preserve each
  harness's native interaction instructions.
- [x] Amend ADR-0045, its decision index, the agent guide, `MAINTAINING.md`,
  `CONTRIBUTING.md`, `CLAUDE.md`, and the history-policy note. State semantic rather than
  byte-exact landed-message verification.
- [x] Add forward supersession notes where canonical-looking historical tempdocs still
  prescribe the whole rich PR body or custom transport. Do not rewrite dated evidence.
- [x] Do not add a comment-writing workflow, Checks writer, required context, or new CI
  permission in this slice. Reconsider only after a live event/SHA proof.

### 22.3 Teardown, publication, and measured follow-up

- [x] Delete the co-located `## Public commit` / `## Review record` extraction contract and
  its obsolete fixtures in the same change. Do not recreate the rejected async or
  temporal-swap clients.
- [x] Run focused Node tests, workflow/policy checks, skill/doc regeneration checks, the
  complete governance and agent-analytics suites, compilation/tests, UI verification,
  diff checks, and a public-content/secret scan before publication.
- [x] Critically review the implementation against this plan and obtain an independent
  refute-first review before fixing any findings.
- [ ] Publish only with explicit publication authorization. After activation lands, audit
  the next 30 squash merges for public-body hygiene, managed-comment freshness, duplicate
  markers, and semantic landed projection. This item is time-gated, not implementable from
  the pre-activation sample; schedule it rather than pretending it has run.

### 22.4 Derisk result

Read-only REST inspection confirmed the existing PR #627 archive comment's stable ID/URL,
member authorship, exact body, and post-merge readability. The authenticated CLI has the
repository scope needed for an eventual authorized comment mutation. Official GitHub
documentation confirms issue-comment update semantics and check-run retention; local
workflow policy confirms that adding a required check now would also require an explicit
merge-group contract. The main remaining risk is ambiguous network completion around a
comment mutation or an overlapping same-owner edit. It is contained by fingerprint
locking, actor ownership, a documented sole-writer window, one read-back reconciliation,
and no automatic retry; GitHub does not expose a conditional comment update.

Implementation confidence: **8/10**. Difficulty is moderate: use the balanced agentic
coding class with high reasoning (the active `gpt-5.6-terra` class is sufficient). The
design does not need the strongest-capability tier unless live GitHub behavior contradicts
the tested REST model.

## 23. Implementation and critical review — 2026-09-04

The split publication contract is implemented in the isolated
`codex/921-permanent-review-record` worktree, based exactly on `origin/main` at
`f783dd0868b1e04b21129c01b396fbf46e8a7092`. The branch has not been pushed and no
new PR, issue comment, workflow, check, or merge-queue mutation has been created.

The implementation:

- treats the complete PR body as the public squash input and rejects review/template,
  provider, task-state, HTML-detail, stack/base, and process residue;
- manages one marker-owned review comment through a dry-run/fingerprint/execute CLI,
  with complete pagination, repository-permission validation, explicit repository
  resolution, exact read-back, and no blind retry after an ambiguous mutation;
- exposes GitHub's unconditional comment-update limitation in each update plan and in
  canonical/publisher documentation, requiring the authenticated owner to remain the sole
  writer through the short dry-run-to-read-back window;
- keeps the ordinary merge queue and existing `PR_TITLE` / `PR_BODY` repository setting,
  while adding only local/hosted regression invocation and no write-capable workflow;
- updates the PR and review templates, both publisher skills, canonical maintainer and
  agent guidance, ADR-0045 plus two premise-specific probes, and historical supersession
  notes.

The independent refute-first review initially found six issues: a fail-open preview exit,
a production-repository fallback, missing pre-mutation actor authorization, ambiguous
success/read-back reporting, an overclaimed compare-and-swap guarantee, and an ADR probe
that combined two independently breakable premises. All six were corrected. A second pass
found that pagination and actor-permission transport tests could pass without exercising
their required arguments; those tests now pin the exact calls. The final independent pass
reported no remaining actionable issue.

A later explicit `review-changes` pass found two additional boundary defects. The public
residue detector used raw text and both missed common top-level Test plan / Tests /
Verification headings and falsely rejected the same text inside a Markdown fence. The
managed-comment validator also inferred its synthetic pre-create case from a missing
comment ID, so malformed fetched data could bypass the trusted-association check and plan
an update with a null ID. Both are fixed: residue classification now uses top-level
Markdown tokens, while synthetic validation is an explicit internal mode and fetched
comments require a positive integer ID, named author, and trusted association. Regression
fixtures cover the previously missed headings, opaque fence/blockquote text, malformed
live comment shape, and the no-mutation failure path.

Post-fix verification passed:

- all three focused publication-record suites, including CLI exit behavior, paginated and
  malformed comment responses, unauthorized creation, moved head/body, create/update
  ambiguity, success plus failed read-back, and sole-writer disclosure;
- repository history policy, workflow-trigger policy, ADR coverage, canonical links,
  module-dependency canonical state, runtime-config matrix, generated `llms.txt`, generated
  skill sections, and prompt-surface inventory;
- 33/33 governance and 65/65 agent-analytics test files;
- the earlier full verification on the same implementation worktree: Gradle compile and
  tests, UI typecheck, and 468/468 UI test files (6,269/6,269 tests).

A live read-only check against merged PR #627 exercised the real paginated GitHub
transport. It correctly failed because that historical proof predates the new `:v1`
managed marker and its body still contains the now-forbidden `Testing:` review residue;
the strict preview also exited nonzero. The repository-wide secret scan found only an
ignored generated protobuf build artifact false positive; review of the tracked diff found
no credential, machine-local path, or internal-only URL.

The implementation is ready for a local commit. Publication and the 30-merge post-land
audit remain open because they require separate authorization and elapsed production data,
respectively.

The subsequent `review-tempdoc-fit` pass reread the complete tempdoc and found no current
design mismatch. The implementation follows §§19–23's permanent-storage-separation
authority rather than the superseded co-located-body or custom-transport proposals: the PR
body is permanently commit-safe, one managed comment owns mutable review evidence, the
ordinary queue and `PR_BODY` setting remain intact, no write-capable workflow or required
check was introduced, and the 30-merge measurement remains explicitly time-gated.

## 24. Publication outcome — 2026-09-04

The user separately authorized publication. PR #632 is the publication vehicle for this
tempdoc and its implementation. The `PUBLISHED` status above is intentionally part of that
same squash candidate: this revision can appear on `main` only after the required checks and
merge queue accept it. The permanent public commit uses the concise PR body, while the PR's
single managed review-record comment carries the detailed scope, verification, and review
state. The post-land 30-merge audit remains an elapsed-data obligation rather than work that
can be completed in this publication run.

Publication exposed a separate hosted-CI transport defect before merge: two consecutive
`Public claims` attempts reached the unchanged `Build kernel gate inputs` step and then
hit the job's 15-minute ceiling. The preceding module-dependency generator had already
printed its completion path; the next command was the npm-audit reporter, whose three
synchronous registry calls had no deadline. The publication candidate now bounds each
audit call at 30 seconds and makes missing, timed-out, signalled, or unparseable target
evidence fail the npm-audit gate. This prevents both an indefinite job-level timeout and
the more dangerous fallback where unavailable evidence normalized to zero advisories.

The refute-first review of that recovery found three additional fail-open shapes and one
retained-consumer gap before the fix was published. The shared report validator now
requires complete non-negative integer vulnerability and dependency counts; root and
`ui-web` lockfiles are explicitly required, while only the retired `ssot-tools` target may
be reported as non-applicable. Both the discipline-gate enforcer and the retained legacy
ratchet reject unavailable or incomplete evidence, and `--write-baseline` cannot turn a
transport failure into a zero baseline. The fixture self-test and focused producer plus
legacy-consumer regression suites exercise those contracts in hosted CI.

Final verification passed the producer and retained-ratchet suites, the npm-audit fixture
self-test, all 33 governance tests, all 65 agent-analytics tests, workflow/history policy,
ADR coverage, canonical-link and generated-document checks, and every focused publication
record suite. A second independent refute-first pass found no actionable issue. A real
local run while the registry advisory endpoint remained unavailable terminated both
required audits at their 30-second bounds and produced two `report-unavailable` errors;
the negative control therefore fails explicitly and cannot publish zero evidence.

## 25. Hosted advisory transport redesign — 2026-09-04

The bounded npm transport fix proved the gate could fail closed, but the next hosted run
showed that bounded failure was not sufficient availability: npm's bulk advisory POST
accepted the request body and returned no response within either the install's five-minute
fetch timeout or the reporter's explicit deadline. A separate hosted run on an older
revision also proved the pre-fix parser had converted the same transport-error JSON into a
false zero-advisory success. This is a source-authority problem, not a timeout-tuning
problem.

The durable design preserves the externally stable `npm-audit` kernel gate id and its
classified changeset protocol, but supersedes all of its provider-specific evidence and
count-only state:

- exact package versions come from the two existing required lockfiles (`root` and
  `modules/ui-web`);
- bounded, retryable GET requests query GitHub's Global Security Advisories API in
  URL-length-safe batches, with complete pagination and the workflow's read-only
  `GITHUB_TOKEN`;
- the report records provider identity, lockfile digest, package-version cardinality, and
  deduplicated advisory identities; an unavailable target is explicit evidence and fails
  the gate;
- the baseline accepts individual high/critical GHSA identities per target and pins each
  accepted severity. A new identity or an upward severity change is a regression, so an
  unrelated advisory disappearing can no longer cancel a new one through an unchanged
  count;
- a declared regression must repin that same advisory identity in the same change. A
  directly added baseline identity remains a classified baseline shift, preserving the
  kernel's no-silent-relaxation invariant;
- the old npm JSON producer, count baseline, compatibility ratchet, fixtures, and their
  workflow/module-filter references are removed in the same change. The historical gate
  id remains only to avoid needless churn in SARIF and changeset routing; its title and
  canonical documentation name the current GitHub-advisory evidence source.

CI installs for these already-covered lockfiles use `--audit=false`, eliminating the
duplicate best-effort POST that consumed five minutes before the authoritative gate ran.
Repository vulnerability alerts and automated security updates become the ambient
monitoring layer; the per-PR gate remains the deterministic lockfile fact lane. Automatic
version-update configuration, advisory issue creation, and a scheduled compatibility run
of npm's provider-specific endpoint are intentionally out of scope because they would add
separate policy and notification authorities without improving the merge fact.

This is broader than PR-publication tooling: the reusable principle is that a required
security fact must be backed by a bounded, observable evidence protocol and compared by
stable defect identity rather than aggregate count. The retirement condition is either a
repository-native dependency-review signal with equivalent lockfile coverage and baseline
semantics, or loss of the Global Advisory API's unauthenticated/read-only contract. In
either case, replace this source in place; do not run two required vulnerability
authorities indefinitely.

### 25.1 Remaining implementation plan

- [x] Replace the npm POST producer with the batched GitHub advisory producer and injected
  transport tests covering pagination, retry, timeout, malformed data, target absence,
  deduplication, and lockfile extraction.
- [x] Replace count comparison with advisory-identity/severity comparison, migrate the
  governed baseline and fixtures, require same-change repinning, and remove the retained
  legacy ratchet.
- [x] Wire the new artifact into `Public claims`, disable duplicate install audits for the
  covered lockfiles, and update module filters and expected-state guidance.
- [x] Update canonical gate documentation and ADR-0044's fact-lane consequences, regenerate
  derived docs/skills, and run focused plus complete governance verification.
- [x] Enable and read back GitHub vulnerability alerts and automated security updates.
- [ ] Refresh PR #632's managed review record and public body, then resume the authorized
  publication flow.

### 25.2 Implementation evidence

The producer queried the real root and `ui-web` lockfiles successfully with the current
GitHub API version in about two seconds, covering 312 and 364 unique package versions and
returning 62 and 21 deduplicated advisories respectively. The initial high/critical
identity baseline contains 22 root and 15 `ui-web` GHSAs. These values are evidence about
the migration snapshot, not future count thresholds; only the recorded identities and
their accepted severities govern later runs.

Focused tests cover batching, pagination, deduplication, transient retry, non-transient
HTTP failure, explicit timeout signals, malformed payloads, package-lock parsing, missing
targets, duplicate/stale report evidence, new identities, severity escalation, declared
but unpinned regressions, silent and declared baseline shifts, and the rule that rebalance
cannot accept a new advisory. The live gate passed against a freshly regenerated report.

GitHub returned `204 No Content` for enabling and reading back repository vulnerability
alerts. Automated security fixes returned enabled and unpaused, and the repository's
`security_and_analysis.dependabot_security_updates.status` read-back is `enabled`. The
immediate Dependabot alert list was empty; that is not used as proof of coverage because
initial dependency-graph indexing is asynchronous.

Verification passed all 34 governance test files and every gate fixture, all 65
agent-analytics test files, workflow-trigger/history/hook/Codex parity checks, ADR coverage,
governance-dashboard freshness, generated docs/skills, canonical links, module-dependency
and runtime-config projections, prompt-surface inventory, the focused
`WireShapeMandateTest`, and the full multi-module Gradle test task. The remaining checkbox
is now only the already-authorized PR-record refresh and publication sequence.
