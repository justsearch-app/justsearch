# Frontend settings attempt owner (2026-09-14)

C2-6 item3a introduces the transport-injected settings attempt helper and the
settings-specific mode queue entry. It does not yet migrate the eight callers;
item3b/3c and integrated/live/model/installed/final-head hosted proof remain required.

`createSettingsAttempt` takes a retained observation witness and freezes one v7 key,
serialized patch/witness body and mode header. `saveAbsoluteSettings` snapshots the
intent before GET and uses that observation only for an independent absolute field
write. No global revision cache or authentication owner is added. The caller supplies
its existing authorized transport. Missing/invalid witnesses fail closed.

Network delivery loss, explicit retryable reconfiguration/capacity admission and
open202 replay reuse those exact bytes. ACCEPTED/RUNNING/COMPLETE_WITH_GAPS remain
unsettled. COMPLETE requires matching key, committed witness key and expected+1
revision. Receipt-only replay remains receipt-only; no GET invents a full result.
Typed terminal refusal and uncertain timeout preserve the original attempt for replay.

Review found a competing mode-queue timer could discard that identity. The existing
queue still bounds mode-intent allocation and all generic writers. The new
`enqueueUiModeSettings` freezes event-time fields, then hands deadline ownership to
the bounded helper when its writer starts. This avoids another queue or attempt
registry and retains the shared event-time sequence. A stalled settings attempt
settles with its identity and the next queued intent can proceed.

Final1461 passes47 tests across four suites, TypeScript checking and focused ESLint.
The new suite has28 cases: v7 encoding/randomness and immutable capture; witness
validation; exact network/admission/open replay; receipt-only outcome; refusal
identity; invalid receipt checks; cancellation/timeout cleanup; event-time capture;
queued witness ordering and queue timeout identity. The remaining19 cases are the
existing wire/admission/mode suites. Negative1460 restores the competing timer and
fails specifically because a generic TimeoutError replaces the identity-bearing
error. Restored1461 passes. Earlier1453 had a case-sensitive happy-dom Headers test
assumption; corrected to case-insensitive Headers.get. Negative1458 had the same
intended assertion plus assertion-promise reporting noise;1460 isolates the assertion.

The initial independent reviewer identified capacity/gap handling and the deadline
integration defect. Root implemented both corrections and took the final deadline
diff after two rounds; a separate read-only review cleared the final deadline correction. Its report
misnamed the base as7f665a33d; git and the manifest identify the actual c8aa61591
base. It inspected1458/1459; root subsequently isolated the same negative assertion
in1460 and reran unchanged production sources in1461. Exact source hashes, commands and raw paths are in the adjacent manifest.
Raw files live in the active worktree tmp, retained through acceptance plus30 days
and exported before worktree release. No UI screenshot, live API/model or installed
claim follows from these helper tests.

Backend prerequisite c8aa61591 passes hosted CI34797375495 and CLA34797373680. This
does not prove the subsequent frontend helper/callers. C2 remains open; merge at F.
