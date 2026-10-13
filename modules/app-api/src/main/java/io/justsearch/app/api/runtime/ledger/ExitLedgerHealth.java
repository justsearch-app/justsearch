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
  private ExitLedgerHealth() {}

  public record Failure(Path directory, Instant since, Instant lastSeen, long count) {}

  static synchronized void failed(ExitLedgerWriter writer, Path directory, Instant at) {
    Failure prior = failures.get(writer);
    failures.put(writer, new Failure(directory.toAbsolutePath().normalize(),
        prior == null ? at : prior.since(), at, prior == null ? 1 : prior.count() + 1));
    if (prior == null) LOG.log(System.Logger.Level.WARNING,
        "EXIT_LEDGER_UNWRITABLE: process exit evidence could not be persisted ({0})", writer.family());
  }

  static synchronized void recovered(ExitLedgerWriter writer) {
    Failure prior = failures.remove(writer);
    if (prior != null) LOG.log(System.Logger.Level.INFO,
        "Exit ledger recovered after {0} failed writes; pending gaps and a new record are durable ({1})",
        prior.count(), writer.family());
  }

  public static synchronized Optional<Failure> current(Path directory) {
    Path key = directory.toAbsolutePath().normalize();
    return failures.values().stream().filter(f -> f.directory().equals(key)).reduce((a, b) ->
        new Failure(key, a.since().isBefore(b.since()) ? a.since() : b.since(),
            a.lastSeen().isAfter(b.lastSeen()) ? a.lastSeen() : b.lastSeen(), a.count() + b.count()));
  }
}
