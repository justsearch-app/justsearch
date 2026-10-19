/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.app.api.runtime.child.*;
import io.justsearch.app.api.runtime.containment.EngineContainment;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real production initialization and parse paths in disposable parser JVMs, without a dev stack. */
@EnabledOnOs(OS.WINDOWS)
class TikaProductionOwnerCoverageTest {
  @TempDir Path data;
  @ParameterizedTest
  @ValueSource(strings = {"flat", "structured", "policy"})
  void everyProductionTesseractProbeHasExactOwnerAndClosedWindow(String mode) throws Exception {
    var runtime = TikaOcrRuntime.resolve();
    assertTrue(runtime.available(), "this check requires the real bundled Tesseract");
    ProcessBuilder builder = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
        Probe.class.getName(), mode, data.toString()).redirectErrorStream(true)
        .redirectOutput(data.resolve(mode + ".log").toFile());
    builder.environment().put("PATH", runtime.executableDirectory() + ";" + builder.environment().get("PATH"));
    Process fixture = builder.start();
    var held = ProcessTerminator.forCurrentOs().open(fixture);
    String birth = held.creationFileTime().orElseThrow();
    try {
      assertTrue(fixture.waitFor(60, TimeUnit.SECONDS), "fixture " + fixture.pid() + "/" + birth);
      String output = Files.readString(data.resolve(mode + ".log"), StandardCharsets.UTF_8);
      assertEquals(0, fixture.exitValue(), output);
      assertTrue(output.contains("owner.coverage=PASS"), output);
    } finally { if (fixture.isAlive()) held.terminate(1); held.close(); }
  }
  public static class Probe {
    public static void main(String[] args) throws Exception {
      var job = EngineContainment.installAtBoot();
      assertEquals(EngineContainment.Status.CONTAINED, job.status());
      var records = new CopyOnWriteArrayList<Map<String, Object>>();
      LedgerSink sink = (type, fields) -> { Map<String, Object> record = new java.util.LinkedHashMap<>(fields);
        record.put("type", type.token()); records.add(record); return true; };
      var owner = new EngineIdentity(ProcessHandle.current().pid(),
          ProcessTerminator.forCurrentOs().creationFileTime(ProcessHandle.current().pid()), "production-probe");
      job.recordProcesses(sink, owner);
      OwnedHelper.recordTo(sink, owner);
      Path text = Path.of(args[1]).resolve("document.txt"); Files.writeString(text, "ordinary test document", StandardCharsets.UTF_8);
      switch (args[0]) {
        case "flat" -> { try (var extractor = new ContentExtractor()) { extractor.extract(text); } }
        case "structured" -> { try (var extractor = new StructuredContentExtractor()) { extractor.extract(text); } }
        case "policy" -> { try (var extractor = new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool)) {
          extractor.extract(text);
        } }
        default -> throw new AssertionError(args[0]);
      }
      job.finishProcessRecording(Duration.ofSeconds(3));
      List<Map<String, Object>> helpers = records.stream().filter(r -> "job-process-start".equals(r.get("type"))
          && r.get("executable") != null && r.get("executable").toString().toLowerCase(java.util.Locale.ROOT).endsWith("tesseract.exe")).toList();
      assertFalse(helpers.isEmpty(), "real Tesseract probes must execute");
      for (var helper : helpers) {
        var exit = records.stream().filter(r -> "job-process-exit".equals(r.get("type"))
            && r.get("witnessId").equals(helper.get("witnessId"))).findFirst().orElseThrow();
        assertEquals(owner.pid(), ((Number) helper.get("parentPid")).longValue());
        assertEquals(owner.creationFileTime().orElseThrow(), helper.get("parentCreationFileTime"));
        Instant born = fromFileTime(helper.get("creationFileTime").toString());
        Object at = exit.get("exitedAt");
        assertNotNull(at, "window checks require an actual native exit time");
        Instant ended = at instanceof Instant instant ? instant : Instant.parse(at.toString());
        assertTrue(records.stream().filter(r -> "helper-window-start".equals(r.get("type"))).anyMatch(start -> {
          var close = records.stream().filter(r -> "helper-window-end".equals(r.get("type"))
              && r.get("intentId").equals(start.get("intentId"))).findFirst().orElse(null);
          if (close == null) return false;
          Instant opened = Instant.parse(start.get("requestedAt").toString());
          Instant deadline = Instant.parse(start.get("deadlineAt").toString());
          Instant closed = Instant.parse(close.get("closedAt").toString());
          return !born.isBefore(opened) && !ended.isAfter(closed) && !ended.isAfter(deadline);
        }), "unowned Tesseract probe " + helper + " exit " + exit + " windows "
            + records.stream().filter(r -> r.get("type").toString().startsWith("helper-window")).toList());
      }
      System.out.println("owner.coverage=PASS " + args[0] + " probes=" + helpers.size());
    }
    private static Instant fromFileTime(String birth) {
      long ticks = Long.parseLong(birth) - 116444736000000000L;
      return Instant.ofEpochSecond(ticks / 10000000L, ticks % 10000000L * 100L);
    }
  }
}
