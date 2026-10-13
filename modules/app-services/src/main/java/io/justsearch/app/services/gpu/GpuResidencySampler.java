/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

import io.justsearch.app.api.chat.PressureSignal;
import io.justsearch.app.api.gpu.GpuMemoryReader;
import io.justsearch.app.api.gpu.GpuMemorySample;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.gpu.UserContextReader;
import io.justsearch.app.api.gpu.UserContextSample;
import io.justsearch.core.scheduling.BulkWorkGate;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Engine's GPU residency sampler (Spec E section 3.2, step E3). Every {@link #PERIOD_MS} it
 * takes one reading from the Engine's one {@link GpuMemoryReader}, derives our use and external use,
 * advances the {@link ExternalPressureMachine} and publishes the result:
 *
 * <ul>
 *   <li>{@link BulkWorkGate#setExternalPressure} on each edge, on every profile, so no new bulk GPU
 *       batch starts while another application needs the memory;
 *   <li>{@link PressureSignal#EXTERNAL_MEMORY} or {@link PressureSignal#NONE} to the chat controller
 *       on each change (section 3.4);
 *   <li>{@link #latest()} for status, and {@link #statusReader()} for the operating gpu block.
 * </ul>
 *
 * <p><b>Only external use counts, and only from per-process PDH figures</b> (CH11): a tick is known
 * for pressure only when the reading is known, its source is PDH and per-process figures are
 * present. NVML-only and UNKNOWN readings freeze the machine. External use is the adapter's used
 * memory minus the dedicated use of our processes (the Engine and its descendants, every
 * llama-server child included), so our own oversubscription cannot raise it. Memory safety for our
 * own use is the profiles' capped arenas (step E7), not this sampler.
 *
 * <p><b>Game detection</b> (section 4, step E4) is polled on the same tick from the {@link
 * UserContextReader}: the {@link GameDetector} rule over the foreground reading and this tick's PDH
 * figures, with our same process set, then its debounce. Each edge sets {@link
 * BulkWorkGate#setGame}; the chat controller hears {@link PressureSignal#GAME}, which outranks {@link
 * PressureSignal#EXTERNAL_MEMORY}. The first poll decides at once ({@link GameDetector#seed}), and
 * {@link #bootSample()} takes it synchronously before the chat controller's first start (CH7). While
 * detection is turned off ({@code justsearch.gpu.game_detection = off}) no game is detected and a
 * detected one clears at once.
 *
 * <p>Owns the readers: {@link #close()} cancels the schedule and closes them. The scheduler is the
 * caller's (production: one Engine daemon thread; tests: a manual one).
 */
public final class GpuResidencySampler implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(GpuResidencySampler.class);

  /** Sampler period (Spec E section 0). */
  public static final long PERIOD_MS = 2_000L;

  /**
   * One sampler tick's result.
   *
   * @param sample the reading; null before the first tick or when the reader failed
   * @param ourDedicatedBytes PDH dedicated use summed over our processes; null without PDH figures
   * @param ourSharedBytes PDH shared use summed over our processes; null without PDH figures
   * @param externalBytes adapter used minus our dedicated use, floored at zero; null without PDH
   * @param externalPressure the machine's state after this tick
   * @param game whether a game is detected after this tick (step E4)
   */
  public record ResidencyObservation(
      GpuMemorySample sample,
      Long ourDedicatedBytes,
      Long ourSharedBytes,
      Long externalBytes,
      boolean externalPressure,
      boolean game,
      Instant observedAt) {}

  /** Status reads memory and attribution from the same immutable sampler observation. */
  public interface StatusReader extends GpuMemoryReader {
    ResidencyObservation observation();
  }

  private final GpuMemoryReader reader;
  private final UserContextReader userContext;
  private final BooleanSupplier gameDetectionEnabled;
  private final Supplier<Set<Long>> ourPids;
  private final BulkWorkGate gate;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final ExternalPressureMachine machine = new ExternalPressureMachine();
  private final GameDetector gameDetector = new GameDetector();

  private final Object publication = new Object();
  private Consumer<PressureSignal> pressureReceiver; // Guarded by publication.
  private boolean published; // Guarded by publication: the last external-pressure edge sent.
  private boolean gamePublished; // Guarded by publication: the last game edge sent.
  private boolean gameSeeded; // Guarded by publication: the first poll with detection on ran.
  private PressureSignal reported = PressureSignal.NONE; // Guarded by publication.
  private volatile ResidencyObservation latest;
  private ScheduledFuture<?> schedule; // Guarded by publication.
  private boolean closed; // Guarded by publication.

  /**
   * A sampler without game detection.
   *
   * @param reader the Engine's one GPU memory reader; this sampler owns it from here
   * @param ourPids our processes: the Engine JVM and its descendants
   * @param gate the Engine's bulk work gate
   * @param scheduler runs the ticks (production: the Engine's daemon executor)
   */
  public GpuResidencySampler(
      GpuMemoryReader reader,
      Supplier<Set<Long>> ourPids,
      BulkWorkGate gate,
      Clock clock,
      ScheduledExecutorService scheduler) {
    this(reader, null, () -> false, ourPids, gate, clock, scheduler);
  }

  /**
   * @param reader the Engine's one GPU memory reader; this sampler owns it from here
   * @param userContext the foreground reader for game detection; this sampler owns it; null for none
   * @param gameDetectionEnabled read on every tick: false while the user turned detection off
   * @param ourPids our processes: the Engine JVM and its descendants
   * @param gate the Engine's bulk work gate
   * @param scheduler runs the ticks (production: the Engine's daemon executor)
   */
  public GpuResidencySampler(
      GpuMemoryReader reader,
      UserContextReader userContext,
      BooleanSupplier gameDetectionEnabled,
      Supplier<Set<Long>> ourPids,
      BulkWorkGate gate,
      Clock clock,
      ScheduledExecutorService scheduler) {
    this.reader = Objects.requireNonNull(reader, "reader");
    this.userContext = userContext;
    this.gameDetectionEnabled = Objects.requireNonNull(gameDetectionEnabled, "gameDetectionEnabled");
    this.ourPids = Objects.requireNonNull(ourPids, "ourPids");
    this.gate = Objects.requireNonNull(gate, "gate");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
  }

  /** The Engine process and every process it launched, as the OS reports them now. */
  public static Set<Long> engineAndDescendants() {
    ProcessHandle self = ProcessHandle.current();
    Set<Long> pids = new java.util.HashSet<>();
    pids.add(self.pid());
    self.descendants().forEach(p -> pids.add(p.pid()));
    return pids;
  }

  /**
   * The boot sample (section 4.4, CH7): one tick on the calling thread, before {@link #start()} and
   * before the chat controller's first start, so a game already running is detected (and reported to
   * a receiver bound later) before chat first loads.
   */
  public void bootSample() {
    tick();
  }

  /** Schedules the ticks with a {@link #PERIOD_MS} fixed delay, the first at once. Idempotent. */
  public void start() {
    synchronized (publication) {
      if (closed || schedule != null) return;
      schedule = scheduler.scheduleWithFixedDelay(this::tick, 0, PERIOD_MS, TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Binds the chat controller's pressure input (section 3.4). The last bound receiver wins; a raised
   * pressure or a detected game is sent to it at once, so a controller built after the raise still
   * learns of it.
   */
  public void bindPressureReceiver(Consumer<PressureSignal> receiver) {
    Objects.requireNonNull(receiver, "receiver");
    synchronized (publication) {
      pressureReceiver = receiver;
      if (reported != PressureSignal.NONE) deliver(receiver, reported);
    }
  }

  /** The latest tick's observation; null before the first tick. */
  public ResidencyObservation latest() {
    return latest;
  }

  /**
   * A non-owning {@link GpuMemoryReader} over {@link #latest()} for the operating gpu block (section
   * 3.2: status reads the sampler, never the native counters a second time). Its {@code sampleNow()}
   * returns the latest tick's reading, or null before the first tick (the projection reports gpu
   * unknown); its {@code close()} does nothing, because the Engine closes the real reader.
   */
  public GpuMemoryReader statusReader() {
    return new StatusReader() {
      @Override
      public ResidencyObservation observation() {
        return latest;
      }

      @Override
      public GpuMemorySample sampleNow() {
        ResidencyObservation observation = latest;
        return observation == null ? null : observation.sample();
      }

      @Override
      public void close() {
        // Not the owner: the Engine closes the reader with this sampler.
      }
    };
  }

  private void tick() {
    try {
      observe();
    } catch (RuntimeException | LinkageError e) {
      // A failed tick must not cancel the schedule; the next one samples again.
      log.debug("GPU residency tick failed", e);
    }
  }

  private void observe() {
    GpuMemorySample sample;
    try {
      sample = reader.sampleNow();
    } catch (RuntimeException | LinkageError e) {
      log.debug("GPU memory reader failed; this tick is unknown", e);
      sample = null;
    }
    boolean detectionOn = userContext != null && detectionEnabled();
    UserContextSample context = detectionOn ? readContext() : null;
    Set<Long> ours = pids();
    Instant now = clock.instant();
    ResidencyObservation observation;
    synchronized (publication) {
      if (closed) return;
      Long dedicatedBytes = null;
      Long sharedBytes = null;
      Long externalBytes = null;
      boolean pressure;
      if (sample != null
          && sample.known()
          && sample.source() == GpuMemorySource.PDH
          && !sample.processes().isEmpty()) {
        long dedicated = 0;
        long shared = 0;
        for (Long pid : ours) {
          var memory = sample.process(pid);
          if (memory.isPresent()) {
            dedicated += memory.get().dedicatedBytes();
            shared += memory.get().sharedBytes();
          }
        }
        long external = Math.max(0L, sample.adapterUsedBytes().getAsLong() - dedicated);
        pressure = machine.advanceKnown(sample.adapterFreeBytes().getAsLong(), external, now);
        dedicatedBytes = dedicated;
        sharedBytes = shared;
        externalBytes = external;
      } else {
        pressure = machine.advanceUnknown(now);
      }
      boolean game;
      if (!detectionOn) {
        game = gameDetector.reset();
        gameSeeded = false;
      } else {
        boolean looksLikeGame = GameDetector.looksLikeGame(context, sample, ours);
        game = gameSeeded ? gameDetector.advance(looksLikeGame) : gameDetector.seed(looksLikeGame);
        gameSeeded = true;
      }
      observation =
          new ResidencyObservation(
              sample, dedicatedBytes, sharedBytes, externalBytes, pressure, game, now);
      latest = observation;
      if (pressure != published) {
        published = pressure;
        gate.setExternalPressure(published);
        log.info(
            "External GPU memory pressure {} (free {} bytes, external {} bytes)",
            published ? "raised: bulk GPU work is held" : "cleared",
            sample != null && sample.known() ? sample.adapterFreeBytes().getAsLong() : null,
            externalBytes);
      }
      if (game != gamePublished) {
        gamePublished = game;
        gate.setGame(game);
        log.info(
            "Game {} (foreground {}, notification state {}, game mode {})",
            game ? "detected: bulk GPU work is held" : "no longer detected",
            context == null ? null : context.foregroundPid(),
            context == null ? null : context.notificationState(),
            context == null ? null : context.gameMode());
      }
      PressureSignal signal =
          game
              ? PressureSignal.GAME
              : pressure ? PressureSignal.EXTERNAL_MEMORY : PressureSignal.NONE;
      if (signal != reported) {
        reported = signal;
        Consumer<PressureSignal> receiver = pressureReceiver;
        if (receiver != null) deliver(receiver, signal);
      }
    }
  }

  private boolean detectionEnabled() {
    try {
      return gameDetectionEnabled.getAsBoolean();
    } catch (RuntimeException e) {
      log.debug("Reading the game detection setting failed; detection stays on", e);
      return true;
    }
  }

  private UserContextSample readContext() {
    try {
      return userContext.sampleNow();
    } catch (RuntimeException | LinkageError e) {
      // Never a game on a failed read (section 4.3).
      log.debug("Foreground reading failed; this tick sees no game", e);
      return null;
    }
  }

  private Set<Long> pids() {
    try {
      return ourPids.get();
    } catch (RuntimeException e) {
      log.debug("Listing the Engine's child processes failed; counting the Engine alone", e);
      return Set.of(ProcessHandle.current().pid());
    }
  }

  private static void deliver(Consumer<PressureSignal> receiver, PressureSignal signal) {
    try {
      receiver.accept(signal);
    } catch (RuntimeException e) {
      log.warn("The chat controller rejected the GPU pressure report {}", signal, e);
    }
  }

  /**
   * Cancels the schedule, clears a raised pressure and a detected game (a sampler that no longer
   * reads cannot keep bulk work held) and closes the readers. Idempotent.
   */
  @Override
  public void close() {
    synchronized (publication) {
      if (closed) return;
      closed = true;
      if (schedule != null) schedule.cancel(false);
      if (published) {
        published = false;
        gate.setExternalPressure(false);
      }
      if (gamePublished) {
        gamePublished = false;
        gate.setGame(false);
      }
    }
    try {
      if (userContext != null) userContext.close();
    } finally {
      reader.close();
    }
  }
}
