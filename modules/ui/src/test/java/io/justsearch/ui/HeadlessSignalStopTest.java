/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.ort.HeadlessSignalStopProbe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Real signals cross the JVM boundary; fake ORT calls hold real native leases without a model. */
@DisabledOnOs(OS.WINDOWS)
final class HeadlessSignalStopTest {
  @ParameterizedTest(name = "hooksWaitForAdmittedQueuedDetachedAndBootWork({index}: {arguments})")
  @CsvSource({"TERM,143,active", "INT,130,active", "TERM,143,queued", "INT,130,queued",
      "TERM,143,detached", "INT,130,detached", "TERM,143,boot", "INT,130,boot"})
  void hooksWaitForAdmittedQueuedDetachedAndBootWork(String signal, int status, String mode,
      @TempDir Path dir) throws Exception {
    Process child = child(dir, mode);
    try {
      await(dir, child, "ready");
      signal(child, signal);
      if (mode.equals("boot")) {
        awaitEither(dir, child, "signal-observed", "hook");
        assertFalse(Files.exists(dir.resolve("hook")), output(dir));
        Files.writeString(dir.resolve("publish"), "ready");
      }
      awaitEither(dir, child, "draining", "hook");
      assertFalse(Files.exists(dir.resolve("hook")),
          "JVM hooks started while work was held: " + output(dir));
      Files.writeString(dir.resolve("release"), "native may return");
      assertTrue(child.waitFor(10, TimeUnit.SECONDS), output(dir));
      assertEquals(status, child.exitValue(), output(dir));
      assertEquals("safe", Files.readString(dir.resolve("hook")), output(dir));
      assertTrue(Files.exists(dir.resolve("drained")));
      assertEquals("QUIT", Files.readString(dir.resolve("self-intent")));
      var records = io.justsearch.app.api.runtime.ledger.ExitLedgerReader.read(
          io.justsearch.app.api.runtime.ledger.LedgerFamily.directory(dir),
          io.justsearch.app.api.runtime.ledger.LedgerFamily.ENGINE).records();
      assertEquals(1, records.size());
      assertEquals("QUIT", records.getFirst().field("reason").asString());
      assertEquals(0, records.getFirst().field("expectedCode").asInt());
      assertSupervisorClassification(dir, status, false);
      try (var files = Files.list(dir)) {
        assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith("hs_err")));
      }
    } finally { stop(child, dir); }
  }

  @ParameterizedTest(name = "signalPreservesSelfIntentAndQuitHandoff({index}: {arguments})")
  @CsvSource({"TERM,143,clean", "INT,130,clean", "TERM,1,refused", "INT,1,refused",
      "TERM,1,deadline", "INT,1,deadline"})
  void signalPreservesSelfIntentAndQuitHandoff(String signal, int status, String mode, @TempDir Path dir)
      throws Exception {
    Process child = child(dir, "handoff-" + mode);
    try {
      await(dir, child, "ready");
      signal(child, signal);
      assertTrue(child.waitFor(5, TimeUnit.SECONDS), output(dir));
      assertEquals(status, child.exitValue(), output(dir));
      var records = io.justsearch.app.api.runtime.ledger.ExitLedgerReader.read(
          io.justsearch.app.api.runtime.ledger.LedgerFamily.directory(dir),
          io.justsearch.app.api.runtime.ledger.LedgerFamily.ENGINE).records();
      assertEquals(1, records.size());
      assertEquals("QUIT", records.getFirst().field("reason").asString());
      assertEquals(0, records.getFirst().field("expectedCode").asInt());
      var manifest = new tools.jackson.databind.ObjectMapper().readTree(
          Files.readString(dir.resolve("runtime/manifest.json")));
      assertEquals("pending", manifest.path("shutdownHandoff").path("state").asString());
      assertEquals("quit", manifest.path("shutdownHandoff").path("reason").asString());
      assertSupervisorClassification(dir, status, true);
    } finally { stop(child, dir); }
  }

  @ParameterizedTest(name = "beforeNativeBootSignalExitsPromptly({index}: {arguments})")
  @CsvSource({"TERM,143", "INT,130"})
  void beforeNativeBootSignalExitsPromptly(String signal, int status, @TempDir Path dir)
      throws Exception {
    Process child = child(dir, "preboot");
    try {
      await(dir, child, "ready");
      signal(child, signal);
      assertTrue(child.waitFor(3, TimeUnit.SECONDS), output(dir));
      assertEquals(status, child.exitValue(), output(dir));
      assertEquals("safe", Files.readString(dir.resolve("hook")));
      assertFalse(Files.exists(dir.resolve("native-after-signal")));
    } finally { stop(child, dir); }
  }

  @ParameterizedTest(name = "deadlineHaltsWithoutHooksAndSecondSignalCannotExtendIt({index}: {arguments})")
  @CsvSource({"deadline-native,TERM,1", "deadline-executor,INT,1", "deadline-boot,TERM,1"})
  void deadlineHaltsWithoutHooksAndSecondSignalCannotExtendIt(String mode, String first, int status,
      @TempDir Path dir) throws Exception {
    Process child = child(dir, mode);
    try {
      await(dir, child, "ready");
      long started = System.nanoTime();
      signal(child, first);
      awaitEither(dir, child, mode.equals("deadline-boot") ? "signal-observed" : "draining", "hook");
      assertFalse(waitFor(dir.resolve("hook"), 650), "baseline hooks raced held ownership: " + output(dir));
      assertTrue(child.isAlive(), "held operation must not finish early: " + output(dir));
      signal(child, first.equals("TERM") ? "INT" : "TERM");
      assertTrue(child.waitFor(1_000, TimeUnit.MILLISECONDS), "second signal extended deadline: " + output(dir));
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_700);
      assertEquals(status, child.exitValue(), output(dir));
      assertFalse(Files.exists(dir.resolve("hook")), "halt must bypass every JVM hook");
    } finally { stop(child, dir); }
  }

  @Test
  void signalDuringBootFailureWaitsForFatalCleanup(@TempDir Path dir) throws Exception {
    Process child = child(dir, "boot-failure");
    try {
      await(dir, child, "ready");
      signal(child, "TERM");
      awaitEither(dir, child, "signal-observed", "hook");
      assertFalse(Files.exists(dir.resolve("hook")), output(dir));
      Files.writeString(dir.resolve("publish"), "fail boot");
      await(dir, child, "fatal-cleanup");
      assertFalse(Files.exists(dir.resolve("hook")));
      Files.writeString(dir.resolve("release"), "cleanup may finish");
      assertTrue(child.waitFor(10, TimeUnit.SECONDS), output(dir));
      assertEquals(1, child.exitValue(), output(dir));
      assertEquals("safe", Files.readString(dir.resolve("hook")));
    } finally { stop(child, dir); }
  }

  @ParameterizedTest(name = "fatalShutdownKeepsItsRealProcessStatusAtDeadline({index}: {arguments})")
  @CsvSource({"deadline-fatal", "deadline-fatal-first"})
  void fatalShutdownKeepsItsRealProcessStatusAtDeadline(String mode, @TempDir Path dir)
      throws Exception {
    Process child = child(dir, mode);
    try {
      await(dir, child, "ready");
      signal(child, "TERM");
      awaitEither(dir, child, "draining", "hook");
      assertFalse(Files.exists(dir.resolve("hook")), output(dir));
      if (mode.equals("deadline-fatal")) {
        Files.writeString(dir.resolve("fatal"), "fatal request");
        await(dir, child, "fatal-requested");
      }
      assertTrue(child.waitFor(2, TimeUnit.SECONDS), output(dir));
      assertEquals(1, child.exitValue(), output(dir));
      assertFalse(Files.exists(dir.resolve("hook")));
    } finally { stop(child, dir); }
  }

  @Test
  void handlerRegistrationFailureKeepsDefaultHandlingAndAttemptsBothSignals() throws Exception {
    Class<?> routerType = null;
    try { routerType = Class.forName("io.justsearch.app.engine.EngineSignalStop"); }
    catch (ClassNotFoundException baseline) { /* assertion names the missing boot routing */ }
    assertNotNull(routerType, "boot must install signal routing with registration failure handling");
    Object router = routerType.getConstructor().newInstance();
    var install = routerType.getMethod("install");
    // -Xrs still permits application-owned Signal.handle registration on this JDK. Inject the
    // actual registration exception instead; no handler replacement occurs when handle throws.
    try (var signals = org.mockito.Mockito.mockStatic(sun.misc.Signal.class)) {
      signals.when(() -> sun.misc.Signal.handle(org.mockito.ArgumentMatchers.any(sun.misc.Signal.class),
          org.mockito.ArgumentMatchers.any(sun.misc.SignalHandler.class)))
          .thenThrow(new IllegalArgumentException("registration refused"));
      assertDoesNotThrow(() -> install.invoke(router));
      signals.verify(() -> sun.misc.Signal.handle(org.mockito.ArgumentMatchers.argThat(s -> s.getName().equals("TERM")),
          org.mockito.ArgumentMatchers.any(sun.misc.SignalHandler.class)));
      signals.verify(() -> sun.misc.Signal.handle(org.mockito.ArgumentMatchers.argThat(s -> s.getName().equals("INT")),
          org.mockito.ArgumentMatchers.any(sun.misc.SignalHandler.class)));
    }
  }

  @ParameterizedTest(name = "containedExtractionAndMcpHelpersEndWithSignalStoppedEngine({index}: {arguments})")
  @CsvSource({"children", "deadline-children"})
  @org.junit.jupiter.api.condition.EnabledOnOs(OS.LINUX)
  void containedExtractionAndMcpHelpersEndWithSignalStoppedEngine(String mode, @TempDir Path dir)
      throws Exception {
    Process child = child(dir, mode);
    var identities = new java.util.ArrayList<io.justsearch.app.api.runtime.child.LinuxProcessIdentity>();
    try {
      await(dir, child, "ready");
      for (String role : List.of("extraction", "mcp")) {
        identities.add(io.justsearch.app.api.runtime.child.LinuxProcessIdentity.fromJson(
            new tools.jackson.databind.ObjectMapper().readTree(Files.readString(dir.resolve(role + ".json"))))
            .orElseThrow());
      }
      signal(child, "TERM");
      awaitEither(dir, child, "draining", "hook");
      if (mode.equals("children")) Files.writeString(dir.resolve("release"), "clean close");
      assertTrue(child.waitFor(4, TimeUnit.SECONDS), output(dir));
      assertEquals(mode.equals("children") ? 143 : 1, child.exitValue(), output(dir));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      for (var identity : identities) {
        while (io.justsearch.app.api.runtime.child.LinuxProcessIdentity.observe(identity.pid()).presence()
            != io.justsearch.app.api.runtime.child.ProcessTerminator.IdentityPresence.GONE
            && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(io.justsearch.app.api.runtime.child.ProcessTerminator.IdentityPresence.GONE,
            io.justsearch.app.api.runtime.child.LinuxProcessIdentity.observe(identity.pid()).presence());
      }
    } finally {
      stop(child, dir);
      for (var identity : identities) {
        if (!io.justsearch.app.api.runtime.child.LinuxProcessIdentity.read(identity.pid()).equals(java.util.Optional.of(identity)))
          continue;
        Process cleanup = new ProcessBuilder(io.justsearch.app.api.runtime.child.BoundChildProcess.helper().toString(),
            "signal", Long.toString(identity.pid()), identity.bootId(), identity.startTicks(), "KILL").start();
        try { assertTrue(cleanup.waitFor(3, TimeUnit.SECONDS)); }
        finally { if (cleanup.isAlive()) { cleanup.destroyForcibly(); cleanup.waitFor(); } }
      }
    }
  }

  private static void assertSupervisorClassification(Path dir, int status, boolean manifestAware) throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (!Files.isDirectory(root.resolve("governance"))) root = root.getParent();
    var script = "const s=require('./scripts/dev/lib/engine-supervisor.cjs');"
        + "const a=require('./scripts/dev/lib/engine-actuator.cjs');const p=s.loadPolicy();"
        + "const code=Number(process.argv[1]);const fs=require('node:fs');const path=require('node:path');"
        + "const data=process.argv[2],useHandoff=process.argv[3]==='true';let requestedReason=null,selfIntent=null;"
        + "if(useHandoff){const m=JSON.parse(fs.readFileSync(path.join(data,'runtime/manifest.json')));"
        + "requestedReason=s.exitHandoffReason(m,m.pid,m.instanceId);"
        + "selfIntent=fs.readFileSync(path.join(data,'runtime/exit-ledger/engine.v1.jsonl'),'utf8').trim()"
        + ".split(String.fromCharCode(10)).map(JSON.parse).findLast(r=>r.type==='engine-self-intent');}"
        + "console.log(JSON.stringify({decision:s.decide({event:'exit',exitCode:code,requestedReason,restartCount:0},p),"
        + "ledger:a.classifyEngineExit({code,selfIntent,policy:p})}));";
    Process node = new ProcessBuilder("node", "-e", script, Integer.toString(status), dir.toString(),
        Boolean.toString(manifestAware))
        .directory(root.toFile()).redirectErrorStream(true)
        .redirectOutput(dir.resolve("classification.json").toFile()).start();
    try {
      assertTrue(node.waitFor(5, TimeUnit.SECONDS));
      String json = Files.readString(dir.resolve("classification.json"));
      assertEquals(0, node.exitValue(), json);
      var result = new tools.jackson.databind.ObjectMapper().readTree(json);
      assertEquals(manifestAware ? "stop" : "restart", result.path("decision").path("action").asString());
      assertEquals(manifestAware ? "REQUESTED" : "TRANSIENT", result.path("decision").path("exitClass").asString());
      assertEquals(!manifestAware, result.path("decision").path("counted").asBoolean());
      assertEquals("UNEXPECTED", result.path("ledger").path("exitClass").asString());
      if (manifestAware) assertEquals("QUIT", result.path("ledger").path("reason").asString());
    } finally { if (node.isAlive()) { node.destroyForcibly(); node.waitFor(); } }
  }

  private static Process child(Path dir, String mode) throws Exception {
    Path home = Files.createDirectories(dir.resolve("home"));
    Path args = dir.resolve("java.args");
    Files.writeString(args, "-cp\n\"" + System.getProperty("java.class.path").replace("\\", "\\\\")
        + "\"\n-Duser.home=\"" + home + "\"\n-XX:+EnableDynamicAgentLoading\n");
    // Background Gradle launchers can inherit SIG_IGN for INT. HotSpot preserves that disposition;
    // reset it before exec so both candidate and baseline receive the actual signal under test.
    var builder = new ProcessBuilder("python3", "-c",
        "import os,signal,sys; signal.signal(signal.SIGINT,signal.SIG_DFL); os.execv(sys.argv[1],sys.argv[1:])",
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "@" + args, (mode.startsWith("handoff-") ? HeadlessSignalHandoffProbe.class : HeadlessSignalStopProbe.class).getName(), dir.toString(), mode)
        .directory(dir.toFile()).redirectErrorStream(true).redirectOutput(dir.resolve("child.log").toFile());
    builder.environment().keySet().removeIf(key -> key.startsWith("JUSTSEARCH_")
        || List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS", "_JAVA_OPTIONS",
            "DISPLAY", "WAYLAND_DISPLAY").contains(key));
    builder.environment().put("CUDA_VISIBLE_DEVICES", "-1");
    for (String key : List.of("HOME", "USERPROFILE", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME")) {
      builder.environment().put(key, home.toString());
    }
    Process child = builder.start();
    Files.writeString(dir.resolve("child.pid"), Long.toString(child.pid()));
    return child;
  }

  private static void signal(Process child, String signal) throws Exception {
    Process kill = new ProcessBuilder("kill", "-s", signal, "--", Long.toString(child.pid())).start();
    try {
      assertTrue(kill.waitFor(3, TimeUnit.SECONDS));
      assertEquals(0, kill.exitValue());
    } finally { if (kill.isAlive()) { kill.destroyForcibly(); kill.waitFor(); } }
  }

  private static boolean waitFor(Path file, long millis) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    while (!Files.exists(file) && System.nanoTime() < deadline) Thread.sleep(5);
    return Files.exists(file);
  }

  private static void await(Path dir, Process child, String marker) throws Exception {
    awaitEither(dir, child, marker, marker);
  }

  private static void awaitEither(Path dir, Process child, String first, String second) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!Files.exists(dir.resolve(first)) && !Files.exists(dir.resolve(second))) {
      assertTrue(child.isAlive() && System.nanoTime() < deadline,
          "missing " + first + "/" + second + ": " + output(dir));
      Thread.sleep(5);
    }
  }

  private static String output(Path dir) throws Exception { return Files.readString(dir.resolve("child.log")); }

  private static void stop(Process child, Path dir) throws Exception {
    Files.writeString(dir.resolve("release"), "test cleanup");
    Files.writeString(dir.resolve("publish"), "test cleanup");
    if (child.isAlive()) child.destroyForcibly();
    assertTrue(child.waitFor(5, TimeUnit.SECONDS), "test child survived cleanup");
  }
}
