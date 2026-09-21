/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.app.api.operations.AppliedIndexGeneration;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerIngestService;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class EngineAppliedGenerationObservationTest {
  private static final AppliedIndexGeneration GENERATION = generation("generation-a", "{\"index\":1}");

  @Test
  void boundedClientReturnsStableObservationAndRefusesMissingOwner() {
    WorkerAppServices owner = ownerReturning(GENERATION);
    try (var stable = ClientFixture.open(() -> owner)) {
      assertSame(GENERATION, stable.client().captureAppliedGeneration(TestEngineContexts.FOREGROUND));
    }

    try (var missing = ClientFixture.open(() -> null)) {
      assertStatus(KnowledgeClientException.Status.UNAVAILABLE,
          () -> missing.client().captureAppliedGeneration(TestEngineContexts.FOREGROUND));
    }
  }

  @Test
  void boundedClientRefusesFreshOwnerAAfterAtoBtoARebound() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    WorkerIngestService ingestA = mock(WorkerIngestService.class);
    when(ingestA.captureAppliedGeneration(any())).thenAnswer(invocation -> {
      entered.countDown();
      assertTrue(release.await(5, TimeUnit.SECONDS));
      return GENERATION;
    });
    WorkerAppServices ownerA = ownerWith(ingestA);
    WorkerAppServices ownerB = ownerReturning(generation("generation-b", "{\"index\":2}"));
    WorkerAppServices freshOwnerA = ownerReturning(GENERATION);
    var published = new AtomicReference<>(ownerA);

    try (var fixture = ClientFixture.open(published::get);
        var callers = Executors.newSingleThreadExecutor()) {
      var result = callers.submit(
          () -> fixture.client().captureAppliedGeneration(TestEngineContexts.FOREGROUND));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      published.set(ownerB);
      published.set(freshOwnerA);
      release.countDown();
      assertFutureStatus(KnowledgeClientException.Status.ABORTED, result);
    } finally {
      release.countDown();
    }
  }

  @Test
  void independentlyComposedRootsProduceTheSameRevisionAndIncludeGenerationId() throws Exception {
    try (var first = RootFixture.open(GENERATION);
        var second = RootFixture.open(GENERATION);
        var otherGeneration = RootFixture.open(generation("generation-b", "{\"index\":1}"))) {
      String firstRevision = first.root().appliedConfigurationRevision(TestEngineContexts.FOREGROUND);
      assertEquals(firstRevision,
          second.root().appliedConfigurationRevision(TestEngineContexts.FOREGROUND));
      assertNotEquals(firstRevision,
          otherGeneration.root().appliedConfigurationRevision(TestEngineContexts.FOREGROUND));
    }
  }

  @Test
  void rootRefusesRegistryRevisionMovementDuringGenerationCapture() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    WorkerIngestService ingest = mock(WorkerIngestService.class);
    when(ingest.captureAppliedGeneration(any())).thenAnswer(invocation -> {
      entered.countDown();
      assertTrue(release.await(5, TimeUnit.SECONDS));
      return GENERATION;
    });
    try (var fixture = RootFixture.open(ownerWith(ingest));
        var callers = Executors.newSingleThreadExecutor()) {
      var result = callers.submit(
          () -> fixture.root().appliedConfigurationRevision(TestEngineContexts.FOREGROUND));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      fixture.root().indexComponent().setAppliedVersion("index-applied-v2");
      release.countDown();
      assertFutureStatus(KnowledgeClientException.Status.ABORTED, result);
    } finally {
      release.countDown();
    }
  }

  @Test
  void rootRefusesClientReplacementDuringGenerationCapture() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    WorkerIngestService ingest = mock(WorkerIngestService.class);
    when(ingest.captureAppliedGeneration(any())).thenAnswer(invocation -> {
      entered.countDown();
      assertTrue(release.await(5, TimeUnit.SECONDS));
      return GENERATION;
    });
    WorkerAppServices replacementOwner = ownerReturning(GENERATION);

    try (var fixture = RootFixture.open(ownerWith(ingest));
        var replacement = ClientFixture.open(() -> replacementOwner);
        var callers = Executors.newSingleThreadExecutor()) {
      EngineKnowledgeClient original = fixture.client();
      var result = callers.submit(
          () -> fixture.root().appliedConfigurationRevision(TestEngineContexts.FOREGROUND));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      try {
        setClient(fixture.root(), replacement.client());
        release.countDown();
        assertFutureStatus(KnowledgeClientException.Status.ABORTED, result);
      } finally {
        setClient(fixture.root(), original);
      }
    } finally {
      release.countDown();
    }
  }

  private static WorkerAppServices ownerReturning(AppliedIndexGeneration generation) {
    WorkerIngestService ingest = mock(WorkerIngestService.class);
    when(ingest.captureAppliedGeneration(any())).thenReturn(generation);
    return ownerWith(ingest);
  }

  private static WorkerAppServices ownerWith(WorkerIngestService ingest) {
    WorkerAppServices owner = mock(WorkerAppServices.class);
    when(owner.ingestService()).thenReturn(ingest);
    return owner;
  }

  private static AppliedIndexGeneration generation(String id, String inputs) {
    return new AppliedIndexGeneration(id, new IndexTargetSnapshot(sha256(inputs), inputs));
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static ComponentSpec spec(String name, boolean essential) {
    return new ComponentSpec(name, essential, Set.of(),
        ComponentSpec.ComposeCapability.CHOOSES_PER_APPLY, Duration.ZERO, 2);
  }

  private static void establishAppliedVersions(EngineRoot root) {
    root.indexComponent().setAppliedVersion("index-applied");
    encoderComponent(root).setAppliedVersion("encoders-applied");
    root.components().register(spec("api", true)).setAppliedVersion("api-applied");
    root.components().register(spec("generative", false)).setAppliedVersion("generative-applied");
  }

  private static ComponentHandle encoderComponent(EngineRoot root) {
    try {
      Field field = EngineRoot.class.getDeclaredField("encoderComponent");
      field.setAccessible(true);
      return (ComponentHandle) field.get(root);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("EngineRoot encoder component seam changed", failure);
    }
  }

  private static void setClient(EngineRoot root, EngineKnowledgeClient client) {
    try {
      Field field = EngineRoot.class.getDeclaredField("client");
      field.setAccessible(true);
      field.set(root, client);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("EngineRoot client publication seam changed", failure);
    }
  }

  private static void assertFutureStatus(
      KnowledgeClientException.Status expected, java.util.concurrent.Future<?> future) {
    ExecutionException wrapper = assertThrows(ExecutionException.class,
        () -> future.get(5, TimeUnit.SECONDS));
    KnowledgeClientException failure =
        org.junit.jupiter.api.Assertions.assertInstanceOf(KnowledgeClientException.class, wrapper.getCause());
    assertEquals(expected, failure.status());
  }

  private static void assertStatus(
      KnowledgeClientException.Status expected, org.junit.jupiter.api.function.Executable executable) {
    KnowledgeClientException failure = assertThrows(KnowledgeClientException.class, executable);
    assertEquals(expected, failure.status());
  }

  private record ClientFixture(
      DefaultEngineProcessResources resources, EngineKnowledgeClient client) implements AutoCloseable {
    static ClientFixture open(java.util.function.Supplier<WorkerAppServices> services) {
      var resources = new DefaultEngineProcessResources();
      var client = new EngineKnowledgeClient(
          resources.executors(), services, new ForegroundLoadGate(new ForegroundLoad()),
          10_000, 100, IpcTelemetry.noop(), () -> {}, resources.admission());
      return new ClientFixture(resources, client);
    }

    @Override
    public void close() {
      try {
        client.close();
      } finally {
        resources.close();
      }
    }
  }

  private record RootFixture(EngineRoot root, EngineKnowledgeClient client) implements AutoCloseable {
    static RootFixture open(AppliedIndexGeneration generation) throws Exception {
      return open(ownerReturning(generation));
    }

    static RootFixture open(WorkerAppServices owner) throws Exception {
      KnowledgeServer server = mock(KnowledgeServer.class);
      when(server.foregroundLoad()).thenReturn(new ForegroundLoad());
      when(server.appServices()).thenReturn(owner);
      when(server.awaitClosed(anyLong())).thenReturn(true);
      var root = new EngineRoot(mock(OperationStore.class), mock(OperationAttemptRunner.class),
          (ignored, executors, ingestion, indexComponent, encoderComponent) -> {
            EngineRootRecordedLifecycleTestSupport.bindOffline(server, ingestion);
            return server;
          }, 10_000, 100);
      establishAppliedVersions(root);
      EngineKnowledgeClient client = (EngineKnowledgeClient) root.start(
          new io.justsearch.core.scheduling.GpuSchedulingGauge(), IpcTelemetry.noop());
      return new RootFixture(root, client);
    }

    @Override
    public void close() {
      try {
        root.close();
      } finally {
        root.processResources().close();
      }
    }
  }
}
