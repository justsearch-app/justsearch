/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Task b6d536 b101, preservation: a helper the owner stops is still recorded once, REQUESTED with
 * the stop reason, after exit records became write-once. Real Windows processes; this also passes
 * on b599d4d7e.
 */
@EnabledOnOs({OS.WINDOWS, OS.LINUX})
final class OwnedHelperStopRecordTest {
  private final List<Map<String, Object>> records = new CopyOnWriteArrayList<>();

  private Process start(String command, String purpose) throws Exception {
    OwnedHelper.recordTo((type, fields) -> {
      Map<String, Object> record = new LinkedHashMap<>(fields);
      record.put("type", type.token());
      records.add(record);
      return true;
    }, new EngineIdentity(ProcessHandle.current().pid(),
        ProcessTerminator.forCurrentOs().creationFileTime(ProcessHandle.current().pid()), "exit-once"));
    return OwnedHelper.start(new ProcessBuilder(java.io.File.separatorChar != '\\'
        ? FakeChild.argv(command.startsWith("exit") ? "exit:0:0" : "sleep")
        : List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)).redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD), purpose);
  }

  private List<Map<String, Object>> exits(Process p) {
    return records.stream().filter(r -> "helper-exit".equals(r.get("type"))
        && Long.valueOf(p.pid()).equals(r.get("pid"))).toList();
  }

  @Test void aStoppedHelperIsRecordedOnceAsRequestedWithTheStopReason() throws Exception {
    Process p = start("Start-Sleep -Seconds 30", "stopped probe");
    Thread.sleep(500);
    OwnedHelper.terminate(p, "test stop");
    assertTrue(p.waitFor(10, TimeUnit.SECONDS));
    Thread.sleep(OwnedHelperTiming.GRACE_MS + 1_000);
    List<Map<String, Object>> exits = exits(p);
    assertEquals(1, exits.size(), exits.toString());
    assertEquals("REQUESTED", exits.getFirst().get("exitClass"));
    assertTrue(String.valueOf(exits.getFirst().get("reason")).contains("test stop"), exits.toString());
  }

  /** The grace a late owner gets; a constant here so the check also compiles on b599d4d7e. */
  private static final class OwnedHelperTiming {
    static final long GRACE_MS = 2_000;
  }
}
