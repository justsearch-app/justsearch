package io.justsearch.ui.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.RuntimeManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 501 — verifies the runtime-manifest publisher writes a well-formed manifest
 * with phased readiness + worker-state projection, removes it on close, and notifies
 * registered listeners.
 *
 * <p>Phase 1 introduced the head/worker shape; Phase 12 added the lifecycle field and the
 * worker.state ("pending"|"ready"|"failed") discriminator + spawnError companion.
 */
class RuntimeManifestPublisherTest {

  @Test
  void instanceIdIsFreshUuidPerPublisher(@TempDir Path tmp) {
    RuntimeManifestPublisher p1 = new RuntimeManifestPublisher(tmp);
    RuntimeManifestPublisher p2 = new RuntimeManifestPublisher(tmp);

    assertNotEquals(p1.instanceId(), p2.instanceId(), "each publisher must mint a fresh UUID");
    UUID.fromString(p1.instanceId());
    UUID.fromString(p2.instanceId());
  }

  @Test
  void publishHeadWritesPhasedManifestAndNotifiesListeners(@TempDir Path tmp) throws IOException {
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp);
    AtomicReference<RuntimeManifest> seen = new AtomicReference<>();
    publisher.addListener(seen::set);

    RuntimeManifest published = publisher.publishHead(54321, "session-abc");

    assertEquals(RuntimeManifest.CURRENT_SCHEMA_VERSION, published.schemaVersion());
    assertEquals(publisher.instanceId(), published.instanceId());
    assertEquals(tmp.toString(), published.dataDir());
    assertEquals("LIFECYCLE_STATE_STARTING", published.lifecycle(),
        "head-only publish initializes lifecycle = STARTING (worker not yet connected)");
    assertNotNull(published.head());
    assertEquals(54321, published.head().apiPort());
    assertEquals("http://127.0.0.1:54321", published.head().apiBaseUrl());
    assertEquals("session-abc", published.head().sessionToken());
    assertNotNull(published.head().readyAt());
    assertNull(published.worker(), "worker fields appear only after publishWorkerReady/Failed");
    assertEquals(published, seen.get(), "listeners must be notified synchronously");
    assertTrue(Files.exists(publisher.manifestPath()));
  }

  @Test
  void publishWorkerReadyUpdatesWorkerFieldsAndPreservesHead(@TempDir Path tmp) throws IOException {
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp);
    publisher.publishHead(54321, null);

    // Lane F B11 removed grpcPort: the index half is composed in this JVM and has no process port.
    RuntimeManifest updated =
        publisher.publishWorkerReady(tmp.resolve("index").toString(), "LIFECYCLE_STATE_READY");

    assertEquals(54321, updated.head().apiPort());
    assertEquals("LIFECYCLE_STATE_READY", updated.lifecycle());
    assertNotNull(updated.worker());
    assertEquals("ready", updated.worker().state());
    assertEquals(tmp.resolve("index").toString(), updated.worker().indexBasePath());
    assertNotNull(updated.worker().readyAt());
    assertNull(updated.worker().spawnError(), "spawnError null when state=ready");
  }

  @Test
  void publishWorkerFailedRecordsReasonAndDegradedLifecycle(@TempDir Path tmp)
      throws IOException {
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp);
    publisher.publishHead(54321, null);

    RuntimeManifest updated =
        publisher.publishWorkerFailed("native library missing", "LIFECYCLE_STATE_DEGRADED");

    assertEquals("LIFECYCLE_STATE_DEGRADED", updated.lifecycle());
    assertNotNull(updated.worker());
    assertEquals("failed", updated.worker().state());
    assertEquals("native library missing", updated.worker().spawnError());
    assertNull(updated.worker().readyAt(), "no readyAt when worker failed");
  }

  @Test
  void publishLifecycleNoopsWhenUnchanged(@TempDir Path tmp) throws IOException {
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp);
    publisher.publishHead(54321, null);

    RuntimeManifest before = publisher.current();
    RuntimeManifest same = publisher.publishLifecycle("LIFECYCLE_STATE_STARTING");

    assertEquals(before, same, "publishLifecycle is a no-op when the value is unchanged");
  }

  @Test
  void publishLifecycleUpdatesOnTransition(@TempDir Path tmp) throws IOException {
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp);
    publisher.publishHead(54321, null);
    assertEquals("LIFECYCLE_STATE_STARTING", publisher.current().lifecycle());

    RuntimeManifest updated = publisher.publishLifecycle("LIFECYCLE_STATE_READY");

    assertEquals("LIFECYCLE_STATE_READY", updated.lifecycle());
    assertEquals(updated, publisher.current(), "current() reflects the transition");
  }

  @Test
  void publishWorkerBeforeHeadThrows(@TempDir Path tmp) {
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp);
    assertThrows(
        IllegalStateException.class,
        () -> publisher.publishWorkerReady("/tmp/index", "READY"));
    assertThrows(
        IllegalStateException.class,
        () -> publisher.publishWorkerFailed("oops", "DEGRADED"));
  }

  @Test
  void publishHeadTwiceThrows(@TempDir Path tmp) throws IOException {
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp);
    publisher.publishHead(54321, null);
    assertThrows(IllegalStateException.class, () -> publisher.publishHead(54321, null));
  }

  @Test
  void manifestFileIsValidJsonWithExpectedShape(@TempDir Path tmp) throws IOException {
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp);
    publisher.publishHead(54321, "tok");
    publisher.publishWorkerReady("/tmp/index", "READY");

    String content = Files.readString(publisher.manifestPath());
    JsonNode root = new ObjectMapper().readTree(content);

    assertEquals(2, root.get("schemaVersion").asInt());
    assertEquals(publisher.instanceId(), root.get("instanceId").asText());
    assertTrue(root.get("pid").asLong() > 0);
    assertEquals("READY", root.get("lifecycle").asText());
    // `head.apiPort` is the API-port discovery contract for the NON-JVM consumers — the MCPB
    // stdio bridge (packaging/mcpb/server/index.js) and scripts/sandbox/mcp-typed-confirm.mjs
    // read exactly this path (tempdoc 930 repointed both off the removed api-port.txt sibling;
    // check-runtime-manifest-closure forbids reintroducing one). Renaming it breaks them
    // silently, so the field name is asserted here at the JSON level, not just on the record.
    assertEquals(
        54321,
        root.get("head").get("apiPort").asInt(),
        "head.apiPort is the non-JVM API-port discovery contract (tempdoc 501 §6 / 930)");
    assertNull(root.get("worker").get("grpcPort"));
    assertEquals("ready", root.get("worker").get("state").asText());
  }

  @Test
  void closeRemovesManifestFile(@TempDir Path tmp) throws IOException {
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp);
    publisher.publishHead(54321, null);
    assertTrue(Files.exists(publisher.manifestPath()));

    publisher.close();

    assertFalse(Files.exists(publisher.manifestPath()), "manifest removed on clean shutdown");
    assertNull(publisher.current());
  }

  @Test
  void predecessorChildrenAreCarriedByThePreBindSeed(@TempDir Path tmp) throws IOException {
    Path runtime = tmp.resolve("runtime");
    Files.createDirectories(runtime);
    ManagedChild child =
        new ManagedChild(
            "predecessor-child",
            ManagedChild.Kind.EXTRACTION,
            424242,
            "2026-09-08T00:00:00Z",
            ManagedChild.normalizePath(Path.of("java")),
            null,
            null,
            null,
            "argv");
    Files.writeString(
        runtime.resolve("manifest.json"),
        "{\"schemaVersion\":2,\"pid\":424241,\"startedAt\":\"2026-09-08T00:00:00Z\","
            + "\"children\":["
            + new ObjectMapper().writeValueAsString(child)
            + "]}");

    MutableManagedChildRegistry registry = new MutableManagedChildRegistry();
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp, registry);
    RuntimeManifest seed = publisher.publishOwnershipSeed();

    assertEquals(java.util.List.of(child), registry.snapshot());
    assertEquals(java.util.List.of(child), seed.children());
    assertNull(seed.head().apiPort(), "the durable ownership seed precedes API bind");
    JsonNode disk = new ObjectMapper().readTree(Files.readString(publisher.manifestPath()));
    assertEquals("predecessor-child", disk.get("children").get(0).get("id").asText());
  }

  @Test
  void futureManifestSchemaIsRefusedBeforePublication(@TempDir Path tmp) throws IOException {
    Path runtime = tmp.resolve("runtime");
    Files.createDirectories(runtime);
    Files.writeString(runtime.resolve("manifest.json"), "{\"schemaVersion\":3,\"pid\":424242}");

    assertThrows(IllegalStateException.class, () -> new RuntimeManifestPublisher(tmp));
  }

  @Test
  void restartRetainsOwnershipAndFinallyCloseCannotEraseIt(@TempDir Path tmp) throws IOException {
    MutableManagedChildRegistry registry = new MutableManagedChildRegistry();
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp, registry);
    publisher.publishOwnershipSeed();
    ManagedChild child =
        new ManagedChild(
            "warm-child", ManagedChild.Kind.LLAMA_SERVER, 424242,
            "2026-09-08T00:00:00Z", ManagedChild.normalizePath(Path.of("llama-server")),
            "http://127.0.0.1:8080", "model.gguf", "declared", "argv");
    registry.register(child);
    publisher.markShutdownPending("restart");

    publisher.completeShutdown("restart", true, "GRACEFUL");
    publisher.close();

    assertTrue(Files.isRegularFile(publisher.manifestPath()));
    JsonNode disk = new ObjectMapper().readTree(Files.readString(publisher.manifestPath()));
    assertEquals("ready", disk.get("shutdownHandoff").get("state").asText());
    assertEquals("warm-child", disk.get("children").get(0).get("id").asText());
  }

  @Test
  void pendingShutdownSurvivesLateReadinessWritesButNotANewIncarnation(@TempDir Path tmp)
      throws IOException {
    MutableManagedChildRegistry registry = new MutableManagedChildRegistry();
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp, registry);
    publisher.publishHead(54321, "test-token");
    publisher.markShutdownPending("restart");
    var original = new ObjectMapper().readTree(Files.readString(publisher.manifestPath()));

    // Capability callbacks can still fire while ordered close is draining. The handoff,
    // unlike aggregate readiness, must survive all those projections and finally cleanup.
    publisher.publishLifecycle("LIFECYCLE_STATE_READY");
    publisher.publishWorkerFailed("closing", "LIFECYCLE_STATE_DEGRADED");
    publisher.publishAi("ready", false, null, true, "LIFECYCLE_STATE_READY", null, null, null, null);
    publisher.close();
    var retained = new ObjectMapper().readTree(Files.readString(publisher.manifestPath()));
    assertEquals(original.get("shutdownHandoff"), retained.get("shutdownHandoff"));
    assertEquals(original.get("instanceId"), retained.get("instanceId"));

    RuntimeManifestPublisher successor = new RuntimeManifestPublisher(tmp);
    RuntimeManifest seed = successor.publishOwnershipSeed();
    assertNotEquals(publisher.instanceId(), seed.instanceId());
    assertNull(seed.shutdownHandoff(), "a successor must not inherit a predecessor shutdown intent");
  }

  @Test
  void terminalShutdownDeletesOnlyAfterChildrenAreGoneAndIndexIsGraceful(@TempDir Path tmp)
      throws IOException {
    MutableManagedChildRegistry registry = new MutableManagedChildRegistry();
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp, registry);
    publisher.publishOwnershipSeed();
    ManagedChild child =
        new ManagedChild(
            "live-child", ManagedChild.Kind.EXTRACTION, 424242,
            "2026-09-08T00:00:00Z", ManagedChild.normalizePath(Path.of("java")),
            null, null, null, "argv");
    registry.register(child);
    publisher.markShutdownPending("quit");
    assertThrows(IOException.class, () -> publisher.completeShutdown("quit", true, "GRACEFUL"));
    publisher.close();
    assertTrue(Files.isRegularFile(publisher.manifestPath()), "failed child cleanup retains evidence");

    registry.remove(child.id());
    publisher.completeShutdown("quit", true, "GRACEFUL");
    assertFalse(Files.exists(publisher.manifestPath()));
  }

  @Test
  void shutdownWaitingForRegistryDoesNotHoldPublisher(@TempDir Path tmp) throws Exception {
    MutableManagedChildRegistry registry = new MutableManagedChildRegistry();
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp, registry);
    publisher.publishOwnershipSeed();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread completion = new Thread(() -> {
      try { publisher.completeShutdown("restart", true, "GRACEFUL"); }
      catch (Throwable error) { failure.set(error); }
    }, "contended-manifest-shutdown");
    try (var tasks = Executors.newSingleThreadExecutor()) {
      java.util.concurrent.Future<?> pending;
      synchronized (registry) {
        completion.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (completion.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
          Thread.sleep(1);
        }
        assertEquals(Thread.State.BLOCKED, completion.getState());
        pending = tasks.submit(() -> { publisher.markShutdownPending("restart"); return null; });
        // Former publisher->registry code holds publisher here and times this operation out.
        // Release the registry even on failure so the negative control cannot strand test threads.
        pending.get(1, TimeUnit.SECONDS);
      }
      completion.join(5000);
      assertFalse(completion.isAlive());
      assertNull(failure.get());
    }
  }

  @Test
  void childPersistenceAndShutdownCompletionCannotDeadlockUnderForcedContention(@TempDir Path tmp)
      throws Exception {
    MutableManagedChildRegistry registry = new MutableManagedChildRegistry();
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp, registry);
    publisher.publishOwnershipSeed();
    CountDownLatch childCommitEntered = new CountDownLatch(1);
    CountDownLatch releaseChildCommit = new CountDownLatch(1);
    publisher.addListener(
        manifest -> {
          if (manifest.children() != null && !manifest.children().isEmpty()) {
            childCommitEntered.countDown();
            try {
              releaseChildCommit.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        });
    ManagedChild child =
        new ManagedChild(
            "contended-child", ManagedChild.Kind.EXTRACTION, 424242,
            "2026-09-08T00:00:00Z", ManagedChild.normalizePath(Path.of("java")),
            "stdio", null, null, "argv");

    try (var tasks = Executors.newFixedThreadPool(2)) {
      var registration =
          tasks.submit(
              () -> {
                registry.register(child);
                return null;
              });
      assertTrue(childCommitEntered.await(5, TimeUnit.SECONDS));
      var shutdown =
          tasks.submit(
              () -> {
                publisher.completeShutdown("restart", true, "GRACEFUL");
                return null;
              });
      assertThrows(TimeoutException.class, () -> shutdown.get(100, TimeUnit.MILLISECONDS));

      releaseChildCommit.countDown();
      registration.get(5, TimeUnit.SECONDS);
      shutdown.get(5, TimeUnit.SECONDS);
    }

    JsonNode disk = new ObjectMapper().readTree(Files.readString(publisher.manifestPath()));
    assertEquals("contended-child", disk.path("children").get(0).path("id").asText());
    assertEquals("ready", disk.path("shutdownHandoff").path("state").asText());
  }

  @Test
  void shutdownDispositionCoversEveryReasonAndCompletionOutcome(@TempDir Path tmp)
      throws IOException {
    for (String reason : java.util.List.of("restart", "hang")) {
      RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp.resolve(reason));
      publisher.publishOwnershipSeed();
      publisher.markShutdownPending(reason);
      publisher.completeShutdown(reason, true, "GRACEFUL");
      publisher.close();
      JsonNode disk = new ObjectMapper().readTree(Files.readString(publisher.manifestPath()));
      assertEquals("ready", disk.path("shutdownHandoff").path("state").asText());
    }

    for (String reason : java.util.List.of("quit", "upgrade")) {
      RuntimeManifestPublisher publisher =
          new RuntimeManifestPublisher(tmp.resolve(reason + "-clean"));
      publisher.publishOwnershipSeed();
      publisher.markShutdownPending(reason);
      publisher.completeShutdown(reason, true, "GRACEFUL");
      assertFalse(Files.exists(publisher.manifestPath()));
    }

    for (String reason : java.util.List.of("restart", "hang", "quit", "upgrade")) {
      RuntimeManifestPublisher publisher =
          new RuntimeManifestPublisher(tmp.resolve(reason + "-unclean"));
      publisher.publishOwnershipSeed();
      publisher.markShutdownPending(reason);
      assertThrows(IOException.class, () -> publisher.completeShutdown(reason, false, "GRACEFUL"));
      publisher.close();
      assertTrue(Files.exists(publisher.manifestPath()));
    }

    RuntimeManifestPublisher nonGraceful =
        new RuntimeManifestPublisher(tmp.resolve("non-graceful-index"));
    nonGraceful.publishOwnershipSeed();
    nonGraceful.markShutdownPending("quit");
    assertThrows(
        IOException.class, () -> nonGraceful.completeShutdown("quit", true, "FORCED"));
    nonGraceful.close();
    assertTrue(Files.exists(nonGraceful.manifestPath()));
  }

  @Test
  void listenerOnlyFiresForFuturePublishes(@TempDir Path tmp) throws IOException {
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp);
    publisher.publishHead(54321, null);

    AtomicReference<RuntimeManifest> seen = new AtomicReference<>();
    publisher.addListener(seen::set);

    assertNull(
        seen.get(),
        "addListener must NOT replay current state — current() is the explicit read path. "
            + "Replay-on-register conflates 'snapshot' and 'change event' semantics and causes "
            + "spurious SSE UPDATE frames at controller-init time (Phase 2 live-verify finding).");

    publisher.publishWorkerReady("/tmp/idx", "READY");
    assertNotNull(seen.get(), "listener must fire on subsequent publish");
    assertEquals("/tmp/idx", seen.get().worker().indexBasePath());
  }

  /**
   * Tempdoc 501 Phase 34 (F6): commit() reorder. The new ordering is
   * write → set → startLog → notify. A failing listener must NOT block
   * the start.log record of the event — postmortem readers should still
   * see the event happened, even if downstream consumers crashed.
   */
  @Test
  void listenerFailureStillRecordsStartLogEntry(@TempDir Path tmp) throws IOException {
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp);
    publisher.publishHead(54321, null);
    // Listener that throws — simulates a downstream consumer crash.
    publisher.addListener(
        m -> {
          throw new RuntimeException("simulated listener failure");
        });

    // Should not throw — notifyListeners catches per-listener exceptions.
    publisher.publishWorkerReady("/tmp/idx", "READY");

    // start.log must contain BOTH the publishHead and the publishWorkerReady
    // entries — Phase 34's reorder put appendStartLog before notifyListeners
    // so the failing listener cannot suppress the postmortem record.
    Path startLog =
        tmp.resolve("runtime").resolve("instances").resolve(publisher.instanceId()).resolve("start.log");
    assertTrue(Files.isRegularFile(startLog), "start.log must exist after publish events");
    String content = Files.readString(startLog);
    assertTrue(
        content.contains("publishHead apiPort=54321"),
        "start.log must record publishHead: " + content);
    assertTrue(
        content.contains("publishWorkerReady lifecycle=READY"),
        "start.log must record publishWorkerReady even though the listener threw: " + content);
  }

  /**
   * Tempdoc 501 Phase 25 + Phase 39 (F9): start.log format. Each line is
   * an ISO-8601 instant followed by a space and the event narrative.
   * Order: publisher-constructed (from constructor) →
   * publishHead → publishWorkerReady → publisher-close. Postmortem
   * readers depend on this format being stable.
   */
  @Test
  void startLogRecordsTimestampedEventNarrative(@TempDir Path tmp) throws IOException {
    try (RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp)) {
      publisher.publishHead(54321, null);
      publisher.publishWorkerReady("/tmp/idx", "READY");
      Path startLog =
          tmp.resolve("runtime").resolve("instances").resolve(publisher.instanceId()).resolve("start.log");
      assertTrue(Files.isRegularFile(startLog));
      // close() is invoked by try-with-resources; assertions on the file
      // happen AFTER close so the closing event is also recorded.
    }
    // Reconstruct path; publisher is closed but file persists.
    java.util.List<Path> instanceDirs;
    try (var s = Files.list(tmp.resolve("runtime").resolve("instances"))) {
      instanceDirs = s.toList();
    }
    assertEquals(1, instanceDirs.size());
    Path startLog = instanceDirs.get(0).resolve("start.log");
    String content = Files.readString(startLog);
    String[] lines = content.split("\n");
    // Expected: constructed, publishHead, publishWorkerReady, close → 4 lines.
    assertTrue(lines.length >= 4, "expected ≥4 start.log lines, got: " + content);
    // Every line must start with ISO-8601 instant (yyyy-mm-ddThh:mm:ss...Z).
    java.util.regex.Pattern iso8601 =
        java.util.regex.Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
    for (String line : lines) {
      if (line.isBlank()) continue;
      assertTrue(
          iso8601.matcher(line).find(),
          "line must start with ISO-8601 timestamp: '" + line + "'");
    }
    assertTrue(content.contains("publisher-constructed"), "missing constructed entry");
    assertTrue(content.contains("publishHead apiPort=54321"), "missing publishHead entry");
    assertTrue(content.contains("publishWorkerReady lifecycle=READY"), "missing publishWorkerReady entry");
    assertTrue(content.contains("publisher-close"), "missing close entry");
  }

  /**
   * Tempdoc 501 Phase 34 (F6): commit() ordering on write failure. Writing
   * is the first step; if it throws, current state must NOT advance and no
   * listener should fire. This guards the "publisher state matches what is
   * on disk" invariant.
   */
  @Test
  void writeFailureLeavesPublisherStateUnchanged(@TempDir Path tmp) throws IOException {
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp);
    RuntimeManifest initial = publisher.publishHead(54321, null);

    AtomicReference<RuntimeManifest> notified = new AtomicReference<>();
    publisher.addListener(notified::set);

    // Force write failure by deleting the runtime directory and replacing
    // it with a regular file — writeManifest's Files.createDirectories
    // succeeds idempotently when a directory exists, but the
    // resolveSibling+ATOMIC_MOVE step requires the target directory to
    // exist as a directory, which fails when the path is a regular file.
    Path runtimeDir = tmp.resolve("runtime");
    Files.walk(runtimeDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
      try {
        Files.deleteIfExists(p);
      } catch (IOException ignore) {
        // best-effort
      }
    });
    Files.writeString(runtimeDir, "blocker");

    assertThrows(
        IOException.class,
        () -> publisher.publishWorkerReady("/tmp/idx", "READY"),
        "write failure must propagate");

    assertEquals(
        initial.instanceId(),
        publisher.current().instanceId(),
        "publisher state must stay at the pre-failure manifest");
    assertNull(
        publisher.current().worker(),
        "worker must remain unset because the failed publishWorkerReady did not commit");
    assertNull(notified.get(), "listener must not fire on a failed write");
  }

  // ------------------------------------------------- tempdoc 842 §2.5 realized chat identity

  @Test
  void publishChatProjectsRealizedIdentityAndClearsItWhenTheEngineGoesDown(@TempDir Path tmp)
      throws IOException {
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp);
    publisher.publishHead(54321, null);
    assertNull(publisher.current().chat(), "no chat block before the first publish");

    RuntimeManifest up =
        publisher.publishChat(
            io.justsearch.app.api.inference.RealizedChatIdentity.of(
                "compact",
                tmp.resolve("models").resolve("compact").resolve("Qwen3.5-4B-Q4_K_M.gguf"),
                tmp.resolve("models").resolve("compact").resolve("mmproj-F16.gguf")));

    assertNotNull(up.chat());
    assertEquals("compact", up.chat().profileId());
    assertEquals(
        "Qwen3.5-4B-Q4_K_M.gguf",
        up.chat().modelFile(),
        "the manifest carries a bare file name, never the directory layout");
    assertEquals(Boolean.TRUE, up.chat().mmprojActive());
    assertEquals(54321, up.head().apiPort(), "publishing chat must not disturb the head block");

    // Engine goes down: the block must be CLEARED. A stale "compact, vision on" standing over a
    // dead engine is the exact declared-vs-realized lie this block exists to prevent.
    RuntimeManifest down = publisher.publishChat(null);
    assertNull(down.chat(), "a downed engine has no realized identity");
  }

  @Test
  void publishChatIsANoOpWhenTheIdentityIsUnchanged(@TempDir Path tmp) throws IOException {
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp);
    publisher.publishHead(54321, null);
    var identity =
        io.justsearch.app.api.inference.RealizedChatIdentity.of(
            "standard", tmp.resolve("Qwen_Qwen3.5-9B-Q4_K_M.gguf"), tmp.resolve("mmproj-F16.gguf"));

    RuntimeManifest first = publisher.publishChat(identity);
    AtomicReference<RuntimeManifest> seen = new AtomicReference<>();
    publisher.addListener(seen::set);
    RuntimeManifest second = publisher.publishChat(identity);

    assertSame(first, second, "an unchanged identity must not rewrite the manifest");
    assertNull(seen.get(), "an unchanged identity must not notify listeners");
  }

  @Test
  void publishChatReportsADroppedProjectorRatherThanOmittingIt(@TempDir Path tmp)
      throws IOException {
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp);
    publisher.publishHead(54321, null);

    RuntimeManifest m =
        publisher.publishChat(
            io.justsearch.app.api.inference.RealizedChatIdentity.of(
                null, tmp.resolve("bare-operator-model.gguf"), null));

    assertNotNull(m.chat(), "an engine IS up, so there is a realized identity to report");
    assertEquals(Boolean.FALSE, m.chat().mmprojActive(), "the dropped projector must be visible");
    assertNull(
        m.chat().profileId(),
        "a bare path carries no profile claim, and null must not be filled in with the default");
  }

  @Test
  void publishChatBeforePublishHeadThrows(@TempDir Path tmp) {
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp);
    assertThrows(
        IllegalStateException.class,
        () ->
            publisher.publishChat(
                io.justsearch.app.api.inference.RealizedChatIdentity.of(
                    "compact", tmp.resolve("m.gguf"), null)),
        "same ordering contract every other publish* method has");
  }
}
