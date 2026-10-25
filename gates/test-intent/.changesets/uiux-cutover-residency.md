---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: codex-residency-builder-7-fendcaap
---

# Test-intent entries

NR15/RR15 choices are agent-adopted under the separately quoted delegation, not owner-verbatim scenarios. This grouping adds to the existing historical uiux-cutover changeset and does not accept or replace its missing d5 acceptance. Final official producer ran in an owned shared clone, without source commits, against exact baseline 12c8644475714846a23cb4b8ebe736b972d57cba with final fixtures. It records 404 candidate passes, 122 assertion failures before and 282 preservation passes before, zero skips and no exception/compile/environment failures. Producer exits 1 because named preservation controls intentionally pass before. Earlier attempts and the intermediate two-case outcome producer are excluded from final evidence and retained under tmp/wp1.5a/. The final producer includes the late forced Structured scope repair and all outcome/Ask preservation checks on the exact final production source.

Changed old expectations are explicit: the branch successful-terminal and two external-lock transcript fixtures are instrumentation corrections; the 11 context cases gain RR15 slot retirement/review requirements. They are not described as signature-only adaptations. The Structured error announcement case also explicitly reviews/discards unknown delivery before a fresh repeated submission; its done case remains preservation. No old branch/context/honesty/Structured announcement oracle was deleted. The two honesty fixtures are directly affected lock-preservation checks, not an unrelated expectation change. Storage faults are injected instance-method write failures, not native quota evidence. Two Storage realms are unit isolation, not browser tab/runtime proof. Real production UI-to-HTTP/controller/model proof and the full final gates belong to the fresh verifier.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Correct the successful dispatch fake to emit done BEFORE EOF, as its existing terminal comment intended. The previous EOF produced STREAM_INCOMPLETE; a late event after EOF could not repair it. All existing assertions remain, including the two forks of the same base, child identities and 3 / 3 pager. EOF without terminal is separately tested as unknown delivery blocking a second submission in the new residency file. This changes the wire fixture to represent its intended successful completion, not the branch oracle.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request; agent-adopted NR15/RR15 in the supplied agreed design and scenarios, 2026-10-25",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility and preservation of human Send.",
          "choice": "Preserve existing branches/pager and accepted streaming behavior. RR15: unknown transport delivery cannot authorize blind resend; only a successful terminal or CANCELLED retires the unresolved task.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-cutover-residency.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.honesty.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Two external-lock fixtures establish their genuine saved transcript before the external lock arrives. Previously they tried to create a successful fake answer while already known locked, which the new required pre-dispatch write guard refuses. The remedy target/navigation and sticky-lock assertions are unchanged; no assertion or scan is removed. The source-copy scan also continues to read the new residency test file, whose status fixture now uses available:false and the actual operating state without a redundant mode field.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request; agent-adopted NR15/RR15 in the supplied agreed design and scenarios, 2026-10-25",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility and preservation of human Send.",
          "choice": "NR15/RR15: Preserve the existing 423 terminal, lock adoption and inaccessible-history behavior. Explicit human requests retain conversation write/context guards. A known locked history cannot create a successful new answer; these fixtures establish the readable answer before the external lock while retaining every original remedy/navigation and sticky-lock assertion.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-cutover-residency.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.honesty.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts"
      ],
      "class": "New",
      "behaviour": "RR15 explicitly retires the known unissued question before DIFFERENT follow-up submissions in seven failed-write-refresh cases, X15, and the load/reload failed-history cases. X28 intentionally replaces parallel unresolved submissions with a visible blocked second send, explicit discard and companion review before the other conversation sends. All original context, draft and target assertions remain.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request; agent-adopted NR15/RR15 in the supplied agreed design and scenarios, 2026-10-25",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility and preservation of human Send.",
          "choice": "RR15: Reserveinslotbeforefirstdependentawait/fork; ACKretentionanduncertaindeliveryBEFOREissuance/destructiveclear. Failedwritesmustnotclaimsuccessorremoveonlycopy. Secondunresolvedsubmissionrequiresvisibleexplicitreplacement/blocks, notoverwrite. Retiringtaskmustretainanynewerunsavedcompanions (attachmentmaykeepbounded draft-only recoveryuntilrestored/discarded); acknowledgecompaniondraftwritebeforeclear. Onlyconfirmedpre-admissionrefusalshowsQuestionnotsent andordinaryRetry/taskboundLoadanyway. UnknowntransportdeliveryshowsDeliveryunknown/checksavedconversation, noblindresend. Everyreload/duplicatedtabfreshowner/reviewonly.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-cutover-residency.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#marks a saved floor unconfirmed when refresh fails and blocks Ask without losing its draft",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#marks a saved compact unconfirmed when refresh fails and blocks Ask without losing its draft",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#marks a saved summary unconfirmed when refresh fails and blocks Ask without losing its draft",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#marks a saved restore unconfirmed when refresh fails and blocks Ask without losing its draft",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#marks a saved exclude unconfirmed when refresh fails and blocks Ask without losing its draft",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#marks a saved include unconfirmed when refresh fails and blocks Ask without losing its draft",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#marks a saved include-all unconfirmed when refresh fails and blocks Ask without losing its draft",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#requires explicit retirement before sending in another conversation while context waits (X28/RR15)",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#keeps a refused question visible beside newer input through retry and recovery (X15)",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#warns after a failed history read on load and refuses Ask with its draft kept",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#warns after a failed history read on reload and refuses Ask with its draft kept"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserve the unchanged effective-context floor, exclusion, summary, ordering, draft, reload and interrupted-read checks named here; each passes before and after.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request; agent-adopted NR15/RR15 in the supplied agreed design and scenarios, 2026-10-25",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility and preservation of human Send.",
          "choice": "NR15: New typing/IME/programmatic same-text assignment, conversation switch, handoff, New/removal/navigation/reload during refusal/load must not be overwritten or sent by an older callback. Retain the refused question and newer draft according to ownership. Accepted streaming/stop/late-stop/context/record persistence remains. All original C4/A1/FC15 and CD3/TD10a safeguards remain.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/uiux-cutover-residency.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#sets the floor on the turn’s own question, re-reads /history, and dims what is above",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#keeps the divider’s controls REACHABLE — separator is on the hairline, not the row",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#restores the full context from the divider’s own control",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#re-reads /history even when the write was REFUSED, and says which act failed",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#compacts up to a turn, then discloses and EDITS the summary in place",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#keeps a REFUSED summary edit open, with the reader’s correction still in it",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#says the summarization did not happen when the compaction came back empty",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#excludes both of the turn’s messages, dims it, and counts it in the bar",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#offers INCLUDE for a turn already hidden, and puts it back",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#puts every hidden turn back in one act from the bar",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#renders no ⋯ trigger on an agent turn, whose ids belong to the run plane",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#withholds the trigger on SETTLED turns too while the window is streaming",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#renders no ⋯ trigger on a LIVE turn — its handle is positional until the record speaks",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#renders no meter until a turn has reported an occupancy",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#reads the occupancy off the turn’s own terminal and draws it against n_ctx",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#leaves the meter behind when the reader claims another conversation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#opens the shared inspector on a prompt that agrees with the transcript",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#sends the earlier turns as context, oldest first, on the conversation’s own id",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#sends no context with the first question of a new conversation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#leaves out excluded messages and everything above the floor, and leads with the summary",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#does not bring older turns back when the floor’s own message is excluded (S2.9)",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#places a floor that sits on a message not shown here by the saved order",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#leaves out a hidden message from INSIDE a combined answer, as the bar counts it",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#starts at a floor set INSIDE a combined answer, sending nothing saved before it",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#starts at a floor on the LAST message of a combined answer, sending only that message",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#does not send older turns before the conversation’s context settings have arrived",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#refuses the follow-up when the conversation’s context settings cannot be read",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#shows in the inspector exactly the messages it sends — one hidden answer, not its turn",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#shows each note on its own turn live, after the record refresh, and after a reload",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#counts unique hidden messages not shown here beside the hidden turns on screen",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#shows and undoes exclusions that are ALL off screen, without inventing a turn count",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#clears every recorded exclusion once each, on screen and off",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#says the context starts at a message not shown here, shows and edits its summary, and restores",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#reports a PARTIALLY refused Include all as a failure and shows what was really saved",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#does not wipe the bar when the reload after Include all fails",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#marks Include all unconfirmed when its reload failed",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#marks Restore unconfirmed when its reload failed",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#leaves the bar on the saved floor when Restore is refused AND its reload fails",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#queues the next write until the previous refresh has settled",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#serializes 'R1' and confirms saved context before the next Ask after refresh failure",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#serializes 'R4' and confirms saved context before the next Ask after refresh failure",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#serializes 'R5' and confirms saved context before the next Ask after refresh failure",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#serializes 'R10' and confirms saved context before the next Ask after refresh failure",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#serializes 'include' and confirms saved context before the next Ask after refresh failure",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#serializes 'compact' and confirms saved context before the next Ask after refresh failure",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#serializes 'summary' and confirms saved context before the next Ask after refresh failure",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#keeps an honest refusal after context recovers and the composer becomes \"\" (X20)",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#keeps an honest refusal after context recovers and the composer becomes \"A different draft\" (X20)",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#keeps the refused-send reason through failed background retries (X19)",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#re-reads interrupted first history on reattach after screen switch and retries failures (X14a)",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#re-reads interrupted first history on reattach after reparent and retries failures (X14a)",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#refuses follow-up after an interrupted first history read and a failed re-read (X14b)",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#confirms a lost write response by reading history before sending Ask",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#retries unconfirmed context on backoff and reconnect and cancels retries on disconnect",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#sends the submitted question after a context wait and keeps later input: A newer unsent question",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#sends the submitted question after a context wait and keeps later input: Submitted question",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#refuses Ask when a load read is still pending and its confirmation read fails",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#recovers a failed load history read on backoff without another action",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#waits for queued context writes before Ask and sends the question once",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#Include all uses the exclusions saved by an earlier queued action after its failed refresh",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#automatically confirms saved context when a backoff retry succeeds",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#keeps the conversation the reader moved to on ITS saved state while the undo finishes"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts"
      ],
      "class": "New",
      "behaviour": "NR15/RR15 human demand, correct shape and scope, zero-document model/typed Structured/passage Summarize, typed refusal rollback, authoritative true/false force and dwell, exactly one 200/202 continuation, duplicate lock, assignment/IME/context/scope/lifecycle revocation, strict whitelist and UTF8 caps, reserve/uncertain-delivery/retirement ACK failures, stale writers and isolated/copy Storage realms, input precedence and tab-session review-only recovery. No backend policy, history store, force permission or excerpts are persisted. Forced Structured completion consumes its own original one-send scope; copied outbound paths confer no ownership over newer file preparation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request; agent-adopted NR15/RR15 in the supplied agreed design and scenarios, 2026-10-25",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility and preservation of human Send.",
          "choice": "## NR15: preserve human Send and residency behavior before cutover (agent-adopted 2026-10-25)\n\nIndependent source challenge and running policy/HTTP triage identify this as C1/S2 preservation, not lower-tier polish. Old main marks explicit human dispatch; Search omitted it, treated typed residency refusals as generic answer failures and gates unloaded chat as offline. No backend auth/residency-policy weakening, new durable force preference or changed chat-enable defaults is authorized.\n\n1. Human Ask/Structured/model-only/compatibility/Retry/Edit requests retain human demand semantics when installed chat is transiently unloaded; exact supported text/files/shape/context once, no silent scope widening. Model-only and typed Structured must not inherit unrelated indexed-document/agent-tool prerequisites; grounded Ask retains its own prerequisites. Explicit off/uninstalled/failed states stay honestly unavailable.\n2. Pre-admission pressure/loading/refusal creates no message presented as sent/answered/saved. Preserve recoverable original unsent question and newer user input under existing ownership/save points; end busy truthfully. No new force permission inferred from stale UI.\n3. Ordinary retry respects current reason/retry interval. Explicit Load anyway is offered only when returned/authoritative forceAllowed permits, including allowed dwell; false/absent never authorizes force. Server revalidation remains authoritative.\n4. An unchanged, still-owned refused task may load and retry exactly once after one explicit activation, even with202/pending readiness. A load action without a refused task loads only. Failure/transport loss keeps input recoverable; repeated activation cannot duplicate send.\n5. New typing/IME/programmatic same-text assignment, conversation switch, handoff, New/removal/navigation/reload during refusal/load must not be overwritten or sent by an older callback. Retain the refused question and newer draft according to ownership; never silently resend newer contents as the old task. If no existing owner can durably retain both necessary inputs, return a bounded explicit retention proposal before building new state.\n6. Stop before admission versus leaving/unmount retains existing distinct meaning and backend stop semantics; later load cannot start an abandoned/stopped question or duplicate it after return. Accepted streaming/stop/late-stop/context/record persistence remains.\n7. Delegate/Ctrl-Cmd-Enter remain agent semantics; automatic titles/viewing/polling/reload/background work never receive human loading/force authority. No automatic force or sticky permission.\n8. Ready→unloaded→loading→ready, explicit off, failure/holds/stopping updates reason/actions correctly. No new always-ready flag, fabricated successful response or physical-GPU/Windows claim. Real controlled production HTTP/gate fixtures are labeled as such.\n\nAll original C4/A1/FC15 and CD3/TD10a safeguards remain. These outcomes clarify already shipped human Send/refusal preservation; fresh builder/committed independent real boundary proof required. Native physical residency transitions remain explicit verification limits when unavailable.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request; agent-adopted NR15/RR15 in the supplied agreed design and scenarios, 2026-10-25",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility and preservation of human Send.",
          "choice": "settled refusal recovery protocol (agent-adopted after independent design review)\n\nAdopt source design with ALL five reviewer corrections. Human intent only atsv3Ask; typedrefusal/no fake savedturn; humanreadiness distinctgrounded/model/typedStructuredvsDelegate; capturedtaskforce200/202once; noauto-bypass/backgroundhumanload. PreserveC4/FC15/CD3/TD10a andexistingStop/423/unknownstreamsemantics.\n\nPersistedwhitelistONLYuserauthoredraw/edit text,schema,minimaltypedrequest/record/shape/place/scopeidentifiers. No outboundquestiontransformedwithrankingtrace,selectedtext/fileexcerpt,resolvedhistory,backendtrace,secrets,forcepermission,callback/progress. In-memorycapturedtaskcanretaintransformedbody; restoremustreview/reselectwhencannotreconstructexactscope,never substitutehistory/currentselection/fullfile. Savedturnactionsstoreidentityratherthancopybackendcontent.\n\nUseDraftPersistence's opt-in ACKNOWLEDGED SESSIONSTORAGE attachment, rawlocaldraftkeyandlegacyprotocolUNCHANGED. Fixedversion1 namespace, main or fixedsupportedcompatibility-shape discriminator; oneunresolvedtaskperdeclaredowner, maxcurrent+originatingplace drafts, no keysbymount/CID/visitedhistory. Strictversion/type/unknownfieldvalidation; opaqueattachmentincarnationandexpectedstoredbytesrejectstaleoverlappingwriters. StoredIDsnevercallbackauthority; everyreload/duplicatedtabfreshowner/reviewonly, nocross-tabexactly-oncepromise. Incomingtaskwinscurrentcomposer; invalid/mismatchedattachmentnevererasesrawdraft. Rawdraft/newattachmentprecedenceexplicitlytested.\n\nBounds: serializedJSON UTF8<=262144bytes; eachusertext/draftsnapshot<=65536UTF8bytes; schema<=16384UTF8bytes; max128scopeIDs, each<=4096UTF8bytes; max2distinctdraftplaces; oneunresolvedtask. Excessrefusebeforeissuancewithouttruncation, preserveexistingraw/live/storedcopies. Reserveinslotbeforefirstdependentawait/fork; ACKretentionanduncertaindeliveryBEFOREissuance/destructiveclear. Failedwritesmustnotclaimsuccessorremoveonlycopy. Secondunresolvedsubmissionrequiresvisibleexplicitreplacement/blocks, notoverwrite. Retiringtaskmustretainanynewerunsavedcompanions (attachmentmaykeepbounded draft-only recoveryuntilrestored/discarded); acknowledgecompaniondraftwritebeforeclear.\n\nAttachmentrecoveryissurvivingTABSESSIONonly, notpermanenthistory/fulltaborappclosure/olderclients. Exposeextra-copy sessionlimit plainlywhenretained, preservecurrentrawdraftunderitsnormaldurablerules. This is an explicit bounded-retention decision; no claimbothA+Bsurvivefullclosure. Same-tabreload/hide/disconnectarecovered; no newlocalhistory/store/framework.\n\nOnlyconfirmedpre-admissionrefusalshowsQuestionnotsent andordinaryRetry/taskboundLoadanyway. UnknowntransportdeliveryshowsDeliveryunknown/checksavedconversation, noblindresend; emptyfailedlookup/textmatch/StopUUIDarenotdeduplicationproof. Ifunresolved,newsubmissionrequiresfreshclearreview; no backendidempotencystore. Restoredcopiesneverforce/sendautomatically.\n\nStop/revocationlatchedsynchronouslythroughlateLOADING/refusal/transportloss/loadsuccess; nonecanreinstatecontinuation. OnlybackendCANCELLEDprovesstopped savedturn. Stopped/restoredtasksneedfreshreviewednewsubmission/newdispatchUUID. ExistingearlyStopregistryentrybound/noageexpiryunchanged. BeforefollowupissuanceStoponlyrevokeslocalcontinuation, no claimloadcancelled. Sourcecaps/whitelist/ownership/recoverylimitsaretestedsaved-statecontract, notimplementationoptional.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-cutover-residency.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#marks explicit human Send and removes an unaccepted optimistic answer",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#allows human Send from positively unloaded chat without granting Delegate or force",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#zero documents do not block human core.free-chat (unloaded=false)",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#zero documents do not block human core.free-chat (unloaded=true)",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#zero documents do not block human core.extract (unloaded=false)",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#zero documents do not block human core.extract (unloaded=true)",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#retains A and newer B across same-tab reload, review-only",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#one explicit force continues captured A exactly once after 200 without a READY poll",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#one explicit force continues captured A exactly once after 202 without a READY poll",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#unknown transport delivery never claims not sent or offers blind retry",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#uses authoritative GPU_BUSY forceAllowed=true without automatic dispatch",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#uses authoritative GPU_BUSY forceAllowed=false without automatic dispatch",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#uses authoritative GAME_MODE forceAllowed=true without automatic dispatch",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#uses authoritative GAME_MODE forceAllowed=false without automatic dispatch",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#uses authoritative RELOAD_BACKOFF forceAllowed=true without automatic dispatch",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#uses authoritative RELOAD_BACKOFF forceAllowed=false without automatic dispatch",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#uses authoritative LOADING forceAllowed=false without automatic dispatch",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#uses authoritative HELD_MODEL forceAllowed=false without automatic dispatch",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#different during force revokes continuation and preserves the retained question",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#same during force revokes continuation and preserves the retained question",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#away-and-back during force revokes continuation and preserves the retained question",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#empty during force revokes continuation and preserves the retained question",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#ime during force revokes continuation and preserves the retained question",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#hide during force revokes continuation and preserves the retained question",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#pagehide during force revokes continuation and preserves the retained question",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#disconnect during force revokes continuation and preserves the retained question",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#New during force revokes continuation and preserves the retained question",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#Stop during force revokes continuation and preserves the retained question",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#a second unresolved submission cannot replace retained A with newer B",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#refuses issuance when session retention fails, preserving raw and live input",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#rejects singleton-array shape without touching any stored or live text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#rejects singleton-array effort without touching any stored or live text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#rejects singleton-array delivery without touching any stored or live text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#EOF without a terminal remains unknown delivery and blocks a second submission",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#changing the chosen file through the card action during force revokes the old task",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#malformed attachment preserves its bytes and blocks unsafe issuance",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#version attachment preserves its bytes and blocks unsafe issuance",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#unknown-field attachment preserves its bytes and blocks unsafe issuance",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#oversize attachment preserves its bytes and blocks unsafe issuance",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#UTF8 input bounds refuse before issuance, without truncation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#unknown-delivery retention ACK failure blocks issuance before clearing the composer",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#failed retirement keeps A and the newer B companion in existing bytes and live input",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#retiring a task after navigation retains the originating newer draft",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#initial context wait respects same-text assignment without losing captured A",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#initial context wait respects discard without losing captured A",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#done retires the unresolved slot and allows another fresh submission",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#CANCELLED retires the unresolved slot and allows another fresh submission",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#overlapping same-owner mounts reject a stale writer without overwriting the first task",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#main and known compatibility shape owners retain independent unresolved slots",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#schema during force requires review and never dispatches the abandoned continuation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#effort during force requires review and never dispatches the abandoned continuation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#tier during force requires review and never dispatches the abandoned continuation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#selection during force requires review and never dispatches the abandoned continuation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#clear selection during force requires review and never dispatches the abandoned continuation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#context during force requires review and never dispatches the abandoned continuation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#handoff during force requires review and never dispatches the abandoned continuation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#navigation during force requires review and never dispatches the abandoned continuation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#reload during force requires review and never dispatches the abandoned continuation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#free-chat compatibility sends subsequent turns with zero documents and preserves newer input",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#a model-answer turn action never clears or restores unrelated composer text on success",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#a model-answer turn action never clears or restores unrelated composer text on refusal",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#scope-count cap blocks issuance without truncating live inputs or old copies",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#scope-id-UTF8 cap blocks issuance without truncating live inputs or old copies",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#schema-UTF8 cap blocks issuance without truncating live inputs or old copies",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#serialized-UTF8 cap blocks issuance without truncating live inputs or old copies",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#invalid task-field attachment fails closed and leaves exact durable bytes and raw text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#invalid draft-field attachment fails closed and leaves exact durable bytes and raw text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#invalid payload-field attachment fails closed and leaves exact durable bytes and raw text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#invalid scope-type attachment fails closed and leaves exact durable bytes and raw text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#invalid scope-count attachment fails closed and leaves exact durable bytes and raw text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#invalid raw-type attachment fails closed and leaves exact durable bytes and raw text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#invalid raw-UTF8 attachment fails closed and leaves exact durable bytes and raw text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#invalid draft-UTF8 attachment fails closed and leaves exact durable bytes and raw text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#invalid schema-type attachment fails closed and leaves exact durable bytes and raw text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#invalid schema-UTF8 attachment fails closed and leaves exact durable bytes and raw text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#invalid place-type attachment fails closed and leaves exact durable bytes and raw text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#invalid record-type attachment fails closed and leaves exact durable bytes and raw text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#invalid reselection-type attachment fails closed and leaves exact durable bytes and raw text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#invalid effort-value attachment fails closed and leaves exact durable bytes and raw text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#invalid delivery-value attachment fails closed and leaves exact durable bytes and raw text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#invalid three-places attachment fails closed and leaves exact durable bytes and raw text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#invalid typed-version attachment fails closed and leaves exact durable bytes and raw text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#invalid incarnation-type attachment fails closed and leaves exact durable bytes and raw text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#raw takes composer precedence while the session draft remains recoverable",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#incoming takes composer precedence while the session draft remains recoverable",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#tab-isolated Storage realms retain distinct tasks and a copied attachment restores only for review",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#summarize preserves its exact live passage but persists no excerpt, history or force authority",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#Explain retention excludes the embedded backend ranking trace and preserves user instructions",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#an overlapping stale owner cannot retire a task after its live owner retries back to the same inputs",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#human Retry/Edit of saved core.rag-ask keep the intended shape and exact text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#human Retry/Edit of saved core.free-chat keep the intended shape and exact text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#an explicit force retains its lock through the follow-up dispatch",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#a stale force control cannot load or dispatch after explicit retirement",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#a transport load failure retains A and newer input without a follow-up dispatch",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#a http load failure retains A and newer input without a follow-up dispatch",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#a typed load failure retains A and newer input without a follow-up dispatch",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#untyped HTTP rejection remains delivery unknown with no inferred force authority",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#force continuation rechecks off status without granting background readiness",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#force continuation rechecks failed status without granting background readiness",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#force continuation rechecks loading status without granting background readiness",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#exact UTF8 user-text and scope limits are accepted, with captured effort and scope intact",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#exact schema UTF8 limit is accepted for typed Structured",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#Stop through late transport loss stays revoked and cannot become a saved stopped claim",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#choosing a file after a free-chat handoff replaces its model-only preparation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#supported Summarize with a passage does not acquire an indexed-document prerequisite",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#the real chosen-result Ask control remains operable for human admission while unloaded",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#confirmed deletion during force revokes the old target without sending Stop",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#ordinary Retry becomes available after its interval but the timer never submits",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#context that changes during the follow-up confirmation cannot replace the captured force context",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#a forced Structured success consumes only its original one-send file preparation"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserve these six before-state controls: late-LOADING Stop never creates a saved-stopped claim, compatibility composer clears its own draft, explicit off blocks Send, initial context wait preserves newer text, navigation invalidates an older wait, and grounded Ask retains indexed-document requirements.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request; agent-adopted NR15/RR15 in the supplied agreed design and scenarios, 2026-10-25",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility and preservation of human Send.",
          "choice": "NR15: Model-only and typed Structured must not inherit unrelated indexed-document/agent-tool prerequisites; grounded Ask retains its own prerequisites. Explicit off/uninstalled/failed states stay honestly unavailable. New typing/IME/programmatic same-text assignment, conversation switch, handoff, New/removal/navigation/reload during refusal/load must not be overwritten or sent by an older callback. Stop before admission versus leaving/unmount retains existing distinct meaning and backend stop semantics. Accepted streaming/stop/late-stop/context/record persistence remains.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/uiux-cutover-residency.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#Stop during admission cannot be undone by a late typed LOADING refusal",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#compatibility free-chat composer owns clearing its submitted draft",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#ready then explicit off keeps human Send unavailable and draft intact",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#initial context wait respects newer text without losing captured A",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#initial context wait respects navigation away and back without losing captured A",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#grounded Ask still requires indexed documents and never falls through to model or Delegate"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.structuredOutcome.test.ts"
      ],
      "class": "New",
      "behaviour": "RR15 requires visible review and explicit retirement before repeating a submission whose untyped terminal error provides no saved-delivery proof. Both original polite announcements, exact counts, error wording, live-region properties, focus and rerender assertions remain.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request; agent-adopted NR15/RR15 in the supplied agreed design and scenarios, 2026-10-25",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility and preservation of human Send.",
          "choice": "RR15: Reserveinslotbeforefirstdependentawait/fork; ACKretentionanduncertaindeliveryBEFOREissuance/destructiveclear. Failedwritesmustnotclaimsuccessorremoveonlycopy. Secondunresolvedsubmissionrequiresvisibleexplicitreplacement/blocks, notoverwrite. Retiringtaskmustretainanynewerunsavedcompanions (attachmentmaykeepbounded draft-only recoveryuntilrestored/discarded); acknowledgecompaniondraftwritebeforeclear. Onlyconfirmedpre-admissionrefusalshowsQuestionnotsent andordinaryRetry/taskboundLoadanyway. UnknowntransportdeliveryshowsDeliveryunknown/checksavedconversation, noblindresend. Everyreload/duplicatedtabfreshowner/reviewonly.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-cutover-residency.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.structuredOutcome.test.ts#XD2 announces Structured error exactly once politely without taking focus"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.structuredOutcome.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserve two independent successful Structured completion announcements without repeated announcements from record refresh or rerender, and keep focus in the schema field.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request; agent-adopted NR15/RR15 in the supplied agreed design and scenarios, 2026-10-25",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility and preservation of human Send.",
          "choice": "RR15: Reserveinslotbeforefirstdependentawait/fork; ACKretentionanduncertaindeliveryBEFOREissuance/destructiveclear. Failedwritesmustnotclaimsuccessorremoveonlycopy. Secondunresolvedsubmissionrequiresvisibleexplicitreplacement/blocks, notoverwrite. Retiringtaskmustretainanynewerunsavedcompanions (attachmentmaykeepbounded draft-only recoveryuntilrestored/discarded); acknowledgecompaniondraftwritebeforeclear. Onlyconfirmedpre-admissionrefusalshowsQuestionnotsent andordinaryRetry/taskboundLoadanyway. UnknowntransportdeliveryshowsDeliveryunknown/checksavedconversation, noblindresend. Everyreload/duplicatedtabfreshowner/reviewonly.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/uiux-cutover-residency.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.structuredOutcome.test.ts#XD2 announces Structured done exactly once politely without taking focus"
      ]
    }
  ]
}
```

## Acceptance records
