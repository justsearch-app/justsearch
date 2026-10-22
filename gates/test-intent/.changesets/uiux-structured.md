---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: r3qplcrx
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
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/InvalidExtractionSchemaTest.java"
      ],
      "class": "New",
      "behaviour": "XD2: invalid required/properties/type structures, including nested and draft-07 schemas, fail with a reason naming the offending field; no completed answer or assistant context is saved, live or after reopening the store. Preserve valid nested/boolean schemas, local references, and supported explicit drafts.",
      "sources": [
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
          "scenario": "XD2",
          "quote": "XT3 \"a schema the backend cannot apply\" includes a schema whose structure is invalid JSON Schema (for example \"required\" not an array of strings, \"properties\" not an object, an unknown \"type\" value). Such a schema is refused before or at the request with a reason naming the problem; nothing is saved as a completed extraction. Validating the schema's own structure (against the JSON Schema meta-schema of the draft the backend applies, or the equivalent structural checks) is enough; choose the cheapest that covers these cases and say why.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX designs, including WP1.3a Structured mode and the adopted scenarios and pass-2 findings.",
          "choice": "D10 and XD2: validate the schema itself against the applied draft’s bundled meta-schema in the shared Engine. Use the installed validator and its packaged drafts; keep user-schema external loads blocked.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-structured-schema-repair.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/InvalidExtractionSchemaTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.structuredOutcome.test.ts"
      ],
      "class": "New",
      "behaviour": "XD2: each Structured completion/failure is announced exactly once through the existing message store and real toast renderer, politely (role=status, aria-live=polite), including two identical consecutive outcomes; failures include the reason. Rerendering does not repeat it and terminal handling leaves schema focus in place. Stream promises, message subscriptions and toast timers are closed before teardown.",
      "sources": [
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
          "scenario": "XD2",
          "quote": "XT9: when a Structured request completes or fails, assistive technology is told once, politely (reuse the live-region mechanism Search v3 already uses; no new announcer), and focus does not move unasked.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX designs, including WP1.3a Structured mode and the adopted scenarios and pass-2 findings.",
          "choice": "XD2: reuse Search v3’s existing window message/live-region channel to politely announce Structured completion and failure without moving focus. The failure remains visible in the transcript.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-structured-outcomes-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.structuredOutcome.test.ts"
      ],
      "repaired": true
    },
    {
      "class": "New",
      "evidence": "gates/test-intent/evidence/uiux-structured-live-output-repair.json",
      "repaired": true,
      "items": [
        "modules/ui-web/src/shell-v0/components/advisory/AdvisoryToastHost.identity.test.ts"
      ],
      "behaviour": "XD2: a success toast followed by a failure keeps the failure in its original polite live-region node when the older toast expires, without changing the expired success node to failure text. Both toast timers and the store subscription are cleaned up.",
      "sources": [
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
          "scenario": "XD2",
          "quote": "XT9: when a Structured request completes or fails, assistive technology is told once, politely (reuse the live-region mechanism Search v3 already uses; no new announcer), and focus does not move unasked.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/advisory/AdvisoryToastHost.identity.test.ts"
      ]
    },
    {
      "class": "New",
      "evidence": "gates/test-intent/evidence/uiux-structured-live-output-repair.json",
      "repaired": true,
      "items": [
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.acceptedOutput.test.ts"
      ],
      "behaviour": "XT2/XT3/XT10/XN1 and finding 5: after type-invalid or malformed extraction attempts, the old window shows and forwards only the accepted final answer in Structured and Ask follow-ups. Branches forward the accepted inherited answer. Preserve chunk-only terminals when finalResponse is omitted; an explicitly empty finalResponse never completes rejected chunks. Streams and reactive work settle before teardown.",
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
          "scenario": "XT10",
          "quote": "S14.10 [XT10] (S3.9) A Structured follow-up sends the conversation's context the way Ask does: excluded messages and the context floor are honoured (S2.0), so \"extract the dates from the answer above\" works.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XN1",
          "quote": "XN1 The old window's Structured option works as today, except the shared Engine repairs of S14.3 and S14.4.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XT2",
          "quote": "After validation rejects an attempt and a replacement succeeds, subsequent Ask/Structured context and inherited branch context use the accepted answer. History, unified-thread and API readers distinguish rejected attempts from completed answers, live and after restart. Use contradictory facts in the rejected and accepted outputs to check this.",
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
          "choice": "D10: validate parse and schema conformance in the shared Engine with its existing networknt dependency.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX designs, including WP1.3a Structured mode and the adopted scenarios and pass-2 findings.",
          "choice": "D11: refuse omitted Extract inputs rather than silently skipping or cutting.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.acceptedOutput.test.ts"
      ]
    },
    {
      "class": "New",
      "items": [
        "modules/ui-web/src/shell-v0/components/advisory/AdvisoryToastHost.overflow.test.ts"
      ],
      "repaired": true,
      "behaviour": "XT9/XD2: a toast displaced twice by the cap retains its occurrence identity and reaches an exposed live region once over its lifetime. Each unrelated warning/info/success also announces once. The three-toast cap, expiry and subscription/timer cleanup remain intact. Durable repair check; existing accepted checks are unchanged.",
      "sources": [
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
          "scenario": "XD2",
          "quote": "XT9: when a Structured request completes or fails, assistive technology is told once, politely (reuse the live-region mechanism Search v3 already uses; no new announcer), and focus does not move unasked.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-structured-terminal-overflow-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/advisory/AdvisoryToastHost.overflow.test.ts"
      ]
    },
    {
      "class": "New",
      "items": [
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.terminalOutput.test.ts"
      ],
      "repaired": true,
      "behaviour": "XT3/XT5/XN1: when all Structured attempts are rejected and the stream ends in an error, retain the reason, remove rejected output from the live answer and prevent it entering follow-up context. XN1/XN3 preservation repair: free-chat, Ask and summary dispatches keep streamed text when the done answer is explicitly empty. The accepted empty-Structured-terminal safeguard is kept unchanged. Durable checks; no changes to accepted tests.",
      "sources": [
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
          "scenario": "XN1",
          "quote": "XN1 The old window's Structured option works as today, except the shared Engine repairs of S14.3 and S14.4.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XN3",
          "quote": "XN3 Ask and Delegate streaming, stop, late stop, sessions sidebar, context bar, J/K run navigation.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XN1",
          "quote": "Regression from 255b35ca3: when a turn's terminal carries an empty answer while text streamed, the old window now shows \"The model returned no result\" for free chat, Ask with sources and summaries. Use the terminal answer only when it is non-empty; otherwise keep the streamed text, as before. Item 2 must still hold (a rejected Structured run ends in an error, not an empty terminal; check both).",
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
          "choice": "D10: validate parse and schema conformance in the shared Engine with its existing networknt dependency.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX designs, including WP1.3a Structured mode and the adopted scenarios and pass-2 findings.",
          "choice": "D11: refuse omitted Extract inputs rather than silently skipping or cutting.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-structured-terminal-overflow-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.terminalOutput.test.ts"
      ]
    },
    {
      "class": "New",
      "items": [
        "modules/ui-web/src/shell-v0/components/advisory/AdvisoryToastHost.focus.test.ts"
      ],
      "repaired": true,
      "behaviour": "XT9/XD2: Structured completion/failure and general notices retain a focused toast action within the three-toast cap. Displace the next eligible toast; after the arrival expires, restore the sibling in its cached DOM with live announcements off. All timers and subscriptions settle at teardown. Durable checks; previous accepted tests and entries are unchanged.",
      "sources": [
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
          "scenario": "XD2",
          "quote": "XT9: when a Structured request completes or fails, assistive technology is told once, politely (reuse the live-region mechanism Search v3 already uses; no new announcer), and focus does not move unasked.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "A toast containing focus (or hovered, if the host already treats hover as engagement) must not be displaced; displace the next eligible one, or hold the new notice until one is free, without repeating announcements (keep your cache/silencing repairs). Check the general toast case too: any notice, not only Structured ones.",
          "location": "task t-20261019-bd718f request"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-structured-interruption-focus-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/advisory/AdvisoryToastHost.focus.test.ts"
      ]
    },
    {
      "class": "New",
      "items": [
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.interruptedOutput.test.ts"
      ],
      "repaired": true,
      "behaviour": "XT7/XN1: terminate real ReadableStream delivery after rejected and partial extraction chunks by connection error, EOF without terminal, timeout and user cancellation. Only a failure/stopped reason remains, never JSON or an assistant context row. Preserve Free chat and Ask partial-answer behaviour on connection loss. Await each stream and reactive work; do not change accepted checks. Durable checks.",
      "sources": [
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
          "scenario": "XN1",
          "quote": "XN1 The old window's Structured option works as today, except the shared Engine repairs of S14.3 and S14.4.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "A rejected or partial Structured answer must never stay visible as an answer after any failure path, including connection loss, aborts and timeouts; text the user sees afterward is only the failure note. Normal (non-Structured) answers keep their current behaviour on connection loss.",
          "location": "task t-20261019-bd718f request"
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
          "choice": "D10: validate parse and schema conformance in the shared Engine with its existing networknt dependency.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX designs, including WP1.3a Structured mode and the adopted scenarios and pass-2 findings.",
          "choice": "D11: refuse omitted Extract inputs rather than silently skipping or cutting.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-structured-interruption-focus-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.interruptedOutput.test.ts"
      ]
    }
  ]
}
```

Repair entries appended by builder session 5ha3t9t3 on 2026-10-22. Original entries and verifier records are retained. These additions require independent acceptance on the final digest.

Follow-up entries appended by builder session r3qplcrx on 2026-10-22. Existing entries and acceptance records are retained. Both new test files are durable; evidence records ten assertion failures before, thirteen passes after, including three preservation controls. Independent acceptance is pending.

Third repair entries appended by builder session 84aj3121 on 2026-10-22. The existing entries and acceptance records are retained; new checks use baseline 289dff32f and default dependency linking. Independent acceptance is pending.

Fourth-verifier repair entries appended by builder session z9p1afsx on 2026-10-22. Evidence uses f8002f421 and default dependency linking. Existing entries and acceptance records are retained; independent acceptance is pending.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "w2mq0wtk",
  "verdict": "accept",
  "digest": "sha256:e979f512114786adbc09fac1bb3af666a6655ec2306044c6f6ef81a95d432c1c",
  "note": "Test intent only: sources and changed expectations match the agreed scenarios and delegated decisions. Independent Java evidence: 10 assertion failures before, 10 passes after; UI evidence: new checks fail by assertion before and pass after. Product verdict remains fail for S14.3 invalid schema acceptance and S14.9 accessibility/narrow pane findings; this record is not product sign-off."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "fq5j5k0c",
  "verdict": "accept",
  "digest": "sha256:f35330851a224518e61f299a44931d94fefdd6c48d68ec175eb849111947b879",
  "note": "Test intent only. Reviewed before-state tests, agreed scenarios and delegation, then diffs; changed expectations are warranted. Independent Java evidence: 18 candidate cases pass; 10 original and 7 schema-repair assertion failures before, with one preservation case passing before. Independent UI evidence: 19 named candidate cases pass with assertion fail-before. Product verdict remains fail for XD2 duplicate exposed failure announcements and old-window rejected output displayed and reused as context. This record is not product sign-off."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "tno53db9",
  "verdict": "accept",
  "digest": "sha256:09542d06615f88d2fd89e9ea08f1e6b2b86790af1bc2710a92d9a97869bdcdd5",
  "note": "Test intent only: sources and expectations match the agreed and delegated scenarios. Independent scratch Java and frontend evidence passes with assertion fail-before and preservation controls. Product sign-off is withheld for toast overflow and exhausted-retry display findings; an empty-terminal fault-injection regression is also recorded."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "fu2cgmco",
  "verdict": "accept",
  "digest": "sha256:e1cd2654c6f1a87f777629db659676009a4ed6fb508534501ad24e1a975a598c",
  "note": "Reviewed sources and prior-state expectations; fresh run-evidence passes candidate checks and reproduces assertion fail-before for every repaired check id. Product verification fails separately on old-window connection loss and focused-toast displacement."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "mc8fa9g6",
  "verdict": "accept",
  "digest": "sha256:ea4c75f13a1f21099e0bd943c92d5ac28149b0eed55b202d0a2c6c1f296a947b",
  "note": "Test intent only: reviewed prior-state tests, scenarios and delegated decisions, then diffs. Fresh scratch evidence passes all named checks with assertion fail-before for repaired check ids. Product verification fails separately on focus movement within a retained toast and stopped Structured output. The broad UI Java run also encountered missing esbuild. This is not product sign-off."
}
```
