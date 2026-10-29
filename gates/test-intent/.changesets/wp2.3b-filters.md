---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: wp2.3b-builder-20261029
---

# Test-intent entries

The new cases use the existing mounted production Search harness; existing oracles remain unchanged. Evidence is generated with the official runner in an isolated shared clone; the repair leaves the candidate uncommitted for the orchestrator.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts"
      ],
      "class": "New",
      "behaviour": "WP2.3b session-only folder, local calendar day and binary size controls; apply-on-commit, request ordering, Ask ownership, validation, precedence, profile clearing and context honesty through the mounted production Search view.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Remaining Search UI work in areas 1\u20135, WP2.3 package b.",
          "choice": "D1\u2013D12 and F1, F4b, F5 UI, F7 UI, F8\u2013F12, F16 and F18, revision 3 settled in /home/elias/dev/justsearch/tmp/verify/wp2.3-filters/scenarios-wp2.3.md. Session restrictions with per-Send Ask capture; fixed prepared material suspends restrictions.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp2.3b-filters.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F1 commits folder date and size controls and removes each restriction without losing the query",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F4b converts local DST calendar days and rounded binary units and keeps entered chip values",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F5 keeps invalid text beside its error and applies no invalid or incomplete edit",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F7 empty Search names active restrictions and restricted Ask no-content never silently broadens",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F8 same-query filter commits cancel stale rows counts facets and chosen-file preparation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F9 plugin dates preserve folder and size and protected owners never issue filter reruns",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F10 Ask captures at Send and follow-ups capture new controls while live Edit and Retry keep originals",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F11 prepared file Ask and non-retrieval modes explain restriction precedence",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F12 navigation keeps session filters and both profile signals clear and cancel older generations",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F16 controls chips notes and inline errors have accessible names and Escape returns focus",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F18 restricted follow-up carries new restrictions while earlier answer remains in context",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F5 Engine 400 errors appear next to Search and Ask controls with the entered text intact",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F10 pre-admission held Ask owns its Send snapshot before a later authored follow-up",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F8 plugin date commits supersede an identical query before older delayed responses finish"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts"
      ],
      "class": "New",
      "behaviour": "WP2.3b repair: merely visiting or pressing Enter in an unchanged field preserves exact API timestamps, byte bounds, folder spelling and input metadata and sends no request. An edited field commits only its own bound at local-day or binary-unit granularity.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Remaining Search UI work in areas 1\u20135, WP2.3 package b.",
          "choice": "D1, D12, F1, F9 and P1/P2, revision 3 in /home/elias/dev/justsearch/tmp/verify/wp2.3-filters/scenarios-wp2.3.md: only a changed field commits; preserve exact API restrictions in untouched controls and opposite bounds. UI-only repair under the existing design delegation.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp2.3b-unchanged-fields.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F1 D12 unchanged 'folder' preserves exact API restrictions on blur and Enter and an edit commits only that bound",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F1 D12 unchanged 'from' preserves exact API restrictions on blur and Enter and an edit commits only that bound",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F1 D12 unchanged 'to' preserves exact API restrictions on blur and Enter and an edit commits only that bound",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F1 D12 unchanged 'minimum' preserves exact API restrictions on blur and Enter and an edit commits only that bound",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F1 D12 unchanged 'maximum' preserves exact API restrictions on blur and Enter and an edit commits only that bound"
      ],
      "repaired": true
    },
    {
      "items": [
        "governance/ui-a11y-baseline.v1.json",
        "governance/ui-proportion-baseline.v1.json"
      ],
      "class": "Structural rule kept",
      "rule": "Keep every existing axe baseline and geometric predicate unchanged. Add empty-knownRules axe rows and positive presence, minimum height, viewport bottom, no topbar overlap and non-scrolling outer-container constraints for all 24 Filters states.",
      "whySurvives": "The existing states and thresholds remain byte-equivalent as data; the new states exercise the production Filters controls with all three restrictions, a local validation error and a full Recent list. Scroll is confined to bounded panel contents.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Remaining Search UI work in areas 1\u20135, WP2.3 package b.",
          "choice": "D1\u2013D12 and F1, F4b, F5 UI, F7 UI, F8\u2013F12, F16 and F18, revision 3 settled in /home/elias/dev/justsearch/tmp/verify/wp2.3-filters/scenarios-wp2.3.md. Session restrictions with per-Send Ask capture; fixed prepared material suspends restrictions.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records

Pending independent acceptance of the refreshed UTC execution evidence.
