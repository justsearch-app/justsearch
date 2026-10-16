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
import java.nio.file.StandardOpenOption;
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

/** Task b6d536 b101, verifier round 2: real native-job Tesseract exits, with owner facts from the product ledger. */
@EnabledOnOs(OS.WINDOWS)
@Timeout(100)
final class ChildWindowBoundaryTest {
  private static final String TESSERACT = "F:\\scoop\\apps\\tesseract\\current\\tesseract.exe";
  private static final String REASON = "real boundary OCR";
  @TempDir Path dir;

  @Test void unendedChildWindowStillAdmitsAfterDeadlineUntilOwnerEnds() throws Exception {
    String output = probe("deadline");
    assertTrue(output.contains("child.window.unendedAtLaunch=true"), output);
    assertTrue(output.contains("native.creator=child"), output);
    assertTrue(output.contains("tesseract-witness=REQUESTED|" + REASON + "|owner window"), output);
  }

  @Test void rotationWithAlreadyGrownReplacementStillFindsChildWindow() throws Exception {
    String output = probe("rotation");
    assertTrue(output.contains("ledger.rotatedBeforeChild=true"), output);
    assertTrue(output.contains("native.creator=child"), output);
    assertTrue(output.contains("tesseract-witness=REQUESTED|" + REASON + "|owner window"), output);
  }

  private String probe(String mode) throws Exception {
    Process process = java(Probe.class, mode, dir.toString(), TESSERACT).redirectErrorStream(true).start();
    System.out.println("probe identity=" + process.pid() + "/"
        + ProcessTerminator.forCurrentOs().creationFileTime(process.pid()).orElseThrow());
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    System.out.println(output);
    assertTrue(process.waitFor(90, TimeUnit.SECONDS), output);
    assertEquals(0, process.exitValue(), output);
    return output;
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
      Path dir = Path.of(args[1]).toAbsolutePath();
      if (args[0].equals("rotation")) {
        Files.writeString(LedgerFamily.CHILDREN.current(dir), "\n".repeat(65536), StandardCharsets.UTF_8);
      }
      ExitLedgerWriter writer = ExitLedgerWriter.rotating(dir, LedgerFamily.CHILDREN,
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
      EngineIdentity owner = new EngineIdentity(self.pid(), os.creationFileTime(self.pid()), "real-boundary");
      job.recordProcesses(sink, owner);
      OwnedHelper.recordTo(sink, owner, dir);
      if (args[0].equals("rotation")) {
        Files.move(LedgerFamily.CHILDREN.current(dir), LedgerFamily.CHILDREN.rotated(dir));
        System.out.println("ledger.rotatedBeforeChild=true");
      }
      long tesseractPid;
      {
        List<String> childArgs = new ArrayList<>(List.of("--helper-ledger=" + dir));
        childArgs.add(args[0]);
        childArgs.add(args[2]);
        Process child = java(Child.class, childArgs.toArray(String[]::new)).redirectErrorStream(true).start();
        String childBirth = os.creationFileTime(child.pid()).orElseThrow();
        System.out.println("child identity=" + child.pid() + "/" + childBirth);
        String out = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        System.out.print(out);
        if (!child.waitFor(20, TimeUnit.SECONDS) || child.exitValue() != 0) throw new AssertionError(out);
        tesseractPid = Long.parseLong(out.lines().filter(s -> s.startsWith("tesseract-pid="))
            .findFirst().orElseThrow().substring("tesseract-pid=".length()));
        var start = records.stream().filter(r -> "job-process-start".equals(r.get("type"))
            && Long.valueOf(tesseractPid).equals(r.get("pid"))).findFirst().orElseThrow();
        System.out.println("native.creator=" + (Long.valueOf(child.pid()).equals(start.get("parentPid"))
            && childBirth.equals(start.get("parentCreationFileTime")) ? "child" : "other " + start));
      }
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < until && exit(records, tesseractPid) == null) Thread.sleep(25);
      job.finishProcessRecording(Duration.ofSeconds(3));
      var exit = exit(records, tesseractPid);
      System.out.println("tesseract-witness=" + (exit == null ? "missing"
          : exit.get("exitClass") + "|" + exit.get("reason") + "|" + exit.get("intentBasis")));
      records.stream().filter(r -> Long.valueOf(tesseractPid).equals(r.get("pid")))
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
      boolean rotation = args[args.length - 2].equals("rotation");
      try (var ignored = OwnedHelper.window("tesseract.exe", REASON,
          rotation ? Duration.ofSeconds(30) : Duration.ofMillis(200))) {
        if (rotation) {
          Path ledger = Path.of(args[0].substring("--helper-ledger=".length()));
          // Real rotation plus file growth before the next witnessed helper exit. Blank ledger
          // lines are permitted; this stands for other records that grow the replacement file.
          Files.writeString(LedgerFamily.CHILDREN.current(ledger), "\n".repeat(200000), StandardCharsets.UTF_8,
              StandardOpenOption.APPEND);
        } else {
          Thread.sleep(500);
        }
        System.out.println("child.window.unendedAtLaunch=true");
        tesseract(args[args.length - 1]);
        Thread.sleep(3000); // let the Engine's witness resolve while this owner is still alive
      }
    }
  }

  private static long tesseract(String executable) throws Exception {
    Process process = new ProcessBuilder(executable, "stdin", "stdout", "--psm", "6")
        .redirectErrorStream(true).start();
    System.out.println("tesseract-pid=" + process.pid());
    System.out.println("tesseract identity=" + process.pid() + "/"
        + ProcessTerminator.forCurrentOs().creationFileTime(process.pid()).orElseThrow());
    Thread.sleep(400); // keep the real process alive long enough for native membership capture
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
    return process.pid();
  }
}
