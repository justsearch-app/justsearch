---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: codex-builder-fm-teal-20261114
---

# Test-intent entries

This changeset accounts for the nine test files changed by candidate b11b020f89
against origin/main 8b1f34d2d4. Each entry classifies a superseded visual expectation
as `Obsolete` and lists the replacement assertions and their independent intent.
Preserved assertions are described only to delimit each change; no execution pass
or acceptance is claimed here.

Source provenance: R4 names **rule 4** of task t-20261113-uicritique. It is an
agent-drafted, owner-adopted scenario. The gate requires an alphanumeric scenario
ID, so the original rule name is retained as `scenarioName`.
The adoption location is task t-20261113-uicritique user turn round 7:
“yes, update accordingly. im especially surprised by how long it took for agents to realise the text size issue by themselves. you are the first one.”
Round 14 continued that implementation handoff:
“now that the prototype is clear, do you think you should do any further work to make the implementation faster and more accurate for the implementers? if so, proceed with that work accordingly and give me the prompt you want me to start the implementer with.”
The scenario quote in the entries is the adopted rule, not owner-authored wording.

The delegated choices are agent choices. Their delegation quote is
“You are supposed to be the implementation session”, located at task
t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z, with scope
“implement the agreed first-minutes design set”. The entries cite the choices
for neutral citation/originator distinctions and the primary/500 neutral used tag
with at least 4.5:1 contrast. Two further orchestrator choices under the same
delegation keep focus rings and the security dialog's teal border unchanged,
and assign the selected document-view toggle primary text at weight 500.
Those two choices delimit the implementation; these nine diffs add no assertion
for them. The acceptor must confirm the delegated choices' scope against the
named task turns.

Historical descriptions such as “at the ink it always shipped”, “byte-identical”
and “frozen defaults” describe the previous contract; the changed expectations
below deliberately replace the named parts of it. Source-level token and weight
checks do not establish browser appearance, and contrast tables do not pin every
CSS declaration. The entries identify those limits explicitly.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/FilterChip.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R4",
          "scenarioName": "rule 4",
          "quote": "Teal means \"on or working now\" (switches, progress, the lit steps of AI Brain), plus the one filled main action on a screen. Tabs, the active area, selected rows and text actions are neutral. A text action is the normal text colour in medium weight; information is grey.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. Searched docs/decisions for teal, accent-tint, text-tint, text-primary, text-secondary, font-weight, focus ring, originator, used tag, citation colour/color and neutral; no record prescribes the superseded visual values. ADR-0006 describes citation retrieval and matching, not the mark palette. Historical test comments describe the old appearance being superseded by owner-adopted rule 4; they do not supply a conflicting owner decision.",
      "assertionChanges": [
        {
          "oldAssertion": "An active filter chip with the default info tone must expose var(--accent-tint) in the button style.",
          "newAssertion": "The same mounted active/default-info chip must expose var(--text-secondary). The explicit error case still requires var(--accent-danger); keyboard/button semantics, aria-pressed and slotted content checks remain.",
          "reason": "Rule 4 makes information grey and selected controls neutral. The expected secondary token follows that information role, independently of the token the candidate happens to return; this changes the old teal oracle."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/atoms.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R4",
          "scenarioName": "rule 4",
          "quote": "Teal means \"on or working now\" (switches, progress, the lit steps of AI Brain), plus the one filled main action on a screen. Tabs, the active area, selected rows and text actions are neutral. A text action is the normal text colour in medium weight; information is grey.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Distinctions that carry meaning stay distinct by neutral means: grounded citation = primary text at weight 500, weak = secondary, ungrounded unchanged, source-only = primary at weight 600, compared by colour and weight; originator user = primary text, system = secondary, agent unchanged.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. Searched docs/decisions for teal, accent-tint, text-tint, text-primary, text-secondary, font-weight, focus ring, originator, used tag, citation colour/color and neutral; no record prescribes the superseded visual values. ADR-0006 describes citation retrieval and matching, not the mark palette. Historical test comments describe the old appearance being superseded by owner-adopted rule 4; they do not supply a conflicting owner decision.",
      "assertionChanges": [
        {
          "oldAssertion": "A mounted status badge with origin=user must expose var(--accent-tint).",
          "newAssertion": "The user-origin badge must expose var(--text-primary). The agent-origin var(--accent-command), semantic status foreground/background and neutral-border assertions remain.",
          "reason": "Rule 4 removes decorative teal from this identity label; the delegated originator choice explicitly assigns primary text to user while retaining agent and system distinctions. The source specifies the replacement before comparison with the rendered style."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/chat/CitationsPanel.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R4",
          "scenarioName": "rule 4",
          "quote": "Teal means \"on or working now\" (switches, progress, the lit steps of AI Brain), plus the one filled main action on a screen. Tabs, the active area, selected rows and text actions are neutral. A text action is the normal text colour in medium weight; information is grey.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. Searched docs/decisions for teal, accent-tint, text-tint, text-primary, text-secondary, font-weight, focus ring, originator, used tag, citation colour/color and neutral; no record prescribes the superseded visual values. ADR-0006 describes citation retrieval and matching, not the mark palette. Historical test comments describe the old appearance being superseded by owner-adopted rule 4; they do not supply a conflicting owner decision.",
      "assertionChanges": [
        {
          "oldAssertion": "The source-card hover border must contain border-color: var(--cp-hover-edge, var(--accent-tint)).",
          "newAssertion": "It must contain border-color: var(--cp-hover-edge, var(--text-secondary)). The consumer override hook remains; selected-fill precedence, selected-hover treatment and remaining panel assertions stay guarded.",
          "reason": "Rule 4 reserves teal for on/working states and the main filled action. A source-card hover edge is a neutral navigation affordance, so secondary grey replaces its old teal fallback. This is an intentional fallback change, even though the adjacent historical comment still describes the accent as byte-identical."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/chat/MarkdownBlock.citationProjection.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R4",
          "scenarioName": "rule 4",
          "quote": "Teal means \"on or working now\" (switches, progress, the lit steps of AI Brain), plus the one filled main action on a screen. Tabs, the active area, selected rows and text actions are neutral. A text action is the normal text colour in medium weight; information is grey.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Distinctions that carry meaning stay distinct by neutral means: grounded citation = primary text at weight 500, weak = secondary, ungrounded unchanged, source-only = primary at weight 600, compared by colour and weight; originator user = primary text, system = secondary, agent unchanged.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. Searched docs/decisions for teal, accent-tint, text-tint, text-primary, text-secondary, font-weight, focus ring, originator, used tag, citation colour/color and neutral; no record prescribes the superseded visual values. ADR-0006 describes citation retrieval and matching, not the mark palette. Historical test comments describe the old appearance being superseded by owner-adopted rule 4; they do not supply a conflicting owner decision.",
      "assertionChanges": [
        {
          "oldAssertion": "The positive .cite-ref.cite-grounded rule body must contain --text-tint.",
          "newAssertion": "That grounded rule body must contain --text-primary. The base SOURCE rule must still use its own source-colour hook and must not contain --text-tint; projection, class, tooltip and accessibility assertions remain.",
          "reason": "Rule 4 and the delegated citation choice assign grounded marks primary ink at weight 500. The changed assertion pins the primary token in the explicit grounded rule; it does not derive that token from current output or collapse the positive grounding class into the base rule."
        }
      ],
      "checkLimits": "This file checks the grounded colour token, not its weight. Its historical test name claiming the ink always shipped is superseded by rule 4."
    },
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
          "scenario": "R4",
          "scenarioName": "rule 4",
          "quote": "Teal means \"on or working now\" (switches, progress, the lit steps of AI Brain), plus the one filled main action on a screen. Tabs, the active area, selected rows and text actions are neutral. A text action is the normal text colour in medium weight; information is grey.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. Searched docs/decisions for teal, accent-tint, text-tint, text-primary, text-secondary, font-weight, focus ring, originator, used tag, citation colour/color and neutral; no record prescribes the superseded visual values. ADR-0006 describes citation retrieval and matching, not the mark palette. Historical test comments describe the old appearance being superseded by owner-adopted rule 4; they do not supply a conflicting owner decision.",
      "assertionChanges": [
        {
          "oldAssertion": "The BEFORE declaration table for .md-content a expects color: var(--text-tint) and text-decoration: underline.",
          "newAssertion": "That row expects color: var(--text-primary), font-weight: 500 and text-decoration: underline. The rest of the declaration table and geometry/containment checks are unchanged in this diff.",
          "reason": "Rule 4 directly assigns text actions normal text colour in medium weight. Primary/500 expresses that adopted role and retains the underline. The historical BEFORE table is deliberately amended for this product decision; the new colour and weight are not claimed to preserve the previous appearance."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/chat/MarkdownBlock.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R4",
          "scenarioName": "rule 4",
          "quote": "Teal means \"on or working now\" (switches, progress, the lit steps of AI Brain), plus the one filled main action on a screen. Tabs, the active area, selected rows and text actions are neutral. A text action is the normal text colour in medium weight; information is grey.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Distinctions that carry meaning stay distinct by neutral means: grounded citation = primary text at weight 500, weak = secondary, ungrounded unchanged, source-only = primary at weight 600, compared by colour and weight; originator user = primary text, system = secondary, agent unchanged.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. Searched docs/decisions for teal, accent-tint, text-tint, text-primary, text-secondary, font-weight, focus ring, originator, used tag, citation colour/color and neutral; no record prescribes the superseded visual values. ADR-0006 describes citation retrieval and matching, not the mark palette. Historical test comments describe the old appearance being superseded by owner-adopted rule 4; they do not supply a conflicting owner decision.",
      "assertionChanges": [
        {
          "oldAssertion": "The three grounding tiers must have distinct fallback colour tokens, and the SOURCE tier must make four distinct inks. Weak must fall back to --text-secondary; ungrounded to --text-warning.",
          "newAssertion": "The test now compares fallback-colour/font-weight pairs: three distinct grounding presentations and four including SOURCE. It additionally requires grounded weight 500. It retains exact weak/ungrounded fallback tokens and their opt-in colour hooks. A test-local weight reader uses the declared weight or the base .cite-ref weight (default 400).",
          "reason": "Rule 4 removes teal from citation identity; the delegated choice explicitly preserves meaningful differences by colour and weight, assigning grounded primary/500 and source-only primary/600. Thus a colour-only uniqueness requirement is obsolete, while presentation uniqueness remains an independent requirement. The pair comparison follows that chosen distinction rather than allowing whatever combination the candidate emits."
        },
        {
          "oldAssertion": "The default .cite-ref.cite-selected background must be var(--md-cite-selected-bg, var(--accent-tint)).",
          "newAssertion": "It must be var(--md-cite-selected-bg, var(--surface-active)). The exact padding, transparent-edge fallback and other selected-mark containment assertions remain.",
          "reason": "Rule 4 explicitly makes selected rows and active areas neutral. A selected citation mark uses the same neutral active-surface role; its consumer override remains. This intentionally replaces the old frozen teal default."
        }
      ],
      "checkLimits": "The changed pair test pins grounded weight 500 and pair uniqueness; it does not itself pin grounded/source-only primary ink or source-only weight 600. Those are the delegated target values, not additional assertions claimed for this diff. The projection file separately pins grounded primary ink. These are stylesheet-source checks, not evidence of browser rendering or computed contrast."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/utils/originatorTone.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R4",
          "scenarioName": "rule 4",
          "quote": "Teal means \"on or working now\" (switches, progress, the lit steps of AI Brain), plus the one filled main action on a screen. Tabs, the active area, selected rows and text actions are neutral. A text action is the normal text colour in medium weight; information is grey.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Distinctions that carry meaning stay distinct by neutral means: grounded citation = primary text at weight 500, weak = secondary, ungrounded unchanged, source-only = primary at weight 600, compared by colour and weight; originator user = primary text, system = secondary, agent unchanged.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. Searched docs/decisions for teal, accent-tint, text-tint, text-primary, text-secondary, font-weight, focus ring, originator, used tag, citation colour/color and neutral; no record prescribes the superseded visual values. ADR-0006 describes citation retrieval and matching, not the mark palette. Historical test comments describe the old appearance being superseded by owner-adopted rule 4; they do not supply a conflicting owner decision.",
      "assertionChanges": [
        {
          "oldAssertion": "originatorAccent(user) must equal var(--accent-tint); agent must equal var(--accent-command), system var(--text-secondary), and all three must be distinct.",
          "newAssertion": "originatorAccent(user) must equal var(--text-primary). The agent/system tokens and the three-way distinctness assertion remain. The test description now names user=primary and system=secondary.",
          "reason": "The delegated originator choice explicitly assigns user primary, system secondary and keeps agent unchanged under rule 4. Both exact mappings and distinctness are authored expectations, rather than snapshots of the candidate."
        },
        {
          "oldAssertion": "originatorAccentSoft(user) must equal var(--accent-tint-16).",
          "newAssertion": "It must equal var(--surface-active). The agent var(--accent-command-16) and system var(--surface-2) soft-token checks remain.",
          "reason": "Rule 4 makes active and selected surfaces neutral. The user-origin background therefore takes the neutral active-surface role alongside the delegated primary user ink, replacing the old teal wash without deleting the origin axis."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/utils/statusTone.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R4",
          "scenarioName": "rule 4",
          "quote": "Teal means \"on or working now\" (switches, progress, the lit steps of AI Brain), plus the one filled main action on a screen. Tabs, the active area, selected rows and text actions are neutral. A text action is the normal text colour in medium weight; information is grey.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. Searched docs/decisions for teal, accent-tint, text-tint, text-primary, text-secondary, font-weight, focus ring, originator, used tag, citation colour/color and neutral; no record prescribes the superseded visual values. ADR-0006 describes citation retrieval and matching, not the mark palette. Historical test comments describe the old appearance being superseded by owner-adopted rule 4; they do not supply a conflicting owner decision.",
      "assertionChanges": [
        {
          "oldAssertion": "toneAccent(info) must equal var(--accent-tint).",
          "newAssertion": "It must equal var(--text-secondary). Success, warning, error and neutral exact token expectations, the token-reference format loop and status-to-tone cases remain unchanged.",
          "reason": "Rule 4 explicitly says information is grey. This directly supplies the secondary-grey oracle for info, rather than copying the implementation mapping. It does not change the separate success, warning or error meanings."
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
          "scenario": "R4",
          "scenarioName": "rule 4",
          "quote": "Teal means \"on or working now\" (switches, progress, the lit steps of AI Brain), plus the one filled main action on a screen. Tabs, the active area, selected rows and text actions are neutral. A text action is the normal text colour in medium weight; information is grey.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Distinctions that carry meaning stay distinct by neutral means: grounded citation = primary text at weight 500, weak = secondary, ungrounded unchanged, source-only = primary at weight 600, compared by colour and weight; originator user = primary text, system = secondary, agent unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "The \"used\" tag = primary text at weight 500 on a neutral ground, at least 4.5:1.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. Searched docs/decisions for teal, accent-tint, text-tint, text-primary, text-secondary, font-weight, focus ring, originator, used tag, citation colour/color and neutral; no record prescribes the superseded visual values. ADR-0006 describes citation retrieval and matching, not the mark palette. Historical test comments describe the old appearance being superseded by owner-adopted rule 4; they do not supply a conflicting owner decision.",
      "assertionChanges": [
        {
          "oldAssertion": "The contrast table measures used-row primary title/path/locator over surface-secondary plus accent-tint-08, and the used tag text-tint over that row plus accent-tint-16.",
          "newAssertion": "It measures the same row text over surface-secondary plus surface-subtle, and the used tag text-primary over that row plus surface-active. The existing compositing helper and WCAG_AA threshold of 4.5:1 remain.",
          "reason": "Rule 4 requires neutral selected rows; the delegated used-tag choice assigns primary/500 on neutral ground with at least 4.5:1. The table substitutes the specified neutral paint stack while preserving the independent contrast floor and all named row/tag observations. It is a changed surface oracle, not an oracle-preserving adaptation."
        },
        {
          "oldAssertion": "The contrast table expects the tool resource link to use var(--accent) and the nested markdown link to use var(--text-tint).",
          "newAssertion": "Both link entries use var(--text-primary), on their original surface-secondary and surface-subtle backgrounds respectively. The contrast threshold remains.",
          "reason": "Rule 4 makes text actions normal text colour in medium weight. These table entries measure the primary ink required by that role on the existing backgrounds; the AA calculation still rejects insufficient contrast."
        },
        {
          "oldAssertion": "The hover-card document-name contrast entry uses var(--text-tint) over var(--surface-2).",
          "newAssertion": "It uses var(--text-secondary) over the same surface. The other hover-card text pairs remain.",
          "reason": "The document name is informational in the hover card, so rule 4 supplies grey. Secondary is the neutral information role; its contrast is still checked independently."
        },
        {
          "oldAssertion": "SELECTED_MARK uses var(--md-cite-selected-bg, var(--accent-tint)) for weak/ungrounded resting-and-selected contrast checks in both themes.",
          "newAssertion": "Its fallback becomes var(--surface-active). Both themes, weak/ungrounded tiers, the 4.5:1 floor, weak-below-normal ordering and the existing wash/token assertions remain.",
          "reason": "Rule 4 makes selection neutral. The calculation now composites the prescribed active surface while continuing to require legible, distinct low-tier marks. The neutral fallback comes from that design decision; neither measured output nor a lowered contrast threshold supplies it."
        }
      ],
      "checkLimits": "This diff changes colour/background inputs to existing contrast checks; it does not assert the used-tag or link weight 500. The retained NORMAL comparison still uses var(--text-tint), so it is not a new check against the delegated neutral grounded primary/500 presentation. The table is a token-compositing check, not live-browser evidence."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1d79d-3fd3-73f1-bcff-ea37fdc8cd71",
  "verdict": "accept",
  "digest": "sha256:3031b66c18785bc0c87e72e1e7dbbb9a05d687812f12dc6fba14817435ad217e"
}
```
