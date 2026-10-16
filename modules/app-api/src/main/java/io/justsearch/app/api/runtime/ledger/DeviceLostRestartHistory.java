/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import io.justsearch.app.api.runtime.child.StopReason;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The Engine's earlier restarts for device loss: its own {@code engine-self-intent} records with
 * reason {@code DEVICE_LOST} in the {@code engine} family (reconciliation F10). The Engine is their
 * single writer ({@link EngineSelfIntentWriter}).
 *
 * <p>The count is used to limit restarts, so it must be complete: a line the reader had to skip
 * (corrupt, a newer schema or an unknown type) or a device-lost record without a timestamp makes
 * the history unreadable, and the caller fails closed.
 */
public final class DeviceLostRestartHistory {

  private DeviceLostRestartHistory() {}

  /**
   * When each earlier device-lost restart was recorded, in ledger order.
   *
   * @param ledgerDir the ledger directory ({@link LedgerFamily#directory})
   * @throws IOException when the family cannot be read completely
   */
  public static List<Instant> read(Path ledgerDir) throws IOException {
    Objects.requireNonNull(ledgerDir, "ledgerDir");
    LedgerReadResult result = ExitLedgerReader.read(ledgerDir, LedgerFamily.ENGINE);
    if (result.skippedLines() > 0) {
      throw new IOException("the engine ledger has " + result.skippedLines()
          + " unreadable line(s); device-lost restarts cannot be counted");
    }
    List<Instant> restarts = new ArrayList<>();
    for (LedgerRecord record : result.ofType(LedgerRecordType.ENGINE_SELF_INTENT)) {
      if (!record.text("reason").filter(StopReason.DEVICE_LOST.name()::equals).isPresent()) {
        continue;
      }
      restarts.add(record.at().orElseThrow(() -> new IOException(
          "a device-lost self-intent (seq " + record.seq() + ") has no timestamp")));
    }
    return List.copyOf(restarts);
  }
}
