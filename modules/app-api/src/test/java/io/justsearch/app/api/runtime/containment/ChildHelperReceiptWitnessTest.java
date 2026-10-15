/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.child.LedgerSink;
import io.justsearch.app.api.runtime.child.OwnedHelper;
import io.justsearch.app.api.runtime.child.ProcessTerminator;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import io.justsearch.app.api.runtime.ledger.ExitLedgerWriter;
import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import io.justsearch.app.api.runtime.ledger.LedgerWriterId;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Task b6d536 b101, verifier round 2 (T7): real helper receipts from a child must inform the Engine witness. */
@EnabledOnOs(OS.WINDOWS)
@Timeout(75)
final class ChildHelperReceiptWitnessTest {
  private static final String PURPOSE = "unwindowed child OCR";
  @TempDir Path dir;

  @Test void childJvmAwaitedTesseractReceiptReachesWitnessWithoutAWindow() throws Exception {
    Process probe = java(Probe.class, dir.toString(),
        "F:\\scoop\\apps\\tesseract\\current\\tesseract.exe").redirectErrorStream(true).start();
    System.out.println("probe identity=" + probe.pid() + "/"
        + ProcessTerminator.forCurrentOs().creationFileTime(probe.pid()).orElseThrow());
    String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    System.out.println(output);
    assertTrue(probe.waitFor(60, TimeUnit.SECONDS), output);
    assertEquals(0, probe.exitValue(), output);
    assertTrue(output.contains("native.creator=child"), output);
    assertTrue(output.contains("child.actualReceipt=REQUESTED|" + PURPOSE + ": awaited completion"), output);
    assertTrue(output.contains("tesseract-witness=REQUESTED|" + PURPOSE + ": awaited completion"),
        "the witness must carry the intent already recorded by the child's real helper owner: " + output);
  }

  private static ProcessBuilder java(Class<?> entry, String... args) {
    List<String> command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElseThrow(),
        "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"), entry.getName()));
    command.addAll(List.of(args));
    return new ProcessBuilder(command);
  }

  public static final class Probe {
    public static void main(String[] args) throws Exception {
      EngineContainment job = EngineContainment.installAtBoot();
      if (job.status() != EngineContainment.Status.CONTAINED) throw new AssertionError(job);
      ProcessHandle self = ProcessHandle.current();
      ProcessTerminator os = ProcessTerminator.forCurrentOs();
      Path directory = Path.of(args[0]).toAbsolutePath();
      ExitLedgerWriter writer = ExitLedgerWriter.rotating(directory, LedgerFamily.CHILDREN,
          LedgerWriterId.currentProcess(LedgerWriterId.Role.ENGINE, self.info().startInstant().orElseThrow()),
          Clock.systemUTC());
      writer.startSession();
      List<Map<String, Object>> records = new CopyOnWriteArrayList<>();
      LedgerSink sink = (type, fields) -> {
        var row = new java.util.LinkedHashMap<String, Object>(fields);
        row.put("type", type.token());
        records.add(row);
        return writer.append(type, fields);
      };
      EngineIdentity owner = new EngineIdentity(self.pid(), os.creationFileTime(self.pid()), "child-receipt");
      job.recordProcesses(sink, owner);
      OwnedHelper.recordTo(sink, owner, directory);
      Process child = java(Child.class, "--helper-ledger=" + directory, args[1]).redirectErrorStream(true).start();
      String childBirth = os.creationFileTime(child.pid()).orElseThrow();
      System.out.println("child identity=" + child.pid() + "/" + childBirth);
      String childOut = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      System.out.print(childOut);
      if (!child.waitFor(20, TimeUnit.SECONDS) || child.exitValue() != 0) throw new AssertionError(childOut);
      long pid = Long.parseLong(childOut.lines().filter(s -> s.startsWith("tesseract-pid="))
          .findFirst().orElseThrow().substring("tesseract-pid=".length()));
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < until && exit(records, pid) == null) Thread.sleep(25);
      job.finishProcessRecording(Duration.ofSeconds(3));
      var start = records.stream().filter(r -> "job-process-start".equals(r.get("type"))
          && Long.valueOf(pid).equals(r.get("pid"))).findFirst().orElseThrow();
      System.out.println("native.creator=" + (Long.valueOf(child.pid()).equals(start.get("parentPid"))
          && childBirth.equals(start.get("parentCreationFileTime")) ? "child" : "other " + start));
      var json = JsonMapper.builder().build();
      for (String line : Files.readAllLines(LedgerFamily.CHILDREN.current(directory), StandardCharsets.UTF_8)) {
        if (!line.contains("\"helper-exit\"")) continue;
        var receipt = json.readTree(line);
        if (receipt.path("pid").longValue() == pid && receipt.path("owner").path("pid").longValue() == child.pid()) {
          System.out.println("child.actualReceipt=" + receipt.path("exitClass").asString() + "|"
              + (receipt.path("reason").isString() ? receipt.path("reason").asString() : "null"));
          System.out.println("actual ledger receipt=" + line);
        }
      }
      var ended = exit(records, pid);
      System.out.println("tesseract-witness=" + (ended == null ? "missing"
          : ended.get("exitClass") + "|" + ended.get("reason") + "|" + ended.get("intentBasis")));
      records.stream().filter(r -> Long.valueOf(pid).equals(r.get("pid")))
          .forEach(r -> System.out.println("native receipt=" + r));
    }

    private static Map<String, Object> exit(List<Map<String, Object>> records, long pid) {
      return records.stream().filter(r -> "job-process-exit".equals(r.get("type"))
          && Long.valueOf(pid).equals(r.get("pid"))).findFirst().orElse(null);
    }
  }

  public static final class Child {
    public static void main(String[] args) throws Exception {
      OwnedHelper.recordChild(args);
      Process process = OwnedHelper.start(new ProcessBuilder(args[1], "stdin", "stdout", "--psm", "6")
          .redirectErrorStream(true), PURPOSE);
      System.out.println("tesseract-pid=" + process.pid());
      System.out.println("tesseract identity=" + process.pid() + "/"
          + ProcessTerminator.forCurrentOs().creationFileTime(process.pid()).orElseThrow());
      Thread.sleep(400);
      BufferedImage pixels = new BufferedImage(80, 50, BufferedImage.TYPE_INT_RGB);
      var graphics = pixels.createGraphics();
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, 80, 50);
      graphics.dispose();
      var bytes = new ByteArrayOutputStream();
      ImageIO.write(pixels, "png", bytes);
      process.getOutputStream().write(bytes.toByteArray());
      process.getOutputStream().close();
      System.out.print(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
      if (!process.waitFor(10, TimeUnit.SECONDS)) throw new AssertionError("Tesseract did not end");
      Thread.sleep(3000); // keep owner alive while the Engine resolves its real exit
    }
  }
}
