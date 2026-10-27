---
schema: test-intent.v1
task: extraction-configured-limits
author-role: builder
author-session: extraction-configured-limits-builder-20261027
---

# Test-intent entries

The configured extraction policy must survive the core Tika contribution registry and in-process
sandbox composition. These checks exercise the assigned worker-services boundary; Engine indexing,
configuration precedence, recovery, ledger persistence and unchanged-document behavior remain for
independent scenario verification.

D4 execution evidence and independent acceptance are pending the orchestrator/verifier. The builder
was explicitly instructed not to run `run-evidence.mjs` or write Git state. The evidence paths below
are destinations for that script, not a claim that evidence files already exist. Repair check IDs
are listed separately from preservation IDs so the orchestrator can supply `--before 231a93fed5`.

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
          "kind": "owner-words",
          "location": "task extraction-configured-limits user builder assignment, Checks you add (worker-services unit tests)",
          "quote": "Fail before, pass after (assertion failures, not exceptions): for `auto` and `in_process` factory compositions and for `inProcessStructured` with a configured policy, and for a public `TimeboxedContentExtractor` over `ExtractorContributionRegistry.withCoreTika(configured extractor)`: the reported policy equals the configured policy, and an artifact extracted from a small text file validates against the reported policy (the same validation `JobBatchExtractor` uses). Cover a `max_content_length`-only and a `max_file_size`-only configuration. One case where a text file longer than the configured content limit extracts as truncated, within the limit, and validates (CL6)."
        },
        {
          "kind": "owner-words",
          "location": "task extraction-configured-limits user builder assignment, scenario CL6",
          "quote": "A file whose text exceeds the configured `max_content_length` (one limit below and one above the default) is indexed, not failed: partial status, truncated flag set, searchable text before the cut kept, nothing beyond the limit stored. The kept length is at most the limit (a surrogate pair is never split, so it may be one unit shorter)."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Prefer extending `ExtractionSandboxFactoryTest`/`InProcessExtractionSandbox` tests already present over new files where it reads naturally.",
          "delegationLocation": "task extraction-configured-limits user builder assignment, Checks you add (worker-services unit tests)",
          "delegationScope": "Placement and implementation of the assigned worker-services checks; expected behavior and repair design remain fixed.",
          "choice": "Extend ExtractionSandboxFactoryTest with ordinary named test methods, shared local composition helpers, and test-owned temporary text files. Test both public provider constructor overloads. Use small configured limits to exercise each limit independently without large files.",
          "label": "agent choice"
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
          "kind": "owner-words",
          "location": "task extraction-configured-limits user builder assignment, Checks you add (worker-services unit tests)",
          "quote": "Preservation, passing before and after: default policy compositions; explicit `process` routing with a configured policy; a mismatched-policy artifact is still rejected (CL9); a direct non-Tika provider reports defaults."
        },
        {
          "kind": "owner-words",
          "location": "task extraction-configured-limits user builder assignment, scenario CL11b",
          "quote": "Direct Tika delegates, ordinary (non-Tika) providers and default compositions report what they report today."
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-configured-limits-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxFactoryTest.java#defaultCompositionsReportDefaultPolicyAndValidateArtifacts",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxFactoryTest.java#directTikaDelegateReportsConfiguredPolicyAndValidatesArtifact",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxFactoryTest.java#ordinaryProvidersReportDefaultPolicyAndValidateArtifacts",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxFactoryTest.java#mismatchedArtifactPolicyIsStillRejected"
      ]
    }
  ]
}
```

## Acceptance records
