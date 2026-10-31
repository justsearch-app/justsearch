---
schema: test-intent.v1
task: t-20261029-fs993c
author-role: builder
author-session: fe-branch-domain
---

# Branch and version owner preservation

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.acceptedOutputCompatibility.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "split"
      ],
      "whatMoved": "branchInto moved from SearchV3View to branchDomain. The two structural casts and the two calls follow the member into the new owner; all assertions, inputs and Ask accesses are unchanged. The parameterized checks remain XN1 branch inherits only the accepted output after %s and D-T43 failed saved output contributes nothing after branching (%s).",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261029-fs993c request, 2026-10-30",
          "quote": "proceed with step 4"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed with step 4",
          "delegationLocation": "task t-20261029-fs993c request, 2026-10-30",
          "delegationScope": "Step 4 of the agreed frontend restructuring: move the Search view's areas out one at a time, nothing the user sees changes, under the same safety net of scenario checks",
          "choice": "Move branch and version derivation and orchestration into a browser-free owner, adapt only moved member accesses, and preserve reservation and claim/read-before-resend ordering with HTTP-boundary checks.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-branch-domain.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts"
      ],
      "class": "New",
      "behaviour": "Retain all 52 existing branch tests and add nine preservation cases (seven declarations, two parameterized twice). At HTTP fetch-start observe the acknowledged rerun task before branch POST; hold child history and observe child claim before both record reads, no dispatch during the wait, and resend only after release. Independently hold the child thread response while child history completes, and require dispatch to wait for release. Refuse task-bearing recovery writes by exception or missing acknowledgement and retain the inline rewrite without branch POST or dispatch; also fail only the first task save while later writes succeed, with the same refusal and retained rewrite. Exclude a concurrent Retry while the first reserved fork is pending, then issue exactly one dispatch. Leave the screen and reload during a held branch POST, then resolve the old response and observe no child claim, child history read or resend. Separately disconnect and reconnect the same view during that POST, and require no child claim (including transient claims), child thread/history reads or dispatch when the abandoned response arrives. All 61 cases pass on unchanged production 5aff3acd28 in a shared clone and on the extraction. Each of the three added contracts detects its corresponding fault in a scratch clone. This existing test file stays gated; SearchV3View.ask.test.ts also walks views/search-v3. No catalog observations are added.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261029-fs993c request, 2026-10-30",
          "quote": "proceed with step 4"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed with step 4",
          "delegationLocation": "task t-20261029-fs993c request, 2026-10-30",
          "delegationScope": "Step 4 of the agreed frontend restructuring: move the Search view's areas out one at a time, nothing the user sees changes, under the same safety net of scenario checks",
          "choice": "Move branch and version derivation and orchestration into a browser-free owner, adapt only moved member accesses, and preserve reservation and claim/read-before-resend ordering with HTTP-boundary checks.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-branch-domain.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts"
      ]
    }
  ]
}
```

Independent acceptance is left to the verifier. No catalog observations or test-only frontend helper files were added.

Tests added or rewritten: SearchV3View.branch.test.ts — reservation acknowledgement including one-time save failures, independent thread/history waits before resend, concurrent Retry, abandoned fork after leaving/reload or same-instance reconnect — durable. SearchV3View.acceptedOutputCompatibility.test.ts — moved branchInto member access only — durable. HTTP snapshots, response holds, storage fault injection, claim subscription and rendering drains are local scaffolding.

Fault checks ran separately against candidate 11600e464d in a temporary shared clone with copied dependencies. The complete changed branch test file passed all 61 cases on candidate 11600e464d and baseline 5aff3acd28 (a separate shared clone, test file copied unchanged; no moved-member reversal was needed). Each fault was removed before introducing the next:

- Ignoring `reserveHumanTask`'s first `saveRecovery(task)` result: both first-save cases failed at `expect(backend.branches).toEqual([])`, with `AssertionError: expected [ { parent: 'uc-branch', …(1) } ] to deeply equal []`. Received one branch POST naming parent `uc-branch` and fromMsgId `11111111-2222-4333-8444-555555555551`; 2 failed, 59 skipped, exit 1.
- Removing the post-POST `humanOwned(task)` guard: the same-instance reconnect case failed at the claim assertion, with `AssertionError: expected [ 'uc-branch-branch-1' ] to not include 'uc-branch-branch-1'`; 1 failed, 60 skipped, exit 1.
- Starting `refreshRecord(id)` without awaiting it in `openConversation`: the independent thread hold case failed at `expect(backend.dispatches).toEqual([])`, with `AssertionError: expected [ { …(2) } ] to deeply equal []`. Received a dispatch to `uc-branch-branch-1` asking `and the second one?` while the thread response was held and history had completed; 1 failed, 60 skipped, exit 1.

The accepted-output inheritance test still serves the same records for parent and child, so it cannot establish inheritance by the branch operation. Its catalog-bound title and its checks are unchanged in this test-quality round; this remains a verification limitation.
