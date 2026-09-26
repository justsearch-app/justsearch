/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream.run;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentEventPayloads;
import io.justsearch.agent.api.RunObservation;
import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@link RunObservation} backed by the run channel substrate — the concrete half of tempdoc 834
 * §1.3's "PROJECTION, not fork". This is what {@code RunEventHub} became: the same
 * never-both-never-neither delivery, bounded replay and evict-on-throw, on the substrate that
 * already had all of it.
 *
 * <p><strong>The projector is injected</strong> rather than hardcoded to
 * {@link AgentEventPayloads}. The WIRE payload is the base payload plus exactly one overlay — the
 * {@code tool_batch_proposed} gate-prediction, which needs collaborators that live in
 * {@code app-services} and cannot be reached from here. Passing the projector in keeps the journal
 * carrying what the wire carries, instead of quietly dropping the overlay and shipping a
 * gate-hint-less plan preview to every observer.
 */
public final class RunChannelObservation implements RunObservation {

  /** The shape id an agent run is journaled under when the caller does not name one. */
  public static final String AGENT_SHAPE_ID = "core.agent-run";

  private final RunChannelRegistry registry;
  private final Function<AgentEvent, WireFrame> projector;
  private final Clock clock;

  /** Projects with the canonical base payload only — no wire overlay. */
  public static Function<AgentEvent, WireFrame> canonicalProjector() {
    return event ->
        new WireFrame(
            AgentEventPayloads.name(event),
            AgentEventPayloads.withTrace(AgentEventPayloads.base(event), event.trace()));
  }

  public RunChannelObservation(RunChannelRegistry registry) {
    this(registry, canonicalProjector(), Clock.systemUTC());
  }

  public RunChannelObservation(
      RunChannelRegistry registry, Function<AgentEvent, WireFrame> projector, Clock clock) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.projector = Objects.requireNonNull(projector, "projector");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Handle open(String runId, String shapeId, String conversationId) {
    RunChannel channel =
        registry.open(
            new RunId(runId),
            new RunDescriptor(
                shapeId == null || shapeId.isBlank() ? AGENT_SHAPE_ID : shapeId,
                conversationId,
                clock.millis()),
            // A stepped run: it has approval / budget / context gates and MAY park.
            RunChannelPolicy.agent());
    return new ChannelHandle(channel, projector, registry);
  }

  private static final class ChannelHandle implements Handle {

    private final RunChannel channel;
    private final Function<AgentEvent, WireFrame> projector;
    private final RunChannelRegistry registry;

    private ChannelHandle(
        RunChannel channel,
        Function<AgentEvent, WireFrame> projector,
        RunChannelRegistry registry) {
      this.channel = channel;
      this.projector = projector;
      this.registry = registry;
    }

    @Override
    public void publish(AgentEvent event) {
      WireFrame frame = projector.apply(event);
      channel.publish(new RunFrame(frame.name(), frame.payload()));
    }

    @Override
    public int observerCount() {
      return channel.observerCount();
    }

    @Override
    public void setSnapshotSupplier(Supplier<AgentEvent.StateSnapshot> supplier) {
      if (!(channel instanceof SteppedRunChannel stepped)) {
        // Unreachable: open() always builds a parkable channel. Stated rather than assumed, since
        // the sealed hierarchy is what makes the one-shot case structurally snapshot-less (§6.4).
        return;
      }
      stepped.setSnapshotSupplier(
          () -> {
            AgentEvent.StateSnapshot snapshot = supplier.get();
            if (snapshot == null) {
              return null;
            }
            // Taking the snapshot is the one moment fresh session state is in hand, so the park is
            // refreshed here rather than pushed separately — two setters for one fact is how they
            // drift. Every consumer of park() (the §5 enumeration) reads the snapshot too.
            stepped.setPark(parkOf(snapshot.park()));
            return new RunStateSnapshot(AgentEventPayloads.base(snapshot));
          });
    }

    private static ParkState parkOf(AgentEvent.ParkSnapshot park) {
      if (park == null || park.kind() == null) {
        return null;
      }
      try {
        return new ParkState(
            ParkState.Kind.valueOf(park.kind().toUpperCase(java.util.Locale.ROOT)),
            park.sinceEpochMs(),
            park.detail());
      } catch (IllegalArgumentException unknownKind) {
        // A park kind this substrate does not model is still a park; naming it UNOBSERVED would be
        // a lie, and dropping it would report a stopped run as running. Keep the detail, and say so.
        return new ParkState(ParkState.Kind.UNOBSERVED, park.sinceEpochMs(), park.kind());
      }
    }

    @Override
    public Optional<Runnable> observe(long sinceSeq, Consumer<WireFrame> observer, Runnable onDetached) {
      Consumer<SseEnvelope> listener =
          envelope -> {
            RunFrame frame =
                RunFrame.from(envelope)
                    .orElseThrow(
                        () ->
                            new IllegalStateException(
                                "A run channel carried a non-run frame: " + envelope.payload()));
            observer.accept(new WireFrame(frame.event(), frame.data()));
          };
      // Validate/register before the primer, so a blocked primer cannot open a replay gap.
      // A rejected cursor emits nothing; the caller's zero-tail fallback primes exactly once.
      return channel.observe(listener, sinceSeq,
          () -> channel.snapshot().ifPresent(snapshot ->
              observer.accept(new WireFrame(SNAPSHOT_EVENT, snapshot.fields()))),
          subscription -> subscription.onRetire(onDetached))
          .map(subscription -> subscription::unsubscribe);
    }

    @Override
    public void onRetire(Runnable listener) {
      channel.onRetire(listener);
    }

    @Override
    public void retire() {
      registry.retire(channel.id());
    }
  }

  /** The primer's event name — the same one the agent vocabulary already uses. */
  private static final String SNAPSHOT_EVENT = "state_snapshot";
}
