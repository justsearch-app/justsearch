---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: 01a163e4-5c06-7641-b243-d2e06dbb7ea4
---

# Test-intent entries

All scenario and EB1 choices below are agent-adopted under the quoted design delegation, not owner-adopted. These are durable checks; no existing tests or bounded-marker expectations were weakened.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/conversationExistenceCache.test.ts"
      ],
      "class": "New",
      "behaviour": "EC1: one positive existence read per held identity, including overlapping refreshes. EB1: recheck after a marker-window gap and retain confirmations across covered advances.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP5.2b design choices to reduce repeated conversation existence reads while preserving CD4 and WP5.2; agent-adopted scenarios and EB1, not owner-adopted scenarios.",
          "choice": "EC1 A window that refreshes its conversation list repeatedly asks the Engine whether a conversation still exists at most once per Engine run for each conversation the Engine said exists; 20 refreshes with 25 held beyond-limit conversations make about 25 existence reads in total, not 500.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP5.2b design choices to reduce repeated conversation existence reads while preserving CD4 and WP5.2; agent-adopted scenarios and EB1, not owner-adopted scenarios.",
          "choice": "EC3 (preserves WP5.2) A conversation deleted in another window while the Engine keeps running disappears from this window on its next refresh, whether or not this window had cached \"exists\" for it.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP5.2b design choices to reduce repeated conversation existence reads while preserving CD4 and WP5.2; agent-adopted scenarios and EB1, not owner-adopted scenarios.",
          "choice": "Keep the deletion-marker list bounded (newest 100; CD4 F4 stays). Add to the conversation-list response a count of deletions this Engine run has made, and use the Engine's existing instanceId. A window keeps an \"exists\" answer only while both hold: the instanceId is the one it saw when it asked, and the deletion count has advanced by no more than the markers the list can carry since the window's previous refresh. Otherwise it forgets every cached \"exists\" and asks again once. Older Engines without the count: no caching (behaviour as today).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-conversation-existence-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/conversationExistenceCache.test.ts#EC1 reads each of 25 held beyond-limit identities once across 20 refreshes",
        "modules/ui-web/src/shell-v0/state/conversationExistenceCache.test.ts#EC1 coalesces existence reads from overlapping refreshes",
        "modules/ui-web/src/shell-v0/state/conversationExistenceCache.test.ts#EB1 rechecks all cached identities when more than 100 deletions hide a missed delete",
        "modules/ui-web/src/shell-v0/state/conversationExistenceCache.test.ts#EB1 keeps confirmations through repeated advances within the marker window",
        "modules/ui-web/src/shell-v0/state/conversationExistenceCache.test.ts#EB1 uses the marker capacity when a restored deletion leaves a shorter marker list"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/conversationExistenceCache.test.ts"
      ],
      "class": "New",
      "behaviour": "EC2–EC5 preservation: restart and late-reply safety, same-run delete markers, retry inconclusive reads, retain drafts and beyond-limit conversations without storing the cache. EB1: require current identity and valid deletion metadata.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP5.2b design choices to reduce repeated conversation existence reads while preserving CD4 and WP5.2; agent-adopted scenarios and EB1, not owner-adopted scenarios.",
          "choice": "EC2 (preserves CD4 F1) A window that missed a delete, including across an Engine restart, still drops a conversation the Engine says no longer exists, on its next refresh after the restart. A cached \"exists\" answer from before an Engine restart is never trusted after it.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP5.2b design choices to reduce repeated conversation existence reads while preserving CD4 and WP5.2; agent-adopted scenarios and EB1, not owner-adopted scenarios.",
          "choice": "EC3 (preserves WP5.2) A conversation deleted in another window while the Engine keeps running disappears from this window on its next refresh, whether or not this window had cached \"exists\" for it.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP5.2b design choices to reduce repeated conversation existence reads while preserving CD4 and WP5.2; agent-adopted scenarios and EB1, not owner-adopted scenarios.",
          "choice": "EC4 \"Could not answer\" (error, timeout, old server) is never cached: the next refresh asks again, and the conversation stays meanwhile.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP5.2b design choices to reduce repeated conversation existence reads while preserving CD4 and WP5.2; agent-adopted scenarios and EB1, not owner-adopted scenarios.",
          "choice": "EC5 (preserves CD4) Local-only drafts and conversations merely beyond the list limit stay; no new saved state on disk; no change to what the user sees.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP5.2b design choices to reduce repeated conversation existence reads while preserving CD4 and WP5.2; agent-adopted scenarios and EB1, not owner-adopted scenarios.",
          "choice": "Keep the deletion-marker list bounded (newest 100; CD4 F4 stays). Add to the conversation-list response a count of deletions this Engine run has made, and use the Engine's existing instanceId. A window keeps an \"exists\" answer only while both hold: the instanceId is the one it saw when it asked, and the deletion count has advanced by no more than the markers the list can carry since the window's previous refresh. Otherwise it forgets every cached \"exists\" and asks again once. Older Engines without the count: no caching (behaviour as today).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-conversation-existence-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/conversationExistenceCache.test.ts#EC2 EB1 drops a cached identity after an Engine restart with lost markers",
        "modules/ui-web/src/shell-v0/state/conversationExistenceCache.test.ts#EC3 removes a cached identity from same-run deletion markers",
        "modules/ui-web/src/shell-v0/state/conversationExistenceCache.test.ts#EB1 does not cache with unavailable or invalid deletion count undefined",
        "modules/ui-web/src/shell-v0/state/conversationExistenceCache.test.ts#EB1 does not cache with unavailable or invalid deletion count -1",
        "modules/ui-web/src/shell-v0/state/conversationExistenceCache.test.ts#EB1 does not cache with unavailable or invalid deletion count 1.5",
        "modules/ui-web/src/shell-v0/state/conversationExistenceCache.test.ts#EB1 does not cache with unavailable or invalid deletion count 0",
        "modules/ui-web/src/shell-v0/state/conversationExistenceCache.test.ts#EB1 does not cache with unavailable or invalid deletion count 9007199254740992",
        "modules/ui-web/src/shell-v0/state/conversationExistenceCache.test.ts#EB1 does not cache without current Engine identity",
        "modules/ui-web/src/shell-v0/state/conversationExistenceCache.test.ts#EC4 retries an inconclusive answer on the next refresh and keeps the conversation",
        "modules/ui-web/src/shell-v0/state/conversationExistenceCache.test.ts#EC5 keeps local drafts and beyond-limit conversations without saving the cache",
        "modules/ui-web/src/shell-v0/state/conversationExistenceCache.test.ts#EB1 discards confirmations if deletion metadata disappears or regresses",
        "modules/ui-web/src/shell-v0/state/conversationExistenceCache.test.ts#EC2 EB1 a late pre-restart answer cannot populate the new Engine cache",
        "modules/ui-web/src/shell-v0/state/conversationExistenceCache.test.ts#EC5 a newer refresh does not reuse an earlier absence for a restored beyond-limit identity"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationExistenceMetadataHttpTest.java"
      ],
      "class": "New",
      "behaviour": "EB1: real HTTP list responses reuse the existing runtime instanceId, count all successful deletions beyond the bounded newest-100 markers, and start a fresh count under a new Engine identity.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP5.2b design choices to reduce repeated conversation existence reads while preserving CD4 and WP5.2; agent-adopted scenarios and EB1, not owner-adopted scenarios.",
          "choice": "Keep the deletion-marker list bounded (newest 100; CD4 F4 stays). Add to the conversation-list response a count of deletions this Engine run has made, and use the Engine's existing instanceId. A window keeps an \"exists\" answer only while both hold: the instanceId is the one it saw when it asked, and the deletion count has advanced by no more than the markers the list can carry since the window's previous refresh. Otherwise it forgets every cached \"exists\" and asks again once. Older Engines without the count: no caching (behaviour as today).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-conversation-existence-metadata.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationExistenceMetadataHttpTest.java"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "wp5.2b-t5uoot23",
  "verdict": "accept",
  "digest": "sha256:de191bf6009c85b4f4ffd8d0ecdc93bb82f5cbf8cd671c71a8f2753b972a6cb7",
  "note": "Intent sources match EC1-EC5 and EB1. Independent scratch evidence reproduces assertion fail-before and pass-after; real HTTP/browser scenarios pass. Existing Java suite dependency failure and skipped corpus metric remain unverified."
}
```
