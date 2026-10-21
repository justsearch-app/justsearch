---
schema: test-intent.v1
task: t-20261019-b81d05
author-role: builder
author-session: builder-index-deletes-v25-merge-20261021
---

# This branch's jobs.db migration follows main's V24 as V25

The builder does not supply acceptance. Main's #841 took jobs.db V24 for transactional job counts
and claim/readiness indexes. This branch's migration (versioned DELETE payloads and watcher
reconciliation receipts, no table layout change) now runs after it as V25, so main's version
number and meaning are kept. Two of main's checks pinned the reached version to 24; they now pin
25. Every other assertion in them (saved fields, exact counts, admission fence, rollback, claim
order, older-binary refusal) is unchanged.

```json
{
  "entries": [{
    "items": [
      "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobCountsMigrationTest.java",
      "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueScaleV24MigrationEvidenceTest.java"
    ],
    "class": "Obsolete",
    "sources": [
      {
        "kind": "owner-adopted-scenario",
        "task": "t-20261019-b81d05",
        "scenario": "I10",
        "quote": "Populated databases from older versions upgrade with order, generation, payload readers and rollback or retry preserved. There is a fixture for every new persisted format (JobQueueMigrationTest, exact-replay cleanup tests).",
        "label": "agent-drafted, owner-adopted"
      },
      {
        "kind": "owner-adopted-scenario",
        "task": "t-20261019-b81d05",
        "scenario": "I7",
        "quote": "Explicit (user or API) deletes keep their semantics.",
        "label": "agent-drafted, owner-adopted"
      }
    ],
    "conflictSearch": "decision records searched for a conflict: none. Main's V24 keeps its number, objects and migration step; this branch's step is appended as V25.",
    "oldToNew": [
      {
        "test": "JobCountsMigrationTest.everySupportedVersionMigratesToV24WithoutChangingSavedRows and unreleasedQueueV23GainsMainAdmissionFenceWithoutLosingCounts",
        "old": "assertEquals(24,version(queue)) after open and reopen",
        "new": "assertEquals(25,version(queue)); saved fields, exact counts and admission counts unchanged. The injected V24 failure and its rollback check are unchanged."
      },
      {
        "test": "QueueScaleV24MigrationEvidenceTest.v13AndMainV23MigrateToV24WithCrashReopenAndOlderBinaryRefusal",
        "old": "assertEquals(24, version(conn)); main V23 refusal text names schema version 24",
        "new": "assertEquals(25, version(conn)); refusal text names schema version 25. The crash at V24, rollback, counts, claim order and saved-field digest are unchanged."
      }
    ]
  }]
}
```
