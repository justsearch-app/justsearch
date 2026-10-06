---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, Spec F item 6 (supervision and containment), step 5 Node part: the dev
runner gets a Node exit-ledger reader and writer (`scripts/dev/lib/exit-ledger.cjs`) and the shared
cross-language fixture cases under `governance/fixtures/exit-ledger/cases/`, each with the summary
every reader must give. The Java reader is held to the same summary file here; the Node reader is
held to it by `scripts/dev/test-dev-runner-exit-ledger.mjs`. This changeset covers only this slice's
Java test; the dev-runner script tests need no entry (Spec F section 8.1).

```json
{
  "entries": [
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerSummaryParityTest.java",
        "governance/fixtures/exit-ledger/cases/reader-edge/engine.v1.jsonl",
        "governance/fixtures/exit-ledger/cases/reader-edge/engine.v1.1.jsonl",
        "governance/fixtures/exit-ledger/cases/reader-edge/expected-summary.json",
        "governance/fixtures/exit-ledger/cases/children-basic/children.v1.jsonl",
        "governance/fixtures/exit-ledger/cases/children-basic/expected-summary.json"
      ],
      "class": "New",
      "behaviour": "Spec B T15 as Spec F step 5 uses it. javaReaderGivesTheSharedSummary: for every case directory under governance/fixtures/exit-ledger/cases, the Java ExitLedgerReader (rotated file then current, CRLF stripped, blank lines skipped, lines over 8192 bytes, malformed, non-object, bad envelope or bad writer counted corrupt, newer schema counted unknownSchema, a type outside the family counted unknownType, a torn tail flagged) and LedgerTimestamps (an instant is known only if it is a Z-suffixed parseable instant at or after 2001, otherwise null) give exactly the summary in expected-summary.json for both families: per record type, seq, at, writer, detail, raw detail and every time field, plus the four counters. The Node reader is checked against the same file, so the two agree through it. No fail-before is claimed: the Java reader already exists and this pins its agreement with the new Node reader.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S15",
          "quote": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S17",
          "quote": "Dev and app hosts give the same accounting and cleanup when the Engine dies during startup or later; an identity lookup failure reads as unknown, never dead.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-ledger-parity.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerSummaryParityTest.java#javaReaderGivesTheSharedSummary"
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
  "session": "0ae522aa-cda1-4c85-9e8f-65677d5cc3c0",
  "verdict": "accept",
  "digest": "sha256:5bfd168ffc0df4d24e60562475218d3f950e2a4cac49ee9371856315977e3de9",
  "note": "Verifier f12 (not named in the brief, judged because the gate needs it): ExitLedgerSummaryParityTest and its fixtures pass on ef6df5aec; the fixture oracle is hand-designed edge cases (S15, S17), no fail-before claimed, consistent with class New."
}
```
