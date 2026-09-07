- first-startup.txt: http_first_response_ms=2563 head_ready_ms=2563 worker_ready_ms=7475
- restart-startup.txt: http_first_response_ms=2623 head_ready_ms=2623 worker_ready_ms=7058
- agent-turn.txt: agent_turn_ms=8408 bytes=158666

# GC (head-gc.log, first launch)
- uptime covered: 142 s; pauses: 6 (young 1, full 5)
- young pause ms: p50 2.6 · p95 2.6 · max 2.6 · total 3
- full pauses: Metadata GC Threshold 109M->13M 22.6ms @1s; CodeCache GC Threshold 84M->17M 29.8ms @1s; Metadata GC Threshold 142M->35M 38.8ms @17s; CodeCache GC Threshold 112M->60M 40.1ms @75s; CodeCache GC Threshold 191M->63M 38.5ms @142s
- causes: {"Metadata GC Threshold":2,"CodeCache GC Threshold":3,"Allocation Failure":1}
- live heap after GC (M): min 13 · max 63 · last 63 of 491M committed
- lines mentioning CodeCache: 6; Metaspace: 7

# GC (head-gc-2.log, warm restart)
- uptime covered: 17 s; pauses: 3 (young 0, full 3)
- young pause ms: p50 - · p95 - · max 0.0 · total 0
- full pauses: Metadata GC Threshold 112M->13M 20.2ms @1s; Metadata GC Threshold 84M->16M 23.1ms @1s; Metadata GC Threshold 131M->34M 36.1ms @17s
- causes: {"Metadata GC Threshold":3}
- live heap after GC (M): min 13 · max 34 · last 34 of 491M committed
- lines mentioning CodeCache: 0; Metaspace: 4

# Working set (MB) per role
- startup: head n=3 min 265 · p50 270 · max 296 | worker n=3 min 258 · p50 292 · max 310
- idle_first60s: head n=29 min 303 · p50 330 · max 331 | worker n=29 min 1487 · p50 4042 · max 4172
- ingest_enrich: head n=18 min 333 · p50 364 · max 369 | worker n=18 min 4064 · p50 4180 · max 4223
- search_and_agent: head n=27 min 369 · p50 386 · max 394 | worker n=27 min 4201 · p50 4250 · max 4283
- restart_warm: head n=13 min 260 · p50 296 · max 319 | worker n=13 min 192 · p50 3608 · max 3638
- head threads first/last: 76/52; head CPU seconds consumed over window: 1

# Search latency (POST /api/knowledge/search, sequential, ms; grouped by effectiveMode when recorded)
- search-load-after-enrich.csv: n=60 p50 402 · p95 880 · max 923
    HYBRID: n=51 p50 389 · p95 880 · max 923
    TEXT: n=9 p50 460 · p95 534 · max 534
- search-load-during-enrich.csv: n=60 p50 414 · p95 885 · max 916
    HYBRID: n=51 p50 408 · p95 885 · max 916
    TEXT: n=9 p50 441 · p95 597 · max 597
- search-load-lexical-after-enrich.csv: n=30 p50 87 · p95 140 · max 173
    TEXT: n=30 p50 87 · p95 140 · max 173
