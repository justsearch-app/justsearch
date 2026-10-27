---
schema: test-intent.v1
task: extraction-configured-limits
author-role: builder
author-session: extraction-configured-limits-builder-20261027
---

# Test-intent entries

Configured extraction limits must survive the core Tika contribution registry and the in-process
sandbox: the policy an extractor reports is the policy that stamps its artifacts. Engine indexing,
configuration precedence, recovery and ledger rows were verified separately on real Engines.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxFactoryTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "CL1/CL2/CL4/CL11: auto and in_process factory compositions, the structured factory with an explicit policy, and both public provider constructors over a core Tika registry report the configured policy and yield text artifacts that validate against that reported policy. Each composition separately covers a content-only limit of 128 UTF-16 units and a file-only limit of 4096 bytes. CL6: text longer than the configured content limit yields SUCCESS_PARTIAL with truncated=true, keeps its prefix within the limit, excludes text after the cut, and validates against the reported configured policy.",
      "sources": [
        {
          "kind": "defect-source",
          "safeguard": "no data loss",
          "reproduction": "Start the Engine with worker.limits.max_content_length: 8192 in application.yaml and an owned folder of Markdown files: every file ends WRITE_FAILED with \"ExtractionException: Extraction artifact policy id does not match expected policy\" and none is searchable (reproduced on real Engines at 0d634e424b and 231a93fed5)."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261026-8f2d61 request",
          "delegationScope": "Unicode text extraction: text extracted from the user's files is measured, decoded and stored correctly",
          "label": "agent choice",
          "choice": "CL1-CL6, CL11: with a configured max_content_length or max_file_size, the auto and in_process compositions, the structured factory with an explicit policy and the public timeboxed-extractor constructors over a core Tika registry report the configured policy, and their artifacts validate against it; text over the configured content limit is kept truncated within the limit, not failed. The policy-id check itself is kept."
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-configured-limits-repairs.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxFactoryTest.java#autoFactoryReportsConfiguredContentLimitAndValidatesArtifact",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxFactoryTest.java#autoFactoryReportsConfiguredFileLimitAndValidatesArtifact",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxFactoryTest.java#inProcessFactoryReportsConfiguredContentLimitAndValidatesArtifact",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxFactoryTest.java#inProcessFactoryReportsConfiguredFileLimitAndValidatesArtifact",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxFactoryTest.java#structuredFactoryReportsConfiguredContentLimitAndValidatesArtifact",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxFactoryTest.java#structuredFactoryReportsConfiguredFileLimitAndValidatesArtifact",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxFactoryTest.java#publicConstructorsReportRegistryContentLimitAndValidateArtifact",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxFactoryTest.java#publicConstructorsReportRegistryFileLimitAndValidateArtifact",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxFactoryTest.java#configuredContentLimitTruncatesAndValidatesAgainstReportedPolicy"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxFactoryTest.java"
      ],
      "class": "New",
      "behaviour": "CL7/CL11b: default auto, in_process, structured and registry compositions preserve default policy and valid artifacts; a direct configured Tika delegate still reports its own policy; ordinary direct providers and ordinary providers under core.tika retain the default policy and valid artifacts. CL9: validation still rejects a configured-policy artifact when defaults are expected with the policy-mismatch diagnostic. The pre-existing processFactoryBuildsSandboxWithProvidedCommandAndPolicy check is unchanged and remains the CL8 preservation check.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261026-8f2d61 request",
          "delegationScope": "Unicode text extraction: text extracted from the user's files is measured, decoded and stored correctly",
          "label": "agent choice",
          "choice": "CL7-CL9, CL11b: default compositions, a direct Tika delegate, ordinary non-Tika providers and explicit process routing report what they report today, and an artifact whose policy id differs from the expected policy is still rejected."
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-configured-limits-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxFactoryTest.java#defaultCompositionsReportDefaultPolicyAndValidateArtifacts",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxFactoryTest.java#directTikaDelegateReportsConfiguredPolicyAndValidatesArtifact",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxFactoryTest.java#ordinaryProvidersReportDefaultPolicyAndValidateArtifacts",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxFactoryTest.java#mismatchedArtifactPolicyIsStillRejected"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractorContributionRegistryTest.java"
      ],
      "class": "New",
      "behaviour": "All installed policy-driven contributions share one policy regardless of their ids, and conflicting installs are rejected before any state changes. The registry recomputes that policy on install and public uninstall (defaults when none remain), retains its value across close, and stamps and bounds ordinary-provider artifacts with it while policy-driven artifacts retain their equal policy and validation remains enforced.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261026-8f2d61 request",
          "delegationScope": "Unicode text extraction: text extracted from the user's files is measured, decoded and stored correctly",
          "label": "agent choice",
          "choice": "Invariant of the configured-limit repair: on every composition the policy an extractor reports is the policy that stamps and bounds its artifacts. Settled registry contract: all policy-driven contributions in one registry share one equal policy, whatever their ids; installing a policy-driven contribution with a different policy is rejected before any state changes; the registry reports that shared policy and stamps ordinary contributions' artifacts with it; it is recomputed on install and explicit uninstall, returns to the defaults when the last policy-driven contribution is explicitly uninstalled (or none was installed), and is kept unchanged by close."
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-configured-limits-registry.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractorContributionRegistryTest.java#ordinaryContributionUsesConfiguredRegistryPolicyAndValidatesArtifact",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractorContributionRegistryTest.java#closeRetainsConfiguredCoreTikaPolicy",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractorContributionRegistryTest.java#uninstallCoreTikaRestoresDefaultPolicy",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractorContributionRegistryTest.java#installRejectsConfiguredCoreAfterDefaultPolicyDrivenContribution",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractorContributionRegistryTest.java#installRejectsMismatchedPolicyDrivenContributionAndAcceptsMatchingPolicy",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractorContributionRegistryTest.java#collidingCoreInstallRetainsConfiguredPolicy",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractorContributionRegistryTest.java#ordinaryCoreAfterUninstallReportsDefaultPolicy",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractorContributionRegistryTest.java#ordinaryCoreBeforeConfiguredVendorUsesSharedPolicyAndValidatesArtifact",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractorContributionRegistryTest.java#ordinaryCoreAfterConfiguredVendorUsesSharedPolicyAndValidatesArtifact",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractorContributionRegistryTest.java#uninstallCoreTikaRetainsConfiguredVendorPolicy",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractorContributionRegistryTest.java#uninstallLastPolicyDrivenContributionRestoresDefaultPolicy",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractorContributionRegistryTest.java#closeRetainsConfiguredVendorPolicy"
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
  "session": "01a17da7-84ad-7fe2-ad58-f461f9ae4bce",
  "verdict": "accept",
  "digest": "sha256:d8a7b823c5181bc86a64bad699920d295909258f84befee61780f1ffd36ba39c"
}
```
