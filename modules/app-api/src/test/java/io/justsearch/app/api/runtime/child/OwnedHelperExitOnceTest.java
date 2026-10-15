/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Task b6d536 b101: one helper-exit record per helper exit, written once with its final class and
 * reason, on real Windows processes. The same assertions run on b599d4d7e, where the OS exit
 * notification raced the owner's wait and wrote a second record.
 */
@EnabledOnOs(OS.WINDOWS)
final class OwnedHelperExitOnceTest {
  private final List<Map<String, Object>> records = new CopyOnWriteArrayList<>();

  private Process start(String command, String purpose) throws Exception {
    OwnedHelper.recordTo((type, fields) -> {
      Map<String, Object> record = new LinkedHashMap<>(fields);
      record.put("type", type.token());
      records.add(record);
      return true;
    }, new EngineIdentity(ProcessHandle.current().pid(),
        ProcessTerminator.forCurrentOs().creationFileTime(ProcessHandle.current().pid()), "exit-once"));
    return OwnedHelper.start(new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
        "-Command", command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD), purpose);
  }

  private List<Map<String, Object>> exits(Process p) {
    return records.stream().filter(r -> "helper-exit".equals(r.get("type"))
        && Long.valueOf(p.pid()).equals(r.get("pid"))).toList();
  }

  @Test void anExitSeenBeforeTheOwnersWaitIsRecordedOnceAsRequested() throws Exception {
    Process p = start("exit 0", "exit-once probe");
    // Let the OS exit notification run before the owner waits: the order that wrote two records.
    p.toHandle().onExit().get(15, TimeUnit.SECONDS);
    Thread.sleep(300);
    assertTrue(p.waitFor(10, TimeUnit.SECONDS));
    Thread.sleep(OwnedHelperTiming.GRACE_MS + 1_000);
    List<Map<String, Object>> exits = exits(p);
    assertEquals(1, exits.size(), "one helper-exit record per helper exit: " + exits);
    assertEquals("REQUESTED", exits.getFirst().get("exitClass"));
    assertTrue(String.valueOf(exits.getFirst().get("reason")).contains("exit-once probe"), exits.toString());
  }

  @Test void anExitNobodyAwaitedOrStoppedIsRecordedOnceWithAReason() throws Exception {
    Process p = start("exit 0", "unawaited probe");
    p.toHandle().onExit().get(15, TimeUnit.SECONDS);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (exits(p).isEmpty() && System.nanoTime() < deadline) Thread.sleep(50);
    Thread.sleep(OwnedHelperTiming.GRACE_MS + 1_000);
    List<Map<String, Object>> exits = exits(p);
    assertEquals(1, exits.size(), exits.toString());
    assertEquals("UNEXPECTED", exits.getFirst().get("exitClass"));
    assertNotNull(exits.getFirst().get("reason"), "an unexpected exit says why it is unexpected");
    assertTrue(String.valueOf(exits.getFirst().get("reason")).contains("no owner wait or stop request"),
        exits.toString());
  }

  /** The grace a late owner gets; a constant here so the check also compiles on b599d4d7e. */
  private static final class OwnedHelperTiming {
    static final long GRACE_MS = 2_000;
  }
}
