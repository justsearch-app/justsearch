/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream.run;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The live-run directory: what §1.2.3 identified as the ONE structural difference between a run
 * channel and the 18 process-lifetime catalog channels — lifetime and cardinality. A run channel is
 * per-instance, N-at-a-time, and must be created, bounded and DESTROYED.
 *
 * <p><strong>{@code retire} owns the whole terminal transition</strong> (§2). Today that transition
 * is two sites that disagree — {@code AgentLoopService} removes the session from its registry and
 * then immediately closes the hub, while attach refuses an already-removed session — so a reattach
 * arriving in the gap sees "gone" for a run whose answer just landed. Here it is one method: refuse
 * further publishes, keep the ring READABLE for {@code linger}, then drop the ring and keep a
 * tombstone. That is what lets §1.6's 404 say {@code retired} rather than {@code unknown}, which is
 * the difference between "this run is over, read the record" and "I have never heard of it".
 *
 * <p><strong>Cardinality.</strong> Capped at {@value #MAX_CHANNELS} channels. Retired-and-lingering
 * channels are dropped oldest-first to make room; a LIVE run is never dropped, so the 33rd
 * concurrent live run is REFUSED with a typed error rather than silently evicting someone's
 * in-flight answer. {@code AgentSessionRegistry} records the real-world expectation in code ("for a
 * desktop deployment with 0–1 concurrent sessions this is irrelevant"), so the cap is generous by
 * roughly two orders of magnitude — it exists to make the failure legible, not to be reached.
 *
 * <p>Thread-safe: every mutation is under this monitor. Nothing here calls out to a socket, so the
 * monitor is never held across a blocking write.
 */
public final class RunChannelRegistry {

  /** Maximum channels held at once (live + lingering), tempdoc 834 §2. */
  public static final int MAX_CHANNELS = 32;

  /** How many retired runs stay answerable as {@code retired} after their ring is dropped. */
  public static final int MAX_TOMBSTONES = 256;

  /** The linger a caller gets when it does not name one (§2's 60 s). */
  public static final Duration DEFAULT_LINGER = Duration.ofSeconds(60);

  /** What the registry knows about a run id — the three cases §1.6's 404 body distinguishes. */
  public enum Lookup {
    /** In flight, or retired and still inside its linger: observable. */
    LIVE,
    /** Terminal and past its linger: the record is the place to read it. */
    RETIRED,
    /** Never opened here (or long enough ago that even the tombstone is gone). */
    UNKNOWN
  }

  private final Clock clock;
  private final Map<RunId, Entry> entries = new LinkedHashMap<>();
  private final Map<RunId, RunDescriptor> tombstones =
      new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<RunId, RunDescriptor> eldest) {
          return size() > MAX_TOMBSTONES;
        }
      };

  public RunChannelRegistry() {
    this(Clock.systemUTC());
  }

  public RunChannelRegistry(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Opens a channel for a run. {@link RunChannelPolicy#parkable()} selects the subtype, which is
   * where §3.4's ask-survival guard becomes structural.
   *
   * @throws RunChannelCapacityExceededException when {@value #MAX_CHANNELS} channels are already
   *     held and none of them can be dropped without killing a live run
   * @throws IllegalStateException when the id is already open
   */
  public synchronized RunChannel open(RunId id, RunDescriptor descriptor, RunChannelPolicy policy) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(policy, "policy");
    prune();
    Entry existing = entries.get(id);
    if (existing != null && !existing.base.retired()) {
      throw new IllegalStateException("Run " + id.value() + " is already open");
    }
    makeRoom(id);
    Entry entry;
    if (policy.parkable()) {
      SteppedRunChannelImpl stepped = new SteppedRunChannelImpl(id, descriptor, policy, clock);
      entry = new Entry(stepped, stepped);
    } else {
      OneShotRunChannelImpl oneShot = new OneShotRunChannelImpl(id, descriptor, policy, clock);
      entry = new Entry(oneShot, oneShot);
    }
    entries.put(id, entry);
    tombstones.remove(id);
    return entry.channel;
  }

  /** The channel for {@code id} while it is observable (live, or retired inside its linger). */
  public synchronized Optional<RunChannel> find(RunId id) {
    if (id == null) {
      return Optional.empty();
    }
    prune();
    Entry entry = entries.get(id);
    return entry == null ? Optional.empty() : Optional.of(entry.channel);
  }

  /** Every run still executing, newest first (§3.5 — a list, never collapsed by conversation). */
  public synchronized List<RunChannel> live() {
    prune();
    List<RunChannel> out = new ArrayList<>();
    for (Entry entry : entries.values()) {
      if (!entry.base.retired()) {
        out.add(entry.channel);
      }
    }
    out.sort(
        (a, b) ->
            Long.compare(b.descriptor().startedAtEpochMs(), a.descriptor().startedAtEpochMs()));
    return List.copyOf(out);
  }

  /**
   * What the registry can honestly say about {@code id}. Callers answering §1.6's 404 use this to
   * pick {@code reason}: a run past its linger is {@link Lookup#RETIRED} ("this run is over"), one
   * that was never here is {@link Lookup#UNKNOWN}.
   */
  public synchronized Lookup lookup(RunId id) {
    if (id == null) {
      return Lookup.UNKNOWN;
    }
    prune();
    if (entries.containsKey(id)) {
      return Lookup.LIVE;
    }
    return tombstones.containsKey(id) ? Lookup.RETIRED : Lookup.UNKNOWN;
  }

  /** The descriptor of a retired run, for the 404 body's {@code recordHint}. */
  public synchronized Optional<RunDescriptor> retiredDescriptor(RunId id) {
    if (id == null) {
      return Optional.empty();
    }
    prune();
    return Optional.ofNullable(tombstones.get(id));
  }

  /** Retires with the default {@value #MAX_TOMBSTONES}-bounded tombstone and §2's 60 s linger. */
  public void retire(RunId id) {
    retire(id, DEFAULT_LINGER);
  }

  /**
   * The terminal transition, in one place: refuse further publishes, fire the retire listeners so
   * attached writers close, and keep the ring readable for {@code linger} so a tab reloading as the
   * answer lands still replays it. Idempotent, and a no-op for an unknown id.
   */
  public void retire(RunId id, Duration linger) {
    Objects.requireNonNull(linger, "linger");
    if (linger.isNegative()) {
      throw new IllegalArgumentException("linger must not be negative, got " + linger);
    }
    List<Runnable> listeners = List.of();
    synchronized (this) {
      Entry entry = id == null ? null : entries.get(id);
      if (entry == null) return;
      if (!entry.base.retired()) {
        entry.retiredAtMs = clock.millis();
        entry.lingerMs = linger.toMillis();
        listeners = entry.base.markRetired();
      }
      prune();
    }
    AbstractRunChannel.notifyRetired(listeners);
  }

  /** Total channels held (live + lingering) — the number {@value #MAX_CHANNELS} bounds. */
  public synchronized int size() {
    prune();
    return entries.size();
  }

  /** Drops every channel; for shutdown and for test isolation. */
  public void clear() {
    List<Runnable> listeners = new ArrayList<>();
    synchronized (this) {
      for (Entry entry : entries.values()) {
        listeners.addAll(entry.base.markRetired());
      }
      entries.clear();
      tombstones.clear();
    }
    AbstractRunChannel.notifyRetired(listeners);
  }

  /** Moves lingering channels past their window to tombstones. Callers hold the monitor. */
  private void prune() {
    long now = clock.millis();
    var expired = new ArrayList<RunId>();
    for (Map.Entry<RunId, Entry> e : entries.entrySet()) {
      Entry entry = e.getValue();
      if (entry.base.retired() && now - entry.retiredAtMs >= entry.lingerMs) {
        expired.add(e.getKey());
      }
    }
    for (RunId id : expired) {
      Entry entry = entries.remove(id);
      tombstones.put(id, entry.base.descriptor());
    }
  }

  /**
   * Frees a slot for {@code incoming} when the cap is reached, dropping retired-and-lingering
   * channels OLDEST-FIRST. A live run is never dropped — refusing the newcomer is the honest
   * failure; evicting someone's in-flight answer to make room is not.
   */
  private void makeRoom(RunId incoming) {
    if (entries.size() < MAX_CHANNELS || entries.containsKey(incoming)) {
      return;
    }
    var lingering = new ArrayList<Map.Entry<RunId, Entry>>();
    for (Map.Entry<RunId, Entry> e : entries.entrySet()) {
      if (e.getValue().base.retired()) {
        lingering.add(e);
      }
    }
    lingering.sort((a, b) -> Long.compare(a.getValue().retiredAtMs, b.getValue().retiredAtMs));
    for (Map.Entry<RunId, Entry> e : lingering) {
      if (entries.size() < MAX_CHANNELS) {
        break;
      }
      entries.remove(e.getKey());
      tombstones.put(e.getKey(), e.getValue().base.descriptor());
    }
    if (entries.size() >= MAX_CHANNELS) {
      throw new RunChannelCapacityExceededException(MAX_CHANNELS);
    }
  }

  /**
   * One held channel, in its two views: {@code channel} is what callers get, {@code base} is the
   * package-private half that owns {@code markRetired}. Same instance — {@link AbstractRunChannel}
   * cannot declare {@code implements RunChannel} without joining the sealed permits clause.
   */
  private static final class Entry {
    private final AbstractRunChannel base;
    private final RunChannel channel;
    private long retiredAtMs;
    private long lingerMs;

    private Entry(AbstractRunChannel base, RunChannel channel) {
      this.base = base;
      this.channel = channel;
    }
  }
}
