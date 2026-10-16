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
 *
 * <p>A window is its owner's: it covers a launch only when the launched process's creator (its
 * parent PID and birth, read natively) is the process that opened the window. Windows opened in
 * another process, such as the extraction child, reach this registry from the shared children
 * ledger ({@link #readWindowsFrom}). A window covers from its start to its recorded end; a window
 * with no recorded end covers until its owner's proven exit (its exit or owner-died record), and
 * without one for as long as its owner lives. Its deadline bounds a lease, not the coverage.
 * Cutoffs compare native 100 ns instants.
 *
 * <p>The helpers another process owns, such as the extraction child's, reach this registry the
 * same way: their owner receipts in the shared ledger apply to a member only when its PID, birth
 * and native creator are the receipt's.
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
    private Instant ended;
  }

  /** One owner window: the image it admits, its span and the identity of the process that opened it. */
  static final class Window {
    private final String image;
    private final String reason;
    private final Instant start;
    private final Key owner;
    private Instant closed;

    Window(String image, String reason, Instant start, long ownerPid, String ownerBirth) {
      this.image = image;
      this.reason = reason;
      this.start = start;
      this.owner = new Key(ownerPid, ownerBirth);
    }

    void close(Instant at) {
      closed = at;
    }

    private boolean admits(String executable) {
      if (executable == null) return false;
      String name = executable.substring(Math.max(executable.lastIndexOf('\\'), executable.lastIndexOf('/')) + 1);
      return name.toLowerCase(Locale.ROOT).equals(image.toLowerCase(Locale.ROOT));
    }

    private boolean covers(String executable, Instant born, Instant exited, Long creatorPid, String creatorBirth) {
      if (born == null || creatorPid == null || creatorBirth == null || owner.birth() == null) return false;
      if (owner.pid() != creatorPid || !owner.birth().equals(creatorBirth)) return false;
      if (!admits(executable)) return false;
      Instant end = end();
      if (born.isBefore(start) || (end != null && born.isAfter(end))) return false;
      return exited == null || end == null || !exited.isAfter(end);
    }

    /** The recorded end, else the owner's proven exit; null while neither is known. */
    private Instant end() {
      Instant end = closed;
      Owned ownerFacts = OWNED.get(owner);
      if (ownerFacts != null && ownerFacts.ended != null && (end == null || ownerFacts.ended.isBefore(end))) {
        end = ownerFacts.ended;
      }
      return end;
    }

    /** Whether this window is open at {@code now} for launches of {@code executable}'s image. */
    private boolean openFor(String executable, Instant now) {
      return closed == null && !now.isBefore(start) && admits(executable);
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
  private static LedgerWindows ledgerWindows;

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
    outcome(pid, birth, exitClass, reason, null);
  }

  /**
   * The owner recorded this identity's exit with this class and reason; {@code ended}, when known,
   * is when it exited. A window this identity opened covers nothing after it.
   */
  public static synchronized void outcome(long pid, String birth, String exitClass, String reason,
      Instant ended) {
    if (birth == null) return;
    Owned owned = OWNED.computeIfAbsent(new Key(pid, birth), k -> new Owned());
    owned.outcome = new Resolution(exitClass, reason, "owner exit record");
    owned.ended = ended;
  }

  /**
   * An owner window opened by the process {@code ownerPid}/{@code ownerBirth}: launches of {@code
   * image} that process makes within it are intended. {@code deadline} is the lease span the owner
   * declared; it does not end the window (see the class comment).
   */
  public static synchronized void windowOpened(String intentId, String image, String reason,
      Instant start, Instant deadline, long ownerPid, String ownerBirth) {
    WINDOWS.put(intentId, new Window(image, reason, start, ownerPid, ownerBirth));
  }

  /** The owner window ended. */
  public static synchronized void windowClosed(String intentId, Instant closedAt) {
    Window window = WINDOWS.get(intentId);
    if (window != null) window.closed = closedAt;
  }

  /** Whether a window this process opened is open now for launches of {@code executable}'s image. */
  static synchronized boolean openWindowFor(String executable, Instant now) {
    long self = ProcessHandle.current().pid();
    return WINDOWS.values().stream().anyMatch(w -> w.owner.pid() == self && w.openFor(executable, now));
  }

  /**
   * The reason of the latest window of {@code creatorPid}/{@code creatorBirth} that covers a launch
   * of {@code executable} born at {@code born} and exited at {@code exited}: what an owner records
   * for a helper exit it neither stopped nor awaited.
   */
  static synchronized Optional<String> windowReason(String executable, Instant born, Instant exited,
      long creatorPid, String creatorBirth) {
    return WINDOWS.values().stream().filter(w -> w.covers(executable, born, exited, creatorPid, creatorBirth))
        .reduce((a, b) -> b).map(w -> w.reason);
  }

  /**
   * The Engine reads the windows and helper receipts other processes record in the children ledger
   * of {@code ledgerDir} (the extraction child's Tesseract window and helpers), from the ledger's
   * current end onward.
   */
  public static synchronized void readWindowsFrom(java.nio.file.Path ledgerDir) {
    ledgerWindows = ledgerDir == null ? null : new LedgerWindows(ledgerDir);
  }

  /**
   * Classifies a witnessed exit. An exit its owner already recorded has that record's class and
   * reason. Otherwise a launch a window covers is REQUESTED with the window's reason at once (with
   * the owner's stop reason, if it recorded one): the window was forced before the launch, so the
   * class never waits on the owner's exit receipt. A window applies only when {@code
   * creatorPid}/{@code creatorBirth}, the exiting process's parent as read natively, is the window's
   * owner. Without a window, empty while the owner of an owned identity has not recorded its exit
   * and {@code ownerWaitOver} is false: the witness asks again later. A crash {@code exitCode} makes
   * any REQUESTED class UNEXPECTED, even inside a window. The witness must still apply its
   * registration wait before publishing the accepted class with {@link #witnessed} for the exit's
   * console host; resolving alone never publishes a class that may still be deferred.
   */
  public static synchronized Optional<Resolution> resolve(long pid, String birth, String executable,
      Instant born, Instant exited, boolean ownerWaitOver, Long creatorPid, String creatorBirth,
      Integer exitCode) {
    Owned owned = birth == null ? null : OWNED.get(new Key(pid, birth));
    LedgerWindows.Receipt receipt = owned == null ? ledgerReceipt(pid, birth, creatorPid, creatorBirth) : null;
    Resolution resolution;
    if (owned != null && owned.outcome != null) {
      resolution = owned.outcome;
    } else if (receipt != null && receipt.outcome() != null) {
      resolution = receipt.outcome();
    } else {
      Optional<Window> window = window(executable, born, exited, creatorPid, creatorBirth);
      String stop = owned != null ? owned.intent : receipt != null ? receipt.intent() : null;
      if (window.isPresent()) {
        resolution = stop != null ? new Resolution("REQUESTED", stop, "owner intent")
            : new Resolution("REQUESTED", window.get().reason, "owner window");
      } else if (owned == null && receipt == null) {
        resolution = new Resolution("UNEXPECTED", NO_INTENT, "none");
      } else if (!ownerWaitOver) {
        return Optional.empty();
      } else if (stop != null) {
        resolution = new Resolution("REQUESTED", stop, "owner intent");
      } else {
        resolution = owned != null
            ? new Resolution("UNEXPECTED", "owner recorded no exit and no stop intent", "none")
            : new Resolution("UNEXPECTED", NO_INTENT, "none");
      }
    }
    return Optional.of(afterExitCode(resolution, exitCode));
  }

  /**
   * Publishes only the witness's accepted final class, after registration/owner waits and native
   * exit-code classification. A console must not inherit a no-intent result still being deferred.
   */
  public static synchronized void witnessed(long pid, String birth, Resolution resolution) {
    if (birth != null) WITNESSED.put(new Key(pid, birth), resolution);
  }

  /** A crash exit code is UNEXPECTED whatever intent or window would have made it REQUESTED. */
  public static Resolution afterExitCode(Resolution resolution, Integer exitCode) {
    if (exitCode == null || !OwnedHelper.crashCode(exitCode) || !"REQUESTED".equals(resolution.exitClass())) {
      return resolution;
    }
    return new Resolution("UNEXPECTED", "crash exit code after: " + resolution.reason(), resolution.basis());
  }

  /** Classifies a witnessed exit whose exit code is unknown: no crash override applies. */
  public static Optional<Resolution> resolve(long pid, String birth, String executable, Instant born, Instant exited,
      boolean ownerWaitOver, Long creatorPid, String creatorBirth) {
    return resolve(pid, birth, executable, born, exited, ownerWaitOver, creatorPid, creatorBirth, null);
  }

  private static Optional<Window> window(String executable, Instant born, Instant exited, Long creatorPid,
      String creatorBirth) {
    Optional<Window> local = WINDOWS.values().stream()
        .filter(w -> w.covers(executable, born, exited, creatorPid, creatorBirth)).reduce((a, b) -> b);
    if (local.isPresent() || ledgerWindows == null || creatorPid == null
        || creatorPid == ProcessHandle.current().pid()) {
      return local;
    }
    // Launched by another process: its windows are in the shared ledger, not in this JVM.
    return ledgerWindows.read().stream()
        .filter(w -> w.covers(executable, born, exited, creatorPid, creatorBirth)).reduce((a, b) -> b);
  }

  /**
   * The receipt the member's creator recorded in the shared ledger, when that creator is another
   * process and the receipt names this PID and birth as created by it; null otherwise.
   */
  private static LedgerWindows.Receipt ledgerReceipt(long pid, String birth, Long creatorPid,
      String creatorBirth) {
    if (ledgerWindows == null || birth == null || creatorPid == null || creatorBirth == null
        || creatorPid == ProcessHandle.current().pid()) {
      return null;
    }
    ledgerWindows.read();
    LedgerWindows.Receipt receipt = ledgerWindows.receipt(pid, birth);
    return receipt == null || !receipt.createdBy(creatorPid, creatorBirth) ? null : receipt;
  }

  /**
   * A console host follows its client: the client's resolved exit, once known. Empty while the
   * client's owner may still record it and {@code ownerWaitOver} is false.
   */
  public static synchronized Optional<Resolution> resolveConsole(long clientPid, String clientBirth,
      boolean ownerWaitOver) {
    Owned owned = clientBirth == null ? null : OWNED.get(new Key(clientPid, clientBirth));
    Resolution witnessed = clientBirth == null ? null : WITNESSED.get(new Key(clientPid, clientBirth));
    // An accepted witness class includes native crash/unknown-code facts and is final. A late
    // owner receipt must not erase them. Before acceptance, an owner outcome can resolve the exit.
    Optional<Resolution> owner = witnessed != null ? Optional.of(witnessed)
        : owned != null && owned.outcome != null ? Optional.of(owned.outcome)
        : ownerOf(clientPid, clientBirth, ownerWaitOver);
    if (owner == null) return Optional.empty();
    Resolution client = owner.orElse(null);
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
