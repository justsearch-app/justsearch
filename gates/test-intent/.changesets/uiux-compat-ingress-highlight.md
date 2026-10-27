---
schema: test-intent.v1
task: uiux-compat-ingress-highlight
author-role: builder
author-session: codex-uiux-compat-ingress-builder
---

# Test-intent entries

These contracts are agent-adopted (delegated-choice). Sources are W3/C8/A7 and A3/C9
in the supplied WP1.5 agreement and the corrected bounded builder brief. The correction
settles 0-based inclusive bounds: finite integers with 0 <= start <= end. No old assertion,
baseline or runner configuration is weakened. No author acceptance is provided.

Causes on f17b988e2f: `router/navigationHandler.ts:193` parks only canonical Search tasks;
Ask's catalog schema instead hydrates the old `ask` store, which Search no longer reads.
Shape-mounted Search rejects incoming hand-offs at `SearchV3View.ts:3982`.
Retired aliases work because the resolver redirects to canonical Search; canonical
addresses already use its hand-off preparation and validation. Separately,
`plugin-api/capabilities/ui.ts:58` copies identity but omits both highlight fields.
The earlier repair also rejected line 0 with `start > 0`.

The first compatibility/highlight repair's checks live in two new files; the S67 extension below adds one separate file. HostApiSelection.test.ts and
SearchV3View.handoffs.test.ts are byte-identical to f17b988e2f, preserving earlier
evidence and acceptance digests. On the baseline with only these two new files overlaid,
14 repair checks fail at the intended assertions and all 23 preservation checks pass;
the candidate passes all 37. The correction alone was also reproduced against the earlier
uncommitted repair: five line-zero checks failed (three trust tiers and two rendered widths).

DOM probes at 900 and 1568 mock the observed box and assert overlay/inline mode, one reader,
its path/range and actual painted text, including the first line and negative-range refusal.
These are DOM tests, not real browser geometry evidence. File-error HTTP/SSE fixtures
are controlled responses, not native Engine file-read evidence.

The unchanged official evidence producer is invoked for repair and preservation ids.
To satisfy the builder's shared-clone/private-dependencies rule, a temporary Git adapter
translates only worktree creation/removal into shared-clone/detached-checkout/removal,
with plain npm ci in each clone. A private temporary object directory with read-only
source alternates holds the producer's throwaway-index working-tree objects. No source
worktree registration, shared node_modules or source object writes are needed.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts"
      ],
      "class": "New",
      "behaviour": "New public-address and public-plugin regression checks for agent-adopted W3/C8/A7 and A3/C9: exact prepared inputs, no dispatch until Send, chosen request scope, visible file refusal, valid 0-based inclusive range propagation and painted lines in inline/overlay presentations. Existing test files are byte-identical to f17b988e2f.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "Task t-20261019-bd718f agreement, quoted in /home/elias/dev/justsearch/tmp/verify/agreed/wp1.5.md (WP1.5 source and W3/C8/A3/A7 amendments)",
          "delegationScope": "Design and implement the JustSearch UI/UX Search v3 cutover, preserving supported hand-offs, human Send and document reading.",
          "choice": "Agent-adopted W3/C8/A7: supported compatibility links retain exact text and chosen files, select Ask/model-only/Structured and wait for Send; invalid or unavailable scope is visibly refused without broadening. Preserve retired aliases and canonical Search links. Agent-adopted A3/C9: public plugin inspector opens one reader on the right file with the supplied passage. The corrected builder brief specifies finite integer bounds with 0 <= start <= end, otherwise no highlight and no error; retain the existing 0-based inclusive line convention.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-compat-ingress-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation CORE keeps a valid 0-based inclusive passage",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation TRUSTED_PLUGIN keeps a valid 0-based inclusive passage",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation UNTRUSTED_PLUGIN keeps a valid 0-based inclusive passage",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector at width 900 paints valid range 6 to 8 only",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector at width 1568 paints valid range 6 to 8 only",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector at width 900 paints valid range 0 to 1 only",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector at width 1568 paints valid range 0 to 1 only",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C8 W3 A7 compatibility address core.ask-surface keeps text and files until Send",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C8 W3 A7 compatibility address core.free-chat-surface keeps text and files until Send",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C8 W3 A7 compatibility address core.extract-surface keeps text and files until Send",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C8 A7 a warm compatibility address replaces preparation and still waits",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C8 A2 an invalid file scope through core.ask-surface keeps text and refuses Send",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C8 A7 file link through core.ask-surface with missing file refuses without widening scope",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C8 A7 file link through core.ask-surface with unreadable file refuses without widening scope"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts"
      ],
      "class": "New",
      "behaviour": "New preservation checks for retired aliases and canonical Search address text/files/request scope; canonical invalid, missing and unreadable file refusal without widening; absent, partial, reversed, negative, fractional, nonfinite or nonnumeric plugin highlight bounds are ignored without throwing or retaining a previous highlight. Negative-start ranges paint no lines at both widths. Existing test files are byte-identical to f17b988e2f.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "Task t-20261019-bd718f agreement, quoted in /home/elias/dev/justsearch/tmp/verify/agreed/wp1.5.md (WP1.5 source and W3/C8/A3/A7 amendments)",
          "delegationScope": "Design and implement the JustSearch UI/UX Search v3 cutover, preserving supported hand-offs, human Send and document reading.",
          "choice": "Agent-adopted W3/C8/A7: supported compatibility links retain exact text and chosen files, select Ask/model-only/Structured and wait for Send; invalid or unavailable scope is visibly refused without broadening. Preserve retired aliases and canonical Search links. Agent-adopted A3/C9: public plugin inspector opens one reader on the right file with the supplied passage. The corrected builder brief specifies finite integer bounds with 0 <= start <= end, otherwise no highlight and no error; retain the existing 0-based inclusive line convention.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/uiux-compat-ingress-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range undefined to undefined without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range 6 to undefined without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range undefined to 8 without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range -1 to 8 without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range 8 to 6 without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range 1.5 to 8 without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range 6 to 8.5 without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range NaN to 8 without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range 6 to NaN without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range Infinity to 8 without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range 6 to Infinity without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range 6 to 8 without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range 6 to 8 without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector passage validation ignores the whole invalid range null to 8 without error or a stale highlight",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector at width 900 paints valid range -1 to 1 only",
        "modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts#C9 A3 public inspector at width 1568 paints valid range -1 to 1 only",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C2 A7 preservation address core.unified-chat-surface keeps its exact chosen scope",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C2 A7 preservation address core.search-surface keeps its exact chosen scope",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C2 A7 preservation address core.agent-surface keeps its exact chosen scope",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C2 A7 preservation address core.search-v3-surface keeps its exact chosen scope",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C8 A2 an invalid file scope through core.search-v3-surface keeps text and refuses Send",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C8 A7 file link through core.search-v3-surface with missing file refuses without widening scope",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts#C8 A7 file link through core.search-v3-surface with unreadable file refuses without widening scope"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts"
      ],
      "class": "New",
      "behaviour": "New S67 cold document intake regression checks through the public address parser, catalog resolver and navigation handler. Ask, canonical Search, FreeChat and Extract retain exact Unicode/newline input, mode and file scope; earlier preparation remains reachable via Review this draft and nothing dispatches. Capacity and UTF-8 text-limit refusal retain incoming address inputs and name the reason.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "Task t-20261019-bd718f agreement, quoted in /home/elias/dev/justsearch/tmp/verify/agreed/wp1.5.md (WP1.5 source and W3/C8/A3/A7 amendments)",
          "delegationScope": "Design and implement the JustSearch UI/UX Search v3 cutover, preserving supported hand-offs, human Send and document reading.",
          "choice": "Agent-adopted W3/C8 extension S67 in the bounded builder brief: a valid fresh full-document hand-off wins the composer with exact text, mode and files without sending; the earlier unsent draft remains reachable for review. Genuine capacity or validation refusal must retain incoming text in the composer or address and identify the actual reason. Preserve existing recovery formats, all limits, ownership distinctions and the requirement for human review.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-compat-cold-arrival-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 cold core.ask-surface arrival replaces one retained preparation without losing either draft",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 cold core.search-v3-surface arrival replaces one retained preparation without losing either draft",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 cold core.free-chat-surface arrival replaces one retained preparation without losing either draft",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 cold core.extract-surface arrival replaces one retained preparation without losing either draft",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 core.ask-surface genuine capacity refusal retains the incoming address and both existing drafts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 core.search-v3-surface genuine capacity refusal retains the incoming address and both existing drafts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 text limit refusal retains the incoming address and the earlier draft",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 file limit refusal retains incoming text in the address with the real reason: file count",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 file limit refusal retains incoming text in the address with the real reason: path bytes"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserve text-only recovery loading and review, its reuse during incoming intake, and distinct ownership even when raw and typed text bytes match. No recovery limits or format versions change.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "Task t-20261019-bd718f agreement, quoted in /home/elias/dev/justsearch/tmp/verify/agreed/wp1.5.md (WP1.5 source and W3/C8/A3/A7 amendments)",
          "delegationScope": "Design and implement the JustSearch UI/UX Search v3 cutover, preserving supported hand-offs, human Send and document reading.",
          "choice": "Agent-adopted W3/C8 extension S67 in the bounded builder brief: a valid fresh full-document hand-off wins the composer with exact text, mode and files without sending; the earlier unsent draft remains reachable for review. Genuine capacity or validation refusal must retain incoming text in the composer or address and identify the actual reason. Preserve existing recovery formats, all limits, ownership distinctions and the requirement for human review.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/uiux-compat-cold-arrival-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 old text-only recovery remains reviewable after an incoming task",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 preservation same raw bytes at a different place do not merge draft ownership",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts#S67 preservation old text-only recovery loads without an incoming task and requires review"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.tabIdentity.test.ts"
      ],
      "class": "New",
      "behaviour": "A6 new-tab and per-tab pointer restore regression checks through the real Shell, router and Search component, including a sidebar list arriving before startup settles.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "Task t-20261019-bd718f agreement, quoted in /home/elias/dev/justsearch/tmp/verify/agreed/wp1.5.md (A6 amendment)",
          "delegationScope": "Design and implement the JustSearch UI/UX Search v3 cutover, preserving per-tab identity and explicit navigation.",
          "choice": "Agent-adopted A6: a bare launch restores only this tab's actual retained conversation pointer; shared journal identity and global recency confer no identity. Explicit addresses, history traversal and Recents retain their existing navigation semantics. Deleted and locked identities never become another conversation.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-compat-tab-identity-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.tabIdentity.test.ts#A6 a new tab ignores conversation identity in the shared core.search-v3-surface journal",
        "modules/ui-web/src/shell-v0/chrome/Shell.tabIdentity.test.ts#A6 a new tab ignores conversation identity in the shared core.unified-chat-surface journal",
        "modules/ui-web/src/shell-v0/chrome/Shell.tabIdentity.test.ts#A6 the surviving tab pointer outranks another tabs shared journal conversation",
        "modules/ui-web/src/shell-v0/chrome/Shell.tabIdentity.test.ts#A6 the tab pointer restores even when its sidebar row arrives before startup settles"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.tabIdentity.test.ts"
      ],
      "class": "New",
      "behaviour": "A6 preservation: bare old journal restart, same-tab pointer, explicit conversation/query and unavailable record identity. These DOM fixtures are controlled state/HTTP, not genuine previous-version browser upgrade evidence.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "Task t-20261019-bd718f agreement, quoted in /home/elias/dev/justsearch/tmp/verify/agreed/wp1.5.md (A6 amendment)",
          "delegationScope": "Design and implement the JustSearch UI/UX Search v3 cutover, preserving per-tab identity and explicit navigation.",
          "choice": "Agent-adopted A6: a bare launch restores only this tab's actual retained conversation pointer; shared journal identity and global recency confer no identity. Explicit addresses, history traversal and Recents retain their existing navigation semantics. Deleted and locked identities never become another conversation.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/uiux-compat-tab-identity-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.tabIdentity.test.ts#A6 preservation restart from an old bare journal opens fresh",
        "modules/ui-web/src/shell-v0/chrome/Shell.tabIdentity.test.ts#A6 preservation an explicit conversation address outranks the pointer and journal",
        "modules/ui-web/src/shell-v0/chrome/Shell.tabIdentity.test.ts#A6 preservation an explicit query outranks the pointer and journal",
        "modules/ui-web/src/shell-v0/chrome/Shell.tabIdentity.test.ts#A6 preservation same-tab reload with a pointer and a bare journal restores that tab",
        "modules/ui-web/src/shell-v0/chrome/Shell.tabIdentity.test.ts#A6 preservation a 404 record pointer never borrows the shared journal identity",
        "modules/ui-web/src/shell-v0/chrome/Shell.tabIdentity.test.ts#A6 preservation a 423 record pointer never borrows the shared journal identity"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.streamReturn.test.ts"
      ],
      "class": "New",
      "behaviour": "New HO8a regression checks: two hand-offs during a controlled chosen-file stream keep that conversation one navigation away by sidebar and the conversation-address route used by Back. Both retained texts and file preparations survive the return, pagehide save and stream completion; no extra dispatch, abort or Stop.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "Task t-20261019-bd718f agreement, quoted in supplied WP1.3b / WP1.5 agreements; bounded finish-HO8a builder brief, orchestrator decision",
          "delegationScope": "Design and implement the JustSearch UI/UX Search v3 cutover, preserving running work, draft recovery and explicit human Send.",
          "choice": "Agent-adopted HO8a: existing running work remains reachable by sidebar or conversation navigation after two hand-offs. An empty destination composer needs no extra recovery slot. Keep DS1 exactly: two retained input-bearing proposals, third new proposal refused, no silent drops, unchanged limit, validator and persisted format. Retain every edited draft; genuine third unretained inputs and failed storage acknowledgements still refuse departure.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-compat-stream-return-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.streamReturn.test.ts#HO8a two hand-offs keep the running Ask one sidebar navigation away",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.streamReturn.test.ts#HO8a two hand-offs keep the running Ask one Back navigation away"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.streamReturn.test.ts"
      ],
      "class": "New",
      "behaviour": "New HO8a preservation checks: an already-retained edited follow-up restores without another slot; newer raw text beside two acknowledged proposals refuses a departure that needs three input-bearing snapshots; storage write failure refuses even an empty-destination return. Existing DS1 and S67 test files remain byte-identical.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "Task t-20261019-bd718f agreement, quoted in supplied WP1.3b / WP1.5 agreements; bounded finish-HO8a builder brief, orchestrator decision",
          "delegationScope": "Design and implement the JustSearch UI/UX Search v3 cutover, preserving running work, draft recovery and explicit human Send.",
          "choice": "Agent-adopted HO8a: existing running work remains reachable by sidebar or conversation navigation after two hand-offs. An empty destination composer needs no extra recovery slot. Keep DS1 exactly: two retained input-bearing proposals, third new proposal refused, no silent drops, unchanged limit, validator and persisted format. Retain every edited draft; genuine third unretained inputs and failed storage acknowledgements still refuse departure.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/uiux-compat-stream-return-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.streamReturn.test.ts#HO8a preservation one hand-off restores a streaming follow-up and chosen files",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.streamReturn.test.ts#HO8a preservation a third unretained edited draft still blocks departure without losing inputs",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.streamReturn.test.ts#HO8a preservation an empty destination still requires an acknowledged recovery write"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.refusalNavigation.test.ts"
      ],
      "class": "New",
      "behaviour": "Agent-adopted F1/F3 and rules 2\u20133: conversation URL/traversal, real Search URL, visible exact incoming text/file count, immediate retry after discard, and retirement on navigation. Isolated unaccepted-input reload uses the brief-authorized rule-4 fallback; not claimed repaired.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "Task t-20261019-bd718f agreement, quoted in /home/elias/dev/justsearch/tmp/verify/agreed/wp1.5.md (WP1.5 source and W3/C8/A3/A7 amendments); orchestrator builder-6 brief, 2026-10-27",
          "delegationScope": "Design and implement the JustSearch UI/UX Search v3 cutover, preserving supported hand-offs, human Send and document reading.",
          "choice": "Agent-adopted refusal navigation: never freeze later addresses; show exact refused text and file count; acknowledged discard retries the parked task immediately without sending; acceptance or departure retires it. Use the explicit rule-4 fallback: preserve reload restoration without new stored state because accepted pre-boot input entries and fresh refused native hash entries both lack ownership metadata. Preserve S67, genuine limits, DS1, A6 and HO8a.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-refusal-navigation-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.refusalNavigation.test.ts#S79 refusal allows conversation addresses and subsequent traversal",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.refusalNavigation.test.ts#S80 S81 Search after refusal projects its current address",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.refusalNavigation.test.ts#S82 refusal notice shows exact incoming text and file count",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.refusalNavigation.test.ts#S77 discard prepares parked hand-off immediately with mode and files",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.refusalNavigation.test.ts#S83 navigation retires refusal so later discard cannot resurrect it"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.refusalNavigation.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserve accepted tasks and human edits through Library/Back/Forward/reload; canonical accepted reload, original pre-boot input entries and owned history traversal never re-deliver. Preserve S67 cold intake and the real 128-file limit refusal.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "Task t-20261019-bd718f agreement, quoted in /home/elias/dev/justsearch/tmp/verify/agreed/wp1.5.md (WP1.5 source and W3/C8/A3/A7 amendments); orchestrator builder-6 brief, 2026-10-27",
          "delegationScope": "Design and implement the JustSearch UI/UX Search v3 cutover, preserving supported hand-offs, human Send and document reading.",
          "choice": "Agent-adopted refusal navigation: never freeze later addresses; show exact refused text and file count; acknowledged discard retries the parked task immediately without sending; acceptance or departure retires it. Use the explicit rule-4 fallback: preserve reload restoration without new stored state because accepted pre-boot input entries and fresh refused native hash entries both lack ownership metadata. Preserve S67, genuine limits, DS1, A6 and HO8a.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/uiux-refusal-navigation-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.refusalNavigation.test.ts#S78 accepted address and edited text survive Library traversal and reload without delivery",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.refusalNavigation.test.ts#S67 preservation cold valid arrival retains earlier draft without a false refusal",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.refusalNavigation.test.ts#S76 preservation genuine refusal names the file limit and keeps existing draft",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.refusalNavigation.test.ts#S78 preservation canonical accepted reload keeps human edits without delivering old inputs",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.refusalNavigation.test.ts#S78 preservation original pre-boot input entry keeps human edits without delivering old inputs",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.refusalNavigation.test.ts#S78 preservation owned input history traversal keeps human edits without delivering old inputs"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.refusalRetirement.test.ts"
      ],
      "class": "New",
      "behaviour": "Same-surface rail, fresh address, and modal navigation clear the refusal notice and its recovery alert, preserve exact current/retained drafts, and prevent later discard from preparing the retired task.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "Task t-20261019-bd718f agreement, quoted in /home/elias/dev/justsearch/tmp/verify/agreed/wp1.5.md (WP1.5 source and W3/C8/A3/A7 amendments); orchestrator builder-6 brief, 2026-10-27",
          "delegationScope": "Design and implement the JustSearch UI/UX Search v3 cutover, preserving supported hand-offs, human Send and document reading.",
          "choice": "Agent-adopted refusal navigation rule: accepted navigation away retires the parked hand-off and its visible refusal. The stale recovery alert retires with that refusal; a later proposal must still report a genuine capacity limit. Unknown rejected navigation keeps the refusal available for the existing explicit discard action.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-refusal-retirement-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.refusalRetirement.test.ts#S84 rail to bare Search retires refusal notice and its alert without changing drafts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.refusalRetirement.test.ts#S84 fresh address to bare Search retires refusal notice and its alert without changing drafts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.refusalRetirement.test.ts#S84 accepted modal navigation retires refusal while keeping the underlying preparation"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.refusalRetirement.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserve traversal retirement, real two-draft refusal on another arrival, and rejected-navigation refusal plus immediate intake after explicit discard. Nothing sends automatically.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "Task t-20261019-bd718f agreement, quoted in /home/elias/dev/justsearch/tmp/verify/agreed/wp1.5.md (WP1.5 source and W3/C8/A3/A7 amendments); orchestrator builder-6 brief, 2026-10-27",
          "delegationScope": "Design and implement the JustSearch UI/UX Search v3 cutover, preserving supported hand-offs, human Send and document reading.",
          "choice": "Agent-adopted refusal navigation rule: accepted navigation away retires the parked hand-off and its visible refusal. The stale recovery alert retires with that refusal; a later proposal must still report a genuine capacity limit. Unknown rejected navigation keeps the refusal available for the existing explicit discard action.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/uiux-refusal-retirement-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.refusalRetirement.test.ts#S84 traversal to bare Search retires refusal notice and its alert without changing drafts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.refusalRetirement.test.ts#S84 preservation another proposal still reports the genuine capacity limit after retirement",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.refusalRetirement.test.ts#S84 preservation rejected unknown navigation keeps refusal available for explicit discard"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.scopeRetirement.test.ts"
      ],
      "class": "New",
      "behaviour": "Agent-adopted D-T24: navigation still retires one-shot hand-off data. When text remains after losing files or a forced mode, explain this in the composer and block sending until a file/mode choice or a changed question. Explicit new choices and unrelated text use ordinary composer behavior. A same-task layout reparent keeps the hand-off. HO9 itself is owner-agreed; this guard is an agent choice under delegation. Carrying the same guarded text into an unvisited conversation preserves the guard; only a person's file/mode choice or replacement text releases it.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "Task t-20261019-bd718f agreement, quoted in /home/elias/dev/justsearch/tmp/verify/agreed/wp1.5.md",
          "delegationScope": "Design and implement the Search v3 cutover; preserve the owner-agreed HO9 retirement contract in /home/elias/dev/justsearch/tmp/verify/agreed/wp1.3b.md:33.",
          "choice": "Agent-adopted D-T24: navigation still retires one-shot hand-off data. When text remains after losing files or a forced mode, explain this in the composer and block sending until a file/mode choice or a changed question. Explicit new choices and unrelated text use ordinary composer behavior. A same-task layout reparent keeps the hand-off. HO9 itself is owner-agreed; this guard is an agent choice under delegation.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-scope-retirement-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.scopeRetirement.test.ts#HO9 return shows why unchanged incoming text needs a new file or mode choice",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.scopeRetirement.test.ts#HO9 unchanged incoming text cannot send all files after return through Enter",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.scopeRetirement.test.ts#HO9 unchanged incoming text cannot send all files after return through button",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.scopeRetirement.test.ts#HO9 unchanged incoming text cannot send all files after return through submit event",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.scopeRetirement.test.ts#HO9 choosing files after return sends exactly that new scope as Ask",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.scopeRetirement.test.ts#HO9 carrying unchanged returned text into a conversation keeps the guard"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.scopeRetirement.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserve ordinary Ask after replacing returned text or explicitly choosing a mode; preserve the exact prepared summary selection on synchronous layout reparent. Existing tests, including every HO9 check, remain byte-identical.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "Task t-20261019-bd718f agreement, quoted in /home/elias/dev/justsearch/tmp/verify/agreed/wp1.5.md",
          "delegationScope": "Design and implement the Search v3 cutover; preserve the owner-agreed HO9 retirement contract in /home/elias/dev/justsearch/tmp/verify/agreed/wp1.3b.md:33.",
          "choice": "Agent-adopted D-T24: navigation still retires one-shot hand-off data. When text remains after losing files or a forced mode, explain this in the composer and block sending until a file/mode choice or a changed question. Explicit new choices and unrelated text use ordinary composer behavior. A same-task layout reparent keeps the hand-off. HO9 itself is owner-agreed; this guard is an agent choice under delegation.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-scope-retirement-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.scopeRetirement.test.ts#HO9 replacing returned text sends an ordinary all-files Ask",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.scopeRetirement.test.ts#HO9 choosing a mode after return permits ordinary sending",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.scopeRetirement.test.ts#HO9 a layout reparent keeps the exact prepared hand-off"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sameModeLoad.test.ts"
      ],
      "class": "New",
      "behaviour": "Shown-mode Load anyway regression: held 202 response still leads to exactly one follow-up with the original question; pointer and keyboard use the composer Mode menu.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "Task t-20261019-bd718f agreement, quoted in /home/elias/dev/justsearch/tmp/verify/agreed/wp1.5.md",
          "delegationScope": "Design and implement the Search v3 cutover; preserve the owner-agreed HO9 retirement contract in /home/elias/dev/justsearch/tmp/verify/agreed/wp1.3b.md:33.",
          "choice": "Agent-adopted preservation row 4: selecting the shown mode leaves an ordinary pending Load anyway continuation and ordinary text/files/schema unchanged. R3 still permits choosing the shown mode to acknowledge retired hand-off inputs and end the block, without sending by itself. Exact orchestrator repair in current builder brief: same tier and retiredHandoffText === null returns before revokeHumanTask.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-same-mode-load-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sameModeLoad.test.ts#pending Load anyway survives shown Ask choice by pointer",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sameModeLoad.test.ts#pending Load anyway survives shown Ask choice by keyboard"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sameModeLoad.test.ts"
      ],
      "class": "New",
      "behaviour": "Shown-mode Ordinary text, chosen-file scope and Structured schema stay intact; shown Ask still ends retired hand-off block. No automatic send.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "Task t-20261019-bd718f agreement, quoted in /home/elias/dev/justsearch/tmp/verify/agreed/wp1.5.md",
          "delegationScope": "Design and implement the Search v3 cutover; preserve the owner-agreed HO9 retirement contract in /home/elias/dev/justsearch/tmp/verify/agreed/wp1.3b.md:33.",
          "choice": "Agent-adopted preservation row 4: selecting the shown mode leaves an ordinary pending Load anyway continuation and ordinary text/files/schema unchanged. R3 still permits choosing the shown mode to acknowledge retired hand-off inputs and end the block, without sending by itself. Exact orchestrator repair in current builder brief: same tier and retiredHandoffText === null returns before revokeHumanTask.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/uiux-same-mode-load-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sameModeLoad.test.ts#shown mode preserves ordinary text by pointer",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sameModeLoad.test.ts#shown mode preserves ordinary text by keyboard",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sameModeLoad.test.ts#shown mode preserves ordinary files by pointer",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sameModeLoad.test.ts#shown mode preserves ordinary files by keyboard",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sameModeLoad.test.ts#shown mode preserves ordinary schema by pointer",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sameModeLoad.test.ts#shown mode preserves ordinary schema by keyboard",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sameModeLoad.test.ts#R3 shown Ask choice still ends retired hand-off block by pointer",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sameModeLoad.test.ts#R3 shown Ask choice still ends retired hand-off block by keyboard"
      ]
    }
  ]
}
```

## Builder validation (not independent acceptance)

File-by-file result:

- `modules/ui-web/src/shell-v0/router/navigationHandler.ts`: retained the earlier compatibility-target lowering into canonical Search ingress before schema hydration, preserving task text, shape and file validation.
- `modules/ui-web/src/shell-v0/plugin-api/capabilities/ui.ts`: retained highlight propagation and corrected validation to integer bounds with 0 <= start <= end. Number.isInteger rejects NaN and infinities.
- `modules/ui-web/src/shell-v0/plugin-api/plugin-types.ts`: documented both optional highlight fields with the reader's 0-based inclusive convention and corrected valid bounds.
- `modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts`: moved all new compatibility-address checks into their own file, with isolated setup/cleanup.
- `modules/ui-web/src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts`: moved all new inspector checks into their own file; tests valid first-line spans and negative-start refusal at both rendered widths, in addition to trust tiers and malformed ranges.
- `gates/test-intent/evidence/uiux-compat-ingress-repair.json`: regenerated unchanged-producer evidence for 14 repaired check ids, including all assertion failures on f17b988e2f.
- `gates/test-intent/evidence/uiux-compat-ingress-preservation.json`: regenerated unchanged-producer evidence for 23 preservation check ids.
- This changeset: updated paths, the corrected contract, check ids and validation results. No acceptance record added.

`HostApiSelection.test.ts` and `SearchV3View.handoffs.test.ts` are restored byte-for-byte to f17b988e2f. No existing test files remain changed relative to that baseline.

Before/after scenario results (same final new test files overlaid on the baseline):

| Scenario / test | f17b988e2f | Candidate |
| --- | --- | --- |
| C8 W3 A7 compatibility address Ask, FreeChat and Extract: text/files/mode, human Send | 3 assertion failures | 3 pass |
| C8 A7 warm compatibility arrival replaces preparation and waits | 1 assertion failure | 1 pass |
| C8 A2 invalid Ask file scope retains text and refuses Send | 1 assertion failure | 1 pass |
| C8 A7 missing/unreadable Ask file: request scope and visible refusal | 2 assertion failures | 2 pass |
| C9 A3 valid inclusive inspector passage, including line 0, across three trust tiers | 3 assertion failures | 3 pass |
| C9 A3 inspector paints lines 6–8 and 0–1 at widths 900 and 1568 | 4 assertion failures | 4 pass |
| C2 A7 three retired aliases and canonical Search retain text/files/request scope | 4 pass | 4 pass |
| C8 A2/A7 canonical Search invalid/missing/unreadable file refusal | 3 pass | 3 pass |
| C9 A3 absent/partial/reversed/negative/fractional/nonfinite/nonnumeric ranges ignored | 14 pass | 14 pass |
| C9 A3 negative-start inspector ranges paint no lines at both widths | 2 pass | 2 pass |

Commands and observed results:

- `.agents/preflight`: absent; not run.
- Plain `npm ci` in source UI and root: exit 0, 283 and 386 packages installed. UI reports one moderate audit finding; root reports zero vulnerabilities. Dependencies and lockfiles unchanged. Both shared before-clones used their own plain UI npm ci, 283 packages each; no shared dependency trees.
- In `modules/ui-web`, `npm run typecheck`: exit 0.
- In `modules/ui-web`, `npm run lint`: exit 0, zero warnings.
- In `modules/ui-web`, `npm run test:unit:run -- --configLoader runner --maxWorkers=4`: exit 0; 643 files, 8,214 tests passed, no skipped tests; 96.33 seconds.
- Focused `npm run test:unit:run -- src/shell-v0/plugin-api/HostApiInspectorHighlight.test.ts src/shell-v0/views/search-v3/SearchV3View.compatLinks.test.ts --configLoader runner --maxWorkers=4 --reporter=json --outputFile=<private result>`: baseline exit 1, 23 passed / 14 assertion failures; candidate exit 0, 37 passed. The unchanged official producer also exits 0 for repair (with --before f17b988e2f) and preservation ids. All 14 repair ids classify as assertion failures before and pass after; all 23 preservation cases pass in both direct focused runs.
- `node scripts/ci/run-ui-web-gates.mjs`: exit 0, 28/28 passed.
- `node scripts/governance/gates/test-intent/cli.mjs check --base origin/main`: exit 1, exactly ten expected problems: the package's missing independent acceptance plus nine main-moved uncovered items listed below. No evidence-invalid, entry-invalid or stale acceptance-digest problems. The untouched baseline shared clone reports the same nine uncovered items with the same base b742806877c52da9d7197c0654954011ef5a4121. Its remote-tracking main was aligned to the source's origin/main because a local clone otherwise tracks the source's local main branch.
- `git diff --check`: exit 0. Existing test files compared with `git diff --exit-code f17b988e2f -- <both original test files>`: exit 0.

Main-moved uncovered items (this package touches none of these):

- `modules/telemetry/src/test/java/io/justsearch/telemetry/TracingBootstrapFlushBarrierTest.java`
- `modules/telemetry/src/test/java/io/justsearch/telemetry/TracingBootstrapFlushRaceTest.java`
- `modules/telemetry/src/test/java/io/justsearch/telemetry/TracingBootstrapFlushTest.java`
- `modules/ui/src/integrationTest/java/io/justsearch/app/engine/LinuxWatchedDriveReturnIntegrationTest.java`
- `modules/ui/src/integrationTest/java/io/justsearch/app/engine/OutstandingHttpFailureCleanupIntegrationTest.java`
- `modules/ui/src/integrationTest/java/io/justsearch/ui/api/LocalApiConfigIsolationIntegrationTest.java`
- `modules/ui/src/integrationTest/java/io/justsearch/ui/api/LocalApiIntegrationTestBase.java`
- `modules/ui/src/integrationTest/java/io/justsearch/ui/api/PlannedContextSettingsIntegrationTest.java`
- `modules/ui/src/integrationTest/java/io/justsearch/ui/api/ProdModeSettingsPersistenceIntegrationTest.java`

All processes ran with CUDA_VISIBLE_DEVICES=-1, inherited JUSTSEARCH_* and JVM option variables removed, and GRADLE_OPTS retained. No Engine, browser, GPU, owner app or existing service was used. Checks used private temporary clones, their own dependencies/build folders, DOM/HTTP/SSE fixtures, the suite's own loopback fixtures and npm's ordinary cache. No commits, merge, publishing, GitHub issue actions or independent acceptance. Real browser/native Engine boundary confirmation remains with the independent verifier.


## S67 cold-arrival extension (agent-adopted, delegated-choice)

The supplied third builder brief extends W3/C8 with S67 and canonical fresh-load arrivals.
The new checks live only in `SearchV3View.coldArrival.test.ts`; all existing test files,
including the earlier two regression files, remain byte-identical to b831201474.

On b831201474, raw restoration marks the composer as needing review with no preparation
(`SearchV3View.ts:1644`). During incoming intake, `openRecovery` also retains the matching
saved preparation (`:1284`). `persistDraft` combines the incoming snapshot, raw snapshot
and saved preparation (`:1127–1133`). `isSv3Recovery` rejects the three drafts at
`sv3-recovery.ts:55` (the limit is two), despite only one earlier unsent text.
`navigationHandler.ts:211` clears the hand-off state before acceptance and
`URLProjector.ts:287` replaces the address; `takeSearchV3Handoff` also consumed it
before persistence at `SearchV3View.ts:3983`. The canonical URL reproduces the
same component failure. Real-browser pairing and final command results follow below.

The repair retains the matching saved proposal as the outgoing recovery copy only
when cold-restored raw text and place match. This is retention, never authority to
apply old settings or send. Different text or place still needs a separate slot.
Text-only legacy copies are normalized to an explicit null preparation when written
in the existing version-2 payload. No validator, slot limit, byte limit or format
version is changed. The existing transient hand-off is consumed after safe intake;
a refused hand-off stays available to preserve the address, marked refused so remount
cannot replay it. Accepted intake explicitly refreshes URL projection.


S67 file-by-file changes:

- `modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ts`: retain one matching cold raw/saved proposal rather than two copies when intake reserves its outgoing work; normalize legacy preparation absence to null on existing payload writes. Leave different text/place separate. Consume the transient task only after a successful acknowledgement and refresh the accepted address. Mark refused tasks against remount replay. Name draft-count, UTF-8 text, chosen-file count/path and Structured-schema validation limits in the refusal instead of reporting one generic limit.
- `modules/ui-web/src/shell-v0/utils/compose.ts`: allow the existing transient hand-off register to be inspected before acceptance and mark refusal there. No second register or durable format.
- `modules/ui-web/src/shell-v0/router/navigationHandler.ts`: keep the original input state in a pushed address while fresh intake is pending/refused; state distribution still gets the existing empty hand-off snapshot.
- `modules/ui-web/src/shell-v0/router/URLProjector.ts`: don't overwrite a pending/refused incoming address on initial activation or subsequent store refresh. Accepted intake explicitly replaces it with the current durable navigation projection.
- `modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts`: 12 new checks; four cold arrivals, two genuine-capacity URL refusals, text/file-count/file-path byte refusals, and three preservation checks (both old text-only recovery paths and distinct ownership). The canonical arrival uses only query/docIds, with no mode override. No new test changes any existing test file.
- `gates/test-intent/evidence/uiux-compat-cold-arrival-repair.json`: unchanged official evidence producer; all nine checks classify as assertion failures on b831201474 and pass on the working tree.
- `gates/test-intent/evidence/uiux-compat-cold-arrival-preservation.json`: official producer, all three checks pass; the direct paired baseline run also passes all three.
- `gates/test-intent/evidence/uiux-compat-cold-arrival-browser.json`: durable paired native Engine/Chromium observations for four before/after cases, harness source, exact text/URLs, recovery payloads, zero-work counts, source hashes and cleanup.
- This changeset adds the S67 contracts, ids, sources and evidence. The original two evidence files and original 37 regression checks remain unchanged.

S67 component evidence, using the exact final new test file on both revisions:

| Scenario | b831201474 | Working tree |
| --- | --- | --- |
| Cold Ask, canonical Search, FreeChat, Extract: exact text/mode/files; previous draft reachable by review; no automatic dispatch | 4 assertion failures: old text retained instead of incoming | 4 pass |
| Genuine capacity refusal via Ask/canonical: incoming URL survives; prior raw and typed work kept; reason names two-draft capacity | 2 assertion failures: incoming query absent from address | 2 pass |
| UTF-8 text, file-count and path-byte refusal: incoming URL survives; actual limit named | 3 assertion failures: incoming query absent from address | 3 pass |
| Old text-only recovery with and without incoming task; explicit review required | 2 pass | 2 pass |
| Same raw text at a different place remains a distinct owner and refuses a third snapshot | 1 pass | 1 pass |

The diagnostic baseline probe captured the actual rejected JSON: incoming text/chosen scope,
old raw text with null preparation, and old identical text with decoy scope (all place=null).
It is the drafts.length > 2 branch at `sv3-recovery.ts:55`; no text/file/schema bound is exceeded.
Only the private baseline clone's temporary test probe was instrumented; production code and
all final checked-in test source are uninstrumented. Direct baseline results: 3 pass / 9 fail;
working-tree focused results: 12 pass. The unchanged hand-off tests also passed (62 cases).

Final S67 command results observed before browser execution:

- `.agents/preflight`: absent.
- Plain own `modules/ui-web` `npm ci`: source and baseline shared clone each exit 0, 283 packages each. The producer's two temporary baseline shared clones also ran their own plain npm ci, 283 packages each. No shared or linked node_modules.
- `npm run typecheck`: exit 0.
- `npm run lint`: exit 0, no warnings.
- `npm run test:unit:run -- --configLoader runner --maxWorkers=4`: exit 0, 644 files / 8,226 tests passed, no skips.
- `npm run test:unit:run -- src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts --configLoader runner --maxWorkers=4 --reporter=json --outputFile=<private result>`: baseline exit 1 with 9 intended assertion failures and 3 passes; working tree exit 0, all 12 pass. The last full-suite run also includes the final 12 checks.
- `node scripts/ci/run-ui-web-gates.mjs`: exit 0, 28/28 passed.
- Official `run-evidence.mjs --out gates/test-intent/evidence/uiux-compat-cold-arrival-repair.json --before b831201474 --check <each of nine ids>`: exit 0. Preservation producer `--out gates/test-intent/evidence/uiux-compat-cold-arrival-preservation.json --check <each of three ids>`: exit 0. Shared-clone adapter/private object directory as documented earlier; the producer and runner code are unchanged.
- `node scripts/governance/gates/test-intent/cli.mjs check --base f17b988e2f`: exit 1, exactly one problem, this package's missing independent acceptance. No evidence-invalid or stale digest problems. Non-failing finding: reconsider rate 2/19 audited PRs (10.5%) exceeds one in ten over the last 28 days; independent acceptance remains the orchestrator's responsibility.
- `git diff --check`: exit 0. All existing test files and `sv3-recovery.ts` byte-identical to b831201474; limits and accepted recovery formats unchanged.

Real native Engine/headless Chromium pairing completed. Durable compact observations,
exact URLs/texts, recovery snapshots, zero-work counts, harness source, build results,
private-service cleanup and candidate production/test SHA-256 hashes are in
[`uiux-compat-cold-arrival-browser.json`](../evidence/uiux-compat-cold-arrival-browser.json).
Only draft recovery payloads are archived; API/session authentication tokens are excluded.
Screenshots, full console/traffic captures and logs were also kept in the session's
private temporary folder, which the launcher removes at session end.

| Real browser scenario | b831201474 | Working tree |
| --- | --- | --- |
| S67 compatibility Ask full document load after one unsent draft | FAIL: old text, chosen new file, false limit, stripped URL | PASS: exact incoming text/files, old preparation reviewable, incoming still retained after old review, no false refusal |
| S67 canonical Search query/docIds full document load after one unsent draft | Same failure | Same pass |
| Genuine capacity refusal after two distinct preparations, incoming Ask | FAIL: incoming query stripped; generic reason | PASS: current B kept, incoming C stays in address, actual two-draft capacity named |
| Genuine capacity refusal after two distinct preparations, incoming canonical Search | Same failure | Same pass |

All eight browser cases made zero automatic chat/model work requests and had zero
page errors. Each case used a newly launched short `/tmp/jsv-*` Chromium profile/TMPDIR;
all eight profiles were removed. Chat was unconfigured in fresh private Engine HOME/data,
with AI explicitly disabled; no chat model was needed or loaded. The private fixture corpus root
was admitted through the actual Engine API. The UI was each corresponding checkout's
source through its own Vite loopback service; the Engine was each tree's installed binary.
No other session's Engine, frontend, app, display, desktop or user data was used.

Commands and observed browser/build results:

- In the baseline shared clone, `env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew :modules:ui:installDist`: exit 0, 88 tasks (57 executed, 31 from cache); 1,158 seconds queued, 17 seconds building.
- In the source checkout, the same exact Gradle command: exit 0, 88 tasks (57 executed, 31 from cache); 2,112 seconds queued, 16 seconds building. Neither node_modules directory was a symlink; no shared Engine JVM lock, init script, --stop or --no-daemon was used.
- `node cold-case.mjs` using the supplied verifier's case/helper with a canonical loop and old-draft review assertions: baseline exit 1, two intended assertion failures; candidate exit 0, two passes.
- `node cold-refusal.mjs`: baseline exit 1, two intended address/refusal-reason assertion failures; candidate exit 0, two passes. It prepares A and B through real navigation, then fully loads incoming C.
- Own `browser-pair.py` campaign: exit 0 after checking both runtime cleanups. It ran one private Engine at a time, directly through `modules/ui/build/install/ui/bin/ui`, with private HOME/data/index/corpus, ephemeral loopback ports, no shared JVM lock, and matching frontend. Both manifests reported worker ready. Both private Engine profiles and both Vite processes were cleaned up by recorded process identity.
- Chromium sandbox probe: launch failed with “No usable sandbox”; it tested no product scenario and its short profile was removed. The permitted --no-sandbox fallback was then used only for the own-loopback browser cases; every result records sandbox=false.

All task work remains uncommitted. No pushes, publishing, GitHub issues or independent
acceptance were performed. Shared resources touched: the required Gradle slot queue/cache,
npm's ordinary cache, the existing Chromium executable and machine CPU. CUDA was disabled
for all checks; inherited JUSTSEARCH_* and JVM option variables were stripped, and
GRADLE_OPTS retained. No custom logs were written into /tmp/js-linux-port.

The only required check not satisfied by this builder is independent test-intent acceptance,
which is reserved for the separate verifier/acceptor. The final gate still reports that one
expected problem and the non-failing 2/19 reconsider-rate finding. No other work is left open.


## A6 / HO8a cutover regression assignment (agent-adopted, delegated-choice)

A6 cause on ec41e49283: `chrome/Shell.ts:1844–1846` takes the last shared localStorage
journal's parsed state as automatic boot state, including another tab's conversation id.
It bypasses the per-tab ownership rule in `controllers/lastViewedConversation.ts:10–12`.
The bounded fix uses that existing pointer at the automatic bare Search boot only.
An absent pointer removes the shared conversation id; a present pointer supplies that
tab's id before navigation hydration, including when its sidebar row arrived first.
Explicit addresses and journal traversal/Recents do not use this automatic-boot branch.
No new store, option or persisted format is added.

New checks live only in `Shell.tabIdentity.test.ts`. Four assertions fail on ec41e49283
and pass after; six preservation checks pass after and are paired separately against
ec41e49283. Official evidence uses the unchanged producer and the existing shared-clone
Git adapter/private object-directory method described above, with plain npm ci per clone.

The earlier HO8a contract question is resolved by the supplied orchestrator decision:
retain A and B exactly, and avoid reserving a slot for the empty destination composer.
The completion evidence and checks are recorded below. The following partial handback
is historical evidence from the previous builder, not the current completion status.


Partial builder handback, 2026-10-27 (work remains uncommitted):

- `chrome/Shell.ts` imports the existing per-tab pointer and substitutes it for the shared journal's conversation id only during automatic bare Search boot. A missing pointer removes shared identity. Four repair assertions fail before/pass after; six preservation assertions pass before/after. New `Shell.tabIdentity.test.ts` contains all ten; no existing test file changed.
- New `SearchV3View.streamReturn.test.ts` has two controlled streaming component cases: two arrivals reproduce the one-click failure; the one-arrival follow-up control passes. No production recovery changes were made pending the DS1/HO8a contract clarification.
- Official A6 evidence files contain four fail-before assertion classifications and ten passing candidate checks. Browser observations, exact harness sources/adaptations, private CPU process arguments, hashes, failure evidence and cleanup are archived in `gates/test-intent/evidence/uiux-compat-cutover-regressions-browser.json`. No independent acceptance or general completion claim.

Observed command results for this assignment:

| Command | Result |
| --- | --- |
| `.agents/preflight` existence check | Absent; no preflight command available |
| `git clone --quiet --shared --no-checkout . "$TMPDIR/before"` and detached ec41e49283 checkout | Exit 0; private baseline clone |
| Same shared clone recipe at ebf79183 into `$TMPDIR/known-good` | Exit 0; created for possible old-UI control, never built/run; no known-good pass claimed |
| Plain `npm ci` in source, baseline and known-good `modules/ui-web` | Three own dependency trees, 283 packages each, no linked/shared node_modules |
| `npm run test:unit:run -- src/shell-v0/chrome/Shell.tabIdentity.test.ts --configLoader runner --maxWorkers=4 --reporter=json --outputFile=<private report>` | Final baseline exit 1: 4 intended assertion failures / 6 pass. Final source exit 0: all 10 pass |
| Same focused command with both new test files before the final extra A6 ordering check | Baseline exit 1: 4 intended assertion failures / 7 pass; the final additional ordering check independently fails before/passes after |
| `npm run typecheck` | Final exit 0; initial new-test typing errors were corrected |
| `npm run lint` | Final exit 0; initial unused diagnostic key was removed |
| `npm run test:unit:run -- --configLoader runner --maxWorkers=4` | Exit 1: 645 files pass / 1 fails, 8,237 tests pass / 1 fails, no skips. Sole failure is the new unresolved HO8a reproduction. Existing S67 checks pass |
| `node scripts/ci/run-ui-web-gates.mjs` | Exit 0, 28/28 pass |
| Official `run-evidence.mjs --out gates/test-intent/evidence/uiux-compat-tab-identity-repair.json --before ec41e49283 --check <four ids>` | Exit 0, four candidate passes and four baseline assertion failures |
| Official `run-evidence.mjs --out gates/test-intent/evidence/uiux-compat-tab-identity-preservation.json --check <six ids>` | Exit 0, six passes; separately paired baseline also six passes |
| `node scripts/governance/gates/test-intent/cli.mjs check --base f17b988e2f` | Exit 1: exactly missing independent acceptance. Non-failing finding: reconsider rate 2/19 (10.5%) |
| In source and baseline, `env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew :modules:ui:installDist` | Both queued; canceled with Ctrl-C on handback, exit 130. No exit 75 observed; neither is a build pass |
| Own direct `modules/ui/build/install/ui/bin/ui`, plus own loopback Vite | Before and after private campaigns used existing unchanged installed ec41 Engine and corresponding source UI; both campaigns exited and removed their private data/HOME |
| `node sandbox-probe.mjs` | Native Chromium sandbox failed to launch; zero product cases tested. Own loopback checks used permitted headless `--no-sandbox`; short profiles removed |
| Adapted verifier `node new-tab-identity.mjs` on ec41 | Exit 1: genuine own UI record, no-opener new tab with empty sessionStorage adopted its conversation, one transcript turn and new pointer |
| Adapted verifier `node stream-origin-access.mjs before` on ec41 | Exit 1: B remains after one original-sidebar click, false accessibility due to two-draft refusal; one real chosen-file dispatch, no extra dispatch or Stop |
| Adapted new-tab browser check on first identity patch | Exit 0: no borrowed transcript or pointer, existing own record authored through UI |
| Browser identity controls | Initial patch exposed missing pointer restoration when sidebar data arrived early; fixed using that existing pointer at startup. Revised patch captured passing same-tab reload and explicit-query snapshots; a Library `display:contents` visibility assumption was corrected in harness. Later/final matched controls could not finish: native manifest/catalog/thread/history requests returned 429, and final new-tab rerun could not boot UI. These are not passes |
| S67 browser controls | Did not complete successfully after native API 429; no browser preservation pass claimed |
| `git diff --check` | Exit 0 |

Still unverified in the final real browser: matched final new-tab rerun, Back/Forward,
Recents, deleted/locked controls, a genuine old-version bare-journal full restart,
one-hand-off live return and S67. The final DOM suite covers identity and existing
S67 preservation, but does not substitute for these missing browser checks.

All processes used CUDA_VISIBLE_DEVICES=-1; inherited JUSTSEARCH_* and JVM option
variables were removed, GRADLE_OPTS retained. Actual permitted model argv included
`--device none -ngl 0` and the specified compact Qwen model. Only own processes
were stopped by their recorded campaign identity (or the two specific command
sessions for queued Gradle clients). Shared resources touched: CPU, npm cache,
Gradle queue/cache, installed Chromium and the permitted local llama executable/model.
No dev stack, owner app/data/display, GPU, downloads, GitHub actions, commits or
publication. No custom logs were written under /tmp/js-linux-port.


HO8a completion, 2026-10-27 (agent-adopted, delegated-choice; uncommitted):

The exact third snapshot on ec41e49283 is `{ place: <original conversation id>,
text: "", preparation: { kind: "ask", scope: { kind: "all" } } }`. `swapPlace`
(SearchV3View.ts:3787 after this repair; :3779 before) unconditionally passes it to
`persistDraft` (:1121). A and B already occupy both slots. `saveRecoveryPayload`
(:1235) previously submitted the empty current plus both retained inputs to the unchanged
`isSv3Recovery` two-draft validator (sv3-recovery.ts:55).

`SearchV3View.ts` now omits only that empty current conversation snapshot from the
serialized payload when it would occupy a third slot. The live payload retains current-first
ordering, so callers' existing companion-copy bookkeeping keeps both A and B after the
acknowledged write, later saves and stream completion. Empty hero/results departures,
input-bearing preparations, DS1 limits, sameDraft deduplication, validator and persisted
format are unchanged. The exact snapshot and classification were observed with a temporary
spy before production edits; no diagnostic instrumentation remains.

`SearchV3View.streamReturn.test.ts` now has two repair cases and three preservation
cases. The address-route case exercises the same handler as Back; browser Back/Forward
is checked separately. The third-input fixture models a raw draft newer than its two
acknowledged preparation copies after an interrupted save. Its actual navigation must
refuse without changing raw text or either retained copy. The storage-failure case
requires a write acknowledgement even when omitting an empty destination snapshot.
Existing `SearchV3View.handoffs.test.ts` (including DS1 :699) and
`SearchV3View.coldArrival.test.ts` are unchanged. The previous builder's A6 changes in
`chrome/Shell.ts` and ten checks in `Shell.tabIdentity.test.ts` are kept.


Final browser evidence is `gates/test-intent/evidence/uiux-compat-stream-return-browser.json`.
It archives reproducible harness versions, native HTTP observations, snapshots of visible
inputs and draft/journal storage, exact model/Engine/Chromium process identities and argv,
per-case cleanup, baseline unit assertions, final unit/gate logs and build logs. This is
builder evidence, not independent acceptance. The earlier
`uiux-compat-cutover-regressions-browser.json` remains historical: its missing controls
and pending HO8a decision are superseded by this completed campaign.

Browser outcomes (each group has fresh own HOME/data/index/corpus; only the full-restart
control preserves its own data/origin while changing frontend):

| Case / command in private runtime | Before / known-good | Candidate |
| --- | --- | --- |
| `node stream-origin-access.mjs before` / `after` | ec41 exit 1, intended HO8a assertion: original transcript absent after one row click; B remains, capacity refusal; one chosen-file dispatch, no Stop | exit 0, original transcript visible after one row click; A/B and issued task remain in attachment; one dispatch, no Stop |
| `node stream-origin-access.mjs before-dev` on ebf79183 | exit 0 on genuine old developer Search; known-good one-click return | Same check passed above |
| `node new-tab-identity.mjs` (encrypted record authored through actual UI) | Four A6 unit assertions fail on ec41; prior builder's actual browser reproduction retained | exit 0, no-opener new tab initially has empty per-tab identity and stays empty, no new dispatch |
| `node identity-controls.mjs` with that encrypted record | Six A6 preservation unit assertions pass on ec41 | exit 0: explicit conversation, same-tab bare boot/reload, explicit query outranking identity, actual Back/Forward, Recents, native 423 locked record, unlock/reload, deleted record without borrowing; zero dispatch |
| `node stream-one-arrival.mjs before` / `after` | ec41 exit 0 | exit 0: edited follow-up restored; original Ask still streaming after one row click; exact original chosen-file request; one dispatch, no Stop |
| `node s67-controls.mjs` | All twelve existing S67 component checks pass on ec41 | exit 0, 2/2 browser cases: compatibility and canonical full-document arrival, exact special-character/whitespace text and chosen file, earlier draft reviewable, zero dispatch |
| `node old-bare-restart.mjs` | Genuine ebf79183 UI authored real record, then wrote a bare old-main journal through explicit bare navigation | exit 0: full Chromium close/reopen using same own profile; candidate bare boot with no tab pointer stays fresh; old real record remains in sidebar; no extra dispatch |

Two exploratory control failures were harness-fixture defects, not passes. A conversation
written before enabling encryption stays readable as legacy plaintext even with the key
locked; configuring encryption before authoring the replacement fixture gives real HTTP
423 and the final full identity campaign passes. The old UI's New chat clears its pointer
without projecting a bare URL; the restart harness now performs explicit bare navigation
in that old UI, verifies its actual bare journal, and then really restarts Chromium.
Both failed attempts and the corrected passing ones are retained. No browser case remains
unverified. No native Tauri or pressure/load behavior is claimed.

Observed validation commands (all changes remain uncommitted):

| Command | Actual result |
| --- | --- |
| `.agents/preflight` existence check | Absent; no preflight executable to run |
| `git clone --quiet --shared --no-checkout . "$TMPDIR/before"`; detached ec41e49283 checkout | exit 0; final new test files overlaid, production baseline unchanged |
| Same shared clone / detached checkout recipe for ebf79183 known-good | exit 0; separate dependencies and build directory; genuine old UI exercised |
| Plain `npm ci` in source and baseline `modules/ui-web` | exit 0 each, 283 packages each; own dependency trees; npm reports one moderate dependency vulnerability with unchanged lockfile |
| Plain `npm ci` in known-good `modules/ui-web` | exit 0, 283 packages, own dependency tree |
| Plain `npm ci` at source repository root | exit 0, 386 packages, zero audit vulnerabilities; lockfiles unchanged |
| Initial `npm run test:unit:run -- src/shell-v0/views/search-v3/SearchV3View.streamReturn.test.ts --configLoader runner --maxWorkers=4` before production edit | exit 1: intended original HO8a assertion fails, one-arrival control passes (1 fail / 1 pass). Temporary spy rerun gives the exact empty Ask/all destination above, also 1 fail / 1 pass |
| Focused four-file run (A6, HO8a, S67 and unchanged handoffs), initial general empty-snapshot omission | exit 1: 87 pass / 2 fail. Existing DS1 bare-hero departure exposed over-broad omission; narrowed to conversation places. New storage fault fixture was corrected to spy on actual sessionStorage instance |
| Same focused run after narrowing | exit 0: 89/89 pass. New third-input preservation fixture was subsequently refined to model interrupted raw/attachment persistence directly |
| Exploratory paired baseline runs | Existing handoffs/S67: 74/74 pass. Intermediate new fixtures: 8 pass / 7 fail out of 15, then 82 pass / 7 fail out of 89; extra fixture failure was a supposed third input that actually replaced its current proposal. Final interrupted-save fixture uses the real navigation handler, rather than an ignored unknown sidebar id |
| Final baseline four-file command `npm run test:unit:run -- src/shell-v0/chrome/Shell.tabIdentity.test.ts src/shell-v0/views/search-v3/SearchV3View.streamReturn.test.ts src/shell-v0/views/search-v3/SearchV3View.coldArrival.test.ts src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts --configLoader runner --maxWorkers=4 --reporter=json --outputFile=<private report>` | exit 1: exactly 6 intended repair assertion failures (4 A6 + 2 HO8a), 83 pass, zero skips. All 3 final HO8a preservation checks, all existing DS1 and all 12 S67 checks pass |
| Final focused HO8a five-case command with JSON reporter | exit 0: 5/5 pass, zero skips. During fixture refinement, the unknown sidebar id caused no navigation/error; corrected to actual address navigation and checked before/after |
| `node scripts/governance/gates/test-intent/run-evidence.mjs --out gates/test-intent/evidence/uiux-compat-stream-return-repair.json --before ec41e49283 --before-root <private temp> --check <two HO8a ids>` | exit 0: 2 candidate passes; both fail-before classifications are assertion. Unchanged producer; Git adapter makes only shared clones, runs plain npm ci and removes them; private Git objects/index, no source commit or worktree registration |
| Same official producer `--out gates/test-intent/evidence/uiux-compat-stream-return-preservation.json --check <three preservation ids>` | exit 0: 3/3 candidate passes; final paired baseline above also passes all 3 |
| `npm run typecheck` | exit 0, including final new test fixture |
| `npm run lint` | exit 0, including final new test fixture |
| First full `npm run test:unit:run -- --configLoader runner --maxWorkers=4` | exit 1: 645 files pass / 1 fails; 8,240 tests pass / 1 fails. Sole failure was the new fixture's ignored unknown sidebar id; corrected to real navigation, no product failure masked |
| Final full `npm run test:unit:run -- --configLoader runner --maxWorkers=4` | exit 0: 646/646 files and 8,241/8,241 tests pass, zero skips; duration 110.66s. Full log archived |
| `npm run build` in source / ec41 baseline / ebf known-good `modules/ui-web` | all exit 0, separate dist folders; source and baseline transform 1,061 modules, known-good 1,058. Existing SES bigint-target and chunk-size warnings retained |
| `node scripts/ci/run-ui-web-gates.mjs` | exit 0, 28/28 pass (initial and final logs) |
| `node scripts/governance/gates/test-intent/cli.mjs check --base f17b988e2f` | exit 1 only for missing independent acceptance. Non-failing finding: reconsider rate 2/19 (10.5%); deliver to orchestrator |
| `git diff --check` | exit 0 |

Only independent verification/acceptance remains for the orchestrator. No scenario/design
change, DS1 retirement, limit/validator/format change, existing test-file edit, commit,
GitHub action, publication, dev-stack use, owner-data access, desktop/display use or model
download was made. Only owned processes were stopped by Popen/recorded PID/start identity;
all ten private runtime groups have cleanup records with removed data/HOME, and all short
Chromium profiles are removed. Shared resources touched: CPU, npm cache, installed Engine,
Chromium and the permitted read-only llama executable/model. No Gradle command or Gradle
build pass is claimed in this continuation; the unchanged preinstalled backend serves both
frontend versions. All test/service launchers strip inherited JUSTSEARCH/JVM option variables
and set CUDA_VISIBLE_DEVICES=-1. Actual model argv records CPU flags; a late /proc probe
found no live model and is explicitly not positive process-environment evidence.


Refusal navigation repair, 2026-10-27 (agent-adopted, delegated-choice):

`URLProjector.writeUrl` no longer suppresses writes while a fresh hand-off is parked.
Only initial projection activation preserves an arrival awaiting intake or just refused;
subsequent navigation projects normally. The refusal notice in `SearchV3View` shows the
exact incoming text and distinct file count. An acknowledged companion-copy discard
retries the existing synchronous intake immediately; acceptance or successful navigation
retires the parked hand-off and its notice. No task sends automatically. Storage,
validator, limits, recovery format and draft ownership are unchanged.

Rule 4 takes the brief-authorized fallback: reload remains restoration and the isolated
unaccepted-input reload behavior is unverified, not claimed repaired. A real browser
probe showed a fresh refused native hash entry has `history.state === null`, just like
an accepted original pre-boot input address. The attempted ownership check missed that
fresh refusal; inputs alone cannot distinguish it from accepted work without new stored
state. That optional implementation and its new optional repair check were withdrawn;
`router/sources/URLSource.ts` stays byte-identical to cbde03ed95. Public discard now
prepares the task immediately, rewrites its address normally, and the S77 script's
subsequent reload keeps it. Five new repair assertions fail on cbde03ed95 and pass after;
six new preservation checks pass on both. With unchanged S67/DS1/A6/HO8a/URLSource
controls, paired runs are before 109 pass / 5 intended failures, after 114 pass / 0
failures, no skips.

Every previously accepted test file stays byte-identical to cbde03ed95.
`SearchV3View.coldArrival.test.ts` checks retention of the arrival address at refusal;
it does not assert freezing subsequent navigation, and still passes unchanged.
Only new `SearchV3View.refusalNavigation.test.ts` contains these regression checks.
The unchanged official producer records assertions and content digests in
`uiux-refusal-navigation-{repair,preservation}.json`; its temporary Git adapter uses
shared clones, detached baseline checkouts and plain npm ci, with private temporary
Git objects/index. No commit, source Git object write, registered worktree or shared
node_modules is needed. Browser outcomes and exact commands are recorded in
`gates/test-intent/evidence/uiux-refusal-navigation-browser.json`; full dumps and harness
sources stay in the builder report folder rather than gates. This is builder evidence;
independent acceptance remains for the orchestrator.

Final observed validation: typecheck and lint exit 0; full unit suite 647/647 files,
8,252/8,252 tests, zero skips; UI gates 28/28. Official producer: 5/5 repairs pass
with 5 assertion failures before, and 6/6 preservation checks pass. Test-intent has
only the missing independent acceptance. Requested real-browser matrix on final code:
S77, S79, S80, S81, S78 and S67 all pass (6/6); baseline cbde03ed95 has S78/S67 pass
and the four repair cases fail at the reported behavior. Native saved records were
authored by two completed UI Sends per saved-conversation group on the permitted
compact Qwen CPU. No incoming task sent automatically; no HTTP 429 blocked a case.
The additional isolated unaccepted-reload fixture still fails to prepare after reload
on both revisions; it is the explicitly unverified rule-4 fallback, not a seventh pass.
Before/after visible snapshots also show incoming text plus 129 files in the refusal
notice, and Third immediately in the composer after public discard only after repair.
Every group uses fresh own HOME/data/index/corpus and cleans up its recorded processes
and short headless Chromium profile; no owner apps/data/display, dev stack, GPU or model
download. Full logs/screenshots stay outside gates; the compact browser evidence is
under 200 KB. No Gradle build is claimed; the unchanged preinstalled Engine serves
the corresponding private frontend. All changes remain uncommitted.


Refusal notice retirement completion, 2026-10-27 (agent-adopted, delegated-choice):

The navigation handler already takes the parked hand-off on bare Search navigation.
Absent fresh state does not hydrate stores, and reselecting the mounted surface does
not reconnect SearchV3View. Consequently its local refusedHandoff and recoveryError
survive without any notification. `takeSearchV3Handoff` now emits the existing
`sv3-handoff` notification when it removes a refused task; the receiver's empty-register
branch invokes the existing retirement method and requests a render. No new event,
store, format, retention rule or capacity limit is introduced.

The old two-draft alert is the failed arrival's explanation, so it clears with the
refusal. Capacity remains enforced on each new preparation attempt. The new component
file checks the exact current draft and retained copies, absence of the notice/alert,
and discard without resurrection. On bd558647f6, rail and fresh-address cases fail
at the stale notice assertion; traversal already passes through the store receiver.
A modal navigation also retires the refused register explicitly before opening its
window; it previously bypassed the normal handler branch entirely. Three repair cases
fail at the stale notice assertion on bd558647f6. With traversal and both other
preservation cases plus the eleven unchanged refusal-navigation checks, the paired
result is before 14 pass / 3 fail; after 17 pass / 0 fail.
Previously accepted test files remain byte-identical. Official producer evidence uses
the same shared-clone/private-npm Git adapter convention documented above; all source
changes remain uncommitted. This is builder evidence, not independent acceptance.

Final completion checks and limits for the refusal-notice retirement:

- Causes: normal navigation consumes the refused register at `navigationHandler.ts:215`
  without a store change or remount; `compose.ts:257` previously made that silent.
  `SearchV3View.ts:4035` previously returned without retiring its local notice.
  Modal navigation at `navigationHandler.ts:188` bypassed register consumption entirely.
- `SearchV3View.refusalRetirement.test.ts`: six new checks, three repair and three
  preservation. The official producer records three assertion failures on bd558647f6
  and three candidate passes for repair; three preservation candidate passes. The
  paired new-file plus unchanged refusal-navigation run gives 14 pass / 3 intended
  failures before, 17 pass / 0 failures after. No accepted test file was edited.
- Final full `npm run test:unit:run -- --configLoader runner --maxWorkers=4`: exit 0,
  648 files / 8,258 tests, zero skips. Lint exit 0; UI gates exit 0, 28/28.
  Typecheck's initial unused copied test constant was removed; final result is
  recorded in the compact browser/command evidence. No assertion was weakened.
- `gates/test-intent/evidence/uiux-refusal-retirement-browser.json` records eight
  requested final browser cases passing: trusted rail pointer and native fresh hash
  retirement, S77 capacity/discard/reload, S78 accepted-address history/reload,
  S79 refusal/conversation history/later arrival, S80/S81 Search and retry, and S67
  full-document cold arrival. Both retirement probes fail on bd558647f6 specifically
  because the refusal notice persists; B stays exact and discard never prepares C.
  After repair the notice and its old capacity alert both disappear, while retained
  drafts and the genuine limit on another incoming proposal remain intact.
- Each case group uses fresh own Engine HOME/data/index/corpus and headless Chromium
  profiles. The two saved records were authored by two completed actual UI Sends on
  the permitted Qwen CPU model; actual argv contains `--device none -ngl 0` and
  CUDA_VISIBLE_DEVICES=-1. No hand-off sends automatically. All owned service groups
  and short Chromium profiles have cleanup records. No 429 blocked a final case.
- The first history run's S79 later-arrival assertion failed during a Vite reload
  while production source was being edited; its earlier history assertions passed.
  That failed attempt is retained, not counted as a pass. The frozen final source
  passes the unchanged scripts on fresh services. Native Chromium sandbox startup
  failed; permitted --no-sandbox was used only against own loopback services.
- `node scripts/governance/gates/test-intent/cli.mjs check --base origin/main` exits 1:
  missing independent acceptance and three unrelated uncovered JVM files:
  `IngestPreparationBoundaryTest.java`, `HeldInferenceCompositionFixture.java`,
  `VerifierCappedBacklogStatusTest.java`. All three also fail on bd558647f6 against
  the exact same main commit a9a5928a92cc. They are outside this assignment; no JVM
  tests or their changesets were changed. The orchestrator must reconcile them.
- Plain npm ci ran independently in the source root, source UI and shared-clone
  baseline UI. Shared resources touched: CPU, npm cache, installed Engine/Chromium,
  and the permitted read-only llama executable/model. No Gradle build, dev stack,
  owner data/display, GPU, model download, commit, push or publication is claimed.
  Browser evidence is under 200 KB; full logs/screenshots stay in the private temp
  report folder named in that evidence. Independent verification remains outstanding.


D-T24 scope retirement guard, 2026-10-27 (agent-adopted, delegated-choice):

HO9 is owner-agreed, not replaced: `/home/elias/dev/justsearch/tmp/verify/agreed/wp1.3b.md:33`
(S15.9 / HO9) says one-shot data “never reaches a later, unrelated send ... including
... navigates away”. D-T24 is the orchestrator's agent-adopted design under delegation.

`markPossibleMove` still retires forced shape, selection and chosen-file context after
an actual departure. The retained view now remembers which unchanged text lost context,
shows a composer notice, and rejects both composer and view submission paths. Explicit
file/mode choices or editing the question clear that guard. Choosing a new file also
establishes its new one-send Ask scope. Synchronous layout reparent still skips retirement.
Ordinary Ask preparations with no lost scope/shape are unaffected. Existing `reselect`
preparation carries unknown input if the guarded draft is saved; no store, option,
validator, format version or limit changed. The saved prior proposal remains available
for explicit recovery review, preserving S67 and the existing two-proposal contract.

New component checks: six repair assertions fail on 887587e2c3 and pass after; three
preservation checks pass on both. The unchanged official producer records them in
`uiux-scope-retirement-{repair,preservation}.json`, using shared clones, private Git
objects/index and plain npm ci per checkout. All previously accepted test files stay
byte-identical. No builder acceptance, commit, push, publication or issue action.

Final full unit run: 649 files / 8,267 tests pass, no skips. Typecheck and lint exit 0;
UI gates 28/28 pass. An earlier full run exposed overbroad guard/persistence changes
(ordinary context reload and four S67 checks); those were repaired without touching
old checks, and the final suite passes. Full logs retain those initial failures.
Browser and final governance results are recorded in the compact evidence linked below.


An explicit click on the already displayed mode now also reaches the existing tier
handler (`Sv3Composer.chooseTier`); otherwise choosing Ask could not clear the new
guard. The new preservation check clicks the real mode menu rather than synthesizing
its event. Final official evidence was regenerated for this exact test file: six
assertion failures before, all nine new checks passing after. The paired baseline with
unchanged hand-off/cold-arrival/context checks: 150 pass / 6 intended failures (156).
The final full suite after this change again passes all 8,267 tests in 649 files.

Final browser evidence: [uiux-scope-retirement-browser.json](../evidence/uiux-scope-retirement-browser.json).
On the final frozen candidate, all 13 scope/send browser assertions pass: nine Library
→ Back, Library → Search rail and Forward-after-Back combinations across one/two/refused-
third arrivals; three CPU-ready Enter issuance probes; and one explicit choice of the
currently displayed Ask mode. Unchanged returned text sends no request. Choosing Ask
explicitly permits a plain Ask with no retired selection.

The final private Engine then exits 1 unexpectedly. The runtime supervisor raises
“Own service exited”; engine.log reports a crash-head JSON file. The supplied harness
removes its private profile in finally, so that crash report itself was removed and the
remaining evidence establishes the exit and reported path, not its cause. Per the builder
brief's instruction to return on a non-implementation failure, no Engine restart or JVM
repair was attempted. The final conversation → Back and new guard-carry browser checks
are unverified; the orchestrator must investigate or authorize a separate Engine lane
before those can complete. All seven owned service groups are cleaned. The final group
has 13 passing assertion cases and an unsuccessful service lifecycle; it is not an
overall browser validation pass.

Earlier intermediate conversation → Back runs pass on baseline and candidate, each
using a genuine completed model turn as its saved-record seed. The original chosen-file
proposal remains available for explicit review and nothing beyond the seed is sent.
Those intermediate runs are not promoted to final-candidate verification. Baseline
issuance probes send docIds: [] on all three routes while native inference is available.
The nine adapted baseline return cases fail at the missing notice. The original verifier
scope-return script additionally reproduces all three reported baseline scope losses.

The verifier scripts were adapted to D-T24's visible retirement/send-block expectation;
the obsolete expectation of live scope retention is not asserted after the repair.
The issuance probe uses direct private native HTTP rather than the verifier's controlled
residency gate or its unrelated pooled-source switch. Harness sources, hashes, exact
native CPU model argv, readiness, request scopes, outcomes and cleanup are in the compact
evidence (under 200 KB). Full dumps/screenshots/logs stay in the task's private temporary
folder. Native Chromium sandbox startup failed; only own loopback services were tested
with the permitted --no-sandbox fallback. All seven owned service groups and their browser
profiles have cleanup records. One successor supervisor was launched while the prior
shutdown poll still reported running; this sequencing lapse is recorded in the evidence.
Later restarts waited for full supervisor completion. Only recorded owned processes
were stopped. No GPU, downloads, dev stack, owner apps/data or desktop was used.

The native build request was canceled while queued, before acquiring a Gradle slot.
No native build is claimed: the unchanged preinstalled Engine served private before/after
frontends with identical install hashes, as in the preceding repair. There are no JVM
changes. Plain npm ci ran in the source root, source UI and shared-clone baseline UI;
producer-created shared clones also installed their own dependencies. .agents/preflight
is absent. Final typecheck, lint, 8,267 unit tests and 28 UI gates pass.

Final test-intent against origin/main be1f315b5072 exits 1 with this package's missing
independent acceptance and eight unrelated main-moved JVM items under
modules/ui/src/test/java/io/justsearch/ui/api/: StatusFixtureWork.java,
StatusIsolationHttpTest.java, StatusNonBlockingHttpTest.java, VerifierStatus2Test.java,
VerifierStatus3Test.java, VerifierStatus4Test.java, VerifierStatus5Test.java and
VerifierStatusIsolationTest.java. All eight also occur on the baseline against the
identical main commit; none was edited. No evidence-invalid or source/digest problem
is reported. Independent verification/acceptance belongs to the orchestrator.


The final guard also follows unchanged text when navigation carries it into an unvisited
conversation. Applying a preparation during that carry is not a person's mode choice.
Only explicit review/choice clears the guard; another place's remembered guarded copy
requires review. A new ninth component check fails on the baseline at the missing notice
and passes on the final code. Official repair evidence now contains six fail-before
assertions and six final passes; the three preservation checks still pass on both.
Final browser assertions above refer to final-scope. The planned final-conversation
group was not launched after the unexpected Engine exit; earlier after/after-online/
after-conversation runs are retained as intermediate evidence.


Shown-mode Load anyway repair, 2026-10-27 (agent-adopted, delegated-choice):

`SearchV3View.onTierChange` returns before revoking the human task when the selected
tier is already shown and no retired hand-off text is held. Its one-line comment explains
the exception. `Sv3Composer.chooseTier` stays unchanged, so R3's explicit same-mode
acknowledgement still ends the retired hand-off block. No other production code changes.

New `SearchV3View.sameModeLoad.test.ts` exercises the actual composer Mode menu. Two
Load anyway checks fail on 42f911cdba with one dispatch instead of two; all eight
preservation checks pass there. The candidate passes all ten. HTTP refusal and held
202 are controlled fixtures. The DOM keyboard fixture supplies happy-dom's absent native
Enter click; the real browser script separately uses trusted pointer and Enter activation.
Ordinary text, chosen-file scope and Structured schema remain unchanged; R3's shown Ask
choice clears the notice without sending, then explicit Send issues an unscoped Ask.
No previously accepted test file was edited. The unchanged official producer generated
repair and preservation evidence using the existing documented shared-clone/private-npm
Git adapter, private object directory and temporary index; no commits or worktree registration.

Typecheck and lint pass. Full unit suite: 650 files / 8,277 tests pass, zero skips
(116.96 s). UI gates: 28/28 pass. Against origin/main 372b812bf137 the governance
check reports missing independent acceptance plus nine unrelated uncovered tests; all nine
also occur on 42f911cdba against that exact base. They are listed in the compact browser
evidence and were not edited. No evidence-invalid or source/digest problem is reported
for this candidate. Independent verification/acceptance remains with the orchestrator.

Browser completion and exact commands: [uiux-same-mode-load-browser.json](../evidence/uiux-same-mode-load-browser.json).

Final real-browser results: baseline 42f911cdba has the untouched control pass and both
same-mode pointer/keyboard cases fail at the missing retry. The candidate passes all
three, with dispatch HTTP statuses 503/200 in each case and the exact original question
in its sole follow-up. Pressure refusal and held 202 are injected exactly as the verifier
did; successful follow-up Engine/model responses are real. R3 pointer and keyboard both
pass on another fresh private Engine: retirement blocks unchanged text, choosing shown
Ask itself sends nothing, then deliberate Enter sends one ordinary Ask. Four additional
real chosen-file/schema preservation controls pass. The R3 seed is a real selected-file
answer saved through UI and checked through native history. There are no blocked cases.

All three Engine groups shut down successfully, with no overlap between groups; only
owned services were stopped. Headless Chromium short profiles were removed. Native
sandbox startup failed; the permitted --no-sandbox fallback used only own loopback
services. CPU argv/CUDA evidence is captured on the final fresh group. The unchanged
installed backend was reused; no Gradle build, GPU use or download is claimed. Shared
resources touched: CPU, npm cache, preinstalled Engine/Chromium and permitted read-only
llama executable/model. Compact browser evidence stays under 200 KB. Against this task's
42f911cdba baseline governance has only the missing independent acceptance. Against
origin/main the nine unrelated uncovered items remain; each was reproduced on baseline
against the same exact main commit. All work stays uncommitted.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "orchestrator",
  "session": "8c330fb4-0ab8-439d-8de2-6a42f0669e2b",
  "verdict": "accept",
  "digest": "sha256:5f70edbf661a345a9c76f07bd89a86baea04827bc3b7511a8d639debca6e1ecc",
  "note": "Accepted on independent evidence: Codex verifiers rounds 3-6 (separate sessions) passed the matrix, R1-R5, HO9, recovery rows and P1 on the real browser and Engine; all 16 entries are New checks in new files with delegated-choice warrants; no existing test changed."
}
```
