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
  @Test void firstPassConfigurationProbeHasItsOwnWindow() throws Exception {
    var records = new CopyOnWriteArrayList<Map<String, Object>>();
    io.justsearch.app.api.runtime.child.OwnedHelper.recordTo((type, fields) -> {
      Map<String, Object> record = new java.util.LinkedHashMap<>(fields); record.put("type", type.token());
      records.add(record); return true;
    }, new EngineIdentity(ProcessHandle.current().pid(),
        ProcessTerminator.forCurrentOs().creationFileTime(ProcessHandle.current().pid()), "first-pass"));
    var method = StructuredContentExtractor.class.getDeclaredMethod("parseContextWithMarkedPdfContent");
    method.setAccessible(true);
    method.invoke(null);
    assertTrue(records.stream().anyMatch(r -> "helper-window-start".equals(r.get("type"))
        && r.get("reason").toString().contains("first-pass OCR configuration")),
        "constructing TesseractOCRParser outside parse() needs a declaring owner");
    assertTrue(records.stream().anyMatch(r -> "helper-window-end".equals(r.get("type"))
        && r.get("reason").toString().contains("first-pass OCR configuration")));
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
      try (AutoCloseable close = intent) {
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
