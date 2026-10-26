---
schema: test-intent.v1
task: t-20261024-bc37c1
author-role: builder
author-session: 01a1754c-1c12-7760-b39d-e30180e8ad23
---

# Test-intent entries

Builder execution logs, XML, actual HTTP JSON and consumer proof are retained under
`tmp/agent-evidence/windows-ui-repair/`. Independent acceptance remains pending; none is claimed here. The isolated added Java/frontend test files are subject to the gate's exemption
rules and independent verification, including real assertion fail-before evidence.

The technical WU1/WU3 scenarios and choices below are orchestrator-authored. They are not
human owner quotes or claimed owner adoption. Only the autonomy delegation is quoted as
owner wording, relayed verbatim by the orchestrator with its original root-session location.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StoreEnumerationInvalidIdsHttpTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "instrumentation"
      ],
      "whatMoved": "On Windows the planted invalid-directory inputs are U+2028 LINE SEPARATOR and U+2003 EM SPACE, both explicitly asserted invalid before Path.resolve/mkdir. Non-Windows retains every original hostile name. The two test methods retain all list/history/branch/delete/child-protection and unchanged-meta/sentinel assertions, without skips or mkdir exception swallowing. Existing syntactic-ID tests are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; don't ask me for verdicts on technical work.",
          "delegationLocation": "task t-20261024-bc37c1 request (root user takeover request in original root session 01a172e0-9080-7942-b8b0-0837c22cfb1e; verbatim owner words relayed by the orchestrator to this builder, not directly read in the original root conversation)",
          "delegationScope": "As relayed by the orchestrator: all remaining handoff work, including Windows UI qualification. Technical fixture and diagnostic decisions are delegated; this does not authorize weakening established oracles.",
          "choice": "Orchestrator technical decision WU1: plant U+2028 LINE SEPARATOR and U+2003 EM SPACE on Windows, assert invalidity before resolving/creating each folder, preserve the complete hostile-name list on non-Windows, and retain all existing HTTP and unchanged-file assertions. This fixture choice is an agent choice under the delegated technical autonomy, not human owner words or an owner-adopted scenario.",
          "label": "agent choice — orchestrator technical decision relayed to builder"
        }
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/NoEvidenceDispatchHttpTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Retain every original positional assertion, input, timeout and fake-model ownership rule. Add complete saved-history and HTTP thread rows to assertion diagnostics; the NO_CONTENT helper obtains the response before asserting so classification failures also retain the response. No expected result or assertion position changes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; don't ask me for verdicts on technical work.",
          "delegationLocation": "task t-20261024-bc37c1 request (root user takeover request in original root session 01a172e0-9080-7942-b8b0-0837c22cfb1e; verbatim owner words relayed by the orchestrator to this builder, not directly read in the original root conversation)",
          "delegationScope": "As relayed by the orchestrator: all remaining handoff work, including Windows UI qualification. Technical fixture and diagnostic decisions are delegated; this does not authorize weakening established oracles.",
          "choice": "Orchestrator technical decision WU3: retain the original NoEvidenceDispatchHttpTest positional assertions and add full saved-history/HTTP-row failure diagnostics. This diagnostic choice is an agent choice under the delegated technical autonomy, not human owner words or an owner-adopted scenario.",
          "label": "agent choice — orchestrator technical decision relayed to builder"
        }
      ]
    }
  ]
}
```

## Acceptance records

Pending independent acceptance on the exact candidate after authorized execution. No acceptance
from another candidate is carried forward.

```json
{
  "kind": "acceptance",
  "role": "orchestrator",
  "session": "01a172e0-9080-7942-b8b0-0837c22cfb1e",
  "verdict": "accept",
  "digest": "sha256:ddc2c408100c1c61bb9c26bc53956faaaa579769bdd19baab742fddabc7eb351",
  "note": "Non-author delegated orchestrator /root/ui_closeout acting under root authority. Reviewed baseline and settled WU1/WU3, preserved positional and filesystem oracles, and corrected delegated-choice provenance. This accepts the two Adaptation entries only; independent committed behavior and Windows proof remain required."
}
```

```json
{
  "kind": "audit",
  "role": "verifier",
  "session": "01a175a7-0941-7642-824b-b15bba715f23",
  "verdict": "accept",
  "digest": "sha256:ddc2c408100c1c61bb9c26bc53956faaaa579769bdd19baab742fddabc7eb351",
  "note": "Independent Codex verifier session 01a175a7-0941-7642-824b-b15bba715f23. Compared committed baseline and exact candidate 582633aa source. Accept only the WU1 portable-fixture and WU3 diagnostic Adaptation entries: complete non-Windows hostile-name list, both HTTP methods and unchanged-file oracles retained; every original NoEvidence positional assertion retained. Delegated-choice provenance correctly labels orchestrator decisions and only supplied autonomy wording as owner quote. This records source adaptation acceptance; Java runtime and Windows qualification remain pending. No claim of parent/root authority or original owner conversation access."
}
```
