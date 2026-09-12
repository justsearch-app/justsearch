/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

@org.junit.jupiter.api.Tag("windows")
@EnabledOnOs(OS.WINDOWS)
@Timeout(45)
final class WindowsParserContainmentTest {
  @TempDir Path tempDir;

  @ParameterizedTest
  @ValueSource(strings = {"recycle", "timeout", "close"})
  void killingParserReapsAlreadyLiveNativeDescendant(String mode) throws Exception {
    Path request = tempDir.resolve(mode + ".txt");
    Files.writeString(request, "content");
    Path pidFile = Path.of(request + ".pid");
    List<String> command = PersistentExtractionSandboxTest.javaCommand(
        NativeChild.class, "--enable-native-access=ALL-UNNAMED");
    ProcessHandle nativeChild = null;
    try (PersistentExtractionSandbox sandbox = new PersistentExtractionSandbox(
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.readers(), command,
        TikaExtractionPolicy.defaults(), OcrRoutingConfig.disabled(), Duration.ofSeconds(5),
        1, mode.equals("recycle") ? 1 : 500, null)) {
      java.util.concurrent.FutureTask<ExtractionArtifact> pending =
          new java.util.concurrent.FutureTask<>(() -> sandbox.extract(request));
      Thread caller = Thread.ofVirtual().start(pending);
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while (!Files.exists(pidFile) && System.nanoTime() < deadline && !pending.isDone()) {
        Thread.sleep(20);
      }
      if (!Files.exists(pidFile) && pending.isDone()) {
        // Preserve timeout/bootstrap/protocol failure instead of hiding it behind the PID assertion.
        var _ = pending.get();
      }
      assertTrue(Files.exists(pidFile), "bootstrap and native spawn must actually execute");
      long pid = Long.parseLong(Files.readString(pidFile));
      nativeChild = ProcessHandle.of(pid).orElseThrow();
      assertTrue(nativeChild.isAlive(), "capture a LIVE native child before parser termination");
      Files.writeString(Path.of(request + ".release"), "parent observed live child");
      if (mode.equals("timeout")) {
        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> pending.get(15, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof TimeboxedContentExtractor.ExtractionTimeoutException);
      } else {
        assertEquals("native child was alive", pending.get(15, TimeUnit.SECONDS).result().content());
      }
      caller.join(Duration.ofSeconds(1));
      if (mode.equals("recycle")) {
        // Request-budget recycling happens when the next request acquires this slot.
        Path next = tempDir.resolve("next.txt");
        Files.writeString(next, "next request");
        assertEquals("no native child requested", sandbox.extract(next).result().content());
      }
      if (mode.equals("close")) {
        assertTrue(nativeChild.isAlive());
        sandbox.close();
      }
      if (nativeChild != null) {
        nativeChild.onExit().get(10, TimeUnit.SECONDS);
        assertFalse(nativeChild.isAlive(), "native process must die with recycled parser");
      }
    } finally {
      // Refutation runs intentionally remove containment; clean the exact fixture PID on failure.
      if (nativeChild == null && Files.exists(pidFile)) {
        nativeChild = ProcessHandle.of(Long.parseLong(Files.readString(pidFile))).orElse(null);
      }
      if (nativeChild != null && nativeChild.isAlive()) {
        nativeChild.destroyForcibly();
        nativeChild.onExit().get(10, TimeUnit.SECONDS);
      }
    }
  }

  @org.junit.jupiter.api.Test
  void unavailableNativeAccessFailsBootstrapBeforeAnyResponse() throws Exception {
    Path log = tempDir.resolve("denied-bootstrap.log");
    Process child = new ProcessBuilder(PersistentExtractionSandboxTest.javaCommand(
        ExtractionSandboxChild.class, "--illegal-native-access=deny"))
        .redirectError(log.toFile()).start();
    try {
      assertTrue(child.waitFor(10, TimeUnit.SECONDS), "bootstrap failure must terminate promptly");
      assertTrue(child.exitValue() != 0, "native setup failure must not serve requests");
      assertEquals(-1, child.getInputStream().read(), "no protocol response before containment");
      assertTrue(Files.readString(log).contains("IllegalCallerException"),
          "the refusal must come from actual restricted FFM access");
    } finally {
      child.destroyForcibly();
      assertTrue(child.waitFor(10, TimeUnit.SECONDS));
    }
  }

  /** A parser that deliberately abandons a still-live native process, like a timed-out OCR owner. */
  public static final class NativeChild {
    public static void main(String[] args) throws Exception {
      ExtractionSandboxChild.initializeProcessBoundary(args);
      JsonMapper mapper = JsonMapper.builder().build();
      byte[] frame;
      while ((frame = SandboxFrames.read(System.in, SandboxFrames.MAX_FRAME_BYTES)) != null) {
        SandboxExtractionRequest request = mapper.readValue(frame, SandboxExtractionRequest.class);
        if (request.path().endsWith("next.txt")) {
          ExtractionArtifact next = ExtractionArtifact.full(
              new ContentExtractor.ExtractionResult("no native child requested", null, "text/plain"),
              request.policy(), "native-child", false);
          SandboxFrames.write(System.out,
              mapper.writeValueAsBytes(SandboxExtractionResponse.fromArtifact(request.requestId(), next)));
          continue;
        }
        Process child = new ProcessBuilder("ping.exe", "-t", "127.0.0.1")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!child.isAlive()) throw new IllegalStateException("native fixture failed to start");
        Path pidTemp = Path.of(request.path() + ".pid.tmp");
        Files.writeString(pidTemp, Long.toString(child.pid()));
        Files.move(pidTemp, Path.of(request.path() + ".pid"));
        while (!Files.exists(Path.of(request.path() + ".release"))) {
          Thread.sleep(10);
        }
        if (request.path().contains("timeout.txt")) {
          Thread.sleep(120_000);
        }
        ExtractionArtifact artifact = ExtractionArtifact.full(
            new ContentExtractor.ExtractionResult("native child was alive", null, "text/plain"),
            request.policy(), "native-child", false);
        SandboxFrames.write(System.out,
            mapper.writeValueAsBytes(SandboxExtractionResponse.fromArtifact(request.requestId(), artifact)));
      }
    }
  }
}
