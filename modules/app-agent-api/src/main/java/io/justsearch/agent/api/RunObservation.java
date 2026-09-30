/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The seam through which a run becomes an OBSERVED entity (tempdoc 834 §1.3).
 *
 * <p>This replaces {@code RunEventHub}, the session-local hub the agent loop used to own. Rev 1 of
 * 834 proposed building a hub, registry, sequence authority, ring buffer, subscription handle and
 * heartbeat here; all six already existed two packages over, production-proven, in
 * {@code app-observability}'s SSE substrate. So the decision was PROJECTION, not fork: the loop
 * keeps its vocabulary and hands events to this interface, and the substrate that implements it
 * owns sequencing, bounded replay, fan-out, evict-on-throw, and the terminal transition.
 *
 * <p><strong>Why the observer side speaks {@link WireFrame} and not {@link AgentEvent}.</strong>
 * The journal carries the wire-projected {@code (name, payload)} pair from the single authority
 * ({@link AgentEventPayloads}); publishing a raw {@code AgentEvent} into a serialized journal would
 * bypass that authority and re-create the three-way drift it exists to prevent (§1.3.2). Events go
 * IN typed and come OUT projected — which is also why the AG-UI adapter grew a map-input overload
 * with an equivalence gate (§6.5).
 *
 * <p>Depends on nothing but the JDK and this module's own {@code AgentEvent}, so the agent module
 * gains no dependency by publishing through it.
 */
public interface RunObservation {

  /** One frame as it goes on the wire: the projected {@code (name, payload)} pair. */
  record WireFrame(String name, Map<String, Object> payload) {}

  /**
   * Opens the observation channel for a run.
   *
   * @param runId the run's identity — for an agent run, its {@code sessionId} (§3.2 aliases the two
   *     rather than minting a mapping table that would drift)
   * @param shapeId what kind of run this is
   * @param conversationId the conversation it answers into; blank when it answers into none
   */
  Handle open(String runId, String shapeId, String conversationId);

  /**
   * The no-substrate default: nothing is journaled and {@link Handle#observerCount()} is 0.
   *
   * <p>Zero, not one, and deliberately so. With no substrate installed there is no channel a second
   * observer could ever attach to, so answering "someone is watching" would let a WATCH run proceed
   * UNSUPERVISED on a misconfigured wiring — the exact safety goal the posture-graded park exists
   * to meet. Parking instead fails loudly and recoverably. (The initiating observer is counted
   * separately by the session, so a normally-wired run with a live socket still PROCEEDs.)
   */
  RunObservation NONE = (runId, shapeId, conversationId) -> Handle.NONE;

  /** One run's channel. */
  interface Handle {

      /** See {@link RunObservation#NONE}. */
    Handle NONE =
        new Handle() {
          @Override
          public void publish(AgentEvent event) {}

          @Override
          public int observerCount() {
            return 0;
          }

          @Override
          public void setSnapshotSupplier(Supplier<AgentEvent.StateSnapshot> supplier) {}

          @Override
          public Optional<Runnable> observe(long sinceSeq, Consumer<WireFrame> observer, Runnable onDetached) {
            return Optional.empty();
          }

          @Override
          public void onRetire(Runnable listener) {}

          @Override
          public void retire() {}
        };

    /** Projects and journals an event, then fans it out to every attached observer. */
    void publish(AgentEvent event);

    /**
     * How many LIVE observers are attached. Must reflect EVICTION — an observer whose delivery
     * throws is a dead socket and must stop being counted, or the posture-graded zero-observer park
     * never fires and a Watch run proceeds unwatched.
     */
    int observerCount();

    /**
     * Installs the act-on-the-run primer, pushed before every replay (§6.1). A SUPPLIER because the
     * snapshot must be current at attach time; a value stamped at run start would prime a
     * reattacher with a state the run left thousands of frames ago.
     */
    void setSnapshotSupplier(Supplier<AgentEvent.StateSnapshot> supplier);

    /**
     * Attaches an observer, replaying the retained frames after {@code sinceSeq} atomically with
     * the registration, and returns an unsubscribe handle. {@code onDetached} fires when that
     * observer is retired (including delivery failure/overflow), independently of run retirement.
     *
     * <p>Empty means the cursor fell outside the retained window and NOTHING was registered — the
     * caller must re-attach from 0 rather than hold a silently dead stream. {@code sinceSeq == 0}
     * always succeeds, so it is the guaranteed path.
     */
    Optional<Runnable> observe(long sinceSeq, Consumer<WireFrame> observer, Runnable onDetached);

    /** Registers a callback fired once when the run is retired. */
    void onRetire(Runnable listener);

    /**
     * The terminal transition: refuse further publishes, close attached observers, keep the replay
     * readable for a linger window, then drop it. One call, because splitting it across two sites
     * is how "retired-but-readable" and "gone" got conflated in the first place (§2).
     */
    void retire();
  }
}
