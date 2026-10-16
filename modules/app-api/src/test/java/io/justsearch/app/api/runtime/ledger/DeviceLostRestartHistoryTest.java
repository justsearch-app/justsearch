/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.api.runtime.child.StopReason;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec A §7.3 and reconciliation F10: the Engine counts its earlier device-lost restarts from its
 * own {@code engine-self-intent} records, and a history it cannot read completely is an error the
 * chat controller fails closed on.
 */
final class DeviceLostRestartHistoryTest {

  private static final EngineSelfIntentWriter.EngineIdentity ENGINE =
      new EngineSelfIntentWriter.EngineIdentity(4242L, Optional.empty(), "inst-1");

  @TempDir Path dataDir;

  private void record(Instant at, StopReason reason, Optional<StopDetail> detail, int code) {
    var writer = new EngineSelfIntentWriter(dataDir, ENGINE, ExitLedgerWriterTest.ENGINE,
        Clock.fixed(at, ZoneOffset.UTC));
    assertTrue(writer.record(reason, detail, code));
  }

  private Path ledgerDir() {
    return LedgerFamily.directory(dataDir);
  }

  @Test
  void anEmptyDataDirectoryHasNoEarlierRestarts() throws IOException {
    assertEquals(List.of(), DeviceLostRestartHistory.read(ledgerDir()));
  }

  @Test
  void onlyDeviceLostSelfIntentsCountInLedgerOrder() throws IOException {
    Instant first = Instant.parse("2026-10-06T10:00:00Z");
    Instant second = Instant.parse("2026-10-06T10:45:00Z");
    record(first, StopReason.DEVICE_LOST, Optional.empty(), 4);
    record(Instant.parse("2026-10-06T10:20:00Z"), StopReason.ENGINE_RESTART,
        Optional.of(StopDetail.HANG), 4);
    record(Instant.parse("2026-10-06T10:30:00Z"), StopReason.QUIT, Optional.empty(), 0);
    record(second, StopReason.DEVICE_LOST, Optional.empty(), 4);

    assertEquals(List.of(first, second), DeviceLostRestartHistory.read(ledgerDir()));
  }

  @Test
  void anUnreadableLineMakesTheHistoryUnreadable() throws IOException {
    record(Instant.parse("2026-10-06T10:00:00Z"), StopReason.DEVICE_LOST, Optional.empty(), 4);
    Files.writeString(LedgerFamily.ENGINE.current(ledgerDir()), "not a ledger line\n",
        StandardCharsets.UTF_8, StandardOpenOption.APPEND);

    var failure = assertThrows(IOException.class, () -> DeviceLostRestartHistory.read(ledgerDir()));
    assertTrue(failure.getMessage().contains("1 unreadable line"), failure.getMessage());
  }

  @Test
  void aWriteStillInProgressAtTheTailIsNotAnError() throws IOException {
    Instant at = Instant.parse("2026-10-06T10:00:00Z");
    record(at, StopReason.DEVICE_LOST, Optional.empty(), 4);
    Files.writeString(LedgerFamily.ENGINE.current(ledgerDir()), "{\"schema\":",
        StandardCharsets.UTF_8, StandardOpenOption.APPEND);

    assertEquals(List.of(at), DeviceLostRestartHistory.read(ledgerDir()));
  }
}
