# Parser retained-slot fixture startup allowance

Broader400 at0b4b13ad0 plus the MCP correction fails exactly one test: the successful
replacement request at PersistentExtractionSandboxTest:542 exceeds a one-second request
budget. All retained-slot assertions before replacement pass. This fixture budget also
includes cold JVM startup. The neighboring timeout/replacement test already uses ten
seconds for the same Windows cold-start condition.

The retained-slot test now uses that ten-second allowance, preserving its40-second outer
watchdog, real hung child, kill refusal, exact retained PID, blocked replacement, one-spawn
assertion, actual kill and different recovered PID. A dedicated replacement child delays
startup by two seconds so the old budget fails deterministically. No production deadline,
retirement behavior or assertion is weakened. The test still waits for its actual timeout
before attempting retirement; it does not replace the timeout stimulus with a different fault.

Windows11 / Temurin25.0.2, base41c575f21 plus this test-only item:
- Negative405 with the delayed child and old one-second allowance fails the replacement call.
- Restored406 passes28/28 sandbox tests, zero skips, in51s (suite46.654s).
- Adverse408 clears the live child's slot before the retention exception. The test fails its
  exact retained-PID assertion. Production is restored byte-for-byte in finally.
- Final409 passes the restored production test in17s; complete compile/gates410 pass in6s.

Raw: `tmp/c1-parser-startup-negative-405.txt`,
`tmp/c1-parser-startup-negative-results-405/manifest.json`,
`tmp/c1-parser-startup-restored-406.txt`,
`tmp/c1-parser-startup-restored-results-406/manifest.json`,
`tmp/c1-parser-startup-adverse-408.txt`,
`tmp/c1-parser-startup-adverse-results-408/manifest.json`,
`tmp/c1-parser-startup-final-409.txt`,
`tmp/c1-parser-startup-final-results-409/manifest.json`,
`tmp/c1-parser-startup-build-410.txt`.

Full400's failure remains preserved. A fresh stress-enabled integrated pass and the remaining
live/hosted checks are mandatory before C1 closes.
