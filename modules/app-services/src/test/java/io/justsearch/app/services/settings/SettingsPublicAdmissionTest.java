/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SettingsPublicAdmissionTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path directory;

  @Test
  void closingBeforeAcceptedWorkAttachesKeepsTypedRefusal() {
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        directory.resolve("closing-settings.json"));
    OperationAttemptRunner attempts = mock(OperationAttemptRunner.class);
    EngineAdmissionService admission = mock(EngineAdmissionService.class);
    when(admission.attach(any())).thenThrow(new EngineAdmissionException(
        EngineAdmissionException.Reason.FROZEN, 1));
    when(admission.isClosing()).thenReturn(true);
    var service = new SettingsServiceImpl(store, attempts, () -> {}, admission);
    var context = TestEngineContexts.ui().withWorkId(java.util.UUID.randomUUID());
    var input = patch(store.inspect().witness(), "{\"ui\":{\"theme\":\"dark\"}}");
    var record = mock(io.justsearch.agent.api.registry.OperationRecordHandle.class);

    var refusal = assertThrows(OperationPreparationRefused.class,
        () -> service.applyAccepted(input, null, context, record));

    assertEquals("ENGINE_CLOSING", refusal.refusal().errorCode().orElseThrow());
    verifyNoInteractions(attempts);
    assertEquals(new SettingsWitness(0, null), store.inspect().witness());
  }

  @Test
  void committedResultGapRefusesFreshMutationButAllowsOriginalReplay() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      var runner = spy(fixture.runner());
      var committed = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      AtomicInteger starts = new AtomicInteger();
      doAnswer(call -> {
        var result = call.callRealMethod();
        if (starts.getAndIncrement() == 0) {
          committed.countDown();
          assertTrue(release.await(5, TimeUnit.SECONDS));
        }
        return result;
      }).when(runner).start(any(), any());
      var service = new SettingsServiceImpl(fixture.settings(), runner);
      var original = patch(fixture.settings().inspect().witness(), "{\"ui\":{\"mode\":\"advanced\"}}");
      var pool = Executors.newSingleThreadExecutor();
      try {
        var future = pool.submit(() -> service.applyPublic(original, "client:2", TestEngineContexts.ui()));
        assertTrue(committed.await(5, TimeUnit.SECONDS));
        var older = patch(fixture.settings().inspect().witness(), "{\"ui\":{\"mode\":\"simple\"}}");
        var refused = assertThrows(OperationPreparationRefused.class,
            () -> service.applyPublic(older, "client:1", TestEngineContexts.ui()));
        assertEquals("RECONFIGURE_IN_PROGRESS", refused.refusal().errorCode().orElseThrow());
        var replay = service.applyPublic(original, "client:2", TestEngineContexts.ui());
        assertEquals(OperationState.COMPLETE, replay.record().state());
        release.countDown();
        assertEquals(OperationState.COMPLETE, future.get(5, TimeUnit.SECONDS).record().state());
        assertEquals(OperationState.COMPLETE, service.applyPublic(older, "client:1", TestEngineContexts.ui()).record().state());
        assertEquals("advanced", fixture.settings().inspect().settings().getMode());
      } finally {
        release.countDown();
        pool.shutdownNow();
      }
    }
  }

  @Test
  void postcommitListenerFailureStillAdvancesModeOrderingAndNudgesAfterAdmissionRelease() throws Exception {
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, directory.resolve("settings.json"));
    var config = new io.justsearch.configuration.resolved.ConfigStore(
        io.justsearch.app.services.config.ConfigStoreRebuilder.prepare(store.load()));
    AtomicInteger notifications = new AtomicInteger();
    config.addListener(event -> {
      if (notifications.getAndIncrement() == 0) throw new IllegalStateException("postcommit listener failure");
    });
    try (var fixture = new RuntimeIntentTestFixture(directory, store, config)) {
      AtomicInteger nudges = new AtomicInteger();
      var service = new SettingsServiceImpl(store, fixture.runner(), nudges::incrementAndGet);
      var first = service.applyPublic(patch(store.inspect().witness(),
          "{\"ui\":{\"mode\":\"advanced\",\"chatEnabled\":true}}"), "client:2", TestEngineContexts.ui());
      assertEquals(OperationState.COMPLETE, first.record().state());
      assertEquals(1, nudges.get());
      var older = service.applyPublic(patch(store.inspect().witness(),
          "{\"ui\":{\"mode\":\"simple\",\"theme\":\"dark\"}}"), "client:1", TestEngineContexts.ui());
      assertEquals(OperationState.COMPLETE, older.record().state());
      assertEquals("advanced", store.inspect().settings().getMode());
      assertEquals("dark", store.inspect().settings().getTheme());
      assertEquals(1, nudges.get());
    }
  }

  @Test
  void internalWriterCanAdvanceTheWitnessWithoutBeingOverwrittenByPublicAdmission() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      var runner = spy(fixture.runner());
      doAnswer(call -> {
        fixture.spec().setChatEnabled(true);
        return call.callRealMethod();
      }).when(runner).accept(any());
      var service = new SettingsServiceImpl(fixture.settings(), runner);
      var result = service.applyPublic(patch(fixture.settings().inspect().witness(),
          "{\"ui\":{\"theme\":\"dark\"}}"), null, TestEngineContexts.ui());
      assertEquals(OperationState.FAILED, result.record().state());
      assertEquals("VERSION_CONFLICT", result.response().errorCode().orElseThrow());
      assertEquals("system", fixture.settings().inspect().settings().getTheme());
      assertTrue(fixture.settings().inspect().settings().getChatEnabled());
      assertEquals(1, fixture.settings().inspect().witness().acceptedRevision());
    }
  }

  @Test
  void acceptanceRaceReturnsTheOtherExecutionWithoutRepeatingBookkeeping() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      var runner = spy(fixture.runner());
      var input = patch(fixture.settings().inspect().witness(),
          "{\"ui\":{\"chatEnabled\":true,\"mode\":\"advanced\"}}");
      AtomicInteger nudges = new AtomicInteger();
      var service = new SettingsServiceImpl(fixture.settings(), runner, nudges::incrementAndGet);
      doAnswer(call -> {
        new SettingsServiceImpl(fixture.settings(), fixture.runner())
            .applyPublic(input, "client:2", TestEngineContexts.ui());
        return call.callRealMethod();
      }).when(runner).accept(any());
      var replay = service.applyPublic(input, "client:2", TestEngineContexts.ui());
      assertEquals(OperationState.COMPLETE, replay.record().state());
      assertEquals(1, fixture.settings().inspect().witness().acceptedRevision());
      assertEquals(0, nudges.get(), "a replay must not repeat the winner's observation");
      assertFalse(replay.response().structuredData().containsKey("ui"));
    }
  }

  private static SettingsV2 patch(SettingsWitness witness, String json) {
    var tree = (ObjectNode) JSON.readTree(json);
    tree.set("witness", JSON.valueToTree(witness));
    tree.put("operationKey", OperationKeys.generate(Clock.systemUTC()));
    return JSON.treeToValue(tree, SettingsV2.class);
  }
}
