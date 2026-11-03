---
schema: test-intent.v1
task: t-20261102-redesign
author-role: orchestrator
author-session: 36cedc77-c806-45bd-af3b-93bd126c9108
---

# E2 internal stored field

The stored-field completeness rule is unchanged. The new stored-only binary field `splade_payload`
joins the declared internal fields, with its reason. The source quotes the task request; the
independent acceptor verifies it.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/status/StatusRecordSchemaTest.java"
      ],
      "class": "Structural rule kept",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "location": "task t-20261102-redesign request (2026-11-02)"
        }
      ],
      "rule": "Every stored field in the SSOT field catalog either appears in the search response fixture or is declared internal with a reason why the frontend does not need it.",
      "whySurvives": "The assertion and its loop are unchanged. One name joins INTERNAL_FIELDS: splade_payload, the stored-only binary sparse payload that FieldMapper derives for the write path (SSOT/catalogs/fields.v1.json). Search hits never carry it: SearchResultFormatter's stored-field visitor records string and numeric values only, and has no binary handler. Every other entry is unchanged. The check fails on the candidate without this entry ('Stored field splade_payload is not internal but missing from fixture', CI run 41598612855) and passes with it."
    }
  ]
}
```

## Acceptance records
