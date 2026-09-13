# C1 prerequisite: parent SPLADE preservation during enrichment

2026-09-09. Implemented above `b0078e603` in `worktree-lane-F-A`. This is a
bounded correction discovered by the completed batch-3 live pacing capture; it
does not complete C1 or replace final live verification of batch 4.

The live run recorded 87 SPLADE churn drops while embedding and NER rewrote
parents. Sparse postings are not stored fields. The canonical reset-status
policy correctly marks omitted completed SPLADE as pending after RMW. The
combined enrichment owner now re-derives available completed parent SPLADE in
the same write as a pending embedding/NER outcome. Unavailable providers retain
the canonical reset/retry behavior; FAILED and COMPLETED_EMPTY are not revived.

A preservation bundle is withheld if either stage has no outcome. Preservation
alone does not reserve embedding's budget. Completed window vectors remain in
the existing bounded, content-keyed accumulator until their successful write.
Because the batch result gives counts rather than successful document IDs,
partial batches conservatively retain completed vectors and report no durable
progress. No new cache or persistent writer was introduced.

## Local proof

Executed on Windows/JDK 25 in this worktree:

`./gradlew.bat :modules:worker-services:test --tests '*CombinedEnrichmentBackfillOpsTest' --tests '*CombinedEnrichmentRmwIntegrationTest' --tests '*CombinedEnrichmentLongDocumentTest'`

`tmp/c1-batch4-rmw-restored-tests-6.txt`: exit 0, 52 tests, zero failures/errors.
XML is preserved in `tmp/c1-batch4-composition-green-results/worker-services/`.
Coverage includes real sparse postings for embedding and NER RMW, encoder
failure and blank content, stop-before/after preservation, legitimate stage
deferral, preservation-only nondeferral, and completed-window reuse after a
withheld or partial write. The earlier blank-content failure revealed a test
catalog mismatch: its status fields were not stored, unlike the canonical
catalog. The fixture now matches the shipped status/retry storage behavior.

Deliberate mutations each failed the intended assertion and were restored:

- Disable completed-parent enrollment: real RMW tests observed COMPLETED become
  PENDING and a missing attempted-retry update. Log
  `tmp/c1-batch4-rmw-preservation-mutant.txt`; XML
  `tmp/c1-batch4-rmw-mutation-results/preservation.xml`.
- Treat preservation as independent pending work: the second embedding was never
  invoked. Log `tmp/c1-batch4-rmw-share-mutant.txt`; XML
  `tmp/c1-batch4-rmw-mutation-results/share.xml`.
- Treat any partial write as complete: the partial-write regression observed
  `progressed=true` instead of false. Log `tmp/c1-batch4-rmw-partial-mutant.txt`;
  XML `tmp/c1-batch4-rmw-mutation-results/partial.xml`.

Independent read-only review found no remaining production defect after the
preservation-budget correction. Its remaining partial-count proof gap was closed
by the added regression and mutation above. Review did not independently execute
the tests. Raw evidence is retained in this worktree through lane completion plus
30 days. Final installed-candidate admission/pacing and the full C1 stress suite
remain required in batch 4; no search-quality certification is claimed here.
