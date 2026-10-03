# C1 OCR component ownership correction

This implements independent-review must-fix2/3, owned by C1-7/C1-11. The governing decisions are
dated in design section0 and [the stage correction](../../stages/C1.md#2026-09-09-independent-review-ocr-component-ownership).
The earlier per-document pool lifetime and its close-before-return test are explicitly superseded.

## Result and ownership

PdfOcrEngine opens one lazy pool per component. Each document has a durable cancellation flag,
its temp directory and process handles, and a caller/page-task ownership count. EngineFutures'
actual-exit callback balances accepted page tasks, including queued cancellation and refusal.
At most poolSize documents may remain owned. One wedged document can leave spare workers usable;
a surviving child or failed temp deletion retains its document slot. Later calls, task exit and
component close retry cleanup without another poller or executor.

Document ownership is reserved before creating its directory. Close therefore sees an in-progress
filesystem acquisition, and a later cancellation cannot lose a directory created after close began.

Per-child termination uses timed waitFor. Document cleanup and component pool shutdown each have
a monotonic five-second wait budget. Cancellation requests every task/process stop before waiting;
one cleanup exception does not skip another document or pool shutdown. The component reports and
retains incomplete close. Temp files are deleted only after caller/tasks and children exit, and
the document slot is released only after successful deletion. Cleanup completion is observable:
another thread cannot treat an in-progress deletion as completed disposal.

Concrete close ownership runs through PolicyDrivenTikaExtractor, the contribution registry,
InProcessExtractionSandbox, the routing sandbox and the child cache. The general provider
interface remains unchanged. Cache replacement closes before replacing; EOF closes the cache.
The contribution registry retains failed providers for retry and still closes the other providers.

IndexingLoop propagates incomplete extractor close before closing NER. KnowledgeServer keeps its
completion latch/resources intact when application-service close fails. Reconstruction closes the
incumbent before replacing it and retains a failed candidate rollback in one pending slot; another
reconstruction must close that slot first. Shutdown also closes the pending candidate.
Shutdown attempts both the pending candidate and incumbent before propagating any failure; a
successful close is cleared so retry cannot repeat its native teardown.

Real executor refusals retain their typed reason/retry policy. Local document capacity has a
separate OCR exception without inventing a registry retry delay. The policy extractor records
the OCR failure and returns the real structured baseline instead of PARSER_FAILED.

## Executed proof

Windows, Temurin25.0.2, base44039df47 plus the OCR source overlay. All commands used the lane's
owned build session; no concurrent Gradle/dev stack was active.

- Compile283 passes, initially identifying an unused helper and an iteration warning; both were
  corrected. Focused284 stops at PMD's redundant CompletionException qualifier, corrected.
- Focused285 executes17 OCR cases and fails one real cleanup race: the caller could return after
  a task marked disposal but before temp deletion completed. The disposal guard/notification fix
  closes that race. Its failed XML is preserved.
- Focused286 passes OCR, component wrappers/cache, structured-refusal, IndexingLoop and server
  close-retry checks, plus affected PMD.
- Adverse287 deliberately opens a pool per call and rethrows optional OCR refusal. Both selected
  regressions fail for the intended behavior: pool count2 instead of1, and the real mixed PDF's
  structured text is lost to INSTANCE_LIMIT. Production code was restored immediately afterward.
- Restored288 encounters one incorrect Mockito in-order verification and a redundant assertion
  qualifier. Production replacement ordering was correct; the fixture now verifies both adjacent
  rollback-close calls together before incumbent retry/start. Restored289 passes59 represented
  cases with zero failures/errors/skips, including all19 PolicyDrivenTikaExtractor cases, all18
  OCR engine cases, contribution/cache/loop ownership and five server close/replacement cases.
- Final290 passes all18 OCR cases after simplifying the pool fixture helper and removing a mock
  self-observation. Full build291 (`build -x test`) passes in33s, including affected PMD and all
  compilation. It is build proof, not a full stress-suite result.
- Final-review296 passes61 cases with zero failures/errors/skips and affected PMD. Its two added
  regressions hold temporary-directory acquisition across close and retain two simultaneously
  failing service owners through shutdown. Full build297 verifies this final correction.

The regressions include a starter that consumes interruption and remains live beyond five seconds;
caller return restores interruption and retains files, a second document uses the same pool,
close fails while the starter remains owned, and release permits final cleanup. Separate tests
hold a surviving child to exhaust document capacity and inject a throwing child kill to verify
other owners are cancelled and the pool is shut down. The real PDF refusal test exercises routing,
baseline text preservation and the failure metric with a mocked runtime availability probe.

Raw logs: `tmp/c1-ocr-compile-283.txt`, `tmp/c1-ocr-focused-284.txt`,
`tmp/c1-ocr-focused-285.txt`, `tmp/c1-ocr-ownership-286.txt`, `tmp/c1-ocr-adverse-287.txt`,
`tmp/c1-ocr-restored-288.txt`, `tmp/c1-ocr-restored-289.txt`,
`tmp/c1-ocr-final-focused-290.txt`, `tmp/c1-ocr-build-291.txt`.
Preserved reports: `tmp/c1-ocr-results-285/`, `tmp/c1-ocr-results-286/`,
`tmp/c1-ocr-results-287/`, `tmp/c1-ocr-results-290/`, `tmp/c1-ocr-results-296/`.
Final logs: `tmp/c1-ocr-final-review-296.txt`, `tmp/c1-ocr-final-build-297.txt`.
Reports from later focused runs can replace Gradle's live report
directory; these immutable copies preserve the named earlier outcomes. Full stress/local live
and hosted proof after all ordered C1 corrections remain required.

## Review and immediate remaining boundary

The read-only reviewer accepted the single pool, bounded document owners, concrete close path and
structured-result preservation. Root corrected its disposal/cancellation findings, then its final
acquisition/publication and dual-service-shutdown findings, with the regressions above.

The final review also identified a distinct production process boundary: PersistentExtractionSandbox
force-kills a parser JVM on recycling/timeout/close, while native Tesseract descendants are not
registered or contained by that parent. The parser's retained Java owner cannot survive its own
process death. EOF/cache-close proof does not cover forced recycling. That process boundary is now implemented with mandatory Windows parser Job Objects and
non-vacuous forced-recycling/Engine-crash proof; see [parser containment](parser-containment.md).
This supersedes the earlier investigation note. Final C1 review and current integrated/live/hosted
acceptance remain required; no owner decision or authorization is pending.

## Integrated checkpoint proof

Full run299 at clean pushed `bad1a7622` passes in8m52s, including all five supervised
recovery scenarios. The command includes build, test, installed distribution and system
integration tests with stress enabled and test Error Prone enabled. The represented XML
contains9,751 unit cases and118 integration cases with zero failures/errors;25 unit and52
integration cases are skipped. Unchanged Gradle tasks may reuse earlier reports. See
[last full run](last-full-run-summary.json), `tmp/c1-ocr-integrated-299.txt` and
`tmp/c1-ocr-integrated-results-299/manifest.json`. This does not close the native-descendant
containment finding or replace final live and hosted C1 acceptance.
