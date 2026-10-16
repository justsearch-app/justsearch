/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import java.nio.file.Path;
import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.Optional;

/** Process-wide health of owner, helper and witness writers, scoped to their ledger directory. */
public final class ExitLedgerHealth {
  private static final System.Logger LOG = System.getLogger(ExitLedgerHealth.class.getName());
  private static final IdentityHashMap<ExitLedgerWriter, Failure> failures = new IdentityHashMap<>();
  private static final IdentityHashMap<ExitLedgerWriter, java.util.Set<String>> warnedCauses = new IdentityHashMap<>();
  private ExitLedgerHealth() {}

  public record Failure(Path directory, Instant since, Instant lastSeen, long count, String cause) {
    public Failure(Path directory, Instant since, Instant lastSeen, long count) {
      this(directory, since, lastSeen, count, "write_failed");
    }
  }

  private static final java.util.Map<Path, Failure> unpersistedRestarts = new java.util.HashMap<>();

  /** Boot can restart before its self-intent writer exists; surface the memory-only budget. */
  public static synchronized void unpersistedDeviceLostRestart(Path directory) {
    Path key = directory.toAbsolutePath().normalize();
    Instant now = Instant.now();
    Failure prior = unpersistedRestarts.get(key);
    unpersistedRestarts.put(key, new Failure(key, prior == null ? now : prior.since(), now,
        prior == null ? 1 : prior.count() + 1, "device_lost_restart_unpersisted"));
    if (prior == null) LOG.log(System.Logger.Level.WARNING,
        "EXIT_LEDGER_UNWRITABLE: the device-loss restart count could not be persisted; this incarnation counts it in memory");
  }

  /** The pending early reservation was persisted at commit or abandoned by another exit. */
  public static synchronized void deviceLostRestartCountPersisted(Path directory) {
    unpersistedRestarts.remove(directory.toAbsolutePath().normalize());
  }

  static synchronized void failed(ExitLedgerWriter writer, Path directory, Instant at, java.io.IOException failure) {
    Failure prior = failures.get(writer);
    failures.put(writer, new Failure(directory.toAbsolutePath().normalize(),
        prior == null ? at : prior.since(), at, prior == null ? 1 : prior.count() + 1));
    String cause = failure.getClass().getSimpleName();
    String causeKey = failure.getClass().getName() + ":" + failure.getMessage();
    if (failure instanceof java.nio.file.FileSystemException filesystem && filesystem.getReason() != null) {
      cause += ": " + filesystem.getReason();
    } else if (!(failure instanceof java.nio.file.FileSystemException) && failure.getMessage() != null) {
      cause += ": " + failure.getMessage();
    }
    if (warnedCauses.computeIfAbsent(writer, ignored -> new java.util.HashSet<>()).add(causeKey)) {
      LOG.log(System.Logger.Level.WARNING,
          "EXIT_LEDGER_UNWRITABLE: process exit evidence could not be persisted ({0}; {1})",
          writer.family(), cause);
    }
  }

  static synchronized void recovered(ExitLedgerWriter writer) {
    Failure prior = failures.remove(writer);
    warnedCauses.remove(writer);
    if (prior != null) LOG.log(System.Logger.Level.INFO,
        "Exit ledger recovered after {0} failed writes; pending gaps and a new record are durable ({1})",
        prior.count(), writer.family());
  }

  public static synchronized Optional<Failure> current(Path directory) {
    Path key = directory.toAbsolutePath().normalize();
    return java.util.stream.Stream.concat(failures.values().stream(), unpersistedRestarts.values().stream())
        .filter(f -> f.directory().equals(key)).reduce((a, b) ->
        new Failure(key, a.since().isBefore(b.since()) ? a.since() : b.since(),
            a.lastSeen().isAfter(b.lastSeen()) ? a.lastSeen() : b.lastSeen(), a.count() + b.count(),
            a.cause().equals(b.cause()) ? a.cause() : "write_failed,device_lost_restart_unpersisted"));
  }
}
