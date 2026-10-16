/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.api.runtime.child.StopReason;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("engine-self-intent writer")
final class EngineSelfIntentWriterTest {

  private static final EngineSelfIntentWriter.EngineIdentity ENGINE =
      new EngineSelfIntentWriter.EngineIdentity(4242L, Optional.of("134042121021140000"), "inst-1");

  @TempDir Path dataDir;

  @Test
  @DisplayName("writes the Engine's identity, reason, detail and expected code to engine.v1.jsonl")
  void writesSelfIntent() throws IOException {
    EngineSelfIntentWriter writer = writer(dataDir);

    assertTrue(writer.record(StopReason.DEVICE_LOST, Optional.of(StopDetail.ESCALATED), 5));
    assertTrue(writer.record(StopReason.QUIT, Optional.empty(), 0));
    assertTrue(writer.healthy());

    LedgerReadResult read =
        ExitLedgerReader.read(LedgerFamily.directory(dataDir), LedgerFamily.ENGINE);
    assertEquals(2, read.ofType(LedgerRecordType.ENGINE_SELF_INTENT).size());
    LedgerRecord first = read.records().get(0);
    assertEquals("DEVICE_LOST", first.text("reason").orElseThrow());
    assertEquals("escalated", first.text("detail").orElseThrow());
    assertEquals(5, first.field("expectedCode").asInt());
    assertEquals(4242L, first.field("engine").path("pid").asLong());
    assertEquals("134042121021140000", first.field("engine").path("creationFileTime").asString());
    assertEquals("inst-1", first.field("engine").path("instanceId").asString());
    assertTrue(read.records().get(1).field("detail").isNull());
    assertFalse(Files.exists(LedgerFamily.CHILDREN.current(LedgerFamily.directory(dataDir))));
  }

  @Test
  @DisplayName("only a reason that ends the Engine is a self-intent")
  void onlyEngineEndingReasons() {
    EngineSelfIntentWriter writer = writer(dataDir);
    assertThrows(IllegalArgumentException.class,
        () -> writer.record(StopReason.RECYCLE, Optional.empty(), 0));
  }

  @Test
  @DisplayName("an unwritable ledger never stops the exit, and the writer reports it")
  void unwritableLedger() throws IOException {
    Path blocked = dataDir.resolve("blocked");
    Files.createDirectories(blocked.resolve("runtime"));
    Files.writeString(blocked.resolve("runtime/exit-ledger"), "not a directory");

    EngineSelfIntentWriter writer = writer(blocked);
    assertFalse(writer.record(StopReason.QUIT, Optional.empty(), 0));
    assertFalse(writer.healthy());
  }

  @Test
  @DisplayName("the identity refuses values a reader could not use")
  void identityValidation() {
    assertThrows(IllegalArgumentException.class,
        () -> new EngineSelfIntentWriter.EngineIdentity(0L, Optional.empty(), "i"));
    assertThrows(IllegalArgumentException.class,
        () -> new EngineSelfIntentWriter.EngineIdentity(1L, Optional.of("0x1F"), "i"));
    assertThrows(IllegalArgumentException.class,
        () -> new EngineSelfIntentWriter.EngineIdentity(1L, Optional.empty(), " "));
  }

  private static EngineSelfIntentWriter writer(Path dataDir) {
    return new EngineSelfIntentWriter(dataDir, ENGINE, ExitLedgerWriterTest.ENGINE,
        ExitLedgerWriterTest.CLOCK);
  }
}
