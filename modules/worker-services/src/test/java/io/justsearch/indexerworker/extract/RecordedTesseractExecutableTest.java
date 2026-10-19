/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.app.api.runtime.child.ProcessTerminator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Isolated process environments exercise actual PATH precedence and actual native launches. */
@EnabledOnOs({OS.WINDOWS, OS.LINUX})
class RecordedTesseractExecutableTest {
  @TempDir Path data;

  @Test @EnabledOnOs(OS.WINDOWS) void scoopPathLaunchesRealExecutable() throws Exception { run("shim"); }
  @Test @EnabledOnOs(OS.WINDOWS) void commandOnPathIsNeverLaunched() throws Exception { run("cmd"); }
  @Test void repeatedLaunchesReuseRecordedResolution() throws Exception { run("cache"); }
  @Test void statusReportsExecutableSource() throws Exception { run("status"); }
  @Test void configuredWinsAndInvalidConfiguredDoesNotFallBack() throws Exception { run("configured"); }
  @Test void appOwnedWinsOverPath() throws Exception { run("app-owned"); }
  @Test void runtimeRestoreInvalidatesTheRecord() throws Exception { run("restore"); }
  @Test @EnabledOnOs(OS.WINDOWS) void transparentBatchResolvesButAmbiguousBatchAndShimCyclesBlock() throws Exception { run("wrappers"); }

  private void run(String scenario) throws Exception {
    Path real = NativeTesseractFixture.write(data.resolve("real"), "REAL", true);
    Path shimDirectory = Files.createDirectories(data.resolve("path"));
    Path shim = shimDirectory.resolve(io.justsearch.configuration.NativeTargets.current().executableName("tesseract"));
    if (io.justsearch.configuration.PlatformPaths.isLinux()) Files.createSymbolicLink(shim, real);
    else Files.copy(NativeTesseractFixture.write(data.resolve("launcher"), "LAUNCHER", true), shim);
    if (io.justsearch.configuration.PlatformPaths.isWindows())
      Files.writeString(shimDirectory.resolve("tesseract.shim"), "path = \"" + real + "\"\n", StandardCharsets.UTF_8);
    Path home = Files.createDirectories(data.resolve("home"));
    Path owned = home.resolve("native-bin/tesseract").resolve(io.justsearch.configuration.NativeTargets.current().executableName("tesseract"));
    if (scenario.equals("app-owned") || scenario.equals("configured")) {
      Files.createDirectories(owned.getParent()); Files.copy(real, owned);
    }
    if (scenario.equals("restore")) {
      if (io.justsearch.configuration.PlatformPaths.isWindows()) Files.delete(shimDirectory.resolve("tesseract.shim"));
      Files.copy(real, shim, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    if (scenario.equals("cmd")) {
      Files.delete(shim); Files.delete(shimDirectory.resolve("tesseract.shim"));
      Files.writeString(shimDirectory.resolve("tesseract.cmd"), "@echo off\n echo launched > \"" + data.resolve("batch-ran") + "\"\n", StandardCharsets.UTF_8);
    }
    var builder = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "--enable-native-access=ALL-UNNAMED", "-Duser.dir=" + data,
        "-cp", System.getProperty("java.class.path"), Probe.class.getName(), scenario, data.toString())
        .directory(data.toFile()).redirectErrorStream(true).redirectOutput(data.resolve("probe.log").toFile());
    var env = builder.environment();
    env.put("PATH", shimDirectory.toString());
    for (String name : List.of("JUSTSEARCH_TESSERACT_PATH", "TESSERACT_PATH", "JUSTSEARCH_REPO_ROOT", "JUSTSEARCH_DATA_DIR")) env.remove(name);
    env.put("JUSTSEARCH_HOME", home.toString()); env.put("APPDATA", data.resolve("appdata").toString());
    env.put("LOCALAPPDATA", data.resolve("localappdata").toString());
    Process process = builder.start();
    var held = ProcessTerminator.forCurrentOs().open(process);
    Object birth = io.justsearch.configuration.PlatformPaths.isLinux() ? held.processIdentity() : held.creationFileTime().orElseThrow();
    try {
      assertTrue(process.waitFor(60, TimeUnit.SECONDS), "probe timeout " + process.pid() + "/" + birth);
      assertEquals(0, process.exitValue(), Files.readString(data.resolve("probe.log"), StandardCharsets.UTF_8));
    } finally { if (process.isAlive()) held.terminate(1); held.close(); }
    assertFalse(Files.exists(data.resolve("batch-ran")), "batch launcher ran");
  }

  public static class Probe {
    public static void main(String[] args) throws Exception {
      String scenario = args[0]; Path data = Path.of(args[1]);
      Path real = data.resolve("real").resolve(io.justsearch.configuration.NativeTargets.current().executableName("tesseract")).toRealPath();
      Path owned = data.resolve("home/native-bin/tesseract").resolve(io.justsearch.configuration.NativeTargets.current().executableName("tesseract"));
      OcrRoutingConfig config = new OcrRoutingConfig(true, List.of("eng"), 5000, 1, 4096, 40000000, 72, 0);
      switch (scenario) {
        case "shim" -> {
          assertLaunches(real, data, config);
          var chosen = TikaOcrRuntime.resolve();
          assertEquals(real, chosen.executable(), "PATH shim must record the real executable");
          assertEquals(data.resolve("path").resolve(io.justsearch.configuration.NativeTargets.current().executableName("tesseract")), chosen.getClass().getMethod("launcher").invoke(chosen));
          assertEquals("PATH", chosen.getClass().getMethod("source").invoke(chosen));
          var at = (java.time.Instant) chosen.getClass().getMethod("resolvedAt").invoke(chosen);
          assertNotNull(at); assertFalse(at.isAfter(java.time.Instant.now()), "recorded resolution time");
          assertEquals("", TikaOcrRuntime.blockedReason(config));
          try (var flat = new ContentExtractor(); var structured = new StructuredContentExtractor();
              var policy = new PolicyDrivenTikaExtractor(Executors::newFixedThreadPool)) {
            Path text = data.resolve("document.txt"); Files.writeString(text, "fixture");
            flat.extract(text); structured.extract(text); policy.extract(text);
          }
          assertFalse(Files.exists(data.resolve("path/launches.log")), "Tika must never probe the PATH launcher");
        }
        case "cmd" -> {
          var result = invokeUnavailable(data, config);
          assertFalse(Files.exists(data.resolve("batch-ran")), "batch launcher was executed");
          assertFalse(TikaOcrRuntime.resolve().available(), "unresolved .cmd is unavailable");
          assertEquals(TikaOcrRuntime.REASON_ENGINE_MISSING, TikaOcrRuntime.blockedReason(config));
          assertTrue(status().contains("batch launcher"), status());
          assertEquals(OcrSkipReason.ENGINE_MISSING, result.failureReason());
        }
        case "cache" -> {
          System.setProperty("justsearch.tesseract.path", real.toString());
          var first = TikaOcrRuntime.resolve();
          assertLaunches(real, data, config); assertLaunches(real, data, config);
          assertSame(first, TikaOcrRuntime.resolve(), "launches must reuse the recorded resolution");
          var count = TikaOcrRuntime.class.getDeclaredField("resolutionCount"); count.setAccessible(true);
          assertEquals(1L, count.getLong(null), "exactly one resolution for repeated launches");
          Files.writeString(data.resolve("path/tesseract.shim"), "path = \"missing.exe\"\n");
          assertSame(first, TikaOcrRuntime.resolve(), "PATH launcher changes do not silently retarget the record");
          assertLaunches(real, data, config);
          assertEquals(1L, Files.readAllLines(data.resolve("real/launches.log"), StandardCharsets.UTF_8)
              .stream().filter(line -> line.equals("--version")).count(), "only one executable check across launches");
          System.clearProperty("justsearch.tesseract.path");
          Files.createDirectories(owned.getParent()); Files.copy(real, owned);
          var appRuntime = TikaOcrRuntime.resolve();
          assertLaunches(owned.toRealPath(), data, config); assertLaunches(owned.toRealPath(), data, config);
          assertSame(appRuntime, TikaOcrRuntime.resolve(), "app runtime logs must not trigger re-resolution");
          assertEquals(2L, count.getLong(null), "one configured and one app-owned resolution");
        }
        case "status" -> {
          assertTrue(status().contains("[PATH]"), status()); assertTrue(status().contains(real.toString()), status());
          Files.createDirectories(owned.getParent()); Files.copy(real, owned);
          assertTrue(status().contains("[app-owned]"), status());
          System.setProperty("justsearch.tesseract.path", real.toString());
          assertTrue(status().contains("[configured]"), status());
        }
        case "configured" -> {
          System.setProperty("justsearch.tesseract.path", real.toString());
          assertEquals(real, TikaOcrRuntime.resolve().executable());
          System.setProperty("justsearch.tesseract.path", data.resolve("missing.exe").toString());
          assertFalse(TikaOcrRuntime.resolve().available(), "configured missing path must not fall back");
        }
        case "app-owned" -> { assertEquals(owned.toRealPath(), TikaOcrRuntime.resolve().executable()); }
        case "restore" -> {
          Path pathExecutable = data.resolve("path").resolve(io.justsearch.configuration.NativeTargets.current().executableName("tesseract")).toRealPath();
          assertEquals(pathExecutable, TikaOcrRuntime.resolve().executable());
          Files.createDirectories(owned.getParent()); Files.copy(real, owned);
          assertEquals(owned.toRealPath(), TikaOcrRuntime.resolve().executable(), "runtime installation invalidates PATH selection");
          Files.delete(owned);
          assertEquals(pathExecutable, TikaOcrRuntime.resolve().executable(), "runtime removal invalidates app-owned selection");
        }
        case "wrappers" -> {
          Path batch = data.resolve("forward.cmd");
          Files.writeString(batch, "@echo off\n@\"%~dp0real\\tesseract.exe\" %*\n");
          System.setProperty("justsearch.tesseract.path", batch.toString());
          assertEquals(real, TikaOcrRuntime.resolve().executable()); assertLaunches(real, data, config);
          Path ambiguous = data.resolve("ambiguous.bat");
          Files.writeString(ambiguous, "@echo off\n@\"" + real + "\" %*\necho side-effect\n");
          System.setProperty("justsearch.tesseract.path", ambiguous.toString());
          assertFalse(TikaOcrRuntime.resolve().available(), "ambiguous batch is unavailable");
          System.setProperty("justsearch.tesseract.path", data.resolve("path").resolve(io.justsearch.configuration.NativeTargets.current().executableName("tesseract")).toString());
          Files.writeString(data.resolve("path/tesseract.shim"), "path = \"" + data.resolve("path").resolve(io.justsearch.configuration.NativeTargets.current().executableName("tesseract")) + "\"\n");
          assertFalse(TikaOcrRuntime.resolve().available(), "shim cycle is unavailable");
        }
        default -> throw new AssertionError(scenario);
      }
    }

    private static String status() throws Exception {
      var counts = org.mockito.Mockito.mock(io.justsearch.adapters.lucene.runtime.IndexCountOps.class);
      var type = Class.forName("io.justsearch.indexerworker.services.IndexStatusOps");
      var constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
      var ops = constructor.newInstance(null, null, counts, null, null, null, null, null, null, null, null, null, null, null, 0L);
      var method = ops.getClass().getDeclaredMethod("buildVisualExtraction"); method.setAccessible(true);
      return ((io.justsearch.ipc.VisualExtractionStatus) method.invoke(ops)).getOcrEngine();
    }

    private static PdfOcrEngine.OcrEngineResult invokeUnavailable(Path data, OcrRoutingConfig config) throws Exception {
      Path image = data.resolve("input.png"); Files.writeString(image, "fixture");
      try (var engine = PdfOcrEngine.create(Executors::newFixedThreadPool, config, null)) { return engine.ocrImage(image, 10000); }
    }

    private static void assertLaunches(Path real, Path data, OcrRoutingConfig config) throws Exception {
      var result = invokeUnavailable(data, config);
      assertTrue(result.text().contains(real.toString()), "actual process image must be the real executable: " + result);
      assertTrue(result.text().contains("REAL"), "launcher stand-in must never execute: " + result);
    }
  }
}
