---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b83-vdu-review
---

Four reviewer findings on the picture-reading (VDU) branch, each settled by a check on the real path
and repaired. A vision call refused by a closed generation gate now returns its document to PENDING
and gives back the attempt the mark counted, so the idle trigger, which looks only at PENDING, picks
it up again and temporary refusals never spend the retries kept for documents that really fail.
Every MCP protocol method the server answers and every resource it reads declares one activity
role: the index summary, the indexed roots, catalog resources, the handshake, the catalogs and ping
are observations with named reasons; the facet reads, prompts/get and any undeclared resource stay
deliberate. A window that loads the conversation list while another window's automatic title is
still fresh looks again once that entry would be stale and asks if nobody renewed it. A late
automatic title no longer makes the window write its own rename again.

```json
{
  "entries": [
    {
      "items": ["modules/app-engine/src/test/java/io/justsearch/app/engine/VduDeferredRetryTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "Over the real index half (a Lucene index with the VDU retry field, WorkerIngestService, the Engine's in-process EngineKnowledgeClient) and the production OfflineCoordinator, VduBatchProcessor and VduOfflineTriggerSampler, with a stand-in vision call that takes the production GenerationTracker's admission: while a generative settings change holds generation drained, each idle trigger tick starts a pass, the pass reaches the document's vision call, and the document is PENDING afterwards (one refusal, and three in a row); the first idle tick after the drain ends reads it and it is COMPLETED. Separately, three passes started directly while drained report AI_OFFLINE with no failed document, and the first admitted pass reads the document and completes it (deferrals do not spend the three attempts). On 0a79303bb all three cases fail by assertion: the document is PROCESSING after the first refused call, and after three refused passes the admitted pass processes nothing because the fourth mark exceeds the retry budget.",
      "sources": [
        {"kind": "delegated-choice", "delegationQuote": "do whatever you think is best. your working autonomously", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z", "delegationScope": "how a picture-reading pass treats a document whose vision call the generation gate refuses", "choice": "The b83 builder contract records the design: a person's activity interrupts a pass at the next document boundary and the document stays pending; the reviewer's finding V1 is to be settled by lastDeferredVduDocumentIsAutomaticallyRetriedAfterConfigDrain with three successive refusals followed by successful admission. The repair returns the refused document to PENDING with its attempt given back (a new in-process worker operation, releaseVduProcessing), so automatic scheduling retries it and refusals do not count as attempts.", "label": "agent choice"}
      ],
      "evidence": "gates/test-intent/evidence/b83-vdu-review.json",
      "newChecks": ["modules/app-engine/src/test/java/io/justsearch/app/engine/VduDeferredRetryTest.java"]
    },
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/VduRequestActivityTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "earlierRequestDoesNotInterruptAndObserversDoNotResetIdle (extended; its earlier steps unchanged): during an accepted pass, over real HTTP admission and the production pass interrupt, an unattended MCP client's resources/read of justsearch://index/summary, justsearch://index/roots and justsearch://resource/core.index-status, its resources/list, tools/list and prompts/list, and a resources/subscribe the server does not answer neither interrupt the pass nor reset the idle clock. On 0a79303bb it fails by assertion at the first resources/read (the summary read interrupts the pass). The parameterized case that reads an undeclared resource (justsearch://document/pending) still interrupts. Build cleanup asserts active bootstrap mocks and the constructed controller adapter prerequisite in VduRequestActivityTest, retaining original inputs, resource lifetimes and every existing assertion.",
      "sources": [
        {"kind": "delegated-choice", "delegationQuote": "do whatever you think is best. your working autonomously", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z", "delegationScope": "which MCP protocol methods and resources count as a person acting", "choice": "The b83 builder contract records the design: every route and MCP tool declares exactly one activity role; anything a client asks for deliberately is USER_ACTION, unattended observation is OBSERVATION with a named justification; decide the role of every MCP resource and other MCP protocol methods by the same rule. Status-like reads (index summary, indexed roots, catalog resources) and discovery or keepalive (initialize, the three list methods, ping) are observations; the facet reads, which run searches, prompts/get and undeclared resources are deliberate; an unanswered method is refused and is not activity.", "label": "agent choice"}
      ],
      "evidence": "gates/test-intent/evidence/b6d536-build-green-0a79303bb-cab5500a9a.json",
      "newChecks": ["modules/ui/src/test/java/io/justsearch/ui/api/VduRequestActivityTest.java#earlierRequestDoesNotInterruptAndObserversDoNotResetIdle"]
    },
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpActivityDeclarationTest.java"],
      "class": "New",
      "behaviour": "The methods the MCP dispatch answers equal the declared methods plus tools/call and resources/read, and the URIs readResource dispatches (plus the catalog-resource prefix) equal the declared resources, both read from the production source; validateMcpProtocol accepts the production declarations, rejects an observation without a reason and a reason whose declaration is deliberate; an undeclared or missing URI is deliberate and a catalog resource is an observation. These pin new declarations (METHOD_USER_ACTIVITY, RESOURCE_USER_ACTIVITY and validateMcpProtocol do not exist on 0a79303bb, so it cannot compile there).",
      "sources": [
        {"kind": "delegated-choice", "delegationQuote": "do whatever you think is best. your working autonomously", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z", "delegationScope": "how the MCP activity declarations are kept complete", "choice": "The b83 builder contract asks that the role of every MCP resource and protocol method be decided with the structural check covering them. The declarations are two maps beside the dispatch they describe, their observation reasons a separate table checked in both directions at class load, and this test compares them with the dispatch the server runs.", "label": "agent choice"}
      ],
      "evidence": "gates/test-intent/evidence/b83-mcp-declarations.json",
      "newChecks": ["modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpActivityDeclarationTest.java"]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/state/conversationListStore.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "recentPendingAutoTitleResumesAfterReloadWithoutFurtherInput (added; existing cases unchanged): a window loads the list once, with two automatic titles still pending from 10 s before (one whose asker was replaced by this reload, one another window keeps renewing), and nothing touches it afterwards. Within the asker's retry interval it asks for neither; the abandoned one is asked for once by 120 s and its name shows; the renewed one is never asked for and stays pending; the window sends only the list read and the automatic-title request, both observations. On 0a79303bb it fails by assertion: no automatic-title request is ever sent.",
      "sources": [
        {"kind": "owner-words", "quote": "Coordinate with the inference lane's host contract (priority and aging).", "location": "task t-20261005-b6d536 request (item 5)", "provenance": "quote corrected to the owner's exact words; it was paraphrased as \"coordination with the inference lane's priority and aging.\""},
        {"kind": "delegated-choice", "delegationQuote": "do whatever you think is best. your working autonomously", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z", "delegationScope": "how a pending automatic title survives a reload of the window that was asking", "choice": "The b83 builder contract asks to keep one window owning a title attempt at a time and untouched UIs sending nothing that counts as activity. A window that finds a fresh pending entry looks again when it would be stale (plus a random moment within the first retry interval, so several waiting windows do not ask together) and asks only if the entry is still unsettled and was not renewed.", "label": "agent choice"}
      ],
      "evidence": "gates/test-intent/evidence/b83-vdu-review.json",
      "newChecks": ["modules/ui-web/src/shell-v0/state/conversationListStore.test.ts#recentPendingAutoTitleResumesAfterReloadWithoutFurtherInput"]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.honesty.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "a late automatic title never writes this window's rename over a newer rename from another tab (added; existing cases unchanged): a stand-in server stores the automatic title when the request arrives and holds its answer; the reader renames the conversation in this window (written through as theirs), another tab then renames it on the server, and the held answer is released. The server keeps the other tab's name and this window sent exactly one title write, the reader's rename. On 0a79303bb it fails by assertion: the window wrote its rename again when the answer landed, over the newer name. The same write-back is on main 52e01bef4.",
      "sources": [
        {"kind": "delegated-choice", "delegationQuote": "do whatever you think is best. your working autonomously", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z", "delegationScope": "what a window does when its automatic title lands after a rename", "choice": "The b83 builder contract records that renames beat automatic titles and asks to repair finding V4 whether or not it is pre-existing. The window writes nothing when the answer lands: the server stores the automatic title only over no name and the rename was written through when it was made.", "label": "agent choice"}
      ],
      "evidence": "gates/test-intent/evidence/b83-vdu-review.json",
      "newChecks": ["modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.honesty.test.ts#a late automatic title never writes this window's rename over a newer rename from another tab"]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS2-20261015",
  "verdict": "accept",
  "digest": "sha256:d09eaba9454d0f1b218bff19aec314275a8402745070ad493c7df872ee45ad1d",
  "note": "Reviewed deferred retry, neutral MCP observations and automatic-title preservation against their intent sources. Own candidate checks passed; own 0a79303bb Java/frontend reruns reproduced the declared PENDING/retry, observer-interruption and title assertions."
}
```
