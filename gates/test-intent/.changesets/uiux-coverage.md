---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: builder-uiux-coverage-20261021
---

# Test-intent entries

The repaired evidence is deliberately left reporting its real infrastructure failure:
`--copy-node-modules` dereferences `.bin/vitest`, whose relative import then resolves
against `.bin/dist/cli.js` rather than `vitest/dist/cli.js`. Candidate checks all pass;
its scripted before run cannot start. A separate shared baseline clone with hard-linked
dependencies executed the final tests: 12 assertion failures, 10 preservation passes.
See `tmp/wp4.2a/baseline-final-tests.log` and `tmp/wp4.2a/evidence-repaired.log`.
The builder has not authored an acceptance record.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts"
      ],
      "class": "New",
      "behaviour": "Read the check's sentence counts and examination facts on live and saved Ask paths, use the existing shared coverage note, show unexamined sources even with no matches, retain each fact through refresh, and render stable ordinary text. No new saved field.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "HC1",
          "quote": "A completed Ask whose answer is a numbered or bulleted list, with every sentence verified, reads as fully grounded, not partly grounded, live and after a cold reload. The sentence total is the check's own count whenever the answer carries one; the window's own count is used only for answers that carry none.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "HC2",
          "quote": "When the check did not cover everything (sources not examined, part of the text examined, only some sentences scored, not scored), the turn says so using the shared coverage note: the same text the old window shows for the same saved answer, with no second phrasing of the same fact. Holds live, after a same-window refresh, after a cold reload and after an Engine restart.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "HC5",
          "quote": "In an Ask turn's sources panel, a source the check never examined shows as not examined, never as not cited; a partly examined source keeps its grade. Live and after cold reload.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "HC6",
          "quote": "Live and reloaded coverage agree fact by fact. A same-window refresh (including one during streaming or during the check) keeps facts the live turn showed when the record lacks them and never invents one. A cold reload (new window, no retained browser state) shows only what the record holds, and absence reads as in S10.4.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "HC8",
          "quote": "The coverage note stays readable without overflow at about 360 px, at 200 % text zoom, in both themes and in forced colors; it is reachable as text on the turn and is not re-announced on each streamed frame.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Implement the agreed designs; orchestrator D1 divides WP4.2 into frontend coverage WP4.2a and the other packages.",
          "choice": "Decision D1: four packages; WP4.2a is coverage only, frontend only. S10.7 (unfinished check) belongs to WP4.3. Order after pass 2: 4.5 and 4.2a first, then 4.2b, then 4.3.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-coverage-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts#HC1 uses the check count for a fully verified list live and cold",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts#HC1 honors a count even when other coverage facts are absent",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts#HC2 shares the sources note live refreshed and cold",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts#HC2 shares the text note live refreshed and cold",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts#HC2 shares the sentences note live refreshed and cold",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts#HC2 shares the not scored note live refreshed and cold",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts#HC5 supplies examination facts with matches present=true",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts#HC5 supplies examination facts with matches present=false",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts#HC6 preserves each live fact when a settled refresh carries only some facts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts#HC6 fills absent live facts from the record without inventing source facts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts#HC6 retains live facts during streaming or checking refresh",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts#HC8 keeps the note as ordinary stable text without live announcements"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserve complete/legacy prose frames and marks, missing-fact absence, producer gating, and the absence of Ask coverage notes on delegate and noncompleted Ask turns. Shared legacy/Summarize coverage wording remains the authority.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "HC3",
          "quote": "When the check covered everything, no extra coverage note appears.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "HC4",
          "quote": "An answer saved before coverage facts existed, or whose record carries none, shows today's line unchanged; missing facts never read as complete or as not grounded.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "HN1",
          "quote": "Marks, the frame line and the grounded/partly verdict for prose answers are unchanged; live and reloaded marks are equal (WP4.1 N4.3).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "HN2",
          "quote": "Producer gate as today (evidenceProjection.ts:418, recordEvidence.ts:94, :144): explicit cosine or NONE producers stay unverified; historical envelopes without a scorer stamp keep their marks; an unknown named producer fails closed; live and saved paths apply the same rule.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "HN3",
          "quote": "Delegate turns' frame, receipt and evidence are unchanged (A4 E deferred).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "HN4",
          "quote": "The old window and Summarize coverage lines are unchanged.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "HN5",
          "quote": "Halted, refused and failed Ask turns keep their own notes, including stopped by you after cold reload (as WP1.2 delivers).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Implement the agreed designs; orchestrator D1 divides WP4.2 into frontend coverage WP4.2a and the other packages.",
          "choice": "Decision D1: four packages; WP4.2a is coverage only, frontend only. S10.7 (unfinished check) belongs to WP4.3. Order after pass 2: 4.5 and 4.2a first, then 4.2b, then 4.3.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-coverage-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts#HC3 HC4 HN1 preserve complete and legacy prose frames and marks without extra notes",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts#HN2 keeps the producer gate for EMBEDDING_COSINE live and cold",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts#HN2 keeps the producer gate for NONE live and cold",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts#HN2 keeps the producer gate for UNKNOWN live and cold",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts#HN2 keeps the producer gate for null live and cold",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts#HN2 keeps the producer gate for  live and cold",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts#HN3 HN5 keep agent turns without Ask coverage notes",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts#HN3 HN5 keep halted turns without Ask coverage notes",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts#HN3 HN5 keep refused turns without Ask coverage notes",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage.test.ts#HN3 HN5 keep failed turns without Ask coverage notes"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage-repairs.test.ts"
      ],
      "class": "New",
      "behaviour": "Repair F1: Ask FULLTEXT_FALLBACK sources with reported examination gaps show not examined, live and cold, with and without admitted matches; a partially examined cited source keeps its grade. Repair F3: mixed document-level/chunk Delegate sources with cosine provenance retain both neutral cards, without examination or not-cited groups, on live and saved projections. Existing accepted checks and evidence are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "HC5",
          "quote": "In an Ask turn's sources panel, a source the check never examined shows as not examined, never as not cited; a partly examined source keeps its grade. Live and after cold reload.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "HN3",
          "quote": "Delegate turns' frame, receipt and evidence are unchanged (A4 E deferred).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Implement the agreed designs; orchestrator D1 divides WP4.2 into frontend coverage WP4.2a and the other packages.",
          "choice": "Decision D1: four packages; WP4.2a is coverage only, frontend only. S10.7 (unfinished check) belongs to WP4.3. Order after pass 2: 4.5 and 4.2a first, then 4.2b, then 4.3.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-coverage-repairs.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage-repairs.test.ts#HC5 shows unexamined FULLTEXT_FALLBACK sources with matches present=true live and cold",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage-repairs.test.ts#HC5 shows unexamined FULLTEXT_FALLBACK sources with matches present=false live and cold",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-coverage-repairs.test.ts#HN3 keeps the mixed-source Delegate panel neutral live and cold"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-imyhj10i-20261022",
  "verdict": "accept",
  "digest": "sha256:c5a4d5ffe60d6d7331a864f236f1de14f60afdf8e990703b2d730e6339a83bb3",
  "note": "Accept expectation provenance only: HC1-HC6/HC8, HN1-HN5 and delegated D1 support the New checks; no existing expectation is weakened. Independent execution: 12 repair assertions fail before, all 22 checks pass the candidate, and 10 preservation checks pass before. Scratch run-evidence passes using the retained npx wrapper to bypass the dependency copier launcher defect. Overall product verification fails HC5 for the FULLTEXT_FALLBACK saved-record counterexample and HC8 at 360px/200% text with the sidebar expanded."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-mb30mucs-20261022",
  "verdict": "accept",
  "digest": "sha256:e6ba456fbefebf3ac01786784a8b5b97417fa98a2fa2950ea90bbe948c0d0c75",
  "note": "Accept expectation provenance and execution only. HC1-HC6/HC8, HN1-HN5 and delegated D1 support these New checks; none weakens an existing expectation. Independent scratch reruns: all 25 candidate checks passed, 12 original repairs failed by assertion on 1016257d2, three source-panel repairs failed by assertion on ea2b423b3, and all 10 preservation checks passed before. Offline npx wrapper bypassed the dependency-copier launcher defect. Live product verdict is reported separately."
}
```
