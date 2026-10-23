---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: wp32-third-repair-fnfjwjd3
---

# Test-intent entries

Scenario metadata uses S321–S327 for S32.1–S32.7, and N321 for N32.1 (the gate accepts alphanumeric ids). The scenario text is quoted verbatim.

The before-state is ae50fbc4e. New repair checks run unchanged against it. Existing WP3.1 limit/syntax checks are preservation checks; this package does not reimplement their validator.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.excludes.test.ts"
      ],
      "class": "New",
      "behaviour": "S32.1 typed consent/428 approval and cancellation without writes; S32.2 explicit Save and disabled unsaved effects; S32.3 structured partial/unknown outcomes; S32.5 conflict review preserves draft and uses a new witnessed save; S32.6 inline invalid-save refusal; S32.7 one live busy/result region with overlap blocked. WX2 names pending file/folder removal, excludes it from completed counts, and explains rebuild-wait semantics before Apply.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S321",
          "quote": "Apply asks for the typed confirmation the operation requires, and after confirming it runs; cancelling the confirmation removes nothing and changes no saved list beyond what the user explicitly saved (decision 2).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S322",
          "quote": "Saving the list and removing already-indexed matches are distinct, visible steps: the user can tell, before acting, whether an action only changes what future indexing skips or also removes documents now; Preview never changes what gets indexed without saying so (decision 2).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S323",
          "quote": "Apply reports what it removed, and when it was partial says which scope was not checked or not removed: a watched folder offline or missing, an unreadable file or folder, an Engine refusal partway (documents already removed are not presented as an unchanged library), or the entry cap reached. It never reports a complete result for a partial run. Retrying reconciles to the true state.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S325",
          "quote": "A save that conflicts with another settings change, or whose outcome is uncertain, keeps the user's typed draft and says what happened; the user can retry without retyping.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S326",
          "quote": "A pattern the matchers cannot interpret is refused with an inline, announced error at save; it is never saved and then silently ignored by walks (`WorkerScanOps.java:488-514`).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S327",
          "quote": "Editor, Save, Preview, Apply, the confirmation and the result are operable by keyboard and screen reader (busy and result announced once), in both themes, High Contrast and narrow width.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "2. **Editor semantics (WP3.2).** Options: (a) explicit Save; Preview and Apply act on the saved list (Preview disabled while the draft is unsaved); no backend change; (b) Preview evaluates the unsaved draft (operation gains a patterns argument); (c) keep save-then-preview and say so. **Recommend (a).** Honest with no new backend surface; saving only affects future indexing, so previewing after save loses nothing.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "3. **Apply scope (WP3.2).** Options: (a) walk the disk as today and report unchecked scope; (b) also walk the index for matches whose files are gone or offline. **Recommend (a).** Gone files are handled by deletion cleanup (#793); offline ones are reported (S32.3). (b) is new machinery for a rare case.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "WX1 Apply's result is stated in what the Engine can guarantee, not in indexed-document counts: it reports how many matching files and folders are confirmed no longer searchable (each deletion request the Worker confirmed), without claiming each was indexed before. Every deletion that returned an error, was refused, was rejected by the circuit breaker, or was never attempted (offline or missing watched folder, unreadable entry, entry cap) is listed as not removed or not checked; a failed or unknown deletion is never counted as removed. The Worker deletion contract is not widened. Apply uses an outcome-carrying variant of the prefix deletion; existing callers of deleteDocsByPathPrefix keep their current behaviour. S32.3's \"reports what it removed\" is read through WX1.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "WX2 (orchestrator decision under the user's delegation, 2026-10-21; agent-adopted, not owner words; refines WX1): the Worker's deleteByPath and deleteById replies gain one additive, optional field that is true only when the deletion was buffered for later application (index switching) instead of applied. Apply reports such matches as \"removal completes when the index rebuild finishes\", never as removed. A reply with a negative job count and no error, or any reply the Engine cannot interpret, counts as unknown, not removed. Existing callers and older readers of these replies are unaffected (the field defaults to false; no existing field changes meaning). Why: only the Worker knows at deletion time whether it buffered; an Engine-side refusal during rebuild would race a switch that starts mid-Apply.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-excludes-editor-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.excludes.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/excludes/ExcludesOutcomeTest.java"
      ],
      "class": "New",
      "behaviour": "S32.3: report Worker-confirmed requests, failed file deletion, Engine refusal after confirmed progress, unavailable roots, unreadable files/folders, whole remaining scope after the entry cap, retry reconciliation, and operation wire fields. An unconfirmed prefix names the whole failed scope and never counts its unattempted descendants as removals.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S323",
          "quote": "Apply reports what it removed, and when it was partial says which scope was not checked or not removed: a watched folder offline or missing, an unreadable file or folder, an Engine refusal partway (documents already removed are not presented as an unchanged library), or the entry cap reached. It never reports a complete result for a partial run. Retrying reconciles to the true state.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "3. **Apply scope (WP3.2).** Options: (a) walk the disk as today and report unchecked scope; (b) also walk the index for matches whose files are gone or offline. **Recommend (a).** Gone files are handled by deletion cleanup (#793); offline ones are reported (S32.3). (b) is new machinery for a rare case.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "WX1 Apply's result is stated in what the Engine can guarantee, not in indexed-document counts: it reports how many matching files and folders are confirmed no longer searchable (each deletion request the Worker confirmed), without claiming each was indexed before. Every deletion that returned an error, was refused, was rejected by the circuit breaker, or was never attempted (offline or missing watched folder, unreadable entry, entry cap) is listed as not removed or not checked; a failed or unknown deletion is never counted as removed. The Worker deletion contract is not widened. Apply uses an outcome-carrying variant of the prefix deletion; existing callers of deleteDocsByPathPrefix keep their current behaviour. S32.3's \"reports what it removed\" is read through WX1.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "AX3 Pass-2 finding 8 (honest Preview/Apply outcomes for unindexed, missing, unreadable and offline entries) is delivered with WP3.2, not WP3.1.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "WX2 (orchestrator decision under the user's delegation, 2026-10-21; agent-adopted, not owner words; refines WX1): the Worker's deleteByPath and deleteById replies gain one additive, optional field that is true only when the deletion was buffered for later application (index switching) instead of applied. Apply reports such matches as \"removal completes when the index rebuild finishes\", never as removed. A reply with a negative job count and no error, or any reply the Engine cannot interpret, counts as unknown, not removed. Existing callers and older readers of these replies are unaffected (the field defaults to false; no existing field changes meaning). Why: only the Worker knows at deletion time whether it buffered; an Engine-side refusal during rebuild would race a switch that starts mid-Apply.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-excludes-editor-java-repairs.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/excludes/ExcludesOutcomeTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/PrefixDeletionOutcomeTest.java"
      ],
      "class": "New",
      "behaviour": "WX1 outcome-carrying prefix deletion distinguishes confirmed zero-job completion from RPC error, Engine refusal, circuit rejection and transport failure; the legacy method still returns zero or rethrows its typed refusal.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S323",
          "quote": "Apply reports what it removed, and when it was partial says which scope was not checked or not removed: a watched folder offline or missing, an unreadable file or folder, an Engine refusal partway (documents already removed are not presented as an unchanged library), or the entry cap reached. It never reports a complete result for a partial run. Retrying reconciles to the true state.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "WX1 Apply's result is stated in what the Engine can guarantee, not in indexed-document counts: it reports how many matching files and folders are confirmed no longer searchable (each deletion request the Worker confirmed), without claiming each was indexed before. Every deletion that returned an error, was refused, was rejected by the circuit breaker, or was never attempted (offline or missing watched folder, unreadable entry, entry cap) is listed as not removed or not checked; a failed or unknown deletion is never counted as removed. The Worker deletion contract is not widened. Apply uses an outcome-carrying variant of the prefix deletion; existing callers of deleteDocsByPathPrefix keep their current behaviour. S32.3's \"reports what it removed\" is read through WX1.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-excludes-editor-java-repairs.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/PrefixDeletionOutcomeTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ExclusionSaveHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve the already-implemented WP3.1 refusal through real HTTP SettingsController -> SettingsServiceImpl normalization: input overflow, expansion overflow and invalid syntax return 400 INVALID_REQUEST with unchanged persisted policy/witness.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S326",
          "quote": "A pattern the matchers cannot interpret is refused with an inline, announced error at save; it is never saved and then silently ignored by walks (`WorkerScanOps.java:488-514`).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "Pass-2 finding 1: “Exceeding input or expanded-pattern limits is explicitly refused without changing saved policy; test both limits through the editor and API.”",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-excludes-editor-java-preservation.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ExclusionSaveHttpTest.java"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.settings-attempt.test.ts"
      ],
      "class": "New",
      "behaviour": "The settings-attempt checks now enter via explicit Save, and expect no Preview/Apply side effect from saving. Keep witness capture, conflict and uncertain-byte preservation, disabled/unwitnessed load, complete-receipt waiting, retry identity, legacy list/help, and both overflow-refusal assertions; errors are asserted at their inline announced location. The unchanged legacy list Apply supplies typed consent.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S321",
          "quote": "Apply asks for the typed confirmation the operation requires, and after confirming it runs; cancelling the confirmation removes nothing and changes no saved list beyond what the user explicitly saved (decision 2).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S322",
          "quote": "Saving the list and removing already-indexed matches are distinct, visible steps: the user can tell, before acting, whether an action only changes what future indexing skips or also removes documents now; Preview never changes what gets indexed without saying so (decision 2).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S325",
          "quote": "A save that conflicts with another settings change, or whose outcome is uncertain, keeps the user's typed draft and says what happened; the user can retry without retyping.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S326",
          "quote": "A pattern the matchers cannot interpret is refused with an inline, announced error at save; it is never saved and then silently ignored by walks (`WorkerScanOps.java:488-514`).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "N321",
          "quote": "Env/`-D` override display; Reload of the saved list; settings saves elsewhere (Settings surface).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "2. **Editor semantics (WP3.2).** Options: (a) explicit Save; Preview and Apply act on the saved list (Preview disabled while the draft is unsaved); no backend change; (b) Preview evaluates the unsaved draft (operation gains a patterns argument); (c) keep save-then-preview and say so. **Recommend (a).** Honest with no new backend surface; saving only affects future indexing, so previewing after save loses nothing.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "Pass-2 finding 1: “Exceeding input or expanded-pattern limits is explicitly refused without changing saved policy; test both limits through the editor and API.”",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "AX1 A saved exclusion list never stops indexing. An entry that the current matcher cannot parse (for example one saved by an earlier version, such as \"[legacy\") is matched as a literal name; the list's other entries keep working on every path; Preview and the editor show the saved list as it is; the editor names each such entry with a plain warning so the user can correct it.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "AX2 The current meaning of [ ] and { } in exclusion patterns also applies to lists saved before the change; the editor's pattern help says what they mean.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-excludes-editor-settings-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.settings-attempt.test.ts"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/ExclusionLimitTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The successful prefix and file deletion fakes supply completion-aware methods with explicit confirmed results. The same paths, membership side effects, legacy job counts, Preview nonmutation and every existing oracle remain unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S323",
          "quote": "Apply reports what it removed, and when it was partial says which scope was not checked or not removed: a watched folder offline or missing, an unreadable file or folder, an Engine refusal partway (documents already removed are not presented as an unchanged library), or the entry cap reached. It never reports a complete result for a partial run. Retrying reconciles to the true state.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "WX1 Apply's result is stated in what the Engine can guarantee, not in indexed-document counts: it reports how many matching files and folders are confirmed no longer searchable (each deletion request the Worker confirmed), without claiming each was indexed before. Every deletion that returned an error, was refused, was rejected by the circuit breaker, or was never attempted (offline or missing watched folder, unreadable entry, entry cap) is listed as not removed or not checked; a failed or unknown deletion is never counted as removed. The Worker deletion contract is not widened. Apply uses an outcome-carrying variant of the prefix deletion; existing callers of deleteDocsByPathPrefix keep their current behaviour. S32.3's \"reports what it removed\" is read through WX1.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "Pass-2 finding 8: “Check matching parity on a readable, fully indexed fixture with unchanged files and policy. Separately verify honest Preview/Apply outcomes for unindexed, missing, unreadable and offline entries.”",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "WX2 (orchestrator decision under the user's delegation, 2026-10-21; agent-adopted, not owner words; refines WX1): the Worker's deleteByPath and deleteById replies gain one additive, optional field that is true only when the deletion was buffered for later application (index switching) instead of applied. Apply reports such matches as \"removal completes when the index rebuild finishes\", never as removed. A reply with a negative job count and no error, or any reply the Engine cannot interpret, counts as unknown, not removed. Existing callers and older readers of these replies are unaffected (the field defaults to false; no existing field changes meaning). Why: only the Worker knows at deletion time whether it buffered; an Engine-side refusal during rebuild would race a switch that starts mid-Apply.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-excludes-editor-java-preservation.json"
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexingControllerExcludesApplyTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The successful prefix and file deletion fakes supply completion-aware methods with explicit confirmed results. The same paths, membership side effects, legacy job counts, Preview nonmutation and every existing oracle remain unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S323",
          "quote": "Apply reports what it removed, and when it was partial says which scope was not checked or not removed: a watched folder offline or missing, an unreadable file or folder, an Engine refusal partway (documents already removed are not presented as an unchanged library), or the entry cap reached. It never reports a complete result for a partial run. Retrying reconciles to the true state.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "WX1 Apply's result is stated in what the Engine can guarantee, not in indexed-document counts: it reports how many matching files and folders are confirmed no longer searchable (each deletion request the Worker confirmed), without claiming each was indexed before. Every deletion that returned an error, was refused, was rejected by the circuit breaker, or was never attempted (offline or missing watched folder, unreadable entry, entry cap) is listed as not removed or not checked; a failed or unknown deletion is never counted as removed. The Worker deletion contract is not widened. Apply uses an outcome-carrying variant of the prefix deletion; existing callers of deleteDocsByPathPrefix keep their current behaviour. S32.3's \"reports what it removed\" is read through WX1.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "Pass-2 finding 8: “Check matching parity on a readable, fully indexed fixture with unchanged files and policy. Separately verify honest Preview/Apply outcomes for unindexed, missing, unreadable and offline entries.”",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "WX2 (orchestrator decision under the user's delegation, 2026-10-21; agent-adopted, not owner words; refines WX1): the Worker's deleteByPath and deleteById replies gain one additive, optional field that is true only when the deletion was buffered for later application (index switching) instead of applied. Apply reports such matches as \"removal completes when the index rebuild finishes\", never as removed. A reply with a negative job count and no error, or any reply the Engine cannot interpret, counts as unknown, not removed. Existing callers and older readers of these replies are unaffected (the field defaults to false; no existing field changes meaning). Why: only the Worker knows at deletion time whether it buffered; an Engine-side refusal during rebuild would race a switch that starts mid-Apply.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-excludes-editor-java-preservation.json"
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/LegacyExclusionUpgradeTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The successful prefix and file deletion fakes supply completion-aware methods with explicit confirmed results. The same paths, membership side effects, legacy job counts, Preview nonmutation and every existing oracle remain unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S323",
          "quote": "Apply reports what it removed, and when it was partial says which scope was not checked or not removed: a watched folder offline or missing, an unreadable file or folder, an Engine refusal partway (documents already removed are not presented as an unchanged library), or the entry cap reached. It never reports a complete result for a partial run. Retrying reconciles to the true state.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "WX1 Apply's result is stated in what the Engine can guarantee, not in indexed-document counts: it reports how many matching files and folders are confirmed no longer searchable (each deletion request the Worker confirmed), without claiming each was indexed before. Every deletion that returned an error, was refused, was rejected by the circuit breaker, or was never attempted (offline or missing watched folder, unreadable entry, entry cap) is listed as not removed or not checked; a failed or unknown deletion is never counted as removed. The Worker deletion contract is not widened. Apply uses an outcome-carrying variant of the prefix deletion; existing callers of deleteDocsByPathPrefix keep their current behaviour. S32.3's \"reports what it removed\" is read through WX1.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "Pass-2 finding 8: “Check matching parity on a readable, fully indexed fixture with unchanged files and policy. Separately verify honest Preview/Apply outcomes for unindexed, missing, unreadable and offline entries.”",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "WX2 (orchestrator decision under the user's delegation, 2026-10-21; agent-adopted, not owner words; refines WX1): the Worker's deleteByPath and deleteById replies gain one additive, optional field that is true only when the deletion was buffered for later application (index switching) instead of applied. Apply reports such matches as \"removal completes when the index rebuild finishes\", never as removed. A reply with a negative job count and no error, or any reply the Engine cannot interpret, counts as unknown, not removed. Existing callers and older readers of these replies are unaffected (the field defaults to false; no existing field changes meaning). Why: only the Worker knows at deletion time whether it buffered; an Engine-side refusal during rebuild would race a switch that starts mid-Apply.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-excludes-editor-java-preservation.json"
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/ExcludePatternsResolutionTest.java"
      ],
      "class": "New",
      "behaviour": "The wrapped deletion refusal fixture now follows WX1: Apply returns a partial result with zero confirmed requests and names the refused file, retaining the Engine refusal reason. The other resolution and override checks remain unchanged. WX1 supersedes propagation of a deletion refusal that discarded partial progress.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S323",
          "quote": "Apply reports what it removed, and when it was partial says which scope was not checked or not removed: a watched folder offline or missing, an unreadable file or folder, an Engine refusal partway (documents already removed are not presented as an unchanged library), or the entry cap reached. It never reports a complete result for a partial run. Retrying reconciles to the true state.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "3. **Apply scope (WP3.2).** Options: (a) walk the disk as today and report unchecked scope; (b) also walk the index for matches whose files are gone or offline. **Recommend (a).** Gone files are handled by deletion cleanup (#793); offline ones are reported (S32.3). (b) is new machinery for a rare case.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "WX1 Apply's result is stated in what the Engine can guarantee, not in indexed-document counts: it reports how many matching files and folders are confirmed no longer searchable (each deletion request the Worker confirmed), without claiming each was indexed before. Every deletion that returned an error, was refused, was rejected by the circuit breaker, or was never attempted (offline or missing watched folder, unreadable entry, entry cap) is listed as not removed or not checked; a failed or unknown deletion is never counted as removed. The Worker deletion contract is not widened. Apply uses an outcome-carrying variant of the prefix deletion; existing callers of deleteDocsByPathPrefix keep their current behaviour. S32.3's \"reports what it removed\" is read through WX1.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "AX3 Pass-2 finding 8 (honest Preview/Apply outcomes for unindexed, missing, unreadable and offline entries) is delivered with WP3.2, not WP3.1.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "WX2 (orchestrator decision under the user's delegation, 2026-10-21; agent-adopted, not owner words; refines WX1): the Worker's deleteByPath and deleteById replies gain one additive, optional field that is true only when the deletion was buffered for later application (index switching) instead of applied. Apply reports such matches as \"removal completes when the index rebuild finishes\", never as removed. A reply with a negative job count and no error, or any reply the Engine cannot interpret, counts as unknown, not removed. Existing callers and older readers of these replies are unaffected (the field defaults to false; no existing field changes meaning). Why: only the Worker knows at deletion time whether it buffered; an Engine-side refusal during rebuild would race a switch that starts mid-Apply.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-excludes-editor-java-repairs.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/ExcludePatternsResolutionTest.java#applyExcludesReportsWrappedDeletionRefusal"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/DeletionCompletionOutcomeTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/DeletionCompletionReceiptTest.java"
      ],
      "class": "New",
      "behaviour": "WX2: real Worker switch-buffer deletion receipts distinguish deferred and completed mutations, retain legacy fields, and default absent markers to false. Engine completion-aware methods retain deferred scopes and reject negative or unrepresentable counts, failed/error/null/transport/refused/circuit replies. Apply never counts buffered files/folders; retry reconciles. Existing legacy acceptance stays unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S323",
          "quote": "Apply reports what it removed, and when it was partial says which scope was not checked or not removed: a watched folder offline or missing, an unreadable file or folder, an Engine refusal partway (documents already removed are not presented as an unchanged library), or the entry cap reached. It never reports a complete result for a partial run. Retrying reconciles to the true state.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "WX2 (orchestrator decision under the user's delegation, 2026-10-21; agent-adopted, not owner words; refines WX1): the Worker's deleteByPath and deleteById replies gain one additive, optional field that is true only when the deletion was buffered for later application (index switching) instead of applied. Apply reports such matches as \"removal completes when the index rebuild finishes\", never as removed. A reply with a negative job count and no error, or any reply the Engine cannot interpret, counts as unknown, not removed. Existing callers and older readers of these replies are unaffected (the field defaults to false; no existing field changes meaning). Why: only the Worker knows at deletion time whether it buffered; an Engine-side refusal during rebuild would race a switch that starts mid-Apply.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-excludes-editor-java-repairs.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/DeletionCompletionOutcomeTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/DeletionCompletionReceiptTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.accessibility.test.ts"
      ],
      "class": "New",
      "behaviour": "S32.7 / XA1: the shared typed confirmation input has a native associated label including the exact word to type, for Apply and another typed confirmation.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S327",
          "quote": "Editor, Save, Preview, Apply, the confirmation and the result are operable by keyboard and screen reader (busy and result announced once), in both themes, High Contrast and narrow width.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "XA1 (orchestrator decision, 2026-10-22, under the user's delegation of 2026-10-19): An authorization prompt (\"Authorize action ...\") that was answered in another window, or whose pending request the Engine no longer knows (expired, answered, 404), disappears from every open window without user action, at the latest when that window next learns the request's state, and never blocks pointer or keyboard input. Answering a stale prompt changes nothing on the server and closes it with a short neutral note. A prompt for a request that is still pending stays and works as before. The S32.7 typed confirmation's input has an accessible name that includes the word to type (all typed confirmations share it).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "2. **Editor semantics (WP3.2).** Options: (a) explicit Save; Preview and Apply act on the saved list (Preview disabled while the draft is unsaved); no backend change; (b) Preview evaluates the unsaved draft (operation gains a patterns argument); (c) keep save-then-preview and say so. **Recommend (a).** Honest with no new backend surface; saving only affects future indexing, so previewing after save loses nothing.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "3. **Apply scope (WP3.2).** Options: (a) walk the disk as today and report unchecked scope; (b) also walk the index for matches whose files are gone or offline. **Recommend (a).** Gone files are handled by deletion cleanup (#793); offline ones are reported (S32.3). (b) is new machinery for a rare case.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-excludes-editor-xa1-repairs.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.accessibility.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationWithdrawal.test.ts"
      ],
      "class": "New",
      "behaviour": "XA1: the real pending bridge, client and native modal withdraw an answered or unknown request on existing activity/status signals; stale approval and denial are neutral and do not POST; a 410 race is neutral; queued stale prompts never surface and do not reset a still-pending prompt. Original caller prompts also recover through the shared status cadence when the bridge detail lookup failed; transient state-read failures keep their prompt usable.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S327",
          "quote": "Editor, Save, Preview, Apply, the confirmation and the result are operable by keyboard and screen reader (busy and result announced once), in both themes, High Contrast and narrow width.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "XA1 (orchestrator decision, 2026-10-22, under the user's delegation of 2026-10-19): An authorization prompt (\"Authorize action ...\") that was answered in another window, or whose pending request the Engine no longer knows (expired, answered, 404), disappears from every open window without user action, at the latest when that window next learns the request's state, and never blocks pointer or keyboard input. Answering a stale prompt changes nothing on the server and closes it with a short neutral note. A prompt for a request that is still pending stays and works as before. The S32.7 typed confirmation's input has an accessible name that includes the word to type (all typed confirmations share it).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "2. **Editor semantics (WP3.2).** Options: (a) explicit Save; Preview and Apply act on the saved list (Preview disabled while the draft is unsaved); no backend change; (b) Preview evaluates the unsaved draft (operation gains a patterns argument); (c) keep save-then-preview and say so. **Recommend (a).** Honest with no new backend surface; saving only affects future indexing, so previewing after save loses nothing.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "3. **Apply scope (WP3.2).** Options: (a) walk the disk as today and report unchecked scope; (b) also walk the index for matches whose files are gone or offline. **Recommend (a).** Gone files are handled by deletion cleanup (#793); offline ones are reported (S32.3). (b) is new machinery for a rare case.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-excludes-editor-xa1-repairs.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationWithdrawal.test.ts#withdraws the second client prompt when the first client answers and activity arrives (XA1)",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationWithdrawal.test.ts#withdraws an unknown request on the next shared status update (XA1)",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationWithdrawal.test.ts#answering a stale prompt closes it with a neutral note and sends no approval (XA1)",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationWithdrawal.test.ts#denying a stale prompt closes with a neutral note and no server change (XA1)",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationWithdrawal.test.ts#handles a 410 race after the final peek with a neutral note (XA1)",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationWithdrawal.test.ts#removes queued stale prompts without resetting the live prompt or showing the stale one (XA1)",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationWithdrawal.test.ts#withdraws an original caller prompt after the bridge detail lookup failed (XA1)"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts"
      ],
      "class": "New",
      "behaviour": "XA1: invoke and Undo callers through the real Shell/client/toast seam report answered (410), withdrawn, and denied stale approvals with a short neutral note, never retrying the mutation or approving a withdrawn request. Existing expectations are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S327",
          "quote": "Editor, Save, Preview, Apply, the confirmation and the result are operable by keyboard and screen reader (busy and result announced once), in both themes, High Contrast and narrow width.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "XA1 (orchestrator decision, 2026-10-22, under the user's delegation of 2026-10-19): An authorization prompt (\"Authorize action ...\") that was answered in another window, or whose pending request the Engine no longer knows (expired, answered, 404), disappears from every open window without user action, at the latest when that window next learns the request's state, and never blocks pointer or keyboard input. Answering a stale prompt changes nothing on the server and closes it with a short neutral note. A prompt for a request that is still pending stays and works as before. The S32.7 typed confirmation's input has an accessible name that includes the word to type (all typed confirmations share it).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "2. **Editor semantics (WP3.2).** Options: (a) explicit Save; Preview and Apply act on the saved list (Preview disabled while the draft is unsaved); no backend change; (b) Preview evaluates the unsaved draft (operation gains a patterns argument); (c) keep save-then-preview and say so. **Recommend (a).** Honest with no new backend surface; saving only affects future indexing, so previewing after save loses nothing.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "3. **Apply scope (WP3.2).** Options: (a) walk the disk as today and report unchecked scope; (b) also walk the index for matches whose files are gone or offline. **Recommend (a).** Gone files are handled by deletion cleanup (#793); offline ones are reported (S32.3). (b) is new machinery for a rare case.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-excludes-editor-xa1-repairs.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts#XA1: invoke reports a gone approval as a neutral closed request",
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts#XA1: undo reports a gone approval as a neutral closed request",
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts#XA1: invoke reports a withdrawn approval as a neutral closed request",
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts#XA1: undo reports a withdrawn approval as a neutral closed request",
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts#XA1: invoke reports a denied-gone approval as a neutral closed request",
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts#XA1: undo reports a denied-gone approval as a neutral closed request"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationWithdrawal.test.ts"
      ],
      "class": "New",
      "behaviour": "XA1 preservation: a still-pending prompt remains usable after activity/status reconciliation, and a failed state lookup does not prove a request has gone or dismiss its prompt. Both checks pass before and after the repair.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "XA1 (orchestrator decision, 2026-10-22, under the user's delegation of 2026-10-19): An authorization prompt (\"Authorize action ...\") that was answered in another window, or whose pending request the Engine no longer knows (expired, answered, 404), disappears from every open window without user action, at the latest when that window next learns the request's state, and never blocks pointer or keyboard input. Answering a stale prompt changes nothing on the server and closes it with a short neutral note. A prompt for a request that is still pending stays and works as before. The S32.7 typed confirmation's input has an accessible name that includes the word to type (all typed confirmations share it).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-excludes-editor-xa1-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationWithdrawal.test.ts#keeps a still-pending prompt usable after activity and status checks (XA1)",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationWithdrawal.test.ts#does not dismiss a pending prompt on a failed state lookup (XA1)"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Mocks retain the real broker/client exports needed by XA1 and stub the shared status subscription so these existing focused checks do not start status network reads. Every existing input, precondition and expectation is unchanged. XA1 stream/status integration is exercised in pendingAuthorizationWithdrawal.test.ts.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "XA1 (orchestrator decision, 2026-10-22, under the user's delegation of 2026-10-19): An authorization prompt (\"Authorize action ...\") that was answered in another window, or whose pending request the Engine no longer knows (expired, answered, 404), disappears from every open window without user action, at the latest when that window next learns the request's state, and never blocks pointer or keyboard input. Answering a stale prompt changes nothing on the server and closes it with a short neutral note. A prompt for a request that is still pending stays and works as before. The S32.7 typed confirmation's input has an accessible name that includes the word to type (all typed confirmations share it).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsEngineTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "EX1 preservation: Apply now calls deleteDocByIdWithOutcome (WX1/WX2), so its fake must forward that seam to the same real Worker deleteById and carry the actual success/error/buffered receipt. The former legacy-method stub was never called and left the indexed row in place. Only this stub changes: every fixture input, queued-work precondition, real Lucene/SQLite observation, maintenance/explicit-ingest/boot-walk operation and assertion is unchanged. The adapted check is compared on both 4a7f96624 and the candidate; this is no new or repaired product oracle. The unadapted check fails at the existing line 310 deletion assertion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S324",
          "quote": "After a successful Apply, fresh searches in both windows return none of the removed documents (staying out afterwards is WP3.1).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "WX1 Apply's result is stated in what the Engine can guarantee, not in indexed-document counts: it reports how many matching files and folders are confirmed no longer searchable (each deletion request the Worker confirmed), without claiming each was indexed before. Every deletion that returned an error, was refused, was rejected by the circuit breaker, or was never attempted (offline or missing watched folder, unreadable entry, entry cap) is listed as not removed or not checked; a failed or unknown deletion is never counted as removed. The Worker deletion contract is not widened. Apply uses an outcome-carrying variant of the prefix deletion; existing callers of deleteDocsByPathPrefix keep their current behaviour. S32.3's \"reports what it removed\" is read through WX1.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "WX2 (orchestrator decision under the user's delegation, 2026-10-21; agent-adopted, not owner words; refines WX1): the Worker's deleteByPath and deleteById replies gain one additive, optional field that is true only when the deletion was buffered for later application (index switching) instead of applied. Apply reports such matches as \"removal completes when the index rebuild finishes\", never as removed. A reply with a negative job count and no error, or any reply the Engine cannot interpret, counts as unknown, not removed. Existing callers and older readers of these replies are unaffected (the field defaults to false; no existing field changes meaning). Why: only the Worker knows at deletion time whether it buffered; an Engine-side refusal during rebuild would race a switch that starts mid-Apply.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "2. **Editor semantics (WP3.2).** Options: (a) explicit Save; Preview and Apply act on the saved list (Preview disabled while the draft is unsaved); no backend change; (b) Preview evaluates the unsaved draft (operation gains a patterns argument); (c) keep save-then-preview and say so. **Recommend (a).** Honest with no new backend surface; saving only affects future indexing, so previewing after save loses nothing.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "3. **Apply scope (WP3.2).** Options: (a) walk the disk as today and report unchecked scope; (b) also walk the index for matches whose files are gone or offline. **Recommend (a).** Gone files are handled by deletion cleanup (#793); offline ones are reported (S32.3). (b) is new machinery for a rare case.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-excludes-editor-ex1-preservation.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.excludes-override.test.ts"
      ],
      "class": "New",
      "behaviour": "OV1 operator override repair: the notice precedes actions, names the winning resolver source and actual patterns (including an empty list), is keyboard reachable, explicitly named for screen readers, and described by the editor. Preview, Apply and typed confirmation name that list even with zero matches. Save writes only the saved list and explains when it takes effect. An unavailable policy blocks effects without discarding the editable saved list.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S322",
          "quote": "Saving the list and removing already-indexed matches are distinct, visible steps: the user can tell, before acting, whether an action only changes what future indexing skips or also removes documents now; Preview never changes what gets indexed without saying so (decision 2).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "OV1 (orchestrator decision, 2026-10-22, under the user's design delegation): When an operator override (environment variable or system property for the exclusion list) decides the effective list, the editor says so before any action: it names the source and shows the patterns Preview and Apply actually use, and the confirmation names those same patterns. Save still edits the saved list and says it has no effect while the override is set. Without an override nothing changes.\n\n| Case | Expected |\n|---|---|\n| No override | editor, Preview, Apply and confirmation unchanged (must keep) |\n| Environment override only | notice naming the environment source and its patterns; Preview and Apply use and show them; confirmation names them |\n| Environment + system-property override | notice names the winning source (system property, per existing precedence) and its patterns; same as above |\n| Save while overridden | saved list changes; notice says it takes effect only when the override is removed |\n| Override removed after restart | notice gone; saved list governs again (must keep) |\n| Keyboard, screen reader name, 360 px, dark/light | notice reachable and readable (S32.7, must keep) |",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "2. **Editor semantics (WP3.2).** Options: (a) explicit Save; Preview and Apply act on the saved list (Preview disabled while the draft is unsaved); no backend change; (b) Preview evaluates the unsaved draft (operation gains a patterns argument); (c) keep save-then-preview and say so. **Recommend (a).** Honest with no new backend surface; saving only affects future indexing, so previewing after save loses nothing.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "3. **Apply scope (WP3.2).** Options: (a) walk the disk as today and report unchecked scope; (b) also walk the index for matches whose files are gone or offline. **Recommend (a).** Gone files are handled by deletion cleanup (#793); offline ones are reported (S32.3). (b) is new machinery for a rare case.",
          "label": "agent choice"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S327",
          "quote": "Editor, Save, Preview, Apply, the confirmation and the result are operable by keyboard and screen reader (busy and result announced once), in both themes, High Contrast and narrow width.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-excludes-editor-ov1-ui-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.excludes-override.test.ts#OV1 names the env_var policy before actions and in Preview, Apply and confirmation",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.excludes-override.test.ts#OV1 names the jvm_arg policy before actions and in Preview, Apply and confirmation",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.excludes-override.test.ts#OV1 Save retains the override and explains when the saved list takes effect",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.excludes-override.test.ts#OV1 an empty override is still named and is not replaced by the saved list",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.excludes-override.test.ts#OV1 refuses Preview and Apply when the effective policy cannot be read"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.excludes-override.test.ts"
      ],
      "class": "New",
      "behaviour": "OV1 preservation: without an override (including after removal and restart), the editor, help and saved-pattern confirmation keep their prior behavior.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S322",
          "quote": "Saving the list and removing already-indexed matches are distinct, visible steps: the user can tell, before acting, whether an action only changes what future indexing skips or also removes documents now; Preview never changes what gets indexed without saying so (decision 2).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "OV1 (orchestrator decision, 2026-10-22, under the user's design delegation): When an operator override (environment variable or system property for the exclusion list) decides the effective list, the editor says so before any action: it names the source and shows the patterns Preview and Apply actually use, and the confirmation names those same patterns. Save still edits the saved list and says it has no effect while the override is set. Without an override nothing changes.\n\n| Case | Expected |\n|---|---|\n| No override | editor, Preview, Apply and confirmation unchanged (must keep) |\n| Environment override only | notice naming the environment source and its patterns; Preview and Apply use and show them; confirmation names them |\n| Environment + system-property override | notice names the winning source (system property, per existing precedence) and its patterns; same as above |\n| Save while overridden | saved list changes; notice says it takes effect only when the override is removed |\n| Override removed after restart | notice gone; saved list governs again (must keep) |\n| Keyboard, screen reader name, 360 px, dark/light | notice reachable and readable (S32.7, must keep) |",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "2. **Editor semantics (WP3.2).** Options: (a) explicit Save; Preview and Apply act on the saved list (Preview disabled while the draft is unsaved); no backend change; (b) Preview evaluates the unsaved draft (operation gains a patterns argument); (c) keep save-then-preview and say so. **Recommend (a).** Honest with no new backend surface; saving only affects future indexing, so previewing after save loses nothing.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "3. **Apply scope (WP3.2).** Options: (a) walk the disk as today and report unchecked scope; (b) also walk the index for matches whose files are gone or offline. **Recommend (a).** Gone files are handled by deletion cleanup (#793); offline ones are reported (S32.3). (b) is new machinery for a rare case.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-excludes-editor-ov1-ui-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.excludes-override.test.ts#OV1 without an override, including after restart, keeps the saved-list behavior"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ExclusionPolicyHttpTest.java"
      ],
      "class": "New",
      "behaviour": "OV1: the HTTP policy observation does not touch indexing; HTTP read/Preview/Apply agree on source and actual matcher patterns for saved, environment, and environment-plus-system-property cases. Existing precedence and matched-file deletion remain unchanged. Operation responses also carry the winning source and list even when no disk matches exist.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S322",
          "quote": "Saving the list and removing already-indexed matches are distinct, visible steps: the user can tell, before acting, whether an action only changes what future indexing skips or also removes documents now; Preview never changes what gets indexed without saying so (decision 2).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "OV1 (orchestrator decision, 2026-10-22, under the user's design delegation): When an operator override (environment variable or system property for the exclusion list) decides the effective list, the editor says so before any action: it names the source and shows the patterns Preview and Apply actually use, and the confirmation names those same patterns. Save still edits the saved list and says it has no effect while the override is set. Without an override nothing changes.\n\n| Case | Expected |\n|---|---|\n| No override | editor, Preview, Apply and confirmation unchanged (must keep) |\n| Environment override only | notice naming the environment source and its patterns; Preview and Apply use and show them; confirmation names them |\n| Environment + system-property override | notice names the winning source (system property, per existing precedence) and its patterns; same as above |\n| Save while overridden | saved list changes; notice says it takes effect only when the override is removed |\n| Override removed after restart | notice gone; saved list governs again (must keep) |\n| Keyboard, screen reader name, 360 px, dark/light | notice reachable and readable (S32.7, must keep) |",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "2. **Editor semantics (WP3.2).** Options: (a) explicit Save; Preview and Apply act on the saved list (Preview disabled while the draft is unsaved); no backend change; (b) Preview evaluates the unsaved draft (operation gains a patterns argument); (c) keep save-then-preview and say so. **Recommend (a).** Honest with no new backend surface; saving only affects future indexing, so previewing after save loses nothing.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "3. **Apply scope (WP3.2).** Options: (a) walk the disk as today and report unchecked scope; (b) also walk the index for matches whose files are gone or offline. **Recommend (a).** Gone files are handled by deletion cleanup (#793); offline ones are reported (S32.3). (b) is new machinery for a rare case.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-excludes-editor-ov1-java-repair.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ExclusionPolicyHttpTest.java#OV1ReadPreviewAndApplyNameTheWinningPolicyWithoutChangingPrecedence",
        "modules/ui/src/test/java/io/justsearch/ui/api/ExclusionPolicyHttpTest.java#OV1OperationsNameTheEffectivePolicyEvenWithoutDiskMatches"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.excludes.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.settings-attempt.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The fake transport now serves the independent effective-policy observation separately from its existing settings-attempt seam. It tracks the saved patterns from observed settings and completed writes. Every original assertion, fault injection, request count and retained-attempt sequence remains unchanged. The added policy observation does not consume the scripted settings responses.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S322",
          "quote": "Saving the list and removing already-indexed matches are distinct, visible steps: the user can tell, before acting, whether an action only changes what future indexing skips or also removes documents now; Preview never changes what gets indexed without saying so (decision 2).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "OV1 (orchestrator decision, 2026-10-22, under the user's design delegation): When an operator override (environment variable or system property for the exclusion list) decides the effective list, the editor says so before any action: it names the source and shows the patterns Preview and Apply actually use, and the confirmation names those same patterns. Save still edits the saved list and says it has no effect while the override is set. Without an override nothing changes.\n\n| Case | Expected |\n|---|---|\n| No override | editor, Preview, Apply and confirmation unchanged (must keep) |\n| Environment override only | notice naming the environment source and its patterns; Preview and Apply use and show them; confirmation names them |\n| Environment + system-property override | notice names the winning source (system property, per existing precedence) and its patterns; same as above |\n| Save while overridden | saved list changes; notice says it takes effect only when the override is removed |\n| Override removed after restart | notice gone; saved list governs again (must keep) |\n| Keyboard, screen reader name, 360 px, dark/light | notice reachable and readable (S32.7, must keep) |",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "2. **Editor semantics (WP3.2).** Options: (a) explicit Save; Preview and Apply act on the saved list (Preview disabled while the draft is unsaved); no backend change; (b) Preview evaluates the unsaved draft (operation gains a patterns argument); (c) keep save-then-preview and say so. **Recommend (a).** Honest with no new backend surface; saving only affects future indexing, so previewing after save loses nothing.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "3. **Apply scope (WP3.2).** Options: (a) walk the disk as today and report unchecked scope; (b) also walk the index for matches whose files are gone or offline. **Recommend (a).** Gone files are handled by deletion cleanup (#793); offline ones are reported (S32.3). (b) is new machinery for a rare case.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-excludes-editor-ov1-ui-preservation.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.keyboard-ownership.test.ts"
      ],
      "class": "New",
      "behaviour": "CE1 repaired keyboard ownership: document-level Enter neither confirms nor prevents native activation from focused Cancel, Close or another non-Confirm control. Native cancel actions remain wired. Real headless Chromium separately checks native Enter/Space activation, preservation on Confirm, and 240-character message reflow at 360 px in light/dark.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S321",
          "quote": "Apply asks for the typed confirmation the operation requires, and after confirming it runs; cancelling the confirmation removes nothing and changes no saved list beyond what the user explicitly saved (decision 2).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S327",
          "quote": "Editor, Save, Preview, Apply, the confirmation and the result are operable by keyboard and screen reader (busy and result announced once), in both themes, High Contrast and narrow width.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and the shared confirmation dialog",
          "choice": "Clarification CE1 (agent-adopted, orchestrator 2026-10-23, under the user's delegation \"you dont need to wait for my opinion regarding the designs, i agree with you on them\"; origin: verifier-5 F2 and F1)\n\nEnter in a confirmation dialog does what the focused control does. Applies to the shared confirmation dialog, so to every dialog that uses it.\n\n| Case | Expected |\n|---|---|\n| Apply confirmation, phrase typed, Cancel focused, Enter | Cancels; no Apply request; every indexed file stays searchable |\n| Same, Space on Cancel | Cancels (as today) |\n| Phrase typed, focus in the phrase field, Enter | Confirms (as today) |\n| Phrase typed, Confirm focused, Enter | Confirms (as today) |\n| Dialog without a typed phrase, Cancel or close button focused, Enter | Cancels or closes; no confirm |\n| Dialog without a typed phrase, focus on the dialog itself or Confirm, Enter | Confirms (as today) |\n| Phrase not typed, any focus, Enter | No confirm (as today) |\n| Confirmation listing a long unbroken pattern (240 characters) at 360 px wide, light and dark | The full pattern wraps inside the dialog; no horizontal scroll, nothing clipped |",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-excludes-editor-ce1-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.keyboard-ownership.test.ts#CE1 leaves Enter on Cancel to native activation (typed=true)",
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.keyboard-ownership.test.ts#CE1 leaves Enter on Cancel to native activation (typed=false)",
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.keyboard-ownership.test.ts#CE1 leaves Enter on Close to native activation",
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.keyboard-ownership.test.ts#CE1 leaves Enter on another focused control (a)"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.keyboard-ownership.test.ts"
      ],
      "class": "New",
      "behaviour": "CE1 preservation: selecting controls retains native Enter; dialog-focused Enter and matching-phrase Enter confirm exactly once; unmatched phrases cannot confirm from input, dialog or action buttons. Browser preservation also checks native Confirm Enter and Space cancellation.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S321",
          "quote": "Apply asks for the typed confirmation the operation requires, and after confirming it runs; cancelling the confirmation removes nothing and changes no saved list beyond what the user explicitly saved (decision 2).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S327",
          "quote": "Editor, Save, Preview, Apply, the confirmation and the result are operable by keyboard and screen reader (busy and result announced once), in both themes, High Contrast and narrow width.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and the shared confirmation dialog",
          "choice": "Clarification CE1 (agent-adopted, orchestrator 2026-10-23, under the user's delegation \"you dont need to wait for my opinion regarding the designs, i agree with you on them\"; origin: verifier-5 F2 and F1)\n\nEnter in a confirmation dialog does what the focused control does. Applies to the shared confirmation dialog, so to every dialog that uses it.\n\n| Case | Expected |\n|---|---|\n| Apply confirmation, phrase typed, Cancel focused, Enter | Cancels; no Apply request; every indexed file stays searchable |\n| Same, Space on Cancel | Cancels (as today) |\n| Phrase typed, focus in the phrase field, Enter | Confirms (as today) |\n| Phrase typed, Confirm focused, Enter | Confirms (as today) |\n| Dialog without a typed phrase, Cancel or close button focused, Enter | Cancels or closes; no confirm |\n| Dialog without a typed phrase, focus on the dialog itself or Confirm, Enter | Confirms (as today) |\n| Phrase not typed, any focus, Enter | No confirm (as today) |\n| Confirmation listing a long unbroken pattern (240 characters) at 360 px wide, light and dark | The full pattern wraps inside the dialog; no horizontal scroll, nothing clipped |",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-excludes-editor-ce1-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.keyboard-ownership.test.ts#CE1 leaves Enter on another focused control (select)",
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.keyboard-ownership.test.ts#CE1 preserves Enter confirmation on the dialog itself",
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.keyboard-ownership.test.ts#CE1 preserves exactly one Enter confirmation in the matching phrase field",
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.keyboard-ownership.test.ts#CE1 preserves no confirmation for an unmatched phrase with cancel focused",
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.keyboard-ownership.test.ts#CE1 preserves no confirmation for an unmatched phrase with close focused",
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.keyboard-ownership.test.ts#CE1 preserves no confirmation for an unmatched phrase with confirm focused",
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.keyboard-ownership.test.ts#CE1 preserves no confirmation for an unmatched phrase with input focused",
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.keyboard-ownership.test.ts#CE1 preserves no confirmation for an unmatched phrase with dialog focused"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineExclusionApplyPublicationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "EX2 preservation after merging origin/main (#885): this main test spies on deleteDocById to know that explicit Apply reached the normal exclusion delete. On this branch Apply calls deleteDocByIdWithOutcome (WX1/WX2), so the latch never fired and the test failed at line 73 ('explicit Apply reaches the normal exclusion delete'), in CI and locally. Only the spied method name changes; the spy still calls the real method, and every fixture, ordering wait and assertion is unchanged. Local run: fails unadapted for that reason, passes adapted.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S324",
          "quote": "After a successful Apply, fresh searches in both windows return none of the removed documents (staying out afterwards is WP3.1).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "WX1 Apply's result is stated in what the Engine can guarantee, not in indexed-document counts: it reports how many matching files and folders are confirmed no longer searchable (each deletion request the Worker confirmed), without claiming each was indexed before. Every deletion that returned an error, was refused, was rejected by the circuit breaker, or was never attempted (offline or missing watched folder, unreadable entry, entry cap) is listed as not removed or not checked; a failed or unknown deletion is never counted as removed. The Worker deletion contract is not widened. Apply uses an outcome-carrying variant of the prefix deletion; existing callers of deleteDocsByPathPrefix keep their current behaviour. S32.3's \"reports what it removed\" is read through WX1.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "WX2 (orchestrator decision under the user's delegation, 2026-10-21; agent-adopted, not owner words; refines WX1): the Worker's deleteByPath and deleteById replies gain one additive, optional field that is true only when the deletion was buffered for later application (index switching) instead of applied. Apply reports such matches as \"removal completes when the index rebuild finishes\", never as removed. A reply with a negative job count and no error, or any reply the Engine cannot interpret, counts as unknown, not removed. Existing callers and older readers of these replies are unaffected (the field defaults to false; no existing field changes meaning). Why: only the Worker knows at deletion time whether it buffered; an Engine-side refusal during rebuild would race a switch that starts mid-Apply.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "2. **Editor semantics (WP3.2).** Options: (a) explicit Save; Preview and Apply act on the saved list (Preview disabled while the draft is unsaved); no backend change; (b) Preview evaluates the unsaved draft (operation gains a patterns argument); (c) keep save-then-preview and say so. **Recommend (a).** Honest with no new backend surface; saving only affects future indexing, so previewing after save loses nothing.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.2 exclusions editor and Apply",
          "choice": "3. **Apply scope (WP3.2).** Options: (a) walk the disk as today and report unchecked scope; (b) also walk the index for matches whose files are gone or offline. **Recommend (a).** Gone files are handled by deletion cleanup (#793); offline ones are reported (S32.3). (b) is new machinery for a rare case.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

XA1 repair entries appended by builder session b0s1b46m on 2026-10-22; earlier entries and the prior acceptance record are retained.

EX1 preservation follow-up (baseline 4a7f96624): the existing Worker/index/queue assertions remain unchanged. The evidence producer compares the adapted check on both trees. A `passed` fail-before classification makes the producer exit 1 because it requires an assertion failure whenever `--before` is supplied; passing on both trees is preservation evidence for this signature Adaptation, not evidence of repaired product behavior. The unchanged-fake failure and original known-good comparison are retained in `tmp/wp32-third-repair/`.

OV1 repair entries appended by builder session 8acrycvp on 2026-10-22; all earlier entries and acceptance records are retained. The official producer used default dependency-link mode with baseline 052c08961. Preservation evidence intentionally exits 1 because its before checks pass; all candidate checks pass.

CE1 entries appended by builder session 8b7469qf. Official evidence uses default dependency-link mode and baseline bdb952543. Repair evidence exits 0; preservation evidence exits 1 because all eight before checks pass, with all candidate checks passing. No acceptance record authored. Shared-dialog native keyboard/reflow comparison and private Engine checks are retained under `tmp/wp32-ce1/`.

EX2 entry appended by orchestrator session 8c330fb4 on 2026-10-23 after CI on #909; no acceptance record authored.

## Acceptance records


```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "wp32-verifier-6ndxmugk",
  "verdict": "accept",
  "digest": "sha256:22c05b60ed49a80799dd7e2928626304c74c8dce636111721ac777db1ee92247",
  "note": "Accept test expectations: scenarios and delegated WX1/WX2 justify the changed oracles; independent repair checks pass candidate and fail baseline assertions. Product acceptance fails S32.7 (unlabelled confirmation input) and stale authorization prompts in other windows; live switching/refusal and rebuild decisions remain unverified."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "wp32-verifier-dnwld5tp",
  "verdict": "reject",
  "digest": "sha256:12361695a758767e4f1ca6eb4fface0be9acae0b584e9bd8dfaa22197078c615",
  "note": "Reject provenance: five entries cite XA1 as owner-adopted, but the verification brief explicitly identifies it as a delegated orchestrator decision. Cite delegation plus the XA1 choice using delegated-choice. Repair checks pass candidate and fail baseline assertions as required; this rejection does not dispute that behavior."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "wp32-verifier4-v6o7bin7",
  "verdict": "accept",
  "digest": "sha256:ddc6c1528dcb94b26738f2159a12e7d75e8f0742000ef76478efe3d6567c8649",
  "note": "Independent review of before-state tests, agreed sources and diff; EX1 only adapts the fake to completion-aware deletion and keeps every assertion; actual completed, buffered and error Worker replies checked. Five XA1 citations correctly identify a delegated choice. Scratch run-evidence repairs fail by assertions on their historical before states; preservation checks pass. This accepts test intent only: product verification fails S32.2 because override Preview and Apply differ from the list the editor says they use. See verifier-4 evidence report."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "wp32-verifier5-k3794ri4",
  "verdict": "accept",
  "digest": "sha256:4c9d3591865257cf3d7cb548b5e8a32d10a334347c8197eeae56fb568dad902a",
  "note": "Sources, before-state tests and diff independently reviewed. OV1 repairs fail assertions before and pass after; preservation checks pass. Expectations follow S32, AX, WX and delegated XA1/OV1. Test intent accepted; product verdict fails F1 reflow and F2 Enter on Cancel."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "wp32-verifier6-u3fn4n6l",
  "verdict": "accept",
  "digest": "sha256:42a664893dd64ed408655bbe4d3ee034bce56a918f45aae4f951328852b712b4",
  "note": "Sources, before-state oracles and diff independently reviewed. New behavior follows S32 and delegated AX/WX/XA1/OV1/CE1; adaptations preserve existing oracles. Independent scratch CE1 repairs fail assertions before and preservation passes; all candidate checks pass. Historical UI comparisons reproduce repair/preservation distinctions. Real private Engine/Worker and headless Library checks confirm the CE1 repair, partial outcomes and saved-policy semantics. This accepts test intent; environment reruns and product verification are reported separately."
}
```
