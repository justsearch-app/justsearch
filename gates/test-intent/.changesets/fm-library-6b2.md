---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: justsearch-fm-s6b2
---

# Slice 6b-2 test intent

Library's skip rules and reading hours save as you change them (picture note fm-07). Entries cite
the owner's picture note and the implementation delegation; each modified file and the reason for
changing its old assertions is listed individually. Saved-state checks observe exact bytes sent.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.excludes.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Library. The headline says the state, the folder row says how many and how current; nothing is said twice. Skip rules and reading hours save as you change them: no Save / Reload / Preview / Apply row; adding a rule that removes files asks in place.",
          "location": "task t-20261113-fmimpl agreement, picture note fm-07"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "B2': the ask is about the whole saved list because core.apply-excludes applies all effective rules: \"Remove files already read that match your skip rules from search? Files on disk are not touched.\" If the saved list changes while it is open (this or another window) it is withdrawn and asked again for the new list; one ask at a time; it does not take focus (announced politely). B4': per-rule Preview counts are behind developer tools (Count matching files); the removal outcome is plain: done, partly done (offline or missing folders, unreadable files, failed deletions) with Try again, or not sure with Try again; never complete from a success receipt alone; files no longer on disk are not checked (stated). Review repairs (R6-R10): R6 core.apply-excludes takes the saved list the ask was about (optional rules) and refuses with EXCLUDES_CHANGED, removing nothing, when the rules in effect differ at apply time (another window, an override); the page withdraws the ask and asks again for the list now saved; callers without rules keep the earlier behaviour under the HIGH-risk consent capsule; the operation declares an Inline confirmation (the in-place ask), no typed word. R7 the bytes saved are the draft captured when the field was left; queued saves and B11 re-sends send that snapshot, never the live text. R8 reading hours follow B3's unknown-outcome rule: same key and bytes kept, Not sure this saved with Try again, settled before any newer change is judged or sent. R9 reading-hours saves already requested finish after leaving Library. R10 removal words say only what the result establishes: no number of files from deletion-request counts.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Removal is reached through the in-place ask after a rule is saved, consent is that answer (no second dialog), and outcomes are said in plain words. The outcome names no number of files: confirmedRemovals counts deletion requests (R10).",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for skip rules, exclude patterns, apply-excludes, Save patterns, indexing/reading rules, reading hours and explicit save/autosave: only 0008-settings-ephemeral-defaults-safe.md mentions excludePatterns (that it persists), which this keeps; no conflict. docs/reference/secondary-views-behavior.md described explicit Save as an intermediate slice and is updated here. Private records were not read, per the builder brief.",
      "assertionChanges": [
        {
          "oldAssertion": "S32.1 typed Apply confirmation (showConfirmDialog with typedConfirmWord core.apply-excludes) recovers the 428.",
          "newAssertion": "Remove from search in the in-place ask recovers the same 428 transport sequence; showConfirmDialog is never called.",
          "reason": "fm-07: no Apply row; adding a rule asks in place."
        },
        {
          "oldAssertion": "S32.1 cancelling the typed dialog removes nothing.",
          "newAssertion": "Keep them removes nothing and writes nothing more.",
          "reason": "The ask's second answer replaces Cancel."
        },
        {
          "oldAssertion": "S32.2 Save patterns exists and an unsaved draft disables Preview and Apply.",
          "newAssertion": "No Save/Preview/Apply/Reload control; a draft is not saved before its field is left.",
          "reason": "fm-07 and B3'."
        },
        {
          "oldAssertion": "S32.3/WX1/WX2 'Partial Apply' with engine reasons as text; 'Apply complete'; 'unconfirmed'.",
          "newAssertion": "'Partly done' with plain categories per path (Offline or missing folder, Couldn't be removed, Waits for the rebuild to finish), engine reason kept as the item's title; 'Not sure the files were removed' for an unparseable receipt; 'Done. Files already read that match your skip rules are no longer in search'; no outcome says a number of files removed.",
          "reason": "B4' plain outcome words; every scope stays asserted. R10: main's 'N matching files or folders confirmed' counted deletion requests (one per folder); the outcome now states no number rather than call requests files."
        },
        {
          "oldAssertion": "S32.5 Review latest settings, keep draft, then Save.",
          "newAssertion": "Changed elsewhere shows the saved list; Keep my draft saves the draft on the shown revision (witness 1).",
          "reason": "B6'/S6b-5 conflict choice."
        },
        {
          "oldAssertion": "S32.6 server INVALID_REQUEST for [broken shown in #excludes-error.",
          "newAssertion": "[broken is caught before sending: field aria-invalid, #rule-problem-0 alert says a [ needs a closing ], no POST.",
          "reason": "S6b-3: a half pattern is never saved; the reason shows."
        },
        {
          "oldAssertion": "S32.7 Working on exclusions busy status and disabled Save/Preview/Apply/Reload during Apply.",
          "newAssertion": "During a removal in flight both answers are aria-disabled and ignored; one final Done status in the polite removal slot.",
          "reason": "No button row exists; the overlap guard is kept."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.excludes-override.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Library. The headline says the state, the folder row says how many and how current; nothing is said twice. Skip rules and reading hours save as you change them: no Save / Reload / Preview / Apply row; adding a rule that removes files asks in place.",
          "location": "task t-20261113-fmimpl agreement, picture note fm-07"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "S6b-12': an override keeps today's behaviour: editable and saved; the note explains the saved list takes effect when the override is removed; nothing is asked (the saved list removes nothing while overridden). B4': per-rule Preview counts are behind developer tools (Count matching files); the removal outcome is plain: done, partly done (offline or missing folders, unreadable files, failed deletions) with Try again, or not sure with Try again; never complete from a success receipt alone; files no longer on disk are not checked (stated). Review repairs (R6-R10): R6 core.apply-excludes takes the saved list the ask was about (optional rules) and refuses with EXCLUDES_CHANGED, removing nothing, when the rules in effect differ at apply time (another window, an override); the page withdraws the ask and asks again for the list now saved; callers without rules keep the earlier behaviour under the HIGH-risk consent capsule; the operation declares an Inline confirmation (the in-place ask), no typed word. R7 the bytes saved are the draft captured when the field was left; queued saves and B11 re-sends send that snapshot, never the live text. R8 reading hours follow B3's unknown-outcome rule: same key and bytes kept, Not sure this saved with Try again, settled before any newer change is judged or sent. R9 reading-hours saves already requested finish after leaving Library. R10 removal words say only what the result establishes: no number of files from deletion-request counts.",
          "label": "agent choice"
        }
      ],
      "behaviour": "The override note is visible before any change and names source and patterns; the developer count shows the effective patterns; saving still works and explains when it takes effect; nothing is asked or removed while overridden; an unreadable policy blocks counting and removal with a plain alert.",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for skip rules, exclude patterns, apply-excludes, Save patterns, indexing/reading rules, reading hours and explicit save/autosave: only 0008-settings-ephemeral-defaults-safe.md mentions excludePatterns (that it persists), which this keeps; no conflict. docs/reference/secondary-views-behavior.md described explicit Save as an intermediate slice and is updated here. Private records were not read, per the builder brief.",
      "assertionChanges": [
        {
          "oldAssertion": "OV1 Preview, Apply and the typed confirmation name the override.",
          "newAssertion": "The developer count names it; adding a rule asks nothing and handleApplyExcludes invokes no removal; no dialog.",
          "reason": "S6b-12' and B2' (the saved list removes nothing while overridden)."
        },
        {
          "oldAssertion": "OV1 help says 'using the saved list'; Apply confirmation says 'Apply saved patterns'.",
          "newAssertion": "Help says 'Saving a rule changes what future reading skips'; adding a rule asks in place and Remove from search invokes core.apply-excludes with the asked-about list {rules: ['saved-only.txt', 'more.txt']} and {consented: true}.",
          "reason": "fm-07; R6: the removal is bound to the list the ask was about."
        },
        {
          "oldAssertion": "OV1 empty override: confirmation contains (none).",
          "newAssertion": "The note contains (none); nothing is asked.",
          "reason": "S6b-12'."
        },
        {
          "oldAssertion": "OV1 unreadable policy: Apply disabled, alert contains 'policy'.",
          "newAssertion": "No ask, no invocation, alert says the rules in effect couldn't be checked.",
          "reason": "Plain words; the gate is kept."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.settings-attempt.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Library. The headline says the state, the folder row says how many and how current; nothing is said twice. Skip rules and reading hours save as you change them: no Save / Reload / Preview / Apply row; adding a rule that removes files asks in place.",
          "location": "task t-20261113-fmimpl agreement, picture note fm-07"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "B3': save on leaving the field only (no idle timer); leaving Library or switching tabs leaves the field and saves; an unknown outcome keeps the same key and bytes, reconciles before any newer draft and shows Not sure this saved with Try again; a load failure says Couldn't load your skip rules with Try again and the editor is not editable until loaded. B6': each core.set-indexing-rules write carries the settings revision its draft was based on (optional witness argument, frozen as the prepared witness); a save elsewhere since is VERSION_CONFLICT; Library shows Changed elsewhere with Use the saved rules / Keep my draft. A refusal whose re-read shows the rules unchanged since the draft's base is rebased (unrelated settings saved elsewhere are not a conflict). B2': the ask is about the whole saved list because core.apply-excludes applies all effective rules: \"Remove files already read that match your skip rules from search? Files on disk are not touched.\" If the saved list changes while it is open (this or another window) it is withdrawn and asked again for the new list; one ask at a time; it does not take focus (announced politely). Review repairs (R6-R10): R6 core.apply-excludes takes the saved list the ask was about (optional rules) and refuses with EXCLUDES_CHANGED, removing nothing, when the rules in effect differ at apply time (another window, an override); the page withdraws the ask and asks again for the list now saved; callers without rules keep the earlier behaviour under the HIGH-risk consent capsule; the operation declares an Inline confirmation (the in-place ask), no typed word. R7 the bytes saved are the draft captured when the field was left; queued saves and B11 re-sends send that snapshot, never the live text. R8 reading hours follow B3's unknown-outcome rule: same key and bytes kept, Not sure this saved with Try again, settled before any newer change is judged or sent. R9 reading-hours saves already requested finish after leaving Library. R10 removal words say only what the result establishes: no number of files from deletion-request counts.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Exact-path bytes, retained-attempt bytes, witness chaining and the 202 wait are unchanged; removal of a keep-out saves at once; a conflict keeps the draft editable with its choice; load failure is said in the section; capacity refusals are plain.",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for skip rules, exclude patterns, apply-excludes, Save patterns, indexing/reading rules, reading hours and explicit save/autosave: only 0008-settings-ephemeral-defaults-safe.md mentions excludePatterns (that it persists), which this keeps; no conflict. docs/reference/secondary-views-behavior.md described explicit Save as an intermediate slice and is updated here. Private records were not read, per the builder brief.",
      "assertionChanges": [
        {
          "oldAssertion": "RF55 removing a keep-out posts nothing until Save patterns.",
          "newAssertion": "Removing a keep-out posts at once: ['*.log'] on the observed witness.",
          "reason": "fm-07: saves as you change them."
        },
        {
          "oldAssertion": "AX1 unchanged legacy list previewed and applied without a write.",
          "newAssertion": "A legacy literal-name rule survives saving another rule and removal applies the saved list (one POST), carrying it unchanged: {rules: ['[legacy', 'private', 'more']}.",
          "reason": "Apply exists only as the ask's answer after a save; R6 binds it to the saved list."
        },
        {
          "oldAssertion": "Failed/unwitnessed load sets the page-level error banner (library.error).",
          "newAssertion": "The section alert says Couldn't load your skip rules; the field is disabled; no POST.",
          "reason": "B3' load failure wording; said once."
        },
        {
          "oldAssertion": "Conflict sets excludesLoaded=false until an explicit reload; a fresh attempt after reload.",
          "newAssertion": "The draft stays editable, no write until a choice; Keep my draft sends a fresh key on the shown revision 6.",
          "reason": "B6'/S6b-5 choice."
        },
        {
          "oldAssertion": "Conflict renders disabled controls and an Apply button.",
          "newAssertion": "Field stays enabled with Changed elsewhere; no Apply control; the draft survives remount.",
          "reason": "A save must not disable a field (S6b-4) and fm-07."
        },
        {
          "oldAssertion": "EX4 error text is the raw 'Invalid settings request'; one POST.",
          "newAssertion": "Plain words: 'At most 512 skip rules can be saved; this list has 513...' / 'These skip rules could not be saved...'; leaving Library tries the same draft once more and is refused the same way; draft kept.",
          "reason": "512-entry capacity scenario; B3' leaving saves."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.slice2.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Library. The headline says the state, the folder row says how many and how current; nothing is said twice. Skip rules and reading hours save as you change them: no Save / Reload / Preview / Apply row; adding a rule that removes files asks in place.",
          "location": "task t-20261113-fmimpl agreement, picture note fm-07"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "B3': save on leaving the field only (no idle timer); leaving Library or switching tabs leaves the field and saves; an unknown outcome keeps the same key and bytes, reconciles before any newer draft and shows Not sure this saved with Try again; a load failure says Couldn't load your skip rules with Try again and the editor is not editable until loaded. B6': each core.set-indexing-rules write carries the settings revision its draft was based on (optional witness argument, frozen as the prepared witness); a save elsewhere since is VERSION_CONFLICT; Library shows Changed elsewhere with Use the saved rules / Keep my draft. A refusal whose re-read shows the rules unchanged since the draft's base is rebased (unrelated settings saved elsewhere are not a conflict).",
          "label": "agent choice"
        }
      ],
      "behaviour": "Both editors save on change and share the write queue; no refused round trip after Library's own writes; external rule changes conflict with Changed elsewhere; unrelated external writes do not; the receipt witness is never trusted unless it is this write's direct successor.",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for skip rules, exclude patterns, apply-excludes, Save patterns, indexing/reading rules, reading hours and explicit save/autosave: only 0008-settings-ephemeral-defaults-safe.md mentions excludePatterns (that it persists), which this keeps; no conflict. docs/reference/secondary-views-behavior.md described explicit Save as an intermediate slice and is updated here. Private records were not read, per the builder brief.",
      "assertionChanges": [
        {
          "oldAssertion": "Save patterns / Save reading rules buttons drive every save; B1 asserts no implicit save.",
          "newAssertion": "Leaving a skip-rule field or changing a rule saves; B1 still asserts nothing is saved while typing.",
          "reason": "fm-07."
        },
        {
          "oldAssertion": "V3/R2/R2b 'Reading times changed elsewhere' and Save my draft / Discard my draft; R2b expects no write after a later second-window save.",
          "newAssertion": "'Changed elsewhere' with Keep my draft / Use the saved rules; R2b Keep my draft sends one write on the shown revision, refused, so the later save is not overwritten, then a second Keep writes.",
          "reason": "B6' moves the revision check to the server."
        },
        {
          "oldAssertion": "B unrelated external write conflicts with skip rules until explicit review.",
          "newAssertion": "An unrelated external write is not a conflict for either editor; each refused write is re-sent only after reading the saved value.",
          "reason": "B6' rebase rule; no stale witness or lost draft."
        },
        {
          "oldAssertion": "B missing/gap/wrong-key receipt: skip save conflicts with no extra read.",
          "newAssertion": "The unverified receipt is not used: the first POST carries revision 0, is refused, one read follows, then revision 1 succeeds.",
          "reason": "Same protection, plus rebase."
        },
        {
          "oldAssertion": "R2 reconnect keeps a dirty battery-only draft.",
          "newAssertion": "A battery change saves at once; the reconnect case uses an hours draft still being typed, which leaving saves and finds changed elsewhere.",
          "reason": "S6b-6 battery saves on change."
        },
        {
          "oldAssertion": "L19 first rule write args {schedule:null,pauseOnBattery:true}.",
          "newAssertion": "Same args plus the base witness (revision 0, or 1 after Library's own skip save) and exactly one rule write.",
          "reason": "B6'."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Library. The headline says the state, the folder row says how many and how current; nothing is said twice. Skip rules and reading hours save as you change them: no Save / Reload / Preview / Apply row; adding a rule that removes files asks in place.",
          "location": "task t-20261113-fmimpl agreement, picture note fm-07"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "B6': each core.set-indexing-rules write carries the settings revision its draft was based on (optional witness argument, frozen as the prepared witness); a save elsewhere since is VERSION_CONFLICT; Library shows Changed elsewhere with Use the saved rules / Keep my draft. A refusal whose re-read shows the rules unchanged since the draft's base is rebased (unrelated settings saved elsewhere are not a conflict). S6b-6: reading hours and battery rule save on change; invalid combination not sent.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Hours, days and battery save on change with their base witness; invalid combinations are refused with the same messages and not sent; a pending save keeps focus and enabled controls; approval replays the same args.",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for skip rules, exclude patterns, apply-excludes, Save patterns, indexing/reading rules, reading hours and explicit save/autosave: only 0008-settings-ephemeral-defaults-safe.md mentions excludePatterns (that it persists), which this keeps; no conflict. docs/reference/secondary-views-behavior.md described explicit Save as an intermediate slice and is updated here. Private records were not read, per the builder brief.",
      "assertionChanges": [
        {
          "oldAssertion": "Rule args equal {schedule, pauseOnBattery}; saves only on submit.",
          "newAssertion": "Args add the fixture's witness; each change saves once; an unchanged submit sends nothing.",
          "reason": "B6' and S6b-6."
        },
        {
          "oldAssertion": "S38.2 days: no call until both days are cleared.",
          "newAssertion": "Clearing Monday saves days [7]; clearing Sunday too is refused and not sent.",
          "reason": "Saves on change; invalid not sent."
        },
        {
          "oldAssertion": "S38.2 409/200 VERSION_CONFLICT shows 'Review your draft and save again'.",
          "newAssertion": "A conflict re-reads the saved rules and shows Changed elsewhere with the other window's battery rule; Keep my draft writes on that revision without a preparation nonce; 400 still shows the engine message.",
          "reason": "B6'."
        },
        {
          "oldAssertion": "S38.7 Save button keeps focus and aria-disabled while pending; fieldset disabled.",
          "newAssertion": "The changed checkbox keeps focus while pending, the fieldset stays enabled, one rule write.",
          "reason": "No Save button; a save never disables controls (S6b-4, S6b-11)."
        },
        {
          "oldAssertion": "Fixture settings response has no witness.",
          "newAssertion": "Fixture includes a witness (the Head always returns one).",
          "reason": "Writes require their base revision."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/library-first-minutes/presentation.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Library. The headline says the state, the folder row says how many and how current; nothing is said twice. Skip rules and reading hours save as you change them: no Save / Reload / Preview / Apply row; adding a rule that removes files asks in place.",
          "location": "task t-20261113-fmimpl agreement, picture note fm-07"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Keep the skip-rule editor in the default view while removing its Save row.",
          "label": "agent choice"
        }
      ],
      "behaviour": "The default view still holds the skip-rule editor (Add a rule present) and has no Save patterns control.",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for skip rules, exclude patterns, apply-excludes, Save patterns, indexing/reading rules, reading hours and explicit save/autosave: only 0008-settings-ephemeral-defaults-safe.md mentions excludePatterns (that it persists), which this keeps; no conflict. docs/reference/secondary-views-behavior.md described explicit Save as an intermediate slice and is updated here. Private records were not read, per the builder brief.",
      "assertionChanges": [
        {
          "oldAssertion": "[label=\"Save patterns\"] is present.",
          "newAssertion": "[label=\"Add a rule\"] is present and [label=\"Save patterns\"] is absent.",
          "reason": "fm-07: no Save row."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.refresh-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Library. The headline says the state, the folder row says how many and how current; nothing is said twice. Skip rules and reading hours save as you change them: no Save / Reload / Preview / Apply row; adding a rule that removes files asks in place.",
          "location": "task t-20261113-fmimpl agreement, picture note fm-07"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "B5': Undo only for the last skip-rule glob change while no other settings save happened since; it restores the rule list only and says removed files come back when read again; main's exact-path Undo is removed, exact-path authority unchanged. B3': save on leaving the field only (no idle timer); leaving Library or switching tabs leaves the field and saves; an unknown outcome keeps the same key and bytes, reconciles before any newer draft and shows Not sure this saved with Try again; a load failure says Couldn't load your skip rules with Try again and the editor is not editable until loaded. S6b-12': an override keeps today's behaviour: editable and saved; the note explains the saved list takes effect when the override is removed; nothing is asked (the saved list removes nothing while overridden).",
          "label": "agent choice"
        }
      ],
      "behaviour": "Two real windows still observe each other's saves on focus and return, keep drafts and conflict witnesses, keep uncertain bytes and keyed retries, and retain exact-path bytes; exact-path removal saves at once without Undo; override keeps saving and asks nothing.",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for skip rules, exclude patterns, apply-excludes, Save patterns, indexing/reading rules, reading hours and explicit save/autosave: only 0008-settings-ephemeral-defaults-safe.md mentions excludePatterns (that it persists), which this keeps; no conflict. docs/reference/secondary-views-behavior.md described explicit Save as an intermediate slice and is updated here. Private records were not read, per the builder brief.",
      "assertionChanges": [
        {
          "oldAssertion": "L2 Undo after an exact-path removal restores the literal.",
          "newAssertion": "Removal saves at once; Undo re-adds nothing and never rewinds over a later save.",
          "reason": "B5': main's exact-path Undo is removed."
        },
        {
          "oldAssertion": "L3 has an exact-path draft variant.",
          "newAssertion": "Text variant only: an exact-path removal is saved at once (covered by settings-attempt RF55).",
          "reason": "fm-07."
        },
        {
          "oldAssertion": "L5 exactly two writes with identical bytes.",
          "newAssertion": "Three writes, all identical bytes (leaving Library checks the same attempt again).",
          "reason": "B3'."
        },
        {
          "oldAssertion": "L7 'reload' via Reload saved patterns.",
          "newAssertion": "'return' via leaving and returning to Library; same retained-bytes assertions.",
          "reason": "No Reload button."
        },
        {
          "oldAssertion": "L8 Preview and Apply enabled under an override.",
          "newAssertion": "No Preview/Apply/Save controls; a draft saves; no removal ask.",
          "reason": "S6b-12'."
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/resources/registry/emitter/ui-operation-wire.golden.json"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Library. The headline says the state, the folder row says how many and how current; nothing is said twice. Skip rules and reading hours save as you change them: no Save / Reload / Preview / Apply row; adding a rule that removes files asks in place.",
          "location": "task t-20261113-fmimpl agreement, picture note fm-07"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "B6': each core.set-indexing-rules write carries the settings revision its draft was based on (optional witness argument, frozen as the prepared witness); a save elsewhere since is VERSION_CONFLICT; Library shows Changed elsewhere with Use the saved rules / Keep my draft. A refusal whose re-read shows the rules unchanged since the draft's base is rebased (unrelated settings saved elsewhere are not a conflict). Review repairs (R6-R10): R6 core.apply-excludes takes the saved list the ask was about (optional rules) and refuses with EXCLUDES_CHANGED, removing nothing, when the rules in effect differ at apply time (another window, an override); the page withdraws the ask and asks again for the list now saved; callers without rules keep the earlier behaviour under the HIGH-risk consent capsule; the operation declares an Inline confirmation (the in-place ask), no typed word. R7 the bytes saved are the draft captured when the field was left; queued saves and B11 re-sends send that snapshot, never the live text. R8 reading hours follow B3's unknown-outcome rule: same key and bytes kept, Not sure this saved with Try again, settled before any newer change is judged or sent. R9 reading-hours saves already requested finish after leaving Library. R10 removal words say only what the result establishes: no number of files from deletion-request counts.",
          "label": "agent choice"
        }
      ],
      "behaviour": "The operation wire golden records core.set-indexing-rules accepting an optional witness {acceptedRevision >= 0, lastCommittedOperationKey string|null} (required fields and additionalProperties:false unchanged), and core.apply-excludes accepting an optional rules array (at most 512 strings or exact-path objects, additionalProperties:false) with an INLINE confirmation in place of TYPED ops.apply-excludes.confirm; risk stays HIGH.",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for skip rules, exclude patterns, apply-excludes, Save patterns, indexing/reading rules, reading hours and explicit save/autosave: only 0008-settings-ephemeral-defaults-safe.md mentions excludePatterns (that it persists), which this keeps; no conflict. docs/reference/secondary-views-behavior.md described explicit Save as an intermediate slice and is updated here. Private records were not read, per the builder brief.",
      "assertionChanges": [
        {
          "oldAssertion": "Inputs: schedule, pauseOnBattery only.",
          "newAssertion": "Inputs: schedule, pauseOnBattery and optional witness (strict object).",
          "reason": "B6': the write carries the revision its draft was based on."
        },
        {
          "oldAssertion": "core.apply-excludes: inputs {} (no properties); confirm TYPED with confirmTextKey ops.apply-excludes.confirm.",
          "newAssertion": "core.apply-excludes: inputs {rules: array of string|object, maxItems 512}, additionalProperties false; confirm INLINE.",
          "reason": "R6: the removal carries the list its consent was given for, and the declared consent is Library's in-place ask."
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/IndexingRulesIntentTest.java"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "New",
      "repaired": true,
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Library. The headline says the state, the folder row says how many and how current; nothing is said twice. Skip rules and reading hours save as you change them: no Save / Reload / Preview / Apply row; adding a rule that removes files asks in place.",
          "location": "task t-20261113-fmimpl agreement, picture note fm-07"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "B6': each core.set-indexing-rules write carries the settings revision its draft was based on (optional witness argument, frozen as the prepared witness); a save elsewhere since is VERSION_CONFLICT; Library shows Changed elsewhere with Use the saved rules / Keep my draft. A refusal whose re-read shows the rules unchanged since the draft's base is rebased (unrelated settings saved elsewhere are not a conflict). Added scenario: overnight Sunday hours survive a Head restart (the settings owner and its IndexingHold); manual pause intent kept.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Two added cases; the three existing ones are unchanged. ruleWriteCarryingItsDraftRevisionConflictsAfterAnotherWindowSaved: after another window saves, a write carrying the older witness fails VERSION_CONFLICT and leaves witness and settings unchanged; the same rules on the current witness commit once and replay by key without a new revision; malformed witnesses are BAD_REQUEST. overnightSundayHoursAndManualPauseSurviveHeadRestart: Sunday 22:00-06:00 written with its witness plus a manual pause survive a new UiSettingsStore on the same file (Head restart): schedule bytes equal, paused with USER_PAUSED, allows Sunday 23:20 and Monday 05:00, not Monday 07:00. On origin/main both fail by assertion (BAD_REQUEST instead of VERSION_CONFLICT; the witnessed write is refused).",
      "evidence": "gates/test-intent/evidence/fm-library-6b2.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/IndexingRulesIntentTest.java"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/library-first-minutes/slice-6b2.test.ts"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "repaired": true,
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Library. The headline says the state, the folder row says how many and how current; nothing is said twice. Skip rules and reading hours save as you change them: no Save / Reload / Preview / Apply row; adding a rule that removes files asks in place.",
          "location": "task t-20261113-fmimpl agreement, picture note fm-07"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "B2': the ask is about the whole saved list because core.apply-excludes applies all effective rules: \"Remove files already read that match your skip rules from search? Files on disk are not touched.\" If the saved list changes while it is open (this or another window) it is withdrawn and asked again for the new list; one ask at a time; it does not take focus (announced politely). B3': save on leaving the field only (no idle timer); leaving Library or switching tabs leaves the field and saves; an unknown outcome keeps the same key and bytes, reconciles before any newer draft and shows Not sure this saved with Try again; a load failure says Couldn't load your skip rules with Try again and the editor is not editable until loaded. B4': per-rule Preview counts are behind developer tools (Count matching files); the removal outcome is plain: done, partly done (offline or missing folders, unreadable files, failed deletions) with Try again, or not sure with Try again; never complete from a success receipt alone; files no longer on disk are not checked (stated). B5': Undo only for the last skip-rule glob change while no other settings save happened since; it restores the rule list only and says removed files come back when read again; main's exact-path Undo is removed, exact-path authority unchanged. B6': each core.set-indexing-rules write carries the settings revision its draft was based on (optional witness argument, frozen as the prepared witness); a save elsewhere since is VERSION_CONFLICT; Library shows Changed elsewhere with Use the saved rules / Keep my draft. A refusal whose re-read shows the rules unchanged since the draft's base is rebased (unrelated settings saved elsewhere are not a conflict). S6b-12': an override keeps today's behaviour: editable and saved; the note explains the saved list takes effect when the override is removed; nothing is asked (the saved list removes nothing while overridden). Added scenarios: first run writes nothing on open; deleting the last rule saves an empty list; 512-entry capacity refusal keeps the draft and other rules; exact-path entries keep ids, scope and bytes across glob saves; battery-only first change keeps a null schedule; turning hours off keeps days/times; manual pause intent kept; skip-rule save, hours save and toolbar pause/resume overlap without stale witness or lost draft; a file missing on disk stays searchable after removal (stated). Review repairs (R6-R10): R6 core.apply-excludes takes the saved list the ask was about (optional rules) and refuses with EXCLUDES_CHANGED, removing nothing, when the rules in effect differ at apply time (another window, an override); the page withdraws the ask and asks again for the list now saved; callers without rules keep the earlier behaviour under the HIGH-risk consent capsule; the operation declares an Inline confirmation (the in-place ask), no typed word. R7 the bytes saved are the draft captured when the field was left; queued saves and B11 re-sends send that snapshot, never the live text. R8 reading hours follow B3's unknown-outcome rule: same key and bytes kept, Not sure this saved with Try again, settled before any newer change is judged or sent. R9 reading-hours saves already requested finish after leaving Library. R10 removal words say only what the result establishes: no number of files from deletion-request counts. Second review repairs: R11 both editors save by one rule: every save-triggering event (leaving a field, a reading-hours change, Try again, Undo) captures an immutable snapshot of exactly what is to be saved and appends it to that editor's ordered queue; one write in flight; an unknown outcome stops the queue until it is reconciled (same key and bytes), then the queue continues with the remaining snapshots, each rebased only on a revision whose rules equal that snapshot's base (else Changed elsewhere, B6'/B11); the queue survives leaving Library and coming back (a returning page shows queued snapshots as not yet saved and never replaces them with an older saved base); nothing reads live text when sending. R12 each removal ask and its result carry the serial and list they were asked for; a result only updates the outcome for its own list and never clears a newer ask; outcome words name only the applied list.",
          "label": "agent choice"
        }
      ],
      "behaviour": "48 happy-dom cases over a fake settings owner (witness, keyed replay, literal authority, 512 capacity, half-pattern refusal) that records exact POST bodies and operation args: S6b-1 (no button row; first run writes nothing; leave-to-save exact bytes, patch holds only ui.excludePatterns, theme/mode kept, survives remount; blank row writes nothing; last rule deleted saves []; exact-path id/scope/bytes kept), S6b-2/B2 (in-place polite ask, no focus taken, Keep them / Remove from search, re-ask when the list changed, one ask), B4 (done/partly/not sure, plain categories, limitation stated, developer-only counts), S6b-3 ([a never sent, reason via aria-describedby, fixed saves; legacy rule kept), S6b-4/B3 (in-flight typing, no premature saved, unknown outcome same bytes then newer draft, tab switch and leaving save, load failure), S6b-5/B6 (Changed elsewhere both choices for rules and hours, unrelated saves not a conflict), S6b-6 (battery null schedule with witness, hours, start=end refused, hours off keeps times), S6b-7 (Head save with Worker down, removal cannot run now), overlap with toolbar pause, S6b-9/B5 Undo, S6b-12' override. Seven review-repair cases: R6 (a list saved elsewhere between the check and the removal removes nothing; the call carries {rules}; asked again for the new list), R7 twice (a queued save and a B11 re-send send the draft captured when the field was left, not text typed after returning), R8 twice (a lost answer says Not sure this saved with Try again; the same key and bytes settle it before the newer change; Try again replays without a new revision), R9 (a change queued behind a pending save is saved after leaving Library), R10 (one confirmed folder request is not '1 file removed'). The fake engine replays operations by key and refuses a removal for another list, as the real ones do. Nine second-review cases (R11, R12): the four review counterexamples — a reading-hours change queued behind a write whose answer was lost survives leaving and returning and is saved after that write is settled; a newer skip-rule draft left while an uncertain write waits is saved after it, both when that write is rebased across an unrelated save and when its list is found saved; a reading-hours time is saved as it was when its field was left, not as typed after returning; an older removal result keeps the newer ask and names only the list it removed for — and five further orderings: an unknown outcome then leaving and returning keeps the newer draft shown as not saved yet; two queued snapshots of each editor are saved in order across an unrelated revision move; Undo pressed behind an uncertain write settles that write first and never overwrites it; a draft left behind an uncertain Undo is saved after the Undo is settled. On origin/main all 48 fail by assertion; on dfdd5e42ea the seven R6-R10 cases fail by assertion; on 67e5f1400c the nine R11/R12 cases fail by assertion and the other 39 pass.",
      "evidence": "gates/test-intent/evidence/fm-library-6b2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/library-first-minutes/slice-6b2.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/library-first-minutes/slice-6b2-lockdown.test.ts"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "repaired": true,
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Library. The headline says the state, the folder row says how many and how current; nothing is said twice. Skip rules and reading hours save as you change them: no Save / Reload / Preview / Apply row; adding a rule that removes files asks in place.",
          "location": "task t-20261113-fmimpl agreement, picture note fm-07"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "B2': the ask is about the whole saved list because core.apply-excludes applies all effective rules: \"Remove files already read that match your skip rules from search? Files on disk are not touched.\" If the saved list changes while it is open (this or another window) it is withdrawn and asked again for the new list; one ask at a time; it does not take focus (announced politely). B3': save on leaving the field only (no idle timer); leaving Library or switching tabs leaves the field and saves; an unknown outcome keeps the same key and bytes, reconciles before any newer draft and shows Not sure this saved with Try again; a load failure says Couldn't load your skip rules with Try again and the editor is not editable until loaded. S6b-11: keyboard and screen reader reach every new control, the ask is announced, focus returns sensibly; light and dark; 600 px to wide.",
          "label": "agent choice"
        }
      ],
      "behaviour": "7 headless-Chromium cases: Tab out of a new rule saves exactly ['*.log','*.tmp'], focus stays where Tab went, the ask region is inside a polite live region and Keep them by keyboard returns focus to the rule list without invoking anything; a held save never disables the focused field and the later text is saved on Tab; Only between via ArrowDown and battery via Space save with their witness and keep focus; at 600 and 1280 px in light and dark both answers fit the viewport, the page does not scroll sideways and text differs from background. On origin/main all 7 fail by assertion.",
      "evidence": "gates/test-intent/evidence/fm-library-6b2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/library-first-minutes/slice-6b2-lockdown.test.ts"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/CoreOperationCatalogTest.java"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Library. The headline says the state, the folder row says how many and how current; nothing is said twice. Skip rules and reading hours save as you change them: no Save / Reload / Preview / Apply row; adding a rule that removes files asks in place.",
          "location": "task t-20261113-fmimpl agreement, picture note fm-07"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "B2': the ask is about the whole saved list because core.apply-excludes applies all effective rules: \"Remove files already read that match your skip rules from search? Files on disk are not touched.\" If the saved list changes while it is open (this or another window) it is withdrawn and asked again for the new list; one ask at a time; it does not take focus (announced politely). B3': save on leaving the field only (no idle timer); leaving Library or switching tabs leaves the field and saves; an unknown outcome keeps the same key and bytes, reconciles before any newer draft and shows Not sure this saved with Try again; a load failure says Couldn't load your skip rules with Try again and the editor is not editable until loaded. B4': per-rule Preview counts are behind developer tools (Count matching files); the removal outcome is plain: done, partly done (offline or missing folders, unreadable files, failed deletions) with Try again, or not sure with Try again; never complete from a success receipt alone; files no longer on disk are not checked (stated). B5': Undo only for the last skip-rule glob change while no other settings save happened since; it restores the rule list only and says removed files come back when read again; main's exact-path Undo is removed, exact-path authority unchanged. B6': each core.set-indexing-rules write carries the settings revision its draft was based on (optional witness argument, frozen as the prepared witness); a save elsewhere since is VERSION_CONFLICT; Library shows Changed elsewhere with Use the saved rules / Keep my draft. A refusal whose re-read shows the rules unchanged since the draft's base is rebased (unrelated settings saved elsewhere are not a conflict). S6b-12': an override keeps today's behaviour: editable and saved; the note explains the saved list takes effect when the override is removed; nothing is asked (the saved list removes nothing while overridden). Added scenarios: first run writes nothing on open; deleting the last rule saves an empty list; 512-entry capacity refusal keeps the draft and other rules; exact-path entries keep ids, scope and bytes across glob saves; battery-only first change keeps a null schedule; turning hours off keeps days/times; manual pause intent kept; skip-rule save, hours save and toolbar pause/resume overlap without stale witness or lost draft; a file missing on disk stays searchable after removal (stated). Review repairs (R6-R10): R6 core.apply-excludes takes the saved list the ask was about (optional rules) and refuses with EXCLUDES_CHANGED, removing nothing, when the rules in effect differ at apply time (another window, an override); the page withdraws the ask and asks again for the list now saved; callers without rules keep the earlier behaviour under the HIGH-risk consent capsule; the operation declares an Inline confirmation (the in-place ask), no typed word. R7 the bytes saved are the draft captured when the field was left; queued saves and B11 re-sends send that snapshot, never the live text. R8 reading hours follow B3's unknown-outcome rule: same key and bytes kept, Not sure this saved with Try again, settled before any newer change is judged or sent. R9 reading-hours saves already requested finish after leaving Library. R10 removal words say only what the result establishes: no number of files from deletion-request counts.",
          "label": "agent choice"
        }
      ],
      "behaviour": "core.apply-excludes stays HIGH risk and UI-only; its declared consent is Inline (Library's in-place ask) and its inputs name the optional rules list.",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for skip rules, exclude patterns, apply-excludes, Save patterns, indexing/reading rules, reading hours and explicit save/autosave: only 0008-settings-ephemeral-defaults-safe.md mentions excludePatterns (that it persists), which this keeps; no conflict. docs/reference/secondary-views-behavior.md described explicit Save as an intermediate slice and is updated here. Private records were not read, per the builder brief. CoreOperationCatalogTest pinned the typed confirmation 'so it isn't accidentally weakened'; the repair guidance asks the operation's declared consent to agree with the in-place ask, and the HIGH-risk trust gate (consent capsule) is unchanged.",
      "assertionChanges": [
        {
          "oldAssertion": "applyExcludesHasHighRiskTypedConfirm: confirm is ConfirmStrategy.Typed with key ops.apply-excludes.confirm.",
          "newAssertion": "applyExcludesIsHighRiskWithItsInPlaceAskBoundToTheAskedList: risk HIGH, confirm ConfirmStrategy.Inline, inputs contain \"rules\", executors {UI}.",
          "reason": "Docs and the declared consent agree with the in-place ask; consent is bound to the asked list (R6), and the HIGH risk tier still requires the consent capsule."
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/handlers/EngineRefusalHandlersTest.java"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Library. The headline says the state, the folder row says how many and how current; nothing is said twice. Skip rules and reading hours save as you change them: no Save / Reload / Preview / Apply row; adding a rule that removes files asks in place.",
          "location": "task t-20261113-fmimpl agreement, picture note fm-07"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "B2': the ask is about the whole saved list because core.apply-excludes applies all effective rules: \"Remove files already read that match your skip rules from search? Files on disk are not touched.\" If the saved list changes while it is open (this or another window) it is withdrawn and asked again for the new list; one ask at a time; it does not take focus (announced politely). B3': save on leaving the field only (no idle timer); leaving Library or switching tabs leaves the field and saves; an unknown outcome keeps the same key and bytes, reconciles before any newer draft and shows Not sure this saved with Try again; a load failure says Couldn't load your skip rules with Try again and the editor is not editable until loaded. B4': per-rule Preview counts are behind developer tools (Count matching files); the removal outcome is plain: done, partly done (offline or missing folders, unreadable files, failed deletions) with Try again, or not sure with Try again; never complete from a success receipt alone; files no longer on disk are not checked (stated). B5': Undo only for the last skip-rule glob change while no other settings save happened since; it restores the rule list only and says removed files come back when read again; main's exact-path Undo is removed, exact-path authority unchanged. B6': each core.set-indexing-rules write carries the settings revision its draft was based on (optional witness argument, frozen as the prepared witness); a save elsewhere since is VERSION_CONFLICT; Library shows Changed elsewhere with Use the saved rules / Keep my draft. A refusal whose re-read shows the rules unchanged since the draft's base is rebased (unrelated settings saved elsewhere are not a conflict). S6b-12': an override keeps today's behaviour: editable and saved; the note explains the saved list takes effect when the override is removed; nothing is asked (the saved list removes nothing while overridden). Added scenarios: first run writes nothing on open; deleting the last rule saves an empty list; 512-entry capacity refusal keeps the draft and other rules; exact-path entries keep ids, scope and bytes across glob saves; battery-only first change keeps a null schedule; turning hours off keeps days/times; manual pause intent kept; skip-rule save, hours save and toolbar pause/resume overlap without stale witness or lost draft; a file missing on disk stays searchable after removal (stated). Review repairs (R6-R10): R6 core.apply-excludes takes the saved list the ask was about (optional rules) and refuses with EXCLUDES_CHANGED, removing nothing, when the rules in effect differ at apply time (another window, an override); the page withdraws the ask and asks again for the list now saved; callers without rules keep the earlier behaviour under the HIGH-risk consent capsule; the operation declares an Inline confirmation (the in-place ask), no typed word. R7 the bytes saved are the draft captured when the field was left; queued saves and B11 re-sends send that snapshot, never the live text. R8 reading hours follow B3's unknown-outcome rule: same key and bytes kept, Not sure this saved with Try again, settled before any newer change is judged or sent. R9 reading-hours saves already requested finish after leaving Library. R10 removal words say only what the result establishes: no number of files from deletion-request counts.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Every handler still rethrows a typed Engine refusal unchanged; the apply case now reaches the service through the consent-bound path with valid arguments.",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for skip rules, exclude patterns, apply-excludes, Save patterns, indexing/reading rules, reading hours and explicit save/autosave: only 0008-settings-ephemeral-defaults-safe.md mentions excludePatterns (that it persists), which this keeps; no conflict. docs/reference/secondary-views-behavior.md described explicit Save as an intermediate slice and is updated here. Private records were not read, per the builder brief.",
      "assertionChanges": [
        {
          "oldAssertion": "apply cases: ApplyExcludesHandler gets {path, pathHash, name} (ignored) and rethrows the refusal.",
          "newAssertion": "apply cases: ApplyExcludesHandler gets {rules: ['*.log']} and rethrows the same refusal (assertSame unchanged).",
          "reason": "R6: core.apply-excludes now accepts only its consented list (additionalProperties false), so unrelated fields are BAD_REQUEST before the service; the refusal oracle is unchanged."
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/excludes/ExcludesConsentTest.java"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "repaired": true,
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Library. The headline says the state, the folder row says how many and how current; nothing is said twice. Skip rules and reading hours save as you change them: no Save / Reload / Preview / Apply row; adding a rule that removes files asks in place.",
          "location": "task t-20261113-fmimpl agreement, picture note fm-07"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "B2': the ask is about the whole saved list because core.apply-excludes applies all effective rules: \"Remove files already read that match your skip rules from search? Files on disk are not touched.\" If the saved list changes while it is open (this or another window) it is withdrawn and asked again for the new list; one ask at a time; it does not take focus (announced politely). B3': save on leaving the field only (no idle timer); leaving Library or switching tabs leaves the field and saves; an unknown outcome keeps the same key and bytes, reconciles before any newer draft and shows Not sure this saved with Try again; a load failure says Couldn't load your skip rules with Try again and the editor is not editable until loaded. B4': per-rule Preview counts are behind developer tools (Count matching files); the removal outcome is plain: done, partly done (offline or missing folders, unreadable files, failed deletions) with Try again, or not sure with Try again; never complete from a success receipt alone; files no longer on disk are not checked (stated). B5': Undo only for the last skip-rule glob change while no other settings save happened since; it restores the rule list only and says removed files come back when read again; main's exact-path Undo is removed, exact-path authority unchanged. B6': each core.set-indexing-rules write carries the settings revision its draft was based on (optional witness argument, frozen as the prepared witness); a save elsewhere since is VERSION_CONFLICT; Library shows Changed elsewhere with Use the saved rules / Keep my draft. A refusal whose re-read shows the rules unchanged since the draft's base is rebased (unrelated settings saved elsewhere are not a conflict). S6b-12': an override keeps today's behaviour: editable and saved; the note explains the saved list takes effect when the override is removed; nothing is asked (the saved list removes nothing while overridden). Added scenarios: first run writes nothing on open; deleting the last rule saves an empty list; 512-entry capacity refusal keeps the draft and other rules; exact-path entries keep ids, scope and bytes across glob saves; battery-only first change keeps a null schedule; turning hours off keeps days/times; manual pause intent kept; skip-rule save, hours save and toolbar pause/resume overlap without stale witness or lost draft; a file missing on disk stays searchable after removal (stated). Review repairs (R6-R10): R6 core.apply-excludes takes the saved list the ask was about (optional rules) and refuses with EXCLUDES_CHANGED, removing nothing, when the rules in effect differ at apply time (another window, an override); the page withdraws the ask and asks again for the list now saved; callers without rules keep the earlier behaviour under the HIGH-risk consent capsule; the operation declares an Inline confirmation (the in-place ask), no typed word. R7 the bytes saved are the draft captured when the field was left; queued saves and B11 re-sends send that snapshot, never the live text. R8 reading hours follow B3's unknown-outcome rule: same key and bytes kept, Not sure this saved with Try again, settled before any newer change is judged or sent. R9 reading-hours saves already requested finish after leaving Library. R10 removal words say only what the result establishes: no number of files from deletion-request counts.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Six cases over ApplyExcludesHandler with the real ExcludesServiceImpl and a published ConfigStore: the asked-about list removes exactly its matches; a list saved elsewhere after the ask ('*' added) is EXCLUDES_CHANGED with no deletion call; an env override in effect refuses the saved list; exact-path entries are part of the list (another id or a missing entry refuses; the same list applies); malformed rules are BAD_REQUEST before any deletion; a caller without rules keeps applying the rules in effect. On dfdd5e42ea the four binding cases fail by assertion (the removal succeeded under another list) and the two preservation cases pass.",
      "evidence": "gates/test-intent/evidence/fm-library-6b2.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/excludes/ExcludesConsentTest.java"
      ]
    }
  ]
}
```

## Execution

Evidence `gates/test-intent/evidence/fm-library-6b2.json` was written by
`run-evidence.mjs --before origin/main` (dea8a9f97b) with one `--check` per new-check file.
Candidate: `IndexingRulesIntentTest` 5/5, `ExcludesConsentTest` 6/6, `slice-6b2.test.ts` 48/48 and
`slice-6b2-lockdown.test.ts` 7/7 passed; before-state classification `assertion` for all four.

Review repairs (R6-R10) were also checked against the committed slice dfdd5e42ea in a shared clone:
the seven repair cases in `slice-6b2.test.ts` fail there by assertion (R6 `{}` sent instead of the
asked list; R7 `['*']` saved instead of the left draft, twice; R8 no Not sure this saved / no Try
again; R9 only the first value saved; R10 "1 file removed from search" for one folder request), and
`ExcludesConsentTest`'s four binding cases fail by assertion (the removal succeeded under another
list) while its two preservation cases pass.

Second review repairs (R11, R12) were checked against 67e5f1400c (the unchanged worktree head before
the redesign): the nine new cases in `slice-6b2.test.ts` fail there by assertion (reading hours: the
queued "off" replaced by the older saved "on" on return; 10:00 left but 11:00 sent; skip rules: B
never sent after A was reconciled, twice; three snapshots coalesced to A, C; no "not saved yet" on
return; Undo sent `['*.log']` over the uncertain B; the draft sent before the uncertain Undo was
settled; removal: the newer ask cleared by the older result) and the other 39 pass.

The J4 release journey change (`scripts/release/qual/journeys/linux.py`) is checked by
`scripts/release/qual/journeys/test_current_ui.py` (python unittest, outside the evidence runners):
on the base journey the modified H5 case and the new J4 cases fail (the journey still clicks Save
patterns, never answers Keep them and never authors reading hours); with the change all 13 pass.
J4 now also authors reading hours (Only between, then Any time, kept turned off) and requires them
after restart; `test_redesign.py` and `test_windows.py` restart fixtures carry that authored state.
The scripts are out of the gate's scope by design.
