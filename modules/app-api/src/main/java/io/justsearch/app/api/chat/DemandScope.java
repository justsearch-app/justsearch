/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

import io.justsearch.app.api.status.operating.BulkWorkKind;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Declares, at the call site, what the generation calls made inside a block are (Spec E step E2).
 *
 * <p>Query-assist callers wrap their {@code chatCompletion} call in {@code
 * DemandScope.call(DemandKind.ASSIST, ...)}; the chat server then opens an ASSIST lease, which
 * keeps a drain waiting and fails fast while admission is closed but is not counted as generating,
 * so it never slows indexing (reconciliation v2: "Chat generating (HUMAN_CHAT, AGENT_CHAT,
 * BACKGROUND GENERATE leases; not ASSIST)").
 *
 * <p>VDU wraps its own vision and enrichment calls in {@code DemandScope.bulk(BulkWorkKind.VDU,
 * ...)}: VDU's generation is bulk work, not chat (Spec E §1.2: "VDU's own generation is bulk
 * (`BulkWorkKind.VDU`). Status must not report it as \"chat is answering\""), so its leases keep
 * a drain waiting but are not counted as generating.
 *
 * <p>The declaration is read synchronously by the {@code OnlineAiService} call on the calling
 * thread, before any asynchronous hand-off; a call outside a scope is a generation as before.
 */
public final class DemandScope {

  private static final ThreadLocal<DemandKind> DEMAND = new ThreadLocal<>();
  private static final ThreadLocal<BulkWorkKind> BULK = new ThreadLocal<>();

  private DemandScope() {}

  /** Runs {@code call} with {@code kind} as the current demand, restoring the previous one. */
  public static <T> T call(DemandKind kind, Supplier<T> call) {
    return within(DEMAND, Objects.requireNonNull(kind, "kind"), call);
  }

  /** Runs {@code call} as bulk work of {@code kind}, restoring the previous declaration. */
  public static <T> T bulk(BulkWorkKind kind, Supplier<T> call) {
    return within(BULK, Objects.requireNonNull(kind, "kind"), call);
  }

  /** The demand declared on this thread, or {@code null} outside any scope. */
  public static DemandKind current() {
    return DEMAND.get();
  }

  /** The bulk work declared on this thread, or {@code null} outside any bulk scope. */
  public static BulkWorkKind currentBulk() {
    return BULK.get();
  }

  private static <K, T> T within(ThreadLocal<K> slot, K value, Supplier<T> call) {
    Objects.requireNonNull(call, "call");
    K previous = slot.get();
    slot.set(value);
    try {
      return call.get();
    } finally {
      if (previous == null) {
        slot.remove();
      } else {
        slot.set(previous);
      }
    }
  }
}
