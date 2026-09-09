# C1-14 confinement signature correction — 2026-09-09

ExtractionParserConfinementTest still matched the old exact buildContentExtractor argument list.
After executor injection it failed without saying anything about where construction occurred.
The check now locates the construction call by name within the ingest branch and still requires
it before the deferred branch. The actual parser-library and routing-family rules remain intact.

A deliberate source mutation moved the actual extractor construction before the ingest condition.
The updated check failed the intended assertion: the extractor owner must only be created by the
ingest-capable branch. It compiled and ran 29 launcher tests, with exactly that one failure.
`tmp/c1-batch4-confinement-mutant-40.txt`; XML `tmp/c1-batch4-confinement-mutant-40/`.
The source was restored in finally. Restored run42 passed all 34 selected launcher/guard checks:
`tmp/c1-batch4-restored-tests-42.txt`, XML `tmp/c1-batch4-restored-green-42/app-launcher/`.

Tested tree: correction above b5d1fd961 with the C1-7 fixture changes; local Windows, Temurin JDK 25.
This verifies the confinement guard's bite, not the still-pending Rule 9/factory-census proof or
final C1 integration/hosted acceptance. Preserve these raw artifacts through lane completion +30 days.


## C1-14 final acceptance gap closure (2026-09-09)

Run184 and restored186 pass EngineSandboxFailureWorkflowTest through a real EngineRoot,
production ingestion, search/fetch and the ingestion ledger. In one fresh session the extraction
child command cannot start. The fixture independently detects PDF, Office, archive, image and
binary exactly once, submits all five alongside text/Markdown/CSV, and reconciles each path hash:
all routed events are SANDBOX_FAILED with RETRY_WITH_BACKOFF; the three decoder events are
SUCCESS_FULL with reason SUCCESS. Every decoder's unique marker is returned for its exact
normalized identity and appears in fetched content. The index holds exactly three documents.
Queue drain is intentionally not used because routed jobs remain retryable. PDF/PNG signatures
exercise routing before failed child launch; they are not parser correctness fixtures. Existing
Office and archive fixtures are reused. System-property overrides restore prior values in finally.
Raw log tmp/c1-waiter-sandbox-184.txt; XML tmp/c1-waiter-sandbox-184/.

Run185 supplies the exact Tika-import mutation missing from the earlier PDFBox-only proof.
A temporary app-services production class imports org.apache.tika.metadata.Metadata and declares
a field of that type. Since app-services intentionally does not expose Tika on its compile
classpath, javac compiled this one mutation fixture against the existing Tika3.2.3 cache jar into
that module's production class output. The guard run excluded only app-services:compileJava so
its normal jar included the adverse bytecode. No dependency or lockfile was changed. The source
and class were deleted in finally. The ArchUnit rule FAILED specifically on
C1ForbiddenTikaMutation.parserMetadata -> org.apache.tika.metadata.Metadata (33 tests,1 failure).
Restored186 uses the ordinary build graph and passes all33 confinement cases, plus four focused
workflow/waiter cases. Raw failure log/XML are tmp/c1-tika-mutation-185.txt and
 tmp/c1-tika-mutation-185/; restored log tmp/c1-waiter-sandbox-restored-186.txt.

The surviving routing/chaos test comments now describe probe failure as visible confinement;
they no longer claim silent in-process fallback. Runtime chaos assertions are unchanged.
