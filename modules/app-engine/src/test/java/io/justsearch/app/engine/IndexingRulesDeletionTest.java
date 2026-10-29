/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.core.scheduling.IndexingSchedule;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.IndexingLoop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** S38.8: recorded deletion stays usable while the actual processing lane is held by both rules. */
final class IndexingRulesDeletionTest {
  @Test
  @Timeout(30)
  void recordedForgetRemovesIndexedDataWhileScheduleAndBatteryKeepProcessingHeld(@TempDir Path directory)
      throws Exception {
    Path watched = Files.createDirectory(directory.resolve("watched"));
    Path exact = Files.writeString(watched.resolve("private.txt"), "original private bytes");
    Path sibling = Files.writeString(watched.resolve("sibling.txt"), "original sibling bytes");
    try (var f = new EngineForgetInProcessTest.Fixture(directory.resolve("process"), List.of(watched),
        Map.of("justsearch.extraction.sandbox.mode", "in_process"))) {
      f.seed(exact, "private indexed text"); f.seed(sibling, "public sibling text");
      var bus = (WorkerSignalBus) EngineForgetInProcessTest.field(f.loop, IndexingLoop.class, "signalBus");
      var hold = assertDoesNotThrow(bus::indexingHold);
      assertNotNull(hold, "use the actual processing lane's hold, not another gauge");
      hold.setLocalTime(() -> new io.justsearch.core.scheduling.IndexingHold.LocalMinute(1, 500));
      hold.setOnBattery(true);
      hold.setPolicy(false, null, null, new IndexingSchedule(true, List.of(1), 600, 660), true);
      assertTrue(bus.isIndexingHeld());
      var result = f.forget(exact, "FILE");
      var outcome = JsonMapper.builder().build().valueToTree(result.structuredData().get("forget"));
      assertEquals("COMPLETE", outcome.path("status").asString(), result.message());
      assertEquals("CONFIRMED", outcome.path("removal").asString());
      assertEquals(0, f.runtime.indexCountOps().countByIdAndChunksStrict(EngineForgetInProcessTest.key(exact)));
      assertEquals(3, f.runtime.indexCountOps().countByIdAndChunksStrict(EngineForgetInProcessTest.key(sibling)));
      assertTrue(f.queue.forgetJobsAbsent(EngineForgetInProcessTest.key(exact), false));
      assertEquals("original private bytes", Files.readString(exact));
      assertTrue(bus.isIndexingHeld(), "deletion did not require clearing either restriction");
      assertEquals(List.of("SCHEDULE", "ON_BATTERY"), hold.snapshot().reasons());
    }
  }
}
