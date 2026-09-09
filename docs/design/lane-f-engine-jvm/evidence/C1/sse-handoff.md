# C1-7 bounded SSE replay handoff

2026-09-09, Windows / Temurin25.0.2; combined candidate over d0d09bc72. C1 remains open.

SseStreamChannel reuses its existing history capacity for the replay/live handoff's
ArrayBlockingQueue. A blocked replay socket cannot accumulate an unbounded live-frame backlog.
Overflow clears queued frame references, retires only that listener, and reports failure to the
handoff caller after its currently blocked callback returns. Publishers do not wait for the replay
socket. The queue's overflow check, offer and retirement clear share one monitor so a concurrent
publisher cannot refill a retired queue. Normal replay-to-live ordering and the existing channel
read/write lock ownership remain. This is a bounded projection of existing history policy, with no
new catalog, reconnect protocol or executor.

The tests cover the real capacity2 queue and a blocked replay while three new frames publish;
normal replay/live ordering; callback Error cleanup; and a deterministic two-publisher stale-check
race using a controlled Queue at the existing private handoff seam. The race also retains a healthy
subscriber and asserts it receives both frames exactly once. All barriers and threads are released
and joined in finally. The injected race test supplements rather than replaces the real-capacity
proof. Independent producer review found no remaining semantic defect; its two proof gaps prompted
the concurrent regression and healthy-subscriber assertion.

Runs100/105 passed the original bounded handoff tests; run100's unrelated launcher PMD failed and
was repaired separately. Mutations106/107 caught doubled capacity and omitted overflow clear.
Run128 passed all388 observability tests (four new handoff cases), launcher/lifetime checks,
whole-program dead-code and affected PMD. Mutation129 removes only the handoff buffer monitor and
fails because the retired queue is refilled. Mutation130 doubles the real queue and fails because
the blocked listener remains registered. Mutation131 omits overflow clear and fails the retained
frame reference assertion. The script expected the word "empty" but the actual assertion says
"release buffered frame references"; root read that expected behavioral failure independently.
Every mutation restored the original source in finally.

Raw logs: tmp/c1-batch4-restored-128.txt, c1-batch4-sse-mutant-{106,107,129,130,131}.txt.
XML snapshots: tmp/c1-batch4-green-128/app-observability and the matching mutant directories.
Retain through lane completion plus30 days. No full/stress/live/hosted claim follows from this item.

Restored run133 passed all observability tests, WholeProgramDeadCodeTest and observability PMD.
Log: tmp/c1-batch4-restored-133.txt; XML: tmp/c1-batch4-green-133.
