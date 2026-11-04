/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

/** Engine-owned backlog hysteresis. Picture reading never contributes to either count. */
public final class HeavyIndexingState {
  public static final int ENTER_BACKLOG = 50;
  public static final int EXIT_BACKLOG = 10;
  private boolean heavy;
  public synchronized boolean update(boolean running, long pendingFiles, long pendingEmbeddings) {
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
