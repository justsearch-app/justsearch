/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.app.api.OfflineProcessingOutcome.BlockReason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pauses automatic picture reading after repeated automatic passes that launched or relaunched
 * llama-server for picture reading and completed no document.
 *
 * <p>A pass counts when every document attempt was refused by generation ({@link Cause#REFUSED})
 * or when the vision entry itself failed ({@link Cause#ENTRY_FAILED}). A pass that completes or
 * fails at least one document resets the count; a pass that was cancelled, interrupted by activity,
 * deferred before entry or had nothing to do neither counts nor resets. After {@link #OPEN_AFTER}
 * counted passes in a row the breaker opens: no automatic pass starts, so llama-server is not
 * relaunched for vision. After the back-off (5 minutes, doubling to at most 60) one automatic pass
 * may probe. Progress closes the breaker and resets the back-off; another counted pass reopens it
 * with the next back-off.
 *
 * <p>Explicit passes are never gated here. {@link #close} is called for a person's explicit pass, a
 * committed settings apply and a person switching chat off or on. State is memory only: a new
 * Engine starts closed. Document states and retry counts are never touched.
 */
public final class AutomaticVduBreaker {
  private static final Logger LOG = LoggerFactory.getLogger(AutomaticVduBreaker.class);

  /** Counted passes in a row that open the breaker. */
  public static final int OPEN_AFTER = 3;
  static final Duration FIRST_BACKOFF = Duration.ofMinutes(5);
  static final Duration MAX_BACKOFF = Duration.ofMinutes(60);

  /** Why a pass counted. */
  public enum Cause {
    /**
     * Entered picture reading, but the Engine held generation closed (a settings change in progress
     * or settings recovery), so every document attempt was turned away.
     */
    REFUSED(VduCapabilityState.REASON_BREAKER_OPEN_REFUSED),
    /** Launched or relaunched llama-server for picture reading, but the vision entry failed. */
    ENTRY_FAILED(VduCapabilityState.REASON_BREAKER_OPEN_ENTRY_FAILED);

    private final String reasonCode;

    Cause(String reasonCode) {
      this.reasonCode = reasonCode;
    }

    public String reasonCode() {
      return reasonCode;
    }
  }

  /** The classification of one finished automatic pass. */
  public sealed interface Result {
    /** At least one document completed or failed (a failure spends an attempt). */
    record Progress() implements Result {}
    /** Counted: llama-server was (re)launched for picture reading and no document finished. */
    record Counted(Cause cause, String detail) implements Result {
      public Counted {
        Objects.requireNonNull(cause, "cause");
      }
    }
    /** Neither counts nor resets: cancelled, interrupted, deferred before entry, nothing to do. */
    record Neutral() implements Result {}
  }

  /** The breaker's state, for status. */
  public enum State { CLOSED, OPEN, PROBING }

  /** Status view; {@code retryInMillis} is 0 unless OPEN. */
  public record Snapshot(State state, int consecutive, Cause cause, String detail,
      long backoffMillis, long retryInMillis) {
    public boolean paused() {
      return state != State.CLOSED;
    }
  }

  private static final DateTimeFormatter NEXT_TRY = DateTimeFormatter.ofPattern("HH:mm");

  private final VduCapabilityState status;
  private final LongSupplier nanoTime;
  private final Clock wallClock;
  private State state = State.CLOSED;
  private int consecutive;
  private Cause cause;
  private String detail;
  private Duration backoff = FIRST_BACKOFF;
  private long probeAtNanos;
  /** Wall-clock time of the next probe, for status text only; timing uses {@link #nanoTime}. */
  private Instant probeAt;

  public AutomaticVduBreaker(VduCapabilityState status, LongSupplier nanoTime) {
    this(status, nanoTime, Clock.system(java.time.ZoneId.systemDefault()));
  }

  /** @param wallClock gives the local time of the next try shown in status */
  AutomaticVduBreaker(VduCapabilityState status, LongSupplier nanoTime, Clock wallClock) {
    this.status = Objects.requireNonNull(status, "status");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
  }

  /**
   * Asks to start one automatic pass. True while closed; when open and the back-off has elapsed,
   * true once (the half-open probe) until that pass ends. The caller must report every admitted
   * pass through {@link #passEnded}, also when starting it failed.
   */
  public synchronized boolean tryStartAutomatic() {
    return switch (state) {
      case CLOSED -> true;
      case PROBING -> false;
      case OPEN -> {
        if (nanoTime.getAsLong() - probeAtNanos < 0) yield false;
        state = State.PROBING;
        LOG.info("Automatic picture reading: one probe pass after a {} min pause", backoff.toMinutes());
        yield true;
      }
    };
  }

  /** Records how an admitted automatic pass ended. */
  public synchronized void passEnded(Result result) {
    Objects.requireNonNull(result, "result");
    switch (result) {
      case Result.Progress _ -> {
        if (state != State.CLOSED) LOG.info("Automatic picture reading resumed: the probe made progress");
        closeLocked();
      }
      case Result.Neutral _ -> {
        // A probe that could not tell either way leaves the breaker open; the elapsed back-off lets
        // the next eligible tick probe again.
        if (state == State.PROBING) openLocked(false, false);
      }
      case Result.Counted counted -> {
        cause = counted.cause();
        detail = counted.detail();
        if (state == State.PROBING) {
          backoff = min(backoff.multipliedBy(2), MAX_BACKOFF);
          openLocked(true, true);
        } else if (state == State.CLOSED && ++consecutive >= OPEN_AFTER) {
          backoff = FIRST_BACKOFF;
          openLocked(true, true);
        }
      }
    }
  }

  /** Closes at once with the count and back-off reset (a person's action or committed settings). */
  public synchronized void close(String event) {
    if (state != State.CLOSED || consecutive > 0) {
      LOG.info("Automatic picture reading breaker closed by {}", event);
    }
    closeLocked();
  }

  public synchronized Snapshot snapshot() {
    long retryIn = state == State.OPEN
        ? Math.max(0, TimeUnit.NANOSECONDS.toMillis(probeAtNanos - nanoTime.getAsLong())) : 0;
    return new Snapshot(state, consecutive, cause, detail, backoff.toMillis(), retryIn);
  }

  /**
   * Classifies a pass that returned an outcome. Progress is any completed or failed document; a
   * refusal is an entered pass that ended blocked {@code AI_OFFLINE} with nothing finished (the
   * batch returns that only when generation refused a document after entry).
   */
  public static Result classify(OfflineProcessingOutcome outcome, String detail) {
    if (outcome.processed() + outcome.failed() > 0) return new Result.Progress();
    if (outcome.blockedReason() == BlockReason.AI_OFFLINE) {
      return new Result.Counted(Cause.REFUSED, detail);
    }
    return new Result.Neutral();
  }

  /** Classifies a pass that ended with a failure: only a failed vision entry counts. */
  public static Result classify(Throwable failure, String detail) {
    var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
      if (current instanceof java.util.concurrent.CancellationException) return new Result.Neutral();
      if (VduBatchProcessor.isEntryFailure(current)) {
        return new Result.Counted(Cause.ENTRY_FAILED, detail);
      }
    }
    return new Result.Neutral();
  }

  /** @param restartBackoff whether a new back-off starts now (false keeps the elapsed deadline) */
  private void openLocked(boolean announce, boolean restartBackoff) {
    state = State.OPEN;
    if (restartBackoff) {
      probeAtNanos = nanoTime.getAsLong() + backoff.toNanos();
      probeAt = wallClock.instant().plus(backoff);
    }
    String text = describe();
    status.block(cause.reasonCode(), text);
    if (announce) LOG.warn("Automatic picture reading paused: {}", text);
  }

  private String describe() {
    String seen = detail == null || detail.isBlank() ? "" : " (" + detail + ")";
    String what = cause == Cause.REFUSED
        ? "the Engine held generation closed (a settings change in progress or settings recovery),"
            + " so every document was turned away"
        : "picture-reading mode could not be entered";
    String next = nanoTime.getAsLong() - probeAtNanos < 0
        ? "Next try at " + NEXT_TRY.format(probeAt.atZone(wallClock.getZone()))
        : "Next try when the computer is next idle";
    return "Automatic picture reading is paused after " + OPEN_AFTER
        + " automatic passes in a row made no progress: " + what + seen
        + ". " + next + "; a manual run, applying settings or switching chat off or on"
        + " resumes it now.";
  }

  private void closeLocked() {
    state = State.CLOSED;
    consecutive = 0;
    backoff = FIRST_BACKOFF;
    cause = null;
    detail = null;
    status.clear(VduCapabilityState.REASON_BREAKER_OPEN_REFUSED);
    status.clear(VduCapabilityState.REASON_BREAKER_OPEN_ENTRY_FAILED);
  }

  private static Duration min(Duration a, Duration b) {
    return a.compareTo(b) <= 0 ? a : b;
  }
}
