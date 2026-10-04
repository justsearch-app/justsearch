# R10: lifecycle proof must observe indexed content

Full863 atd9c80a646 fails batchIndexingMultipleFiles with expected>=10 and observed10.
The queue-drained half of the condition fails. The preceding lifecycle test searches
for lifecycle-modified plus the common run suffix, which already matches tokens in
the initial indexed content. It deletes the source while its modification is queued.
The log records NoSuchFileException during SourceContentHash, an IO_FAILED retry
after60s, then all ten batch documents committed successfully. The retry remains in
the queue until the next claim classifies the deleted source as STALE_SOURCE.
This is a false-positive lifecycle witness, not lost batch ingestion.

Raw full output/XML are `tmp/c2-review-r10-full863.txt` and
`tmp/c2-review-r10-full863-xml/`; the extracted suite log is
`tmp/c2-review-r10-workflow-diagnostic865.txt`. At diagnostic lines3177–3204, the
modification is accepted and then fails because the test already deleted its file;
lines3312–3314 show all ten batch rows committed and marked done.

The correction requires the actual indexed document's content to contain the modified
marker before deletion. WorkerSearchService.fetchDocuments reads the index through
DocumentFieldOps, not the mutable source file. Keep the existing unforced submission,
search, document-count, batch and prune assertions, with the existing30s bound.
Negative control: omit only modification submission; the strengthened witness must
fail for absent modified indexed content before it deletes the source.

Negative867 executes one case and fails specifically on absent modified indexed
content. After exact-source restoration, gate868 executes all eight workflow cases,
with no failures/errors/skips; PMD and UI integration-test compilation pass. Raw
`tmp/c2-review-r10-workflow-negative867.txt`, its preserved
`tmp/c2-review-r10-workflow-negative867-xml/`, `tmp/c2-review-r10-workflow868.txt`
and `tmp/c2-review-r10-workflow868-xml/` retain both outcomes. Exact gate command and
counts are in review-r10-lifecycle-verification.json. Full863 represents10133 cases
across1647 suites, with one failure and35 inherited skips; a successful full rerun
is still required after this correction.
Retain raw evidence through lane acceptance plus30 days and export before worktree
release. R10 and the final independent correction review remain open.
