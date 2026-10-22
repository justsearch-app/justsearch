---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: 01a16588-7f50-7043-91b3-f56c8319c56f
---

# Test-intent entries

Scenarios are agent-adopted under the quoted design delegation (delegated-choice), never owner-adopted. All checks below are durable. No acceptance record is authored by the builder.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The fake list now distinguishes an ordinary recent window from an exact, complete fork response. Optional gates hold or refuse fork reads and reproduce the existing locked-history 423 response without parent pointers. Existing mutation, dispatch, history-ordering, Ask/Delegate and navigation expectations stay. The deliberately changed empty-continuation pager expectation is separately covered by New below.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.4a branch-discovery design and scenarios S16.1–S16.5, preserving BN1–BN4; agent-adopted, not owner-adopted.",
          "choice": "The pager shows every version of a fork however old it is and however many conversations exist (beyond the list's 100-row cap), with the same members on the base and on each branch, live, after reload and after an Engine restart. With forks at two different turns, a branch of a branch and an empty branch, each turn shows only the versions that fork there; a conversation that is both a branch and a parent pages its parent's fork at its first own turn and its own forks at later turns. These reads do not replace or enlarge the sidebar's recent list.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.4a branch-discovery design and scenarios S16.1–S16.5, preserving BN1–BN4; agent-adopted, not owner-adopted.",
          "choice": "Branch, Edit, Retry and pager navigation on Ask and Delegate turns (WP1.2 S2.5, N2.1).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.4a branch-discovery design and scenarios S16.1–S16.5, preserving BN1–BN4; agent-adopted, not owner-adopted.",
          "choice": "Sidebar list, its order and size, renaming, the locked-store notice.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts"
      ],
      "class": "New",
      "behaviour": "S16.1–S16.4: complete old fork sets from both sides, empty and terminal continuations, separate turns and generations, pending/failed/removed version honesty, parent label and accessible original navigation. Retry sends while a fork read is pending. All are durable checks.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.4a branch-discovery design and scenarios S16.1–S16.5, preserving BN1–BN4; agent-adopted, not owner-adopted.",
          "choice": "The pager shows every version of a fork however old it is and however many conversations exist (beyond the list's 100-row cap), with the same members on the base and on each branch, live, after reload and after an Engine restart. With forks at two different turns, a branch of a branch and an empty branch, each turn shows only the versions that fork there; a conversation that is both a branch and a parent pages its parent's fork at its first own turn and its own forks at later turns. These reads do not replace or enlarge the sidebar's recent list.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.4a branch-discovery design and scenarios S16.1–S16.5, preserving BN1–BN4; agent-adopted, not owner-adopted.",
          "choice": "Creating a branch (Edit, Retry, Branch) or deleting one updates the pager on both sides. While a read is pending or after it failed, the pager shows no count or marks it as not current; it never presents an out-of-date count as current, and never blocks reading or sending.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.4a branch-discovery design and scenarios S16.1–S16.5, preserving BN1–BN4; agent-adopted, not owner-adopted.",
          "choice": "An open branch says which conversation it came from (the parent's opening question, or a neutral name when hidden or locked) and offers a control to open the original, reachable by keyboard and announced. A branch of a branch names its immediate parent. A base conversation shows none of this. Same live and after reload.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.4a branch-discovery design and scenarios S16.1–S16.5, preserving BN1–BN4; agent-adopted, not owner-adopted.",
          "choice": "Opening the original shows the parent conversation; Back returns to the branch.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.4a branch-discovery design and scenarios S16.1–S16.5, preserving BN1–BN4; agent-adopted, not owner-adopted.",
          "choice": "Branch, Edit, Retry and pager navigation on Ask and Delegate turns (WP1.2 S2.5, N2.1).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.4a branch-discovery design and scenarios S16.1–S16.5, preserving BN1–BN4; agent-adopted, not owner-adopted.",
          "choice": "Sidebar list, its order and size, renaming, the locked-store notice.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.4a branch-discovery design and scenarios S16.1–S16.5, preserving BN1–BN4; agent-adopted, not owner-adopted.",
          "choice": "For S16.3, use the neutral name 'Original conversation' when the immediate parent's opening question cannot be read. Use a native 'Open original' button whose accessible name is 'Open original: <parent name>'. This names the immediate parent without exposing hidden or locked content.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-branch-discovery-ui-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#shows the base as version 1 of the fork and pages forward into the branch",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#S16.1 discovers every old version on both sides after reload without enlarging recents",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#S16.2 hides counts while pending and failed and preserves typing",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#S16.2 refreshes old fork membership on creation and deletion",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#S16.3 S16.4 names the immediate parent and opens it through an accessible native control",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#S16.3 restores the neutral immediate-parent control for an old locked branch",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#S16.3 uses a neutral name for a hidden parent on an empty branch",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#S16.1 pages an empty continuation at the last answer from base and branch",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#S16.1 keeps the parent fork on the first own turn and own forks on later turns",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#S16.2 never labels a removed open branch as the base version while absence is unconfirmed",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#BN1 S16.2 Retry sends while complete fork reads are still pending",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#S16.1 S16.2 waits for branch lineage before presenting a fork count"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts"
      ],
      "class": "New",
      "behaviour": "S16.2 preservation: a successful reply from an older, cancelled refresh cannot resurrect a deleted version. Durable check; passes on the before-state too.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.4a branch-discovery design and scenarios S16.1–S16.5, preserving BN1–BN4; agent-adopted, not owner-adopted.",
          "choice": "Creating a branch (Edit, Retry, Branch) or deleting one updates the pager on both sides. While a read is pending or after it failed, the pager shows no count or marks it as not current; it never presents an out-of-date count as current, and never blocks reading or sending.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-branch-discovery-ui-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#S16.2 ignores a stale successful read after a newer deletion refresh"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatControllerForkDiscoveryTest.java"
      ],
      "class": "New",
      "behaviour": "S16.1–S16.2 complete exact-fork HTTP list semantics beyond both recent caps, filter identity for old-server detection, empty-prefix and deeper scopes, input rejection at the read boundary. Durable checks.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.4a branch-discovery design and scenarios S16.1–S16.5, preserving BN1–BN4; agent-adopted, not owner-adopted.",
          "choice": "The pager shows every version of a fork however old it is and however many conversations exist (beyond the list's 100-row cap), with the same members on the base and on each branch, live, after reload and after an Engine restart. With forks at two different turns, a branch of a branch and an empty branch, each turn shows only the versions that fork there; a conversation that is both a branch and a parent pages its parent's fork at its first own turn and its own forks at later turns. These reads do not replace or enlarge the sidebar's recent list.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.4a branch-discovery design and scenarios S16.1–S16.5, preserving BN1–BN4; agent-adopted, not owner-adopted.",
          "choice": "Creating a branch (Edit, Retry, Branch) or deleting one updates the pager on both sides. While a read is pending or after it failed, the pager shows no count or marks it as not current; it never presents an out-of-date count as current, and never blocks reading or sending.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.4a branch-discovery design and scenarios S16.1–S16.5, preserving BN1–BN4; agent-adopted, not owner-adopted.",
          "choice": "Read-only list-filter design: parentSessionId and branchPointMessageId identify one fork and must be supplied together; reject an incomplete pair, blank branch point or unsafe parent id with 400. A filtered response echoes the requested fork so an older Engine that ignores unknown filters cannot masquerade as complete. The existing unfiltered two-store list, limit, shape filter, ordering and response stay unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-branch-discovery-java-repair.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatControllerForkDiscoveryTest.java#discoversOnlyOneForkBeyondTheRecentCap",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatControllerForkDiscoveryTest.java#filterReflectsCreationDeletionAndHiddenContent",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatControllerForkDiscoveryTest.java#rejectsPartialOrUnsafeForkFilters"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatControllerForkDiscoveryTest.java"
      ],
      "class": "New",
      "behaviour": "BN4 preservation: absent filters keep the default 20-row recent window, 100-row cap, order and shape filtering. Durable check. The existing unmodified ChatControllerConversationJoinTest also checks joined run/store readers.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.4a branch-discovery design and scenarios S16.1–S16.5, preserving BN1–BN4; agent-adopted, not owner-adopted.",
          "choice": "`GET /api/chat/conversations` without the new filter answers exactly as today for UI, API and MCP readers.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-branch-discovery-java-preservation.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatControllerForkDiscoveryTest.java#unfilteredListKeepsDefaultLimitCapOrderAndShapeFilter"
      ]
    }
  ]
}
```

## Acceptance records
