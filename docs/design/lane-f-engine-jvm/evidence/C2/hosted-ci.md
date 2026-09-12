# C2 hosted verification

September12: CI34695294553 at3be553d89 passes12 jobs but Build (no model blobs)
fails in the all-source-set static-analysis step: ui compileIntegrationTestJava,
SchemaMismatchStatusContractTest.java:107 still calls EngineRoot without the now
required OperationAttemptRunner. The focused unit compilation missed this source
set. CI34696855256 at4e800f61d repeats that exact failure in completed build job
103561729483; the overall run was still active when inspected. These are required
red checks, not PMD violations or waived platform limits.

Correction supplies the explicit mocked runner alongside the existing mocked store
in this schema-status fixture, whose property is index mismatch reporting and whose
production bootstrap dependencies are required even though the test does not perform
recorded mutations. No constructor fallback or test suppression is added.

Sources: [earlier run](https://github.com/justsearch-app/justsearch/actions/runs/34695294553),
[current build](https://github.com/justsearch-app/justsearch/actions/runs/34696855256/job/103561729483).
Raw logs: tmp/c2-hosted-34695294553-failed.txt and
 tmp/c2-hosted-34696855256-build.txt. The attempt to retrieve the overall active run
log is retained in tmp/c2-hosted-34696855256-failed.txt (not a failure log).
Retain with the lane's evidence inventory through acceptance plus30 days.


Local633 passes :modules:ui:compileIntegrationTestJava and :modules:ui:pmdIntegrationTest
in10s. Log: tmp/c2-2-hosted-fixture-633.txt. Input is the fixture correction atop4e800f61d
plus the independently verified pending pack completion item; that item does not change
EngineRoot's constructor contract. Fresh hosted success remains required after push.
