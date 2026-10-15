/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.runtime.child.LedgerSink;
import io.justsearch.app.api.runtime.child.ProcessTerminator;
import io.justsearch.app.api.runtime.containment.EngineContainment;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** W3 on real Tika/Tesseract inside a disposable job; no application stack or GPU. */
@EnabledOnOs(OS.WINDOWS)
final class TikaProbeOwnerIntentTest {
  // b6d536 b101 adaptation: the first-pass OCR configuration is a TesseractOCRConfig bean; it
  // launches nothing, so it writes no window (a window per parse was the main ledger cost).
  @Test void firstPassConfigurationLaunchesNothingAndWritesNoWindow() throws Exception {
    Process probe = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
        ConfigurationProbe.class.getName()).redirectErrorStream(true).start();
    String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(probe.waitFor(30, TimeUnit.SECONDS), output);
    assertEquals(0, probe.exitValue(), output);
    assertTrue(output.contains("configuration.windows=0"), "configuration declares no window: " + output);
    assertTrue(output.contains("configuration.launches=0"), "configuration starts no process: " + output);
  }

  public static final class ConfigurationProbe {
    public static void main(String[] args) throws Exception {
      var job = EngineContainment.installAtBoot();
      if (job.status() != EngineContainment.Status.CONTAINED) throw new AssertionError(job);
      List<Map<String, Object>> records = new CopyOnWriteArrayList<>();
      LedgerSink sink = (type, fields) -> {
        var record = new java.util.LinkedHashMap<String, Object>(fields);
        record.put("type", type.token()); records.add(record); return true;
      };
      var self = new EngineIdentity(ProcessHandle.current().pid(),
          ProcessTerminator.forCurrentOs().creationFileTime(ProcessHandle.current().pid()), "first-pass");
      job.recordProcesses(sink, self);
      io.justsearch.app.api.runtime.child.OwnedHelper.recordTo(sink, self);
      Thread.sleep(500); // the job's initial inventory (this JVM and its console host) settles
      List<Object> before = records.stream().map(r -> r.get("witnessId")).toList();
      var method = StructuredContentExtractor.class.getDeclaredMethod("parseContextWithMarkedPdfContent");
      method.setAccessible(true);
      for (int i = 0; i < 20; i++) method.invoke(null);
      job.finishProcessRecording(Duration.ofSeconds(3));
      System.out.println("configuration.windows=" + records.stream()
          .filter(r -> String.valueOf(r.get("type")).startsWith("helper-window")).count());
      System.out.println("configuration.launches=" + records.stream()
          .filter(r -> "job-process-start".equals(r.get("type")) && !before.contains(r.get("witnessId"))).count());
      records.forEach(r -> System.out.println("probe.record=" + r));
    }
  }

  @Test void normalTesseractAvailabilityIsCoveredByClosedOwnerIntent() throws Exception {
    var runtime = TikaOcrRuntime.resolve();
    org.junit.jupiter.api.Assumptions.assumeTrue(runtime.available(), "real Tesseract unavailable");
    Process probe = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
        Probe.class.getName(), runtime.executableDirectory().toString()).redirectErrorStream(true).start();
    String birth = ProcessTerminator.forCurrentOs().creationFileTime(probe.pid()).orElseThrow();
    String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(probe.waitFor(30, TimeUnit.SECONDS), "probe identity " + probe.pid() + "/" + birth);
    assertEquals(0, probe.exitValue(), output);
    assertTrue(output.contains("tika.available=true"), output);
    assertTrue(output.contains("owner.closed=true"), "the successful code-1 probe needs owner intent: " + output);
    assertTrue(output.contains("witness.code1=true"), output);
  }

  public static final class Probe {
    public static void main(String[] args) throws Exception {
      var job = EngineContainment.installAtBoot();
      if (job.status() != EngineContainment.Status.CONTAINED) throw new AssertionError(job);
      List<Map<String, Object>> records = new CopyOnWriteArrayList<>();
      LedgerSink sink = (type, fields) -> {
        var record = new java.util.LinkedHashMap<String, Object>(fields);
        record.put("type", type.token()); records.add(record); return true;
      };
      job.recordProcesses(sink, new EngineIdentity(ProcessHandle.current().pid(),
          ProcessTerminator.forCurrentOs().creationFileTime(ProcessHandle.current().pid()), "tika-probe"));
      AutoCloseable intent = () -> {};
      try {
        var helper = Class.forName("io.justsearch.app.api.runtime.child.OwnedHelper");
        intent = (AutoCloseable) helper.getMethod("window", String.class, String.class, Duration.class)
            .invoke(null, "tesseract.exe", "Tika Tesseract availability", Duration.ofSeconds(10));
      } catch (ClassNotFoundException before) { /* assertion, not linkage failure */ }
      boolean available;
      AutoCloseable close = intent;
      try (close) {
        var parserClass = Class.forName("org.apache.tika.parser.ocr.TesseractOCRParser");
        var parser = parserClass.getConstructor().newInstance();
        parserClass.getMethod("setTesseractPath", String.class).invoke(parser, args[0]);
        available = (boolean) parserClass.getMethod("hasTesseract").invoke(parser);
      }
      job.finishProcessRecording(Duration.ofSeconds(3));
      System.out.println("tika.available=" + available);
      System.out.println("owner.closed=" + (records.stream().anyMatch(r -> "helper-window-start".equals(r.get("type")))
          && records.stream().anyMatch(r -> "helper-window-end".equals(r.get("type")))));
      System.out.println("witness.code1=" + records.stream().anyMatch(r -> "job-process-exit".equals(r.get("type"))
          && Integer.valueOf(1).equals(r.get("code"))));
      records.forEach(r -> System.out.println("probe.record=" + r));
    }
  }
}
