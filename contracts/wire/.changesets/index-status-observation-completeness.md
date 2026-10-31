---
evolution-rule: additive-optional
---
Added `WorkerOperationalView.count_observations` and `CountObservationView` for count
freshness and observation time, `EnrichmentProgressView.completeness_known` for
unreadable audits, and `ChunkCoverageView.chunking_not_required`,
`chunk_splade_doc_count` and `chunk_splade_failed_count` for applicable work.
All additions are backward-compatible with existing producers and consumers.
