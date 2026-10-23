/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.adapters.lucene.runtime.PathPresence;
import io.justsearch.indexerworker.loop.SourcePresence;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one root-identity question every path that can end in a deletion asks before a file counts
 * as confirmed absent (task t-20261013-ef3c41, round 4, S8): admission, the pre-publish freshness
 * check, the post-extraction re-hash, the writer's pre-write check and the watcher's delete
 * confirmation, through {@link SourcePresence}. The prune asks the same question through {@link
 * RootPresenceGuard}. Every path that adds or updates content asks its other half (round 5):
 * admission of a present source through {@link #admits}, and the walks, the sync and the watcher
 * registration through {@link #refusesRoot}.
 *
 * <p>A folder vouches for the absence of what is beneath it only while it is a present, listable
 * folder, the same folder a live watcher registration captured (when there is one), and on the
 * volume recorded for it or for the nearest recorded folder above it ({@link RootVolumeLedger}). A
 * different volume at the same drive letter, a root that cannot be identified, or a record that
 * cannot be read is "cannot tell". Content beneath a folder is admitted only while it is a
 * present, listable folder on that recorded volume.
 *
 * <p>Round 8 (refined decision 2) and round 8b (decisions 1 and 2): only a deliberate add records
 * a volume, which is a request carrying the add marker: the person's add of a root, or an explicit
 * ingest request of a folder or of files, a person's or an agent's ({@link #recordDeliberateAdd}).
 * No walk ever records. Every other path (admission, the walks, the sync, the watcher, the boot
 * reconcile, the prune) refuses a folder with nothing recorded at or above it as unproven and
 * records nothing. The one exception is the one-time seeding on evidence ({@link
 * #seedAfterUpgrade}) of a root after an upgrade or of a configured collection. The parent folder
 * of an explicitly ingested file is not a root: it answers through its own record, which never
 * answers for a root. A ledger with no base path checks no volume; the in-memory one an unwired
 * owner uses records a folder the first time it is seen.
 *
 * <p>Cost is per root, not per file: each answer for a folder is kept for {@link #VOUCH_TTL_MS}
 * ms. A swap within that second can still be answered from the previous look.
 */
public final class RootWitness implements SourcePresence.RootCheck {
  private static final Logger log = LoggerFactory.getLogger(RootWitness.class);

  /** How long one answer for a folder is reused. */
  static final long VOUCH_TTL_MS = 1_000L;

  private final RootVolumeLedger volumes;
  private final Function<Path, RootIdentity> registered;
  private final java.util.function.Predicate<Path> declaredRootCovers;
  private final ConcurrentHashMap<Path, Answer> recent = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Path, Answer> admitted = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Path, FileFolderAnswer> fileFolders = new ConcurrentHashMap<>();

  private record Answer(boolean yes, long expiresNanos) {}

  private record FileFolderAnswer(boolean vouching, Answer answer) {}

  private RootWitness(RootVolumeLedger volumes, Function<Path, RootIdentity> registered,
      java.util.function.Predicate<Path> declaredRootCovers) {
    this.volumes = volumes;
    this.registered = registered;
    this.declaredRootCovers = declaredRootCovers;
  }

  /**
   * A witness whose records live only as long as it does: what an ingestion authority uses until
   * the Worker's service wires the persistent one in.
   */
  public static RootWitness inMemory() {
    return new RootWitness(RootVolumeLedger.inMemory(), ignored -> null, ignored -> false);
  }

  /**
   * A witness over the persistent {@code volumes} record, consulting {@code registered} for the
   * identity a live watcher registration captured (or {@code null} when none covers the folder)
   * and {@code declaredRootCovers} for whether a declared root (watched, or held for its volume)
   * is at or above a folder.
   */
  static RootWitness of(RootVolumeLedger volumes, Function<Path, RootIdentity> registered,
      java.util.function.Predicate<Path> declaredRootCovers) {
    return new RootWitness(volumes, registered, declaredRootCovers);
  }

  @Override
  public boolean vouches(Path folder) {
    Path key = normalize(folder);
    if (key == null) {
      return false;
    }
    long now = System.nanoTime();
    Answer cached = recent.get(key);
    if (cached != null && cached.expiresNanos() - now > 0) {
      return cached.yes();
    }
    boolean vouches = PathPresence.isReadableDirectory(key) && sameRoot(key);
    recent.put(key, new Answer(vouches, now + TimeUnit.MILLISECONDS.toNanos(VOUCH_TTL_MS)));
    return vouches;
  }

  /**
   * True while content beneath {@code folder} may be added or updated (round 5, S8): a present,
   * listable folder on the volume recorded for it; a root with nothing recorded is unproven (round
   * 8). A folder that is itself a link is admitted as before, since no volume is recorded for it.
   * Unlike {@link #vouches}, a watcher registration's identity is not asked: a folder deleted and
   * made again on the same volume is the person's own, and its new files are indexed.
   */
  @Override
  public boolean admits(Path folder) {
    Path key = normalize(folder);
    if (key == null) {
      return false;
    }
    long now = System.nanoTime();
    Answer cached = admitted.get(key);
    if (cached != null && cached.expiresNanos() - now > 0) {
      return cached.yes();
    }
    boolean admits;
    if (Files.isDirectory(key, LinkOption.NOFOLLOW_LINKS)) {
      admits = PathPresence.isReadableDirectory(key) && folderVolumeHolds(key);
    } else {
      admits = Files.isDirectory(key);
    }
    admitted.put(key, new Answer(admits, now + TimeUnit.MILLISECONDS.toNanos(VOUCH_TTL_MS)));
    return admits;
  }

  /**
   * {@link #vouches} for the parent folder of an explicitly ingested file (round 8): a present,
   * listable folder on the volume recorded for the nearest recorded root at or above it. Under a
   * declared root with nothing recorded it is unproven, as the root is. Outside every declared
   * root, with nothing recorded above it, the folder's own record answers, which only the explicit
   * ingest request records (round 8b, decision 1); with none it is unproven. That record never
   * answers for a root.
   */
  @Override
  public boolean vouchesForFile(Path folder) {
    return answerForFileFolder(folder, true);
  }

  /** {@link #admits} for the parent folder of an explicitly ingested file; as {@link #vouchesForFile}. */
  @Override
  public boolean admitsFile(Path folder) {
    return answerForFileFolder(folder, false);
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // a native identity read may throw unchecked
  private boolean answerForFileFolder(Path folder, boolean vouching) {
    Path key = normalize(folder);
    if (key == null) {
      return false;
    }
    long now = System.nanoTime();
    FileFolderAnswer cached = fileFolders.get(key);
    if (cached != null && cached.vouching() == vouching && cached.answer().expiresNanos() - now > 0) {
      return cached.answer().yes();
    }
    boolean yes;
    if (!vouching && !Files.isDirectory(key, LinkOption.NOFOLLOW_LINKS)) {
      yes = Files.isDirectory(key);
    } else if (!PathPresence.isReadableDirectory(key)) {
      yes = false;
    } else {
      try {
        RootIdentity current = RootIdentity.capture(key);
        RootIdentity atRegistration = vouching ? registered.apply(key) : null;
        if (atRegistration != null) {
          atRegistration.requireSame(current, key);
        }
        // Round 8b (decision 2): nothing here records; only the explicit ingest request does.
        requireVolume(key, current);
        yes = true;
      } catch (IOException | RuntimeException cannotTell) {
        log.warn("Folder {} of an explicitly ingested file is not the folder or volume it was ({})",
            key, cannotTell.getMessage());
        yes = false;
      }
    }
    fileFolders.put(key, new FileFolderAnswer(vouching,
        new Answer(yes, now + TimeUnit.MILLISECONDS.toNanos(VOUCH_TTL_MS))));
    return yes;
  }

  /**
   * A request carrying the add marker (round 8b, decision 1): the person's add of a root, or an
   * explicit ingest request, a person's or an agent's. A folder's volume is recorded under its own
   * key ({@link #recordAdded}). For a single file, its parent folder's volume is recorded under the
   * folder's own key unless a declared root covers that folder, whose own rule then answers.
   */
  void recordDeliberateAdd(Path path, boolean singleFile) {
    Path key = normalize(path);
    if (key == null) {
      return;
    }
    if (!singleFile) {
      recordAdded(key);
    } else if (key.getParent() != null) {
      recordExplicitFileFolder(key.getParent());
    }
  }

  /**
   * Records the volume of {@code folder}, the parent of an explicitly ingested file, replacing what
   * its own key held (round 8b, decision 1). Nothing is recorded under a declared root, or for a
   * folder that is missing, not a real folder, or whose volume cannot be read.
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // a native identity read may throw unchecked
  void recordExplicitFileFolder(Path folder) {
    Path key = normalize(folder);
    if (key == null || declaredRootCovers.test(key)
        || !Files.isDirectory(key, LinkOption.NOFOLLOW_LINKS)
        || !PathPresence.isReadableDirectory(key)) {
      return;
    }
    try {
      String volume = RootIdentity.capture(key).volume();
      if (volume != null) {
        volumes.recordFileFolder(key, volume);
      }
    } catch (IOException | RuntimeException notRecorded) {
      log.warn("Could not record the volume of {}, the folder of an explicitly ingested file: {}",
          key, notRecorded.getMessage());
    } finally {
      forgetAnswers();
    }
  }

  /**
   * Records the volume of {@code root} now: the person added it (round 8, refined decision 2).
   * The add's record replaces what the root's own key held and ends its upgrade seeding. A root
   * that is missing, not a real folder, or whose volume cannot be read records nothing.
   *
   * @return whether a volume was recorded
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // a native identity read may throw unchecked
  boolean recordAdded(Path root) {
    Path key = normalize(root);
    if (key == null || !Files.isDirectory(key, LinkOption.NOFOLLOW_LINKS)
        || !PathPresence.isReadableDirectory(key)) {
      return false;
    }
    try {
      String volume = RootIdentity.capture(key).volume();
      if (volume == null) {
        return false;
      }
      volumes.record(key, volume);
      return true;
    } catch (IOException | RuntimeException notRecorded) {
      log.warn("Could not record the volume of the added root {}: {}", key,
          notRecorded.getMessage());
      return false;
    } finally {
      forgetAnswers();
    }
  }

  /** Whether {@code root} still awaits its one-time upgrade seeding; an unreadable record says no. */
  boolean awaitsUpgradeSeeding(Path root) {
    Path key = normalize(root);
    if (key == null) {
      return false;
    }
    try {
      return volumes.awaitsSeeding(key);
    } catch (IOException cannotTell) {
      log.debug("The upgrade seeding of {} cannot be read ({})", key, cannotTell.getMessage());
      return false;
    }
  }

  /**
   * The one-time upgrade seeding of {@code root} (round 8, refined decision 2). When the root
   * awaits it and is a present, listable real folder whose volume can be read, that volume is
   * recorded only if {@code evidence} confirms the root's indexed files are on it. Anything else
   * records nothing and the root keeps awaiting, so a later attempt adopts a returning drive.
   * With no platform identity and no known record, only the comparison is skipped and seeding
   * finishes without recording an identity. A known record that cannot be confirmed stays pending.
   *
   * @return whether the root no longer awaits seeding
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // a native identity read may throw unchecked
  boolean seedAfterUpgrade(Path root, java.util.function.Predicate<Path> evidence) {
    Path key = normalize(root);
    if (key == null || !awaitsUpgradeSeeding(key)) {
      return true;
    }
    if (!Files.isDirectory(key, LinkOption.NOFOLLOW_LINKS)
        || !PathPresence.isReadableDirectory(key)) {
      return false;
    }
    try {
      String volume = RootIdentity.capture(key).volume();
      if (volume == null && volumes.recordedFor(key).isPresent()) {
        return false;
      }
      if (volume != null && !evidence.test(key)) {
        log.info("Root {} is not recorded after the upgrade: its indexed files are not on the drive"
            + " at its path", key);
        return false;
      }
      volumes.seeded(key, volume);
      log.info("Root {} is recorded on {} after the upgrade", key, volume);
      return true;
    } catch (IOException | RuntimeException notSeeded) {
      log.warn("Root {} is not recorded after the upgrade ({})", key, notSeeded.getMessage());
      return false;
    } finally {
      forgetAnswers();
    }
  }

  private void forgetAnswers() {
    recent.clear();
    admitted.clear();
    fileFolders.clear();
  }

  /**
   * True when the root at {@code root} is a real folder (not a link) that is not on the volume
   * recorded for it, whose volume cannot be told, or that has nothing recorded (round 8).
   * The walks, the sync and the watcher registration then refuse the root: they add nothing,
   * remove nothing and the root shows its root-level unavailable state instead of being walked as
   * if it were the original (task t-20261013-ef3c41, rounds 4 and 5, S8). A missing root or a root
   * that is itself a link is not refused here; those keep their own handling.
   */
  boolean refusesRoot(Path root) {
    return refusesRoot(root, true);
  }

  /**
   * {@link #refusesRoot(Path)} for the periodic check that asks every root's volume each cycle
   * (round 6): the same answer, logged at debug so a root that stays on another volume does not
   * warn once a minute.
   */
  boolean refusesRootQuietly(Path root) {
    return refusesRoot(root, false);
  }

  private boolean refusesRoot(Path root, boolean loud) {
    Path key = normalize(root);
    return key != null
        && Files.isDirectory(key, LinkOption.NOFOLLOW_LINKS)
        && !recordedVolumeHolds(key, loud);
  }

  /**
   * True only when {@code root} is a present, listable real folder on the volume recorded for it
   * or for the nearest recorded folder above it (round 8, S8). It never records: a root with
   * nothing recorded is unproven, as is one whose volume or record cannot be read. The
   * deferred-root check asks this, so a recovery path never adopts the volume it finds.
   *
   * <p>Without a platform volume identity and without a previously recorded identity, only the
   * comparison is skipped. A known recorded identity that cannot now be confirmed is uncertainty
   * on every platform, even when the replacement filesystem is present and listable.
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // a native identity read may throw unchecked
  boolean provenOnRecordedVolume(Path root) {
    Path key = normalize(root);
    if (key == null || !Files.isDirectory(key, LinkOption.NOFOLLOW_LINKS)
        || !PathPresence.isReadableDirectory(key)) {
      return false;
    }
    try {
      String volume = RootIdentity.capture(key).volume();
      Optional<String> recorded = volumes.recordedFor(key);
      return volume == null ? recorded.isEmpty()
          : recorded.isPresent() && recorded.get().equals(volume);
    } catch (IOException | RuntimeException cannotTell) {
      log.debug("Root {} is not proven on its recorded volume ({})", key, cannotTell.getMessage());
      return false;
    }
  }

  /** Drops every answer and record at or under {@code pathPrefix}: the person removed the root. */
  void forgetUnder(String pathPrefix) throws IOException {
    forgetUnder(pathPrefix, java.util.List.of());
  }

  void forgetUnder(String pathPrefix, java.util.List<String> excludedSubtrees) throws IOException {
    forgetAnswers();
    volumes.forgetUnder(pathPrefix, excludedSubtrees);
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // a native identity read may throw unchecked
  private boolean recordedVolumeHolds(Path key, boolean loud) {
    try {
      requireRecordedVolume(volumes, key, RootIdentity.capture(key));
      return true;
    } catch (IOException | RuntimeException cannotTell) {
      if (loud) {
        log.warn("Root {} is not walked, synced or watched: it is not on its recorded volume, has"
            + " none recorded, or that cannot be told ({})", key, cannotTell.getMessage());
      } else {
        log.debug("Nothing is admitted under {} now: it is not on its recorded volume, has none"
            + " recorded, or that cannot be told ({})", key, cannotTell.getMessage());
      }
      return false;
    }
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // a native identity read may throw unchecked
  private boolean sameRoot(Path folder) {
    try {
      RootIdentity current = RootIdentity.capture(folder);
      RootIdentity atRegistration = registered.apply(folder);
      if (atRegistration != null) {
        atRegistration.requireSame(current, folder);
      }
      requireVolume(folder, current);
      return true;
    } catch (IOException | RuntimeException cannotTell) {
      log.warn(
          "Folder {} cannot vouch that anything beneath it was deleted: it is not the folder or"
              + " volume it was ({})",
          folder,
          cannotTell.getMessage());
      return false;
    }
  }

  /**
   * The volume rule for a folder that answers for what is beneath it (round 8, refined decision
   * 2): under a declared root, the root's recorded volume, nothing recorded being unproven. A folder
   * no declared root covers (an explicitly ingested folder, or any folder before the roots are
   * registered again after a restart) answers through the record of the nearest recorded folder
   * at or above it, else through its own explicit-file-folder record; with neither it is unproven
   * too. Nothing here records, except the first-sight rule of the in-memory ledger an unwired owner
   * uses (round 8b, decision 2).
   */
  private void requireVolume(Path folder, RootIdentity current) throws IOException {
    if (declaredRootCovers.test(folder) || volumes.adoptsOnFirstSight() || !volumes.enabled()) {
      requireRecordedVolume(volumes, folder, current);
      return;
    }
    String volume = current.volume();
    Optional<String> recorded = volumes.recordedFor(folder);
    if (recorded.isEmpty()) {
      recorded = volumes.recordedFileFolder(folder);
    }
    if (recorded.isEmpty() && volume == null) {
      return;
    }
    if (recorded.isEmpty()) {
      throw new IOException("no volume is recorded for this folder; only adding or ingesting it"
          + " records one");
    }
    if (!recorded.get().equals(volume)) {
      throw new IOException(
          "folder is on " + volume + ", not on " + recorded.get() + " recorded for it");
    }
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // a native identity read may throw unchecked
  private boolean folderVolumeHolds(Path key) {
    try {
      requireVolume(key, RootIdentity.capture(key));
      return true;
    } catch (IOException | RuntimeException cannotTell) {
      log.debug("Nothing is admitted under {} now: it is not on its recorded volume, has none"
          + " recorded, or that cannot be told ({})", key, cannotTell.getMessage());
      return false;
    }
  }

  /**
   * Whether a walk without the add marker refuses {@code folder} (round 8b, decision 2): a real
   * folder a declared root covers answers by the root's rule ({@link #refusesRoot(Path)}); any
   * other real folder, such as one walked before the roots are registered again after a restart,
   * is refused unless a volume recorded at or above it holds. Nothing is recorded, so such a walk
   * is deferred, deletes nothing and proceeds once its root is registered and proven. A missing
   * folder or one that is itself a link is not refused here; those keep their own handling.
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // a native identity read may throw unchecked
  boolean refusesWalk(Path folder) {
    Path key = normalize(folder);
    if (key == null || !Files.isDirectory(key, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    if (declaredRootCovers.test(key)) {
      return refusesRoot(key, true);
    }
    try {
      requireVolume(key, RootIdentity.capture(key));
      return false;
    } catch (IOException | RuntimeException cannotTell) {
      log.warn("Folder {} is not walked: no volume recorded at or above it holds, or that cannot"
          + " be told; nothing is recorded or deleted ({})", key, cannotTell.getMessage());
      return true;
    }
  }

  /**
   * Requires {@code current} to be on the volume recorded for {@code folder} or for the nearest
   * recorded folder above it. Round 8 (refined decision 2): nothing recorded is unproven and
   * throws, recording nothing; only the in-memory ledger of an unwired owner records the folder the
   * first time it is seen, and a disabled ledger checks nothing. A record that cannot be read
   * throws, which callers treat as "cannot tell". With no platform identity and no known record,
   * only the comparison is skipped. A known record must still be confirmed on every platform.
   */
  static void requireRecordedVolume(RootVolumeLedger volumes, Path folder, RootIdentity current)
      throws IOException {
    String volume = current.volume();
    Optional<String> recorded = volumes.recordedFor(folder);
    if (recorded.isPresent()) {
      if (!recorded.get().equals(volume)) {
        throw new IOException(
            "folder is on " + volume + ", not on " + recorded.get() + " recorded for it");
      }
      return;
    }
    if (volume == null || !volumes.enabled()) {
      return;
    }
    if (!volumes.adoptsOnFirstSight()) {
      throw new IOException("no volume is recorded for this root; only adding it records one");
    }
    try {
      volumes.recordIfAbsent(folder, volume);
    } catch (IOException notRecorded) {
      // Nothing is removed on the strength of this record, so a failed write only means a later
      // volume swap is caught by the in-session identity alone.
      log.warn("Could not record the volume of {}: {}", folder, notRecorded.getMessage());
    }
  }

  private static Path normalize(Path folder) {
    if (folder == null) {
      return null;
    }
    try {
      return folder.toAbsolutePath().normalize();
    } catch (RuntimeException invalid) {
      return null;
    }
  }
}
