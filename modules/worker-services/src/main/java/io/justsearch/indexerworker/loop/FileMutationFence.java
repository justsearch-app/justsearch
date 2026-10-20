/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.adapters.lucene.runtime.PruneOps;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The Worker's file-mutation fence. A writer holds it while it checks that its file still exists
 * and publishes the file's rows, so a prune applying its deletions inside it sees each file's rows
 * wholly or not at all.
 *
 * <p>It also records, for each prune that is watching ({@link #watch}), the files whose rows a
 * writer published under it: a writer that checked its file before the file was deleted can
 * publish the file's first rows after the prune's scan, and the prune judges those files again
 * before it finishes.
 */
public final class FileMutationFence {
  private final ReentrantLock lock = new ReentrantLock(true);
  /** One set of recorded paths per watching prune. */
  private final List<Set<String>> watches = new ArrayList<>();

  /** Enters the fence; pair with {@link #unlock()} in a finally block. */
  public void lock() {
    lock.lock();
  }

  public void unlock() {
    lock.unlock();
  }

  /** Runs {@code mutation} inside the fence. */
  public <T> T guard(Supplier<T> mutation) {
    lock.lock();
    try {
      return mutation.get();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Called by a writer, still inside the fence, after it published rows stored under {@code
   * path}, including when the publication failed part way. A prune takes the record inside the
   * fence too, so it sees every publication made before it entered.
   */
  public void published(String path) {
    synchronized (watches) {
      for (Set<String> watch : watches) {
        watch.add(path);
      }
    }
  }

  /** Records every publication from now until the returned record is closed. */
  public PruneOps.Publications watch() {
    Set<String> paths = new HashSet<>();
    synchronized (watches) {
      watches.add(paths);
    }
    return new PruneOps.Publications() {
      @Override
      public Set<String> take() {
        synchronized (watches) {
          Set<String> taken = Set.copyOf(paths);
          paths.clear();
          return taken;
        }
      }

      @Override
      public void close() {
        synchronized (watches) {
          watches.removeIf(watch -> watch == paths);
        }
      }
    };
  }
}
