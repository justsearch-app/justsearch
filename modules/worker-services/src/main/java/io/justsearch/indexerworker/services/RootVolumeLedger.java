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
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * The volume each declared root lives on, kept across restarts (task t-20261013-ef3c41, S8).
 *
 * <p>A drive letter or a mount point can come back holding a different volume: another USB stick
 * at the same letter, a reformatted disk. Its folder of the same name is present and listable, so
 * without a record every file of the original would read as deleted. Round 8 (refined decision
 * 2): only a deliberate add records a volume: the person's add of a root, or an explicit ingest
 * request ({@link #record}, replacing what its own key held). No walk ever records (round 8b,
 * decision 2): every other path refuses a root with nothing recorded as unproven and records
 * nothing. The record is dropped when the person removes the root (its unwatch). A folder is
 * judged by the record of the nearest recorded folder at or above it.
 *
 * <p>Upgrade seeding: when neither this record nor its pending file exists yet (an upgrade from a
 * version without this record), {@link #beginUpgradeSeeding} writes the declared roots to the
 * pending file first, then an empty record, so the moment happens once. A pending root is recorded
 * once, on evidence ({@link #seeded}); a pending file that exists with no record means an
 * interrupted upgrade, which resumes. A configured collection root with nothing recorded is
 * seeded the same way once ({@link #awaitSeedingUnlessRecorded}, round 8b, decision 3).
 *
 * <p>The parent folder of an explicitly ingested file is not a root. Its volume is kept under its
 * own keys ({@link #recordedFileFolder}), recorded by the explicit ingest request alone ({@link
 * #recordFileFolder}), and never answers for a root.
 *
 * <p>Small properties files under the index base folder, rewritten atomically. A file that exists
 * but cannot be read is "cannot tell": the caller removes nothing. A ledger with no base path is
 * disabled and checks nothing; an in-memory one (the unwired default of an authority or watcher,
 * never the Worker's own) records a folder the first time it is seen.
 */
final class RootVolumeLedger {
  static final String FILE_NAME = "root-volumes.properties";
  /** The declared roots still awaiting their one-time upgrade seeding. */
  static final String PENDING_FILE_NAME = "root-volumes-pending.properties";
  /** Key prefix of the records kept for explicitly ingested files' parent folders. */
  private static final String FILE_FOLDER_PREFIX = "explicit-file-folder|";
  private static final String PENDING_COMMENT =
      "Roots awaiting their one-time upgrade seeding (task t-20261013-ef3c41)";

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

  /** Whether this ledger keeps records at all; a disabled one checks nothing. */
  boolean enabled() {
    return file != null || memory != null;
  }

  /**
   * Whether a folder with nothing recorded is recorded the first time it is seen: only the
   * in-memory ledger an unwired authority or watcher uses until the Worker's own is wired in.
   */
  boolean adoptsOnFirstSight() {
    return memory != null;
  }

  /**
   * The volume recorded for {@code root}, or empty when none is recorded.
   *
   * @throws IOException when the ledger exists but cannot be read
   */
  Optional<String> recorded(Path root) throws IOException {
    if (!enabled()) {
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
    putIfAbsent(key(root), volume);
  }

  /**
   * Records {@code volume} for {@code root}, replacing what its own key held, and ends any upgrade
   * seeding it awaited: the person added the root (round 8, refined decision 2).
   */
  void record(Path root, String volume) throws IOException {
    if (!enabled()) {
      return;
    }
    synchronized (lock) {
      Properties entries = load();
      String key = key(root);
      if (!volume.equals(entries.getProperty(key))) {
        entries.setProperty(key, volume);
        store(entries);
      }
      dropPending(key);
    }
  }

  /** The volume recorded for an explicitly ingested file's parent {@code folder}, if any. */
  Optional<String> recordedFileFolder(Path folder) throws IOException {
    if (!enabled()) {
      return Optional.empty();
    }
    synchronized (lock) {
      return Optional.ofNullable(load().getProperty(FILE_FOLDER_PREFIX + key(folder)));
    }
  }

  /**
   * Records {@code volume} for an explicitly ingested file's parent {@code folder}, replacing what
   * its key held: the explicit ingest request is a deliberate add (round 8b, decision 1).
   */
  void recordFileFolder(Path folder, String volume) throws IOException {
    if (!enabled()) {
      return;
    }
    synchronized (lock) {
      Properties entries = load();
      String key = FILE_FOLDER_PREFIX + key(folder);
      if (!volume.equals(entries.getProperty(key))) {
        entries.setProperty(key, volume);
        store(entries);
      }
    }
  }

  private void putIfAbsent(String key, String volume) throws IOException {
    if (!enabled()) {
      return;
    }
    synchronized (lock) {
      Properties entries = load();
      if (entries.putIfAbsent(key, volume) == null) {
        store(entries);
      }
    }
  }

  /**
   * Marks each of {@code configured} that has no volume recorded at or above it, and does not
   * already await it, as awaiting its one-time seeding (round 8b, decision 3): a configured
   * collection is the person's configuration and is seeded like a root after an upgrade. Does
   * nothing for a ledger that is not file-backed, or before the record exists.
   *
   * @return the roots that await seeding now, including ones that awaited it before
   * @throws IOException when the record or the pending file cannot be read or written
   */
  java.util.List<Path> awaitSeedingUnlessRecorded(Collection<Path> configured) throws IOException {
    java.util.List<Path> awaiting = new java.util.ArrayList<>();
    if (file == null) {
      return awaiting;
    }
    synchronized (lock) {
      if (!Files.exists(file)) {
        // The declared roots' upgrade seeding has not begun; it starts first (beginUpgradeSeeding).
        return awaiting;
      }
      Properties pending = loadPending();
      boolean changed = false;
      for (Path root : configured) {
        String key = key(root);
        if (pending.containsKey(key)) {
          awaiting.add(root);
        } else if (recordedFor(root).isEmpty()) {
          pending.setProperty(key, "awaiting");
          awaiting.add(root);
          changed = true;
        }
      }
      if (changed) {
        write(pendingFile(), pending, PENDING_COMMENT);
      }
    }
    return awaiting;
  }

  /**
   * Starts the one-time upgrade seeding when neither the record nor the pending file exists: the
   * {@code declared} roots are written to the pending file first, then an empty record. Does
   * nothing for a ledger that is not file-backed, or whose record or pending file exists.
   *
   * @return whether seeding started now
   * @throws IOException when either file cannot be read or written
   */
  boolean beginUpgradeSeeding(Collection<Path> declared) throws IOException {
    if (file == null) {
      return false;
    }
    synchronized (lock) {
      Path pending = pendingFile();
      if (Files.exists(file) || Files.exists(pending)) {
        return false;
      }
      Properties awaiting = new Properties();
      for (Path root : declared) {
        awaiting.setProperty(key(root), "awaiting");
      }
      write(pending, awaiting, PENDING_COMMENT);
      store(new Properties());
      return true;
    }
  }

  /** Whether {@code root} still awaits its one-time upgrade seeding. */
  boolean awaitsSeeding(Path root) throws IOException {
    if (file == null) {
      return false;
    }
    synchronized (lock) {
      return loadPending().containsKey(key(root));
    }
  }

  /**
   * Ends {@code root}'s upgrade seeding with {@code volume} recorded, or with nothing recorded when
   * {@code volume} is {@code null} (a platform that exposes no volume identity). Does nothing when
   * the root no longer awaits seeding.
   */
  void seeded(Path root, String volume) throws IOException {
    if (file == null) {
      return;
    }
    synchronized (lock) {
      String key = key(root);
      if (!loadPending().containsKey(key)) {
        return;
      }
      if (volume != null) {
        Properties entries = load();
        entries.setProperty(key, volume);
        store(entries);
      }
      dropPending(key);
    }
  }

  /**
   * Drops the record, the pending seeding and the explicit-file folder record of every folder at
   * or under {@code pathPrefix}.
   */
  void forgetUnder(String pathPrefix) throws IOException {
    if (!enabled() || pathPrefix == null || pathPrefix.isBlank()) {
      return;
    }
    String prefix = key(Path.of(pathPrefix));
    synchronized (lock) {
      Properties entries = load();
      boolean changed =
          entries.keySet().removeIf(k -> {
            String folder = (String) k;
            if (folder.startsWith(FILE_FOLDER_PREFIX)) {
              folder = folder.substring(FILE_FOLDER_PREFIX.length());
            }
            return atOrUnder(folder, prefix);
          });
      if (changed) {
        store(entries);
      }
      if (file != null) {
        Properties awaiting = loadPending();
        if (awaiting.keySet().removeIf(k -> atOrUnder((String) k, prefix))) {
          write(pendingFile(), awaiting, PENDING_COMMENT);
        }
      }
    }
  }

  private static boolean atOrUnder(String folder, String prefix) {
    return folder.equals(prefix) || folder.startsWith(withSeparator(prefix));
  }

  private void dropPending(String key) throws IOException {
    if (file == null) {
      return;
    }
    Properties awaiting = loadPending();
    if (awaiting.remove(key) != null) {
      write(pendingFile(), awaiting, PENDING_COMMENT);
    }
  }

  private Path pendingFile() {
    return file.resolveSibling(PENDING_FILE_NAME);
  }

  private Properties loadPending() throws IOException {
    Properties awaiting = new Properties();
    try (InputStream in = Files.newInputStream(pendingFile())) {
      awaiting.load(in);
    } catch (NoSuchFileException none) {
      // Nothing awaits seeding.
    }
    return awaiting;
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
    write(file, entries, "Volume of each declared root (task t-20261013-ef3c41)");
  }

  private static void write(Path target, Properties entries, String comment) throws IOException {
    Files.createDirectories(target.getParent());
    Path temp = target.resolveSibling(target.getFileName() + ".tmp");
    try (OutputStream out = Files.newOutputStream(temp)) {
      entries.store(out, comment);
    }
    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
