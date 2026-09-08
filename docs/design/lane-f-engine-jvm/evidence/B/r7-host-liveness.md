# R7 host liveness and essential stability (2026-09-08)

Candidate above `3ab7c2081`; orchestrator implementation, independent read-only
review by `review_b14_java`. This completes the remaining host behavior of the
B14 correction; combined Java acceptance and stage-B completion remain separate.

Both startup and running probes accept valid bounded HTTP responses, including
503, from the admitted child. The shell checks its child generation and binding
before and after I/O; the development host guards asynchronous probes against
replacement and rejects discovery from another PID or predecessor instance.
Status-body collection is bounded by time and 1 MiB. The Rust helper uses the
existing reqwest parser with an explicit client-local provider from the already
locked rustls/ring dependencies, avoiding handwritten HTTP framing or a global
TLS setting change. The direct rustls dependency changes no locked version.

The existing status response supplies essential readiness: ready Head, successful
Worker observation, healthy core index and non-stale index-serving observation.
Optional AI and the retrieval composite do not govern this clock. A missing,
invalid, stale or unready observation clears the start of the continuous window.
The product window remains 300 seconds; only the existing harness flag permits
the 1.5-second test window.

Final verification: all 11 conformance cases passed on each adapter; 62 Rust
library tests passed; the Node supervisor helper suite and 31 harness self-checks
passed. ESLint, recoverability (44 authorities), canonical docs/index/skill
synchronization and links passed. The conformance binary build passed with
existing unused-code warnings and an incremental-cache access diagnostic; neither
is presented as a warning-free build. Repository-wide Rust formatting has known
baseline drift; new probe code was formatted without rewriting unrelated regions.

The shared process case starts and runs on HTTP 503, spends one crash attempt,
holds the index unready longer than the window, interrupts a partial ready window,
then observes a reset only after a new continuous window. Optional AI stays
unavailable throughout. Removing the interruption reset independently from Node
and Rust made this case fail by resetting the budget early; restored code passes.
Review caught the first fixture using a 200 status endpoint during startup; all
three startup bindings now use the same health endpoint, and both full adapter
runs above include that correction. The Tauri adapter shares the real loop and
HTTP helper, but does not execute an installed AppHandle/desktop package.

One verification rerun reused the intentionally broken Rust binary because copying
back the good source preserved an older timestamp. Refreshing the restored source
timestamp caused recompilation; the final full run below is green. The failed
run remains ignored and is not counted as another product defect. Raw artifacts
remain ignored in the B14 worktree; only summaries and hashes are tracked.

| artifact | SHA-256 |
|---|---|
| `tmp/r7-dev-reviewed-full.txt` | `c19b2e4f64ab2d9c4fa9d48c9ce0a6a2b969ccb2733192b7eab1fb43d7e9ad77` |
| `tmp/r7-tauri-reviewed-full.txt` | `c5bcbfcc373bce7a0fde224bbe5be01f52ed4169748363b26a4cc37219bf0223` |
| `tmp/r7-rust-final.txt` | `85ae894403de1a1da4f993fa69cbc76a31e5cad08b52984b45aa503275d2f3bf` |
| `tmp/r7-node-final.txt` | `0747deaf7069764242a8e8ce1be96e0e482797dbb839e707d1f57cb476be406b` |
| `tmp/r7-self-test.txt` | `7068c8f11f6291f3b5983bccd494e382149fe83aca3682d1db7f2d8ac0fd2cc1` |
| `tmp/r7-node-negative.txt` | `27c5ff8c6a6454eb565828d3d35038b39b2af9eac6340cf84d22ad0767c98bb8` |
| `tmp/r7-rust-negative.txt` | `63d98b8693c7ebdd89ae6125ab1ed278aa573abe51a5415df75acc4fa7b1962a` |
| `tmp/r7-reviewed-build.txt` | `5dbe672848ef0abda6826a1c550ef724d58642b315297a0a227268bfbff5b62b` |
| `tmp/r7-recoverability.txt` | `bbd2dca5fc9f39cf55be22fd731465c9eddf464a44558ebbc3f1aacd0e66fc9d` |
