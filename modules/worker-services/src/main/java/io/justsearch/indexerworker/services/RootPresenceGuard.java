/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.adapters.lucene.runtime.PathPresence;
import io.justsearch.adapters.lucene.runtime.PruneOps;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The prune-side authority for "this root still vouches for absence" (#758).
 *
 * <p>A file reported "not found" is evidence of deletion only while the root that holds it is the
 * same, present, listable directory. This guard captures the root's native identity (volume serial
 * plus file id, {@link RootIdentity}) when the prune starts and re-checks it at every batch
 * boundary, so an unplugged drive, a dropped share, a root renamed away, or a different volume
 * mounted at the same letter mid-prune all stop the prune instead of reading as mass deletion.
 * When the root is registered with a watcher, the identity captured at registration must match
 * too, which catches a volume swap between registration and this prune; and the volume recorded
 * when the person added the root ({@link RootVolumeLedger}) must match, which catches a swap made
 * while the app was not running. The capture holds no handle, so the user may still rename or
 * delete the root while a long prune runs.
 */
final class RootPresenceGuard implements PruneOps.PresenceGuard {
  private static final Logger log = LoggerFactory.getLogger(RootPresenceGuard.class);

  private final Path root;
  private final RootIdentity baseline;
  private final RootIdentity registered;

  private RootPresenceGuard(Path root, RootIdentity baseline, RootIdentity registered) {
    this.root = root;
    this.baseline = baseline;
    this.registered = registered;
  }

  /**
   * Captures the root's identity now. A root that cannot be captured yields a guard that never
   * vouches, so the prune removes nothing and reports itself incomplete.
   *
   * @param registered identity captured when the root was registered, or {@code null} if the root
   *     has no live registration
   */
  static PruneOps.PresenceGuard capture(Path root, RootIdentity registered) {
    return capture(root, registered, RootVolumeLedger.disabled());
  }

  /**
   * As {@link #capture(Path, RootIdentity)}, and the root must also be on the volume {@code
   * volumes} recorded for it (or for the nearest recorded folder above it), so a different volume
   * at the same path, even after a restart, vouches for nothing. Round 8: a root with nothing
   * recorded is unproven and never vouches; nothing is recorded here. An unreadable ledger is
   * "cannot tell": the guard never vouches. This is the same question {@link RootWitness} asks for
   * every other path that can end in a deletion.
   */
  static PruneOps.PresenceGuard capture(
      Path root, RootIdentity registered, RootVolumeLedger volumes) {
    Path normalized;
    try {
      normalized = root.toAbsolutePath().normalize();
    } catch (RuntimeException invalid) {
      return () -> false;
    }
    try {
      RootIdentity baseline = RootIdentity.capture(normalized);
      if (registered != null) {
        registered.requireSame(baseline, normalized);
      }
      RootWitness.requireRecordedVolume(volumes, normalized, baseline);
      return new RootPresenceGuard(normalized, baseline, registered);
    } catch (IOException unavailableOrChanged) {
      log.warn(
          "Root {} is unavailable or no longer the registered volume/folder; it cannot vouch for"
              + " any deletion ({})",
          normalized,
          unavailableOrChanged.getMessage());
      return () -> false;
    }
  }

  @Override
  public boolean rootCurrent() {
    if (!PathPresence.isReadableDirectory(root)) {
      return false;
    }
    try {
      RootIdentity current = RootIdentity.capture(root);
      baseline.requireSame(current, root);
      if (registered != null) {
        registered.requireSame(current, root);
      }
      return true;
    } catch (IOException unavailableOrChanged) {
      return false;
    }
  }
}
