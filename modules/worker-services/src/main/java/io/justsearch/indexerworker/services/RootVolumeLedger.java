/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.configuration.PlatformPaths;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * The volume each root was first pruned from, kept across restarts (task t-20261013-ef3c41, S8).
 *
 * <p>A drive letter or a mount point can come back holding a different volume: another USB stick
 * at the same letter, a reformatted disk. Its folder of the same name is present and listable, so
 * without a record every file of the original would read as deleted. The first prune of a root
 * records the volume it found; a later prune of that root on a different volume removes nothing.
 * The record is dropped when the person removes the root ({@code deleteByPath}).
 *
 * <p>One small properties file under the index base folder, rewritten atomically. A file that
 * exists but cannot be read is "cannot tell": the caller removes nothing. Round 4: the volume is
 * also recorded the first time a source under a root is seen present ({@link RootWitness}), and a
 * folder is judged by the record of the nearest recorded folder at or above it.
 */
final class RootVolumeLedger {
  static final String FILE_NAME = "root-volumes.properties";

  private final Path file;
  /** The records of an in-memory ledger; {@code null} for a file-backed or disabled one. */
  private final Properties memory;
  private final Object lock = new Object();

  private RootVolumeLedger(Path file, Properties memory) {
    this.file = file;
    this.memory = memory;
  }

  /** The ledger kept under {@code indexBasePath}, or a disabled one when there is no base path. */
  static RootVolumeLedger under(Path indexBasePath) {
    return new RootVolumeLedger(
        indexBasePath == null ? null : indexBasePath.resolve(FILE_NAME), null);
  }

  /** A ledger that records nothing (no persistent location). */
  static RootVolumeLedger disabled() {
    return new RootVolumeLedger(null, null);
  }

  /** A ledger kept in memory only, for as long as its owner lives. */
  static RootVolumeLedger inMemory() {
    return new RootVolumeLedger(null, new Properties());
  }

  /**
   * The volume recorded for {@code root}, or empty when none is recorded.
   *
   * @throws IOException when the ledger exists but cannot be read
   */
  Optional<String> recorded(Path root) throws IOException {
    if (file == null && memory == null) {
      return Optional.empty();
    }
    synchronized (lock) {
      return Optional.ofNullable(load().getProperty(key(root)));
    }
  }

  /**
   * The volume recorded for {@code folder} or, when none is, for the nearest recorded folder above
   * it; empty when no folder on its path is recorded.
   *
   * @throws IOException when the ledger exists but cannot be read
   */
  Optional<String> recordedFor(Path folder) throws IOException {
    for (Path candidate = folder.toAbsolutePath().normalize();
        candidate != null;
        candidate = candidate.getParent()) {
      Optional<String> volume = recorded(candidate);
      if (volume.isPresent()) {
        return volume;
      }
    }
    return Optional.empty();
  }

  /** Records {@code volume} for {@code root} unless a volume is already recorded. */
  void recordIfAbsent(Path root, String volume) throws IOException {
    if (file == null && memory == null) {
      return;
    }
    synchronized (lock) {
      Properties entries = load();
      if (entries.putIfAbsent(key(root), volume) == null) {
        store(entries);
      }
    }
  }

  /** Drops the record of every root at or under {@code pathPrefix}. */
  void forgetUnder(String pathPrefix) throws IOException {
    if ((file == null && memory == null) || pathPrefix == null || pathPrefix.isBlank()) {
      return;
    }
    String prefix = key(Path.of(pathPrefix));
    synchronized (lock) {
      Properties entries = load();
      boolean changed =
          entries.keySet().removeIf(k -> {
            String root = (String) k;
            return root.equals(prefix) || root.startsWith(withSeparator(prefix));
          });
      if (changed) {
        store(entries);
      }
    }
  }

  private Properties load() throws IOException {
    Properties entries = new Properties();
    if (memory != null) {
      entries.putAll(memory);
      return entries;
    }
    try (InputStream in = Files.newInputStream(file)) {
      entries.load(in);
    } catch (NoSuchFileException none) {
      // Nothing recorded yet.
    }
    return entries;
  }

  private void store(Properties entries) throws IOException {
    if (memory != null) {
      memory.clear();
      memory.putAll(entries);
      return;
    }
    Files.createDirectories(file.getParent());
    Path temp = file.resolveSibling(FILE_NAME + ".tmp");
    try (OutputStream out = Files.newOutputStream(temp)) {
      entries.store(out, "Volume each indexed root was first pruned from (task t-20261013-ef3c41)");
    }
    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private static String key(Path root) {
    String normalized = root.toAbsolutePath().normalize().toString();
    return PlatformPaths.isWindows() ? normalized.toLowerCase(Locale.ROOT) : normalized;
  }

  private static String withSeparator(String path) {
    String separator = PlatformPaths.isWindows() ? "\\" : "/";
    return path.endsWith(separator) ? path : path + separator;
  }
}
