# Request-time stage latency (ms) over 36 calls
- tookMs: p50 754 · p95 1737 · max 1751
- fusion: n=36 p50 8 · p95 11 · max 20
- chunk-merge: n=26 p50 13 · p95 16 · max 26
- branch-fusion: n=26 p50 0 · p95 0 · max 1
- cross-encoder: n=36 p50 147 · p95 258 · max 260
