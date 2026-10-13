/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.adapters.lucene.runtime.PathPresence;
import io.justsearch.indexerworker.loop.SourcePresence;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
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
 * RootPresenceGuard}.
 *
 * <p>A folder vouches for the absence of what is beneath it only while it is a present, listable
 * folder, the same folder a live watcher registration captured (when there is one), and on the
 * volume recorded for it or for the nearest recorded folder above it ({@link RootVolumeLedger}). A
 * different volume at the same drive letter, a root that cannot be identified, or a record that
 * cannot be read is "cannot tell". The volume is recorded the first time a source under the
 * folder is seen present, or at the root's first prune, whichever comes first.
 *
 * <p>Cost is per root, not per file: the answer for a folder is kept for {@link #VOUCH_TTL_MS} ms,
 * and the question is only asked for a source that was not found. The record of a folder is
 * written once per process.
 */
public final class RootWitness implements SourcePresence.RootCheck {
  private static final Logger log = LoggerFactory.getLogger(RootWitness.class);

  /** How long one answer for a folder is reused. */
  static final long VOUCH_TTL_MS = 1_000L;

  private final RootVolumeLedger volumes;
  private final Function<Path, RootIdentity> registered;
  private final ConcurrentHashMap<Path, Answer> recent = new ConcurrentHashMap<>();
  private final Set<Path> observed = ConcurrentHashMap.newKeySet();

  private record Answer(boolean vouches, long expiresNanos) {}

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
      return cached.vouches();
    }
    boolean vouches = PathPresence.isReadableDirectory(key) && sameRoot(key);
    recent.put(key, new Answer(vouches, now + TimeUnit.MILLISECONDS.toNanos(VOUCH_TTL_MS)));
    return vouches;
  }

  @Override
  public void observedPresent(Path folder) {
    Path key = normalize(folder);
    if (key == null || !observed.add(key)) {
      return;
    }
    try {
      String volume = RootIdentity.capture(key).volume();
      if (volume != null && volumes.recordedFor(key).isEmpty()) {
        volumes.recordIfAbsent(key, volume);
      }
    } catch (IOException notRecorded) {
      // Not recorded now; the next sighting tries again. Nothing is removed on this path.
      observed.remove(key);
      log.debug("Could not record the volume of {}: {}", key, notRecorded.getMessage());
    }
  }

  /**
   * Whether the folder at {@code root} is on the volume recorded for it, recording it when nothing
   * is recorded yet. A folder that cannot be identified, or whose record cannot be read, answers
   * {@code false}: the root then shows its root-level state instead of being walked as if it were
   * the original (task t-20261013-ef3c41, round 4, S8).
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // a native identity read may throw unchecked
  boolean onRecordedVolume(Path root) {
    Path key = normalize(root);
    if (key == null) {
      return false;
    }
    try {
      requireRecordedVolume(volumes, key, RootIdentity.capture(key));
      return true;
    } catch (IOException | RuntimeException cannotTell) {
      log.warn("Root {} is not walked: it is not on the volume it was first seen on, or that cannot"
          + " be told ({})", key, cannotTell.getMessage());
      return false;
    }
  }

  /** Drops every answer and record at or under {@code pathPrefix}: the person removed the root. */
  void forgetUnder(String pathPrefix) throws IOException {
    recent.clear();
    observed.clear();
    volumes.forgetUnder(pathPrefix);
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
