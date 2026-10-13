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
 * present, listable folder on that recorded volume. The volume is recorded the first time a
 * source under the folder is admitted, or the root is walked, synced, watched or pruned, whichever
 * comes first: whatever volume is there then is the one recorded.
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
  private final ConcurrentHashMap<Path, Answer> recent = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Path, Answer> admitted = new ConcurrentHashMap<>();

  private record Answer(boolean yes, long expiresNanos) {}

  private RootWitness(RootVolumeLedger volumes, Function<Path, RootIdentity> registered) {
    this.volumes = volumes;
    this.registered = registered;
  }

  /**
   * A witness whose records live only as long as it does: what an ingestion authority uses until
   * the Worker's service wires the persistent one in.
   */
  public static RootWitness inMemory() {
    return new RootWitness(RootVolumeLedger.inMemory(), ignored -> null);
  }

  /**
   * A witness over the persistent {@code volumes} record, consulting {@code registered} for the
   * identity a live watcher registration captured (or {@code null} when none covers the folder).
   */
  static RootWitness of(RootVolumeLedger volumes, Function<Path, RootIdentity> registered) {
    return new RootWitness(volumes, registered);
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
   * listable folder on the volume recorded for it, recorded now when nothing is recorded yet. A
   * folder that is itself a link is admitted as before, since no volume can be recorded for it.
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
      admits = PathPresence.isReadableDirectory(key) && recordedVolumeHolds(key, false);
    } else {
      admits = Files.isDirectory(key);
    }
    admitted.put(key, new Answer(admits, now + TimeUnit.MILLISECONDS.toNanos(VOUCH_TTL_MS)));
    return admits;
  }

  /**
   * True when the root at {@code root} is a real folder (not a link) that is not on the volume
   * recorded for it, or whose volume cannot be told, recording it when nothing is recorded yet.
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

  /** Drops every answer and record at or under {@code pathPrefix}: the person removed the root. */
  void forgetUnder(String pathPrefix) throws IOException {
    recent.clear();
    admitted.clear();
    volumes.forgetUnder(pathPrefix);
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // a native identity read may throw unchecked
  private boolean recordedVolumeHolds(Path key, boolean loud) {
    try {
      requireRecordedVolume(volumes, key, RootIdentity.capture(key));
      return true;
    } catch (IOException | RuntimeException cannotTell) {
      if (loud) {
        log.warn("Root {} is not walked, synced or watched: it is not on the volume it was first"
            + " seen on, or that cannot be told ({})", key, cannotTell.getMessage());
      } else {
        log.debug("Nothing is admitted under {} now: it is not on the volume it was first seen on,"
            + " or that cannot be told ({})", key, cannotTell.getMessage());
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
      requireRecordedVolume(volumes, folder, current);
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
   * Requires {@code current} to be on the volume recorded for {@code folder} or for the nearest
   * recorded folder above it; records it for {@code folder} when nothing is recorded yet. A record
   * that cannot be read throws, which callers treat as "cannot tell".
   */
  static void requireRecordedVolume(RootVolumeLedger volumes, Path folder, RootIdentity current)
      throws IOException {
    String volume = current.volume();
    if (volume == null) {
      return;
    }
    Optional<String> recorded = volumes.recordedFor(folder);
    if (recorded.isPresent()) {
      if (!recorded.get().equals(volume)) {
        throw new IOException(
            "folder is on " + volume + ", not on " + recorded.get() + " where it was first seen");
      }
      return;
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
