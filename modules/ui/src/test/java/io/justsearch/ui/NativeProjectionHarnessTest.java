/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.core.harness.HarnessBarrierProtocol;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeProjectionHarnessTest {
  private static final String SOURCE = NativeProjectionHarness.SOURCE;
  private static final String SCENARIO = NativeProjectionHarness.SCENARIO;
  private static final String CUT_POINT = "migration-after-pointer-commit";
  private static final String RETAINED_TITLE = "native-retained-projection";
  private static final String LATE_TITLE = "native-late-projection";
  private static final String RETAINED_CONTENT = "retainedbodyq7x91";
  private static final String LATE_CONTENT = "latebodyk9z83";

  @TempDir Path data;

  @Test
  void unrelatedEnvironmentIsAnExactNoopWithoutReadingTheSource() {
    assertNull(NativeProjectionHarness.fromEnvironment(data,
        Map.of("JUSTSEARCH_REAL_RECOVERY_SCENARIO", "ordinary-startup")::get));
    assertNull(NativeProjectionHarness.fromEnvironment(data, Map.of("UNRELATED", "1")::get));
  }

  @Test
  void partialNativeScenarioSelectionRefusesBeforeSourceAccess() {
    var partial = new HashMap<String, String>();
    partial.put("JUSTSEARCH_REAL_RECOVERY_SCENARIO", SCENARIO);
    assertThrows(IllegalArgumentException.class,
        () -> NativeProjectionHarness.fromEnvironment(data, partial::get));

    var exact = selection();
    for (String key : List.of("JUSTSEARCH_SUPERVISOR_HARNESS",
        "JUSTSEARCH_MIGRATION_BARRIER_POINT", "JUSTSEARCH_MIGRATION_BARRIER_SELF_EXIT")) {
      var missing = new HashMap<>(exact);
      missing.remove(key);
      assertThrows(IllegalArgumentException.class,
          () -> NativeProjectionHarness.fromEnvironment(data, missing::get));
    }
  }

  @Test
  void strictFixtureInputRejectsEveryInvalidSourceShape() throws Exception {
    List<String> invalid = new ArrayList<>();
    invalid.add("");
    invalid.add("{");
    invalid.add(fixture(data, "final"));

    String valid = fixture(data, "initial");
    invalid.add(valid.replace("\"phase\":\"initial\"",
        "\"phase\":\"initial\",\"phase\":\"initial\""));
    invalid.add(valid.replace("\"phase\":\"initial\"",
        "\"phase\":\"initial\",\"unknown\":true"));
    invalid.add(fixtureWithRows(data, "initial",
        projection(data, "retained", 1), projection(data, "retained", 1)));
    invalid.add(fixtureWithRows(data, "initial",
        projection(data, "retained", 1), projection(data, "late", 1)));
    invalid.add(fixtureWithRows(data, "initial",
        projection(data, "retained", 1), projection(data, "late", 2).replace(SOURCE,
            "foreign-source")));
    invalid.add(fixtureWithRows(data, "initial",
        projection(data, "retained", 1), projection(data, "late", 2).replace(
            "\"path\":\"" + jsonEscaped(normalized(data.getParent().resolve(
                "native-prefix-root/deleted/late-projection.md"))) + "\"", "\"path\":7")));
    invalid.add(fixtureWithRows(data, "initial", projection(data, "retained", 1),
        projection(data, "late", 2, "wrongbodymismatch")));

    for (String source : invalid) {
      writeFixture(source);
      assertThrows(IllegalArgumentException.class,
          () -> NativeProjectionHarness.fromEnvironment(data, selection()::get));
    }
  }

  @Test
  void initialSnapshotEmitsOnlyRetainedRowAndInterruptionIsAnIOException() throws Exception {
    writeFixture(fixture(data, "initial"));
    NativeProjectionHarness harness = NativeProjectionHarness.fromEnvironment(data,
        selection()::get);
    assertFalse(harness.automaticRootProducersEnabled());

    var emitted = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    var rows = new ArrayList<AcceptedProjection>();
    var failure = new AtomicReference<Throwable>();
    var normalReturn = new AtomicBoolean();
    var executor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "native-projection-harness-test");
      thread.setDaemon(true);
      return thread;
    });
    try {
      var task = executor.submit(() -> {
        try {
          harness.enumerate(row -> {
            rows.add(row);
            emitted.countDown();
          });
          normalReturn.set(true);
        } catch (Throwable thrown) {
          failure.set(thrown);
        } finally {
          finished.countDown();
        }
      });
      assertTrue(emitted.await(2, TimeUnit.SECONDS), "initial source did not emit its row");
      assertEquals(1, rows.size());
      assertEquals("retained", rows.getFirst().documentId());
      assertTrue(rows.getFirst().fieldsJson().contains(
          "\"title\":\"" + RETAINED_TITLE + "\""));
      assertTrue(rows.getFirst().fieldsJson().contains(
          "\"content\":\"" + RETAINED_CONTENT + "\""));
      assertTrue(rows.getFirst().fieldsJson().contains(
          "\"collection\":\"keep-collection\""));
      assertTrue(rows.getFirst().fieldsJson().contains(jsonEscaped(normalized(
          data.getParent().resolve("retained-projection.md")))));
      assertFalse(finished.await(100, TimeUnit.MILLISECONDS),
          "normal initial enumeration must remain held before interruption");
      task.cancel(true);
      assertTrue(finished.await(2, TimeUnit.SECONDS), "interruption did not release the source");
      assertInstanceOf(IOException.class, failure.get());
      assertFalse(normalReturn.get(), "cancelled enumeration returned normally");
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void slowBootstrapReadinessTurnsTrueBeforeAnyCaptureCanBeAttempted() throws Exception {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    var ready = new AtomicBoolean();
    var observedNotReady = new CountDownLatch(1);
    when(bootstrap.isReady()).thenAnswer(invocation -> {
      boolean current = ready.get();
      if (!current) observedNotReady.countDown();
      return current;
    });
    var finished = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    var executor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "native-projection-readiness-test");
      thread.setDaemon(true);
      return thread;
    });
    try {
      executor.execute(() -> {
        try {
          NativeProjectionHarness.awaitVerifiedBootstrap(bootstrap);
        } catch (Throwable thrown) {
          failure.set(thrown);
        } finally {
          finished.countDown();
        }
      });
      assertTrue(observedNotReady.await(2, TimeUnit.SECONDS));
      assertFalse(finished.await(100, TimeUnit.MILLISECONDS),
          "a slow bootstrap must hold the driver before the first capture");
      verify(bootstrap, never()).captureClient();

      ready.set(true);
      assertTrue(finished.await(2, TimeUnit.SECONDS), "readiness transition was not observed");
      assertNull(failure.get());
      verify(bootstrap, never()).captureClient();
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void interruptedBootstrapReadinessWaitFailsBeforeAnyCapture() throws Exception {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    var observedNotReady = new CountDownLatch(1);
    when(bootstrap.isReady()).thenAnswer(invocation -> {
      observedNotReady.countDown();
      return false;
    });
    var finished = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    var executor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "native-projection-readiness-interrupt-test");
      thread.setDaemon(true);
      return thread;
    });
    try {
      var task = executor.submit(() -> {
        try {
          NativeProjectionHarness.awaitVerifiedBootstrap(bootstrap);
        } catch (Throwable thrown) {
          failure.set(thrown);
        } finally {
          finished.countDown();
        }
      });
      assertTrue(observedNotReady.await(2, TimeUnit.SECONDS));
      assertFalse(finished.await(100, TimeUnit.MILLISECONDS));
      verify(bootstrap, never()).captureClient();
      task.cancel(true);
      assertTrue(finished.await(2, TimeUnit.SECONDS),
          "interrupted readiness wait did not finish");
      assertInstanceOf(InterruptedException.class, failure.get());
      verify(bootstrap, never()).captureClient();
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void coherentSuccessorCutEnablesFinalEnumerationAndDoesNotDriveAgain() throws Exception {
    writeFixture(fixture(data, "final"));
    writePriorCut(data, ProcessHandle.current().pid() + 1, "g-source", "g-building");
    NativeProjectionHarness harness = NativeProjectionHarness.fromEnvironment(data,
        selection()::get);
    assertTrue(harness.automaticRootProducersEnabled());

    var rows = new ArrayList<AcceptedProjection>();
    harness.enumerate(rows::add);
    assertEquals(List.of("retained", "late"),
        rows.stream().map(AcceptedProjection::documentId).toList());
    assertTrue(rows.get(1).fieldsJson().contains(
        "\"title\":\"" + LATE_TITLE + "\""));
    assertTrue(rows.get(1).fieldsJson().contains(
        "\"content\":\"" + LATE_CONTENT + "\""));
    assertTrue(rows.get(1).fieldsJson().contains(
        "\"collection\":\"deleted-files\""));
    assertTrue(rows.get(1).fieldsJson().contains(jsonEscaped(normalized(data.getParent().resolve(
        "native-prefix-root/deleted/late-projection.md")))));
    harness.startAfterBootstrap(null, null);
    harness.startAfterBootstrap(null, null);
    rows.clear();
    harness.enumerate(rows::add);
    assertEquals(2, rows.size(), "a successor source remains a complete final set");
  }

  @Test
  void corruptOrMismatchedPriorCutsRefuseTheSuccessor() throws Exception {
    writeFixture(fixture(data, "final"));
    long priorPid = ProcessHandle.current().pid() + 1;

    writePriorCut(data, priorPid, "g-source", "g-building");
    Files.writeString(HarnessBarrierProtocol.reached(data, "migration-barrier"), "{");
    assertThrows(IllegalArgumentException.class,
        () -> NativeProjectionHarness.fromEnvironment(data, selection()::get));

    writePriorCut(data, priorPid, "g-source", "g-building");
    Files.writeString(HarnessBarrierProtocol.reached(data, "native-projection-actions"),
        "{\"scenario\":\"wrong-scenario\",\"pid\":" + priorPid
            + ",\"sourceGeneration\":\"g-source\",\"buildingGeneration\":\"g-building\"}");
    assertThrows(IllegalArgumentException.class,
        () -> NativeProjectionHarness.fromEnvironment(data, selection()::get));

    writePriorCut(data, priorPid, "g-source", "g-building");
    Files.writeString(HarnessBarrierProtocol.reached(data, "native-projection-actions"),
        "{\"scenario\":\"" + SCENARIO + "\",\"pid\":" + priorPid
            + ",\"sourceGeneration\":\"g-source\",\"buildingGeneration\":\"other-building\"}");
    assertThrows(IllegalArgumentException.class,
        () -> NativeProjectionHarness.fromEnvironment(data, selection()::get));
  }

  @Test
  void priorCutRejectsOldShapePidTypesEqualGenerationsAndInvalidLateReceipts() throws Exception {
    writeFixture(fixture(data, "final"));
    long priorPid = ProcessHandle.current().pid() + 1;

    writePrePointerCut(data, priorPid, "g-source", "g-building");
    assertThrows(IllegalArgumentException.class,
        () -> NativeProjectionHarness.fromEnvironment(data, selection()::get));

    writePriorCut(data, priorPid, "g-source", "g-building");
    Files.writeString(HarnessBarrierProtocol.reached(data, "migration-barrier"),
        "{\"point\":\"" + CUT_POINT + "\",\"pid\":\"" + priorPid
            + "\",\"sourceGeneration\":\"g-building\",\"buildingGeneration\":\"\"}");
    assertThrows(IllegalArgumentException.class,
        () -> NativeProjectionHarness.fromEnvironment(data, selection()::get));

    writePriorCut(data, priorPid, "g-source", "g-building");
    Files.writeString(HarnessBarrierProtocol.reached(data, "native-projection-actions"),
        actionMarker("\"" + priorPid + "\"", "g-source", "g-building",
            receipt(SOURCE, "late", 2, "g-source", "NRT")));
    assertThrows(IllegalArgumentException.class,
        () -> NativeProjectionHarness.fromEnvironment(data, selection()::get));

    writePriorCut(data, priorPid, "same-generation", "same-generation");
    assertThrows(IllegalArgumentException.class,
        () -> NativeProjectionHarness.fromEnvironment(data, selection()::get));

    List<String> invalidReceipts = List.of(
        receipt("foreign-source", "late", 2, "g-source", "NRT"),
        receipt(SOURCE, "late", 1, "g-source", "NRT"),
        receipt(SOURCE, "late", 2, "g-building", "NRT"),
        receipt(SOURCE, "late", 2, "g-source", "DURABLE"));
    for (String invalidReceipt : invalidReceipts) {
      writePriorCut(data, priorPid, "g-source", "g-building", invalidReceipt);
      assertThrows(IllegalArgumentException.class,
          () -> NativeProjectionHarness.fromEnvironment(data, selection()::get));
    }
  }

  private Map<String, String> selection() {
    return Map.of("JUSTSEARCH_REAL_RECOVERY_SCENARIO", SCENARIO,
        "JUSTSEARCH_SUPERVISOR_HARNESS", "1",
        "JUSTSEARCH_MIGRATION_BARRIER_POINT", CUT_POINT,
        "JUSTSEARCH_MIGRATION_BARRIER_SELF_EXIT", "1");
  }

  private String fixture(Path root, String phase) {
    return fixtureWithRows(root, phase,
        projection(root, "retained", 1), projection(root, "late", 2));
  }

  private String fixtureWithRows(Path root, String phase, String initial, String late) {
    String retained = projection(root, "retained", 1);
    String finalRows = "[" + retained + "," + late + "]";
    return "{\"version\":1,\"source_id\":\"" + SOURCE + "\",\"phase\":\"" + phase
        + "\",\"initial\":[" + initial + "],\"final\":" + finalRows + "}";
  }

  private String projection(Path root, String documentId, long revision) {
    return projection(root, documentId, revision,
        documentId.equals("retained") ? RETAINED_CONTENT : LATE_CONTENT);
  }

  private String projection(Path root, String documentId, long revision, String content) {
    Path path = documentId.equals("retained")
        ? root.getParent().resolve("retained-projection.md")
        : root.getParent().resolve("native-prefix-root/deleted/late-projection.md");
    String title = documentId.equals("retained") ? RETAINED_TITLE : LATE_TITLE;
    return new AcceptedProjection(SOURCE, documentId, revision, AcceptedProjection.Kind.UPSERT,
        "{\"title\":\"" + title + "\",\"content\":\"" + content
            + "\",\"path\":\"" + jsonEscaped(normalized(path)) + "\",\"collection\":\""
            + (documentId.equals("retained") ? "keep-collection" : "deleted-files")
            + "\"}").encode();
  }

  private void writeFixture(String contents) throws IOException {
    Path input = data.resolve("fixtures/native-projection-source.json");
    Files.createDirectories(input.getParent());
    Files.writeString(input, contents);
  }

  private static void writePriorCut(Path data, long pid, String source, String building)
      throws IOException {
    writePriorCut(data, pid, source, building, receipt(SOURCE, "late", 2, source, "NRT"));
  }

  private static void writePriorCut(Path data, long pid, String source, String building,
      String lateReceipt) throws IOException {
    Files.createDirectories(data.resolve("runtime"));
    Files.writeString(HarnessBarrierProtocol.reached(data, "migration-barrier"),
        "{\"point\":\"" + CUT_POINT + "\",\"pid\":" + pid
            + ",\"sourceGeneration\":\"" + building + "\",\"buildingGeneration\":\"\"}");
    Files.writeString(HarnessBarrierProtocol.reached(data, "native-projection-actions"),
        actionMarker(Long.toString(pid), source, building, lateReceipt));
  }

  private static void writePrePointerCut(Path data, long pid, String source, String building)
      throws IOException {
    Files.createDirectories(data.resolve("runtime"));
    Files.writeString(HarnessBarrierProtocol.reached(data, "migration-barrier"),
        "{\"point\":\"" + CUT_POINT + "\",\"pid\":" + pid
            + ",\"sourceGeneration\":\"" + source + "\",\"buildingGeneration\":\""
            + building + "\"}");
    Files.writeString(HarnessBarrierProtocol.reached(data, "native-projection-actions"),
        actionMarker(Long.toString(pid), source, building,
            receipt(SOURCE, "late", 2, source, "NRT")));
  }

  private static String actionMarker(String pid, String source, String building,
      String lateReceipt) {
    return "{\"scenario\":\"" + SCENARIO + "\",\"pid\":" + pid
        + ",\"sourceGeneration\":\"" + source + "\",\"buildingGeneration\":\""
        + building + "\",\"lateReceipt\":" + lateReceipt + "}";
  }

  private static String receipt(String source, String document, long revision, String generation,
      String visibility) {
    return "{\"sourceId\":\"" + source + "\",\"documentId\":\"" + document
        + "\",\"sourceRevision\":" + revision + ",\"generationId\":\"" + generation
        + "\",\"visibility\":\"" + visibility + "\"}";
  }

  private static String normalized(Path path) {
    String value = path.toAbsolutePath().normalize().toString();
    return System.getProperty("os.name").toLowerCase().contains("win")
        ? value.toLowerCase() : value;
  }

  private static String jsonEscaped(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
