---
schema: test-intent.v1
task: drive-migration
author-role: builder
author-session: codex-drive-migration-builder-20261024
---

# Watched-drive migration regression

The owner adopted successful promotion, current content, exact acknowledgement, readable fenced
status and automatic retry in scenarios v2 plus Amendment A1. Replace the disabled reproduction's
refusal expectation with that outcome; keep its real loop mounts, identity checks and owned cleanup.
The separate watcher-admission case preserves the existing final-fence handoff contract.

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
          "kind": "owner-adopted-scenario",
          "task": "drive-migration",
          "scenario": "D1",
          "quote": "The drive leaves during a held cutover; a file under it is deleted while away; it returns with the same root identity. After cutover (with and without an Engine restart in between), the migration promotes; one writable runtime serves search and ingest; the deleted file's document is gone; every other document is present with its label.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "Continue with the original brief (tmp/builder.md) under v2 plus A1: build, run the checks, commit.",
          "location": "task drive-migration user turn deciding Amendment A1"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "drive-migration",
          "scenario": "D2",
          "quote": "While away, without any companion DELETE: a file is recreated at the same path with new content; a file is modified (including a change that keeps size and mtime). After promotion, the index holds the current content; nothing current is removed.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "drive-migration",
          "scenario": "D5",
          "quote": "Concurrency: (a) admission blocked by the final fence during replay is applied after promotion; (b) an exact-revision receipt replaced during unfenced settlement wins over the older debt.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "drive-migration",
          "scenario": "D7",
          "quote": "When refusing, the Engine keeps a readable index, reports a visible fenced status naming the reason, and retries automatically when the drive and evidence return; existing operator recovery is unchanged. Indefinite refusal with evidence available is a failure.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "drive-migration",
          "scenario": "D8",
          "quote": "The integration cases skip (never pass) when loop/udisks prerequisites are missing, and leave no loop device or mount behind.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "drive-migration",
          "scenario": "A1",
          "quote": "when an incarnation ends because its root became unavailable (its volume left), record that as uncertainty scoped to that root, which only the fresh reconciliation preflight of that root (under the final fence, with the evidence rules above) can clear.",
          "label": "agent-drafted, owner-adopted"
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
