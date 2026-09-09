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
