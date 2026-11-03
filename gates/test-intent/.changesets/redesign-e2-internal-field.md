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
      "whySurvives": "The assertion and its loop are unchanged. One name joins INTERNAL_FIELDS: splade_payload, the stored-only binary sparse payload that FieldMapper derives for the write path (SSOT/catalogs/fields.v1.json). Search hits never carry it: SearchResultFormatter's stored-field visitor records only string and numeric values; its binary handler discards the value. Every other entry is unchanged. The check fails on the candidate without this entry ('Stored field splade_payload is not internal but missing from fixture', CI run 41598612855) and passes with it."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1a074-22ff-7d11-8ea2-1b8ad93ec871",
  "verdict": "accept",
  "digest": "sha256:92e171188474efde84f1ba1326c92e92a5ba139816145e744901217c64880166",
  "note": "verifier-4 on a3a4304934: rule and assertion unchanged, only splade_payload added; StatusRecordSchemaTest fails on 22464e1ee8 for that field and passes after (25 tests, 0 skips); no binary stored value reaches a frontend response. The one wording correction the verifier asked for (the formatter's binary handler discards values) was applied before this record."
}
```
