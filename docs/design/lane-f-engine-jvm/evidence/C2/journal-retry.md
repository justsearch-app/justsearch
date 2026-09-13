# C2-4 journal retry prerequisite — 2026-09-13

At e47275e59 plus the source inventory in
[journal-retry-verification.json](journal-retry-verification.json), the existing
journal now reports whether the event was durably retained. Failed appends do not
enter its durable read tail. The registry attempts the journal before live-ring
deduplication, allowing persistence retry without a second live delivery.

Retained id sets are derived from the existing eight file generations at open,
including events older than the500-entry read tail. Rotation retires only the ids
whose last retained copy is dropped and keeps the tail consistent with the retained
files. An uncertain write/rotation failure invalidates the cache; the next attempt
reconstructs it before writing. The cache is not another persistent authority.
Opening now reads the retained generations once instead of stopping after500 events;
normal reads remain memory-only. File format, generation/byte policy, disabled mode
and non-durable event kinds stay as before.

A killed writer's unterminated fragment is preserved but separated with a newline
before appending the next record. No prior event or fragment is truncated/replaced.
This closes the case where a valid retry was acknowledged into the in-memory id
index but disappeared on reopen because it joined the fragment.

## Evidence and skeptical re-read

`ActionEventJournalRetryTest` drives real files and the actual change registry:

- `failedAppendDoesNotAppearInTheDurableTail`: a file blocks the audit directory;
  append reports failure and the durable tail stays empty.
- `retryAfterJournalFailurePersistsWithoutDuplicatingTheLiveEvent`: the first
  failure still permits one live observation; removing the blocker and retrying
  persists exactly one line without a second typed/live event. Reopen finds it.
- `restartDeduplicatesRetainedIdsBeyondTheReadTail`:503 rows exceed the read tail;
  replaying the first id after reopen leaves the journal bytes unchanged.
- `rotationRetiresOnlyIdsWhoseLastRetainedCopyWasDropped`: small-threshold real
  rotation keeps exactly eight retained records and matches the reopened tail.
  A retained duplicate cannot rotate out newer entries; a dropped id is outside
  the sink's dedup window.
- `appendAfterATornLineRemainsReadableOnTheNextRestart`: a manually interrupted
  line is followed by a complete record; both valid rows survive reopen.

Negative1104 fails all three original regressions (4 represented cases,3 failures).
1105 passes them and rotation but fails the torn-line case (23 represented,1 failure,
expected [row-0,row-1], actual [row-0]). Final1106 executes38 cases in6 suites with
zero failures/errors/skips; journal/registry/controller behavior, PMD, formatting
and UI integration compilation pass. Registry/doc checks1107 pass. Source and raw
hashes, exact commands and XML task counts are in the verification JSON.

The review checks the physical file-derived id sets, not the500-entry tail, as
the duplicate authority; cache state is replaced only after successful full reads.
A failed write is never added to the tail or acknowledged by the boolean result.
The live ring still gates only live consumers. The file blocker, unchanged byte
count and reopened rows challenge those claims independently of this design.
No surviving objection for this sink cut. This is not a complete replay mechanism:
source rows and journal files have different retention windows, and blindly
replaying every old row could evict newer journal events. Resolve source-row
acknowledgement/catch-up and startup ordering before attaching the completion hook.
No new persistent marker or second intent store was introduced here.

Also correct two stale canonical MCP inventory counts to seven and add the query
row to the API map's tool table; live1089 already proved the seventh tool.
Raw tmp logs/counts/XML are retained through lane acceptance plus30days and must be
exported before worktree release. Current hosted runner allocation is pending;
full1059 predates this focused cut. C2-4, batch2 and the lane remain open.
