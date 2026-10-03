# C1 pacing harness: retain the corpus-count floor

Clean pacing207 added a fresh469-file corpus to an independently verified empty index. After30s
without observed watcher activity, jseval reduced the required count469 to0. Subsequent snapshots
showed real indexing activity and469 documents; absence of activity in that initial observation
window was not evidence that the corpus was already indexed.

`ingest_and_wait` already establishes watched-root identity before enqueueing: a known root uses
the union floor, while a new root uses existing count plus corpus count. Retain that established
floor when the watcher observation window expires. The existing readiness timeout still bounds
the run. No retry, polling loop or alternate readiness representation is added.

Regression218 extends the existing new-root floor test with an unobserved-watcher arm. Original
code fails exactly because it passes1001 instead of3001 to readiness; the observed-watcher arm
passes. Restored220 runs all38 ingestion tests successfully. Logs:
`tmp/c1-watcher-floor-before-218.txt`, `tmp/c1-watcher-floor-restored-220.txt`.
The existing known-root union test remains green, so repeated ingestion is not double-counted.
