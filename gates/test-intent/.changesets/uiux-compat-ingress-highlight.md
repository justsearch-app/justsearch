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
