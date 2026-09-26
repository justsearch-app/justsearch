# Onramp demo corpus

A tiny set of **entirely fabricated** documents used by the JustSearch onramp so a fresh
developer/agent has *something to index and query* without supplying their own data. The facts here
are invented (contamination-free — they won't collide with anything a model "already knows"), so this
directory is license-clean by construction (no third-party content).

Used by:
- the onramp first-query walkthrough (see `CONTRIBUTING.md`), and
- the runnable proof `scripts/dev/test-onramp-first-success.mjs`.

Ingest it into a running stack:

```
curl -s -X POST http://127.0.0.1:<apiPort>/api/knowledge/ingest \
  -H "Content-Type: application/json" \
  -d '{"paths":["<repo>/examples/onramp-corpus"],"idempotencyKey":"<fresh UUIDv7>"}'
```

Generate and retain a fresh UUIDv7 before sending, replacing the placeholder above.
Require `success: true` and keep `structuredData.operationKey`. Poll
`GET /api/operation-history/{operationKey}` for completion before querying; the
initial receipt is acceptance, not a file count. Use the same caller-supplied
UUIDv7 `idempotencyKey` when retrying an interrupted request.


Then a keyword search returns a real result **with zero models downloaded** (Tier 0); with the
embedding model present it becomes semantic (Tier 1); with the GPU chat runtime it can answer with a
citation (Tier 2). Run `node scripts/dev/doctor.mjs` to see which tier your environment is at.
