---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: codex-builder-fm-type-ladder-20261113
---

# Test-intent entries

This changeset inventories the five items added or changed by bf602f159a (Inter inside the
app, six text sizes, four radii). Expected values follow the adopted design and delegated
implementation choices below, not the fact that the candidate produces them. Existing
assertions whose product decisions changed are `Obsolete`; their replacements are listed
explicitly rather than described as oracle-preserving adaptations.

Source provenance: R1 names **rule 1** and R8 names **rule 8** of task
t-20261113-uicritique. These are agent-drafted, owner-adopted scenarios. The gate requires
an alphanumeric scenario ID, so the original rule names are retained as `scenarioName`.
The adoption location is task t-20261113-uicritique user turn round 7:
“yes, update accordingly. im especially surprised by how long it took for agents to realise the text size issue by themselves. you are the first one.”
Round 14 continued that implementation handoff:
“now that the prototype is clear, do you think you should do any further work to make the implementation faster and more accurate for the implementers? if so, proceed with that work accordingly and give me the prompt you want me to start the implementer with.”
The scenario quotes in the entries are the adopted rules, not owner-authored wording.
Choices beyond those rules quote the implementation delegation separately and carry the
label `agent choice`; the acceptor must confirm their scope against the named task turns.

The new src/boot/type-ladder-lockdown.test.ts and src/boot/woff2-axes.test.ts are exempt as isolated new test sources.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/chat/MarkdownBlock.geometry.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R1",
          "scenarioName": "rule 1",
          "quote": "Six text sizes and four corner radii, nothing in between. Text: 11 small labels · 12 secondary text · 13 body and controls · 15 the search field and lead sentences · 20 headings · 28 the page headline. Corners: 4 marks and bars · 8 controls and rows · 14 fields, cards, panels and dialogs · fully round. Weight 400 for text, 500 for names and actions, 600 only for the brand.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R8",
          "scenarioName": "rule 8",
          "quote": "One typeface, shipped inside the app: Inter. The app named fonts it did not include, so every computer showed a different one. Ship the font files with the app (no download at run time); headlines use its display cut.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Use document headings h1 20px, h2 15px and h3-h6 13px at weight 500. Keep answer lead prose at 15px independently of the 13px body/control token; tool cards, reasoning, citation hover cards and the citations panel use that 13px token. This implements rule 1 without carrying forward the old 18px, 16px and 14px Search steps or weight 700.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Name the bundled family 'JustSearch Inter', expose it through --font-native, and let Search inherit it through --font-sans. Declare roman and italic @font-face rules once in the global token sheet; keep the system monospace stack for code and avoid duplicate faces in Search.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Use the 8px tier for Markdown preformatted code blocks; use --font-native for the renderer host so it receives the shipped Inter family.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. Searched docs/decisions for font, typeface, Markdown, radius, heading, corner and type size; no record prescribes the superseded type or radius ladder. The previous tests and their historical design comments are the old contract being superseded by owner-adopted rule 1 and rule 8, not a stronger source against those adopted rules.",
      "assertionChanges": [
        {
          "oldAssertion": "RETUNED_873 expects --md-heading-weight 700 and contrasts it with 600; VARIANT_DEFAULTS also expects 700.",
          "newAssertion": "Both tables expect 500; the retune regression counterexample is now 700.",
          "reason": "Rule 1 limits 600 to the brand. The delegated document-heading choice assigns 500 to all h1-h6 headings; the old 700 expectation and its historical 600 comparison are superseded."
        },
        {
          "oldAssertion": "FROZEN_DEFAULTS expects --md-pre-radius 0.375rem (6px); the resolved pre rule in BEFORE also expects 0.375rem.",
          "newAssertion": "Both expect 8px.",
          "reason": "Rule 1 excludes the intermediate 6px radius. The delegated Markdown code-block choice uses the 8px tier."
        },
        {
          "oldAssertion": "The renderer host in BEFORE expects font-family system-ui, -apple-system, sans-serif.",
          "newAssertion": "It expects var(--font-native).",
          "reason": "Rule 8 replaces platform-dependent typography with bundled Inter. The delegated family wiring makes --font-native resolve to 'JustSearch Inter' with fallbacks."
        },
        {
          "oldAssertion": "The prose ramp expects h1 --font-size-xl, h2 --font-size-lg and h3 --font-size-md; h4-h6 already read --font-size-sm.",
          "newAssertion": "h1 still reads --font-size-xl (now 20px); h2 reads --font-size-md (15px), and h3 reads --font-size-sm (13px), matching h4-h6.",
          "reason": "Rule 1 supplies the allowed sizes; the delegated document-heading choice assigns the precise h1/h2/h3-h6 hierarchy. Other geometry, margins, inputs and prose gating stay guarded."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/themes/token-names.generated.ts"
      ],
      "change": "modified",
      "kind": "test-support",
      "class": "Structural rule kept",
      "rule": "The closed TokenName union and TOKEN_NAMES array are exactly the sorted token vocabulary declared by tokens.css plus palette JSONs, generated by scripts/ci/gen-token-names.mjs and checked by its --check mode.",
      "whySurvives": "Both generated representations add only --font-size-secondary and --font-size-headline, corresponding to the new 12px and 28px declarations in tokens.css. No vocabulary member is removed and the generator is unchanged. node scripts/ci/gen-token-names.mjs --check passed with 252 tokens. The register gates this file as a helper imported by token.test.ts; token.ts also imports its union as a type. It owns no saved state and adds no hand-written expected output.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R1",
          "scenarioName": "rule 1",
          "quote": "Six text sizes and four corner radii, nothing in between. Text: 11 small labels · 12 secondary text · 13 body and controls · 15 the search field and lead sentences · 20 headings · 28 the page headline. Corners: 4 marks and bars · 8 controls and rows · 14 fields, cards, panels and dialogs · fully round. Weight 400 for text, 500 for names and actions, 600 only for the brand.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "assertionChanges": [
        {
          "oldAssertion": "The generated union and array do not contain --font-size-secondary or --font-size-headline.",
          "newAssertion": "Both contain those two names, with all previous members retained.",
          "reason": "Rule 1 adds the distinct 12px secondary-text and 28px page-headline steps. The structural predicate remains exact equality to the generator inputs, rather than an allowlist exception."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3Main.imports.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R1",
          "scenarioName": "rule 1",
          "quote": "Six text sizes and four corner radii, nothing in between. Text: 11 small labels · 12 secondary text · 13 body and controls · 15 the search field and lead sentences · 20 headings · 28 the page headline. Corners: 4 marks and bars · 8 controls and rows · 14 fields, cards, panels and dialogs · fully round. Weight 400 for text, 500 for names and actions, 600 only for the brand.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Use document headings h1 20px, h2 15px and h3-h6 13px at weight 500. Keep answer lead prose at 15px independently of the 13px body/control token; tool cards, reasoning, citation hover cards and the citations panel use that 13px token. This implements rule 1 without carrying forward the old 18px, 16px and 14px Search steps or weight 700.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Keep Search radius tiers fixed: sm 4px, md/control 8px through --radius (default 8px), and lg/xl/2xl/3xl/4xl 14px. Remove additive offsets, including the tile hairline's minus-one radius. Zero means no corner; 50% counts as fully round. Zero and round interpretations are also exercised by the exempt type-ladder-lockdown.test.ts; they do not add an entry requirement for that file.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. Searched docs/decisions for font, typeface, Markdown, radius, heading, corner and type size; no record prescribes the superseded type or radius ladder. The previous tests and their historical design comments are the old contract being superseded by owner-adopted rule 1 and rule 8, not a stronger source against those adopted rules.",
      "assertionChanges": [
        {
          "oldAssertion": "MD_PROSE_RAMP includes xl/lg/md. The heading bridge requires lg -> sv3-lg; sv3-lg must be 1.125rem (18px), and sv3-base must be 1rem (16px). xl -> sv3-xl and sv3-xl 1.25rem (20px) are already required.",
          "newAssertion": "MD_PROSE_RAMP includes xl/md/sm. The bridge requires sm -> sv3-sm, md -> sv3-base and xl -> sv3-xl; sv3-base must be 15px and sv3-sm 13px. The xl -> sv3-xl and 20px checks are retained.",
          "reason": "Rule 1 removes the 18px and 16px steps. The delegated document-heading choice uses h1 20px, h2 15px and h3-h6 13px, and the bridge-coverage loop now covers the tokens those headings actually read."
        },
        {
          "oldAssertion": "The KEEPS_VARIANT rationale says the shared heading weight 700 is the desired value.",
          "newAssertion": "It says the shared default 500 is desired; the assertion still rejects a local --md-heading-weight override.",
          "reason": "Rule 1 and the delegated heading choice select weight 500. This comment changes the documented reason; the assertion preserving the shared default is retained."
        },
        {
          "oldAssertion": "The answer-prose bridge must set --font-size-sm to 0.9375rem (15px); the window sv3-sm must remain 0.875rem (14px), so h4-h6 also sit at the 15px prose body size.",
          "newAssertion": "The .sv3-markdown rule must explicitly set font-size: var(--font-size-sv3-base), keeping prose at 15px. Its --font-size-sm must point to --font-size-sv3-sm (13px), independently supplying h3-h6. Tool-card, reasoning, hover-card and citations bridges still point to that window sm token, now 13px.",
          "reason": "Rule 1 excludes the old 14px body/control step. The delegated choice retains 15px answer lead prose and separates it from the document-heading 13px bottom step; the other surfaces retain their own bridge assertions."
        },
        {
          "oldAssertion": "CITE_BRIDGE requires --md-cite-radius 0.25em, which varies with the current font size.",
          "newAssertion": "It requires exactly 4px.",
          "reason": "Rule 1 assigns marks and bars the 4px corner tier; an em radius would introduce intermediate sizes as the typography changes."
        },
        {
          "oldAssertion": "The colour-resolution loop excludes only values ending in em, treating every other CITE_BRIDGE value as a colour.",
          "newAssertion": "It excludes values ending in em or px; 4px is validated by the exact bridge assertion, while every actual colour still resolves through the token sheet in both themes.",
          "reason": "This is the unit-filter adaptation required by the 4px citation-mark choice. The old colour predicate would incorrectly test a length as a colour; no colour expectation or theme is removed."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-tokens.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R1",
          "scenarioName": "rule 1",
          "quote": "Six text sizes and four corner radii, nothing in between. Text: 11 small labels · 12 secondary text · 13 body and controls · 15 the search field and lead sentences · 20 headings · 28 the page headline. Corners: 4 marks and bars · 8 controls and rows · 14 fields, cards, panels and dialogs · fully round. Weight 400 for text, 500 for names and actions, 600 only for the brand.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R8",
          "scenarioName": "rule 8",
          "quote": "One typeface, shipped inside the app: Inter. The app named fonts it did not include, so every computer showed a different one. Ship the font files with the app (no download at run time); headlines use its display cut.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Keep Search radius tiers fixed: sm 4px, md/control 8px through --radius (default 8px), and lg/xl/2xl/3xl/4xl 14px. Remove additive offsets, including the tile hairline's minus-one radius. Zero means no corner; 50% counts as fully round. Zero and round interpretations are also exercised by the exempt type-ladder-lockdown.test.ts; they do not add an entry requirement for that file.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Name the bundled family 'JustSearch Inter', expose it through --font-native, and let Search inherit it through --font-sans. Declare roman and italic @font-face rules once in the global token sheet; keep the system monospace stack for code and avoid duplicate faces in Search.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. Searched docs/decisions for font, typeface, Markdown, radius, heading, corner and type size; no record prescribes the superseded type or radius ladder. The previous tests and their historical design comments are the old contract being superseded by owner-adopted rule 1 and rule 8, not a stronger source against those adopted rules.",
      "assertionChanges": [
        {
          "oldAssertion": "--control-radius must be 0.5rem and must not reference --radius. --radius is 0.625rem (10px); sm/md/lg/xl/2xl/3xl/4xl derive as radius minus 4/minus 2/unchanged/plus 4/plus 8/plus 12/plus 16px.",
          "newAssertion": "--radius must be 8px; sm 4px; md var(--radius); lg/xl/2xl/3xl/4xl each 14px. --control-radius must reference var(--radius), both in the geometry set and the ladder test.",
          "reason": "Rule 1 excludes the additive intermediate tiers. The delegated Search radius choice keeps the theme knob on the 8px control tier and fixes marks to 4px and panel tiers to 14px."
        },
        {
          "oldAssertion": "--font-sans must be -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif; the test is described as keeping the platform stack and no shipped face.",
          "newAssertion": "--font-sans must be var(--font-native). The system monospace assertion and absence of @font-face in this Search-only sheet are retained.",
          "reason": "Rule 8 requires bundled Inter rather than the platform stack. The delegated family choice routes Search to 'JustSearch Inter'; the two @font-face declarations now exist in global tokens.css, so the retained local absence check prevents duplication and does not assert that the app has no faces."
        },
        {
          "oldAssertion": "--font-size-sv3-display must be 1.875rem (30px).",
          "newAssertion": "It must be 28px; the composer still reads that display token and retains weight 400 and its tracking assertion.",
          "reason": "Rule 1 explicitly makes the page headline 28px. The Search headline uses that tier."
        },
        {
          "oldAssertion": "The empty-tile hairline border-radius must be calc(var(--radius-md) - 1px), producing a 7px radius at the old default.",
          "newAssertion": "It must be var(--radius-md), the 8px tier at the new default.",
          "reason": "Rule 1 has no 7px tier; the delegated Search radius choice removes the minus-one radius. Hairline shadow, background, ghost scale and shadow assertions remain."
        }
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
  "session": "codex-verifier-cdyw_35n-test-intent",
  "verdict": "accept",
  "digest": "sha256:419aeee66ab037db81f0ae7078b4a6a47eaf2cfa246bd5390a47dbec735c811e"
}
```
