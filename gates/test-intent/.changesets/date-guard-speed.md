---
schema: test-intent.v1
task: date-guard-speed
author-role: builder
author-session: 01a1c6c7-ea6a-7060-80be-7f571bc1457a
---

# Isolate the date lint guard's planted snippets

The existing direct, optional, literal-computed and JavaScript Date calls retain their
expected diagnostics, and number formatting remains allowed. The harness loads the real
plugin and rule setting from `modules/ui-web/eslint.config.js` once, with an isolated
TypeScript program instead of the application's project service. No timeout is increased.

The assignment also requires subclass, generic, union and string cases. These were absent
from the baseline test, so their explicit coverage is recorded separately from the harness
adaptation. No product code changes.

```json
{
  "entries": [
    {
      "items": ["modules/ui-web/src/shell-v0/display/dateLocaleGuard.test.ts"],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "Create one ESLint instance with only the real SES guard plugin, parser and configured rule setting, and supply a TypeScript program containing the two planted snippets and the ES standard library. The repository project service and unrelated lint rules are no longer run for these snippets. All original planted calls retain their expected line diagnostics and allowed number formatting. Assertions now compare every diagnostic's rule and line so parser failures or unrelated diagnostics cannot be filtered out. The default Vitest timeout remains unchanged. Additional receiver forms are accounted for by the New entry."
    },
    {
      "items": ["modules/ui-web/src/shell-v0/display/dateLocaleGuard.test.ts"],
      "class": "New",
      "behaviour": "Preserve the existing guard's rejection of Date subclass, Date-constrained generic and Date-or-number union receivers, and allow unrelated string formatting in TypeScript and JavaScript. These cases extend the existing named check without changing production behavior.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task date-guard-speed request",
          "quote": "Keep every case it checks: direct, optional, literal-computed, subclass, generic and union calls rejected; unrelated number and string formatting allowed."
        }
      ],
      "evidence": "gates/test-intent/evidence/date-guard-speed.json",
      "newChecks": ["modules/ui-web/src/shell-v0/display/dateLocaleGuard.test.ts#rejects planted Date locale calls while allowing number formatting"]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1c6cd-c131-76d2-8168-69b8cb158c20",
  "verdict": "accept",
  "digest": "sha256:6eed9e7276c7607bec0952fc0e9f8989f63e2e078e0e10704a3490d96a80dc6e"
}
```

```json
{
  "kind": "audit",
  "role": "verifier",
  "session": "01a1c6d4-4e59-7cf0-a66d-f41cad8a7cfd",
  "verdict": "accept",
  "digest": "sha256:6eed9e7276c7607bec0952fc0e9f8989f63e2e078e0e10704a3490d96a80dc6e"
}
```
