/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.child.DefaultChildControl;
import io.justsearch.app.api.runtime.child.LedgerReconciler;
import io.justsearch.app.api.runtime.child.LedgerSink;
import io.justsearch.app.api.runtime.child.LinuxProcessIdentity;
import io.justsearch.app.api.runtime.child.ProcessTerminator;
import io.justsearch.app.api.runtime.child.VramProbe;
import io.justsearch.app.api.runtime.containment.EngineContainment;
import io.justsearch.app.api.runtime.ledger.ExitLedgerReader;
import io.justsearch.app.api.runtime.ledger.ExitLedgerWriter;
import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import io.justsearch.app.api.runtime.ledger.LedgerWriterId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

final class RuntimeManifestRecoveryTest {
  @Test
  void closeBeforePublicationPreservesPredecessor(@TempDir Path data) throws Exception {
    for (String handoff : new String[] {"pending", "incomplete", "ready"}) {
      Path directory = data.resolve(handoff);
      Path manifest = write(directory, "{\"schemaVersion\":2,\"pid\":424242,\"children\":[],"
          + "\"shutdownHandoff\":{\"state\":\"" + handoff + "\"}}");
      String evidence = Files.readString(manifest);
      new RuntimeManifestPublisher(directory).close();
      assertTrue(Files.exists(manifest), "unpublished boot keeps predecessor evidence");
      assertEquals(evidence, Files.readString(manifest), "boot never published ownership");
    }
  }

  @Test
  void onlyCompletedReadyHandoffClassifiesClean(@TempDir Path data) throws Exception {
    for (String handoff : new String[] {"ready", "pending", "incomplete"}) {
      Path manifest = write(data, "{\"schemaVersion\":2,\"pid\":424242,\"children\":[],"
          + "\"shutdownHandoff\":{\"state\":\"" + handoff + "\"}}");
      var verdict = RuntimeManifestPublisher.classifyPreviousShutdown(
          manifest, new ObjectMapper(), 999, pid -> OptionalLong.empty());
      assertEquals(!handoff.equals("ready"), verdict.unclean(), handoff);
    }
  }

  @Test
  void readyHandoffWithRemainingChildIsCrashEvidence(@TempDir Path data) throws Exception {
    Path manifest = write(data, "{\"schemaVersion\":2,\"pid\":424242,\"children\":[{}],"
        + "\"shutdownHandoff\":{\"state\":\"ready\"}}");
    assertTrue(RuntimeManifestPublisher.classifyPreviousShutdown(
        manifest, new ObjectMapper(), 999, pid -> OptionalLong.empty()).unclean());
  }

  @Test
  void ledgerOnlySurvivorSeedsOwnershipThroughSecondBootAndRotation(@TempDir Path data)
      throws Exception {
    Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java")
        .toString(), "-cp", Path.of(SleepingChild.class.getProtectionDomain().getCodeSource()
            .getLocation().toURI()).toString(),
        SleepingChild.class.getName()).start();
    try {
      assertEquals(1, child.getInputStream().read(), "child acknowledges startup");
      ManagedChild identity = ManagedChild.fromProcess(child, ManagedChild.Kind.EXTRACTION,
          null, null, null, null);
      Path ledger = LedgerFamily.directory(data);
      var writer = writer(ledger);
      assertTrue(writer.append(LedgerRecordType.CHILD_SPAWN,
          Map.of("childId", "unregistered", "kind", "EXTRACTION", "owner",
              Map.of("pid", 424242, "instanceId", "predecessor"),
              "launchDescription", "a".repeat(2500))));
      Map<String, Object> start = new LinkedHashMap<>();
      start.put("childId", "unregistered");
      start.put("pid", child.pid());
      start.put("processStartedAt", identity.startedAt());
      start.put("executable", identity.executable());
      start.put("processIdentity", LinuxProcessIdentity.read(child.pid()));
      start.put("creationFileTime", ProcessTerminator.forCurrentOs().creationFileTime(child.pid()));
      start.put("launchDescription", "b".repeat(2500));
      assertTrue(writer.append(LedgerRecordType.CHILD_START, start));
      var originals = ExitLedgerReader.read(ledger, LedgerFamily.CHILDREN).records().stream()
          .map(record -> record.line()).toList();

      // The predecessor died before registry.register(), so there is no manifest row.
      for (int boot = 0; boot < 3; boot++) {
        var registry = new MutableManagedChildRegistry();
        try (var publisher = new RuntimeManifestPublisher(data, registry)) {
          assertEquals(1, registry.snapshot().size(), "ledger-only survivor at boot " + boot);
          assertEquals(child.pid(), registry.snapshot().getFirst().pid());
          assertEquals(identity.processIdentity(), registry.snapshot().getFirst().processIdentity());
          var nextWriter = writer(ledger);
          var verdict = new LedgerReconciler(ledger, LedgerSink.of(nextWriter),
              LedgerReconciler.osIdentity(ProcessTerminator.forCurrentOs())).reconcile();
          assertEquals(1, verdict.survived(), "survival remains open at boot " + boot);
          // Exercise the real session rotation. Afterwards retain only the new generation,
          // modelling the original generation falling outside the retention window.
          Files.writeString(LedgerFamily.CHILDREN.current(ledger), "\n".repeat(1024 * 1024),
              StandardOpenOption.APPEND);
          nextWriter.startSession();
          Path archive = ledger.resolve("children.v1.1.jsonl");
          var lines = Files.readAllLines(LedgerFamily.CHILDREN.current(ledger));
          var carried = lines.stream().map(new ObjectMapper()::readTree).toList();
          assertTrue(carried.containsAll(originals), "original receipts carry unchanged");
          for (String line : lines) {
            assertTrue((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 4096,
                "each carried receipt still fits the writer's record limit");
          }
          Files.delete(archive);
          publisher.publishOwnershipSeed();
        }
        // Test each boot's ledger recovery independently of the manifest projection.
        Files.delete(data.resolve("runtime/manifest.json"));
        assertTrue(child.isAlive(), "ledger reconciliation never kills");
      }
      var registry = new MutableManagedChildRegistry();
      try (var publisher = new RuntimeManifestPublisher(data, registry);
          var control = DefaultChildControl.openForEngine(data, publisher.instanceId(), registry,
              VramProbe.none(), EngineContainment.current())) {
        publisher.publishOwnershipSeed();
        ManagedChildReconciler.withRecordedFallback(registry,
            ManagedChildReconciler.ContainedPredecessors.NONE, control, data).reconcile();
        assertTrue(child.waitFor(Duration.ofSeconds(10)));
        assertTrue(registry.snapshot().isEmpty());
        assertTrue(ExitLedgerReader.read(ledger, LedgerFamily.CHILDREN).records().stream()
            .anyMatch(record -> record.type() == LedgerRecordType.CHILD_EXIT
                && record.text("childId").filter("unregistered"::equals).isPresent()),
            "cleanup closes the original identity after its start generation is evicted");
      }
    } finally {
      child.destroyForcibly();
      assertTrue(child.waitFor(Duration.ofSeconds(10)));
    }
  }

  private static ExitLedgerWriter writer(Path ledger) {
    return ExitLedgerWriter.rotating(ledger, LedgerFamily.CHILDREN,
        LedgerWriterId.currentProcess(LedgerWriterId.Role.ENGINE, Instant.now()), Clock.systemUTC());
  }

  public static final class SleepingChild {
    public static void main(String[] args) throws Exception {
      System.out.write(1);
      System.out.flush();
      System.in.read();
    }
  }

  @Test
  void invalidEvidenceIsQuarantinedAndKept(@TempDir Path data) throws Exception {
    String[][] cases = {
        {"{", "MALFORMED"}, {"null", "MALFORMED"},
        {"{\"schemaVersion\":\"2\"}", "MALFORMED"},
        {"{\"schemaVersion\":-1}", "MALFORMED"},
        {"{\"schemaVersion\":2,\"children\":[{}]}", "INVALID_CHILDREN"},
        {"{\"schemaVersion\":2,\"children\":[null]}", "INVALID_CHILDREN"},
        {"{\"schemaVersion\":2,\"children\":{}}", "INVALID_CHILDREN"},
        {"{\"schemaVersion\":3}", "FUTURE_SCHEMA"}
    };
    for (String[] fixture : cases) {
      Path manifest = write(data, fixture[0]);
      try (var publisher = assertDoesNotThrow(() -> new RuntimeManifestPublisher(data))) {
        assertTrue(publisher.detectedUncleanPreviousShutdown(), fixture[1]);
        assertFalse(Files.exists(manifest));
        assertEquals(fixture[0], Files.readString(manifest.resolveSibling("manifest.quarantine.json")));
      }
    }
  }

  @Test
  void terminalGracefulQuitStillDeletesWhileRestartRetains(@TempDir Path data) throws Exception {
    for (String reason : new String[] {"quit", "upgrade", "restart"}) {
      try (var publisher = new RuntimeManifestPublisher(data.resolve(reason))) {
        publisher.publishOwnershipSeed();
        publisher.markShutdownPending(reason);
        publisher.completeShutdown(reason, true, "GRACEFUL");
        if (reason.equals("restart")) assertTrue(Files.exists(publisher.manifestPath()));
        else assertFalse(Files.exists(publisher.manifestPath()));
      }
    }
  }

  private static Path write(Path data, String evidence) throws Exception {
    Path runtime = Files.createDirectories(data.resolve("runtime"));
    Path manifest = runtime.resolve("manifest.json");
    Files.writeString(manifest, evidence);
    return manifest;
  }
}
