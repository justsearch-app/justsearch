package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.telemetry.catalog.TestMetricRegistry;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Protocol and lifecycle tests for {@link PersistentExtractionSandbox} (tempdoc 885 item 14).
 *
 * <p>Successor to {@code ProcessExtractionSandboxTest}, which exercised the retired
 * one-child-JVM-per-file sandbox. Every stub child here runs the same length-prefixed serve loop
 * the real child runs and branches on the request's file name, so "the next request succeeds" is
 * asserted against the same stub that just failed — which is the property the pool exists for.
 */
final class PersistentExtractionSandboxTest {

  @TempDir Path tempDir;

  private PersistentExtractionSandbox sandbox(List<String> command, Duration timeout) {
    return sandbox(command, timeout, null);
  }

  private PersistentExtractionSandbox sandbox(
      List<String> command, Duration timeout, ExtractionMetricCatalog catalog) {
    return new PersistentExtractionSandbox(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.readers(),
        command, TikaExtractionPolicy.defaults(), OcrRoutingConfig.disabled(), timeout, 1, 500, catalog);
  }

  private Path file(String name) throws Exception {
    Path path = tempDir.resolve(name);
    Files.writeString(path, "sandbox child content");
    return path;
  }

  @Test
  @Timeout(20)
  void registrationPersistenceFailureReapsTheJustSpawnedChild() throws Exception {
    java.util.concurrent.atomic.AtomicReference<ManagedChild> attempted =
        new java.util.concurrent.atomic.AtomicReference<>();
    ManagedChildRegistry failingRegistry =
        new ManagedChildRegistry() {
          @Override
          public List<ManagedChild> snapshot() {
            return List.of();
          }

          @Override
          public void register(ManagedChild child) throws java.io.IOException {
            attempted.set(child);
            throw new java.io.IOException("simulated manifest persistence failure");
          }

          @Override
          public void remove(String childId) {}
        };
    try (PersistentExtractionSandbox sandbox =
        new PersistentExtractionSandbox(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.readers(),
            javaCommand(ExtractionSandboxChild.class),
            TikaExtractionPolicy.defaults(),
            OcrRoutingConfig.disabled(),
            Duration.ofSeconds(5),
            1,
            500,
            null,
            failingRegistry)) {
      SandboxExtractionException failure =
          assertThrows(
              SandboxExtractionException.class,
              () -> sandbox.extract(file("registration-fails.txt")));
      assertTrue(
          failure.getCause() instanceof java.io.IOException,
          "child acquisition must retain the manifest persistence failure as its cause");
    }

    ManagedChild child = attempted.get();
    assertTrue(child != null, "the failure must occur after a real process has been spawned");
    ProcessHandle handle = ProcessHandle.of(child.pid()).orElse(null);
    assertTrue(handle == null || !handle.isAlive(), "an unregistered child must be reaped");
  }

  @Test
  void failedRegistrationRollbackRetainsHandleForCloseRetry() {
    RetryKillProcess process = new RetryKillProcess();
    PersistentExtractionSandbox sandbox =
        sandbox(List.of("unused-command"), Duration.ofSeconds(1));

    sandbox.rollbackFailedRegistration(process, new java.io.IOException("disk full"));
    assertTrue(process.isAlive());
    assertEquals(1, process.destroyCalls);

    sandbox.close();
    assertFalse(process.isAlive());
    assertEquals(2, process.destroyCalls);
  }

  @Test
  void survivingRollbackMakesCloseFailAndKeepsHandleForFinalRetry() {
    RetryKillProcess process = new RetryKillProcess();
    process.killAfter = Integer.MAX_VALUE;
    PersistentExtractionSandbox sandbox = sandbox(List.of("unused-command"), Duration.ofSeconds(1));
    try {
      sandbox.rollbackFailedRegistration(process, new java.io.IOException("disk full"));
      assertThrows(IllegalStateException.class, sandbox::close);
      assertTrue(process.isAlive());
      assertEquals(2, process.destroyCalls);
      process.killAfter = 3;
      sandbox.close();
      assertFalse(process.isAlive());
      assertEquals(3, process.destroyCalls);
    } finally {
      process.killAfter = 0;
      sandbox.close();
    }
  }

  @Test
  @Timeout(40)
  void realExtractionChildIsRegisteredBeforeUseAndRemovedAfterDeath() throws Exception {
    List<ManagedChild> owned = new java.util.concurrent.CopyOnWriteArrayList<>();
    ManagedChildRegistry registry =
        new ManagedChildRegistry() {
          @Override public List<ManagedChild> snapshot() { return List.copyOf(owned); }
          @Override public void register(ManagedChild child) { owned.add(child); }
          @Override public void remove(String childId) { owned.removeIf(c -> c.id().equals(childId)); }
        };
    PersistentExtractionSandbox sandbox =
        new PersistentExtractionSandbox(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.readers(),
            javaCommand(ExtractionSandboxChild.class), TikaExtractionPolicy.defaults(),
            OcrRoutingConfig.disabled(), Duration.ofSeconds(30), 1, 500, null, registry);
    sandbox.extract(file("registered.txt"));
    assertEquals(1, owned.size());
    ManagedChild child = owned.getFirst();
    assertEquals(ManagedChild.Kind.EXTRACTION, child.kind());
    assertTrue(ProcessHandle.of(child.pid()).orElseThrow().isAlive());

    sandbox.close();

    assertTrue(owned.isEmpty(), "confirmed child death removes the persisted ownership record");
    assertTrue(ProcessHandle.of(child.pid()).isEmpty()
        || !ProcessHandle.of(child.pid()).orElseThrow().isAlive());
  }

  @Test
  @Timeout(40)
  void realChildExtractsFileContent() throws Exception {
    try (PersistentExtractionSandbox sandbox =
        sandbox(javaCommand(ExtractionSandboxChild.class), Duration.ofSeconds(30))) {
      ExtractionArtifact artifact = sandbox.extract(file("sandbox.txt"));

      assertEquals("tika-default-v1", artifact.policyId());
      assertTrue(artifact.result().content().contains("sandbox child content"));
    }
  }

  @Test
  @Timeout(60)
  void scriptedChildRoundTripsAsciiResponseAboveFormerTwoMiBCeiling() throws Exception {
    try (PersistentExtractionSandbox sandbox =
        sandbox(javaCommand(ScriptedChild.class), Duration.ofSeconds(45))) {
      ExtractionArtifact artifact = sandbox.extract(file("large-ascii.txt"));

      assertEquals(2 * 1024 * 1024 + 4096, artifact.result().content().length());
      assertTrue(
          artifact.result().content().getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024);
    }
  }

  @Test
  @Timeout(60)
  void scriptedChildRoundTripsMultibyteResponseAboveFormerTwoMiBCeiling() throws Exception {
    try (PersistentExtractionSandbox sandbox =
        sandbox(javaCommand(ScriptedChild.class), Duration.ofSeconds(45))) {
      ExtractionArtifact artifact = sandbox.extract(file("large-multibyte.txt"));

      assertEquals(800_000, artifact.result().content().length());
      assertTrue(
          artifact.result().content().getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024);
    }
  }

  @Test
  void defaultPolicyHasARepresentableDerivedResponseCeiling() {
    int responseBytes =
        PersistentExtractionSandbox.responseByteCeiling(TikaExtractionPolicy.defaults());

    // Schema2 adds up to96 UTF-16 units for requestId, charged at six JSON bytes each.
    assertEquals(66_453_184, responseBytes);
    assertTrue(responseBytes <= SandboxFrames.MAX_FRAME_BYTES);
  }

  @Test
  void policyAboveProtocolCeilingIsRejectedAtConstructionBeforeSpawn() {
    TikaExtractionPolicy oversized =
        new TikaExtractionPolicy(
            "oversized-policy",
            Integer.MAX_VALUE,
            TikaExtractionPolicy.DEFAULT_MAX_INPUT_BYTES,
            TikaExtractionPolicy.DEFAULT_MAX_OFFICE_INPUT_BYTES,
            128,
            128,
            4096,
            256,
            8,
            100.0d,
            true,
            java.util.Set.of(),
            java.util.Set.of());

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new PersistentExtractionSandbox(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.readers(),
                    javaCommand(ScriptedChild.class),
                    oversized,
                    OcrRoutingConfig.disabled(),
                    Duration.ofSeconds(20),
                    1,
                    500,
                    null));

    assertTrue(failure.getMessage().contains("above the protocol maximum"));
  }

  /**
   * Round-trips the argfile form through the JDK's own parser: a real production child is launched
   * from an argfile whose classpath begins with a directory whose name contains a space. If the
   * quoting or backslash escaping were wrong the launcher would split the classpath and never find
   * the child's main class, so this discriminates on the encoding rather than on the file content.
   *
   * <p>This is the shape that fails as {@code CreateProcess error=206} without the argfile: a
   * Gradle test JVM under an isolated Gradle home has an expanded classpath past Windows'
   * 32,767-character command-line limit.
   */
  @Test
  @Timeout(60)
  void argfileCommandLaunchesTheRealChildWithASpacedClasspathEntry() throws Exception {
    Path spaced = Files.createDirectories(tempDir.resolve("class path with spaces"));
    String classpath = spaced.toAbsolutePath() + File.pathSeparator
        + System.getProperty("java.class.path");

    // Threshold 0 forces the argfile branch regardless of how long this runner's classpath is.
    List<String> command =
        ExtractionSandboxCommand.defaultCommand(TikaExtractionPolicy.defaults(), "", 0, classpath);
    assertTrue(command.get(1).startsWith("@"), "expected the argfile form; got: " + command);

    try (PersistentExtractionSandbox sandbox = sandbox(command, Duration.ofSeconds(30))) {
      ExtractionArtifact artifact = sandbox.extract(file("argfile.txt"));
      assertTrue(artifact.result().content().contains("sandbox child content"));
      assertEquals(1L, sandbox.spawnCount());
    }
  }

  /**
   * Round-trips a hostile token through the JDK's OWN argfile parser and back out of a child JVM.
   *
   * <p>The real-child test above proves the argfile form launches, but it cannot discriminate on
   * escaping: an ordinary Windows path survives either encoding, because the parser leaves an
   * unrecognised escape alone (verified by falsification). The sequences that actually differ are
   * backslash-t, backslash-b, backslash-f, a doubled backslash and an embedded quote — so the
   * probe puts all of those in a system property and asserts the child reads back exactly what
   * was written.
   */
  @Test
  @Timeout(60)
  void argFileEncodingRoundTripsThroughTheJdkParser() throws Exception {
    String hostile =
        "C:\\tab\\back\\form\\already\\\\doubled\\dir with spaces\\a\"quoted\".jar";

    Path argFile =
        ExtractionSandboxCommand.writeArgFile(
            List.of("-cp", System.getProperty("java.class.path"), "-Dsandbox.probe=" + hostile));

    // redirectErrorStream: reading two pipes in sequence deadlocks if the child fills the one not
    // being read. One merged stream removes the shape rather than relying on the output being small.
    Process probe =
        new ProcessBuilder(
                ExtractionSandboxCommand.javaBinary(),
                "@" + argFile,
                ArgFileProbeChild.class.getName())
            .redirectErrorStream(true)
            .start();
    String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(probe.waitFor(45, TimeUnit.SECONDS), "probe child must exit");
    assertEquals(0, probe.exitValue(), "probe child failed: " + output);
    assertEquals(hostile, output.trim(), "argfile encoding must round-trip");
  }

  @Test
  @Timeout(40)
  void oneChildServesThreeRequestsWithoutRespawning() throws Exception {
    try (PersistentExtractionSandbox sandbox =
        sandbox(javaCommand(ScriptedChild.class), Duration.ofSeconds(20))) {
      assertEquals("ok-1.txt", sandbox.extract(file("ok-1.txt")).result().content());
      long pid = sandbox.firstChildPid();
      assertEquals("ok-2.txt", sandbox.extract(file("ok-2.txt")).result().content());
      assertEquals("ok-3.txt", sandbox.extract(file("ok-3.txt")).result().content());

      assertEquals(pid, sandbox.firstChildPid(), "all three requests must share one child");
      assertEquals(1L, sandbox.spawnCount());
      assertEquals(0L, sandbox.restartCount());
    }
  }

  @Test
  @Timeout(60)
  void hangingChildIsKilledAtTheDeadlineAndTheNextRequestSucceeds() throws Exception {
    try (TestMetricRegistry registry = new TestMetricRegistry(ExtractionMetricCatalog.DEFINITIONS)) {
      ExtractionMetricCatalog catalog = new ExtractionMetricCatalog(registry);
      // 10 s, not 2: the property under test is "the hung child is killed at ITS deadline and a
      // replacement serves the next request", not the deadline's value. The second extract has to
      // spawn a cold JVM, and on Windows under concurrent-agent load that alone exceeds 2 s — the
      // test then failed with the very ExtractionTimeoutException it had just asserted, on the
      // NEXT call. The hang fixture still trips the deadline long before 10 s.
      try (PersistentExtractionSandbox sandbox =
          sandbox(javaCommand(ScriptedChild.class), Duration.ofSeconds(10), catalog)) {
        Path hanging = file("hang.txt");
        assertThrows(
            TimeboxedContentExtractor.ExtractionTimeoutException.class,
            () -> sandbox.extract(hanging));

        assertEquals(1L, sandbox.restartCount());
        assertEquals(
            1L,
            registry.counterValue(
                ExtractionMetricCatalog.SANDBOX_RESTART_TOTAL,
                ExtractionSandboxRestartTags.of(PersistentExtractionSandbox.REASON_TIMEOUT)));

        assertEquals("next-after-timeout.txt", sandbox.extract(file("next-after-timeout.txt")).result().content());
        assertEquals(2L, sandbox.spawnCount(), "the killed child must be replaced, not reused");
      }
    }
  }

  @Test
  @Timeout(60)
  void crashingChildReportsItsExitCodeAndTheNextRequestSucceeds() throws Exception {
    try (PersistentExtractionSandbox sandbox =
        sandbox(javaCommand(ScriptedChild.class), Duration.ofSeconds(20))) {
      Path crashing = file("crash.txt");
      SandboxExtractionException failure =
          assertThrows(SandboxExtractionException.class, () -> sandbox.extract(crashing));
      assertTrue(
          failure.getMessage().contains("exited with code 3"),
          "failure reason must carry the child exit code; got: " + failure.getMessage());

      assertEquals("next-after-exit.txt", sandbox.extract(file("next-after-exit.txt")).result().content());
      assertEquals(2L, sandbox.spawnCount());
    }
  }

  @Test
  @Timeout(60)
  void childHeapExhaustionIsAPermanentParseFailure() throws Exception {
    try (PersistentExtractionSandbox sandbox =
        sandbox(javaCommand(ScriptedChild.class, "-Xmx64m"), Duration.ofSeconds(45))) {
      Path oom = file("oom.txt");
      ContentExtractor.ExtractionException failure =
          assertThrows(
              ContentExtractor.ExtractionException.class, () -> sandbox.extract(oom));

      // PERMANENT means: not the retryable SandboxExtractionException. JobBatchExtractor maps a
      // plain ExtractionException to PARSER_FAILED + IngestionRetryPolicy.NONE.
      assertEquals(
          ContentExtractor.ExtractionException.class,
          failure.getClass(),
          "an OOM child must not be reported as a retryable sandbox failure: "
              + failure.getMessage());
      assertTrue(
          failure.getMessage().contains("exhausted its heap"),
          "got: " + failure.getMessage());
    }
  }

  /**
   * A chatty parser must not be able to demote an OOM to a retryable failure.
   *
   * <p>The stderr capture is bounded at 64 KB. When it kept the HEAD, a parser that logged more
   * than that before dying pushed its own {@code OutOfMemoryError} trace out of the buffer, the
   * substring test in {@code discardAndClassify} went false, and a permanent parse failure was
   * reported as a retryable crash — so the file would be retried forever against a heap it cannot
   * fit in. The capture keeps the tail for exactly this case.
   */
  @Test
  @Timeout(90)
  void chattyParserCannotDemoteAnOomToRetryable() throws Exception {
    try (PersistentExtractionSandbox sandbox =
        sandbox(javaCommand(ScriptedChild.class, "-Xmx64m"), Duration.ofSeconds(60))) {
      Path noisyOom = file("noisy-oom.txt");
      ContentExtractor.ExtractionException failure =
          assertThrows(ContentExtractor.ExtractionException.class, () -> sandbox.extract(noisyOom));

      assertEquals(
          ContentExtractor.ExtractionException.class,
          failure.getClass(),
          "64 KB of parser chatter must not push the OOM trace out of the capture: "
              + failure.getMessage());
      assertTrue(
          failure.getMessage().contains("exhausted its heap"), "got: " + failure.getMessage());
    }
  }

  @Test
  @Timeout(40)
  void pollutedStdoutDoesNotCorruptTheFrame() throws Exception {
    try (PersistentExtractionSandbox sandbox =
        sandbox(javaCommand(ScriptedChild.class), Duration.ofSeconds(20))) {
      assertEquals("pollute.txt", sandbox.extract(file("pollute.txt")).result().content());
      assertEquals(0L, sandbox.restartCount());
    }
  }

  @Test
  @Timeout(40)
  void duplicateResponseCannotBecomeTheNextDocumentsText() throws Exception {
    try (var sandbox = sandbox(javaCommand(ScriptedChild.class), Duration.ofSeconds(10))) {
      assertEquals("duplicate.txt", sandbox.extract(file("duplicate.txt")).result().content());
      long first = sandbox.firstChildPid();
      var failure = assertThrows(SandboxExtractionException.class,
          () -> sandbox.extract(file("next.txt")));
      assertTrue(causeMessages(failure).contains("requestId does not match"));
      assertFalse(ProcessHandle.of(first).map(ProcessHandle::isAlive).orElse(false));
      assertEquals(1, sandbox.restartCount());
      assertEquals("fresh.txt", sandbox.extract(file("fresh.txt")).result().content());
      assertNotEquals(first, sandbox.firstChildPid());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"missing-id", "legacy-schema"})
  @Timeout(40)
  void uncorrelatedWireResponsesAreRejectedAndParserRetired(String mode) throws Exception {
    try (var sandbox = sandbox(javaCommand(ScriptedChild.class), Duration.ofSeconds(10))) {
      var failure = assertThrows(SandboxExtractionException.class,
          () -> sandbox.extract(file(mode + ".txt")));
      assertTrue(causeMessages(failure).contains(mode.equals("missing-id")
          ? "requestId must contain" : "Unsupported sandbox response schema"));
      assertEquals(1, sandbox.restartCount());
      assertEquals("fresh.txt", sandbox.extract(file("fresh.txt")).result().content());
      assertEquals(2, sandbox.spawnCount());
    }
  }

  @Test
  @Timeout(40)
  void saturatedReaderAfterFrameWriteRetiresChildBeforeNextRequest() throws Exception {
    var owner = new io.justsearch.core.execution.TestEngineExecutors();
    var registration = owner.register(new io.justsearch.core.execution.EngineExecutorSpec(
        "refused-reader", io.justsearch.core.execution.EngineExecutorSpec.Kind.BACKGROUND,
        io.justsearch.core.execution.EngineExecutorSpec.Mode.PLATFORM, 1, 1, 1));
    var observed = org.mockito.Mockito.spy(registration);
    var executor = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ExecutorService>();
    org.mockito.Mockito.doAnswer(open -> {
      var pool = registration.open(open.getArgument(0));
      executor.set(pool);
      return pool;
    }).when(observed).open(org.mockito.ArgumentMatchers.any());
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var queuedDone = new java.util.concurrent.CountDownLatch(1);
    var firstPid = new java.util.concurrent.atomic.AtomicLong();
    ManagedChildRegistry children = new ManagedChildRegistry() {
      @Override public List<ManagedChild> snapshot() { return List.of(); }
      @Override public void register(ManagedChild child) { firstPid.compareAndSet(0, child.pid()); }
      @Override public void remove(String id) {}
    };
    try (var sandbox = new PersistentExtractionSandbox(observed, javaCommand(ScriptedChild.class),
        TikaExtractionPolicy.defaults(), OcrRoutingConfig.disabled(), Duration.ofSeconds(10),
        1, 500, null, children)) {
      executor.get().execute(() -> {
        entered.countDown();
        try { release.await(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      });
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      executor.get().execute(queuedDone::countDown);
      var failure = assertThrows(SandboxExtractionException.class,
          () -> sandbox.extract(file("unread.txt")));
      assertTrue(failure.getCause() instanceof java.util.concurrent.RejectedExecutionException);
      assertTrue(firstPid.get() > 0, "rejection must occur after actual parser spawn and frame write");
      assertFalse(ProcessHandle.of(firstPid.get()).map(ProcessHandle::isAlive).orElse(false));
      assertEquals(1, sandbox.restartCount());
      release.countDown();
      assertTrue(queuedDone.await(2, TimeUnit.SECONDS));
      assertEquals("next.txt", sandbox.extract(file("next.txt")).result().content());
      assertNotEquals(firstPid.get(), sandbox.firstChildPid());
    } finally {
      release.countDown();
      owner.close();
    }
  }

  @Test
  @Timeout(40)
  void survivingRetirementRetainsItsSlotUntilKillActuallyCompletes() throws Exception {
    Process realChild = new ProcessBuilder(javaCommand(ScriptedChild.class)).start();
    var allowKill = new java.util.concurrent.atomic.AtomicBoolean();
    Process retained = org.mockito.Mockito.mock(Process.class,
        org.mockito.AdditionalAnswers.delegatesTo(realChild));
    org.mockito.Mockito.doAnswer(kill -> {
      if (allowKill.get()) realChild.destroyForcibly();
      return retained;
    }).when(retained).destroyForcibly();
    org.mockito.Mockito.doAnswer(wait -> allowKill.get()
        && realChild.waitFor((Long) wait.getArgument(0), wait.getArgument(1)))
        .when(retained).waitFor(org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any(TimeUnit.class));
    try (var sandbox = sandbox(javaCommand(ScriptedChild.class), Duration.ofSeconds(1))) {
      try (var spawns = org.mockito.Mockito.mockConstruction(ProcessBuilder.class,
          (builder, context) -> org.mockito.Mockito.when(builder.start()).thenReturn(retained))) {
        assertThrows(IllegalStateException.class, () -> sandbox.extract(file("hang.txt")));
        assertTrue(realChild.isAlive());
        assertEquals(realChild.pid(), sandbox.firstChildPid(), "retain exact live child in its slot");
        assertThrows(IllegalStateException.class, () -> sandbox.extract(file("blocked.txt")));
        assertEquals(1, spawns.constructed().size(), "retiring slot cannot spawn a replacement");
        assertEquals(1, sandbox.spawnCount());
      }
      allowKill.set(true);
      assertEquals("recovered.txt", sandbox.extract(file("recovered.txt")).result().content());
      assertFalse(realChild.isAlive());
      assertNotEquals(realChild.pid(), sandbox.firstChildPid());
    } finally {
      allowKill.set(true);
      realChild.destroyForcibly();
      assertTrue(realChild.waitFor(5, TimeUnit.SECONDS));
    }
  }

  private static String causeMessages(Throwable failure) {
    StringBuilder messages = new StringBuilder();
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      messages.append(cause.getMessage()).append(' ');
    }
    return messages.toString();
  }

  @Test
  @Timeout(40)
  void malformedResponseIsASandboxFailure() throws Exception {
    try (PersistentExtractionSandbox sandbox =
        sandbox(javaCommand(ScriptedChild.class), Duration.ofSeconds(20))) {
      Path malformed = file("malformed.txt");
      assertThrows(SandboxExtractionException.class, () -> sandbox.extract(malformed));
    }
  }

  @Test
  @Timeout(40)
  void oversizedFrameIsASandboxFailure() throws Exception {
    try (PersistentExtractionSandbox sandbox =
        new PersistentExtractionSandbox(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.readers(),
            javaCommand(ScriptedChild.class),
            TikaExtractionPolicy.defaults(),
            OcrRoutingConfig.disabled(),
            Duration.ofSeconds(20),
            1,
            500,
            null,
            2048,
            4096)) {
      Path oversized = file("oversized.txt");
      assertThrows(SandboxExtractionException.class, () -> sandbox.extract(oversized));
    }
  }

  @Test
  @Timeout(40)
  void ocrConfigReachesTheChild() throws Exception {
    OcrRoutingConfig ocrConfig =
        new OcrRoutingConfig(true, List.of("deu"), 1_234, 4, 2048, 8_000_000, null, null);
    try (PersistentExtractionSandbox sandbox =
        new PersistentExtractionSandbox(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.readers(),
            javaCommand(ScriptedChild.class),
            TikaExtractionPolicy.defaults(),
            ocrConfig,
            Duration.ofSeconds(20),
            1,
            500,
            null)) {
      assertEquals("ocr-enabled:deu", sandbox.extract(file("ocr.txt")).result().content());
    }
  }

  @Test
  @Timeout(40)
  void childRecyclesAfterItsRequestBudget() throws Exception {
    try (PersistentExtractionSandbox sandbox =
        new PersistentExtractionSandbox(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.readers(),
            javaCommand(ScriptedChild.class),
            TikaExtractionPolicy.defaults(),
            OcrRoutingConfig.disabled(),
            Duration.ofSeconds(20),
            1,
            2,
            null)) {
      sandbox.extract(file("ok-1.txt"));
      long first = sandbox.firstChildPid();
      sandbox.extract(file("ok-2.txt"));
      sandbox.extract(file("ok-3.txt"));

      assertNotEquals(first, sandbox.firstChildPid(), "child must be recycled after 2 requests");
      assertEquals(2L, sandbox.spawnCount());
      assertEquals(1L, sandbox.restartCount());
    }
  }

  /**
   * Regression for the defect the chaos tier found (tempdoc 885 §SC-chaos): the timebox and the
   * sandbox enforced the SAME deadline, and the timebox starts its clock first, so it always won.
   * Its {@code shutdownNow()} then interrupted the pool's wait, and every wedged child was
   * recycled as {@code interrupted} — the pool's own kill-at-the-deadline path, which is the whole
   * point of the design, never ran. The unit tests could not see it because they drive the pool
   * directly, with no timebox around it; this one goes through the factory, as production does.
   */
  @Test
  @Timeout(60)
  void factoryLetsTheSandboxDeadlineFireBeforeTheTimeboxBackstop() throws Exception {
    try (TestMetricRegistry registry = new TestMetricRegistry(ExtractionMetricCatalog.DEFINITIONS)) {
      ExtractionMetricCatalog catalog = new ExtractionMetricCatalog(registry);
      try (TimeboxedContentExtractor extractor =
          ExtractionSandboxFactory.create(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(), io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), io.justsearch.indexerworker.TestWorkerExecutorRegistrations.readers(),
              ExtractionSandboxFactory.Mode.PROCESS,
              TikaExtractionPolicy.defaults(),
              OcrRoutingConfig.disabled(),
              // Must exceed TimeboxedContentExtractor.MIN_TIMEOUT (5 s): below it the timebox is
              // clamped up to 5 s and would beat a 2 s sandbox deadline for the wrong reason,
              // making this test pass even with the grace removed.
              Duration.ofSeconds(6),
              catalog,
              OcrMetricCatalog.noop(),
              javaCommand(ScriptedChild.class),
              ExtractionSandboxFactory.PoolSettings.defaults())) {
        Path hanging = file("hang.txt");
        assertThrows(
            TimeboxedContentExtractor.ExtractionTimeoutException.class,
            () -> extractor.extractArtifact(hanging));

        assertEquals(
            1L,
            registry.counterValue(
                ExtractionMetricCatalog.SANDBOX_RESTART_TOTAL,
                ExtractionSandboxRestartTags.of(PersistentExtractionSandbox.REASON_TIMEOUT)),
            "the sandbox must own the deadline and kill the child itself");
        assertEquals(
            0L,
            registry.counterValue(
                ExtractionMetricCatalog.SANDBOX_RESTART_TOTAL,
                ExtractionSandboxRestartTags.of(
                    PersistentExtractionSandbox.REASON_INTERRUPTED)),
            "the timebox backstop must not pre-empt the sandbox deadline");
      }
    }
  }

  @Test
  @Timeout(60)
  void childExitsWhenItsParentPidIsGone() throws Exception {
    Process victim = new ProcessBuilder(javaCommand(ExitingChild.class)).start();
    assertTrue(victim.waitFor(30, TimeUnit.SECONDS), "helper process must exit");
    // Keep the Process handle referenced for the rest of the test: on Windows an open handle stops
    // the OS recycling the PID, so "dead pid" stays unambiguously dead.
    long deadPid = victim.pid();

    List<String> command = new ArrayList<>(javaCommand(ExtractionSandboxChild.class));
    command.add(ExtractionSandboxChild.PARENT_PID_FLAG + deadPid);
    Process orphan = new ProcessBuilder(command).start();
    try {
      assertTrue(
          orphan.waitFor(30, TimeUnit.SECONDS),
          "child must halt itself once its parent PID is gone (no orphan after a Worker kill)");
    } finally {
      orphan.destroyForcibly();
      // Holds the handle to the end of the test so Windows cannot recycle deadPid underneath us.
      victim.destroyForcibly();
    }
  }

  /**
   * Launches a stub child on this JVM and classpath, through the same inline-or-argfile choice the
   * production builder makes. Not cosmetic reuse: this helper spells the classpath out, and under
   * an isolated {@code GRADLE_USER_HOME} the expanded test classpath crosses Windows'
   * 32,767-character limit, so every test in this class died with {@code CreateProcess error=206}.
   */
  static List<String> javaCommand(Class<?> mainClass, String... jvmArgs) {
    String executable =
        Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString();
    List<String> options = new ArrayList<>(List.of(jvmArgs));
    options.add("-cp");
    options.add(System.getProperty("java.class.path"));

    List<String> direct = new ArrayList<>();
    direct.add(executable);
    direct.addAll(options);
    direct.add(mainClass.getName());
    if (ExtractionSandboxCommand.commandLineLength(direct)
        <= ExtractionSandboxCommand.MAX_INLINE_COMMAND_CHARS) {
      return List.copyOf(direct);
    }
    return List.of(
        executable, "@" + ExtractionSandboxCommand.writeArgFile(options), mainClass.getName());
  }

  private static boolean windows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  /** Echoes back the system property the argfile carried, for the encoding round-trip. */
  public static final class ArgFileProbeChild {
    public static void main(String[] args) {
      System.out.print(System.getProperty("sandbox.probe", "<absent>"));
    }
  }

  /** Trivial helper whose only job is to produce a PID that is definitely dead. */
  public static final class ExitingChild {
    public static void main(String[] args) {
      // Intentionally empty: the JVM exits immediately.
    }
  }

  private static final class RetryKillProcess extends Process {
    private boolean alive = true;
    private int destroyCalls;
    private int killAfter = 2;

    @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
    @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
    @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
    @Override public int waitFor() { alive = false; return 0; }
    @Override public boolean waitFor(long timeout, TimeUnit unit) { return !alive; }
    @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
    @Override public void destroy() { destroyForcibly(); }
    @Override public Process destroyForcibly() {
      destroyCalls++;
      if (destroyCalls >= killAfter) alive = false;
      return this;
    }
    @Override public boolean isAlive() { return alive; }
    @Override public long pid() { return 424243L; }
  }

  /**
   * One stub child covering every failure mode, selected by the request's file name. Runs the real
   * serve loop, so a request that follows a failure lands on a genuinely fresh child.
   */
  public static final class ScriptedChild {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    public static void main(String[] args) throws Exception {
      PrintStream protocolOut = System.out;
      System.setOut(new PrintStream(System.err, true, StandardCharsets.UTF_8));
      InputStream in = System.in;
      byte[] frame;
      List<byte[]> retained = new ArrayList<>();
      while ((frame = SandboxFrames.read(in, SandboxFrames.MAX_FRAME_BYTES)) != null) {
        SandboxExtractionRequest request =
            MAPPER.readValue(
                new String(frame, StandardCharsets.UTF_8), SandboxExtractionRequest.class);
        String name = Path.of(request.path()).getFileName().toString();

        if (name.contains("hang")) {
          Thread.sleep(600_000L);
        }
        if (name.contains("crash")) {
          System.exit(3);
        }
        if (name.contains("oom")) {
          if (name.contains("noisy")) {
            // Overflow the parent's 64 KB stderr capture BEFORE dying, so the OutOfMemoryError
            // trace is only visible to a capture that keeps the tail rather than the head.
            for (int i = 0; i < 400; i++) {
              System.err.println("chatty parser noise line " + i + " " + "x".repeat(512));
            }
            System.err.flush();
          }
          while (true) {
            retained.add(new byte[8 * 1024 * 1024]);
          }
        }
        if (name.contains("malformed")) {
          writeRaw(protocolOut, "{not-json".getBytes(StandardCharsets.UTF_8));
          continue;
        }
        if (name.contains("oversized")) {
          writeRaw(protocolOut, "x".repeat(8192).getBytes(StandardCharsets.UTF_8));
          continue;
        }
        if (name.contains("pollute")) {
          // Goes to stderr thanks to the redirect above — the pollution the frame must survive.
          System.out.println("parser chatter {\"schemaVersion\":1}");
        }

        OcrRoutingConfig ocr = request.ocrConfig();
        String content;
        if (name.contains("large-ascii")) {
          content = "a".repeat(2 * 1024 * 1024 + 4096);
        } else if (name.contains("large-multibyte")) {
          content = "€".repeat(800_000);
        } else {
          content =
              name.contains("ocr") && ocr != null && ocr.enabled()
                  ? "ocr-enabled:" + String.join(",", ocr.languages())
                  : name;
        }
        ExtractionArtifact artifact =
            ExtractionArtifact.full(
                new ContentExtractor.ExtractionResult(content, null, "text/plain"),
                request.policy(),
                "scripted-child",
                false);
        String response = MAPPER.writeValueAsString(
            SandboxExtractionResponse.fromArtifact(request.requestId(), artifact));
        if (name.contains("missing-id")) {
          response = response.replace("\"requestId\":\"" + request.requestId() + "\",", "");
        }
        if (name.contains("legacy-schema")) {
          response = response.replace("\"schemaVersion\":2", "\"schemaVersion\":1");
        }
        byte[] payload = response.getBytes(StandardCharsets.UTF_8);
        SandboxFrames.write(protocolOut, payload);
        if (name.contains("duplicate")) SandboxFrames.write(protocolOut, payload);
      }
    }

    private static void writeRaw(OutputStream out, byte[] payload) throws java.io.IOException {
      SandboxFrames.write(out, payload);
    }
  }
}
