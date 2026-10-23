---
schema: test-intent.v1
task: t-20261023-c3e5b0
author-role: builder
author-session: 01a16aca-3b92-7e90-aaa3-399605f18c48
---

# Test-intent entries

Both items are classified `New` against 7cc290535. The UI diff adds a new
Report a problem suite and its opener mock; it retains the existing expectations.
The Rust diff adds one accepted-URL input to an existing test. These additions
pin a new feature and its preservation obligations, with no reported pre-existing
defect or replaced oracle. Neither is repaired behaviour, so no fail-before is
claimed. The Rust URL acceptance is new coverage of an existing validator.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/HelpSurface.test.ts"
      ],
      "class": "New",
      "behaviour": "C1-C2: a native keyboard-accessible Report a problem button invokes core.copy-diagnostic-summary exactly once with empty args and consented:false, copies the returned summary, then opens exactly the template URL once. No other query parameter, diagnostic text, automatic issue request, summary display or backend-message display is allowed; the polite receipt tells the user to paste, check and submit on GitHub. C3: a rejected operation, missing summary, clipboard refusal or clipboard exception still opens the template and gives the Copy diagnostic summary fallback without exposing exception/summary text. C4: an opener rejection with either copy outcome, or a blocked browser popup, shows the URL; the rejection receipt is selectable text. C5: export keeps its button context, telemetry arguments, saved-path receipt and error receipt, without opening a report. Existing Copy diagnostic summary and keyboard shortcut expectations remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, design it, then open the task register and take over the report issue work",
          "delegationLocation": "task t-20261023-c3e5b0 request",
          "delegationScope": "Task t-20261023-c3e5b0: the owner agreed to a user-initiated Report a problem action that copies the redacted diagnostic summary and opens the bug report template in the user's browser, with no automatic issue filing, and handed the design and build to the agent. It covers the Help & Support action and its failure handling; it does not authorise changing any other established contract.",
          "choice": "C1: one click runs `core.copy-diagnostic-summary`, writes the summary to the clipboard, and opens exactly `https://github.com/justsearch-app/justsearch/issues/new?template=bug_report.md`.\nC2: the opened URL carries nothing but that template parameter.\nC3: summary or clipboard failure: the page still opens and the message says to use \"Copy diagnostic summary\".\nC4: opening fails: the message shows the URL as selectable text.\nC5: Copy diagnostic summary and Export diagnostics unchanged.\nC6: the desktop shell's URL validator accepts this URL.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/report-problem.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/HelpSurface.test.ts#C1-C2 invokes the summary once, copies it, and opens only the exact template URL",
        "modules/ui-web/src/shell-v0/views/HelpSurface.test.ts#C3 still opens the report when the summary operation fails",
        "modules/ui-web/src/shell-v0/views/HelpSurface.test.ts#C3 still opens the report when the clipboard refuses",
        "modules/ui-web/src/shell-v0/views/HelpSurface.test.ts#C3 still opens the report when the clipboard throws",
        "modules/ui-web/src/shell-v0/views/HelpSurface.test.ts#C3 treats a missing summary as a copy failure and opens the report",
        "modules/ui-web/src/shell-v0/views/HelpSurface.test.ts#C4 shows the URL as selectable text if opening fails (copied: true)",
        "modules/ui-web/src/shell-v0/views/HelpSurface.test.ts#C4 shows the URL as selectable text if opening fails (copied: false)",
        "modules/ui-web/src/shell-v0/views/HelpSurface.test.ts#C4 reports a blocked popup through the shared browser opener",
        "modules/ui-web/src/shell-v0/views/HelpSurface.test.ts#C5 preserves export arguments and success/error receipts without opening a report"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "C6: open_guard_tests::urls_accept_only_web_and_mail additionally asserts that the exact Report a problem HTTPS template URL is accepted by validate_external_url. Existing accepted URLs and Windows-drive classification assertions remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, design it, then open the task register and take over the report issue work",
          "delegationLocation": "task t-20261023-c3e5b0 request",
          "delegationScope": "Task t-20261023-c3e5b0: the owner agreed to a user-initiated Report a problem action that copies the redacted diagnostic summary and opens the bug report template in the user's browser, with no automatic issue filing, and handed the design and build to the agent. It covers the Help & Support action and its failure handling; it does not authorise changing any other established contract.",
          "choice": "C1: one click runs `core.copy-diagnostic-summary`, writes the summary to the clipboard, and opens exactly `https://github.com/justsearch-app/justsearch/issues/new?template=bug_report.md`.\nC2: the opened URL carries nothing but that template parameter.\nC3: summary or clipboard failure: the page still opens and the message says to use \"Copy diagnostic summary\".\nC4: opening fails: the message shows the URL as selectable text.\nC5: Copy diagnostic summary and Export diagnostics unchanged.\nC6: the desktop shell's URL validator accepts this URL.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/report-problem.json",
      "newChecks": [
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::urls_accept_only_web_and_mail"
      ],
      "repaired": false
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "orchestrator",
  "session": "e9e48de5-620a-4c70-abc9-0fdb6be8f2d7",
  "verdict": "accept",
  "digest": "sha256:a8f85c303930b508d379bdf7541ff99ffb36ff4316a7460bf370bd42a03de30a"
}
```
