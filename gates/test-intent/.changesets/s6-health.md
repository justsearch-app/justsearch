---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: s6-health-builder-2-20261111
---

# S6 piece 8 — quiet System and Health

Sources are the owner's delegation for the frame build and the owner's request for this piece; each choice names the settled
piece 8 scenario or amendment (scenarios settled 2026-11-11T12:40Z, decisions 13:00Z). Two new isolated files carry further
fail-before checks and need no entry: `HealthSurface.quiet.test.ts` and `HealthSurface.truthfulness.test.ts`; their checks are
in `gates/test-intent/evidence/s6-health-repaired.json` (and one preservation check in `gates/test-intent/evidence/s6-health-new.json`).
The verifier repair (SH13 row navigation, SH16 reflow, SH7 failure retirement) adds the isolated `HealthSurface.frame-lockdown.test.ts`
and one case in `HealthSurface.quiet.test.ts`; their entries below point at `gates/test-intent/evidence/s6-health-verifier-1.json`
(fail-before on 394467ba1d). The second verifier repair (SH3 per-source ages in Diagnostics, SH14 a section intent kept across
engine loss and recovery) adds the isolated `HealthSurface.freshness-lockdown.test.ts`; its entry points at
`gates/test-intent/evidence/s6-health-verifier-2.json` (fail-before on bf1b6b5688). Its third case, recovery without a pending
intent still moving focus to the main content, is a preservation check and passes on bf1b6b5688 too. The frame check's 900x700 and 1280x720 layout reference was recorded on 394467ba1d. Independent acceptance is pending.

What each Obsolete entry removes:

- CapabilityMap.test.ts: Removed expectation: 'always lists keyword search as available, regardless of AI state' asserted Available for a null (cold-start) state.
- HealthSurface.render.test.ts: Removed expectations: the '.card.section.recommended' verdict footer ('All healthy' / '✓ Up to date' / 'Checking' / 'Connecting' / 'Reduced capability' / 'Service degraded') and the header '.header jf-status-badge' text and tone in six cases ('renders "All healthy" ...', '595 §1.1 header and footer agree ...', '595 §10.3 ... both surfaces', '§2.C shows "Connecting…" ...', 'B7/B2 ... "Service degraded"', '595 §15.2 (E4) ... header ... (warning)'). The footer and the badge no longer exist.
- indexingSurfaceSweep.test.ts: Removed expectation: S33.4 read the hold reason from renderRecommendedActions (the retired verdict footer) and the header's jf-status-badge tone.
- StatusPill.notices.test.ts: Removed expectation: in 'an operation ending across a detach is silent' the operation ended after the window returned, and silence depended on the old serial read chain not reaching the operation before the test changed its answer.
- IndexedRootViewSchemaTest.java: Removed expectation: each coverage field 'must be an integer on the wire' (type exactly "integer").
- CoreSurfaceCatalogTest.java: Removed expectation: systemHubShape pinned the members as Health, Logs, Activity.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/CapabilityMap.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "SH1 and the settled kinds split: 'initial keyword availability' is DELIVER (broken today). With no status observation (cold start) the keyword row says its status is unconfirmed instead of Available; with a live snapshot it stays Available regardless of AI state (the existing 807 §E.4 anti-regression case keeps that).",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell user turn requesting S6 piece 8, quoted in the piece 8 builder brief, 2026-11-11",
          "label": "owner request for this piece"
        }
      ],
      "conflictSearch": "Decision records searched for a conflict: rg -n -i -E 'keyword.*available|Search \\(keyword\\)|System Health|health-surface|Health, Logs|Logs, Activity|inFlightCount|failedCount|indexed-root|IndexedRootView|detach|seenAtGap|recovery.?index|No recoverable|status.?badge|Up to date' docs/decisions. Matches only in ADR-0015 (MCP tool justsearch_status), ADR-0048 (extraction client detach), ADR-0041/0028 (the word 'recommended') and ADR-0044 (CI branch up to date); none governs Health's presentation, the System tab order, folder job counts or notification timing. No conflicting decision found."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/CapabilityMap.test.ts"
      ],
      "class": "New",
      "behaviour": "Cold start (no status observed): the keyword-search row reads unconfirmed, never Available.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "SH1 and the settled kinds split: 'initial keyword availability' is DELIVER (broken today). With no status observation the keyword row says its status is unconfirmed.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell user turn requesting S6 piece 8, quoted in the piece 8 builder brief, 2026-11-11",
          "label": "owner request for this piece"
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-health-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/CapabilityMap.test.ts#keeps cold-start keyword availability unconfirmed"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "S6 piece 8 quiet Health, settled 2026-11-11T12:40Z with the 13:00Z decisions: six rows (Engine, Folders, Index, AI models, Graphics card, Background work) whose status cell is empty only when its facts are current and fine; unknown or stale is never fine; the headline says 'Everything is working' only when all six are current and fine and otherwise names checking, work or the most consequential problem; 'No recovery actions returned' is never evidence of health; one collapsed Diagnostics disclosure holds the technical readouts; recovery actions for active problems stay outside it and are offered only from a current recovery-index read (stale remedies cannot execute as current).",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell user turn requesting S6 piece 8, quoted in the piece 8 builder brief, 2026-11-11",
          "label": "owner request for this piece"
        }
      ],
      "conflictSearch": "Decision records searched for a conflict: rg -n -i -E 'keyword.*available|Search \\(keyword\\)|System Health|health-surface|Health, Logs|Logs, Activity|inFlightCount|failedCount|indexed-root|IndexedRootView|detach|seenAtGap|recovery.?index|No recoverable|status.?badge|Up to date' docs/decisions. Matches only in ADR-0015 (MCP tool justsearch_status), ADR-0048 (extraction client detach), ADR-0041/0028 (the word 'recommended') and ADR-0044 (CI branch up to date); none governs Health's presentation, the System tab order, folder job counts or notification timing. No conflicting decision found."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts"
      ],
      "class": "New",
      "behaviour": "The same six situations through the six rows and the one headline: an empty recovery index with other rows unconfirmed never claims health ('Up to date', 'No recoverable conditions active', 'Everything is working'); retrieval UNKNOWN reads Checking in the Index row and the headline and never 'Service degraded'; a cosmetic LambdaMART degradation reads 'Reduced capability' calmly; before any data every row is unconfirmed and the headline is checking; an impairing degraded readiness reads 'Service degraded' in the Index row and headline; an overdue rebuild escalates the Index row and headline to 'taking longer than expected'.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "S6 piece 8 quiet Health, settled 2026-11-11T12:40Z with the 13:00Z decisions: six rows (Engine, Folders, Index, AI models, Graphics card, Background work) whose status cell is empty only when its facts are current and fine; unknown or stale is never fine; the headline says 'Everything is working' only when all six are current and fine and otherwise names checking, work or the most consequential problem; 'No recovery actions returned' is never evidence of health; one collapsed Diagnostics disclosure holds the technical readouts; recovery actions for active problems stay outside it and are offered only from a current recovery-index read (stale remedies cannot execute as current).",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell user turn requesting S6 piece 8, quoted in the piece 8 builder brief, 2026-11-11",
          "label": "owner request for this piece"
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-health-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts#SH2: no recovery conditions alone never claims health while other rows are unconfirmed",
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts#595 §1.1 SH1: headline and Index row agree on the unknown boundary — both \"Checking…\", NEVER a split",
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts#595 §10.3: a cosmetic degradation (LambdaMART) reads calm in the Index row and headline (not an alarm)",
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts#§2.C SH1: every row is checking (not all-operational) before health data arrives",
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts#B7/B2: reads observed-state from the store; an impairing degraded readiness → \"Service degraded\"",
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts#595 §15.2 (E4) SH8: an OVERDUE rebuild escalates the Index row and headline to \"taking longer than expected\""
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts",
        "modules/ui-web/src/shell-v0/views/BulkOperationCompletion.test.ts",
        "modules/ui-web/src/shell-v0/views/BulkOperationImmediateRefusal.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "instrumentation"
      ],
      "whatMoved": "The remedy under test used to be written straight into Health's private recommendedActions map. It now arrives through Health's real recovery-index read (a fetch answer with the same target, conditionId|subject key and {} arguments); BulkOperationCompletion gives Health a host whose fetch reaches the test's existing stub (without one every Health read failed silently), and the ImmediateRefusal server answers the recovery index only for the health-recommended case, which waits for the remedy to arrive. Inputs (targets, keys, arguments), the invoked control, the observations and every expected outcome are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "SH12 with the settled amendment: stale remedies cannot execute as current; Health offers a recovery control only from a current recovery-index read.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.redesign.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "D1: the two folder observations (failedCount 1, then inFlightCount 1 during the retry) and the final settled observation now arrive through the shared indexed-roots read, which owns the retained last-known-failure memory, instead of being written into Library's private roots and lastKnownFailed fields. The observed headline, attention text, failed-files list and the 25 alternating unconfirmed polls are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Settled amendment adopted from challenger point 7: Health and Library agree on current versus last-known failures, pause reasons and enrichment from the same inputs, not only the same function; the shared folder observation holds the failure memory both read.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "S6 piece 8 quiet Health, settled 2026-11-11T12:40Z with the 13:00Z decisions: six rows (Engine, Folders, Index, AI models, Graphics card, Background work) whose status cell is empty only when its facts are current and fine; unknown or stale is never fine; the headline says 'Everything is working' only when all six are current and fine and otherwise names checking, work or the most consequential problem; 'No recovery actions returned' is never evidence of health; one collapsed Diagnostics disclosure holds the technical readouts; recovery actions for active problems stay outside it and are offered only from a current recovery-index read (stale remedies cannot execute as current). SH8: indexing transitions keep their shared explanations in the rows.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell user turn requesting S6 piece 8, quoted in the piece 8 builder brief, 2026-11-11",
          "label": "owner request for this piece"
        }
      ],
      "conflictSearch": "Decision records searched for a conflict: rg -n -i -E 'keyword.*available|Search \\(keyword\\)|System Health|health-surface|Health, Logs|Logs, Activity|inFlightCount|failedCount|indexed-root|IndexedRootView|detach|seenAtGap|recovery.?index|No recoverable|status.?badge|Up to date' docs/decisions. Matches only in ADR-0015 (MCP tool justsearch_status), ADR-0048 (extraction client detach), ADR-0041/0028 (the word 'recommended') and ADR-0044 (CI branch up to date); none governs Health's presentation, the System tab order, folder job counts or notification timing. No conflicting decision found."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts"
      ],
      "class": "New",
      "behaviour": "For every case of the sweep (energy, game, external GPU, chat, age, explicit stale, Engine gone): the headline never says 'Everything is working'; a confirmed hold keeps its shared explanation in the Background work row; an unconfirmed observation makes the Index row read the shared 'Indexing status unconfirmed' and leaves Background work non-empty.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "S6 piece 8 quiet Health, settled 2026-11-11T12:40Z with the 13:00Z decisions: six rows (Engine, Folders, Index, AI models, Graphics card, Background work) whose status cell is empty only when its facts are current and fine; unknown or stale is never fine; the headline says 'Everything is working' only when all six are current and fine and otherwise names checking, work or the most consequential problem; 'No recovery actions returned' is never evidence of health; one collapsed Diagnostics disclosure holds the technical readouts; recovery actions for active problems stay outside it and are offered only from a current recovery-index read (stale remedies cannot execute as current). SH8: indexing transitions keep their shared explanations in the rows.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell user turn requesting S6 piece 8, quoted in the piece 8 builder brief, 2026-11-11",
          "label": "owner request for this piece"
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-health-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts#S33.4 SH8 Health rows keep each hold explanation and withhold fine claims for all unconfirmed observations"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Settled amendment adopted from challenger point 3: hanging and superseded reads are bounded per source, so the status controller reads roots, presence and each operation outcome independently. A read right after a window returns now observes the operation promptly; by the controller's existing rule an end seen after a running observation that followed the gap is live (the existing 'an operation seen running again after the gap ends live, so it notifies' case).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Decision records searched for a conflict: rg -n -i -E 'keyword.*available|Search \\(keyword\\)|System Health|health-surface|Health, Logs|Logs, Activity|inFlightCount|failedCount|indexed-root|IndexedRootView|detach|seenAtGap|recovery.?index|No recoverable|status.?badge|Up to date' docs/decisions. Matches only in ADR-0015 (MCP tool justsearch_status), ADR-0048 (extraction client detach), ADR-0041/0028 (the word 'recommended') and ADR-0044 (CI branch up to date); none governs Health's presentation, the System tab order, folder job counts or notification timing. No conflicting decision found."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts"
      ],
      "class": "New",
      "behaviour": "An operation seen running, whose end happens while the window is detached, is silent when the returned window's first read finds it complete; the panel row shows the observed completion.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Settled amendment adopted from challenger point 3 (independent bounded reads per source); the C6 rule that work ending across a gap in observation is silent is kept.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-health-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPill.notices.test.ts#an operation ending across a detach is silent"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/indexing/IndexedRootViewSchemaTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "SH-COUNTS (decided, includes a small backend fix): the folders endpoint reports a root's job counts as unknown (not zero) when counting fails, and the contract marks those counts nullable.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Decision records searched for a conflict: rg -n -i -E 'keyword.*available|Search \\(keyword\\)|System Health|health-surface|Health, Logs|Logs, Activity|inFlightCount|failedCount|indexed-root|IndexedRootView|detach|seenAtGap|recovery.?index|No recoverable|status.?badge|Up to date' docs/decisions. Matches only in ADR-0015 (MCP tool justsearch_status), ADR-0048 (extraction client detach), ADR-0041/0028 (the word 'recommended') and ADR-0044 (CI branch up to date); none governs Health's presentation, the System tab order, folder job counts or notification timing. No conflicting decision found."
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/indexing/IndexedRootViewSchemaTest.java"
      ],
      "class": "New",
      "behaviour": "The committed indexed-root schema types each coverage field as [\"integer\",\"null\"], and the schema generator honours @Nullable so the record and baseline agree.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "SH-COUNTS (decided): job counts and coverage are nullable on the wire so a failed count is unknown, never zero.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-health-repaired.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/indexing/IndexedRootViewSchemaTest.java#baselineCarriesCoverageFields"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexingControllerFailureTest.java"
      ],
      "class": "New",
      "behaviour": "When an ordinary (non-capacity) job-count query fails, the folders endpoint still answers with the root and reports inFlightCount, failedCount and the coverage counts as present nulls, never fabricated zeros.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "SH-COUNTS (decided, includes a small backend fix): the folders endpoint (IndexingController ~898) reports a root's job counts as unknown (not zero) when counting fails.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-health-repaired.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexingControllerFailureTest.java#ordinaryJobCountFailureReportsUnknownInsteadOfZero"
      ]
    },
    {
      "items": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/surface/CoreSurfaceCatalogTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "S6 piece 8 decision: the System catalog order is Health, Activity, Logs (Health's label 'Health'); IDs, audiences and placements are preserved.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Decision records searched for a conflict: rg -n -i -E 'keyword.*available|Search \\(keyword\\)|System Health|health-surface|Health, Logs|Logs, Activity|inFlightCount|failedCount|indexed-root|IndexedRootView|detach|seenAtGap|recovery.?index|No recoverable|status.?badge|Up to date' docs/decisions. Matches only in ADR-0015 (MCP tool justsearch_status), ADR-0048 (extraction client detach), ADR-0041/0028 (the word 'recommended') and ADR-0044 (CI branch up to date); none governs Health's presentation, the System tab order, folder job counts or notification timing. No conflicting decision found."
    },
    {
      "items": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/surface/CoreSurfaceCatalogTest.java"
      ],
      "class": "New",
      "behaviour": "The System hub's members are exactly Health, Activity, Logs in that order; the rest of systemHubShape is unchanged.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "S6 piece 8 decision: catalog order Health, Activity, Logs.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-health-repaired.json",
      "newChecks": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/surface/CoreSurfaceCatalogTest.java#systemHubShape"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.quiet.test.ts"
      ],
      "class": "New",
      "behaviour": "SH7 and the 13:00Z failure-retirement rule: a failed operation receipt retired by a later successful invocation of the same operation, or marked read through the shared seen cursor, stays retired when its receipts leave the bounded ledger history; a new failure is still reported in the Background work row.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Piece 8 decision 2026-11-11T13:00Z: a failed operation receipt counts as a current problem until a later invocation of the same operation succeeds or the user marks it read through the shared seen cursor (justsearch.recall.seen-cursor.v1). Receipts are immutable, so the status controller keeps the retirement when both receipts leave the bounded retained history; a later failure is a new receipt and is reported.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell user turn requesting S6 piece 8, quoted in the piece 8 builder brief, 2026-11-11",
          "label": "owner request for this piece"
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-health-verifier-1.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.quiet.test.ts#SH7 a retired failure stays retired when its receipts leave the bounded history; a new failure is reported"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.frame-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "SH13: in real Chromium, every Health row action (Open Logs, Open Library, Open AI Brain, Recent activity) and every remedy declared in Diagnostics reaches its window through the shell's navigate-with-context path and the address follows, including the Graphics card row with GPU telemetry unavailable and a GPU-backed model. SH16: at 600x400 with 200% root text Health and its open Diagnostics never scroll sideways and truncated values keep their full text; 900x700 and 1280x720 at 100% keep the layout recorded on 394467ba1d.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Settled piece 8 scenarios SH13 (navigation from Health's rows) and SH16 (600x400 at 200% text): a row action uses the same navigate-with-context contract (target, state) as jf-control and the capability map, so the shell activates the destination; Diagnostics readouts wrap long unbroken values inside their column instead of widening it, and a truncated span name carries its full text as a title.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell user turn requesting S6 piece 8, quoted in the piece 8 builder brief, 2026-11-11",
          "label": "owner request for this piece"
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-health-verifier-1.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.frame-lockdown.test.ts#every row action and Diagnostics remedy reaches its window through the shell and the address follows (current status, GPU telemetry unavailable, GPU-backed model)",
        "modules/ui-web/src/shell-v0/views/HealthSurface.frame-lockdown.test.ts#every row action and Diagnostics remedy reaches its window through the shell and the address follows (engine starting)",
        "modules/ui-web/src/shell-v0/views/HealthSurface.frame-lockdown.test.ts#600x400 with 200% text: Health and the open Diagnostics never scroll sideways and long values stay readable"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.freshness-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "SH3: in real Chromium, with status refused for 45 s while inference keeps answering, every age in the expanded Diagnostics is dated by its own source: the status age by the status receipt (about 48 s) with the worker sample it carries, contact by the last answer on any channel (just now), uptime as of the status receipt, the AI Engine card by the inference receipt, and the stream frame age by the last stream frame. SH14: AI Brain's engine-history intent arriving while the shell is in engine loss with Health open keeps its destination: after recovery Diagnostics is expanded and focus stays on the AI Engine section.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Settled piece 8 scenarios SH3 and SH14 with the amendments 'every source has its own freshness', 'expanded Diagnostics makes no stale claims' and 'AI Brain's engine-history link expands and focuses its section': Health's Connection card reads the status receipt for its data age instead of the combined last-success time, and the engine-loss treatment, on recovery, returns focus only when it is still in the treatment or nowhere, so a section a window focused during recovery keeps it.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell user turn requesting S6 piece 8, quoted in the piece 8 builder brief, 2026-11-11",
          "label": "owner request for this piece"
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-health-verifier-2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.freshness-lockdown.test.ts#status stops for 45 s while inference answers: the status age reads its own receipt, contact and inference stay just now",
        "modules/ui-web/src/shell-v0/views/HealthSurface.freshness-lockdown.test.ts#AI Brain's engine-history intent during the loss: after recovery Diagnostics is open and focus stays on the AI Engine section"
      ]
    }
  ]
}
```

## Acceptance records
