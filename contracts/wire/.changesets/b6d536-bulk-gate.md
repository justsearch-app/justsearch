---
evolution-rule: additive-optional
---
Spec E steps E0b and E1 add five optional fields to the status payload.

- `EnrichmentProgressView`: `embedded_and_sparse_count = 25` (whole documents with both a
  completed embedding and a completed sparse encoding; the S5 rate counter), and
  `embedding_available = 26`, `splade_available = 27`, `ner_available = 28` (whether the stage
  can make progress, so the operating block does not read a stage with work but no model as
  running).
- `CoreIndexView`: `vdu_available = 18` (the head can run visual extraction now).

A producer that does not send the availability fields is read as "available", which is
today's behaviour. No existing field is removed, renamed or retyped, and no field number is
reused.
