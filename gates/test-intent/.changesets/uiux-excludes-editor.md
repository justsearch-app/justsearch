---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: wp32-wx2-builder-20261021
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
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XA1",
          "quote": "An authorization prompt (\"Authorize action ...\") that was answered in another window, or whose pending request the Engine no longer knows (expired, answered, 404), disappears from every open window without user action, at the latest when that window next learns the request's state, and never blocks pointer or keyboard input. Answering a stale prompt changes nothing on the server and closes it with a short neutral note. A prompt for a request that is still pending stays and works as before. The S32.7 typed confirmation's input has an accessible name that includes the word to type (all typed confirmations share it).",
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
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XA1",
          "quote": "An authorization prompt (\"Authorize action ...\") that was answered in another window, or whose pending request the Engine no longer knows (expired, answered, 404), disappears from every open window without user action, at the latest when that window next learns the request's state, and never blocks pointer or keyboard input. Answering a stale prompt changes nothing on the server and closes it with a short neutral note. A prompt for a request that is still pending stays and works as before. The S32.7 typed confirmation's input has an accessible name that includes the word to type (all typed confirmations share it).",
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
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XA1",
          "quote": "An authorization prompt (\"Authorize action ...\") that was answered in another window, or whose pending request the Engine no longer knows (expired, answered, 404), disappears from every open window without user action, at the latest when that window next learns the request's state, and never blocks pointer or keyboard input. Answering a stale prompt changes nothing on the server and closes it with a short neutral note. A prompt for a request that is still pending stays and works as before. The S32.7 typed confirmation's input has an accessible name that includes the word to type (all typed confirmations share it).",
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
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XA1",
          "quote": "An authorization prompt (\"Authorize action ...\") that was answered in another window, or whose pending request the Engine no longer knows (expired, answered, 404), disappears from every open window without user action, at the latest when that window next learns the request's state, and never blocks pointer or keyboard input. Answering a stale prompt changes nothing on the server and closes it with a short neutral note. A prompt for a request that is still pending stays and works as before. The S32.7 typed confirmation's input has an accessible name that includes the word to type (all typed confirmations share it).",
          "label": "agent-drafted, owner-adopted"
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
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "XA1",
          "quote": "An authorization prompt (\"Authorize action ...\") that was answered in another window, or whose pending request the Engine no longer knows (expired, answered, 404), disappears from every open window without user action, at the latest when that window next learns the request's state, and never blocks pointer or keyboard input. Answering a stale prompt changes nothing on the server and closes it with a short neutral note. A prompt for a request that is still pending stays and works as before. The S32.7 typed confirmation's input has an accessible name that includes the word to type (all typed confirmations share it).",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    }
  ]
}
```

XA1 repair entries appended by builder session b0s1b46m on 2026-10-22; earlier entries and the prior acceptance record are retained.

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
