/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The owner facts the Engine job's witness reads to classify a member's exit (task b6d536 b101).
 *
 * <p>Owners publish here what they also write to the ledger: that they own a process, the stop
 * intent they recorded for it, the class and reason of the exit they recorded, and the helper
 * windows they opened. The witness gives a member's exit the class and reason of the intent that
 * preceded it; only an exit with no recorded intent is {@code UNEXPECTED}, with a reason saying so.
 * Membership alone still never supplies intent. Bounded: the oldest entries are dropped first.
 */
public final class ProcessIntents {
  /** A classified exit: its class, reason and what the class rests on. */
  public record Resolution(String exitClass, String reason, String basis) {}

  /** An exit for which no owner recorded an intent. */
  public static final String NO_INTENT = "no recorded intent";

  private static final int MAX_PROCESSES = 4096;
  private static final int MAX_WINDOWS = 256;

  private record Key(long pid, String birth) {}

  private static final class Owned {
    private String intent;
    private Resolution outcome;
  }

  private static final class Window {
    private final String image;
    private final String reason;
    private final Instant start;
    private final Instant deadline;
    private Instant closed;

    private Window(String image, String reason, Instant start, Instant deadline) {
      this.image = image;
      this.reason = reason;
      this.start = start;
      this.deadline = deadline;
    }

    private boolean covers(String executable, Instant born, Instant exited) {
      if (executable == null || born == null) return false;
      String name = executable.substring(Math.max(executable.lastIndexOf('\\'), executable.lastIndexOf('/')) + 1);
      if (!name.toLowerCase(Locale.ROOT).equals(image.toLowerCase(Locale.ROOT))) return false;
      if (born.isBefore(start) || born.isAfter(deadline) || (closed != null && born.isAfter(closed))) return false;
      return exited == null || (!exited.isAfter(deadline) && (closed == null || !exited.isAfter(closed)));
    }
  }

  private static final Map<Key, Owned> OWNED = new LinkedHashMap<>() {
    @Override protected boolean removeEldestEntry(Map.Entry<Key, Owned> eldest) {
      return size() > MAX_PROCESSES;
    }
  };
  private static final Map<Key, Resolution> WITNESSED = new LinkedHashMap<>() {
    @Override protected boolean removeEldestEntry(Map.Entry<Key, Resolution> eldest) {
      return size() > MAX_PROCESSES;
    }
  };
  private static final Map<String, Window> WINDOWS = new LinkedHashMap<>() {
    @Override protected boolean removeEldestEntry(Map.Entry<String, Window> eldest) {
      return size() > MAX_WINDOWS;
    }
  };

  private ProcessIntents() {}

  /** An owner holds this identity and will record its exit. */
  public static synchronized void owned(long pid, String birth) {
    if (birth != null) OWNED.computeIfAbsent(new Key(pid, birth), k -> new Owned());
  }

  /** The owner recorded a stop intent for this identity. */
  public static synchronized void intent(long pid, String birth, String reason) {
    if (birth != null) OWNED.computeIfAbsent(new Key(pid, birth), k -> new Owned()).intent = reason;
  }

  /** The owner recorded this identity's exit with this class and reason. */
  public static synchronized void outcome(long pid, String birth, String exitClass, String reason) {
    if (birth == null) return;
    OWNED.computeIfAbsent(new Key(pid, birth), k -> new Owned()).outcome =
        new Resolution(exitClass, reason, "owner exit record");
  }

  /** An owner window opened: launches of {@code image} within it are intended. */
  public static synchronized void windowOpened(String intentId, String image, String reason,
      Instant start, Instant deadline) {
    WINDOWS.put(intentId, new Window(image, reason, start, deadline));
  }

  /** The owner window ended. */
  public static synchronized void windowClosed(String intentId, Instant closedAt) {
    Window window = WINDOWS.get(intentId);
    if (window != null) window.closed = closedAt;
  }

  /**
   * Classifies a witnessed exit. Empty while the owner of an owned identity has not recorded its
   * exit and {@code ownerWaitOver} is false: the witness asks again later.
   */
  public static synchronized Optional<Resolution> resolve(long pid, String birth, String executable,
      Instant born, Instant exited, boolean ownerWaitOver) {
    Optional<Resolution> owner = ownerOf(pid, birth, ownerWaitOver);
    if (owner == null) return Optional.empty();
    Resolution resolution = owner.orElseGet(() -> WINDOWS.values().stream()
        .filter(w -> w.covers(executable, born, exited)).reduce((a, b) -> b)
        .map(w -> new Resolution("REQUESTED", w.reason, "owner window"))
        .orElse(new Resolution("UNEXPECTED", NO_INTENT, "none")));
    if (birth != null) WITNESSED.put(new Key(pid, birth), resolution);
    return Optional.of(resolution);
  }

  /**
   * A console host follows its client: the client's resolved exit, once known. Empty while the
   * client's owner may still record it and {@code ownerWaitOver} is false.
   */
  public static synchronized Optional<Resolution> resolveConsole(long clientPid, String clientBirth,
      boolean ownerWaitOver) {
    Optional<Resolution> owner = ownerOf(clientPid, clientBirth, ownerWaitOver);
    if (owner == null) return Optional.empty();
    Resolution client = owner.orElseGet(() -> clientBirth == null ? null
        : WITNESSED.get(new Key(clientPid, clientBirth)));
    if (client == null) {
      return ownerWaitOver ? Optional.of(new Resolution("UNEXPECTED",
          NO_INTENT + " for the console client", "none")) : Optional.empty();
    }
    return Optional.of(new Resolution(client.exitClass(), client.reason(), "console client"));
  }

  /** Null: still waiting for the owner. Empty: no owner outcome or intent applies. */
  private static Optional<Resolution> ownerOf(long pid, String birth, boolean ownerWaitOver) {
    Owned owned = birth == null ? null : OWNED.get(new Key(pid, birth));
    if (owned == null) return Optional.empty();
    if (owned.outcome != null) return Optional.of(owned.outcome);
    if (!ownerWaitOver) return null;
    return Optional.of(owned.intent != null
        ? new Resolution("REQUESTED", owned.intent, "owner intent")
        : new Resolution("UNEXPECTED", "owner recorded no exit and no stop intent", "none"));
  }
}
