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
transition settles before restoration and no document starts: the request costs two relaunches
(entry plus return). These are not fixed wall-clock
guarantees: existing launch, PDF rendering and per-page inference deadlines remain unchanged.
Module tests hold the actual model exchange, issue a human demand, release the current
document, and require restored chat within five seconds on the fake-process fixture, followed by
a successful chat completion within two seconds. Live launch and document timings require the
separate verifier's measurement.

The sampler makes at most one loaded-chat attempt in an uninterrupted idle period. New activity
followed by another full idle window rearms it. A failed launch additionally waits at least five
minutes after completion. A lost start race with an explicit pass rearms after the next full
idle window without requiring fresh human activity. This avoids repeating a failed launch or cycling through more batches
on every 30-second tick.

Status uses the existing `VDU_BATCH` procedure, with `automatic-vdu` as its owner/reason, and the
existing VDU loading transitions and capability failure reasons. The AI panel describes the active
procedure as “Reading pictures. Chat resumes after the current document.” A stale snapshot does
not claim that picture reading is currently active. The existing states resume when the hold closes.

Explicit passes and automatic passes with chat already off keep their existing entry and pacing
paths, including the explicit pass's generation drain. The stricter AC-only admission and the
one-attempt rule apply to borrowing loaded chat.

## Restoration, startup and shutdown

A failed exit never rolls back to the temporary vision configuration. It retains the latest normal configuration and profile offline, with `config_apply_failed`. The controller retries after 5 seconds, then 10, 20 and at most 30 seconds between failures. After five consecutive failed attempts, counting the failed return from the pass, automatic retries stop. Status retains `config_apply_failed` and says “Automatic retries stopped after five failed attempts.” A person's next chat request, applying settings, or switching chat off and on resets the budget and permits the next attempt. A chat request requests an immediate serialized restoration attempt, subject to residency admission. Any normal configuration applied after the pass began supersedes the saved restore target. A restarted normal configuration ends restoration; a record-only apply becomes its next target. Turning chat off clears the controller's pending retry. For GPU chat, GPU and game pressure defer restoration with the existing pressure reason and two-minute reload dwell; a human request receives the usual refusal and load-anyway option. CPU chat retains its normal residency exemption. Sampler ticks and ordinary activation forwarding cannot bypass that backoff. This shared exit repair applies to explicit passes too.

An explicit pass requested while restoration is pending performs that serialized restoration before entering the vision configuration. It does not wait for a retry timer, new activity or reload dwell. Entry therefore costs one normal start and one vision relaunch, with the existing bounded start and stop deadlines. The real-controller check with two healthy fake starts delayed by two seconds reaches its first document within twelve seconds; real model startup remains subject to the existing health deadline.

Settings committed through a record-only prepared refresh while chat is offline also retain the newest normal restore target and reset the retry budget. Preparing a candidate without committing it does not reset the budget. A disabled publication clears the target; a verified running publication finishes restoration.

Automatic BUSY entry is a deferral with no picture-reading offline/blocked reason. It defers for another five-minute idle window, then retries when eligible, without requiring fresh human activity. It cannot immediately retry in a loop.

Both automatic paths clamp user idle age to JVM process uptime: startup starts the five-minute idle clock. This intentionally changes the chat-off startup behaviour from 0a79303bb too, which could previously run an automatic pass immediately. Explicit passes do not wait for this clock. Loaded automatic borrowing remains Windows-only in practice: other platforms report an unknown power source and are safely ineligible.

The additive `automatic-vdu` ENGINE reason is published only while an automatic VDU hold borrows loaded chat with chat enabled. The panel's document-boundary caption requires this reason and `VDU_BATCH`. Explicit and chat-off passes keep the original panel text.

Without an active picture-reading pass, shutdown retains the original order and allowances: interactive cancellation and both work drains precede the late terminal chat stop. A quit runs inside its single quit deadline (Spec F). With an active pass, the quit's admission step (QB1) fences chat actuations and new documents and asks the pass to cancel, without waiting. llama-server is stopped only through the chat controller, by the quit's chat stop that starts at QB1. The wait for the pass to end comes out of the quit's live-work budget, not a separate allowance. When the orderly cancel completes within that budget, the interrupted document is back to PENDING with its pre-claim retry count. If the supervisor's forced exit overtakes the cancel, the document stays PROCESSING and the next start's general startup recovery returns it to PENDING, keeping the claimed retry count; that is the documented limit. Restart, upgrade and hang closes, which have no quit deadline, keep the earlier early stop: with an active pass, shutdown first fences chat actuations, interrupts the task and its inference exchange, and stops picture reading with the normal twelve-second terminal allowance. No return to normal chat is launched during shutdown. The current document is abandoned without a terminal acknowledgement, retaining the live procedure authority through its conditional PROCESSING-to-PENDING reset. Reset and final result publication serialize; reset requires the same procedure owner and claimed retry count, and restores the retry count from before the claim. Cancellation does not spend a document retry. A delayed result cannot overwrite PENDING and a completed result cannot be reset. Document ownership and the pre-claim count are held in worker memory. A worker restart loses that ownership and recovers through the existing general startup recovery, which retains the claimed retry count.

If an explicit pass is still draining a person's chat when shutdown begins, cancelling the entry releases the pass while normal chat keeps its ordinary interactive cancellation and late physical stop. `VDU_STOP_DEFERRED_CHAT` records that early-stop deferral; VDU inference leases do not count as chat generation and still receive the early stop.

Every later shutdown step is attempted even after a slow early stop or drain. `VDU_DRAIN_TIMEOUT`, `VDU_DRAIN_INTERRUPTED`, `VDU_DRAIN_FAILED` and `VDU_STOP_INCOMPLETE` are named early-step outcomes; final terminal cleanup checks whether any process actually survives. An undrained owner still retains its dependencies. Human activity during ordinary operation retains document-boundary interruption.
