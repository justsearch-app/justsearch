# C1 final candidate verification

C1 remains OPEN. Checkpoint `0b4b13ad03dd18eac0df66ab57dc8ec1071c710b` passed local
integrated verification, but final review found MCP's omitted retry-safety field. The
[correction](mcp-retry-safety.md) follows this checkpoint; the full385 result is not proof
of that later change.

## Clean pushed checkpoint

Full385: `./gradlew.bat build test :modules:system-tests:integrationTest :modules:ui:installDist
-PincludeStress=true -PskipErrorProneTests=false --console=plain` passes in9m28s on
Windows11 / Temurin25.0.2. Gradle reports16 executed,2 cached,351 up-to-date tasks.
Represented XML:9782 tests, zero failures/errors,25 skips; integration118, zero failures/errors,
52 skips. All five supervised recovery scenarios pass. A read-only reviewer independently
matched the log and manifest hashes. Raw: `tmp/c1-final-integrated-385.txt`,
`tmp/c1-final-integrated-results-385/manifest.json`. See [full summary](last-full-run-summary.json).

UI386 typecheck passes; UI387 unit passes6474 tests/483 files (HappyDOM teardown AbortError
messages remain in the output); UI388 gates pass27/27. Governance389 passes the Engine port,
blocking efficacy17 seams,44 store authorities, executor consult25 rows/67 recipes, JVM option
pins and eight regeneration sets. PIT targets are unchanged from360/365, reused explicitly.
Rust390 passes77 tests. Raw: `tmp/c1-final-ui-typecheck-386.txt`,
`tmp/c1-final-ui-unit-387.txt`, `tmp/c1-final-ui-gates-388.txt`,
`tmp/c1-final-governance-389.txt`, `tmp/c1-final-governance-389.sarif`,
`tmp/c1-final-shell-390.txt`.

Fresh live392 booted the installed checkpoint; standard cuda12 activation completed in13472ms.
No ingestion or fairness capture ran before the review finding. The owned stack was stopped
with clean:none, portsClosed:true. This establishes activation only, not live acceptance.
Raw startup: `tmp/c1-final-live-start-392.txt`.

## Hosted checkpoint

[CI34383091150](https://github.com/justsearch-app/justsearch/actions/runs/34383091150)
at0b4b13ad0 passes build, all unit lanes, integration-system, Windows-native and the other jobs.
Measured axe fails before measurement at Install headless Chromium: Google's Chrome APT
Packages.gz SHA256 disagrees with its signed index. Attempt2 fails identically. Both attempts
are preserved: `tmp/c1-hosted-failure-394.txt`, `tmp/c1-hosted-failure-retry-398.txt`. Job/revision metadata: `tmp/c1-hosted-checkpoint-401.json`.
No checksum verification or accessibility check is relaxed. A current successful hosted run
including this advisory remains mandatory. CI34386342721 repeats the same pre-measurement
hash failure at41c575f21. [Playwright APT preparation](playwright-apt.md) now excludes only
dedicated Chrome source files, with nine local fail-closed filesystem cases; hosted proof is pending.

## Broader run after MCP correction

Run400 (`build :modules:ui:test --tests ...EngineAdmissionTransportTest`, without stress)
keeps the eight MCP transport cases passing but fails the parser surviving-retirement
regression at the successful replacement request. The one-second request budget also
covered cold JVM startup. Preserve400 and correct that fixture separately; the latest
completed stress-enabled full run remains385 and does not erase this later failure.
The [parser fixture correction](parser-fixture-startup.md) reproduces delayed startup and
preserves the retained-slot assertions, including a failing lost-slot mutation.

## Remaining acceptance

Re-run integrated verification on the MCP correction, standard-active initial root scan under
continuous search, strict default-limit fairness, offline full enrichment under search, and
aggregate-limited admission. Record current successful hosted proof, then reconcile C1 before C2.
At this checkpoint origin/main has no commits/files ahead of the lane after a fresh fetch.
