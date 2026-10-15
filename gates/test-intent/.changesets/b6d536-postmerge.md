---
schema: test-intent.v1
task: b6d536
author-role: builder
author-session: codex-b6d536-postmerge
---

# Post-merge placement diagnostics and sampler adaptation

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/ChatPlacementDiagnosticsTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingChatModelDiagnosticTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "An unmeasured custom chat model emits a diagnostic only for a positive GPU reservation (absent with no GPU or zero layers), never an unmet-setting cause. Pending chat identity verification has its own status diagnostic and names the vision-off reason; a serving vision model stays available.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "emit it only when a GPU reservation is actually made for a custom chat model whose memory couldn't be measured. It's a diagnostic, never a `user_setting_unmet` token.",
          "location": "task b6d536 request"
        },
        {
          "kind": "owner-words",
          "quote": "While a chat model's identity is still being verified, the status needs its own reason, `chat_identity_pending`.",
          "location": "task b6d536 request"
        },
        {
          "kind": "owner-words",
          "quote": "If vision is off during that time, the reason given for vision being off names `chat_identity_pending`.",
          "location": "task b6d536 request"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-postmerge-diagnostics.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/ChatPlacementDiagnosticsTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingChatModelDiagnosticTest.java"
      ]
    },
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/PlannedContextSettingsIntegrationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The CPU-placement HTTP test explicitly runs the status sampler before observing the notice and again before observing its clearance, matching its sibling. Inputs, HTTP writes, persistence checks, status reads and every expected value stay unchanged. Product StatusLifecycleHandler dispatchObservations samples device-plan and sampleAndBuildStatusSnapshot waits for that publication; ordinary handleStatus projects existing observations.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Then add the sample call before the read, as an adaptation entry in the changeset.",
          "location": "task b6d536 request"
        }
      ]
    }
  ]
}
```

## Acceptance records

Pending separate verification. The builder records no acceptances.
