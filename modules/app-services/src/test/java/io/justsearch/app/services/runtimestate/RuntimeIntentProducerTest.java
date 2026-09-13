/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.ModeTransitionOutcome;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.bootstrap.phases.InferenceWiring;
import io.justsearch.app.services.brainruntime.BrainRuntimeServiceImpl;
import io.justsearch.app.services.intent.EngineProvenance;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

class RuntimeIntentProducerTest {
  @TempDir Path directory;

  @Test
  void incompleteKeyReturnsAcceptedWithoutWaitingOrObserving() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory, false)) {
      var context = TestEngineContexts.internal();
      String key = OperationKeys.generate(Clock.systemUTC());
      var request = new OperationAttemptRunner.Request(key,
          OperationDescriptor.invocation(OperationKind.SETTINGS_APPLY, "core.set-chat-enabled",
              "{\"enabled\":true}", false), context,
          EngineProvenance.invocation(context, ExecutorTag.UI, Instant.now(), Optional.empty()));
      fixture.runner().accept(request);
      var runtime = mock(OnlineAiService.class);
      var reconciler = mock(RuntimeReconciler.class);
      var service = new BrainRuntimeServiceImpl(runtime, fixture.settings(), null, null, fixture.spec(), reconciler);
      var outcome = assertTimeoutPreemptively(Duration.ofSeconds(2),
          () -> service.switchInferenceMode("online", context, key));
      assertEquals(ModeTransitionOutcome.STATE_ACCEPTED, outcome.state());
      assertEquals(key, outcome.operationKey());
      assertNull(outcome.acceptedRevision());
      assertNull(outcome.mode());
      assertFalse(fixture.spec().load().chatEnabled());
      verifyNoInteractions(runtime, reconciler);
    }
  }

  @Test
  void completedRetryAnswersEvenWhenSettingsCannotBePreparedAgain() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory, false)) {
      var context = TestEngineContexts.internal();
      String key = OperationKeys.generate(Clock.systemUTC());
      var runtime = mock(OnlineAiService.class);
      when(runtime.getCurrentMode()).thenReturn("online");
      var reconciler = mock(RuntimeReconciler.class);
      var service = new BrainRuntimeServiceImpl(runtime, fixture.settings(), null, null, fixture.spec(), reconciler);
      assertEquals(ModeTransitionOutcome.STATE_CONVERGED,
          service.switchInferenceMode("online", context, key).state());
      Files.writeString(fixture.settings().settingsPath(), "{broken");
      clearInvocations(runtime, reconciler);
      var replay = service.switchInferenceMode("online", context, key);
      assertEquals(ModeTransitionOutcome.STATE_RECORDED, replay.state());
      assertEquals(1L, replay.acceptedRevision());
      assertNull(replay.mode());
      verifyNoInteractions(runtime, reconciler);
    }
  }

  @Test
  void failedAcceptanceCannotWriteOrObserve() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory, false)) {
      var runner = spy(fixture.runner());
      doThrow(new OperationStoreException(OperationStoreException.Code.STORAGE_FAILED, null))
          .when(runner).accept(any());
      var spec = new RuntimeSpecStore(fixture.settings(), runner);
      byte[] before = Files.readAllBytes(fixture.settings().settingsPath());
      var observation = mock(Runnable.class);
      assertThrows(OperationStoreException.class,
          () -> spec.writeIntent(true, TestEngineContexts.internal(), null, observation));
      assertArrayEquals(before, Files.readAllBytes(fixture.settings().settingsPath()));
      verifyNoInteractions(observation);
    }
  }

  @Test
  void bootSeedCannotOverwriteAChoiceMadeAfterItsNoOpDecision() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      var stale = fixture.settings().inspect();
      var reader = spy(fixture.settings());
      var firstRead = new java.util.concurrent.atomic.AtomicBoolean(true);
      doAnswer(call -> {
        if (firstRead.compareAndSet(true, false)) {
          fixture.spec().setChatEnabled(false);
          return stale;
        }
        return fixture.settings().inspect();
      }).when(reader).inspect();
      var spec = new RuntimeSpecStore(reader, fixture.runner());
      var refused = assertThrows(SettingsCommitOwner.Refused.class, spec::seedAutostartIfUnset);
      assertEquals("VERSION_CONFLICT", refused.response().errorCode().orElseThrow());
      assertFalse(fixture.spec().load().chatEnabled());
      assertEquals(1, fixture.settings().inspect().witness().acceptedRevision());
    }
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void typedSeedRefusalsLeaveBootstrapAlive() {
    String enabled = System.getProperty("justsearch.ai.autostart.enabled");
    String disabled = System.getProperty("justsearch.ai.autostart.disabled");
    try {
      System.setProperty("justsearch.ai.autostart.enabled", "true");
      System.setProperty("justsearch.ai.autostart.disabled", "false");
      var failure = OperationResult.failure("Recovery required", "SETTINGS_RECOVERY_REQUIRED", Map.of(), false);
      var spec = mock(RuntimeSpecStore.class);
      when(spec.seedAutostartIfUnset()).thenThrow(new OperationPreparationRefused(failure),
          new SettingsCommitOwner.Refused(failure));
      assertDoesNotThrow(() -> InferenceWiring.seedAutostartSpec(spec));
      assertDoesNotThrow(() -> InferenceWiring.seedAutostartSpec(spec));
      verify(spec, times(2)).seedAutostartIfUnset();
    } finally {
      if (enabled == null) System.clearProperty("justsearch.ai.autostart.enabled");
      else System.setProperty("justsearch.ai.autostart.enabled", enabled);
      if (disabled == null) System.clearProperty("justsearch.ai.autostart.disabled");
      else System.setProperty("justsearch.ai.autostart.disabled", disabled);
    }
  }

  @Test
  void acceptedRefusalStillReturnsItsServerIssuedKey() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      var context = TestEngineContexts.internal();
      var stale = fixture.settings().inspect();
      var reader = spy(fixture.settings());
      doAnswer(call -> {
        fixture.spec().setChatEnabled(false);
        return stale;
      }).when(reader).inspect();
      var reconciler = mock(RuntimeReconciler.class);
      var service = new BrainRuntimeServiceImpl(mock(OnlineAiService.class), reader, null, null,
          new RuntimeSpecStore(reader, fixture.runner()), reconciler);
      var failure = assertThrows(SettingsCommitOwner.Refused.class,
          () -> service.switchInferenceMode("online", context, null));
      assertEquals("VERSION_CONFLICT", failure.response().errorCode().orElseThrow());
      assertTrue(failure.response().structuredData().get("operationKey") instanceof String);
      String key = (String) failure.response().structuredData().get("operationKey");
      var request = new OperationAttemptRunner.Request(key,
          OperationDescriptor.invocation(OperationKind.SETTINGS_APPLY, "core.set-chat-enabled",
              "{\"enabled\":true}", false), context,
          EngineProvenance.invocation(context, ExecutorTag.UI, Instant.now(), Optional.empty()));
      var row = fixture.runner().lookup(request).orElseThrow().accepted();
      assertEquals(io.justsearch.app.api.operations.OperationState.FAILED, row.state());
      assertEquals("VERSION_CONFLICT", row.failureReason());
      verifyNoInteractions(reconciler);
    }
  }
}
