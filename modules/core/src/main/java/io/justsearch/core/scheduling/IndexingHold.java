/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.scheduling;

import java.time.Clock;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.time.ZoneId;
import java.util.function.Supplier;

/**
 * User indexing hold, shared by every processing lane. Admission and closing admission are
 * serialized. A lease is an explicit acknowledgment: it is released only after permitted work
 * and its publication finish. Discovery, deletions and transition handshakes need no lease.
 *
 * <p>"Read waiting files now" is an in-memory release of the reading rules (hours, battery) for the
 * file-reading loop only: {@link #tryAcquireIngestion()} and {@link #ingestionHeld()} honour it,
 * while {@link #held()}, {@link #tryAcquire()} and {@link #acquire()} keep every other lane held.
 * It ends when the loop's poll under it claims nothing, on a manual pause or settings recovery, on
 * an accepted change of the reading rules, when a rule reason that did not hold at its start begins
 * to hold, when its hold episode ends (no rule holds any more, observed or not), and with the
 * process. An ended release never revives.
 */
public final class IndexingHold {
  public enum State { RUNNING, PAUSING, PAUSED }
  public record Snapshot(State state, String reason, Long untilEpochMs, int waiting,
      List<String> reasons, boolean released) {
    public Snapshot { reasons = List.copyOf(reasons); }
    public Snapshot(State state, String reason, Long untilEpochMs, int waiting, List<String> reasons) {
      this(state, reason, untilEpochMs, waiting, reasons, false);
    }
  }
  public record LocalMinute(int weekday, int minute) {}

  /** What asking to read the waiting files did. */
  public enum ReadNow { STARTED, JOINED, NOTHING_WAITING, NOT_HELD }

  /** The outcome and, for NOT_HELD, the manual reason in effect (null when no rule holds). */
  public record ReadNowResult(ReadNow outcome, String reason) {}

  private static final List<String> RULE_REASONS = List.of("SCHEDULE", "ON_BATTERY");

  /** The active release: its identity, the rule reasons it covers and when its episode ends. */
  private static final class Release {
    private final List<String> covered;
    /** When reading hours open, if only the schedule holds; battery changes are reported. */
    private Long episodeEndsAtMs;
    private Release(List<String> covered) { this.covered = List.copyOf(covered); }
  }
  private IndexingSchedule schedule;
  private boolean pauseOnBattery;
  private boolean onBattery;
  private Supplier<LocalMinute> localTime;

  /** Uses a single current OS-local snapshot. Called only for an enabled schedule. */
  public synchronized void setLocalTime(Supplier<LocalMinute> source) {
    localTime = Objects.requireNonNull(source, "source");
  }

  public void setPolicy(boolean paused, Long until, String pauseReason,
      IndexingSchedule hours, boolean battery) {
    synchronized (releasedClaim) {
      synchronized (this) {
        // An accepted change of the reading rules ends a release; an unchanged save does not.
        if (!Objects.equals(schedule, hours) || pauseOnBattery != battery) release = null;
        if (paused) pause(until, pauseReason);
        else resume();
        schedule = hours;
        pauseOnBattery = battery;
        notifyAll();
      }
    }
  }

  public synchronized boolean pauseOnBattery() { return pauseOnBattery; }

  public void setOnBattery(boolean battery) {
    synchronized (releasedClaim) {
      synchronized (this) {
        onBattery = battery;
        effectiveReasons();
        notifyAll();
      }
    }
  }

  private final Clock clock;
  private boolean requested;
  private String reason;
  private Long untilEpochMs;
  private int active;
  private int waiting;
  private Release release;
  /**
   * Held while a claim of file jobs is made under a release, and taken before this monitor by every
   * explicit revocation (Pause, recovery, a rule edit, battery starting): revocation waits for that
   * claim, and none starts after it. Observers never take it; a claim without a release neither.
   */
  private final Object releasedClaim = new Object();

  public IndexingHold() { this(Clock.systemUTC()); }
  public IndexingHold(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
    localTime = () -> {
      var now = clock.instant().atZone(ZoneId.systemDefault());
      return new LocalMinute(now.getDayOfWeek().getValue(), now.getHour() * 60 + now.getMinute());
    };
  }

  public void pause(Long until, String pauseReason) {
    if (!"USER_PAUSED".equals(pauseReason) && !"SETTINGS_RECOVERY".equals(pauseReason)) {
      throw new IllegalArgumentException("Unknown indexing pause reason");
    }
    synchronized (releasedClaim) {
      synchronized (this) {
        requested = true;
        reason = pauseReason;
        untilEpochMs = until;
        release = null;
        notifyAll();
      }
    }
  }

  public synchronized void resume() {
    requested = false;
    reason = null;
    untilEpochMs = null;
    notifyAll();
  }

  public synchronized boolean held() {
    return !effectiveReasons().isEmpty();
  }

  /** Null means admission was refused; no processing may start in that case. */
  public synchronized Lease tryAcquire() {
    if (held()) return null;
    active++;
    return new Lease();
  }

  /** Interruptible wait used by explicitly accepted work; each wake rechecks wall-clock expiry. */
  public Lease acquire() throws InterruptedException {
    return acquire(() -> true);
  }

  /** Returns null if the caller withdraws admission while waiting; policy stays unchanged. */
  public synchronized Lease acquire(java.util.function.BooleanSupplier admitted) throws InterruptedException {
    Objects.requireNonNull(admitted, "admitted");
    waiting++;
    try {
      while (admitted.getAsBoolean()) {
        if (!held()) {
          active++;
          return new Lease();
        }
        wait(250L);
      }
      return null;
    } finally {
      waiting--;
    }
  }

  /**
   * File reading's admission: as {@link #tryAcquire()}, except that an active release lets it
   * through the reading rules. Every other lane keeps using {@link #tryAcquire()}.
   */
  public synchronized Lease tryAcquireIngestion() {
    if (ingestionHeld()) return null;
    active++;
    return new Lease();
  }

  /** Whether file reading is held: held, and no release covers the reasons that hold it. */
  public synchronized boolean ingestionHeld() {
    return held() && release == null;
  }

  /** What one admitted claim of file jobs took, and the release it was made under (or null). */
  public record IngestionClaim<T>(T claimed, Object release) {}

  /**
   * The file-reading loop's claim. Under a release it is serialized with revocation (Pause, settings
   * recovery, a rule edit, battery starting): revocation waits for a claim in progress and no claim
   * starts once it completed. Null when file reading is held; the claim is then not made.
   */
  public <T> IngestionClaim<T> claimIngestion(Supplier<T> claim) {
    Objects.requireNonNull(claim, "claim");
    Object under;
    synchronized (this) {
      if (ingestionHeld()) return null;
      under = release;
    }
    if (under == null) return new IngestionClaim<>(claim.get(), null);
    synchronized (releasedClaim) {
      synchronized (this) {
        // Revoked (or ended) since the first look: the hold applies as without a release.
        if (ingestionHeld()) return null;
        under = release;
      }
      return new IngestionClaim<>(claim.get(), under);
    }
  }

  /**
   * Asks to read the files the reading rules hold back now. {@code filesWaiting} is the caller's
   * reading of the ready file jobs, taken outside this monitor. The hold is checked again here.
   */
  public synchronized ReadNowResult readWaitingNow(boolean filesWaiting) {
    var reasons = effectiveReasons();
    if (release != null) return new ReadNowResult(ReadNow.JOINED, null);
    if (requested) return new ReadNowResult(ReadNow.NOT_HELD, reason);
    var rules = reasons.stream().filter(RULE_REASONS::contains).toList();
    if (rules.isEmpty()) return new ReadNowResult(ReadNow.NOT_HELD, null);
    if (!filesWaiting) return new ReadNowResult(ReadNow.NOTHING_WAITING, null);
    release = new Release(rules);
    endFinishedRelease(reasons);
    notifyAll();
    return new ReadNowResult(ReadNow.STARTED, null);
  }

  /** The active release's identity, or null; handed back to {@link #endRelease(Object)}. */
  public synchronized Object activeRelease() {
    effectiveReasons();
    return release;
  }

  /** Ends the given release if it is still the active one: its poll claimed nothing. */
  public synchronized void endRelease(Object drained) {
    if (drained != null && release == drained) {
      release = null;
      notifyAll();
    }
  }

  /**
   * Evaluates the release's end conditions on every reading of the reasons, held or not: a manual
   * hold, an uncovered rule reason, or the end of its hold episode, including reading hours that
   * opened since the last reading. Once ended it is gone.
   */
  private void endFinishedRelease(List<String> reasons) {
    if (release == null) return;
    var rules = reasons.stream().filter(RULE_REASONS::contains).toList();
    long now = clock.millis();
    if (requested || rules.isEmpty() || !release.covered.containsAll(rules)
        || release.episodeEndsAtMs != null && now >= release.episodeEndsAtMs) {
      release = null;
      notifyAll();
      return;
    }
    release.episodeEndsAtMs = rules.contains("ON_BATTERY") ? null : hoursOpenAt(now);
  }

  /** The start of the next local minute the reading hours allow, counted from the current one. */
  private Long hoursOpenAt(long now) {
    var local = localTime.get();
    int weekday = local.weekday();
    int minute = local.minute();
    for (int ahead = 1; ahead <= 7 * 1440; ahead++) {
      if (++minute == 1440) {
        minute = 0;
        weekday = weekday == 7 ? 1 : weekday + 1;
      }
      if (schedule.allows(weekday, minute)) return now - Math.floorMod(now, 60_000L) + ahead * 60_000L;
    }
    return null;
  }

  public synchronized Snapshot snapshot() {
    var reasons = effectiveReasons();
    if (reasons.isEmpty()) return new Snapshot(State.RUNNING, null, null, waiting, reasons, false);
    return new Snapshot(active == 0 ? State.PAUSED : State.PAUSING, reasons.getFirst(),
        requested ? untilEpochMs : null, waiting, reasons, release != null);
  }

  private List<String> effectiveReasons() {
    if (requested && untilEpochMs != null && clock.millis() >= untilEpochMs) resume();
    var reasons = new ArrayList<String>();
    if (requested) reasons.add(reason);
    if (schedule != null && schedule.enabled()) {
      var now = localTime.get();
      if (!schedule.allows(now.weekday(), now.minute())) reasons.add("SCHEDULE");
    }
    if (pauseOnBattery && onBattery) reasons.add("ON_BATTERY");
    endFinishedRelease(reasons);
    return reasons;
  }

  public final class Lease implements AutoCloseable {
    private boolean closed;
    private Lease() {}
    @Override public void close() {
      synchronized (IndexingHold.this) {
        if (!closed) {
          closed = true;
          active--;
          IndexingHold.this.notifyAll();
        }
      }
    }
  }
}
