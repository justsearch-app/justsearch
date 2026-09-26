# Demo corpus (tempdoc 669)

A small, deliberately **messy** set of **entirely fabricated** documents — mixed
formats, two non-English languages, one synthetically scanned/OCR document —
for reproducible public demo assets, beyond what the onramp's four tiny English
markdown files can show. A sibling to `examples/onramp-corpus/`, not a
replacement for it.

All prose content is invented, contamination-free, license-clean by
construction. `weathered-manifest.jpg` was synthesized (not a real scan) via
Augraphy (MIT License); regenerate both binary assets with
`scripts/dev/generate-demo-corpus-assets.py` (manual, dev-only, never run in
CI — see that file's header). `corpus-signature.json` records a content
signature via `jseval.corpus_identity.corpus_signature()`'s `files=` mode.

Used by `scripts/dev/stage-demo-corpus.mjs`, sharing its ingest/poll/canary
mechanics with `scripts/dev/test-onramp-first-success.mjs` via
`scripts/dev/lib/stage-reference-corpus.mjs`.

Ingest it into a running stack:

```
curl -s -X POST http://127.0.0.1:<apiPort>/api/knowledge/ingest \
  -H "Content-Type: application/json" \
  -d '{"paths":["<repo>/examples/demo-corpus"],"idempotencyKey":"<fresh UUIDv7>"}'
```

Generate and retain a fresh UUIDv7 before sending, replacing the placeholder above.
Require `success: true` and keep `structuredData.operationKey`. Poll
`GET /api/operation-history/{operationKey}` for completion before querying; the
initial receipt is acceptance, not a file count. Use the same caller-supplied
UUIDv7 `idempotencyKey` when retrying an interrupted request.


A keyword search for `"obsidian ledger"` returns `verrenmoor-customs.md`.
Run `node scripts/dev/doctor.mjs` to see which tier your environment is at.
