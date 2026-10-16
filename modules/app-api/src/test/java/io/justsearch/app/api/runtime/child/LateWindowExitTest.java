/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;
import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.app.api.runtime.ledger.*;
import java.util.*;
import java.util.concurrent.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
@Tag("windows")
@EnabledOnOs(value = OS.WINDOWS, disabledReason = "Owner windows require native Windows creation identities")
final class LateWindowExitTest {
  @Test void realHelperInsideAWindowNeverBecomesUnexpectedBeforeALateOwnerWait() throws Exception {
    String javaExecutable = ProcessHandle.current().info().command().orElseThrow();
    String image = java.nio.file.Path.of(javaExecutable).getFileName().toString();
    var field = OwnedHelper.class.getDeclaredField("recorder"); field.setAccessible(true);
    Object saved = field.get(null); List<Map<String,Object>> rows = new CopyOnWriteArrayList<>();
    OwnedHelper.recordTo((type, fields) -> { var row = new LinkedHashMap<String,Object>(fields);
      row.put("type",type.token()); rows.add(row); return true; },
      new EngineSelfIntentWriter.EngineIdentity(ProcessHandle.current().pid(),
          ProcessTerminator.forCurrentOs().creationFileTime(ProcessHandle.current().pid()), "late-window-wait"));
    AutoCloseable open; AutoCloseable active;
    try {
      var factory=OwnedHelper.class.getMethod("sharedWindow",String.class,String.class,Duration.class);
      Object shared=factory.invoke(null,image,"OCR open window",Duration.ofMinutes(1));
      open=(AutoCloseable)shared; active=(AutoCloseable)shared.getClass().getMethod("acquire").invoke(shared);
    } catch(NoSuchMethodException before) {
      open=OwnedHelper.window(image,"OCR open window",Duration.ofMinutes(1)); active=()->{};
    }
    try (var ignored=open; var unused=active) {
      Process p=OwnedHelper.start(new ProcessBuilder(javaExecutable,"--version")
          .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD),"late OCR owner");
      System.out.println("real helper identity="+p.pid()+"/"+ProcessTerminator.forCurrentOs().creationFileTime(p.pid()).orElseThrow());
      p.toHandle().onExit().get(10,TimeUnit.SECONDS);
      Thread.sleep(2_600); // the open owner window is still live throughout
      assertTrue(p.waitFor(10,TimeUnit.SECONDS));
      var exits=rows.stream().filter(r->"helper-exit".equals(r.get("type"))&&Long.valueOf(p.pid()).equals(r.get("pid"))).toList();
      System.out.println("late owner exits="+exits);
      assertEquals(1,exits.size(),"one receipt for the real helper exit");
      assertEquals("REQUESTED",exits.getFirst().get("exitClass"),"helper exited inside an open owner window");
    } finally { field.set(null,saved); }
  }
}
