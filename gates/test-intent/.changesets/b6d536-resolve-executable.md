---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: codex-b41-resolve-executable-20261009
---

# Recorded Tesseract executable selection

Real executable fixtures exercise native launch identity, resolution caching and the product status builder. Canonical checks fail by assertion on 74b776097 and pass after. The added precedence/restoration checks preserve existing behavior. Independent acceptance is pending. Report: F:/agent-sandbox/design-b6d536/codex/b41-resolve-executable/report.md.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/RecordedTesseractExecutableTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "PATH Scoop metadata and transparent batch forwarders select a checked real executable; unresolved batch launchers never run; repeated launches reuse one recorded resolution; existing OCR status reports PATH, app-owned or configured source. Configured/app-owned precedence and runtime restoration remain intact. Tests launch real stand-in executables and detect independent Tika launcher probes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "resolve the program once, check it, record the exact executable, and launch that directly",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b41 builder, user turn 2026-10-09)'; the text does not appear in the owner's turns of session 886833dd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Do not launch .bat or .cmd files: if one cannot be resolved to a real executable, treat Tesseract as unavailable with a clear reason rather than running it through cmd.exe.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b41 builder, user turn 2026-10-09)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-resolve-executable.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/RecordedTesseractExecutableTest.java#scoopPathLaunchesRealExecutable",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/RecordedTesseractExecutableTest.java#commandOnPathIsNeverLaunched",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/RecordedTesseractExecutableTest.java#repeatedLaunchesReuseRecordedResolution",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/RecordedTesseractExecutableTest.java#statusReportsExecutableSource",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/RecordedTesseractExecutableTest.java#transparentBatchResolvesButAmbiguousBatchAndShimCyclesBlock"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/NativeTesseractFixture.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaOcrRuntimeTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PdfOcrEngineTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "instrumentation"
      ],
      "whatMoved": "On Windows the existing runtime/PDF fixtures are real short-lived compiled executables instead of batch scripts. Existing precedence, language, tessdata, dynamic runtime and PDF lifecycle assertions are unchanged. The shared fixture records its actual image and invocations; compiler and probe cleanup retain native birth identities at spawn. Original before-state oracles run against the same replacement fixtures. Linux fixtures remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Preservation, pass before and after: configured path wins over app-owned and PATH; an invalid configured path does not fall back; app-owned runtime wins over PATH; existing TikaOcrRuntimeTest and PdfOcrEngineTest; the launcher-child accounting checks from 74b776097.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b41 builder, user turn 2026-10-09)'; the text does not appear in the owner's turns of session 886833dd"
        }
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
  "session": "verify-census7-independent-20261009",
  "verdict": "accept",
  "digest": "sha256:283c723f500506a4c52b0c5f95f31c7a68327503b6a2d48d9e4e7ae0586d7325",
  "note": "Independent identical-source comparison: five new executable checks fail by intended assertions on 351d0f22e and all eight pass on 4d02d4879; native stand-ins prove launched images and forbidden launcher nonexecution. Three precedence/restoration controls plus all 27 adapted runtime/PDF tests pass both without skips. Source integrity verified. Evidence F:/agent-sandbox/design-b6d536/codex/verify-census7/. Acceptance covers test intent, not unfinished live OCR preservation."
}
```
