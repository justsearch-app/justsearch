/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

/** Engine-owned backlog hysteresis. Picture reading never contributes to either count. */
public final class HeavyIndexingState {
  public static final int ENTER_BACKLOG = 50;
  public static final int EXIT_BACKLOG = 10;
  private boolean heavy;
  private long generation;

  public synchronized long generation() {
    return generation;
  }

  /** A sample started before a hold/reset cannot restore the old hysteresis. */
  public synchronized boolean updateIfCurrent(long observedGeneration, boolean running,
      long pendingFiles, long pendingEmbeddings) {
    return observedGeneration == generation ? update(running, pendingFiles, pendingEmbeddings) : heavy;
  }

  public synchronized boolean update(boolean running, long pendingFiles, long pendingEmbeddings) {
    if (!running) generation++;
    boolean next = running && (heavy
        ? pendingFiles >= EXIT_BACKLOG || pendingEmbeddings >= EXIT_BACKLOG
        : pendingFiles >= ENTER_BACKLOG || pendingEmbeddings >= ENTER_BACKLOG);
    if (heavy != next) {
      heavy = next;
      io.justsearch.core.status.StatusChangeCounter.process().changed();
    }
    return heavy;
  }
}
