---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: hxq51l9r
---

# Test-intent entries

All checks are durable. UI expectations add Structured behavior; the Engine entries repair existing extraction behavior. The final UI checks also failed by assertion in a shared baseline clone with its own hard-linked dependencies (tmp/wp1.3a/ui-baseline-continuation.log: 17 assertion failures, 39 preservation passes). Java D4 evidence ran from a byte-identical candidate overlay in that temporary clone, isolating the script's before-worktree from the source repository; all ten repair cases failed by assertion on 738e6a443 and passed on the candidate. No acceptance record is authored by this builder.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/StructuredExtractionTest.java"
      ],
      "class": "New",
      "behaviour": "Parse complete JSON and validate required fields/types; retry malformed output; fail unappliable schemas; persist only accepted answers with their sources; branch context excludes contradictory rejected facts; crash-open Extract is incomplete; oversized instructions fail durably.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XT2",
          "quote": "S14.2 [XT2] (S3.2, ch. 9) A Structured send runs one extraction with the typed instruction and the box's schema. The result is shown as literal text: Markdown, HTML and citation-like strings inside the JSON stay literal and copyable, and no citation marks are added. The documents it was taken from stay reachable as sources. It reads the same live, after reload and after an Engine restart, including after a validation retry: rejected attempts are never joined to or shown as the result. Extract turns saved by the old window open in Search v3 with the same output.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XT3",
          "quote": "S14.3 [XT3] (ch. 4, new; broken today) Only output that parses as JSON and conforms to the schema (required fields present, declared types) is reported as a completed extraction. Malformed output with matching braces, valid JSON that violates the schema, and a schema the backend cannot apply each end in a retry or a visible failure with a reason. Holds in both windows (shared Engine). Decision D10.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XT5",
          "quote": "S14.5 [XT5] (S3.4) A schema that is not valid JSON, or not an object, is reported at the box before sending; nothing is sent, and instruction and schema are kept. Retries exhausted, model error, input too large and Engine unreachable end the turn as failed with a reason, live and after reload, never as a result that looks valid.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XT7",
          "quote": "S14.7 [XT7] (S3.6, ch. 8) Stop during a Structured run stops it; the turn shows as stopped live and after reload (the WP1.2 rule). If the Engine dies after a rejected attempt or during an attempt, after restart the turn shows as not completed, never as a completed extraction, and nothing re-runs without the user's action. Partial JSON is never shown as complete.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XT10",
          "quote": "S14.10 [XT10] (S3.9) A Structured follow-up sends the conversation's context the way Ask does: excluded messages and the context floor are honoured (S2.0), so \"extract the dates from the answer above\" works.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX designs, including WP1.3a Structured mode and the adopted scenarios and pass-2 findings.",
          "choice": "D10: validate parse and schema conformance in the shared Engine with its existing networknt dependency.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX designs, including WP1.3a Structured mode and the adopted scenarios and pass-2 findings.",
          "choice": "Pass-2 finding 5: rejected attempts never become subsequent or inherited context or apparent completed answers.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX designs, including WP1.3a Structured mode and the adopted scenarios and pass-2 findings.",
          "choice": "Scope note: WP1.3a repairs Extract; Summarize remains WP1.3b.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-structured-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/StructuredExtractionTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SelectionContextInjectorTest.java"
      ],
      "class": "New",
      "behaviour": "Extract refuses unreadable/excess selected members and per-document/total selection truncation; duplicate hits name one document; context-budget omissions are refused.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XT4",
          "quote": "S14.4 [XT4] (S3.3, ch. 6) Before sending, the window states what the extraction will read: the chosen files, or else the typed text plus this conversation (D7). That is what is sent. More files than the extraction reads, a chosen file that cannot be read, or input beyond the model's limit is never cut or skipped silently (D11). Several hits from one document count once. Instruction, schema and files are kept on refusal.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX designs, including WP1.3a Structured mode and the adopted scenarios and pass-2 findings.",
          "choice": "D11: refuse omitted Extract inputs rather than silently skipping or cutting.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX designs, including WP1.3a Structured mode and the adopted scenarios and pass-2 findings.",
          "choice": "Pass-2 finding 4, Extract part: cover every truncation threshold, including per-document allocation.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-structured-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SelectionContextInjectorTest.java#extractRefusesUnreadableOrExcessMembersAndDeduplicatesDocuments",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SelectionContextInjectorTest.java#extractRefusesEverySelectionTruncationThreshold",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SelectionContextInjectorTest.java#extractRefusesOmittedConversationContext"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.structured.test.ts"
      ],
      "class": "New",
      "behaviour": "Structured mode and labelled schema input; literal accepted JSON and reachable sources; schema refusal preserves drafts; unavailable chat preserves input; stop; file-count refusal and deduplicated selection; saved context and floor/exclusion; reloaded Retry/Edit/Branch prepare Structured without dispatch. XD1: undelivered requests fail visibly, keep instruction/schema/files live, and disappear after reload while the instruction draft survives.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XT1",
          "quote": "S14.1 [XT1] (S3.1) The Search v3 composer offers Structured (extract fields as JSON against a schema) beside Search, Ask and Delegate, by mouse and keyboard, announced to screen readers. Choosing it shows an editable schema box. The visible label and key hint match what Enter sends. A fresh window still starts in Search; the mode is not remembered between runs (WP1.1).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XT2",
          "quote": "S14.2 [XT2] (S3.2, ch. 9) A Structured send runs one extraction with the typed instruction and the box's schema. The result is shown as literal text: Markdown, HTML and citation-like strings inside the JSON stay literal and copyable, and no citation marks are added. The documents it was taken from stay reachable as sources. It reads the same live, after reload and after an Engine restart, including after a validation retry: rejected attempts are never joined to or shown as the result. Extract turns saved by the old window open in Search v3 with the same output.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XT4",
          "quote": "S14.4 [XT4] (S3.3, ch. 6) Before sending, the window states what the extraction will read: the chosen files, or else the typed text plus this conversation (D7). That is what is sent. More files than the extraction reads, a chosen file that cannot be read, or input beyond the model's limit is never cut or skipped silently (D11). Several hits from one document count once. Instruction, schema and files are kept on refusal.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XT5",
          "quote": "S14.5 [XT5] (S3.4) A schema that is not valid JSON, or not an object, is reported at the box before sending; nothing is sent, and instruction and schema are kept. Retries exhausted, model error, input too large and Engine unreachable end the turn as failed with a reason, live and after reload, never as a result that looks valid.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XT6",
          "quote": "S14.6 [XT6] (S3.5) With chat unavailable, Structured shows the existing unavailable reason (as S1.4); instruction, schema and chosen files are kept; Search keeps working.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XT7",
          "quote": "S14.7 [XT7] (S3.6, ch. 8) Stop during a Structured run stops it; the turn shows as stopped live and after reload (the WP1.2 rule). If the Engine dies after a rejected attempt or during an attempt, after restart the turn shows as not completed, never as a completed extraction, and nothing re-runs without the user's action. Partial JSON is never shown as complete.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XT8",
          "quote": "S14.8 [XT8] (S3.7, ch. 7) Retry and Edit on a Structured turn fork and run Structured again (never Ask), and say before the press which schema and inputs they will use; after a reload, where the turn's own schema and inputs are unknown, they behave as decision D3. Branch from a Structured turn sends nothing and leaves the composer in Structured for the next send. Ask and Delegate turns behave as in WP1.2.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XT9",
          "quote": "S14.9 [XT9] (S3.8, ch. 10) The schema box has a label and is reachable by keyboard; multi-line editing never sends by accident; it is readable in both themes, under forced colours and at narrow width with the document pane open, and does not hide the transcript's last turn. Its text survives switching mode or conversation in the same tab; on reload as decision D3b. One tab's schema is never shown in or sent by another tab.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XT10",
          "quote": "S14.10 [XT10] (S3.9) A Structured follow-up sends the conversation's context the way Ask does: excluded messages and the context floor are honoured (S2.0), so \"extract the dates from the answer above\" works.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX designs, including WP1.3a Structured mode and the adopted scenarios and pass-2 findings.",
          "choice": "D3: after reload Retry/Edit prepare the instruction using the current schema and stated inputs and wait for send.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX designs, including WP1.3a Structured mode and the adopted scenarios and pass-2 findings.",
          "choice": "D3b: schema is per-window memory only; reload discards it.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX designs, including WP1.3a Structured mode and the adopted scenarios and pass-2 findings.",
          "choice": "D7: no-file extraction reads typed text and this conversation.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX designs, including WP1.3a Structured mode and the adopted scenarios and pass-2 findings.",
          "choice": "D10: the Engine validates output; the client renders the accepted terminal literally.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX designs, including WP1.3a Structured mode and the adopted scenarios and pass-2 findings.",
          "choice": "D11: retain instruction/schema/selection on refusal.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX designs, including WP1.3a Structured mode and the adopted scenarios and pass-2 findings.",
          "choice": "XD1: when the request never reached the Engine, nothing is recorded, so no new saved failure state is added. Live, the turn ends as failed with the reason and the instruction, schema and files are kept. After reload that turn is absent (it was never recorded), never shown as a result, and the instruction is still in the composer draft as the draft rules already keep it. Failures after the Engine accepted the turn (retries exhausted, model error, input too large, schema the backend cannot apply) are recorded and show as failed with the reason after reload and after an Engine restart.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-structured-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.structured.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-search.test.ts"
      ],
      "class": "New",
      "behaviour": "The exact mode set now includes Structured beside the existing Search, Ask and Delegate; Search remains the fresh-window default. Other WP1.1 assertions remain.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XT1",
          "quote": "S14.1 [XT1] (S3.1) The Search v3 composer offers Structured (extract fields as JSON against a schema) beside Search, Ask and Delegate, by mouse and keyboard, announced to screen readers. Choosing it shows an editable schema box. The visible label and key hint match what Enter sends. A fresh window still starts in Search; the mode is not remembered between runs (WP1.1).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-structured-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-search.test.ts#offers Search, Ask, Delegate and Structured, and nothing else"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts"
      ],
      "class": "New",
      "behaviour": "Keep the single conversation scroller and all existing region-overflow prohibitions. Add a bounded native schema textarea, whose own text can scroll to keep the dock bounded.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XT9",
          "quote": "S14.9 [XT9] (S3.8, ch. 10) The schema box has a label and is reachable by keyboard; multi-line editing never sends by accident; it is readable in both themes, under forced colours and at narrow width with the document pane open, and does not hide the transcript's last turn. Its text survives switching mode or conversation in the same tab; on reload as decision D3b. One tab's schema is never shown in or sent by another tab.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX designs, including WP1.3a Structured mode and the adopted scenarios and pass-2 findings.",
          "choice": "D3b: schema text stays editable in memory for this tab.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX designs, including WP1.3a Structured mode and the adopted scenarios and pass-2 findings.",
          "choice": "Use an 8rem maximum schema-editor height with native text scrolling, so the dock stays bounded while editing a long schema.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-structured-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts#puts the ONE scroller inside the content surface"
      ]
    }
  ]
}
```

## Acceptance records
