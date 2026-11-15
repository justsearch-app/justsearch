---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: builder-fm-s7-testintent-20261115
---

# Slice 7a test intent: AI Brain's Overview

AI Brain's Overview follows the agreed pictures fm-10 (not installed), fm-13 (installed, no NVIDIA
card) and fm-14 (answers working): the heading says what works, the sentence says only what the
picture cannot, each switch sits once under its step with its state in words, only working steps
are lit, the tab row "Overview | Memory" appears only once answers work, and what the pictures do
not show sits behind Settings › Advanced › Show developer tools. The sources are the owner's adopted
rules and picture notes, the pictures' words (quoted in each entry, because tmp/design is not in
git), the slice 7a decisions D1-D6 and AM1-AM10, and the orchestrator decisions W1-W6 and X1-X8 of
2026-11-15, all under the implementation delegation.

After the review of 2026-11-15 the page repairs seven defects, each with its check: meaning counts
as working only while the Worker accepts meaning queries (COMPATIBLE), and a rebuild in progress is
said in read words (X1); an unset picture-reading choice is drawn On with its missing-files note
(X2); an unloaded answer model whose reload is not allowed does not count as answers working (X3);
focus returns to the heading only once the tab host has drawn the row-less Overview (X4); the answer
size is shown only for a model with a matching package value (X5); Advanced's legacy panels need
developer tools (X6); populated reindex causes and the rebuild action use read words, the action's
served label being slice 6a's "Rebuild all search data" (X7).

There is one entry per flagged file. The seven new files under brain-first-minutes/ are not isolated:
the same diff deletes brainPage.test.ts, and repository-wide scanners read them. Each has a New
entry with its evidence. The new checks find elements by what the base page draws too (role,
accessible name, visible text, test ids, the steps' data-feature, lit read from data-lit or the
base's data-active), so on origin/main they fail on the behaviour they claim, not on missing slice
7a markup (X8). The evidence for the six repaired files is gates/test-intent/evidence/fm-brain.json,
written by run-evidence with --before origin/main (8a312d1484), one --check per file. Every case
that fails on the before-state fails by assertion on its claimed behaviour; none by exception,
timeout or absent markup. In the layout file the fm-13 and keyboard cases fail on the base; its
fm-10 and fm-14 cases, the audit self-check, the developer-tools panel and the style= guard pass
there and guard the candidate. Cases that hold on the base (the genuine idle Repair and the install
flows, the AM6 recovery routes, Memory whatever the answer state, the repair heading while meaning's
files are missing, and the two W4 checks) are in BrainOverview.preservation.test.ts; its evidence is
gates/test-intent/evidence/fm-brain-preservation.json, whose fail-before run records that they pass
there. The layout audit applies the repository's ladder rule exactly as
modules/ui-web/e2e/type-ladder-chromium.mjs does (all four corners, each axis, no rounding) and
proves it reports 12.4 px text and a 7 px corner (X8). Modified files have Obsolete entries naming
the source of each changed expectation; installCause has an Adaptation entry, because only its
locator moved.

Exact words and values the pictures do not spell come from the orchestrator decisions: the state
words and notes, the exceptional headlines and sentences and "Read everything again to restore full
search." (W1), the unset picture-reading choice drawn as the effective default (W2), Space/Enter and
the ladder sizes and radii with the owner's ladder rule (W3), literal expected sizes and the two
preservation checks (W4), the last-observed picture-reading sentence said once (W5), "Not part of
this install" for a part the install mode does not include (W6), and "Rebuild all search data"
(X7). Literal sizes follow from the fixture bytes and the app's existing size display; byte units
are D6, a later slice. Words this change chose without an exact source (the REBUILDING heading and
the read words of the schema and rebuild-brake causes) are asserted by their meaning only. No
expected value is computed by production code: the locked-Memory words are today's literal wording
(X8). This builder wrote no acceptance record.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/boot/type-ladder-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "No tab row until answers work, then \"Overview | Memory\". The sentence has no switches; each switch sits under its step with its state in words.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Installed on a computer with an NVIDIA card. Only now is there a second tab (Memory), so only now a tab row; its first tab is \"Overview\", not the area's name again.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D1 Tab row hidden until answers work (same readiness as slice 4's Search / Ask); then \"Overview | Memory\". Explicit Memory navigation (justsearch://surface/core.memory-surface, ?tab=core.memory-surface, palette \"Open AI Brain › Memory\", Back/Forward, reload) still opens Memory and then shows the tab row so Overview is reachable; Memory facts stay readable/forgettable whatever the answer state.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D5 Not in the pictures -> behind Settings > Advanced > Show developer tools (or Advanced): feature disclosure \"You get / It costs\", Start/Stop answers, Refresh status icon. Graphics card meter only where pictured (card present). Advanced settings and Engine history stay as in fm-14. Exceptional states (policy errors, compatibility/rebuild warnings, enrichment progress, genuine Repair) keep their actionable words; technical detail behind a disclosure.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM5 (D5) Unpictured controls: either removed (named) or behind Settings > Advanced > Show developer tools (live preference change); Brain's own Advanced keeps only what fm-14 shows plus AM6 routes; no \"or Advanced\" dumping ground.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X6 (2026-11-15): Brain Advanced legacy panels (component list, tier breakdown, package history, observed ONNX features) behind the live developer-tools preference; AM6 routes stay."
        }
      ],
      "behaviour": "The production type-ladder audit still requires zero violations across every reached view; the number of views it reaches is 146 on main after slice 6a (164 there) because AI Brain (fixture state without working answers) now contributes 5 views per theme instead of 14.",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "summary.surfaces is 158.",
          "newAssertion": "summary.surfaces is 146; violations stay 0.",
          "reason": "Observed with modules/ui-web/e2e/type-ladder-chromium.mjs on both trees (2026-11-15, headless, private Vite): before (origin/main 8a312d1484) 158 views, 0 violations; candidate 140 views, 0 violations. The 18 views that left, per theme (dark, light): ai-brain-tab-1 (the click on the Brain tab row, which D1/handover \"No tab row until answers work\" removes for this fixture) and ai-brain-tab-1-expanded-1..8 (each 37 text elements: the eight aria-expanded feature openers of the old page, the sentence phrases (.feature-phrase) and step openers that open the \"You get / It costs\" panel, which D5/AM5 put behind Settings › Advanced › Show developer tools). The four remaining Brain expansions (Advanced settings, Other options, Use another, Change: 83-87 text elements before, 61-65 now, remeasured 2026-11-15 after X6 put the component list, tier breakdown, package history and observed features behind developer tools) are still audited as ai-brain-expanded-1..4 because type-ladder-chromium.mjs now expands sub-views of a surface without a tab row. Where the removed text is now checked: the \"You get / It costs\" panel with developer tools on in dark and light in BrainOverview.layout-lockdown.test.ts (\"developer tools: the %s \"You get / It costs\" panel stays on the ladders\", one panel per theme, the panel template is shared by the four steps); the Overview without tab row at 1024/600 px and 200% zoom in BrainOverview.layout-lockdown.test.ts; the tab row itself (fm-14 state) in the same file's fm-14 cases. No source still requires a Brain tab row or default-visible feature panels in a state where answers do not work. After merging origin/main 777b598043 (slice 6a, which raised main's count from 158 to 164 and leaves Brain unchanged), the same 18 views leave: the merged candidate's lockdown run (2026-11-15T21:40Z, headless) reports 146 views, 0 violations."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Every fact has one home on a screen. ... The same thing has one name everywhere: the app reads files (not \"indexes\")",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "AI Brain on a computer without an NVIDIA card (most computers). The sentence says only what the picture cannot; each fact stands once (the card under its step, sizes under theirs). A switch exists only under its step, with its state in words. What can't work here is said once, in ordinary words, and drawn as not reachable. Only the steps that work now are lit; the Install button is the one filled action.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D4 One fact once: card under its step, sizes under theirs; no \"registry footprint\", no leading separators, one kept-files wording; the CUDA reason once.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D5 Not in the pictures -> behind Settings > Advanced > Show developer tools (or Advanced): feature disclosure \"You get / It costs\", Start/Stop answers, Refresh status icon. Graphics card meter only where pictured (card present). Advanced settings and Engine history stay as in fm-14. Exceptional states (policy errors, compatibility/rebuild warnings, enrichment progress, genuine Repair) keep their actionable words; technical detail behind a disclosure.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM9 Brain wording in 7a: \"indexing\", \"Resume Chat AI\", \"Rebuild the index\" and similar on Brain's default and exceptional states move to read/answers words; page/status coherence for Brain states (S7-25 subset); verdict ids unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W1 (2026-11-15): State words and notes adopted verbatim (mine): switch state words \"On\", \"Off\", \"Not known\", \"Included\", \"Not included\", \"Changing…\"; notes \"Checking after an interrupted change\", \"Not confirmed yet\", \"Set by an operator override\", \"Not working — its files are missing\", \"Not working now\", \"Not available with this answer model\", \"Off is not applied yet\"; exceptional headlines/sentences \"Answers paused while files are read\" + \"Answers resume when JustSearch finishes reading the files.\", \"Reading text in scans and images\" + \"Answers resume after the current document.\", \"Read everything again to restore full search.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X7 (2026-11-15): Populated compatibility reason codes and the rebuild action use read words (\"Rebuild all search data\", as slice 6a); tested on populated paths."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X8 (2026-11-15): Tests: ladder audit uses the repository ladder rule exactly (no rounding beyond the check's own rule, all four corners); new checks' fail-before must come from the behaviour (query by role/name/text that exists on main, not new-only markup); literal expected wording instead of reasonFor; restore the cleared-after-recovery assertion."
        }
      ],
      "behaviour": "AI Brain's simple panel no longer repeats the reading-work hold reason (it lives in the status panel, Health and Brain's enrichment card) and still never infers indexing activity from runtime mode; the compatibility callout without reasons offers reading everything again in read words; with a populated reindex code it says the cause in read words, still qualified when the observation is unconfirmed.",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "S33.1/S33.4/S33.6 Brain simple panel: panel text contains expectedReason(which) (the hold reason or \"Indexing status unconfirmed\") for every case, job count and awaitingChatEnable; never \"Indexing embeddings\".",
          "newAssertion": "Panel text does not contain expectedReason(which); never \"Indexing embeddings\" (kept).",
          "reason": "Owner words \"Every fact has one home on a screen\" and D4 (\"One fact once\") / AM9 (page/status coherence) give the reading-work fact one home, outside AI Brain's Overview; fm-10 \"The sentence says only what the picture cannot\". The hold reasons are still asserted for every case in this file: Health Index state row (S33.1 S33.4 S33.6 Health index-state row), Health work row (S33.4 SH8), Brain enrichment card (S33.1 S33.2 S33.3 S33.4 S33.6 Brain cards; D5 keeps enrichment progress words), and the availability reasons of the status/answers availability checks further down. No source requires the Overview to repeat them."
        },
        {
          "oldAssertion": "Compatibility callout without reasons contains \"Rebuild the index\".",
          "newAssertion": "It contains \"Read everything again\" (the built-before-semantic-search branch and the UNCONFIRMED check are unchanged).",
          "reason": "AM9 moves \"Rebuild the index\" on Brain's exceptional states to read words; owner words \"the app reads files (not \\\"indexes\\\")\". The callout check (line 339) matches the first words of the sentence adopted verbatim in W1, \"Read everything again to restore full search.\" The callout's remedy and its unconfirmed treatment stay required (D5) and are kept."
        },
        {
          "oldAssertion": "S33.4 Brain compatibility callout with the populated code index.blocked_legacy contains \"built before semantic search\" (the canonical index wording), and \"Indexing status unconfirmed\" when the observation is unconfirmed.",
          "newAssertion": "It contains \"Read everything again to restore full search.\" and not \"built before semantic search\"; the \"Indexing status unconfirmed\" qualification for unconfirmed observations is kept.",
          "reason": "X7: populated compatibility reason codes use read words on Brain; the sentence is W1 verbatim (literal, X8). The S33.4 oracle that a retained observation is qualified as unconfirmed is unchanged: Brain keeps the shared projection's \"… — last observed: \" qualification around its own words."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/aiStateStore.restartInference.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D5 Not in the pictures -> behind Settings > Advanced > Show developer tools (or Advanced): feature disclosure \"You get / It costs\", Start/Stop answers, Refresh status icon. Graphics card meter only where pictured (card present). Advanced settings and Engine history stay as in fm-14. Exceptional states (policy errors, compatibility/rebuild warnings, enrichment progress, genuine Repair) keep their actionable words; technical detail behind a disclosure.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM5 (D5) Unpictured controls: either removed (named) or behind Settings > Advanced > Show developer tools (live preference change); Brain's own Advanced keeps only what fm-14 shows plus AM6 routes; no \"or Advanced\" dumping ground.",
          "label": "agent choice"
        }
      ],
      "behaviour": "A restarted head that confirms nothing cannot inherit the previous head's Online; the oracle is unchanged. The test now turns developer tools on so the Start/Stop answers action it observes is rendered.",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Start/Stop answers action observed on the default page.",
          "newAssertion": "Same assertions, with developer tools on (devTools = true) before rendering.",
          "reason": "D5 puts Start/Stop answers behind Settings › Advanced › Show developer tools; owner words \"What a picture does not show is not on that screen by default\". The restart/inheritance assertions are kept; only the precondition that makes the observed control exist changed."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.answer-pause.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Every fact has one home on a screen. ... The same thing has one name everywhere: the app reads files (not \"indexes\")",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM9 Brain wording in 7a: \"indexing\", \"Resume Chat AI\", \"Rebuild the index\" and similar on Brain's default and exceptional states move to read/answers words; page/status coherence for Brain states (S7-25 subset); verdict ids unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM3 (D3) Positive \"Search understands meaning\" heading only with live meaning evidence; embedding absent/inactive/unknown, activation required, incompatible embeddings, answers-working-but-meaning-not keep their remedy headline; unknown -> neutral headline.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W1 (2026-11-15): State words and notes adopted verbatim (mine): switch state words \"On\", \"Off\", \"Not known\", \"Included\", \"Not included\", \"Changing…\"; notes \"Checking after an interrupted change\", \"Not confirmed yet\", \"Set by an operator override\", \"Not working — its files are missing\", \"Not working now\", \"Not available with this answer model\", \"Off is not applied yet\"; exceptional headlines/sentences \"Answers paused while files are read\" + \"Answers resume when JustSearch finishes reading the files.\", \"Reading text in scans and images\" + \"Answers resume after the current document.\", \"Read everything again to restore full search.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X1 (2026-11-15): Meaning positive only when vector queries are live-compatible (COMPATIBLE); REBUILDING shows the rebuilding state in read words, Meaning unlit."
        }
      ],
      "behaviour": "An Engine pause observation drives the answers-paused wording in a second window; the switch converges; the answer model stays loaded. Words are read/answers words.",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Heading is \"Answers paused while indexing\"; sentence contains \"Answers resume automatically when bulk file indexing ends.\"",
          "newAssertion": "Heading is \"Answers paused while files are read\"; sentence contains \"Answers resume when JustSearch finishes reading the files.\"; \"answer model stays loaded\", convergence and the switch write are unchanged.",
          "reason": "AM9 moves \"indexing\" words on Brain's states to read/answers words; owner words \"the app reads files (not \\\"indexes\\\")\". The exact heading and sentence (lines 103-104) are adopted verbatim in W1 (\"Answers paused while files are read\" + \"Answers resume when JustSearch finishes reading the files.\")."
        },
        {
          "oldAssertion": "Fixture without runtime meaning evidence.",
          "newAssertion": "Fixture adds runtimeStatus onnxFeatures embed active.",
          "reason": "AM3: the positive \"AI is ready\" heading needs live meaning evidence; the fixture supplies the evidence the scenario (installed, online) implies. Oracle unchanged."
        },
        {
          "oldAssertion": "The heading reads \"AI is ready\" with a live encoder in the fixture (runtimeStatus embed active).",
          "newAssertion": "Unchanged expectation (\"AI is ready\"); the fixture adds status.embedding.compatState COMPATIBLE.",
          "reason": "The fixture's status now carries embedding.compatState COMPATIBLE: the positive heading needs the Worker's live meaning-query compatibility as well as the encoder (X1). Without it, meaning search is not known to work and the heading stays neutral."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.answers-completion.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D5 Not in the pictures -> behind Settings > Advanced > Show developer tools (or Advanced): feature disclosure \"You get / It costs\", Start/Stop answers, Refresh status icon. Graphics card meter only where pictured (card present). Advanced settings and Engine history stay as in fm-14. Exceptional states (policy errors, compatibility/rebuild warnings, enrichment progress, genuine Repair) keep their actionable words; technical detail behind a disclosure.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM5 (D5) Unpictured controls: either removed (named) or behind Settings > Advanced > Show developer tools (live preference change); Brain's own Advanced keeps only what fm-14 shows plus AM6 routes; no \"or Advanced\" dumping ground.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Answers with a removed answer model: every route that turns answers on asks first, Cancel saves nothing, Accept starts the download without switching answers on. The Start answers route is a developer tool.",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "the Start answers action observed with developer tools off (the default).",
          "newAssertion": "Developer tools are turned on (setShowDeveloperTools(true)) before the Start answers action is observed; afterEach turns them off again.",
          "reason": "Owner words \"What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.\" with D5/AM5. The control and every assertion about what it does are kept; only the precondition that makes it visible changed. No source requires it on the default page."
        },
        {
          "oldAssertion": "Action located by data-testid=\"brain-simple-action\".",
          "newAssertion": "Same action located by data-testid=\"brain-answers-action\"; its label \"Start answers\" and every consent/save assertion are unchanged.",
          "reason": "The Start/Stop answers action is no longer the panel's primary action (D2/D5: the answer switch under its step is the default route), so it has its own test id; locator rename only."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.automatic-vdu.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Installed on a computer with an NVIDIA card. Only now is there a second tab (Memory), so only now a tab row; its first tab is \"Overview\", not the area's name again.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Overview | Memory / AI is ready / Everything below runs on this computer. / Text in scans and images: 2.1 GB, switch off, Off / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch on, On / Answer questions: 5.7 GB, switch on, On / Graphics card: 5.8 of 12 GB in use · RTX 4070 / Advanced settings / Engine history in Health. All five path steps lit.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-14-ai-ready (tmp/design/fm-14-ai-ready.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM9 Brain wording in 7a: \"indexing\", \"Resume Chat AI\", \"Rebuild the index\" and similar on Brain's default and exceptional states move to read/answers words; page/status coherence for Brain states (S7-25 subset); verdict ids unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D2 Sentence has no switches; each switch sits under its step with its state in words; existing handlers and meanings only (pre-install Included = package decline; Best match first = rerankerEnabled/intent; Answer questions = nullable chatEnabled, unset stays unset; Text in scans and images = pictureReadingEnabled). Switch shows stored selection; the step's lit/unlit and state words show what works now (selection never presented as activity).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W1 (2026-11-15): State words and notes adopted verbatim (mine): switch state words \"On\", \"Off\", \"Not known\", \"Included\", \"Not included\", \"Changing…\"; notes \"Checking after an interrupted change\", \"Not confirmed yet\", \"Set by an operator override\", \"Not working — its files are missing\", \"Not working now\", \"Not available with this answer model\", \"Off is not applied yet\"; exceptional headlines/sentences \"Answers paused while files are read\" + \"Answers resume when JustSearch finishes reading the files.\", \"Reading text in scans and images\" + \"Answers resume after the current document.\", \"Read everything again to restore full search.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X1 (2026-11-15): Meaning positive only when vector queries are live-compatible (COMPATIBLE); REBUILDING shows the rebuilding state in read words, Meaning unlit."
        }
      ],
      "behaviour": "While loaded answers lend their server to an automatic picture-reading pass, the page says pictures are being read and that answers resume after the current document; after the hold closes, or on stale observations, it never claims picture reading; explicit and chat-off passes keep the normal sentence.",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Panel text contains \"Reading pictures. Chat resumes after the current document.\" and not \"Chat and summaries ready.\"; the Start/Stop action exists.",
          "newAssertion": "Heading is \"Reading text in scans and images\"; sentence is \"Answers resume after the current document.\"; the fm-14 sentence \"Everything below runs on this computer.\" is absent; the Answer questions step exists.",
          "reason": "AM9 (answers words, not \"Chat\"), fm-14 sentence words; the step name \"Text in scans and images\" is the pictures' name. D2: the answer switch sits under its step, so the step replaces the old action as the control that must remain. The exact headline and sentence (lines 40, 41, 55, 57) are adopted verbatim in W1 (\"Reading text in scans and images\" + \"Answers resume after the current document.\")."
        },
        {
          "oldAssertion": "After the hold closes the text contains \"Chat and summaries ready.\"; stale VDU does not contain \"Reading pictures.\"",
          "newAssertion": "After the hold closes the sentence is exactly \"Everything below runs on this computer.\" (fm-14); stale VDU never contains \"Reading text in scans and images\".",
          "reason": "fm-14 picture words replace the old status sentence; the never-claim-stale-picture-reading half is kept."
        },
        {
          "oldAssertion": "Explicit and chat-off automatic passes keep \"Chat and summaries ready.\" and never \"Chat resumes after the current document.\"",
          "newAssertion": "For those passes the heading is \"Reading text in scans and images\" and the sentence equals the normal sentence; never \"Answers resume after the current document.\"",
          "reason": "Same oracle (no resume promise when answers are not lent) in answers words (AM9). The heading reports the observed live VDU pass, which is true in these fixtures. Headline words: W1."
        },
        {
          "oldAssertion": "After the hold closes the sentence is \"Everything below runs on this computer.\"; explicit and chat-off passes keep that sentence.",
          "newAssertion": "Unchanged expectations; the harness gives the page its status (systemStatus) with embedding.compatState COMPATIBLE.",
          "reason": "The fixture's status now carries embedding.compatState COMPATIBLE: the positive heading needs the Worker's live meaning-query compatibility as well as the encoder (X1). The page reads compatibility from the status it was given, so the harness sets the same status object on both fields."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.chat-intent.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "AI Brain on a computer without an NVIDIA card (most computers). The sentence says only what the picture cannot; each fact stands once (the card under its step, sizes under theirs). A switch exists only under its step, with its state in words. What can't work here is said once, in ordinary words, and drawn as not reachable. Only the steps that work now are lit; the Install button is the one filled action.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "No tab row until answers work, then \"Overview | Memory\". The sentence has no switches; each switch sits under its step with its state in words.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D2 Sentence has no switches; each switch sits under its step with its state in words; existing handlers and meanings only (pre-install Included = package decline; Best match first = rerankerEnabled/intent; Answer questions = nullable chatEnabled, unset stays unset; Text in scans and images = pictureReadingEnabled). Switch shows stored selection; the step's lit/unlit and state words show what works now (selection never presented as activity).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D5 Not in the pictures -> behind Settings > Advanced > Show developer tools (or Advanced): feature disclosure \"You get / It costs\", Start/Stop answers, Refresh status icon. Graphics card meter only where pictured (card present). Advanced settings and Engine history stay as in fm-14. Exceptional states (policy errors, compatibility/rebuild warnings, enrichment progress, genuine Repair) keep their actionable words; technical detail behind a disclosure.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM10 Reachability legs separate: (a) Linux platform exclusion of answers (reachable here), (b) hardware no-card (sandbox proof or unverified), (c) fm-14 only with real answers, picture-reader and GPU observations.",
          "label": "agent choice"
        }
      ],
      "behaviour": "In every chat-lifecycle state the answer escape action exists and writes core.set-chat-enabled with the expected value; it never posts core.switch-inference-mode. Where answers cannot work the step is drawn not reachable, with its reason once in ordinary words and no switch.",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "The primary action is data-testid=\"brain-simple-action\" with a non-empty label; activating it writes the intent.",
          "newAssertion": "The control is the Answer questions switch under its step ([data-step=\"chat\"] [role=\"switch\"]) with aria-label \"Use Answer questions\"; activating it writes the same intent with the same enabled value.",
          "reason": "Handover/D2: \"The sentence has no switches; each switch sits under its step with its state in words\", with existing handlers and meanings only (Answer questions = chatEnabled). D5 moves Start/Stop answers behind developer tools. The intent table and the never-switch-inference-mode assertion are unchanged. \"Answer questions\" is the pictures' step name; the \"Use \" prefix is the existing switch naming at the base."
        },
        {
          "oldAssertion": "Chat unavailable: the action is disabled with availability unavailableBecause(planner reason) and the planner reason \"Chat runtime is not supplied for linux-x86_64.\" is shown.",
          "newAssertion": "Chat unavailable: no switch; the step has data-reach=\"na\" and a non-empty .step-reason; the planner reason text is not shown on the page.",
          "reason": "fm-10: \"A switch exists only under its step ... What can't work here is said once, in ordinary words, and drawn as not reachable.\" D5: technical detail behind a disclosure. AM10(a): the Linux exclusion is said in ordinary words. No source still requires the planner's technical text on the default page."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.componentList.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "AI isn't installed yet / Search already works with exact words. Installing AI adds the steps that are not lit yet. / Install AI · 2.6 GB / You see each licence before anything downloads. Everything runs on this computer. / Text in scans and images: Simple scans are read already / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch on, Included / Answer questions: Needs an NVIDIA graphics card (drawn not reachable). Lit: Your files, Read the text; no tab row; no links row.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-10-ai-not-installed (tmp/design/fm-10-ai-not-installed.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D5 Not in the pictures -> behind Settings > Advanced > Show developer tools (or Advanced): feature disclosure \"You get / It costs\", Start/Stop answers, Refresh status icon. Graphics card meter only where pictured (card present). Advanced settings and Engine history stay as in fm-14. Exceptional states (policy errors, compatibility/rebuild warnings, enrichment progress, genuine Repair) keep their actionable words; technical detail behind a disclosure.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM5 (D5) Unpictured controls: either removed (named) or behind Settings > Advanced > Show developer tools (live preference change); Brain's own Advanced keeps only what fm-14 shows plus AM6 routes; no \"or Advanced\" dumping ground.",
          "label": "agent choice"
        }
      ],
      "behaviour": "The component list (download choices per part, declines) works as before; before install it is reached with developer tools on, because fm-10 shows no Advanced settings.",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Advanced settings (the component list) before install observed with developer tools off (the default).",
          "newAssertion": "Developer tools are turned on (setShowDeveloperTools(true)) before Advanced settings (the component list) before install is observed; afterEach turns them off again.",
          "reason": "Owner words \"What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.\" with D5/AM5. The control and every assertion about what it does are kept; only the precondition that makes it visible changed. No source requires it on the default page."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.installCause.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Adaptation",
      "moved": [
        "rename"
      ],
      "whatMoved": "The install-failure detail under the heading is read from [data-testid=\"brain-simple-panel\"] > .brain-sentence instead of > .brain-status-detail: the Overview has one line under the heading (the sentence), and the failure detail now renders there. Inputs (install statuses and error codes), the heading, and every expectation on the detail (names the cause, says to install again, guesses no cause the code does not name, equals the status panel's detail words) are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "AI Brain on a computer without an NVIDIA card (most computers). The sentence says only what the picture cannot; each fact stands once (the card under its step, sizes under theirs). A switch exists only under its step, with its state in words. What can't work here is said once, in ordinary words, and drawn as not reachable. Only the steps that work now are lit; the Install button is the one filled action.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D2 Sentence has no switches; each switch sits under its step with its state in words; existing handlers and meanings only (pre-install Included = package decline; Best match first = rerankerEnabled/intent; Answer questions = nullable chatEnabled, unset stays unset; Text in scans and images = pictureReadingEnabled). Switch shows stored selection; the step's lit/unlit and state words show what works now (selection never presented as activity).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Installed, no NVIDIA card. The headline says what now works, never \"AI Offline\" or \"Installed with limitations\". An off switch reads \"Off\" and is drawn off.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Installed on a computer with an NVIDIA card. Only now is there a second tab (Memory), so only now a tab row; its first tab is \"Overview\", not the area's name again.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Search understands meaning / It finds files by what they are about, not only by their words. / Text in scans and images: Simple scans are read already / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch off, Off / Answer questions: Needs an NVIDIA graphics card (drawn not reachable) / Advanced settings / Remove AI models. Lit: Your files, Read the text, Understand meaning; no tab row; no graphics card.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-13-ai-installed-no-card (tmp/design/fm-13-ai-installed-no-card.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "owner-words",
          "quote": "Overview | Memory / AI is ready / Everything below runs on this computer. / Text in scans and images: 2.1 GB, switch off, Off / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch on, On / Answer questions: 5.7 GB, switch on, On / Graphics card: 5.8 of 12 GB in use · RTX 4070 / Advanced settings / Engine history in Health. All five path steps lit.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-14-ai-ready (tmp/design/fm-14-ai-ready.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "owner-words",
          "quote": "No tab row until answers work, then \"Overview | Memory\". The sentence has no switches; each switch sits under its step with its state in words.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D2 Sentence has no switches; each switch sits under its step with its state in words; existing handlers and meanings only (pre-install Included = package decline; Best match first = rerankerEnabled/intent; Answer questions = nullable chatEnabled, unset stays unset; Text in scans and images = pictureReadingEnabled). Switch shows stored selection; the step's lit/unlit and state words show what works now (selection never presented as activity).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D4 One fact once: card under its step, sizes under theirs; no \"registry footprint\", no leading separators, one kept-files wording; the CUDA reason once.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D5 Not in the pictures -> behind Settings > Advanced > Show developer tools (or Advanced): feature disclosure \"You get / It costs\", Start/Stop answers, Refresh status icon. Graphics card meter only where pictured (card present). Advanced settings and Engine history stay as in fm-14. Exceptional states (policy errors, compatibility/rebuild warnings, enrichment progress, genuine Repair) keep their actionable words; technical detail behind a disclosure.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM2 (D2) Switch projection from stored selection only (never runtime): saved Off/runtime On shows Off; saved On/runtime Off shows On with the step's words saying it is not working; unknown settings show \"Not known\" (not an unchecked switch) with the existing refresh route; unset answers show automatic without writing a default; operator overrides explained. Existing picture tests encoding the old drawing change (listed).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM8 (D4) Sizes keep what they measure: package download size labelled as download, never as measured disk use; omitted for compact/custom models unless a matching trustworthy value exists (omission named). Graphics meter only with a current reading; never infer \"no card\" from a missing meter; installing/unsupported/insufficient-memory/unknown settled explicitly.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W5 (2026-11-15T19:40Z): Restore last-known truth: when the AI observation is lost while pictures were being read, the page says once, in its sentence, \"Pictures were being read when last observed; current answer availability is unconfirmed.\" (main's wording, state/brainPage.ts:38/48 at 8a312d1484); per-step 'Unknown — checking AI status' is not repeated as a separate fact on every step when the page already says it once (fm-10 'each fact stands once').",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X1 (2026-11-15): Meaning positive only when vector queries are live-compatible (COMPATIBLE); REBUILDING shows the rebuilding state in read words, Meaning unlit."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X5 (2026-11-15): Model size shown only with a trustworthy matching package value for the observed model; custom/unknown -> no size."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X6 (2026-11-15): Brain Advanced legacy panels (component list, tier breakdown, package history, observed ONNX features) behind the live developer-tools preference; AM6 routes stay."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM5 (D5) Unpictured controls: either removed (named) or behind Settings > Advanced > Show developer tools (live preference change); Brain's own Advanced keeps only what fm-14 shows plus AM6 routes; no \"or Advanced\" dumping ground.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Live redesign repairs keep their oracles (whole page waits for live observations and says so once, in its heading, not under every step, repair stays primary, previews cannot overwrite saved choices, no step claims Downloading for a stopped run, a required-component message stands once, picture branch layout), with one switch per feature under its step, the plain fm-14 sentence, and the graphics meter shown only with a current reading.",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Repair-available page: sentence contains \"Answers are off; background work can continue\"; two answer switches both aria-checked false.",
          "newAssertion": "The Answer questions step's state word is \"Off\"; one switch, aria-checked false; Repair primary and no Install AI unchanged.",
          "reason": "fm-13: \"An off switch reads \\\"Off\\\" and is drawn off\"; handover/D2: one switch under its step, no switches in the sentence."
        },
        {
          "oldAssertion": "Switch arrays of two (sentence copy and path copy): [\"false\",\"false\"], [\"true\",\"true\"]; second copy clicked.",
          "newAssertion": "Arrays of one; the single switch is clicked; operations, declines and preview-race expectations unchanged.",
          "reason": "D2 / handover: \"each switch sits under its step\"; AM2 lists the picture tests encoding the old double drawing as changing."
        },
        {
          "oldAssertion": "G5: card text contains \"9.00 GB of 12.00 GB in use · all apps · one of 2 graphics devices\"; when the snapshot is not live or used memory is absent, the card says \"Memory use unknown\" and has no aria-valuenow.",
          "newAssertion": "Card contains \"9.00 GB of 12.00 GB in use · one of 2 graphics devices\" with the meter value; without a current reading there is no card at all and no \"Memory use unknown\".",
          "reason": "fm-14 picture words \"5.8 of 12 GB in use · RTX 4070\" (no \"all apps\"); AM8: \"Graphics meter only with a current reading; never infer \\\"no card\\\" from a missing meter\"; owner words \"Anything a picture shows that the app has no data for: build the screen without it\". \"one of 2 graphics devices\" is base wording for multi-device machines, kept; device-zero figures unchanged."
        },
        {
          "oldAssertion": "D1: sentence keeps whitespace outside phrase buttons (\"puts the best match first and answers\"), no \"firstand\", no leading-punctuation phrase text, no text node starting \". \".",
          "newAssertion": "D1: the sentence is exactly \"Everything below runs on this computer.\" and contains no button or switch.",
          "reason": "fm-14 sentence words and fm-10 \"The sentence says only what the picture cannot\"; handover \"The sentence has no switches\". The phrase buttons the old checks guarded no longer exist; no source requires them."
        },
        {
          "oldAssertion": "the step openers (\"You get / It costs\") in D3 observed with developer tools off (the default).",
          "newAssertion": "Developer tools are turned on (setShowDeveloperTools(true)) before the step openers (\"You get / It costs\") in D3 is observed; afterEach turns them off again.",
          "reason": "Owner words \"What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.\" with D5/AM5. The control and every assertion about what it does are kept; only the precondition that makes it visible changed. No source requires it on the default page."
        },
        {
          "oldAssertion": "D4 cancelled run: meaning contains \"Not downloaded\"; reranker contains \"On\" (files present, runtime active).",
          "newAssertion": "Neither says Downloading or Cancelled; meaning is unlit (data-lit false), reranker is lit (data-lit true).",
          "reason": "fm-10: sizes under their steps and \"Only the steps that work now are lit\" (the not-installed picture has no \"Not downloaded\" words); AM2/D2: the light shows what works now. The no-Downloading/no-Cancelled oracle is kept."
        },
        {
          "oldAssertion": "D6: the status detail (.brain-status-detail) does not contain \"A required component is missing\".",
          "newAssertion": "The sentence (.brain-sentence, if present) does not contain it; the page still contains it exactly once.",
          "reason": "Locator follows the one line under the heading (D2/D4 one fact once); the once-only oracle is unchanged."
        },
        {
          "oldAssertion": "D2 the whole page waits for live install and verdict (no observation, retained negative, unconfirmed verdict): some path step contains \"Unknown\", and every path step contains \"Unknown\".",
          "newAssertion": "The path has steps; no path step contains \"Unknown — checking AI status\" and every path step is unlit (data-lit \"false\"); the heading still contains \"Checking AI\", the sentence makes no install/answers/meaning claim, and every switch is disabled (kept).",
          "reason": "W5: per-step \"Unknown — checking AI status\" is not repeated on every step when the page already says it once; the heading \"Checking AI status…\" says it (fm-10 \"each fact stands once\"). The oracle that nothing is claimed as working while observations are not live is kept: every step is unlit and every switch disabled."
        },
        {
          "oldAssertion": "D1: the sentence is \"Everything below runs on this computer.\"; D8: the answer step shows the plan's 1.0 KB size after an older preview resolves.",
          "newAssertion": "Unchanged expectations; the fixture adds status.embedding.compatState COMPATIBLE and runtimeStatus.active.chatProfile \"standard\".",
          "reason": "The fixture's status now carries embedding.compatState COMPATIBLE: the positive heading needs the Worker's live meaning-query compatibility as well as the encoder (X1). X5: once the answer files are downloaded, the size is shown only when the running engine names a profile with a matching package value; \"standard\" is the profile whose package the plan sizes (ChatModelProfile STANDARD \"standard\")."
        },
        {
          "oldAssertion": "G1/G2: with Advanced open, the observed search features and the component rows say \"when last observed\"/\"last observed\" and never \"in use\".",
          "newAssertion": "Unchanged expectations, with developer tools switched on first.",
          "reason": "X6 (AM5) puts the component list and the observed search features behind the live developer-tools preference; the last-observed oracle is kept where the panels now are."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.pausedInstall.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "AI isn't installed yet / Search already works with exact words. Installing AI adds the steps that are not lit yet. / Install AI · 2.6 GB / You see each licence before anything downloads. Everything runs on this computer. / Text in scans and images: Simple scans are read already / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch on, Included / Answer questions: Needs an NVIDIA graphics card (drawn not reachable). Lit: Your files, Read the text; no tab row; no links row.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-10-ai-not-installed (tmp/design/fm-10-ai-not-installed.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "owner-words",
          "quote": "AI Brain on a computer without an NVIDIA card (most computers). The sentence says only what the picture cannot; each fact stands once (the card under its step, sizes under theirs). A switch exists only under its step, with its state in words. What can't work here is said once, in ordinary words, and drawn as not reachable. Only the steps that work now are lit; the Install button is the one filled action.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        }
      ],
      "behaviour": "A genuinely fresh install is untouched by the paused-download handling: \"AI isn't installed yet\", the fm-10 sentence, no \"Download Paused\".",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Text contains \"Install AI models to get started\".",
          "newAssertion": "Text contains \"Search already works with exact words. Installing AI adds the steps that are not lit yet.\"",
          "reason": "fm-10 picture words for the sentence under \"AI isn't installed yet\"; the other two assertions are unchanged."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "No tab row until answers work, then \"Overview | Memory\". The sentence has no switches; each switch sits under its step with its state in words.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Installed, no NVIDIA card. The headline says what now works, never \"AI Offline\" or \"Installed with limitations\". An off switch reads \"Off\" and is drawn off.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D2 Sentence has no switches; each switch sits under its step with its state in words; existing handlers and meanings only (pre-install Included = package decline; Best match first = rerankerEnabled/intent; Answer questions = nullable chatEnabled, unset stays unset; Text in scans and images = pictureReadingEnabled). Switch shows stored selection; the step's lit/unlit and state words show what works now (selection never presented as activity).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM2 (D2) Switch projection from stored selection only (never runtime): saved Off/runtime On shows Off; saved On/runtime Off shows On with the step's words saying it is not working; unknown settings show \"Not known\" (not an unchecked switch) with the existing refresh route; unset answers show automatic without writing a default; operator overrides explained. Existing picture tests encoding the old drawing change (listed).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision: two test fixture operation keys are replaced with '01993ba0-0000-7000-8000-000000000002' (secret-scanner false positive; fixture value only).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W1 (2026-11-15): State words and notes adopted verbatim (mine): switch state words \"On\", \"Off\", \"Not known\", \"Included\", \"Not included\", \"Changing…\"; notes \"Checking after an interrupted change\", \"Not confirmed yet\", \"Set by an operator override\", \"Not working — its files are missing\", \"Not working now\", \"Not available with this answer model\", \"Off is not applied yet\"; exceptional headlines/sentences \"Answers paused while files are read\" + \"Answers resume when JustSearch finishes reading the files.\", \"Reading text in scans and images\" + \"Answers resume after the current document.\", \"Read everything again to restore full search.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W2 (2026-11-15): Unset picture-reading choice is drawn as the Engine's effective default (today On) with nothing written (like unset answers, AM2); its note says why it is not working when it is not.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W5 (2026-11-15T19:40Z): Restore last-known truth: when the AI observation is lost while pictures were being read, the page says once, in its sentence, \"Pictures were being read when last observed; current answer availability is unconfirmed.\" (main's wording, state/brainPage.ts:38/48 at 8a312d1484); per-step 'Unknown — checking AI status' is not repeated as a separate fact on every step when the page already says it once (fm-10 'each fact stands once').",
          "label": "agent choice"
        }
      ],
      "behaviour": "Saved picture reading with an answer model that cannot read pictures: delayed reads cannot overturn newer receipts, interrupted changes reconcile, refusals keep the prior choice, unconfirmed projector reports never read as text-only. The one switch under the step shows the stored choice; the step's light and note show what works now; the sentence carries no per-feature part.",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "view() reads the path copy's last .step-meta (\"path\") and the sentence's pictures phrase (\"sentence\"); two switches.",
          "newAssertion": "view() reads the step's state word (.switch-state, \"word\"), its note (.step-note, \"note\"), one switch, and the step's data-lit.",
          "reason": "D2/handover: one switch under its step with its state in words; the sentence no longer has per-feature parts (fm-14 sentence \"Everything below runs on this computer.\")."
        },
        {
          "oldAssertion": "Not available: path \"On — not available with this answer model\", sentence \"Text in scans and images: On — not available with this answer model.\", lit false.",
          "newAssertion": "Not available: word \"On\", note \"Not available with this answer model\", one switch checked, lit false.",
          "reason": "AM2: \"saved On/runtime Off shows On with the step's words saying it is not working\". Same fact, split into the switch word and the note; the note \"Not available with this answer model\" (line 12) is adopted verbatim in W1."
        },
        {
          "oldAssertion": "Reading: path \"On\", sentence \"It reads text in scans and images.\"; checking: path \"Unknown — checking AI status\" and the sentence says AI status and feature availability are still being checked.",
          "newAssertion": "Reading: word \"On\", no note, lit true. Checking: heading \"Checking AI status…\", the step has no note and is unlit, and the page never says \"Unknown — checking AI status\"; never the not-available words.",
          "reason": "The unknown state is said once, in the base heading \"Checking AI status…\" (it exists at the base): W5, per-step \"Unknown — checking AI status\" is not repeated when the page already says it once (fm-10 \"each fact stands once\"); D2 removes the sentence copy. The never-claim oracle is kept: unlit, never the not-available words."
        },
        {
          "oldAssertion": "P5: Off reads \"Off - files and read text kept\"; never \"On — the saved Off choice is not applied\".",
          "newAssertion": "P5: word \"Off\", no note; never \"Off is not applied yet\".",
          "reason": "fm-13 \"An off switch reads \\\"Off\\\"\"; D4 \"one kept-files wording\" (the per-step kept-files tail is gone). The receipt-ordering oracle is unchanged. \"Off is not applied yet\" (line 185) is adopted verbatim in W1."
        },
        {
          "oldAssertion": "P5 interrupted: path \"Unknown — checking picture reading after an interrupted change\".",
          "newAssertion": "Word \"Not known\", note \"Checking after an interrupted change\", no switch; after reconciliation the word is \"On\".",
          "reason": "AM2: unknown shows \"Not known\" (not an unchecked switch). The word \"Not known\" and the note \"Checking after an interrupted change\" (line 283) are adopted verbatim in W1."
        },
        {
          "oldAssertion": "P3 null projector: path \"On\", sentence \"Reading text in scans and images is unconfirmed.\"; offline engine: sentence unconfirmed.",
          "newAssertion": "P3 null projector: word \"On\", note \"Not confirmed yet\", lit false; offline engine: lit false and never the not-available words.",
          "reason": "AM2: the switch keeps the stored On; the step's light and note show the unconfirmed runtime. The \"never text-only\" oracle is kept; \"Not confirmed yet\" (line 319) is adopted verbatim in W1."
        },
        {
          "oldAssertion": "P4 table (path, sentence, both switches): explicit Off -> \"Off - files and read text kept\", unchecked; operator Off -> \"Off — operator controlled\", unchecked; operator On with inactive reader -> \"Unavailable — operator picture reader is not active\", unchecked; missing files -> \"Unavailable — picture reading files are missing\", checked; unknown settings -> \"Saved picture reading choice is not known. Refresh settings before changing it.\", unchecked; no saved choice -> \"Off - files and read text kept\", unchecked; saved Off with runtime On -> \"On — the saved Off choice is not applied\", checked. Never the not-available words; no posts.",
          "newAssertion": "P4 table (word, note, switch, lit): explicit Off -> Off, none, unchecked, unlit; operator Off -> Off, \"Set by an operator override\", unchecked, unlit; operator On -> On, \"Set by an operator override\", checked, unlit; missing files -> On, \"Not working — its files are missing\", checked, unlit; unknown settings -> \"Not known\", none, no switch, unlit; no saved choice -> On, \"Not available with this answer model\", checked, unlit; saved Off with runtime On -> Off, \"Off is not applied yet\", unchecked, lit. Never the not-available words except for the no-saved-choice row; no posts.",
          "reason": "AM2: \"Switch projection from stored selection only (never runtime): saved Off/runtime On shows Off; ... unknown settings show \\\"Not known\\\" (not an unchecked switch) ...; operator overrides explained.\" The old operator-On and saved-Off/runtime-On rows drew the runtime, and the old no-saved-choice row derived Off from the runtime projector report (base: unset -> runtime mmproj ?? files present); AM2 retires both. With no saved choice the switch now shows the effective stored default (W2: the unset choice is drawn as the Engine's effective default, today On, with nothing written; its note says why it is not working) (the Engine treats unset picture reading as on: InferenceConfig.pictureReadingEnabled returns true for an unset projector choice; the page follows the installed answer model's files), and the note says it does not work with this model. Nothing is written in any row (posts [] kept). The exact notes (lines 333-338, and \"Off is not applied yet\" at line 185) are adopted verbatim in W1."
        },
        {
          "oldAssertion": "Fixture witness lastCommittedOperationKey \"other-window\".",
          "newAssertion": "lastCommittedOperationKey \"01993ba0-0000-7000-8000-000000000002\".",
          "reason": "Orchestrator decision (fixture value only; a secret-scanner false positive). The value is only compared for difference from this window's key."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-switch.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "No tab row until answers work, then \"Overview | Memory\". The sentence has no switches; each switch sits under its step with its state in words.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "AI Brain on a computer without an NVIDIA card (most computers). The sentence says only what the picture cannot; each fact stands once (the card under its step, sizes under theirs). A switch exists only under its step, with its state in words. What can't work here is said once, in ordinary words, and drawn as not reachable. Only the steps that work now are lit; the Install button is the one filled action.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Installed, no NVIDIA card. The headline says what now works, never \"AI Offline\" or \"Installed with limitations\". An off switch reads \"Off\" and is drawn off.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "AI isn't installed yet / Search already works with exact words. Installing AI adds the steps that are not lit yet. / Install AI · 2.6 GB / You see each licence before anything downloads. Everything runs on this computer. / Text in scans and images: Simple scans are read already / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch on, Included / Answer questions: Needs an NVIDIA graphics card (drawn not reachable). Lit: Your files, Read the text; no tab row; no links row.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-10-ai-not-installed (tmp/design/fm-10-ai-not-installed.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "owner-words",
          "quote": "Search understands meaning / It finds files by what they are about, not only by their words. / Text in scans and images: Simple scans are read already / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch off, Off / Answer questions: Needs an NVIDIA graphics card (drawn not reachable) / Advanced settings / Remove AI models. Lit: Your files, Read the text, Understand meaning; no tab row; no graphics card.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-13-ai-installed-no-card (tmp/design/fm-13-ai-installed-no-card.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D2 Sentence has no switches; each switch sits under its step with its state in words; existing handlers and meanings only (pre-install Included = package decline; Best match first = rerankerEnabled/intent; Answer questions = nullable chatEnabled, unset stays unset; Text in scans and images = pictureReadingEnabled). Switch shows stored selection; the step's lit/unlit and state words show what works now (selection never presented as activity).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM2 (D2) Switch projection from stored selection only (never runtime): saved Off/runtime On shows Off; saved On/runtime Off shows On with the step's words saying it is not working; unknown settings show \"Not known\" (not an unchecked switch) with the existing refresh route; unset answers show automatic without writing a default; operator overrides explained. Existing picture tests encoding the old drawing change (listed).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W1 (2026-11-15): State words and notes adopted verbatim (mine): switch state words \"On\", \"Off\", \"Not known\", \"Included\", \"Not included\", \"Changing…\"; notes \"Checking after an interrupted change\", \"Not confirmed yet\", \"Set by an operator override\", \"Not working — its files are missing\", \"Not working now\", \"Not available with this answer model\", \"Off is not applied yet\"; exceptional headlines/sentences \"Answers paused while files are read\" + \"Answers resume when JustSearch finishes reading the files.\", \"Reading text in scans and images\" + \"Answers resume after the current document.\", \"Read everything again to restore full search.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X2 (2026-11-15): Unset picture reading with missing answer files: drawn On (default, W2) with note \"Not working — its files are missing\" (W1); nothing written."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W2 (2026-11-15): Unset picture-reading choice is drawn as the Engine's effective default (today On) with nothing written (like unset answers, AM2); its note says why it is not working when it is not.",
          "label": "agent choice"
        }
      ],
      "behaviour": "The picture reading switch: unknown saved choices read \"Not known\" without a switch and post nothing; Off reads Off and is drawn off; interrupted deliveries reconcile; refusals keep the prior choice and show the refusal; a pending change locks the one switch; stale second windows refresh and converge; operator overrides lock it; consent before downloading the answer model; where answers cannot work, no switch and no download, and the step says simple scans are read already.",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Unknown saved choice: two disabled unchecked switches; the sentence and path both show \"Saved picture reading choice is not known. Refresh settings before changing it.\" as their describedby text and contain no On/Off; clicking posts nothing.",
          "newAssertion": "No switch; the step's word is \"Not known\" exactly once; the step says neither On nor Off; the sentence does not mention pictures; nothing posted.",
          "reason": "AM2: \"unknown settings show \\\"Not known\\\" (not an unchecked switch) with the existing refresh route\"; the refresh route is checked in BrainOverview.switches.test.ts (\"unknown saved choices read Not known ... with a refresh reachable\"). D2: the sentence carries no switch."
        },
        {
          "oldAssertion": "\"ends the unknown picture choice sentence with one period\": the sentence phrase is exactly the not-known sentence.",
          "newAssertion": "Removed.",
          "reason": "D2/handover: the sentence has no per-feature phrase, so the punctuation of that phrase has nothing to guard. No source still requires it."
        },
        {
          "oldAssertion": "Saved Off: the sentence phrase is \"It doesn't read text in scans and images.\" (no \"yet\"); both switches unchecked.",
          "newAssertion": "Saved Off: word \"Off\", step unlit, one unchecked switch.",
          "reason": "fm-13: \"An off switch reads \\\"Off\\\" and is drawn off.\""
        },
        {
          "oldAssertion": "Answer files not downloaded: phrase \"It doesn't read text in scans and images yet.\"",
          "newAssertion": "Word \"Off\" and step unlit.",
          "reason": "D2: the stored choice is shown as the switch word, the step's light shows that it does not work; no sentence phrase exists."
        },
        {
          "oldAssertion": "Interrupted change text \"Unknown — checking picture reading after an interrupted change\"; reconciled text \"Off - files and read text kept\".",
          "newAssertion": "Interrupted text \"Checking after an interrupted change\"; reconciled word \"Off\"; replay/witness assertions unchanged.",
          "reason": "AM2 (Not known / note) and fm-13 (\"Off\"); D4 one kept-files wording. The note \"Checking after an interrupted change\" (lines 133-167) is adopted verbatim in W1."
        },
        {
          "oldAssertion": "Pending Off: the step contains \"Stopping picture reading…\" and the switches are disabled and still checked; after receipt \"Off - files and read text kept\" and step symbol data-active false.",
          "newAssertion": "Pending Off: the word is \"Changing…\" (also after settleTransients), the one switch disabled and still checked; after receipt word \"Off\" and the step unlit.",
          "reason": "D2: the state in words under the step; the switch shows the stored selection until the applied receipt (kept). \"Changing…\" (lines 178, 182) is the base's pending word for Best match first, now shared, and adopted verbatim in W1."
        },
        {
          "oldAssertion": "Switch arrays of two (sentence copy and path copy), e.g. [\"true\",\"true\"]; the second copy ([1]) clicked.",
          "newAssertion": "Arrays of one; the single switch ([0]) clicked; every write, receipt, refusal and convergence expectation unchanged.",
          "reason": "Handover / D2: \"The sentence has no switches; each switch sits under its step with its state in words.\" AM2 lists these picture tests as changing."
        },
        {
          "oldAssertion": "Unavailable picture reader: two disabled switches; clicking posts nothing; text \"Text in scans and images is unavailable on this computer\".",
          "newAssertion": "No switch; nothing posted, no operation; the step contains \"Simple scans are read already\".",
          "reason": "fm-10/fm-13 picture words for the pictures step on a computer where answers cannot work (\"Text in scans and images: Simple scans are read already\", no switch); fm-10 \"A switch exists only under its step\". The no-download/no-post oracle is kept."
        },
        {
          "oldAssertion": "Answer files not downloaded yet, picture choice unset: the step reads \"Off\" and is not lit.",
          "newAssertion": "The step reads \"On\", says \"Not working — its files are missing\", is not lit, and nothing is posted.",
          "reason": "X2 and W2: an unset picture choice is drawn as the Engine's effective default (On) and nothing is written; the note words are W1 verbatim. \"Not lit\" stays (only steps that work now are lit)."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.redesign.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "No tab row until answers work, then \"Overview | Memory\". The sentence has no switches; each switch sits under its step with its state in words.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Installed, no NVIDIA card. The headline says what now works, never \"AI Offline\" or \"Installed with limitations\". An off switch reads \"Off\" and is drawn off.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Overview | Memory / AI is ready / Everything below runs on this computer. / Text in scans and images: 2.1 GB, switch off, Off / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch on, On / Answer questions: 5.7 GB, switch on, On / Graphics card: 5.8 of 12 GB in use · RTX 4070 / Advanced settings / Engine history in Health. All five path steps lit.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-14-ai-ready (tmp/design/fm-14-ai-ready.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D2 Sentence has no switches; each switch sits under its step with its state in words; existing handlers and meanings only (pre-install Included = package decline; Best match first = rerankerEnabled/intent; Answer questions = nullable chatEnabled, unset stays unset; Text in scans and images = pictureReadingEnabled). Switch shows stored selection; the step's lit/unlit and state words show what works now (selection never presented as activity).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D5 Not in the pictures -> behind Settings > Advanced > Show developer tools (or Advanced): feature disclosure \"You get / It costs\", Start/Stop answers, Refresh status icon. Graphics card meter only where pictured (card present). Advanced settings and Engine history stay as in fm-14. Exceptional states (policy errors, compatibility/rebuild warnings, enrichment progress, genuine Repair) keep their actionable words; technical detail behind a disclosure.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM2 (D2) Switch projection from stored selection only (never runtime): saved Off/runtime On shows Off; saved On/runtime Off shows On with the step's words saying it is not working; unknown settings show \"Not known\" (not an unchecked switch) with the existing refresh route; unset answers show automatic without writing a default; operator overrides explained. Existing picture tests encoding the old drawing change (listed).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM3 (D3) Positive \"Search understands meaning\" heading only with live meaning evidence; embedding absent/inactive/unknown, activation required, incompatible embeddings, answers-working-but-meaning-not keep their remedy headline; unknown -> neutral headline.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM8 (D4) Sizes keep what they measure: package download size labelled as download, never as measured disk use; omitted for compact/custom models unless a matching trustworthy value exists (omission named). Graphics meter only with a current reading; never infer \"no card\" from a missing meter; installing/unsupported/insufficient-memory/unknown settled explicitly.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W5 (2026-11-15T19:40Z): Restore last-known truth: when the AI observation is lost while pictures were being read, the page says once, in its sentence, \"Pictures were being read when last observed; current answer availability is unconfirmed.\" (main's wording, state/brainPage.ts:38/48 at 8a312d1484); per-step 'Unknown — checking AI status' is not repeated as a separate fact on every step when the page already says it once (fm-10 'each fact stands once').",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X1 (2026-11-15): Meaning positive only when vector queries are live-compatible (COMPATIBLE); REBUILDING shows the rebuilding state in read words, Meaning unlit."
        }
      ],
      "behaviour": "AI Brain redesign behaviour: one saved answer switch with one busy state; stopping keeps files and background work; runtime evidence shows in the step light and unknown observations disable switches; unset answers stay automatic without a write; feature panels (developer tools) keep focus behaviour; acquisitions and consent unchanged; the graphics meter only with a current reading.",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Two answer switches with distinct ids, both checked; both disabled while busy; after stop both unchecked and the text contains \"Answers are off; background work can continue\".",
          "newAssertion": "One answer switch, checked; disabled while busy; after stop unchecked and the step's word is \"Off\"; the answer model files stay installed; turning on writes enabled:true.",
          "reason": "Handover/D2 one switch under its step; fm-13 \"An off switch reads \\\"Off\\\"\". The keep-files and write oracles are unchanged."
        },
        {
          "oldAssertion": "After mmprojActive false the page text contains \"It doesn't read text in scans and images yet\".",
          "newAssertion": "After mmprojActive false the pictures step is unlit (data-lit false); the unknown-observation and disabled-switch checks are unchanged.",
          "reason": "AM2: the light shows what works now; the sentence has no per-feature part (D2)."
        },
        {
          "oldAssertion": "Null answer intent: the chat path contains \"automatic (no saved answer choice)\".",
          "newAssertion": "Null answer intent: the step's word is \"Automatic\"; the switch is checked; nothing written.",
          "reason": "AM2 \"unset answers show automatic without writing a default\"; D2 \"Answer questions = nullable chatEnabled, unset stays unset\"."
        },
        {
          "oldAssertion": "Information opener located by aria-label \"About Answer questions\", developer tools off.",
          "newAssertion": "Opener located as [data-step=\"chat\"] button.step-info with developer tools on; panel, focus and Escape/outside-click assertions unchanged.",
          "reason": "D5: the \"You get / It costs\" disclosure is a developer tool; owner words \"What a picture does not show ... goes behind Settings › Advanced › Show developer tools\"."
        },
        {
          "oldAssertion": "Graphics memory: card contains \"4.0 KB of 8.0 KB in use · all apps · Test adapter\"; stale values say \"Memory use unknown\" with no aria-valuenow.",
          "newAssertion": "Card contains \"4.0 KB of 8.0 KB in use · Test adapter\"; stale values draw no card and never \"Memory use unknown\".",
          "reason": "fm-14 picture words \"5.8 of 12 GB in use · RTX 4070\"; AM8 meter only with a current reading. The all-app device data source is unchanged."
        },
        {
          "oldAssertion": "Fixture onnxFeatures without status.",
          "newAssertion": "Fixture onnxFeatures embed/reranker with status \"active\".",
          "reason": "AM3: positive headings need live meaning evidence; the fixture states the observation its scenario (AI ready) implies."
        },
        {
          "oldAssertion": "Switch arrays of two (sentence copy and path copy), e.g. [\"true\",\"true\"]; the second copy ([1]) clicked.",
          "newAssertion": "Arrays of one; the single switch ([0]) clicked; every write, receipt, refusal and convergence expectation unchanged.",
          "reason": "Handover / D2: \"The sentence has no switches; each switch sits under its step with its state in words.\" AM2 lists these picture tests as changing."
        },
        {
          "oldAssertion": "keeps runtime evidence in the step light ...: after the snapshot is no longer live, the pictures step contains \"Unknown\".",
          "newAssertion": "The heading is \"Checking AI status…\"; the pictures step does not contain \"Unknown — checking AI status\" and is unlit; both switches stay present and disabled (kept).",
          "reason": "W5: per-step \"Unknown — checking AI status\" is not repeated when the page already says it once, in the heading (fm-10 \"each fact stands once\"). The oracle that an unknown observation is not shown as working is kept by the unlit step and the disabled switches."
        },
        {
          "oldAssertion": "After the paused install completes, the heading reads \"AI is ready\".",
          "newAssertion": "Unchanged expectation; the fixture's status adds embedding.compatState COMPATIBLE.",
          "reason": "The fixture's status now carries embedding.compatState COMPATIBLE: the positive heading needs the Worker's live meaning-query compatibility as well as the encoder (X1)."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.reindex-coherence.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Every fact has one home on a screen. ... The same thing has one name everywhere: the app reads files (not \"indexes\")",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM9 Brain wording in 7a: \"indexing\", \"Resume Chat AI\", \"Rebuild the index\" and similar on Brain's default and exceptional states move to read/answers words; page/status coherence for Brain states (S7-25 subset); verdict ids unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D5 Not in the pictures -> behind Settings > Advanced > Show developer tools (or Advanced): feature disclosure \"You get / It costs\", Start/Stop answers, Refresh status icon. Graphics card meter only where pictured (card present). Advanced settings and Engine history stay as in fm-14. Exceptional states (policy errors, compatibility/rebuild warnings, enrichment progress, genuine Repair) keep their actionable words; technical detail behind a disclosure.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W1 (2026-11-15): State words and notes adopted verbatim (mine): switch state words \"On\", \"Off\", \"Not known\", \"Included\", \"Not included\", \"Changing…\"; notes \"Checking after an interrupted change\", \"Not confirmed yet\", \"Set by an operator override\", \"Not working — its files are missing\", \"Not working now\", \"Not available with this answer model\", \"Off is not applied yet\"; exceptional headlines/sentences \"Answers paused while files are read\" + \"Answers resume when JustSearch finishes reading the files.\", \"Reading text in scans and images\" + \"Answers resume after the current document.\", \"Read everything again to restore full search.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X7 (2026-11-15): Populated compatibility reason codes and the rebuild action use read words (\"Rebuild all search data\", as slice 6a); tested on populated paths."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X8 (2026-11-15): Tests: ladder audit uses the repository ladder rule exactly (no rounding beyond the check's own rule, all four corners); new checks' fail-before must come from the behaviour (query by role/name/text that exists on main, not new-only markup); literal expected wording instead of reasonFor; restore the cleared-after-recovery assertion."
        }
      ],
      "behaviour": "The compatibility callout says each populated reindex cause in AI Brain's read words (one agreed sentence for the embedding causes), keeps the technical detail and the schema arm's real vocabulary, and falls back to one generic remedy line without a code.",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Text contains \"Rebuild the index to restore full search.\"",
          "newAssertion": "Text contains \"Read everything again to restore full search.\"",
          "reason": "AM9 names \"Rebuild the index\" among the words that move to read words; owner words \"the app reads files (not \\\"indexes\\\")\"; D5 keeps the compatibility warning's actionable words. The exact sentence \"Read everything again to restore full search.\" (line 77) is adopted verbatim in W1."
        },
        {
          "oldAssertion": "index.embedding_legacy: the callout contains reasonFor('index.embedding_legacy').wording (the canonical index wording).",
          "newAssertion": "It contains \"Read everything again to restore full search.\" and not the canonical sentence \"Semantic search isn't available on this index yet — rebuild it to enable meaning-based results.\" (literal).",
          "reason": "X7: populated reason codes use read words on Brain; owner words \"the app reads files (not \\\"indexes\\\")\"; the sentence is W1 verbatim. X8: literals instead of reasonFor. The canonical vocabulary stays for the other surfaces (readinessNotice.ts is unchanged)."
        },
        {
          "oldAssertion": "Schema BLOCKED_MISMATCH: the callout contains reasonFor('index.schema_mismatch').wording.",
          "newAssertion": "It does not contain the canonical sentence (literal), says \"Search works fully\" and \"reading everything again\"; \"Schema incompatible\" and the schema_mismatch detail are kept.",
          "reason": "X7 read words; the cause keeps its meaning (search is fully working; reading again adds newer features). The exact sentence is an agent choice under X7, so only its meaning is asserted (no exact value without a source)."
        },
        {
          "oldAssertion": "Schema BLOCKED_LEGACY with index.blocked_legacy: the callout contains reasonFor('index.blocked_legacy').wording.",
          "newAssertion": "It contains \"Read everything again to restore full search.\" and not the canonical sentence (literal); the remedy is still offered.",
          "reason": "X7 and W1 as above; X8 literals."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.removalBand-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D5 Not in the pictures -> behind Settings > Advanced > Show developer tools (or Advanced): feature disclosure \"You get / It costs\", Start/Stop answers, Refresh status icon. Graphics card meter only where pictured (card present). Advanced settings and Engine history stay as in fm-14. Exceptional states (policy errors, compatibility/rebuild warnings, enrichment progress, genuine Repair) keep their actionable words; technical detail behind a disclosure.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM5 (D5) Unpictured controls: either removed (named) or behind Settings > Advanced > Show developer tools (live preference change); Brain's own Advanced keeps only what fm-14 shows plus AM6 routes; no \"or Advanced\" dumping ground.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM6 Recovery routes stay reachable without developer tools: pack import when downloads are disabled, settings refresh for unknown choices, manual repair URLs/destinations, removal refusals/interrupted outcomes, Settings \"Reading memory\" link opens and focuses its control.",
          "label": "agent choice"
        }
      ],
      "behaviour": "The removal band in real Chromium (confirm, refuse, Escape handling, outcomes) is unchanged; its fixture observes no AI state, so Advanced settings is opened with developer tools on.",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Advanced settings (the removal band) in a fixture without AI observations observed with developer tools off (the default).",
          "newAssertion": "Developer tools are turned on (setShowDeveloperTools(true)) before Advanced settings (the removal band) in a fixture without AI observations is observed; they are turned off again after the test.",
          "reason": "Owner words \"What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.\" with D5/AM5 (Brain's Advanced shows only where fm-13/fm-14 show it, plus the AM6 recovery routes). Every assertion about the control itself is kept; only the precondition that makes it visible changed."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "AI Brain on a computer without an NVIDIA card (most computers). The sentence says only what the picture cannot; each fact stands once (the card under its step, sizes under theirs). A switch exists only under its step, with its state in words. What can't work here is said once, in ordinary words, and drawn as not reachable. Only the steps that work now are lit; the Install button is the one filled action.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Installed, no NVIDIA card. The headline says what now works, never \"AI Offline\" or \"Installed with limitations\". An off switch reads \"Off\" and is drawn off.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Search understands meaning / It finds files by what they are about, not only by their words. / Text in scans and images: Simple scans are read already / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch off, Off / Answer questions: Needs an NVIDIA graphics card (drawn not reachable) / Advanced settings / Remove AI models. Lit: Your files, Read the text, Understand meaning; no tab row; no graphics card.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-13-ai-installed-no-card (tmp/design/fm-13-ai-installed-no-card.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D3 Heading says what works: installed with working meaning search and answers unavailable -> a \"Search understands meaning\" style heading (as fm-13), never \"AI Offline\"/\"Installed with limitations\"; answers unavailable said once under its step in ordinary words. Genuine failure/repair/policy headlines stay.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D4 One fact once: card under its step, sizes under theirs; no \"registry footprint\", no leading separators, one kept-files wording; the CUDA reason once.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM3 (D3) Positive \"Search understands meaning\" heading only with live meaning evidence; embedding absent/inactive/unknown, activation required, incompatible embeddings, answers-working-but-meaning-not keep their remedy headline; unknown -> neutral headline.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W5 (2026-11-15T19:40Z): Restore last-known truth: when the AI observation is lost while pictures were being read, the page says once, in its sentence, \"Pictures were being read when last observed; current answer availability is unconfirmed.\" (main's wording, state/brainPage.ts:38/48 at 8a312d1484); per-step 'Unknown — checking AI status' is not repeated as a separate fact on every step when the page already says it once (fm-10 'each fact stands once').",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X1 (2026-11-15): Meaning positive only when vector queries are live-compatible (COMPATIBLE); REBUILDING shows the rebuilding state in read words, Meaning unlit."
        }
      ],
      "behaviour": "Running repair controls: Cancel says the download paused or shows the completed state; planning never shows Downloading for declined or unavailable parts; heading after completion says what works.",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "After the install completes, the heading is \"AI is ready\".",
          "newAssertion": "The heading is \"Search understands meaning\" (answers are saved Off in this fixture; meaning is observed active).",
          "reason": "D3 and fm-13 \"The headline says what now works\": with live meaning evidence (AM3) and answers not working, the heading is fm-13's \"Search understands meaning\", not \"AI is ready\"."
        },
        {
          "oldAssertion": "Planning with an unavailable reranker or answer part: the step contains \"Unavailable on this computer\".",
          "newAssertion": "The step is drawn not reachable (data-reach=\"na\"); never \"Not available here.\"; declined still \"Not downloaded\"; never Downloading/Waiting/Preparing (kept).",
          "reason": "fm-10: \"What can't work here is said once, in ordinary words, and drawn as not reachable\"; D4 one fact once."
        },
        {
          "oldAssertion": "Unavailable answer runtime with complete files: the step contains \"Off — background work can continue\".",
          "newAssertion": "The step's switch word is \"Off\".",
          "reason": "fm-13 \"An off switch reads \\\"Off\\\"\"; D4 one fact once (the background-work tail is gone)."
        },
        {
          "oldAssertion": "A failed install read gates a retained running download/plan observation: the meaning step contains \"Unknown — checking AI status\".",
          "newAssertion": "The heading is \"Checking AI status…\" (kept); the meaning step does not contain \"Unknown — checking AI status\" and is unlit; no progress, Pause or Cancel (kept).",
          "reason": "W5: per-step \"Unknown — checking AI status\" is not repeated when the page already says it once, in the heading (fm-10 \"each fact stands once\"). The gate oracle (no retained progress shown as current) is unchanged; the unlit step keeps \"nothing claimed as working\"."
        },
        {
          "oldAssertion": "After the install finished first, the heading reads \"Search understands meaning\".",
          "newAssertion": "Unchanged expectation; the fixture adds status.embedding.compatState COMPATIBLE.",
          "reason": "The fixture's status now carries embedding.compatState COMPATIBLE: the positive heading needs the Worker's live meaning-query compatibility as well as the encoder (X1)."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.reranker-switch.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "No tab row until answers work, then \"Overview | Memory\". The sentence has no switches; each switch sits under its step with its state in words.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Installed, no NVIDIA card. The headline says what now works, never \"AI Offline\" or \"Installed with limitations\". An off switch reads \"Off\" and is drawn off.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D2 Sentence has no switches; each switch sits under its step with its state in words; existing handlers and meanings only (pre-install Included = package decline; Best match first = rerankerEnabled/intent; Answer questions = nullable chatEnabled, unset stays unset; Text in scans and images = pictureReadingEnabled). Switch shows stored selection; the step's lit/unlit and state words show what works now (selection never presented as activity).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM2 (D2) Switch projection from stored selection only (never runtime): saved Off/runtime On shows Off; saved On/runtime Off shows On with the step's words saying it is not working; unknown settings show \"Not known\" (not an unchecked switch) with the existing refresh route; unset answers show automatic without writing a default; operator overrides explained. Existing picture tests encoding the old drawing change (listed).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W1 (2026-11-15): State words and notes adopted verbatim (mine): switch state words \"On\", \"Off\", \"Not known\", \"Included\", \"Not included\", \"Changing…\"; notes \"Checking after an interrupted change\", \"Not confirmed yet\", \"Set by an operator override\", \"Not working — its files are missing\", \"Not working now\", \"Not available with this answer model\", \"Off is not applied yet\"; exceptional headlines/sentences \"Answers paused while files are read\" + \"Answers resume when JustSearch finishes reading the files.\", \"Reading text in scans and images\" + \"Answers resume after the current document.\", \"Read everything again to restore full search.\"",
          "label": "agent choice"
        }
      ],
      "behaviour": "The Best match first switch: waits for completion, refuses operator-controlled changes, reconciles invalid receipts, converges second windows, completes consented enables, keeps a saved On that does not run as On with a not-working note, cancels consent without a transfer.",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Switch arrays of two (sentence copy and path copy), e.g. [\"true\",\"true\"]; the second copy ([1]) clicked.",
          "newAssertion": "Arrays of one; the single switch ([0]) clicked; every write, receipt, refusal and convergence expectation unchanged.",
          "reason": "Handover / D2: \"The sentence has no switches; each switch sits under its step with its state in words.\" AM2 lists these picture tests as changing."
        },
        {
          "oldAssertion": "After Off: the step contains \"Off — files kept\"; after On: the second .step-meta is \"On\".",
          "newAssertion": "Word \"Off\"; after On word \"On\" and no note.",
          "reason": "fm-13 \"An off switch reads \\\"Off\\\"\"; D4 one kept-files wording."
        },
        {
          "oldAssertion": "Invalid receipt: \"Unknown — checking Best match first after an interrupted change\".",
          "newAssertion": "Word \"Not known\", note \"Checking after an interrupted change\"; then \"Off\" after convergence.",
          "reason": "AM2 unknown -> \"Not known\"; the word \"Not known\" and the note \"Checking after an interrupted change\" (line 120) are adopted verbatim in W1."
        },
        {
          "oldAssertion": "Saved On without a serving observation: \"Unavailable — Best match first is enabled\"; then \"On\".",
          "newAssertion": "Word \"On\", note \"Not working now\"; then \"On\" with no note.",
          "reason": "AM2: \"saved On/runtime Off shows On with the step's words saying it is not working\". \"On\" and the note \"Not working now\" (line 169) are adopted verbatim in W1."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "AI Brain on a computer without an NVIDIA card (most computers). The sentence says only what the picture cannot; each fact stands once (the card under its step, sizes under theirs). A switch exists only under its step, with its state in words. What can't work here is said once, in ordinary words, and drawn as not reachable. Only the steps that work now are lit; the Install button is the one filled action.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Installed, no NVIDIA card. The headline says what now works, never \"AI Offline\" or \"Installed with limitations\". An off switch reads \"Off\" and is drawn off.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "AI isn't installed yet / Search already works with exact words. Installing AI adds the steps that are not lit yet. / Install AI · 2.6 GB / You see each licence before anything downloads. Everything runs on this computer. / Text in scans and images: Simple scans are read already / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch on, Included / Answer questions: Needs an NVIDIA graphics card (drawn not reachable). Lit: Your files, Read the text; no tab row; no links row.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-10-ai-not-installed (tmp/design/fm-10-ai-not-installed.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "owner-words",
          "quote": "No tab row until answers work, then \"Overview | Memory\". The sentence has no switches; each switch sits under its step with its state in words.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D2 Sentence has no switches; each switch sits under its step with its state in words; existing handlers and meanings only (pre-install Included = package decline; Best match first = rerankerEnabled/intent; Answer questions = nullable chatEnabled, unset stays unset; Text in scans and images = pictureReadingEnabled). Switch shows stored selection; the step's lit/unlit and state words show what works now (selection never presented as activity).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D4 One fact once: card under its step, sizes under theirs; no \"registry footprint\", no leading separators, one kept-files wording; the CUDA reason once.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D5 Not in the pictures -> behind Settings > Advanced > Show developer tools (or Advanced): feature disclosure \"You get / It costs\", Start/Stop answers, Refresh status icon. Graphics card meter only where pictured (card present). Advanced settings and Engine history stay as in fm-14. Exceptional states (policy errors, compatibility/rebuild warnings, enrichment progress, genuine Repair) keep their actionable words; technical detail behind a disclosure.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM2 (D2) Switch projection from stored selection only (never runtime): saved Off/runtime On shows Off; saved On/runtime Off shows On with the step's words saying it is not working; unknown settings show \"Not known\" (not an unchecked switch) with the existing refresh route; unset answers show automatic without writing a default; operator overrides explained. Existing picture tests encoding the old drawing change (listed).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM3 (D3) Positive \"Search understands meaning\" heading only with live meaning evidence; embedding absent/inactive/unknown, activation required, incompatible embeddings, answers-working-but-meaning-not keep their remedy headline; unknown -> neutral headline.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM10 Reachability legs separate: (a) Linux platform exclusion of answers (reachable here), (b) hardware no-card (sandbox proof or unverified), (c) fm-14 only with real answers, picture-reader and GPU observations.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W1 (2026-11-15): State words and notes adopted verbatim (mine): switch state words \"On\", \"Off\", \"Not known\", \"Included\", \"Not included\", \"Changing…\"; notes \"Checking after an interrupted change\", \"Not confirmed yet\", \"Set by an operator override\", \"Not working — its files are missing\", \"Not working now\", \"Not available with this answer model\", \"Off is not applied yet\"; exceptional headlines/sentences \"Answers paused while files are read\" + \"Answers resume when JustSearch finishes reading the files.\", \"Reading text in scans and images\" + \"Answers resume after the current document.\", \"Read everything again to restore full search.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W4 (2026-11-15): Expected sizes in tests are literal text, not computed with formatBytes; the two lost preservation checks come back (picture reading \"when last observed\" wording; Answer questions unlit when AI installed but answer model not downloaded).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W5 (2026-11-15T19:40Z): Restore last-known truth: when the AI observation is lost while pictures were being read, the page says once, in its sentence, \"Pictures were being read when last observed; current answer availability is unconfirmed.\" (main's wording, state/brainPage.ts:38/48 at 8a312d1484); per-step 'Unknown — checking AI status' is not repeated as a separate fact on every step when the page already says it once (fm-10 'each fact stands once').",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "W6: a part that the current install mode does not include (state not-in-mode) shows the reason \"Not part of this install\".",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X6 (2026-11-15): Brain Advanced legacy panels (component list, tier breakdown, package history, observed ONNX features) behind the live developer-tools preference; AM6 routes stay."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X8 (2026-11-15): Tests: ladder audit uses the repository ladder rule exactly (no rounding beyond the check's own rule, all four corners); new checks' fail-before must come from the behaviour (query by role/name/text that exists on main, not new-only markup); literal expected wording instead of reasonFor; restore the cleared-after-recovery assertion."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM5 (D5) Unpictured controls: either removed (named) or behind Settings > Advanced > Show developer tools (live preference change); Brain's own Advanced keeps only what fm-14 shows plus AM6 routes; no \"or Advanced\" dumping ground.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Slice A/B review repairs keep their oracles: chosen answers are switched on once and persist; unavailable or not-in-mode answers are never promised and are drawn not reachable; unknown runtime observations stay unconfirmed; saved LLM values are not reverted; inactive loaded features are not shown as working; unknown saved answer intent blocks changes until settings are read; picture-reading holds use last-observed uncertainty (the heading here; the \"when last observed\" sentence in BrainOverview.preservation.test.ts); step openers (developer tools) share expanded state.",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "R1: the second mount has two answer switches, both checked.",
          "newAssertion": "One answer switch, checked.",
          "reason": "Handover/D2 one switch under its step."
        },
        {
          "oldAssertion": "R2 unavailable/not-in-mode: the sentence does not contain \"and answer your questions with sources\" but contains \"answers unavailable on this computer\"; the pictures step contains \"unavailable on this computer\"; no answer switch.",
          "newAssertion": "The sentence mentions no answers at all; the answer step is drawn not reachable (data-reach=\"na\") with a reason (\"Not part of this install\" for not-in-mode, non-empty otherwise); the planner reason \"No supported graphics card\" is not shown; the pictures step contains \"Simple scans are read already\"; no answer switch.",
          "reason": "fm-10: \"What can't work here is said once, in ordinary words, and drawn as not reachable\" and pictures \"Simple scans are read already\"; D4 the reason once. The never-promise oracle is kept and strengthened. \"Not part of this install\" (line 131, the not-in-mode reason) is W6 verbatim."
        },
        {
          "oldAssertion": "R4 unknown runtime: the sentence contains \"Understanding meaning is unconfirmed\" and \"best match first unconfirmed\"; the steps contain \"Unknown\".",
          "newAssertion": "The heading claims neither ready nor understands; the meaning and best-match notes are \"Not confirmed yet\"; meaning unlit.",
          "reason": "AM3: unknown -> neutral headline; AM2 the note shows what works now; D2 no per-feature sentence. \"Not confirmed yet\" (lines 150, 151) is adopted verbatim in W1."
        },
        {
          "oldAssertion": "S2 inactive loaded sessions: reranker and meaning steps contain \"Off\"; the sentence contains \"without best match first\"; Advanced reranker step contains \"Off\".",
          "newAssertion": "Reranker and meaning steps are unlit with the note \"Not working now\"; unknown -> note \"Not confirmed yet\"; Advanced: reranker step unlit (component-row and 1/2 active assertions unchanged).",
          "reason": "AM2: \"Switch projection from stored selection only (never runtime) ... saved On/runtime Off shows On with the step's words saying it is not working\". The old checks drew the runtime as Off, which AM2 retires; \"never promised\" is kept by the unlit step. The notes \"Not working now\" and \"Not confirmed yet\" (lines 297-301) are adopted verbatim in W1."
        },
        {
          "oldAssertion": "S3 unknown saved answer intent (pending or failed settings): the path contains \"Saved answer choice is not known\" and not \"automatic\"; every answer switch is disabled with that describedby text and clicking writes nothing; after settings arrive the switch is enabled and pending shows \"automatic (no saved answer choice)\".",
          "newAssertion": "The step's word is \"Not known\"; no answer switch; nothing written; after settings arrive the switch is enabled and pending shows \"Automatic\".",
          "reason": "AM2: unknown -> \"Not known\" (not an unchecked switch); unset answers show automatic without writing."
        },
        {
          "oldAssertion": "S5 VDU hold: the sentence contains \"while pictures are being read\"; when the snapshot goes stale it contains \"Pictures were being read when last observed\"; after a live completion it contains \"answers your questions\" and not \"last observed\".",
          "newAssertion": "The heading is \"Reading text in scans and images\" during the hold; stale -> the heading is \"Checking AI status…\" (no current picture-reading claim); after a live completion it is neither.",
          "reason": "D2 removes the per-feature sentence that carried the last-observed phrase; the page heading falls back to the base neutral heading \"Checking AI status…\" (lines 346, 351). The never-claim-stale-reading oracle is kept here. The words \"when last observed\" for this hold are now checked in BrainOverview.preservation.test.ts (\"while pictures are being read, a lost observation says the answers state when last observed\"): the page's sentence says once \"Pictures were being read when last observed; current answer availability is unconfirmed.\" (W5, main's wording; W4 brings that preservation check back). The live headline \"Reading text in scans and images\" is adopted verbatim in W1."
        },
        {
          "oldAssertion": "S7: more than 8 openers (sentence phrases, step openers, About), all collapsed; 4 for answers.",
          "newAssertion": "With developer tools on: exactly 4 step openers (one per feature step), all collapsed; 1 for answers; shared expanded state and panel association unchanged.",
          "reason": "D2 (no phrases in the sentence) and D5 (the \"You get / It costs\" disclosure is a developer tool). The count 4 is the four switchable steps of the pictures (meaning, best match, answers, pictures)."
        },
        {
          "oldAssertion": "the step openers (S7) observed with developer tools off (the default).",
          "newAssertion": "Developer tools are turned on (setShowDeveloperTools(true)) before the step openers (S7) is observed; they are turned off again after the test.",
          "reason": "Owner words \"What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.\" with D5/AM5 (Brain's Advanced shows only where fm-13/fm-14 show it, plus the AM6 recovery routes). Every assertion about the control itself is kept; only the precondition that makes it visible changed."
        },
        {
          "oldAssertion": "Advanced open: the four groups render, and the optional parts (splade, ner, citation-scorer) have include switches.",
          "newAssertion": "The four groups render; the component list is absent without developer tools; switching developer tools on, live, shows the optional parts' include switches.",
          "reason": "X6 (AM5): the component list is a developer tool, live; the four pictured groups stay (fm-14 Advanced settings)."
        },
        {
          "oldAssertion": "S2: component rows say in use/Downloaded and the observed features read 1/2 active.",
          "newAssertion": "Unchanged expectations, with developer tools switched on first.",
          "reason": "X6 (AM5) as above."
        },
        {
          "oldAssertion": "S5 (this branch before this round): after a live completion the heading is neither \"Reading text in scans and images\" nor \"Checking AI status…\".",
          "newAssertion": "Also: while disconnected the sentence contains \"Pictures were being read when last observed\"; after the live completion the page no longer contains \"last observed\".",
          "reason": "X8 restores the assertion that the last-observed uncertainty clears after live recovery (origin/main had it on the sentence: not.toContain('last observed')); W5 restores main's last-observed sentence, said once. The restored check reads the whole page, which is at least as strict as main's sentence-only check."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.snapshotLiveness.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Installed on a computer with an NVIDIA card. Only now is there a second tab (Memory), so only now a tab row; its first tab is \"Overview\", not the area's name again.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Overview | Memory / AI is ready / Everything below runs on this computer. / Text in scans and images: 2.1 GB, switch off, Off / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch on, On / Answer questions: 5.7 GB, switch on, On / Graphics card: 5.8 of 12 GB in use · RTX 4070 / Advanced settings / Engine history in Health. All five path steps lit.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-14-ai-ready (tmp/design/fm-14-ai-ready.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D5 Not in the pictures -> behind Settings > Advanced > Show developer tools (or Advanced): feature disclosure \"You get / It costs\", Start/Stop answers, Refresh status icon. Graphics card meter only where pictured (card present). Advanced settings and Engine history stay as in fm-14. Exceptional states (policy errors, compatibility/rebuild warnings, enrichment progress, genuine Repair) keep their actionable words; technical detail behind a disclosure.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM3 (D3) Positive \"Search understands meaning\" heading only with live meaning evidence; embedding absent/inactive/unknown, activation required, incompatible embeddings, answers-working-but-meaning-not keep their remedy headline; unknown -> neutral headline.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W5 (2026-11-15T19:40Z): Restore last-known truth: when the AI observation is lost while pictures were being read, the page says once, in its sentence, \"Pictures were being read when last observed; current answer availability is unconfirmed.\" (main's wording, state/brainPage.ts:38/48 at 8a312d1484); per-step 'Unknown — checking AI status' is not repeated as a separate fact on every step when the page already says it once (fm-10 'each fact stands once').",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X1 (2026-11-15): Meaning positive only when vector queries are live-compatible (COMPATIBLE); REBUILDING shows the rebuilding state in read words, Meaning unlit."
        }
      ],
      "behaviour": "With the backend gone the card no longer reads as working, the heading says once that AI status is being checked and the answer step is unlit without a note of its own; a live observation still renders \"AI is ready\", the fm-14 sentence, the On answer step and an available Stop answers (developer tool).",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Round-13: text does not contain \"Chat and summaries ready.\"; anti-regression: text contains \"Chat and summaries ready.\".",
          "newAssertion": "Round-13: text does not contain \"Everything below runs on this computer.\"; anti-regression: it does.",
          "reason": "fm-14 picture words replace the old status sentence; the oracles (no ready claim when gone, ready claim when live) are unchanged."
        },
        {
          "oldAssertion": "Answer state read from the last .step-meta of the chat path; action located by data-testid=\"brain-simple-action\".",
          "newAssertion": "Answer state read from the step note, else its switch word; action located by data-testid=\"brain-answers-action\". Expected values \"On\", \"Stop answers\" and available are unchanged; the round-13 value changed (next change).",
          "reason": "D2 state in words under the step; D5 Start/Stop answers has its own id as a developer tool."
        },
        {
          "oldAssertion": "Fixture without meaning evidence, developer tools off.",
          "newAssertion": "runtimeStatus embed active (AM3 live meaning evidence for the positive heading) and devTools = true (D5 Start/Stop answers).",
          "reason": "Preconditions only; the asserted outcomes are unchanged."
        },
        {
          "oldAssertion": "Round-13: the answer step state is \"Unknown — checking AI status\".",
          "newAssertion": "Round-13: the heading is \"Checking AI status…\"; the answer step is unlit (data-lit \"false\") and has no note of its own; the heading is not \"AI is ready\" (kept).",
          "reason": "W5: per-step \"Unknown — checking AI status\" is not repeated when the page already says it once, in the heading (fm-10 \"each fact stands once\"). The oracle that a retained \"on\" observation is not reported as current is kept by the heading and the unlit step."
        },
        {
          "oldAssertion": "A live observation renders the heading \"AI is ready\".",
          "newAssertion": "Unchanged expectation; the harness sets systemStatus.embedding.compatState COMPATIBLE.",
          "reason": "The fixture's status now carries embedding.compatState COMPATIBLE: the positive heading needs the Worker's live meaning-query compatibility as well as the encoder (X1)."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.telemetry-mode.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Overview | Memory / AI is ready / Everything below runs on this computer. / Text in scans and images: 2.1 GB, switch off, Off / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch on, On / Answer questions: 5.7 GB, switch on, On / Graphics card: 5.8 of 12 GB in use · RTX 4070 / Advanced settings / Engine history in Health. All five path steps lit.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-14-ai-ready (tmp/design/fm-14-ai-ready.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D5 Not in the pictures -> behind Settings > Advanced > Show developer tools (or Advanced): feature disclosure \"You get / It costs\", Start/Stop answers, Refresh status icon. Graphics card meter only where pictured (card present). Advanced settings and Engine history stay as in fm-14. Exceptional states (policy errors, compatibility/rebuild warnings, enrichment progress, genuine Repair) keep their actionable words; technical detail behind a disclosure.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM5 (D5) Unpictured controls: either removed (named) or behind Settings > Advanced > Show developer tools (live preference change); Brain's own Advanced keeps only what fm-14 shows plus AM6 routes; no \"or Advanced\" dumping ground.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Developer telemetry belongs to Health; Brain never polls telemetry with Advanced closed or open; the Engine history link focuses Health's AI Engine section whether Health is mounted already or later.",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Advanced settings and the Engine history link (fixture without AI observations) observed with developer tools off (the default).",
          "newAssertion": "Developer tools are turned on (setShowDeveloperTools(true)) before Advanced settings and the Engine history link (fixture without AI observations) is observed; they are turned off again after the test.",
          "reason": "Owner words \"What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.\" with D5/AM5 (Brain's Advanced shows only where fm-13/fm-14 show it, plus the AM6 recovery routes). Every assertion about the control itself is kept; only the precondition that makes it visible changed."
        },
        {
          "oldAssertion": "The engine-history link is the first .brain-links anchor.",
          "newAssertion": "Additionally asserts the link text contains \"Engine history in Health\".",
          "reason": "fm-14 picture words \"Engine history in Health\"; added to make sure the clicked link is that one."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.ui-mode.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D5 Not in the pictures -> behind Settings > Advanced > Show developer tools (or Advanced): feature disclosure \"You get / It costs\", Start/Stop answers, Refresh status icon. Graphics card meter only where pictured (card present). Advanced settings and Engine history stay as in fm-14. Exceptional states (policy errors, compatibility/rebuild warnings, enrichment progress, genuine Repair) keep their actionable words; technical detail behind a disclosure.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM5 (D5) Unpictured controls: either removed (named) or behind Settings > Advanced > Show developer tools (live preference change); Brain's own Advanced keeps only what fm-14 shows plus AM6 routes; no \"or Advanced\" dumping ground.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Opening Brain's Advanced never writes ui.mode, under either global detail level.",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Advanced settings (fixture without AI observations) observed with developer tools off (the default).",
          "newAssertion": "Developer tools are turned on (setShowDeveloperTools(true)) before Advanced settings (fixture without AI observations) is observed; they are turned off again after the test.",
          "reason": "Owner words \"What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.\" with D5/AM5 (Brain's Advanced shows only where fm-13/fm-14 show it, plus the AM6 recovery routes). Every assertion about the control itself is kept; only the precondition that makes it visible changed."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ContextWindow.home-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "No tab row until answers work, then \"Overview | Memory\". The sentence has no switches; each switch sits under its step with its state in words.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Installed on a computer with an NVIDIA card. Only now is there a second tab (Memory), so only now a tab row; its first tab is \"Overview\", not the area's name again.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D1 Tab row hidden until answers work (same readiness as slice 4's Search / Ask); then \"Overview | Memory\". Explicit Memory navigation (justsearch://surface/core.memory-surface, ?tab=core.memory-surface, palette \"Open AI Brain › Memory\", Back/Forward, reload) still opens Memory and then shows the tab row so Overview is reachable; Memory facts stay readable/forgettable whatever the answer state.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM5 (D5) Unpictured controls: either removed (named) or behind Settings > Advanced > Show developer tools (live preference change); Brain's own Advanced keeps only what fm-14 shows plus AM6 routes; no \"or Advanced\" dumping ground.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM6 Recovery routes stay reachable without developer tools: pack import when downloads are disabled, settings refresh for unknown choices, manual repair URLs/destinations, removal refusals/interrupted outcomes, Settings \"Reading memory\" link opens and focuses its control.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Settings arrives at Reading memory with focus, from cold and from the Memory tab; queued keyboard changes return to the saved Reading memory after a refused save.",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "The \"Memory tab\" case clicks button[data-tab-id=\"core.memory-surface\"] on the Brain tab row.",
          "newAssertion": "It opens Memory with requestSurfaceNavigation('core.memory-surface') and still waits for ?tab=core.memory-surface in the address; the Settings › Reading memory arrival and focus assertions are unchanged.",
          "reason": "D1 / handover \"No tab row until answers work\": this fixture has no working answers, so no tab to click; D1 keeps explicit Memory navigation working, which is what the case now uses."
        },
        {
          "oldAssertion": "Advanced settings (fixture without AI observations) in the queued-keyboard case observed with developer tools off (the default).",
          "newAssertion": "Developer tools are turned on (setShowDeveloperTools(true)) before Advanced settings (fixture without AI observations) in the queued-keyboard case is observed; they are turned off again after the test.",
          "reason": "Owner words \"What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.\" with D5/AM5 (Brain's Advanced shows only where fm-13/fm-14 show it, plus the AM6 recovery routes). Every assertion about the control itself is kept; only the precondition that makes it visible changed."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ContextWindow.home.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D5 Not in the pictures -> behind Settings > Advanced > Show developer tools (or Advanced): feature disclosure \"You get / It costs\", Start/Stop answers, Refresh status icon. Graphics card meter only where pictured (card present). Advanced settings and Engine history stay as in fm-14. Exceptional states (policy errors, compatibility/rebuild warnings, enrichment progress, genuine Repair) keep their actionable words; technical detail behind a disclosure.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM5 (D5) Unpictured controls: either removed (named) or behind Settings > Advanced > Show developer tools (live preference change); Brain's own Advanced keeps only what fm-14 shows plus AM6 routes; no \"or Advanced\" dumping ground.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Reading memory has one home (AI Brain Advanced); save ownership, refusals and conflicts unchanged.",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "AI Brain's Advanced settings (fixture without AI observations) observed with developer tools off (the default).",
          "newAssertion": "Developer tools are turned on (setShowDeveloperTools(true)) before AI Brain's Advanced settings (fixture without AI observations) is observed; they are turned off again after the test.",
          "reason": "Owner words \"What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.\" with D5/AM5 (Brain's Advanced shows only where fm-13/fm-14 show it, plus the AM6 recovery routes). Every assertion about the control itself is kept; only the precondition that makes it visible changed."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/brainPage.test.ts"
      ],
      "change": "deleted",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/state/brainPage.ts#composeBrainSentence",
            "modules/ui-web/src/shell-v0/state/brainPage.ts#BrainSentencePart"
          ]
        },
        {
          "kind": "owner-words",
          "quote": "No tab row until answers work, then \"Overview | Memory\". The sentence has no switches; each switch sits under its step with its state in words.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "AI Brain on a computer without an NVIDIA card (most computers). The sentence says only what the picture cannot; each fact stands once (the card under its step, sizes under theirs). A switch exists only under its step, with its state in words. What can't work here is said once, in ordinary words, and drawn as not reachable. Only the steps that work now are lit; the Install button is the one filled action.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Installed on a computer with an NVIDIA card. Only now is there a second tab (Memory), so only now a tab row; its first tab is \"Overview\", not the area's name again.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "AI isn't installed yet / Search already works with exact words. Installing AI adds the steps that are not lit yet. / Install AI · 2.6 GB / You see each licence before anything downloads. Everything runs on this computer. / Text in scans and images: Simple scans are read already / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch on, Included / Answer questions: Needs an NVIDIA graphics card (drawn not reachable). Lit: Your files, Read the text; no tab row; no links row.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-10-ai-not-installed (tmp/design/fm-10-ai-not-installed.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "owner-words",
          "quote": "Search understands meaning / It finds files by what they are about, not only by their words. / Text in scans and images: Simple scans are read already / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch off, Off / Answer questions: Needs an NVIDIA graphics card (drawn not reachable) / Advanced settings / Remove AI models. Lit: Your files, Read the text, Understand meaning; no tab row; no graphics card.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-13-ai-installed-no-card (tmp/design/fm-13-ai-installed-no-card.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "owner-words",
          "quote": "Overview | Memory / AI is ready / Everything below runs on this computer. / Text in scans and images: 2.1 GB, switch off, Off / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch on, On / Answer questions: 5.7 GB, switch on, On / Graphics card: 5.8 of 12 GB in use · RTX 4070 / Advanced settings / Engine history in Health. All five path steps lit.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-14-ai-ready (tmp/design/fm-14-ai-ready.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D2 Sentence has no switches; each switch sits under its step with its state in words; existing handlers and meanings only (pre-install Included = package decline; Best match first = rerankerEnabled/intent; Answer questions = nullable chatEnabled, unset stays unset; Text in scans and images = pictureReadingEnabled). Switch shows stored selection; the step's lit/unlit and state words show what works now (selection never presented as activity).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W4 (2026-11-15): Expected sizes in tests are literal text, not computed with formatBytes; the two lost preservation checks come back (picture reading \"when last observed\" wording; Answer questions unlit when AI installed but answer model not downloaded).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W5 (2026-11-15T19:40Z): Restore last-known truth: when the AI observation is lost while pictures were being read, the page says once, in its sentence, \"Pictures were being read when last observed; current answer availability is unconfirmed.\" (main's wording, state/brainPage.ts:38/48 at 8a312d1484); per-step 'Unknown — checking AI status' is not repeated as a separate fact on every step when the page already says it once (fm-10 'each fact stands once').",
          "label": "agent choice"
        }
      ],
      "behaviour": "composeBrainSentence (one sentence carrying a phrase per feature) is removed from brainPage.ts with its test: the pictures' sentence says only what the picture cannot, and each feature's state sits once under its step.",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "assertionChanges": [
        {
          "oldAssertion": "Installed, 27 combinations: meaning phrase \"It understands what your files are about\"; reranker/answers/pictures phrases for true/false/null; ending \". Everything runs on this computer.\"",
          "newAssertion": "Removed. Now: the installed sentence is the pictures' (fm-14 \"Everything below runs on this computer.\" in BrainOverview.tabs.test.ts S7a-5 and BrainSurface.snapshotLiveness.test.ts; fm-13 \"It finds files by what they are about, not only by their words.\" in BrainOverview.steps.test.ts S7a-2); each feature's state is its step word and light (BrainOverview.switches.test.ts, BrainSurface.reranker-switch/picture-switch/picture-availability tests).",
          "reason": "Handover/D2 \"The sentence has no switches\"; fm-10 \"The sentence says only what the picture cannot; each fact stands once\". The removal source deletes the function."
        },
        {
          "oldAssertion": "Before install: first part contains \"Installing AI lets it understand\"; reranker/answers phrases follow the download choice; pictures phrase comes with the answer model's files.",
          "newAssertion": "Removed. Now: fm-10 sentence \"Search already works with exact words. Installing AI adds the steps that are not lit yet.\" (BrainOverview.steps.test.ts S7a-1, BrainSurface.pausedInstall.test.ts); download choice as the Included switch under its step (steps S7a-1 \"Included changes only the decline ...\"); pictures step \"Simple scans are read already\" where answers cannot work (steps S7a-1).",
          "reason": "fm-10 picture words and handover \"Included\" note."
        },
        {
          "oldAssertion": "Before install never promises excluded answers: unavailable/not-in-mode -> \", with answers unavailable on this computer\"; declined -> \", without answers to questions\"; selected -> answers promised.",
          "newAssertion": "Removed. Now: unavailable/not-in-mode answers are drawn not reachable with their reason once and the sentence mentions no answers (BrainOverview.steps.test.ts S7a-1 no-card and Linux cases; BrainSurface.sliceB.test.ts R2; BrainSurface.chat-intent.test.ts). The fm-10 sentence never promises answers in any case, declined or selected.",
          "reason": "fm-10 \"What can't work here is said once ... drawn as not reachable\"; the sentence is fixed picture words."
        },
        {
          "oldAssertion": "Unknown status: exactly \"AI status and feature availability are still being checked. Everything runs on this computer.\"",
          "newAssertion": "Removed. Now: heading \"Checking AI status…\", said once and not repeated under the steps (BrainSurface.picture-availability.test.ts expectChecking; BrainSurface.snapshotLiveness.test.ts round-13; BrainOverview.steps.test.ts S7a-2 \"a lost observation is said once ...\"); while pictures were being read the sentence says \"Pictures were being read when last observed; current answer availability is unconfirmed.\" (BrainOverview.preservation.test.ts); neither claims availability.",
          "reason": "D2 no per-feature sentence; W5 the last-observed picture reading said once in the sentence and the status not repeated per step; the never-claim oracle continues in those files."
        },
        {
          "oldAssertion": "Pictures on but not available with this answer model: \". Text in scans and images: On — not available with this answer model\"",
          "newAssertion": "Removed. Now: word \"On\", note \"Not available with this answer model\", unlit (BrainSurface.picture-availability.test.ts expectNotAvailable).",
          "reason": "AM2."
        },
        {
          "oldAssertion": "Answers-off takes precedence over a picture-reading hold: \". Answers are off; background work can continue\".",
          "newAssertion": "Removed. Now: a chat-off automatic picture pass never says answers resume (BrainSurface.automatic-vdu.test.ts \"keeps the original panel sentence ...\"); the answer step word is \"Off\" (BrainSurface.redesign.test.ts).",
          "reason": "fm-13 \"An off switch reads \\\"Off\\\"\"; D4."
        },
        {
          "oldAssertion": "Answers resume after picture reading (\"resume after the current document\"); missing answer model -> \". Answers are not downloaded yet\" and never claims to answer.",
          "newAssertion": "Removed. Now: \"Answers resume after the current document.\" (BrainSurface.automatic-vdu.test.ts); an installed page whose answer model is not downloaded leaves Answer questions unlit (BrainOverview.preservation.test.ts \"with AI installed but the answer model not downloaded, Answer questions is not lit\").",
          "reason": "D2 removes the sentence part; W4 brings the unlit-answers preservation check back as its own check."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.install.test.ts"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "behaviour": "A first install that runs is not an installed AI with a missing file, and Ask in Search keeps its own readiness while Brain counts processor answers as working. Expectations and their sources: (1) running first install: the panel starts with \"Installing\", contains no repair/\"already installed\"/installed words, no Repair button or install-repair-needed notice, no enabled step switch, and offers Pause download and Cancel - AM4 (\"idle missing-file Repair and \\\"already installed\\\" wording are suppressed and a first running install is not projected as installed\"); \"Pause download\" and \"Cancel\" are the base's existing labels for the run. (2) processor answers ready with two files: Brain shows the tab row with first tab \"Overview\" while the Search composer shows no tier controls (data-tier list empty) - AM1 \"CPU operation counts ... NOT the composer's document/GPU gate; Ask behaviour itself unchanged\"; fm-14 \"its first tab is \\\"Overview\\\"\". Fail-before (origin/main 8a312d1484): both cases fail by assertion on that behaviour: (1) the base panel reads \"Installing… 33% Understanding meaning…\" with installed/repair words during a first install; (2) the base's first tab is \"AI Brain\". The base's genuine idle Repair and its Install, Pause and Cancel flows are behaviour the base already had; they are preservation checks in BrainOverview.preservation.test.ts (X8). Elements are found by what the base page draws too: the steps by .path-step[data-feature] (.files-step, .read-step), switches by role \"switch\" and accessible name \"Use <step name>\", tabs by role \"tab\", headings by .brain-headline, buttons by their labels and panels by their test ids; lit is read from data-lit on the step or, before slice 7a, data-active on its symbol (X8). Isolation: Not isolated (a test-owned file, brainPage.test.ts, is deleted in the same diff), and repository-wide scanners read it as a tracked source: scripts/governance/gates/test-intent/units.test.mjs:775 (scanTestOnlyImports, test-support.mjs:188, reads every tracked file under modules/ui-web); scripts/ci/check-liveness-constants-single-authority.mjs (reads every .ts under modules/ui-web/src, tests included); scripts/ci/check-runtime-manifest-closure.mjs (git ls-files, glob modules/ui-web/src/**/*.{ts,tsx,js,mjs}). scripts/ci/classify-changes.test.mjs lists it among tracked paths for folder expansion but reads only Java tests, build scripts and scripts as content. Other ui-web scanners under scripts/ci skip *.test.ts. It changes no global state: developer tools, navigator.platform, stubbed fetch and seeded catalogs are reset in afterEach/beforeEach.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM4 Minimal A2 presentation guard moves into 7a: while an install runs, idle missing-file Repair and \"already installed\" wording are suppressed and a first running install is not projected as installed; genuine idle Repair stays. Transport/install repairs stay 7b.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM1 (D1) Tab row condition is \"answers work\", defined explicitly from the answer runtime (installed answer model, answers not explicitly off, runtime observed ready or startable/unloaded-but-startable, CPU operation counts), NOT the composer's document/GPU gate; Ask behaviour itself unchanged. Stale/unknown observation keeps the last shown state (no flicker). Tests: ready with zero files, unloaded-but-startable, CPU, stale.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D3 Heading says what works: installed with working meaning search and answers unavailable -> a \"Search understands meaning\" style heading (as fm-13), never \"AI Offline\"/\"Installed with limitations\"; answers unavailable said once under its step in ordinary words. Genuine failure/repair/policy headlines stay.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "AI isn't installed yet / Search already works with exact words. Installing AI adds the steps that are not lit yet. / Install AI · 2.6 GB / You see each licence before anything downloads. Everything runs on this computer. / Text in scans and images: Simple scans are read already / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch on, Included / Answer questions: Needs an NVIDIA graphics card (drawn not reachable). Lit: Your files, Read the text; no tab row; no links row.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-10-ai-not-installed (tmp/design/fm-10-ai-not-installed.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "owner-words",
          "quote": "AI Brain on a computer without an NVIDIA card (most computers). The sentence says only what the picture cannot; each fact stands once (the card under its step, sizes under theirs). A switch exists only under its step, with its state in words. What can't work here is said once, in ordinary words, and drawn as not reachable. Only the steps that work now are lit; the Install button is the one filled action.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X8 (2026-11-15): Tests: ladder audit uses the repository ladder rule exactly (no rounding beyond the check's own rule, all four corners); new checks' fail-before must come from the behaviour (query by role/name/text that exists on main, not new-only markup); literal expected wording instead of reasonFor; restore the cleared-after-recovery assertion."
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-brain.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.install.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.layout-lockdown.test.ts"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "behaviour": "AI Brain's Overview in real headless Chromium with isolated fixture responses (private Vite on port 0, no Engine, no shared stack, CUDA hidden) for the fm-10, fm-13 and fm-14 states, light and dark, at 1024x700, 600 px and 200% zoom; the audit's own reach; the developer-tools feature panel; keyboard operation of a step switch; and the Brain template source free of style= attributes. Expectations and their sources: (1) per picture, theme and size: more than 10 text elements, no horizontal scroll of .brain-scroll, no clipped or off-screen text, and the repository ladder rule exactly as modules/ui-web/e2e/type-ladder-chromium.mjs applies it at the base - every element's four corners, each axis 50% or a px value in [0, 4, 8, 14, 9999]; rendered text and generated ::before/::after text at a size in [11, 12, 13, 15, 20, 28] px compared unrounded (parseFloat); text at 15 px or less without the display optical size and 20/28 px text with opsz 32 - the owner's rule \"From slice 2 on: no text size outside the six and no radius outside the four, proven by the check, not by eye.\" (W3) applied with the repository check's own rule (X8: no extra rounding, all four corners). The three states are the three pictures. (2) the audit itself: a probe paragraph at 12.4 px with a 7 px top-right corner inside the Brain surface is reported exactly once on each ladder (X8; it shows the audit can fail on the two defects the acceptor named). (3) developer tools on: the reranker step opener opens one feature panel whose text and corners stay on the same ladders, dark and light - D5/AM5. (4) the reranker switch in fm-14 toggles off with Space and on with Enter and is not left disabled - W3 \"Space and Enter toggle a switch (S7a-4)\", D2 and AM2 (the switch shows the saved choice). (5) BrainSurface.ts contains no style= template attribute - owner words \"In the app, never set style= on an element: the desktop window drops it.\" (a preservation guard; it does not cover .style= property bindings). The fixture statuses carry embedding.compatState COMPATIBLE (X1). The page is ready when its Answer questions step (.path-step[data-feature=\"chat\"]) is drawn, which the base draws too. Fail-before (origin/main 8a312d1484): the six fm-13 cases fail by assertion on the clipping check (the base clips the picture switch's blocked reason \"Text in scans and images is un…\" in the sentence and in the path: span#brain-sentence-pictures-reason.switch-reason and span#brain-path-pictures-reason.switch-reason); the keyboard case fails by assertion (after Space the base switch still reads aria-checked \"true\": it draws the running feature, not the saved choice). The fm-10 and fm-14 cases, the audit self-check, the two developer-tools panel cases and the style= guard pass on the base: the base page already kept those states on the ladders, so these cases guard the candidate rather than show a repair. Needs headless Chromium (PLAYWRIGHT_BROWSERS_PATH from tmp/ui-checks); it ran in this sandbox. Elements are found by what the base page draws too: the steps by .path-step[data-feature] (.files-step, .read-step), switches by role \"switch\" and accessible name \"Use <step name>\", tabs by role \"tab\", headings by .brain-headline, buttons by their labels and panels by their test ids; lit is read from data-lit on the step or, before slice 7a, data-active on its symbol (X8). Isolation: Not isolated (a test-owned file, brainPage.test.ts, is deleted in the same diff), and repository-wide scanners read it as a tracked source: scripts/governance/gates/test-intent/units.test.mjs:775 (scanTestOnlyImports, test-support.mjs:188, reads every tracked file under modules/ui-web); scripts/ci/check-liveness-constants-single-authority.mjs (reads every .ts under modules/ui-web/src, tests included); scripts/ci/check-runtime-manifest-closure.mjs (git ls-files, glob modules/ui-web/src/**/*.{ts,tsx,js,mjs}). scripts/ci/classify-changes.test.mjs lists it among tracked paths for folder expansion but reads only Java tests, build scripts and scripts as content. Other ui-web scanners under scripts/ci skip *.test.ts. It changes no global state: developer tools, navigator.platform, stubbed fetch and seeded catalogs are reset in afterEach/beforeEach.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "AI isn't installed yet / Search already works with exact words. Installing AI adds the steps that are not lit yet. / Install AI · 2.6 GB / You see each licence before anything downloads. Everything runs on this computer. / Text in scans and images: Simple scans are read already / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch on, Included / Answer questions: Needs an NVIDIA graphics card (drawn not reachable). Lit: Your files, Read the text; no tab row; no links row.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-10-ai-not-installed (tmp/design/fm-10-ai-not-installed.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "owner-words",
          "quote": "Search understands meaning / It finds files by what they are about, not only by their words. / Text in scans and images: Simple scans are read already / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch off, Off / Answer questions: Needs an NVIDIA graphics card (drawn not reachable) / Advanced settings / Remove AI models. Lit: Your files, Read the text, Understand meaning; no tab row; no graphics card.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-13-ai-installed-no-card (tmp/design/fm-13-ai-installed-no-card.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "owner-words",
          "quote": "Overview | Memory / AI is ready / Everything below runs on this computer. / Text in scans and images: 2.1 GB, switch off, Off / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch on, On / Answer questions: 5.7 GB, switch on, On / Graphics card: 5.8 of 12 GB in use · RTX 4070 / Advanced settings / Engine history in Health. All five path steps lit.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-14-ai-ready (tmp/design/fm-14-ai-ready.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "In the app, never set style= on an element: the desktop window drops it.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D5 Not in the pictures -> behind Settings > Advanced > Show developer tools (or Advanced): feature disclosure \"You get / It costs\", Start/Stop answers, Refresh status icon. Graphics card meter only where pictured (card present). Advanced settings and Engine history stay as in fm-14. Exceptional states (policy errors, compatibility/rebuild warnings, enrichment progress, genuine Repair) keep their actionable words; technical detail behind a disclosure.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM5 (D5) Unpictured controls: either removed (named) or behind Settings > Advanced > Show developer tools (live preference change); Brain's own Advanced keeps only what fm-14 shows plus AM6 routes; no \"or Advanced\" dumping ground.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "From slice 2 on: no text size outside the six and no radius outside the four, proven by the check, not by eye.",
          "location": "task t-20261113-fmimpl agreement, adopted design handover 'Done, per slice'",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W3 (2026-11-15): Space and Enter toggle a switch (S7a-4); ladder sizes/radii from the owner's \"no text size outside the six and no radius outside the four\".",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X1 (2026-11-15): Meaning positive only when vector queries are live-compatible (COMPATIBLE); REBUILDING shows the rebuilding state in read words, Meaning unlit."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X8 (2026-11-15): Tests: ladder audit uses the repository ladder rule exactly (no rounding beyond the check's own rule, all four corners); new checks' fail-before must come from the behaviour (query by role/name/text that exists on main, not new-only markup); literal expected wording instead of reasonFor; restore the cleared-after-recovery assertion."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM2 (D2) Switch projection from stored selection only (never runtime): saved Off/runtime On shows Off; saved On/runtime Off shows On with the step's words saying it is not working; unknown settings show \"Not known\" (not an unchecked switch) with the existing refresh route; unset answers show automatic without writing a default; operator overrides explained. Existing picture tests encoding the old drawing change (listed).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-brain.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.layout-lockdown.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.placement.test.ts"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "behaviour": "What the pictures do not show sits behind Settings › Advanced › Show developer tools, live, including Brain's own Advanced legacy panels, and the fm-10 page offers no Advanced settings link without a recovery need. Expectations and their sources: (1) without developer tools there is no Stop/Start answers, no Refresh status and no step or phrase opener; switching developer tools on shows Stop answers, Refresh status and the reranker step opener, whose panel says \"You get\" and \"It costs\"; switching them off removes Stop answers and the panel - D5/AM5, owner words \"What a picture does not show ... goes behind Settings › Advanced › Show developer tools, or away.\" (2) fm-14's links row holds exactly \"Advanced settings\" and \"Engine history in Health\" - fm-14 picture words, D5. (3) X6: with Advanced settings open and developer tools off, the component list, the tier breakdown, the package history and the observed search features are absent while Downloaded files stays; developer tools on, live, shows all four; off again hides them - X6 (AM5 \"Brain's own Advanced keeps only what fm-14 shows plus AM6 routes\"). (4) fm-10 without a recovery need has no Advanced settings link - fm-10 picture words. Fail-before (origin/main 8a312d1484): all four cases fail by assertion on that behaviour: the base offers Stop answers by default; its links row has four entries; its Advanced shows legacy panels without developer tools (the component list and the tier breakdown among them); its fm-10 page has an Advanced settings link. The recovery routes that work without developer tools (AM6) are behaviour the base already had; they are preservation checks in BrainOverview.preservation.test.ts (X8). Elements are found by what the base page draws too: the steps by .path-step[data-feature] (.files-step, .read-step), switches by role \"switch\" and accessible name \"Use <step name>\", tabs by role \"tab\", headings by .brain-headline, buttons by their labels and panels by their test ids; lit is read from data-lit on the step or, before slice 7a, data-active on its symbol (X8). Isolation: Not isolated (a test-owned file, brainPage.test.ts, is deleted in the same diff), and repository-wide scanners read it as a tracked source: scripts/governance/gates/test-intent/units.test.mjs:775 (scanTestOnlyImports, test-support.mjs:188, reads every tracked file under modules/ui-web); scripts/ci/check-liveness-constants-single-authority.mjs (reads every .ts under modules/ui-web/src, tests included); scripts/ci/check-runtime-manifest-closure.mjs (git ls-files, glob modules/ui-web/src/**/*.{ts,tsx,js,mjs}). scripts/ci/classify-changes.test.mjs lists it among tracked paths for folder expansion but reads only Java tests, build scripts and scripts as content. Other ui-web scanners under scripts/ci skip *.test.ts. It changes no global state: developer tools, navigator.platform, stubbed fetch and seeded catalogs are reset in afterEach/beforeEach.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "AI isn't installed yet / Search already works with exact words. Installing AI adds the steps that are not lit yet. / Install AI · 2.6 GB / You see each licence before anything downloads. Everything runs on this computer. / Text in scans and images: Simple scans are read already / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch on, Included / Answer questions: Needs an NVIDIA graphics card (drawn not reachable). Lit: Your files, Read the text; no tab row; no links row.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-10-ai-not-installed (tmp/design/fm-10-ai-not-installed.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "owner-words",
          "quote": "Search understands meaning / It finds files by what they are about, not only by their words. / Text in scans and images: Simple scans are read already / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch off, Off / Answer questions: Needs an NVIDIA graphics card (drawn not reachable) / Advanced settings / Remove AI models. Lit: Your files, Read the text, Understand meaning; no tab row; no graphics card.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-13-ai-installed-no-card (tmp/design/fm-13-ai-installed-no-card.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "owner-words",
          "quote": "Overview | Memory / AI is ready / Everything below runs on this computer. / Text in scans and images: 2.1 GB, switch off, Off / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch on, On / Answer questions: 5.7 GB, switch on, On / Graphics card: 5.8 of 12 GB in use · RTX 4070 / Advanced settings / Engine history in Health. All five path steps lit.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-14-ai-ready (tmp/design/fm-14-ai-ready.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D5 Not in the pictures -> behind Settings > Advanced > Show developer tools (or Advanced): feature disclosure \"You get / It costs\", Start/Stop answers, Refresh status icon. Graphics card meter only where pictured (card present). Advanced settings and Engine history stay as in fm-14. Exceptional states (policy errors, compatibility/rebuild warnings, enrichment progress, genuine Repair) keep their actionable words; technical detail behind a disclosure.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM5 (D5) Unpictured controls: either removed (named) or behind Settings > Advanced > Show developer tools (live preference change); Brain's own Advanced keeps only what fm-14 shows plus AM6 routes; no \"or Advanced\" dumping ground.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM6 Recovery routes stay reachable without developer tools: pack import when downloads are disabled, settings refresh for unknown choices, manual repair URLs/destinations, removal refusals/interrupted outcomes, Settings \"Reading memory\" link opens and focuses its control.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X6 (2026-11-15): Brain Advanced legacy panels (component list, tier breakdown, package history, observed ONNX features) behind the live developer-tools preference; AM6 routes stay."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X8 (2026-11-15): Tests: ladder audit uses the repository ladder rule exactly (no rounding beyond the check's own rule, all four corners); new checks' fail-before must come from the behaviour (query by role/name/text that exists on main, not new-only markup); literal expected wording instead of reasonFor; restore the cleared-after-recovery assertion."
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-brain.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.placement.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.steps.test.ts"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "behaviour": "AI Brain's Overview as drawn in fm-10 and fm-13: the heading says what works, each fact stands once, only working steps are lit, sizes keep what they measure, Brain says read and answers words, and the heading agrees with the status panel. Expectations and their sources (picture words quoted in the sources): fm-10 heading, sentence, one filled action \"Install AI · 2.51 GB\", the licence line, lit steps (files, read only), \"8 files\", \"Built in\", \"Simple scans are read already\", sizes \"1.20 GB download\" and \"341.0 MB download\" (literal text: the fixture bytes in the app's existing size display, W4), the Included switch and its words \"Included\"/\"Not included\" (W1), the answer step drawn as not reachable with \"Needs an NVIDIA graphics card\" once and no CUDA words (fm-10, D4), the Linux exclusion said once without the planner's words (AM10 a); Included changes only the decline and the plan total (\"Install AI · 2.18 GB\"), across page return and restart (D2, existing handler); fm-13 heading \"Search understands meaning\" with its sentence, Off drawn as off, no tab row, no graphics meter, no filled action (fm-13, D3); a lost observation said once by the heading with an unknown choice \"Not known\" (W5, W1); no positive heading and meaning unlit for an inactive or unknown encoder, for embeddings being read again (REBUILDING: the heading says it in read words, the panel has no index/rebuild/semantic/embedding words) and for an unreported compatibility (X1, AM3); activation required keeps its heading, meaning unlit; incompatible embeddings keep the read-again remedy without index words; answers working while meaning does not makes no positive claim (AM3); package sizes labelled as downloads, never as disk use, no leading separators (AM8, D4); no answer size for a compact model, a saved custom path, or an operator's model whose runtime names no profile (X5, AM8); one kept-files wording at most and no CUDA words (D4); read and answers words for the waiting, off, paused and read-again states (AM9); X7: for each populated reason code (index.embedding_mismatch, index.embedding_legacy, index.blocked_legacy, index.rebuild_brake_exhausted, index.rebuild_brake_unreadable, index.schema_mismatch) the callout has one heading in read words without index/rebuild/semantic/embedding words - exactly \"Read everything again to restore full search.\" (W1) for the three embedding causes - and its rebuild action, rendered through the real catalog button from the served registry-operation.en.properties, reads \"Rebuild all search data\" (X7, slice 6a's label); the words of the other three causes are an agent choice under X7, so only their meaning is asserted; the positive heading never stands beside a search problem and the install-failed heading equals the status row's title and reads the base words \"AI install failed\" (AM9 page/status coherence; D3 genuine failure headlines stay). The fixture status carries embedding.compatState COMPATIBLE where meaning works (X1); the size case's runtime names the \"standard\" profile (X5). Fail-before (origin/main 8a312d1484): every case fails by assertion on its claimed behaviour - the base headings \"AI Offline\", \"Rebuild search to use these models\", \"AI is ready\" beside a non-working meaning step, its \"Indexing …\" and canonical index/rebuild words, its sentence, its planner and CUDA words, its \"· 1.20 GB registry footprint · needed\" size and its 5.70 GB answer size for custom models, its per-step \"Unknown — checking AI status\", its missing state words (\"Included\"), and its lit meaning step while activation is required (data-active \"true\"). The repair heading while meaning's files are missing holds on the base; it is a preservation check in BrainOverview.preservation.test.ts (X8). Elements are found by what the base page draws too: the steps by .path-step[data-feature] (.files-step, .read-step), switches by role \"switch\" and accessible name \"Use <step name>\", tabs by role \"tab\", headings by .brain-headline, buttons by their labels and panels by their test ids; lit is read from data-lit on the step or, before slice 7a, data-active on its symbol (X8). Isolation: Not isolated (a test-owned file, brainPage.test.ts, is deleted in the same diff), and repository-wide scanners read it as a tracked source: scripts/governance/gates/test-intent/units.test.mjs:775 (scanTestOnlyImports, test-support.mjs:188, reads every tracked file under modules/ui-web); scripts/ci/check-liveness-constants-single-authority.mjs (reads every .ts under modules/ui-web/src, tests included); scripts/ci/check-runtime-manifest-closure.mjs (git ls-files, glob modules/ui-web/src/**/*.{ts,tsx,js,mjs}). scripts/ci/classify-changes.test.mjs lists it among tracked paths for folder expansion but reads only Java tests, build scripts and scripts as content. Other ui-web scanners under scripts/ci skip *.test.ts. It changes no global state: developer tools, navigator.platform, stubbed fetch and seeded catalogs are reset in afterEach/beforeEach.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "AI isn't installed yet / Search already works with exact words. Installing AI adds the steps that are not lit yet. / Install AI · 2.6 GB / You see each licence before anything downloads. Everything runs on this computer. / Text in scans and images: Simple scans are read already / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch on, Included / Answer questions: Needs an NVIDIA graphics card (drawn not reachable). Lit: Your files, Read the text; no tab row; no links row.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-10-ai-not-installed (tmp/design/fm-10-ai-not-installed.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "owner-words",
          "quote": "Search understands meaning / It finds files by what they are about, not only by their words. / Text in scans and images: Simple scans are read already / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch off, Off / Answer questions: Needs an NVIDIA graphics card (drawn not reachable) / Advanced settings / Remove AI models. Lit: Your files, Read the text, Understand meaning; no tab row; no graphics card.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-13-ai-installed-no-card (tmp/design/fm-13-ai-installed-no-card.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "owner-words",
          "quote": "AI Brain on a computer without an NVIDIA card (most computers). The sentence says only what the picture cannot; each fact stands once (the card under its step, sizes under theirs). A switch exists only under its step, with its state in words. What can't work here is said once, in ordinary words, and drawn as not reachable. Only the steps that work now are lit; the Install button is the one filled action.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Installed, no NVIDIA card. The headline says what now works, never \"AI Offline\" or \"Installed with limitations\". An off switch reads \"Off\" and is drawn off.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "No tab row until answers work, then \"Overview | Memory\". The sentence has no switches; each switch sits under its step with its state in words.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "The \"Included\" switch for the optional part before installing: a per-part decline exists on main (BrainSurface.ts: declined, 'user-declined'), so the switch can sit on what is already saved.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Every fact has one home on a screen. ... The same thing has one name everywhere: the app reads files (not \"indexes\")",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Anything a picture shows that the app has no data for: build the screen without it and name it in the delivery. Never fill it with a made-up value.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D2 Sentence has no switches; each switch sits under its step with its state in words; existing handlers and meanings only (pre-install Included = package decline; Best match first = rerankerEnabled/intent; Answer questions = nullable chatEnabled, unset stays unset; Text in scans and images = pictureReadingEnabled). Switch shows stored selection; the step's lit/unlit and state words show what works now (selection never presented as activity).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D3 Heading says what works: installed with working meaning search and answers unavailable -> a \"Search understands meaning\" style heading (as fm-13), never \"AI Offline\"/\"Installed with limitations\"; answers unavailable said once under its step in ordinary words. Genuine failure/repair/policy headlines stay.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D4 One fact once: card under its step, sizes under theirs; no \"registry footprint\", no leading separators, one kept-files wording; the CUDA reason once.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM2 (D2) Switch projection from stored selection only (never runtime): saved Off/runtime On shows Off; saved On/runtime Off shows On with the step's words saying it is not working; unknown settings show \"Not known\" (not an unchecked switch) with the existing refresh route; unset answers show automatic without writing a default; operator overrides explained. Existing picture tests encoding the old drawing change (listed).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM3 (D3) Positive \"Search understands meaning\" heading only with live meaning evidence; embedding absent/inactive/unknown, activation required, incompatible embeddings, answers-working-but-meaning-not keep their remedy headline; unknown -> neutral headline.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM8 (D4) Sizes keep what they measure: package download size labelled as download, never as measured disk use; omitted for compact/custom models unless a matching trustworthy value exists (omission named). Graphics meter only with a current reading; never infer \"no card\" from a missing meter; installing/unsupported/insufficient-memory/unknown settled explicitly.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM9 Brain wording in 7a: \"indexing\", \"Resume Chat AI\", \"Rebuild the index\" and similar on Brain's default and exceptional states move to read/answers words; page/status coherence for Brain states (S7-25 subset); verdict ids unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM10 Reachability legs separate: (a) Linux platform exclusion of answers (reachable here), (b) hardware no-card (sandbox proof or unverified), (c) fm-14 only with real answers, picture-reader and GPU observations.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W1 (2026-11-15): State words and notes adopted verbatim (mine): switch state words \"On\", \"Off\", \"Not known\", \"Included\", \"Not included\", \"Changing…\"; notes \"Checking after an interrupted change\", \"Not confirmed yet\", \"Set by an operator override\", \"Not working — its files are missing\", \"Not working now\", \"Not available with this answer model\", \"Off is not applied yet\"; exceptional headlines/sentences \"Answers paused while files are read\" + \"Answers resume when JustSearch finishes reading the files.\", \"Reading text in scans and images\" + \"Answers resume after the current document.\", \"Read everything again to restore full search.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W4 (2026-11-15): Expected sizes in tests are literal text, not computed with formatBytes; the two lost preservation checks come back (picture reading \"when last observed\" wording; Answer questions unlit when AI installed but answer model not downloaded).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W5 (2026-11-15T19:40Z): Restore last-known truth: when the AI observation is lost while pictures were being read, the page says once, in its sentence, \"Pictures were being read when last observed; current answer availability is unconfirmed.\" (main's wording, state/brainPage.ts:38/48 at 8a312d1484); per-step 'Unknown — checking AI status' is not repeated as a separate fact on every step when the page already says it once (fm-10 'each fact stands once').",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X1 (2026-11-15): Meaning positive only when vector queries are live-compatible (COMPATIBLE); REBUILDING shows the rebuilding state in read words, Meaning unlit."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X5 (2026-11-15): Model size shown only with a trustworthy matching package value for the observed model; custom/unknown -> no size."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X7 (2026-11-15): Populated compatibility reason codes and the rebuild action use read words (\"Rebuild all search data\", as slice 6a); tested on populated paths."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X8 (2026-11-15): Tests: ladder audit uses the repository ladder rule exactly (no rounding beyond the check's own rule, all four corners); new checks' fail-before must come from the behaviour (query by role/name/text that exists on main, not new-only markup); literal expected wording instead of reasonFor; restore the cleared-after-recovery assertion."
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-brain.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.steps.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.switches.test.ts"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "behaviour": "Each switch sits under its step and shows the stored choice only; the step's light and words show what works now. Expectations and their sources: one switch per choice (Best match first, Answer questions, Text in scans and images) under its step, none in the sentence, each with its state in words \"On\" (D2, W1); saved Off while the runtime runs it: unchecked, \"Off\", lit; saved On while the runtime does not: checked, \"On\", unlit, note \"not working\" (AM2); unknown saved choices: no unchecked switch, \"Not known\", and the Refresh status route reads them again (AM2, AM6); unset answers read \"Automatic\" and nothing is written (AM2); X2: an unset picture choice with the answer model's files missing is checked, reads \"On\", says \"Not working — its files are missing\" and writes nothing (X2, W2, W1); an operator override locks the switch at the override, is described as an operator's, reads \"Off\" (AM2, W1); an interrupted change reads \"Not known\" until checked (W1); a pending change stays locked as \"Changing…\" across navigation (W1); another window's saved choice arrives and a delayed older read does not undo it, the switch showing the saved choice rather than the running feature (AM2). Fail-before (origin/main 8a312d1484): every case fails by assertion on its claimed behaviour - the base draws two switches per choice (sentence and path), draws the running feature rather than the saved choice (aria-checked \"true\" for a saved or overridden Off and after another window's Off), draws an unchecked switch for an unknown choice, draws an unset picture choice Off when the answer files are missing, and has no state words (\"\" for On, Automatic, Not known and Changing…). Elements are found by what the base page draws too: the steps by .path-step[data-feature] (.files-step, .read-step), switches by role \"switch\" and accessible name \"Use <step name>\", tabs by role \"tab\", headings by .brain-headline, buttons by their labels and panels by their test ids; lit is read from data-lit on the step or, before slice 7a, data-active on its symbol (X8). Isolation: Not isolated (a test-owned file, brainPage.test.ts, is deleted in the same diff), and repository-wide scanners read it as a tracked source: scripts/governance/gates/test-intent/units.test.mjs:775 (scanTestOnlyImports, test-support.mjs:188, reads every tracked file under modules/ui-web); scripts/ci/check-liveness-constants-single-authority.mjs (reads every .ts under modules/ui-web/src, tests included); scripts/ci/check-runtime-manifest-closure.mjs (git ls-files, glob modules/ui-web/src/**/*.{ts,tsx,js,mjs}). scripts/ci/classify-changes.test.mjs lists it among tracked paths for folder expansion but reads only Java tests, build scripts and scripts as content. Other ui-web scanners under scripts/ci skip *.test.ts. It changes no global state: developer tools, navigator.platform, stubbed fetch and seeded catalogs are reset in afterEach/beforeEach.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "No tab row until answers work, then \"Overview | Memory\". The sentence has no switches; each switch sits under its step with its state in words.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Installed, no NVIDIA card. The headline says what now works, never \"AI Offline\" or \"Installed with limitations\". An off switch reads \"Off\" and is drawn off.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Overview | Memory / AI is ready / Everything below runs on this computer. / Text in scans and images: 2.1 GB, switch off, Off / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch on, On / Answer questions: 5.7 GB, switch on, On / Graphics card: 5.8 of 12 GB in use · RTX 4070 / Advanced settings / Engine history in Health. All five path steps lit.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-14-ai-ready (tmp/design/fm-14-ai-ready.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D2 Sentence has no switches; each switch sits under its step with its state in words; existing handlers and meanings only (pre-install Included = package decline; Best match first = rerankerEnabled/intent; Answer questions = nullable chatEnabled, unset stays unset; Text in scans and images = pictureReadingEnabled). Switch shows stored selection; the step's lit/unlit and state words show what works now (selection never presented as activity).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM2 (D2) Switch projection from stored selection only (never runtime): saved Off/runtime On shows Off; saved On/runtime Off shows On with the step's words saying it is not working; unknown settings show \"Not known\" (not an unchecked switch) with the existing refresh route; unset answers show automatic without writing a default; operator overrides explained. Existing picture tests encoding the old drawing change (listed).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM6 Recovery routes stay reachable without developer tools: pack import when downloads are disabled, settings refresh for unknown choices, manual repair URLs/destinations, removal refusals/interrupted outcomes, Settings \"Reading memory\" link opens and focuses its control.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision: two test fixture operation keys are replaced with '01993ba0-0000-7000-8000-000000000002' (secret-scanner false positive; fixture value only).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W1 (2026-11-15): State words and notes adopted verbatim (mine): switch state words \"On\", \"Off\", \"Not known\", \"Included\", \"Not included\", \"Changing…\"; notes \"Checking after an interrupted change\", \"Not confirmed yet\", \"Set by an operator override\", \"Not working — its files are missing\", \"Not working now\", \"Not available with this answer model\", \"Off is not applied yet\"; exceptional headlines/sentences \"Answers paused while files are read\" + \"Answers resume when JustSearch finishes reading the files.\", \"Reading text in scans and images\" + \"Answers resume after the current document.\", \"Read everything again to restore full search.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X2 (2026-11-15): Unset picture reading with missing answer files: drawn On (default, W2) with note \"Not working — its files are missing\" (W1); nothing written."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X8 (2026-11-15): Tests: ladder audit uses the repository ladder rule exactly (no rounding beyond the check's own rule, all four corners); new checks' fail-before must come from the behaviour (query by role/name/text that exists on main, not new-only markup); literal expected wording instead of reasonFor; restore the cleared-after-recovery assertion."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W2 (2026-11-15): Unset picture-reading choice is drawn as the Engine's effective default (today On) with nothing written (like unset answers, AM2); its note says why it is not working when it is not.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-brain.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.switches.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.tabs.test.ts"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "behaviour": "AI Brain has a tab row only once answers work here, and then \"Overview | Memory\"; Memory stays reachable by its routes in every answer state. Expectations and their sources: answers working shows \"Overview | Memory\", the graphics card reading \"5.80 GB of 12.00 GB in use · RTX 4070\" (literal, W4), the two pictured links and fm-14's heading and sentence (fm-14, D1); ready with zero files, unloaded but startable, on the processor and starting count as answers work (AM1); X3: unloaded with operating.chat.reloadAllowed false shows no tab row and an unlit Answer questions step, while reloadAllowed true still counts (X3, AM1); answers off, unavailable or failed show no tab row (D1); a stale or unknown observation keeps the row as last shown (AM1); the graphics meter only with a current reading, never \"no card\" or \"Memory use unknown\" without one (AM8); deep link, address tab, Back/Forward and reload open Memory with Overview reachable in every answer state (D1); X4: returning to Overview where the row leaves syncs the address (tab-change detail) and focuses the heading only in the tab host's final drawing - the focus spy records the slot through which the heading is drawn when it takes focus, and that slot must still be connected after the host renders (a slot the host then replaces takes the heading out of the rendered tree, and a browser drops focus with it) (X4, AM7). The fixture statuses carry embedding.compatState COMPATIBLE (X1). Fail-before (origin/main 8a312d1484): every case fails by assertion on its claimed behaviour - the base always shows the row \"AI Brain | Memory\" (first tab not \"Overview\", and a row where none is due), and draws its graphics section for processor answers. Losing readiness while Memory is open, locked Memory and other tab hosts hold on the base; they are preservation checks in BrainOverview.preservation.test.ts (X8). Elements are found by what the base page draws too: the steps by .path-step[data-feature] (.files-step, .read-step), switches by role \"switch\" and accessible name \"Use <step name>\", tabs by role \"tab\", headings by .brain-headline, buttons by their labels and panels by their test ids; lit is read from data-lit on the step or, before slice 7a, data-active on its symbol (X8). Isolation: Not isolated (a test-owned file, brainPage.test.ts, is deleted in the same diff), and repository-wide scanners read it as a tracked source: scripts/governance/gates/test-intent/units.test.mjs:775 (scanTestOnlyImports, test-support.mjs:188, reads every tracked file under modules/ui-web); scripts/ci/check-liveness-constants-single-authority.mjs (reads every .ts under modules/ui-web/src, tests included); scripts/ci/check-runtime-manifest-closure.mjs (git ls-files, glob modules/ui-web/src/**/*.{ts,tsx,js,mjs}). scripts/ci/classify-changes.test.mjs lists it among tracked paths for folder expansion but reads only Java tests, build scripts and scripts as content. Other ui-web scanners under scripts/ci skip *.test.ts. It changes no global state: developer tools, navigator.platform, stubbed fetch and seeded catalogs are reset in afterEach/beforeEach.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Installed on a computer with an NVIDIA card. Only now is there a second tab (Memory), so only now a tab row; its first tab is \"Overview\", not the area's name again.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Overview | Memory / AI is ready / Everything below runs on this computer. / Text in scans and images: 2.1 GB, switch off, Off / Your files: 8 files / Read the text: Built in / Understand meaning: 1.2 GB / Best match first: 341 MB, switch on, On / Answer questions: 5.7 GB, switch on, On / Graphics card: 5.8 of 12 GB in use · RTX 4070 / Advanced settings / Engine history in Health. All five path steps lit.",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-14-ai-ready (tmp/design/fm-14-ai-ready.html; the pictures are not in git, the words are quoted here)",
          "label": "owner-adopted picture words"
        },
        {
          "kind": "owner-words",
          "quote": "No tab row until answers work, then \"Overview | Memory\". The sentence has no switches; each switch sits under its step with its state in words.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Anything a picture shows that the app has no data for: build the screen without it and name it in the delivery. Never fill it with a made-up value.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D1 Tab row hidden until answers work (same readiness as slice 4's Search / Ask); then \"Overview | Memory\". Explicit Memory navigation (justsearch://surface/core.memory-surface, ?tab=core.memory-surface, palette \"Open AI Brain › Memory\", Back/Forward, reload) still opens Memory and then shows the tab row so Overview is reachable; Memory facts stay readable/forgettable whatever the answer state.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM1 (D1) Tab row condition is \"answers work\", defined explicitly from the answer runtime (installed answer model, answers not explicitly off, runtime observed ready or startable/unloaded-but-startable, CPU operation counts), NOT the composer's document/GPU gate; Ask behaviour itself unchanged. Stale/unknown observation keeps the last shown state (no flicker). Tests: ready with zero files, unloaded-but-startable, CPU, stale.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM7 Memory transitions: losing readiness while Memory is open keeps it mounted with a draft, a Forget confirmation and an unresolved Forget response; address and focus sync when returning to Overview and the row disappears; locked/unreadable Memory distinct from empty; other SurfaceTabs hosts unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM8 (D4) Sizes keep what they measure: package download size labelled as download, never as measured disk use; omitted for compact/custom models unless a matching trustworthy value exists (omission named). Graphics meter only with a current reading; never infer \"no card\" from a missing meter; installing/unsupported/insufficient-memory/unknown settled explicitly.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W4 (2026-11-15): Expected sizes in tests are literal text, not computed with formatBytes; the two lost preservation checks come back (picture reading \"when last observed\" wording; Answer questions unlit when AI installed but answer model not downloaded).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X1 (2026-11-15): Meaning positive only when vector queries are live-compatible (COMPATIBLE); REBUILDING shows the rebuilding state in read words, Meaning unlit."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X3 (2026-11-15): \"Answers work\" (AM1) excludes unloaded states whose reload is not allowed (operating.chat.reloadAllowed=false): no tab row, Answer questions unlit."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X4 (2026-11-15): Returning from Memory without readiness: restore focus after SurfaceTabs has rendered (await update, with navigation/connection guards)."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X8 (2026-11-15): Tests: ladder audit uses the repository ladder rule exactly (no rounding beyond the check's own rule, all four corners); new checks' fail-before must come from the behaviour (query by role/name/text that exists on main, not new-only markup); literal expected wording instead of reasonFor; restore the cleared-after-recovery assertion."
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-brain.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.tabs.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.preservation.test.ts"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "behaviour": "Behaviours the AI Brain page had before the first-minutes Overview and keeps after it, checked as preservation (not repaired: every case passes on origin/main 8a312d1484 and on the candidate). (a) While pictures are being read and the observation is then lost, the page says \"Pictures were being read when last observed\" (not while live) - W4, W5 (main's wording, state/brainPage.ts:38/48 at 8a312d1484). (b) With AI installed but the answer model not downloaded, the Answer questions step exists, carries its name and is not lit - W4. (c) The page's install flows: a genuine idle missing file heads \"Installed — repair available\" and Repair runs core.repair-ai-install after one confirmation (AM4 \"genuine idle Repair stays\"; D3 \"Genuine failure/repair/policy headlines stay\"); meaning's files missing keeps that heading with meaning unlit (AM3 keeps the remedy headline); Install AI opens the licences, Accept and download runs core.start-ai-install, Pause download posts /install/pause and Cancel runs core.cancel-ai-install (D2 \"existing handlers\"). (d) AM6 recovery routes without developer tools: pack import (\"Import an offline package\") under Advanced settings when downloads are disabled before install; manual repair links and destinations (the package URL and target path) on the page; an interrupted removal outcome (\"The removal was interrupted\") under Advanced settings; Settings › Reading memory opens Advanced and focuses #brain-contextWindow. (e) Memory whatever the answer state (D1, AM7): losing readiness keeps the same Memory element mounted with its draft, an open Forget question and a pending Forget that completes as \"Forgotten.\", with both tabs still in the row; locked Memory reads \"What the AI has learned is encrypted and locked\" and never \"No learned memory yet.\" - the literal is today's locked wording (readinessNotice.ts memory.locked at the base, unchanged by this change), replacing the reasonFor oracle (X8); another tab host keeps its row \"First | Second\". These cases moved here from the install, placement, steps and tabs checks, without the slice 7a markup guards that made them fail on the base for absent markup (X8). Evidence: gates/test-intent/evidence/fm-brain-preservation.json, run-evidence with --before origin/main; its fail-before records \"passed\" for this file, which is what a preservation check shows; repaired is false, so no fail-before assertion is required. Elements are found by what the base page draws too: the steps by .path-step[data-feature] (.files-step, .read-step), switches by role \"switch\" and accessible name \"Use <step name>\", tabs by role \"tab\", headings by .brain-headline, buttons by their labels and panels by their test ids; lit is read from data-lit on the step or, before slice 7a, data-active on its symbol (X8). Isolation: Not isolated (a test-owned file, brainPage.test.ts, is deleted in the same diff), and repository-wide scanners read it as a tracked source: scripts/governance/gates/test-intent/units.test.mjs:775 (scanTestOnlyImports, test-support.mjs:188, reads every tracked file under modules/ui-web); scripts/ci/check-liveness-constants-single-authority.mjs (reads every .ts under modules/ui-web/src, tests included); scripts/ci/check-runtime-manifest-closure.mjs (git ls-files, glob modules/ui-web/src/**/*.{ts,tsx,js,mjs}). scripts/ci/classify-changes.test.mjs lists it among tracked paths for folder expansion but reads only Java tests, build scripts and scripts as content. Other ui-web scanners under scripts/ci skip *.test.ts. It changes no global state: developer tools, navigator.platform, stubbed fetch and seeded catalogs are reset in afterEach/beforeEach.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W4 (2026-11-15): Expected sizes in tests are literal text, not computed with formatBytes; the two lost preservation checks come back (picture reading \"when last observed\" wording; Answer questions unlit when AI installed but answer model not downloaded).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision W5 (2026-11-15T19:40Z): Restore last-known truth: when the AI observation is lost while pictures were being read, the page says once, in its sentence, \"Pictures were being read when last observed; current answer availability is unconfirmed.\" (main's wording, state/brainPage.ts:38/48 at 8a312d1484); per-step 'Unknown — checking AI status' is not repeated as a separate fact on every step when the page already says it once (fm-10 'each fact stands once').",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "AI Brain on a computer without an NVIDIA card (most computers). The sentence says only what the picture cannot; each fact stands once (the card under its step, sizes under theirs). A switch exists only under its step, with its state in words. What can't work here is said once, in ordinary words, and drawn as not reachable. Only the steps that work now are lit; the Install button is the one filled action.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Installed, no NVIDIA card. The headline says what now works, never \"AI Offline\" or \"Installed with limitations\". An off switch reads \"Off\" and is drawn off.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X8 (2026-11-15): Tests: ladder audit uses the repository ladder rule exactly (no rounding beyond the check's own rule, all four corners); new checks' fail-before must come from the behaviour (query by role/name/text that exists on main, not new-only markup); literal expected wording instead of reasonFor; restore the cleared-after-recovery assertion."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM3 (D3) Positive \"Search understands meaning\" heading only with live meaning evidence; embedding absent/inactive/unknown, activation required, incompatible embeddings, answers-working-but-meaning-not keep their remedy headline; unknown -> neutral headline.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM4 Minimal A2 presentation guard moves into 7a: while an install runs, idle missing-file Repair and \"already installed\" wording are suppressed and a first running install is not projected as installed; genuine idle Repair stays. Transport/install repairs stay 7b.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM6 Recovery routes stay reachable without developer tools: pack import when downloads are disabled, settings refresh for unknown choices, manual repair URLs/destinations, removal refusals/interrupted outcomes, Settings \"Reading memory\" link opens and focuses its control.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM7 Memory transitions: losing readiness while Memory is open keeps it mounted with a draft, a Forget confirmation and an unresolved Forget response; address and focus sync when returning to Overview and the row disappears; locked/unreadable Memory distinct from empty; other SurfaceTabs hosts unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D1 Tab row hidden until answers work (same readiness as slice 4's Search / Ask); then \"Overview | Memory\". Explicit Memory navigation (justsearch://surface/core.memory-surface, ?tab=core.memory-surface, palette \"Open AI Brain › Memory\", Back/Forward, reload) still opens Memory and then shows the tab row so Overview is reachable; Memory facts stay readable/forgettable whatever the answer state.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "D3 Heading says what works: installed with working meaning search and answers unavailable -> a \"Search understands meaning\" style heading (as fm-13), never \"AI Offline\"/\"Installed with limitations\"; answers unavailable said once under its step in ordinary words. Genuine failure/repair/policy headlines stay.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-brain-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.preservation.test.ts"
      ],
      "repaired": false
    },
    {
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "items": [
        "modules/ui-web/src/shell-v0/components/rebuildLabel.test.ts"
      ],
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Every fact has one home on a screen. ... The same thing has one name everywhere: the app reads files (not \"indexes\")",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X7 (2026-11-15): Populated compatibility reason codes and the rebuild action use read words (\"Rebuild all search data\", as slice 6a); tested on populated paths."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM9 Brain wording in 7a: \"indexing\", \"Resume Chat AI\", \"Rebuild the index\" and similar on Brain's default and exceptional states move to read/answers words; page/status coherence for Brain states (S7-25 subset); verdict ids unchanged.",
          "label": "agent choice"
        }
      ],
      "behaviour": "The served rebuild label, visible on the catalog button and its accessible name, is \"Rebuild all search data\"; the typed confirmation stays \"rebuild\".",
      "assertionChanges": [
        {
          "oldAssertion": "messages['ops.rebuild-index.label'] is \"Rebuild search index\" and the rendered button text is \"Rebuild search index\".",
          "newAssertion": "Both are \"Rebuild all search data\"; the confirm word \"rebuild\" and the visible-text-as-name check are unchanged.",
          "reason": "X7: the rebuild action uses read words, \"Rebuild all search data\", as slice 6a; the label lives in the served catalog, which OpButton always reads (it has no label property), so the catalog line changes. Slice 6a made the identical catalog change (commit 791ad9ee76 on worktree-fm-s6, registry-operation.en.properties lines 35-36 and this test's two expectations): the label and description lines here are byte-identical to it, so the two slices merge cleanly."
        }
      ]
    },
    {
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts"
      ],
      "sources": [
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X6 (2026-11-15): Brain Advanced legacy panels (component list, tier breakdown, package history, observed ONNX features) behind the live developer-tools preference; AM6 routes stay."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM5 (D5) Unpictured controls: either removed (named) or behind Settings > Advanced > Show developer tools (live preference change); Brain's own Advanced keeps only what fm-14 shows plus AM6 routes; no \"or Advanced\" dumping ground.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM6 Recovery routes stay reachable without developer tools: pack import when downloads are disabled, settings refresh for unknown choices, manual repair URLs/destinations, removal refusals/interrupted outcomes, Settings \"Reading memory\" link opens and focuses its control.",
          "label": "agent choice"
        }
      ],
      "behaviour": "With stale installed rows, including a part from the component list asks first (opens the consent) instead of withdrawing its decline; the Best match first switch does the same.",
      "assertionChanges": [
        {
          "oldAssertion": "The component-toggle-reranker switch in Advanced is clicked without developer tools; the consent opens and no decline or operation is sent.",
          "newAssertion": "Unchanged expectations, with developer tools switched on first (reset after each case).",
          "reason": "X6 (AM5) puts the component list behind the live developer-tools preference. The same ask-first oracle without developer tools is kept by the step switch case just above it (Best match first). AM6 routes are not in the component list."
        }
      ]
    },
    {
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "conflictSearch": "Decision records searched for a conflict: all 54 records under docs/decisions/ grepped (2026-11-15) for AI Brain, BrainSurface, Memory, tab row, SurfaceTabs, developer tools, picture reading, reranker, chatEnabled, graphics card, type ladder and Overview: none; repeated (2026-11-15) for the X1-X8 terms rebuild-index, Rebuild search index, REBUILDING, compatState, reloadAllowed, pictureReadingEnabled, chatProfile and developer tools: no matches. Matches of the first search only concern runtime and storage (ADR-0001, 0019, 0024, 0047 models/VRAM; ADR-0008 amendment persists chatEnabled, consistent with unset answers staying unset); none states what AI Brain shows. No private records were read.",
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.skipReason.test.ts"
      ],
      "sources": [
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, adopted rules and picture notes",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "label": "agent choice",
          "choice": "Orchestrator decision X6 (2026-11-15): Brain Advanced legacy panels (component list, tier breakdown, package history, observed ONNX features) behind the live developer-tools preference; AM6 routes stay."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "AM5 (D5) Unpictured controls: either removed (named) or behind Settings > Advanced > Show developer tools (live preference change); Brain's own Advanced keeps only what fm-14 shows plus AM6 routes; no \"or Advanced\" dumping ground.",
          "label": "agent choice"
        }
      ],
      "behaviour": "The package history under Advanced names each skipped package's status and reason and replaces a never-downloaded byte count with the status word.",
      "assertionChanges": [
        {
          "oldAssertion": "With Advanced open (Detailed mode), the package history text contains \"Unsupported here\", the hardware reason, \"Declined\", the decline reason and \"114.4 MB\", and no GB size.",
          "newAssertion": "Unchanged expectations, with developer tools switched on in the harness (reset after each case).",
          "reason": "X6 (AM5): the package history is a legacy Advanced panel behind the live developer-tools preference; its skip-reason oracle is kept there."
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
  "session": "01a1e074-6439-71c1-b65f-1796c1e9cd8d",
  "verdict": "reject",
  "digest": "sha256:5de89b147f075a74bac5bd3f5a6411a71a406897cb3ff1bb389881e3ae00b8bb",
  "note": "Reject: repaired install/placement/layout checks fail before on absent data-step markup, not their claimed behavior; moved panel ladder audit rounds font sizes and checks only one corner; locked Memory oracle calls production reasonFor."
}
```
