---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b63-vdu-observation-justified
---

The ingestion history read and other deliberate client reads without unattended consumers advance
shared activity. Each retained observation names its actual consumer or status/health/readiness
purpose in ObservationJustifications; production startup checks registered method/path templates.
ActivityRole semantics, exactly-one declaration validation, and VDU pacing remain unchanged.
Caller audit and native interruption/recovery evidence: F:/agent-sandbox/design-b6d536/codex/b63-vdu-observation-justified/report.md.

```json
{
  "entries": [
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/ObservationJustificationTest.java", "modules/ui/src/test/java/io/justsearch/ui/api/DeclaredRequestActivityTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "Real HTTP through production filters records activity for the deliberate ingestion ledger read even with internal headers; the additional deliberate reads and legacy attachments also record activity. A new observation without a method/path justification is rejected, and registered observer families retain their justifications. The registered-route oracle retains its independent activity rule while adding the newly audited deliberate reads and removing legacy attach exemptions.",
      "sources": [
        {"kind": "owner-words", "quote": "every OBSERVATION declaration must name the unattended consumer or the status/health/readiness reason that justifies it, in one place a test checks", "location": "task t-20261005-b6d536 request"},
        {"kind": "owner-words", "quote": "if any other OBSERVATION route has no such caller or reason, reclassify it and list it", "location": "task t-20261005-b6d536 request"},
        {"kind": "owner-words", "quote": "everything a client asks for deliberately is USER_ACTION", "location": "task t-20261005-b6d536 request"}
      ],
      "evidence": "gates/test-intent/evidence/b63-vdu-observation-justified.json",
      "newChecks": ["modules/ui/src/test/java/io/justsearch/ui/api/ObservationJustificationTest.java", "modules/ui/src/test/java/io/justsearch/ui/api/DeclaredRequestActivityTest.java"]
    }
  ]
}
```

## Acceptance records

Pending independent verifier review. The builder does not provide its own acceptance.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-vdu5-codex-20261011",
  "verdict": "reject",
  "digest": "sha256:e5928435c36f7d75c867beef162422d8238ae33fbbe4f4aa4905f04ea2ca4a8e",
  "note": "Independent round 5: FAIL. Deliberate GET /api/chat/sessions and GET /api/indexing-jobs/failed/by-prefix remain observation with no unattended caller. Independent production-filter assertions fail and actual requests immediately after accepted capture do not interrupt: each operation completes one unit. Startup accepts an obsolete justification after removing its route. Five consumer justifications name sources that do not perform their claimed requests. Ledger and preview repair, real accepted chat and extra deliberate requests interrupt and recover; GPU-on completion, raster search and protocols pass. Automated gates, safety exclusions and remaining native checks/limits are recorded as they complete at F:/agent-sandbox/design-b6d536/codex/verify-vdu5/report.md. No product/test edits, commits or pushes."
}
```
