---
evolution-rule: additive-optional
---
Added `WorkerOperationalView.count_observations` and `CountObservationView` for count
freshness and observation time, `EnrichmentProgressView.completeness_known` for
unreadable audits, and `ChunkCoverageView.chunking_not_required`,
`chunk_splade_doc_count` and `chunk_splade_failed_count` for applicable work.
These fields are optional in newly generated JSON validators. Older generated strict
JSON validators reject the added fields: development clients lose status polling;
production clients log contract drift and continue with raw status data. Protobuf
consumers ignore unknown fields. The worker's chunk-SPLADE total uses optional
presence; the application mapper reports unknown when an older worker omits it.
