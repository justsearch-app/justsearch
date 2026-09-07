package io.justsearch.ui.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.RuntimeManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
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

    // Lane F item A11 + the review's grpcPort decision: the only production caller
    // (RuntimeManifestListenerWiring) passes null, because the index half is composed in this JVM
    // and there is no port. Pinning 12345 here pinned the publisher's ability to carry a number
    // nothing supplies — which would keep reading as "the manifest reports a worker port" long
    // after it stopped being able to.
    RuntimeManifest updated =
        publisher.publishWorkerReady(null, tmp.resolve("index").toString(), "LIFECYCLE_STATE_READY");

    assertEquals(54321, updated.head().apiPort());
    assertEquals("LIFECYCLE_STATE_READY", updated.lifecycle());
    assertNotNull(updated.worker());
    assertEquals("ready", updated.worker().state());
    assertNull(
        updated.worker().grpcPort(),
        "there is no worker port to report; NON_NULL keeps the field out of the JSON entirely");
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
    assertNull(updated.worker().grpcPort(), "no grpcPort when worker failed");
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
        () -> publisher.publishWorkerReady(12345, "/tmp/index", "READY"));
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
    publisher.publishWorkerReady(12345, "/tmp/index", "READY");

    String content = Files.readString(publisher.manifestPath());
    JsonNode root = new ObjectMapper().readTree(content);

    assertEquals(1, root.get("schemaVersion").asInt());
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
    assertEquals(12345, root.get("worker").get("grpcPort").asInt());
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

    publisher.publishWorkerReady(99, "/tmp/idx", "READY");
    assertNotNull(seen.get(), "listener must fire on subsequent publish");
    assertEquals(99, seen.get().worker().grpcPort());
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
    publisher.publishWorkerReady(7777, "/tmp/idx", "READY");

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
        content.contains("publishWorkerReady grpcPort=7777"),
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
      publisher.publishWorkerReady(9000, "/tmp/idx", "READY");
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
    assertTrue(content.contains("publishWorkerReady grpcPort=9000"), "missing publishWorkerReady entry");
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
        () -> publisher.publishWorkerReady(7777, "/tmp/idx", "READY"),
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
