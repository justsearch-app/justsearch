# C1 Windows parser native-descendant containment

## Decision and implementation

The OCR review found a real boundary that Java document ownership cannot cover: forced parser
JVM recycling can destroy the owner while Tesseract remains alive. Windows is the supported
product platform (README). Design section0 assigns this correction to C1-7/C1-11.

`WindowsParserContainment.install` uses FFM to create an unnamed Job Object with
JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE and assign the current parser before any request is served.
The non-inheritable job handle is retained for the entire parser lifetime, with no Java close
method: closing a self-assigned job would terminate the parser itself. Native descendants inherit
membership at creation, including spawns after bootstrap; no snapshot or post-spawn assignment
race is introduced. Bootstrap failure propagates and terminates the child. Failed setup closes
its handle and preserves cleanup failures as suppressed exceptions.

`ExtractionSandboxChild.initializeProcessBoundary` installs containment before starting the
existing Engine-PID watchdog. Engine death makes the watchdog halt the parser, and Windows then
kills the job's native descendants. Forced parser termination triggers the same cleanup without
running Java cleanup hooks. The chaos fixture calls this exact production bootstrap. The default
command already enables native access; both chaos override argfiles now explicitly do so too.
Other platforms retain the watchdog only, without native-tree containment. Arbitrary custom
parser implementations must call the production bootstrap; this is an explicit command contract,
not a guarantee for an unrelated executable that bypasses it.

This restores the necessary mechanism from the former app-util WindowsJobObject, removed with
its sole WorkerSpawner caller at A11, locally under the extraction owner. It adds no module
boundary or general process registry. The earlier best-effort setup and late parent-side
assignment are superseded. The canonical architecture, ADR0048 and RISK010 now describe this
boundary and component OCR pool reuse.

## Verification and refutation

Environment: Windows11, Temurin25.0.2. Base83be8a354 plus this committed item's source changes.

- Run300: compilation and PMD succeeded; two new fixture assertions failed. Request-budget
  recycling happens on the next acquisition, not immediately after the response. A missed
  deadline throws ExtractionTimeoutException, not SandboxExtractionException. The corrected
  tests exercise those actual contracts. This is not adverse production proof.
- Run301:29 focused cases and the isolated Engine-crash case pass. A live native ping process
  stands in for a native OCR process; the fixture intentionally leaves it running. Each test
  captures a live native handle before allowing the parser to return or reach its deadline.
- Run302: all four containment cases and PMD pass. The fourth launches the production child
  with restricted native access denied; actual IllegalCallerException terminates bootstrap,
  returns a nonzero exit and produces no protocol response.
- Adverse303 removes only the production containment bootstrap call. All three request-budget,
  timeout and explicit-close cases fail at the native-process exit wait. Each test cleans its
  exact native fixture handle in finally. Production source is restored immediately afterward.
- Restored304: all30 worker cases pass (four containment,23 persistent sandbox, three component
  ownership). The Engine test's stronger parent assertion exposed the old first-descendant
  discovery selecting an arbitrary process. This was a test observation defect, not a pass.
- Run305: the corrected Engine-crash test derives the parser from the witnessed native child's
  parent, verifies both belong to the live Engine and were not the boot-probe child, then kills
  the Engine. Parser44288 and native7132 were live under Engine42540; both exited, parser within
  approximately501ms. The existing Engine extraction chaos scenario also passes. There are no
  skipped cases in these five selected suites (32 cases represented together).

Commands and outputs: `tmp/c1-parser-containment-300.txt`,
`tmp/c1-parser-containment-301.txt`, `tmp/c1-parser-bootstrap-302.txt`,
`tmp/c1-parser-adverse-303.txt`, `tmp/c1-parser-restored-304.txt`,
`tmp/c1-parser-engine-305.txt`. Immutable reports: `tmp/c1-parser-results-300/`,
`tmp/c1-parser-results-301/`, `tmp/c1-parser-results-302/`, `tmp/c1-parser-results-303/`,
`tmp/c1-parser-results-304/`, `tmp/c1-parser-results-305/`.

Root reviewed the FFM handle lifetime, startup ordering, native inheritance, failure propagation
and non-vacuous PID witnesses after the read-only review's correction limit. The new tests prove
native-process lifetime, not cleanup of parser-owned temporary directories after a forced kill.
Final C1 integrated/live/hosted verification and review remain required after the ordered fixes.

Full build306 (`build -x test -PskipErrorProneTests=false`) passes in33s, including compilation
and PMD. Log: `tmp/c1-parser-build-306.txt`. Canonical index, skill embedding, link, module graph
and runtime configuration checks pass307; neither generated skill copy needed content changes.
Log: `tmp/c1-parser-docs-307.txt`. Existing fixture ballast warnings and protobuf/Mockito JVM
warnings are advisory and remain visible in the raw logs; no validation is suppressed.
