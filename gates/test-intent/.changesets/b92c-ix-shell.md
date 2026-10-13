---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b92c-ix-shell
---

Assigned findings: R04-lifecycle 3, 4, 6, 9, 10; R05-state 7; R12-design 3, 4; R09-security 1;
deny takeover and supervisor fixture teardown. Starting revision 61ff9b955. The owner delegation is
quoted from the supplied owner-turns file; every specific expectation is an agent choice made under
it, not an owner quotation.

Node checks under scripts/ are outside this gate's scope. The accompanying script edits make deny
refuse a foreign stale, dead or unreadable run before any stop (an own run and explicit stop, clean
or reload keep their earlier disposition), append the intent before shutdown or forced kill,
accept runner-job exit zero only with runner identity and gone proof plus a matching intent,
report disjoint historical retention loss separately, and keep fixture artifacts until owned
fixture exits are verified. Earlier deny-reclaim expectations in the dev scripts change under that
choice; their positive reclaim checks now use an explicit warn takeover. R04-lifecycle 7 is not
changed. Raw before and after logs are in F:/agent-sandbox/design-b6d536/codex/b92c-ix-shell/.
Independent acceptance is left to the verifier.

Review repairs (starting revision b366c0500; 61ff9b955 stays the start of the whole change): a
loss row whose evidence range is unknown, including a summary written before scope fields
existed, is relevant only to runs that started before its eviction time (the row's at), in the
census; Java, Node and Rust compaction keep the newest eviction time there and drop stale bounds.
The conformance fixture preload records PID plus a held-at time without starting a process; the
teardown resolves the birth from one identity query and records it before any stop, and its
orderly stop now speaks for the run's own session. Off Windows a failed orderly stop falls back
to the runner's own child handle. Explicit warn takeovers of dead, legacy and expired ownership
are still logged as stale-reclaim interference. Node checks for these are outside this gate's
scope; their before and after logs are in the run folder.

```json
{
  "entries": [
    {
      "items": [
        "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "An updater handoff waits for the actor's persisted exit acknowledgement after the Engine is reaped (R04-lifecycle 4). A crash before the upgrade command is consumed, under an active upgrade with the replacement held, completes the handoff instead of stranding it, and is still recorded as one unexpected exit (R04-lifecycle 3).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md)",
          "delegationScope": "Owner item 6: process ownership and exit accounting; builder run b92c-ix-shell assigned findings.",
          "label": "agent choice",
          "choice": "Separate 'never admitted' from 'reaped but not yet accounted'; accept NoEngine only for an active upgrade under replacement hold with an acknowledged exit. Keep the existing containment barrier and crash classification."
        }
      ],
      "evidence": "gates/test-intent/evidence/b92c-ix-shell-rust.json",
      "newChecks": [
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::prepared_upgrade_waits_for_actor_exit_accounting",
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::upgrade_stop_accepts_crash_before_command_consumption"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/exit_ledger.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Installed-shell rotation keeps earlier incarnations in numbered archives up to the shared 128 MiB retention bound, a failed rotation keeps the previous archive, and an archive evicted at the bound first gets loss receipts that keep its session and evidence time; an unreadable evicted line leaves the summary's scope incomplete (R05-state 7, R12-design 4, R04-lifecycle 10).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md)",
          "delegationScope": "Owner item 6: process ownership and exit accounting; builder run b92c-ix-shell assigned findings.",
          "label": "agent choice",
          "choice": "Align the Rust writer with the Java and Node retention: per-file archives, loss receipts written before eviction, conservative (incomplete) scope for unreadable evicted lines."
        }
      ],
      "evidence": "gates/test-intent/evidence/b92c-ix-shell-rust.json",
      "newChecks": [
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::all_five_incarnations_survive_per_file_rotation",
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::failed_rotation_preserves_previous_archive",
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::eviction_at_the_retention_bound_records_scoped_loss_first"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/exit_ledger.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "When loss-journal compaction merges rows whose evidence range is unknown (such as summaries written before scope fields existed), the compacted row keeps no evidenceFrom or evidenceThrough left over from the newest eviction, and its at is the newest eviction time, the upper bound a reader uses for an unknown range (review findings L4 and M1; starting revision b366c0500).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md)",
          "delegationScope": "Owner item 6: process ownership and exit accounting; builder run b92c-ix-shell assigned findings and the coordinator's review repairs.",
          "label": "agent choice",
          "choice": "The eviction time bounds everything evicted, so an unknown range is relevant only to runs that started before it; a merged range that is unknown keeps no bound, as the Java compactor already does."
        }
      ],
      "evidence": "gates/test-intent/evidence/b92c-ix-shell-r2-rust.json",
      "newChecks": [
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::compacted_unknown_range_drops_stale_bounds_and_keeps_eviction_time"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/PredecessorChildStopTest.java"
      ],
      "class": "New",
      "behaviour": "The boot stop of a manifest-only predecessor refuses a held process whose native birth differs, whose birth is unreadable, or whose executable differs, before any stop (R09-security 1). An UNKNOWN owner-death record does not block a later identity-proven cleanup (R12-design 3).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md)",
          "delegationScope": "Owner item 6: process ownership and exit accounting; builder run b92c-ix-shell assigned findings.",
          "label": "agent choice",
          "choice": "Authorize a legacy-manifest stop only from the held native birth and executable; treat an UNKNOWN owner-death observation as inconclusive while terminal receipts still prevent a duplicate stop."
        }
      ],
      "evidence": "gates/test-intent/evidence/b92c-ix-shell-java.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/PredecessorChildStopTest.java#manifestOnlyFallbackRejectsDifferentHeldBirth",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/PredecessorChildStopTest.java#manifestOnlyFallbackRejectsUnreadableHeldBirth",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/PredecessorChildStopTest.java#manifestOnlyFallbackRejectsDifferentHeldExecutable",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/PredecessorChildStopTest.java#unknownOwnerDeathDoesNotDisableIdentityProvenCleanup"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/PredecessorChildStopTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation: a held process whose native birth and executable match is still stopped (on Windows through the real native query with a recording-only terminator), and the JDK birth-checked fallback stays eligible.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md)",
          "delegationScope": "Owner item 6: process ownership and exit accounting; builder run b92c-ix-shell assigned findings.",
          "label": "agent choice",
          "choice": "Keep valid predecessor cleanup and the non-native fallback while refusing unverifiable processes."
        }
      ],
      "evidence": "gates/test-intent/evidence/b92c-ix-shell-java.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/PredecessorChildStopTest.java#manifestOnlyMatchingHeldIdentityRecordsStop",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/PredecessorChildStopTest.java#jdkBirthCheckedFallbackRemainsEligible"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/LedgerRetentionScopeTest.java"
      ],
      "class": "New",
      "behaviour": "Java retention eviction and loss-journal compaction keep the original affected sessions and evidence interval, so a later run can tell historical loss from its own; an unreadable evicted line leaves the scope incomplete (R04-lifecycle 10).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md)",
          "delegationScope": "Owner item 6: process ownership and exit accounting; builder run b92c-ix-shell assigned findings.",
          "label": "agent choice",
          "choice": "Record bounded session scope and original time bounds on loss rows and keep them through compaction; unknown scope stays conservative in the census."
        }
      ],
      "evidence": "gates/test-intent/evidence/b92c-ix-shell-java.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/LedgerRetentionScopeTest.java#retentionLossKeepsOriginalSessionAndEvidenceRange",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/LedgerRetentionScopeTest.java#compactedLossKeepsOriginalSessionAndEvidenceRange",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/LedgerRetentionScopeTest.java#unreadableEvictedLineLeavesLossScopeIncomplete"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/LedgerRetentionScopeTest.java"
      ],
      "class": "New",
      "behaviour": "Java loss-journal compaction over rows whose evidence range is unknown keeps no evidence bound and records the newest eviction time as the compacted row's at, not the oldest row's time (review finding M1; starting revision b366c0500).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md)",
          "delegationScope": "Owner item 6: process ownership and exit accounting; builder run b92c-ix-shell assigned findings and the coordinator's review repairs.",
          "label": "agent choice",
          "choice": "The eviction time bounds everything evicted, so the compacted row's at must be the newest eviction; Node and Rust already write it so, and the census reads it when the range is unknown."
        }
      ],
      "evidence": "gates/test-intent/evidence/b92c-ix-shell-r2-java.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/LedgerRetentionScopeTest.java#compactedUnknownRangeKeepsNewestEvictionTime"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records
