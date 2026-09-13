# Hosted parser fixture failure: September12

Preparation checkpoint c4fbcb53f run34719066293 fails Windows-native tests job103621468209
at WindowsParserContainmentTest.killingParserReapsAlreadyLiveNativeDescendant, recycle case.
The assertion says no PID file appeared. The fixture applies a five-second sandbox
response budget, while cold child bootstrap, containment setup and native spawn must
all precede that PID file. The log does not expose the future's failure, so a startup
timeout and native/bootstrap failure cannot be distinguished. No worker-services or
workflow code changed from passing a0cf80c0b run34717959968. This establishes an
environment-sensitive failure, not its precise cause or a waived test.

The diagnostic correction preserves a completed extraction failure when no PID file
exists, and the Windows job now uploads JUnit XML/HTML even on failure, retained30 days.
No timing, containment or live-child assertion is relaxed. Focused local770 at23974e424
plus diagnostic diff passes4 cases/1 suite, zero failures/errors/skips and worker-services
test PMD. Workflow YAML parses successfully. The new diagnostic path is not a claim
that the hosted startup failure is fixed. Successor run34720523685 passes04716d41e,
including the original timing and live-child assertions; the precise earlier startup
failure remains unclassified in its insufficient historical log.

```text
gradlew.bat :modules:worker-services:test --tests *WindowsParserContainmentTest
  :modules:worker-services:pmdTest -PwindowsOnly=true -PtestParallelism=1
  --max-workers=4 --console=plain
```

Evidence: worktree tmp/c2-2-preparation-hosted-failure-766.txt (failed hosted job log),
tmp/c2-2-parser-diagnostics-770.txt, -counts.json and -xml/. The counts helper's generic
revision label says c4fbcb53f plus generation/root-plan diff; the actual diagnostic
source is23974e424 plus this test/workflow diff. Retain local evidence through lane
acceptance plus30 days and export before worktree release.
