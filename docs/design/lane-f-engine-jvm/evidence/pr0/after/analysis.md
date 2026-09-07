- first-startup.txt: http_first_response_ms=2555 head_ready_ms=2555 worker_ready_ms=7517
- restart-startup.txt: http_first_response_ms=2630 head_ready_ms=2630 worker_ready_ms=7631
- agent-turn.txt: agent_turn_ms=10750 bytes=212870

# GC (head-gc.log, first launch)
- uptime covered: 146 s; pauses: 5 (young 5, full 0)
- young pause ms: p50 12.8 · p95 21.3 · max 21.3 · total 66
- full pauses: none
- causes: {"Allocation Failure":5}
- live heap after GC (M): min 13 · max 64 · last 63 of 491M committed
- lines mentioning CodeCache: 0; Metaspace: 6

# GC (head-gc-2.log, warm restart)
- uptime covered: 5 s; pauses: 2 (young 2, full 0)
- young pause ms: p50 20.9 · p95 20.9 · max 20.9 · total 35
- full pauses: none
- causes: {"Allocation Failure":2}
- live heap after GC (M): min 13 · max 21 · last 21 of 491M committed
- lines mentioning CodeCache: 0; Metaspace: 3

# Working set (MB) per role
- startup: head n=3 min 335 · p50 338 · max 367 | worker n=3 min 266 · p50 305 · max 317
- idle_first60s: head n=29 min 355 · p50 361 · max 372 | worker n=29 min 1488 · p50 4043 · max 4055
- ingest_enrich: head n=19 min 367 · p50 416 · max 426 | worker n=19 min 4057 · p50 4168 · max 4219
- search_and_agent: head n=28 min 417 · p50 439 · max 460 | worker n=28 min 4202 · p50 4269 · max 4300
- restart_warm: head n=13 min 217 · p50 358 · max 372 | worker n=15 min 83 · p50 2988 · max 3614
- head threads first/last: 69/51; head CPU seconds consumed over window: 1

# Search latency (POST /api/knowledge/search, sequential, ms; grouped by effectiveMode when recorded)
- search-load-after-enrich.csv: n=60 p50 425 · p95 898 · max 945
    HYBRID: n=50 p50 423 · p95 898 · max 945
    TEXT: n=10 p50 456 · p95 581 · max 581
- search-load-during-enrich.csv: n=60 p50 438 · p95 948 · max 982
    HYBRID: n=52 p50 433 · p95 948 · max 982
    TEXT: n=8 p50 471 · p95 551 · max 551
- search-load-lexical-after-enrich.csv: n=30 p50 94 · p95 160 · max 176
    TEXT: n=30 p50 94 · p95 160 · max 176
