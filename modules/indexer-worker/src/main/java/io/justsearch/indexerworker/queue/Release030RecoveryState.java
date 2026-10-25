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
  public static final String RETIREMENT_ROOT_KEY_PREFIX = "release030-root:";
  private static final String RETIRE = "retire|";
  private static final String RETIRE_PATH = "retire-path|";
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
      if (key.startsWith(QUARANTINED) || key.startsWith(RETIRE) || key.startsWith(RETIRE_PATH))
        quarantine.setProperty(key, state.getProperty(key));
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
    if (retiringBuild() || !prepared() || !pendingRoots().isEmpty()
        || state.stringPropertyNames().stream().anyMatch(key -> key.startsWith(QUARANTINED))) return false;
    Files.deleteIfExists(file);
    return true;
  }

  public boolean retiringBuild() { return state.containsKey(RETIRE + "active"); }
  public String retiringActive() { return state.getProperty(RETIRE + "active"); }
  public String retiringBuilding() { return state.getProperty(RETIRE + "building"); }
  public boolean rootsJournaled() { return "true".equals(state.getProperty(RETIRE + "roots-journaled")); }

  public void markRootsJournaled() throws IOException {
    state.setProperty(RETIRE + "roots-journaled", "true");
    save();
  }

  /** Publish completed root walks before acknowledging their ordered journal versions. */
  public void completeRetirementRoots() throws IOException {
    for (Path root : pendingRoots()) state.setProperty(ROOT + PathNormalizer.normalizeKey(root), "done");
    save();
  }

  public boolean replayComplete() { return "true".equals(state.getProperty(RETIRE + "replayed")); }
  public boolean differencesComplete() { return "true".equals(state.getProperty(RETIRE + "differences")); }

  public boolean rootWalked(SwitchBufferCapableQueue.SwitchBufferOp op) {
    return "true".equals(state.getProperty(rootWalkKey(op)));
  }

  /** The exact walk has committed its prune and durably admitted its files, before their writer runs. */
  public void markRootWalked(SwitchBufferCapableQueue.SwitchBufferOp op) throws IOException {
    state.setProperty(rootWalkKey(op), "true");
    save();
  }

  private static String rootWalkKey(SwitchBufferCapableQueue.SwitchBufferOp op) {
    try {
      String identity = op.generation() + "\u0000" + op.key() + "\u0000" + op.revision() + "\u0000" + op.lastUpdatedMs();
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
          .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return RETIRE + "walk|" + java.util.HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 unavailable", unavailable);
    }
  }

  public void markDifferencesComplete() throws IOException {
    state.setProperty(RETIRE + "differences", "true");
    save();
  }

  /** Written before changing state.json; all A/B differences remain obligations until commit. */
  public void beginRetirement(String active, String building, java.util.Map<Path, String> paths)
      throws IOException {
    if (retiringBuild()) throw new IOException("Release retirement already checkpointed");
    state.setProperty(RETIRE + "active", active);
    state.setProperty(RETIRE + "building", building);
    for (var entry : paths.entrySet()) state.setProperty(
        RETIRE_PATH + PathNormalizer.normalizeKey(entry.getKey()), entry.getValue());
    save();
  }

  public java.util.Map<Path, String> retirementPaths() throws IOException {
    var paths = new java.util.LinkedHashMap<Path, String>();
    try {
      for (String key : state.stringPropertyNames().stream().sorted().toList()) {
        if (!key.startsWith(RETIRE_PATH)) continue;
        Path path = Path.of(key.substring(RETIRE_PATH.length()));
        if (!path.isAbsolute()) throw new IOException("Invalid release retirement path");
        paths.put(path, state.getProperty(key));
      }
    } catch (java.nio.file.InvalidPathException invalid) {
      throw new IOException("Invalid release retirement path", invalid);
    }
    return java.util.Map.copyOf(paths);
  }

  public void markReplayComplete() throws IOException {
    state.setProperty(RETIRE + "replayed", "true");
    save();
  }

  public void finishRetirement() throws IOException {
    for (String key : List.copyOf(state.stringPropertyNames())) {
      if (key.startsWith(RETIRE) || key.startsWith(RETIRE_PATH)) state.remove(key);
    }
    save();
  }

  private void save() throws IOException {
    var bytes = new ByteArrayOutputStream();
    state.store(bytes, "0.3.0 queued-work recovery; incomplete roots resume after restart");
    AtomicFileWrites.replaceStrict(file, bytes.toByteArray());
  }

  static Path path(Path database) {
    return database.resolveSibling("upgrade-030-recovery.properties");
  }
}
