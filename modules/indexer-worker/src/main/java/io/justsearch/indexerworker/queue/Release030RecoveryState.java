/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A checkpoint beside jobs.db, written BEFORE v13 is changed; no queue schema version is used. */
public final class Release030RecoveryState {
  private static final Logger log = LoggerFactory.getLogger(Release030RecoveryState.class);
  private static final String ROOT = "root|";
  private static final String QUARANTINED = "quarantined-root|";
  private final Path file;
  private final Properties state;

  private Release030RecoveryState(Path file, Properties state) {
    this.file = file;
    this.state = state;
  }

  static void capture(Path database) throws IOException {
    var existing = load(database);
    if (existing != null) {
      if (existing.prepared()) {
        existing.state.remove("prepared");
        existing.state.remove("database");
        existing.save(); // A restored actual v13 snapshot must prepare again.
      }
      return;
    }
    Properties state = new Properties();
    state.setProperty("format", "1");
    new Release030RecoveryState(path(database), state).save();
  }

  public static Release030RecoveryState load(Path database) throws IOException {
    Properties state = new Properties();
    Path file = path(database);
    try (var input = Files.newInputStream(file)) {
      state.load(input);
    } catch (NoSuchFileException absent) {
      return null;
    } catch (IllegalArgumentException invalid) {
      state.clear(); // An invalid escape makes even the database binding uncertain.
    }
    if (!"1".equals(state.getProperty("format"))) {
      log.warn("Invalid 0.3.0 recovery checkpoint; retaining debt and repeating preparation");
      state.clear();
      state.setProperty("format", "1");
      state.setProperty("uncertain", "true");
    }
    var recovery = new Release030RecoveryState(file, state);
    int invalidKeys = 0;
    for (String key : List.copyOf(state.stringPropertyNames())) {
      if (!key.startsWith(ROOT)) continue;
      try {
        if (Path.of(key.substring(ROOT.length())).isAbsolute()) continue;
      } catch (java.nio.file.InvalidPathException invalid) {
        // A valid properties escape can still decode to an invalid filesystem path.
      }
      var encoded = new StringBuilder();
      var hex = java.util.HexFormat.of();
      for (int i = 0; i < key.length(); i++) encoded.append(hex.toHexDigits(key.charAt(i)));
      state.setProperty(QUARANTINED + encoded, state.getProperty(key));
      state.remove(key);
      invalidKeys++;
    }
    if (invalidKeys > 0) {
      recovery.save(); // Move only invalid keys aside, once; retain their uninterpretable debt.
      log.warn("Quarantined {} invalid 0.3.0 recovery root keys; valid roots will continue", invalidKeys);
    }
    return recovery;
  }

  public boolean prepared() {
    return "true".equals(state.getProperty("prepared"));
  }

  /** A restored/replaced database cannot borrow another database's preparation receipt. */
  public boolean preparedFor(String databaseIdentity) {
    return prepared() && databaseIdentity.equals(state.getProperty("database"));
  }

  /** Called after the atomic boundary backfill/retry repair and before the first job claim. */
  public void prepared(Collection<Path> roots, String databaseIdentity) throws IOException {
    boolean uncertain = "true".equals(state.getProperty("uncertain"));
    var quarantine = new Properties();
    for (String key : state.stringPropertyNames()) {
      if (key.startsWith(QUARANTINED)) quarantine.setProperty(key, state.getProperty(key));
    }
    state.clear();
    state.putAll(quarantine);
    state.setProperty("format", "1");
    state.setProperty("database", databaseIdentity);
    for (Path root : roots) {
      String key = PathNormalizer.normalizeKey(root);
      state.setProperty(ROOT + key, "pending");
      if (uncertain) state.setProperty("retry|" + key, Long.toString(System.currentTimeMillis() + 60_000L));
    }
    state.setProperty("prepared", "true");
    save();
  }

  public List<Path> pendingRoots() {
    return state.stringPropertyNames().stream()
        .filter(key -> key.startsWith(ROOT) && !"done".equals(state.getProperty(key)))
        .sorted().map(key -> Path.of(key.substring(ROOT.length()))).toList();
  }

  /** The directory walk has durably admitted its work and committed confirmed deletions. */
  public void completed(Path root) throws IOException {
    state.setProperty(ROOT + PathNormalizer.normalizeKey(root), "done");
    save();
  }

  /** Failed evidence is durable debt, with a cooldown across ticks and process restarts. */
  public void deferred(Path root, long now) throws IOException {
    state.setProperty("retry|" + PathNormalizer.normalizeKey(root), Long.toString(now + 60_000L));
    save();
  }

  public boolean ready(Path root, long now) throws IOException {
    String key = PathNormalizer.normalizeKey(root);
    if (!"pending".equals(state.getProperty(ROOT + key))) {
      state.setProperty(ROOT + key, "pending");
      deferred(root, now);
      return false;
    }
    try {
      long deadline = Long.parseLong(state.getProperty("retry|" + key, "0"));
      if (deadline < 0 || deadline > now + 60_000L) throw new NumberFormatException("Invalid cooldown");
      return now >= deadline;
    } catch (NumberFormatException invalid) {
      // Bad evidence never authorizes this root's prune, nor starves another root. Replace the
      // corrupt timer with one bounded, durable wait; do not repeat it on every tick/restart.
      deferred(root, now);
      return false;
    }
  }

  public boolean finish() throws IOException {
    if (!prepared() || !pendingRoots().isEmpty()
        || state.stringPropertyNames().stream().anyMatch(key -> key.startsWith(QUARANTINED))) return false;
    Files.deleteIfExists(file);
    return true;
  }

  private void save() throws IOException {
    var bytes = new ByteArrayOutputStream();
    state.store(bytes, "0.3.0 queued-work recovery; incomplete roots resume after restart");
    AtomicFileWrites.replaceStrict(file, bytes.toByteArray());
  }

  private static Path path(Path database) {
    return database.resolveSibling("upgrade-030-recovery.properties");
  }
}
