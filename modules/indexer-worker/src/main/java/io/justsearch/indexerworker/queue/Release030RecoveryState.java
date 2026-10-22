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

/** A checkpoint beside jobs.db, written BEFORE v13 is changed; no queue schema version is used. */
public final class Release030RecoveryState {
  private static final String ROOT = "root|";
  private final Path file;
  private final Properties state;

  private Release030RecoveryState(Path file, Properties state) {
    this.file = file;
    this.state = state;
  }

  static void capture(Path database) throws IOException {
    if (load(database) != null) return;
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
      throw new IOException("Unreadable 0.3.0 recovery checkpoint", invalid);
    }
    if (!"1".equals(state.getProperty("format"))) {
      throw new IOException("Unknown 0.3.0 recovery checkpoint format");
    }
    return new Release030RecoveryState(file, state);
  }

  public boolean prepared() {
    return "true".equals(state.getProperty("prepared"));
  }

  /** Called after the atomic boundary backfill/retry repair and before the first job claim. */
  public void prepared(Collection<Path> roots) throws IOException {
    for (Path root : roots) state.setProperty(ROOT + PathNormalizer.normalizeKey(root), "pending");
    state.setProperty("prepared", "true");
    save();
  }

  public List<Path> pendingRoots() {
    return state.stringPropertyNames().stream()
        .filter(key -> key.startsWith(ROOT) && "pending".equals(state.getProperty(key)))
        .sorted().map(key -> Path.of(key.substring(ROOT.length()))).toList();
  }

  /** The directory walk has durably admitted its work and committed confirmed deletions. */
  public void completed(Path root) throws IOException {
    state.setProperty(ROOT + PathNormalizer.normalizeKey(root), "done");
    save();
  }

  public void finish() throws IOException {
    if (prepared() && pendingRoots().isEmpty()) Files.deleteIfExists(file);
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
