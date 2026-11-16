---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: justsearch-fm-pe
---

# PE: one way to write a size

Every user-visible file or download size goes through the shared formatBytes (display/format.ts);
install progress uses formatBytesOf from the same module. The install flow's own formatters and Browse's
'<n> bytes' rule are removed. New and changed checks in format.test.ts, BrainInstall.flow.test.ts and
browse-lockdown.test.ts fail before by assertion on 2d51325e39 and pass on the candidate
(gates/test-intent/evidence/fm-pe.json). The other edited files change only expected size strings.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/display/format.test.ts",
        "modules/ui-web/src/shell-v0/views/brain-install/BrainInstall.flow.test.ts",
        "modules/ui-web/src/shell-v0/views/library-first-minutes/browse-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "New",
      "behaviour": "Sizes are written one way: formatBytes gives at most three figures, a decimal only below 10, no trailing zeros, B/KB/MB/GB/TB after a space in steps of 1024 ('157 B', '12 KB', '341 MB', '1.2 GB', '2.6 GB', '1 GB', '0 B'), and never four figures (1000 B is '1 KB'); formatBytesOf writes 'X of Y' in the unit of the whole ('1.3 of 2.6 GB'), clamped to 0..whole. The consent dialog lists '1.2 GB' and '23 MB', the install action reads 'Install AI · 2.6 GB', install progress and a part row read '1.3 of 2.6 GB' and '0.5 of 1.2 GB', and Browse's Size column reads '0 B' and '90 B', never 'bytes'. The modified checks in these files ('scales …', S7b-1 consent rows, S7b-3 install label, the Browse tree check) change only their size strings.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Sizes [MISS, one name per thing]. '2.60 GB', '1.19 GB download' and '341.4 MB' on the page, '341 MB' in the dialog, '157 B' in the pane, '158 bytes' in Browse. One formatter: at most three figures, no trailing zeros ('2.6 GB', '1.2 GB', '341 MB').",
          "location": "task t-20261113-fmimpl agreement, design review of the packaged app by the author of the pictures (finding PE)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "PE1 one shared size formatter (display/format.ts formatBytes; the install flow's formatPartSize/formatTotalSize/formatAmountOf and Browse's '<n> bytes' rule removed): at most three figures, no trailing zeros, units B, KB, MB, GB, TB after a space, zero is '0 B'; steps of 1024 kept because downloads and disk already agreed on 1024; a decimal only below 10, as the pictures write sizes ('5.7 GB', '23 MB', '69 KB', '12 GB'); a value that would round to 1000 is written in the next unit. PE2 every user-visible size goes through it (AI Brain install button and step download sizes, consent dialog, install progress 'X of Y' with both amounts in the unit of the whole, side pane, Browse's Size column, Health/storage, status). PE3 the words around sizes stay. PE4 screen readers get the same text.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-pe.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/display/format.test.ts#writes at most three figures, a decimal only below 10, and no trailing zeros (PE1)",
        "modules/ui-web/src/shell-v0/display/format.test.ts#never writes four figures: a value that would round to 1000 moves to the next unit (PE1)",
        "modules/ui-web/src/shell-v0/display/format.test.ts#writes both amounts in the unit of the whole, each with at most three figures (PE1, PE2)",
        "modules/ui-web/src/shell-v0/display/format.test.ts#keeps the amount between zero and the whole",
        "modules/ui-web/src/shell-v0/display/format.test.ts#scales B / KB / MB / GB / TB in steps of 1024, with a space before the unit",
        "modules/ui-web/src/shell-v0/views/brain-install/BrainInstall.flow.test.ts#writes gigabyte amounts in the one size format, in the line and in a part row",
        "modules/ui-web/src/shell-v0/views/brain-install/BrainInstall.flow.test.ts#names the five parts as the page does, with sizes, licences and Terms, under the pictured words",
        "modules/ui-web/src/shell-v0/views/brain-install/BrainInstall.flow.test.ts#after a cancelled run the action is Install AI with the most it can download, never Resume",
        "modules/ui-web/src/shell-v0/views/library-first-minutes/browse-lockdown.test.ts#the browser tree has real roving focus, stable row geometry, reachable actions and a shared keyboard menu"
      ],
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for formatBytes, byte size, file size, download size, GiB, MiB, 1024 and size format: 0019 (16 GiB cache cap), 0047, 0050 and 0051 mention sizes of caches or memory, none governs how the UI writes a size. No conflict."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.running.test.ts",
        "modules/ui-web/src/shell-v0/display/facts.test.ts",
        "modules/ui-web/src/shell-v0/state/aiVerdict.test.ts",
        "modules/ui-web/src/shell-v0/state/installComponents.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.componentList.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.installConsent.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.pausedInstall.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.redesign.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.removalBand-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.skipReason.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts",
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.step-repairs.test.ts",
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.steps.test.ts",
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.tabs.test.ts",
        "modules/ui-web/src/shell-v0/views/library-first-minutes/presentation.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/first-minutes/ResultsPane.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Sizes [MISS, one name per thing]. '2.60 GB', '1.19 GB download' and '341.4 MB' on the page, '341 MB' in the dialog, '157 B' in the pane, '158 bytes' in Browse. One formatter: at most three figures, no trailing zeros ('2.6 GB', '1.2 GB', '341 MB').",
          "location": "task t-20261113-fmimpl agreement, design review of the packaged app by the author of the pictures (finding PE)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "PE1 one shared size formatter (display/format.ts formatBytes; the install flow's formatPartSize/formatTotalSize/formatAmountOf and Browse's '<n> bytes' rule removed): at most three figures, no trailing zeros, units B, KB, MB, GB, TB after a space, zero is '0 B'; steps of 1024 kept because downloads and disk already agreed on 1024; a decimal only below 10, as the pictures write sizes ('5.7 GB', '23 MB', '69 KB', '12 GB'); a value that would round to 1000 is written in the next unit. PE2 every user-visible size goes through it (AI Brain install button and step download sizes, consent dialog, install progress 'X of Y' with both amounts in the unit of the whole, side pane, Browse's Size column, Health/storage, status). PE3 the words around sizes stay. PE4 screen readers get the same text.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Only the expected size strings change, to the one format; inputs, observations and every other assertion stay. Negative checks were moved to the new format of the same amount so they still guard the stale value.",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for formatBytes, byte size, file size, download size, GiB, MiB, 1024 and size format: 0019 (16 GiB cache cap), 0047, 0050 and 0051 mention sizes of caches or memory, none governs how the UI writes a size. No conflict.",
      "assertionChanges": [
        {
          "oldAssertion": "components/StatusPanel.running.test.ts: '412 files remaining · 8.00 GB'",
          "newAssertion": "'412 files remaining · 8 GB'",
          "reason": "PE1/PE2: the old expectation pinned a size format the review names as the defect (two decimals for GB, '.0' trailing zeros, '<n> bytes'); the amount is the same."
        },
        {
          "oldAssertion": "display/facts.test.ts: core.memory/core.size '51.0 MB' and '227.0 MB'",
          "newAssertion": "'51 MB' and '227 MB'",
          "reason": "PE1/PE2: the old expectation pinned a size format the review names as the defect (two decimals for GB, '.0' trailing zeros, '<n> bytes'); the amount is the same."
        },
        {
          "oldAssertion": "state/aiVerdict.test.ts: paused body '1.06 GB already downloaded is kept on disk.'",
          "newAssertion": "'1.1 GB already downloaded is kept on disk.'",
          "reason": "PE1/PE2: the old expectation pinned a size format the review names as the defect (two decimals for GB, '.0' trailing zeros, '<n> bytes'); the amount is the same."
        },
        {
          "oldAssertion": "state/installComponents.test.ts: subtotals '4 components · 1.29 GB', '2 components · 1.02 GB'; summaries '2 installed · 7.08 GB on disk', '1 installed · 1.12 GB on disk'",
          "newAssertion": "'4 components · 1.3 GB', '2 components · 1 GB', '2 installed · 7.1 GB on disk', '1 installed · 1.1 GB on disk'",
          "reason": "PE1/PE2: the old expectation pinned a size format the review names as the defect (two decimals for GB, '.0' trailing zeros, '<n> bytes'); the amount is the same."
        },
        {
          "oldAssertion": "views/BrainSurface.componentList.test.ts: '1.12 GB', '4 components · 1.29 GB', '3 installed · 3.30 GB on disk'",
          "newAssertion": "'1.1 GB', '4 components · 1.3 GB', '3 installed · 3.3 GB on disk'",
          "reason": "PE1/PE2: the old expectation pinned a size format the review names as the defect (two decimals for GB, '.0' trailing zeros, '<n> bytes'); the amount is the same."
        },
        {
          "oldAssertion": "views/BrainSurface.installConsent.test.ts: downloadTotal '10.14 GB' and '700.0 MB'; consentTotal '10.1 GB'",
          "newAssertion": "downloadTotal and consentTotal both '10 GB'; '700 MB'",
          "reason": "PE1/PE2: the old expectation pinned a size format the review names as the defect (two decimals for GB, '.0' trailing zeros, '<n> bytes'); the amount is the same."
        },
        {
          "oldAssertion": "views/BrainSurface.liveRepairs.test.ts: '1.0 KB', '2.0 KB', '9.00 GB of 12.00 GB in use · one of 2 graphics devices'",
          "newAssertion": "'1 KB', '2 KB', '9 GB of 12 GB in use · one of 2 graphics devices'",
          "reason": "PE1/PE2: the old expectation pinned a size format the review names as the defect (two decimals for GB, '.0' trailing zeros, '<n> bytes'); the amount is the same."
        },
        {
          "oldAssertion": "views/BrainSurface.pausedInstall.test.ts: '1.06 GB already downloaded is kept on disk'; plan totals '10.14 GB' then '9.08 GB' (and not '10.14 GB')",
          "newAssertion": "'1.1 GB …'; '10 GB' then '9.1 GB' (and not '10 GB')",
          "reason": "PE1/PE2: the old expectation pinned a size format the review names as the defect (two decimals for GB, '.0' trailing zeros, '<n> bytes'); the amount is the same."
        },
        {
          "oldAssertion": "views/BrainSurface.redesign.test.ts: '8.0 KB', '4.0 KB of 8.0 KB in use · Test adapter'",
          "newAssertion": "'8 KB', '4 KB of 8 KB in use · Test adapter'",
          "reason": "PE1/PE2: the old expectation pinned a size format the review names as the defect (two decimals for GB, '.0' trailing zeros, '<n> bytes'); the amount is the same."
        },
        {
          "oldAssertion": "views/BrainSurface.removalBand-lockdown.test.ts: 'This deletes 1 file (3.0 MB) …', 'answer.gguf · 3.0 MB'",
          "newAssertion": "'(3 MB)', 'answer.gguf · 3 MB'",
          "reason": "PE1/PE2: the old expectation pinned a size format the review names as the defect (two decimals for GB, '.0' trailing zeros, '<n> bytes'); the amount is the same."
        },
        {
          "oldAssertion": "views/BrainSurface.remove-files.test.ts: '3.0 MB on disk', '(3.0 MB)', 'answer.gguf · 2.0 MB', 'mmproj-F16.gguf · 1.0 MB', 'custom.gguf · 2.0 KB …'",
          "newAssertion": "'3 MB on disk', '(3 MB)', 'answer.gguf · 2 MB', 'mmproj-F16.gguf · 1 MB', 'custom.gguf · 2 KB …'",
          "reason": "PE1/PE2: the old expectation pinned a size format the review names as the defect (two decimals for GB, '.0' trailing zeros, '<n> bytes'); the amount is the same."
        },
        {
          "oldAssertion": "views/BrainSurface.skipReason.test.ts: '114.4 MB'",
          "newAssertion": "'114 MB'",
          "reason": "PE1/PE2: the old expectation pinned a size format the review names as the defect (two decimals for GB, '.0' trailing zeros, '<n> bytes'); the amount is the same."
        },
        {
          "oldAssertion": "views/BrainSurface.sliceB.test.ts: button 'Install additions · 1.0 KB'",
          "newAssertion": "'Install additions · 1 KB'",
          "reason": "PE1/PE2: the old expectation pinned a size format the review names as the defect (two decimals for GB, '.0' trailing zeros, '<n> bytes'); the amount is the same."
        },
        {
          "oldAssertion": "views/brain-first-minutes/BrainOverview.step-repairs.test.ts: '3.18 GB download' (and not '6.34 GB download')",
          "newAssertion": "'3.2 GB download' (and not '6.3 GB download')",
          "reason": "PE1/PE2: the old expectation pinned a size format the review names as the defect (two decimals for GB, '.0' trailing zeros, '<n> bytes'); the amount is the same."
        },
        {
          "oldAssertion": "views/brain-first-minutes/BrainOverview.steps.test.ts: 'Install AI · 2.51 GB', '2.18 GB', step sizes '1.20 GB', '341.0 MB', '5.70 GB'",
          "newAssertion": "'Install AI · 2.5 GB', '2.2 GB', '1.2 GB', '341 MB', '5.7 GB'",
          "reason": "PE1/PE2: the old expectation pinned a size format the review names as the defect (two decimals for GB, '.0' trailing zeros, '<n> bytes'); the amount is the same."
        },
        {
          "oldAssertion": "views/brain-first-minutes/BrainOverview.tabs.test.ts: '5.80 GB of 12.00 GB in use · RTX 4070'",
          "newAssertion": "'5.8 GB of 12 GB in use · RTX 4070'",
          "reason": "PE1/PE2: the old expectation pinned a size format the review names as the defect (two decimals for GB, '.0' trailing zeros, '<n> bytes'); the amount is the same."
        },
        {
          "oldAssertion": "views/library-first-minutes/presentation.test.ts: Browse text contains '90 bytes'",
          "newAssertion": "'90 B'",
          "reason": "PE1/PE2: the old expectation pinned a size format the review names as the defect (two decimals for GB, '.0' trailing zeros, '<n> bytes'); the amount is the same."
        },
        {
          "oldAssertion": "views/search-v3/first-minutes/ResultsPane.test.ts: pane path line '/notes/sub · 2 pages · 1.0 KB'",
          "newAssertion": "'/notes/sub · 2 pages · 1 KB'",
          "reason": "PE1/PE2: the old expectation pinned a size format the review names as the defect (two decimals for GB, '.0' trailing zeros, '<n> bytes'); the amount is the same."
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
  "session": "caa6061e-06e5-49fe-8f3b-1ece4af0a8c0",
  "verdict": "accept",
  "digest": "sha256:c89eb31049cb9d2dd1cae8b4d4225e764fa699757ddfeba35ae8a1da681954a3"
}
```
