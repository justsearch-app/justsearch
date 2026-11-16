---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: justsearch-fm-s6c
---

# Slice 6c test intent

"Read waiting files now" under "When to read files" (owner decision Q2). The new behaviour is
covered by isolated new test files (exempt) and by the existing files listed here: the catalog id
list, the recaptured operation-wire golden, the regenerated status fixture, the narrowed 6b1-7 and
the dead-code pin. Evidence: gates/test-intent/evidence/fm-library-6c.json (candidate and before-state 30f367f53c, one check per file).

Review repairs RV6c-1..5 (reviewer-s6c on 0a1023dc2a, accepted by the orchestrator) add cases to the
same new files only: a claim of file jobs under a release is serialized with revocation, so Pause,
recovery, a rule edit and battery starting wait for a claim in progress and no claim starts after
they complete, while status and the other lanes keep reading the hold meanwhile (RV6c-1); a release ends
with its hold episode, also when nothing observed the hold while reading hours were open, and never
revives (RV6c-2); the shared pause derivation drops the released reading-rule reasons, so schedule
or battery with Game Mode, energy saver or external GPU pressure is never "Reading paused" while
released (RV6c-3); "Nothing is waiting to be read." clears on the next fresh observation, which
offers the action again when files wait (RV6c-4); the real Engine keeps a new skip rule, a removed
folder, a cancelled import and a deleted file in effect while a release reads, and keeps content
indexed before (RV6c-5; this case also passes on 867670406a and is preservation evidence there).

Review repairs RV6c-6, RV6c-8 and RV6c-9 (reviewer-s6c-2 on 39e4d66875, accepted by the orchestrator;
RV6c-7 declined) add cases to the same new files only: a release records the next reading-hours
boundary it does not cover, also when it covers only the battery, and ends there even when nothing
observed the crossing, so a battery release at 08:58 under daily hours 09:00-08:59 admits nothing at
09:01 (RV6c-6); the line "Reading the waiting files" is reconciled against the first status observation
newer than the answer, so a release that ended before any active sample ends with that observation,
after a drain or Pause, while reprojections of the older status keep it (RV6c-8); "Nothing is waiting
to be read." stays through a reprojection of the same status and goes with a new observation, which
offers the action again when files wait (RV6c-9). Post-claim fences under a release (RV6c-7) run on the
unchanged normal ingestion path and are not re-proven under a release here.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/CoreOperationCatalogTest.java"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "New",
      "behaviour": "The core catalog declares core.indexing-read-now (\"Read waiting files now\") once; every other id is unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "\"Read waiting files now\" = text action under \"When to read files\", shown only while a reading-hours or battery rule holds files back; no disabled \"Nothing is waiting\".",
          "location": "task t-20261113-fmimpl agreement, owner decisions 2026-11-15, Q2"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "C1' one Engine-owned, in-memory release that lets only the indexing loop's admission claim ingestion jobs (initial reads, rescans, watcher changes) despite reading-hours and battery holds; IndexingHold.held()/acquire()/tryAcquire() and isIndexingHeld() stay unchanged, so enrichment, picture reading (VDU) and GPL stay held; steps inside normal per-file ingestion (migration inline batch embedding included) run as in reading hours; files arriving during the release are read too. It ends at the first ingestion poll under it that claims nothing (a bounded scan of refused rows included), on manual Pause, on SETTINGS_RECOVERY, on an accepted change of the reading-rule fields (unchanged or unrelated saves do not end it), when a rule reason not active at its start begins to hold, and on Engine restart; revocation stops further admission and an admitted batch finishes as with Pause; install/join/revoke serialize with lease acquisition. No saved state. C2' a new operation core.indexing-read-now through the existing catalog/invoke route, MEDIUM risk, no confirmation, metadata audit; outcome started | joined | nothing-waiting | not-held (reason paused, recovery or not-held); it revalidates at install; a keyed replay never reinstalls; the receipt says started, not files read. C3' operating.indexing gains release {active}, readingRuleReasons (schedule, on_battery apart from bulk-work reasons) and heldFilesWaiting from the existing ready-job count; while released the shared projection is not \"Reading paused\"; heavy-indexing reporting counts a released backlog as progress. C4' Library shows the text action under \"When to read files\" only while fresh status says a reading rule holds ready files and no manual Pause or recovery holds and no release is active; while active: \"Reading the waiting files. Your reading rules are unchanged.\"; when it ends the line goes; nothing-waiting says \"Nothing is waiting to be read.\" once in place; not-held refreshes status; failure uses the existing reading-request words; focus moves to the line that replaces the action, or to the \"When to read files\" heading when the action disappears while focused.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-library-6c.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/CoreOperationCatalogTest.java"
      ],
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for waiting files, read now, indexing hold, indexing pause, reading hours, pause on battery and schedule: 0026, 0038, 0039 and 0048 use \"schedule\" for CI or sampling only; 0050 (GPU co-residence) governs bulk-work holds, which this keeps (its reasons still pace enrichment and are listed apart). No conflict. Private records were not read, per the builder brief."
    },
    {
      "items": [
        "modules/app-services/src/test/resources/registry/emitter/ui-operation-wire.golden.json"
      ],
      "change": "modified",
      "kind": "test-data",
      "class": "New",
      "behaviour": "The emitted operation wire gains core.indexing-read-now: MEDIUM risk, no confirmation, metadata-only audit, UI executor only, a strict empty argument object, and an output naming outcome (started, joined, nothing-waiting, not-held) and reason (paused, recovery, not-held). The golden was recaptured with the test's own capture path (delete and re-run); no other entry changed.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "\"Read waiting files now\" = text action under \"When to read files\", shown only while a reading-hours or battery rule holds files back; no disabled \"Nothing is waiting\".",
          "location": "task t-20261113-fmimpl agreement, owner decisions 2026-11-15, Q2"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "C1' one Engine-owned, in-memory release that lets only the indexing loop's admission claim ingestion jobs (initial reads, rescans, watcher changes) despite reading-hours and battery holds; IndexingHold.held()/acquire()/tryAcquire() and isIndexingHeld() stay unchanged, so enrichment, picture reading (VDU) and GPL stay held; steps inside normal per-file ingestion (migration inline batch embedding included) run as in reading hours; files arriving during the release are read too. It ends at the first ingestion poll under it that claims nothing (a bounded scan of refused rows included), on manual Pause, on SETTINGS_RECOVERY, on an accepted change of the reading-rule fields (unchanged or unrelated saves do not end it), when a rule reason not active at its start begins to hold, and on Engine restart; revocation stops further admission and an admitted batch finishes as with Pause; install/join/revoke serialize with lease acquisition. No saved state. C2' a new operation core.indexing-read-now through the existing catalog/invoke route, MEDIUM risk, no confirmation, metadata audit; outcome started | joined | nothing-waiting | not-held (reason paused, recovery or not-held); it revalidates at install; a keyed replay never reinstalls; the receipt says started, not files read. C3' operating.indexing gains release {active}, readingRuleReasons (schedule, on_battery apart from bulk-work reasons) and heldFilesWaiting from the existing ready-job count; while released the shared projection is not \"Reading paused\"; heavy-indexing reporting counts a released backlog as progress. C4' Library shows the text action under \"When to read files\" only while fresh status says a reading rule holds ready files and no manual Pause or recovery holds and no release is active; while active: \"Reading the waiting files. Your reading rules are unchanged.\"; when it ends the line goes; nothing-waiting says \"Nothing is waiting to be read.\" once in place; not-held refreshes status; failure uses the existing reading-request words; focus moves to the line that replaces the action, or to the \"When to read files\" heading when the action disappears while focused.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-library-6c.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/emitter/UIOperationViewConformanceTest.java"
      ],
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for waiting files, read now, indexing hold, indexing pause, reading hours, pause on battery and schedule: 0026, 0038, 0039 and 0048 use \"schedule\" for CI or sampling only; 0050 (GPU co-residence) governs bulk-work holds, which this keeps (its reasons still pace enrichment and are listed apart). No conflict. Private records were not read, per the builder brief."
    },
    {
      "items": [
        "modules/ui-web/src/api/__fixtures__/status-response-live.json"
      ],
      "change": "modified",
      "kind": "test-data",
      "class": "New",
      "behaviour": "The cross-language StatusResponse fixture carries operating.indexing.readingRuleReasons ([]), heldFilesWaiting (false) and release.active (false) as the Java serialization now does; regenerated by the app-api updateSchemas task, nothing else in it changed.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "\"Read waiting files now\" = text action under \"When to read files\", shown only while a reading-hours or battery rule holds files back; no disabled \"Nothing is waiting\".",
          "location": "task t-20261113-fmimpl agreement, owner decisions 2026-11-15, Q2"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "C1' one Engine-owned, in-memory release that lets only the indexing loop's admission claim ingestion jobs (initial reads, rescans, watcher changes) despite reading-hours and battery holds; IndexingHold.held()/acquire()/tryAcquire() and isIndexingHeld() stay unchanged, so enrichment, picture reading (VDU) and GPL stay held; steps inside normal per-file ingestion (migration inline batch embedding included) run as in reading hours; files arriving during the release are read too. It ends at the first ingestion poll under it that claims nothing (a bounded scan of refused rows included), on manual Pause, on SETTINGS_RECOVERY, on an accepted change of the reading-rule fields (unchanged or unrelated saves do not end it), when a rule reason not active at its start begins to hold, and on Engine restart; revocation stops further admission and an admitted batch finishes as with Pause; install/join/revoke serialize with lease acquisition. No saved state. C2' a new operation core.indexing-read-now through the existing catalog/invoke route, MEDIUM risk, no confirmation, metadata audit; outcome started | joined | nothing-waiting | not-held (reason paused, recovery or not-held); it revalidates at install; a keyed replay never reinstalls; the receipt says started, not files read. C3' operating.indexing gains release {active}, readingRuleReasons (schedule, on_battery apart from bulk-work reasons) and heldFilesWaiting from the existing ready-job count; while released the shared projection is not \"Reading paused\"; heavy-indexing reporting counts a released backlog as progress. C4' Library shows the text action under \"When to read files\" only while fresh status says a reading rule holds ready files and no manual Pause or recovery holds and no release is active; while active: \"Reading the waiting files. Your reading rules are unchanged.\"; when it ends the line goes; nothing-waiting says \"Nothing is waiting to be read.\" once in place; not-held refreshes status; failure uses the existing reading-request words; focus moves to the line that replaces the action, or to the \"When to read files\" heading when the action disappears while focused.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-library-6c.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/status/StatusRecordSchemaTest.java"
      ],
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for waiting files, read now, indexing hold, indexing pause, reading hours, pause on battery and schedule: 0026, 0038, 0039 and 0048 use \"schedule\" for CI or sampling only; 0050 (GPU co-residence) governs bulk-work holds, which this keeps (its reasons still pace enrichment and are listed apart). No conflict. Private records were not read, per the builder brief."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/library-first-minutes/slice-6b1.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "\"Read waiting files now\" = text action under \"When to read files\", shown only while a reading-hours or battery rule holds files back; no disabled \"Nothing is waiting\".",
          "location": "task t-20261113-fmimpl agreement, owner decisions 2026-11-15, Q2"
        }
      ],
      "behaviour": "6b1-7 keeps its scope (no More menu, no waiting-work action in a Library where nothing is held) and no longer reads as \"Library never shows the words\": slice 6c shows them under \"When to read files\" while a reading rule holds files back.",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for waiting files, read now, indexing hold, indexing pause, reading hours, pause on battery and schedule: 0026, 0038, 0039 and 0048 use \"schedule\" for CI or sampling only; 0050 (GPU co-residence) governs bulk-work holds, which this keeps (its reasons still pace enrichment and are listed apart). No conflict. Private records were not read, per the builder brief.",
      "assertionChanges": [
        {
          "oldAssertion": "6b1-7 'Library has no misleading waiting-work action': after opening a More menu if one exists, Library's text does not contain 'Read waiting files now'.",
          "newAssertion": "6b1-7 'with nothing held, Library has no waiting-work action and no menu offers one': the same status (nothing held) and the same text check, plus the reading-rules editor's own text does not contain the words and no jf-menu exists.",
          "reason": "Owner decision Q2 adds the action under \"When to read files\" while a rule holds files; the old title stated an unconditional absence. The assertion is kept for its scope and made stricter (it also looks inside the editor). Old and narrowed versions both pass on the base and on the candidate."
        }
      ]
    },
    {
      "items": [
        "gates/dead-code/baseline.txt"
      ],
      "change": "modified",
      "kind": "baseline",
      "class": "Structural rule kept",
      "rule": "The dead-code ratchet pins unused exports per file; a pin may rise only with a declared changeset under gates/dead-code/.changesets.",
      "whySurvives": "Only src/api/generated/schema-types/status-response.ts moves (9 to 11), licensed by gates/dead-code/.changesets/fm-library-6c-indexing-reason.md: the generator now emits the shared IndexingReason enum as a named type and Zod const because readingRuleReasons reuses the reasons vocabulary. No other pin changed; the predicate and every other pin are unchanged, and the gate passes with the declaration.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "C1' one Engine-owned, in-memory release that lets only the indexing loop's admission claim ingestion jobs (initial reads, rescans, watcher changes) despite reading-hours and battery holds; IndexingHold.held()/acquire()/tryAcquire() and isIndexingHeld() stay unchanged, so enrichment, picture reading (VDU) and GPL stay held; steps inside normal per-file ingestion (migration inline batch embedding included) run as in reading hours; files arriving during the release are read too. It ends at the first ingestion poll under it that claims nothing (a bounded scan of refused rows included), on manual Pause, on SETTINGS_RECOVERY, on an accepted change of the reading-rule fields (unchanged or unrelated saves do not end it), when a rule reason not active at its start begins to hold, and on Engine restart; revocation stops further admission and an admitted batch finishes as with Pause; install/join/revoke serialize with lease acquisition. No saved state. C2' a new operation core.indexing-read-now through the existing catalog/invoke route, MEDIUM risk, no confirmation, metadata audit; outcome started | joined | nothing-waiting | not-held (reason paused, recovery or not-held); it revalidates at install; a keyed replay never reinstalls; the receipt says started, not files read. C3' operating.indexing gains release {active}, readingRuleReasons (schedule, on_battery apart from bulk-work reasons) and heldFilesWaiting from the existing ready-job count; while released the shared projection is not \"Reading paused\"; heavy-indexing reporting counts a released backlog as progress. C4' Library shows the text action under \"When to read files\" only while fresh status says a reading rule holds ready files and no manual Pause or recovery holds and no release is active; while active: \"Reading the waiting files. Your reading rules are unchanged.\"; when it ends the line goes; nothing-waiting says \"Nothing is waiting to be read.\" once in place; not-held refreshes status; failure uses the existing reading-request words; focus moves to the line that replaces the action, or to the \"When to read files\" heading when the action disappears while focused.",
          "label": "agent choice"
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
  "session": "verifier-40mjv8ei",
  "verdict": "accept",
  "digest": "sha256:a455234555c4bc0ca75f139db3821dad97b6646637b1ba23349d497968983866"
}
```
