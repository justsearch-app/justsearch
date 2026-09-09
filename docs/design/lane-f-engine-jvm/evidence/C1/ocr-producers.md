# C1-7 bounded PDF OCR producers

2026-09-09, Windows / Temurin 25.0.2, lane-F-A. Changes tested over `e9ed79694`;
the item commit carries implementation and proof. C1 remains open.

WorkerExecutorRegistrations owns one index.pdf-ocr background platform registration, with the
policy's background thread/queue limits and one live instance. ExtractionSandboxFactory and
DefaultWorkerAppServices project auto workers through OcrRoutingConfig.withWorkerLimit before
creating either an in-process extractor or a child request; an explicitly oversized value fails
configuration validation. PdfOcrEngine borrows a per-document pool through its injected factory.
The existing semaphore preserves smaller configured page parallelism. ExtractionSandboxChild's
named openOcrPool is the sole external-process exception: fixed bounded threads, ArrayBlockingQueue
and AbortPolicy. The serial child request loop and actual per-call pool close prevent pool
retention across extractor cache replacement. Image OCR remains synchronous.

Every PDF exit cancels accepted tasks, requests process termination, waits for actual pool/task
and child-process exit, and then deletes temp files. The live process set remains authoritative
until exit; failed termination preserves the temp directory. A starter can consume interruption
before registering its process, so that late registration checks the pool's shutdown state.
No duplicate cancellation marker was introduced. Typed capacity refusal and wrapped fatal errors
escape after cleanup instead of becoming UNKNOWN. These were independently re-read by the
producer reviewer; no surviving defect remained in that assigned slice.

Verification:

- Run87 exposed the delayed process-registration race: waiting for actual pool exit now revealed
  that a starter could consume the cancellation interrupt and enter a long process wait. Root
  fixed this by checking the existing executor shutdown state after live registration.
- Run88 passed all 51 then-current focused OCR/routing/registration tests and worker PMD. Tests
  exercise actual rendered PDFs with controlled process stubs; they do not require Tesseract.
- Mutation90 removed actual pool close; the held-child regression failed because the caller
  returned while its child was still alive. Source restored in finally.
- Review found that destroyForcibly only requests termination. Root added terminateAndWait,
  delayed live-handle removal and an asynchronous-kill regression using a process held alive
  after kill was requested. Mutation96 omitted the exit wait and failed the caller-alive oracle.
- Run98 restored all PDF tests (15, zero failures), routing config and registration tests plus
  worker PMD. This includes actual temp paths, fatal-child cleanup, caller interrupt restoration,
  delayed registration, and asynchronous process-exit ordering.
- Run100 added the real production-registry OCR capacity test. Mutation101 first failed only at
  a redundant shape assertion; root removed that assertion because WorkerExecutorRegistrationsTest
  already owns shape coverage. Mutation102 raised the production cap from one to two and failed
  at the behavioral oracle: the second open unexpectedly succeeded while the cancelled first
  task still ignored interruption. Restored test also proves slot reuse after actual exit.
- Run103: OCR tests and Engine capacity test passed, but launcher PMD remained red on redundant
  qualifications introduced by new architecture imports. Root fixed those unrelated qualifiers.
  Run104 passed the complete focused command: worker OCR/routing/registration tests, production
  Engine OCR-capacity test, their PMD checks and launcher PMD. Unchanged tasks reused valid results.

Commands and output: `tmp/c1-batch4-producers-{87,88}.txt`,
`tmp/c1-batch4-producer-mutant-{90,96}.txt`, `tmp/c1-batch4-producers-restored-98.txt`,
`tmp/c1-batch4-capacity-guards-sse-100.txt`, `tmp/c1-batch4-ocr-instance-mutant-{101,102}.txt`,
`tmp/c1-batch4-ocr-final-{103,104}.txt`. Raw XML snapshots: corresponding mutant directories,
`tmp/c1-batch4-producer-green-98`, and final `tmp/c1-batch4-ocr-green-104` (module XML timestamps
identify reused results). Keep all raw artifacts through lane completion plus 30 days.

The child factory remains guarded by the separate executable census item. Full/stress, real-model,
hosted/platform and final stage-wide validation remain required; this is local producer proof.
