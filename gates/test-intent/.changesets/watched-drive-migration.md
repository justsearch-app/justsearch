---
schema: test-intent.v1
task: drive-migration
author-role: builder
author-session: codex-drive-migration-builder-20261024
---

# Watched-drive migration regression

These expectations implement the orchestrator's settled D1-D9/A1 brief under delegated autonomy:
successful promotion, current content, exact acknowledgement, readable fenced status and automatic
retry. Replace the disabled reproduction's refusal expectation with that outcome; keep its real
loop mounts, identity checks and owned cleanup.
The separate watcher-admission case preserves the existing final-fence handoff contract.
Default ownership uses the index policy's null representation and the live watcher's `default`
name interchangeably; named collections must still match. Replay must preserve a later admission's
exact revision, and its projection-verification refusal must appear in status.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/LinuxWatchedDriveReturnIntegrationTest.java"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Defect pin",
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no data loss",
        "reproduction": "A real loop-backed watched drive leaves at a held migration barrier; its retained reconciliation debt and vanished-file UPSERT prevent a writable promoted runtime."
      },
      "reproduction": "returnDuringBootMigrationPromotes holds migration-before-switching or migration-green-drained, unmounts the owned loop filesystem, deletes a source elsewhere, returns the same root, and attempts promotion with and without restart.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive migration repair and its documentation; this checkpoint diagnoses D1 teardown without a speculative replay rewrite.",
          "choice": "canonicalise default ownership consistently in reconciliation's field comparison, the queue comparison and admission; keep exact-revision verification; and make a verification refusal set the visible refusal/fence status instead of only logging (D7).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive migration repair and its documentation; this checkpoint diagnoses D1 teardown without a speculative replay rewrite.",
          "choice": "D1: The drive leaves during a held cutover; a file under it is deleted while away; it returns with the same root identity. After cutover (with and without an Engine restart in between), the migration promotes; one writable runtime serves search and ingest; the deleted file's document is gone; every other document is present with its label.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive migration repair and its documentation; this checkpoint diagnoses D1 teardown without a speculative replay rewrite.",
          "choice": "D2: While away, without any companion DELETE: a file is recreated at the same path with new content; a file is modified (including a change that keeps size and mtime). After promotion, the index holds the current content; nothing current is removed.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive migration repair and its documentation; this checkpoint diagnoses D1 teardown without a speculative replay rewrite.",
          "choice": "D5: Concurrency: (a) admission blocked by the final fence during replay is applied after promotion; (b) an exact-revision receipt replaced during unfenced settlement wins over the older debt.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive migration repair and its documentation; this checkpoint diagnoses D1 teardown without a speculative replay rewrite.",
          "choice": "D7: When refusing, the Engine keeps a readable index, reports a visible fenced status naming the reason, and retries automatically when the drive and evidence return; existing operator recovery is unchanged. Indefinite refusal with evidence available is a failure.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive migration repair and its documentation; this checkpoint diagnoses D1 teardown without a speculative replay rewrite.",
          "choice": "D8: The integration cases skip (never pass) when loop/udisks prerequisites are missing, and leave no loop device or mount behind.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive migration repair and its documentation; this checkpoint diagnoses D1 teardown without a speculative replay rewrite.",
          "choice": "A1: when an incarnation ends because its root became unavailable (its volume left), record that as uncertainty scoped to that root, which only the fresh reconciliation preflight of that root (under the final fence, with the evidence rules above) can clear.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/watched-drive-migration.json",
      "newChecks": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/LinuxWatchedDriveReturnIntegrationTest.java#returnDuringBootMigrationPromotes"
      ]
    }
  ]
}
```

## Acceptance records
