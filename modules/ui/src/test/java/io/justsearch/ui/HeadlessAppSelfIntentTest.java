/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.runtime.ledger.ExitLedgerReader;
import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import io.justsearch.app.api.runtime.ledger.LedgerRecord;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec B §6.1: the Engine's self-intent recorder appends {@code engine-self-intent} to the data
 * directory's {@code engine.v1.jsonl}, naming this process the way the supervisor's records name
 * it: pid, creation FILETIME (Windows) and the manifest instance.
 */
final class HeadlessAppSelfIntentTest {

  @Test
  void selfIntentsLandInTheEngineLedgerNamingThisProcess(@TempDir Path dataDir) throws Exception {
    var recorder = HeadlessApp.engineSelfIntent(dataDir, "inst-self");
    recorder.record(StopReason.ENGINE_RESTART, Optional.of(StopDetail.HANG), 0);

    List<LedgerRecord> records =
        ExitLedgerReader.read(LedgerFamily.directory(dataDir), LedgerFamily.ENGINE).records();
    assertEquals(1, records.size(), records.toString());
    LedgerRecord intent = records.getFirst();
    assertEquals(LedgerRecordType.ENGINE_SELF_INTENT, intent.type());
    assertEquals(ProcessHandle.current().pid(), intent.field("engine").path("pid").asLong());
    assertEquals("inst-self", intent.field("engine").path("instanceId").asString());
    assertEquals(Optional.of("ENGINE_RESTART"), intent.text("reason"));
    assertEquals(Optional.of("hang"), intent.text("detail"));
    assertEquals(0, intent.field("expectedCode").asInt());
    assertEquals(ProcessHandle.current().pid(), intent.writer().pid());
    if (System.getProperty("os.name", "").startsWith("Windows")) {
      assertTrue(intent.field("engine").path("creationFileTime").isString(),
          "the Engine's birth is named on Windows: " + intent.line());
    }
  }
}
