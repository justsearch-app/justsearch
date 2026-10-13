# Automatic picture reading while chat is loaded

The existing 30-second VDU sampler can lend an idle, managed chat server to one bounded
picture-reading pass. Both user activity and chat generation must have been idle for five
minutes. A queued request or any open inference lease blocks entry, including AUX, ASSIST and
VDU leases. Entry closes admission atomically only if no lease is open; it never waits for a
generation to finish. An external server is not borrowed.

The loaded-chat path requires a confirmed AC power source, no energy-saving request, and no
bulk-work or controller pressure hold. Battery and unknown power sources do not qualify.
The sampler rechecks these conditions at entry and between documents. The controller continues
to apply the existing profiles and vision flags: this change does not select a projector placement
or change the memory plan.

One VDU hold covers the captured batch, using the index port's existing selection limit. A normal
pass relaunches the loaded server once into VDU configuration and once back into the saved chat
configuration. Activity interrupts the pass at the next document boundary. A person's chat demand
also asks the automatic hold to finish and waits until the saved configuration has been restored;
no subsequent document is started. Shutdown rejects waiting demands and fences further launches.

Once document processing has started, the hand-off bound is **the remainder of the current
document plus one chat restoration**. If demand arrives during the entry relaunch, that controller
transition settles before restoration and no document starts. These are not fixed wall-clock
guarantees: existing launch, PDF rendering and per-page inference deadlines remain unchanged.
Module tests hold the actual model exchange, issue a human demand, release the current
document, and require restored chat within five seconds on the fake-process fixture, followed by
a successful chat completion within two seconds. Live launch and document timings require the
separate verifier's measurement.

The sampler makes at most one loaded-chat attempt in an uninterrupted idle period. New activity
followed by another full idle window rearms it. A failed attempt additionally waits at least five
minutes after completion. This avoids repeating a failed launch or cycling through more batches
on every 30-second tick.

Status uses the existing `VDU_BATCH` procedure, with `automatic-vdu` as its owner/reason, and the
existing VDU loading transitions and capability failure reasons. The AI panel describes the active
procedure as “Reading pictures. Chat resumes after the current document.” A stale snapshot does
not claim that picture reading is currently active. The existing states resume when the hold closes.

Explicit passes and automatic passes with chat already off keep their existing entry and pacing
paths, including the explicit pass's generation drain. The stricter AC-only admission and the
one-attempt rule apply to borrowing loaded chat.

## Restoration, startup and shutdown

A failed exit never rolls back to the temporary vision configuration. It retains the previous normal configuration and profile offline, with `config_apply_failed`. The controller retries after 5 seconds, then 10, 20 and at most 30 seconds between failures. A person's next chat request requests an immediate serialized restoration attempt. Sampler ticks and ordinary activation forwarding cannot bypass that backoff. This shared exit repair applies to explicit passes too.

Automatic BUSY entry is a deferral with no picture-reading offline/blocked reason. It defers for another five-minute idle window, then retries when eligible, without requiring fresh human activity. It cannot immediately retry in a loop.

Both automatic paths clamp user idle age to JVM process uptime: startup starts the five-minute idle clock. Explicit passes do not wait for this clock. Loaded automatic borrowing remains Windows-only in practice: other platforms report an unknown power source and are safely ineligible.

The additive `automatic-vdu` ENGINE reason is published only while an automatic VDU hold borrows loaded chat with chat enabled. The panel's document-boundary caption requires this reason and `VDU_BATCH`. Explicit and chat-off passes keep the original panel text.

Terminal shutdown first fences chat actuations, then interrupts the enrichment task and its owned inference exchange, then stops the process. It retains the procedure work authority through the pending-status reset and actual exit, before closing admission. It does not relaunch normal chat. The interrupted document receives no success or FAILED update; the existing processing recovery resets it to PENDING for a later pass. This applies to loaded automatic, chat-off automatic and explicit passes, before the Engine's five-second live-work drain. Human activity during ordinary operation retains document-boundary interruption.
