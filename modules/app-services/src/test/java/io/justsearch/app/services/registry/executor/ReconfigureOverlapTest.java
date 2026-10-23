/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.services.registry.operations.handlers.ReconfigureHandler;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** S36.9/N36.2: a settings admission conflict stays a conflict through keyed replay. */
final class ReconfigureOverlapTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path directory;

  @Test
  void overlappingAcceptedSaveAndItsRetryKeepTypedConflictAndUnrelatedPreferences() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      var runner = spy(fixture.runner());
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      doAnswer(call -> {
        entered.countDown();
        assertTrue(release.await(5, TimeUnit.SECONDS));
        return call.callRealMethod();
      }).when(runner).applySettings(any(), any(), any(), any(), any());
      EngineAdmissionService admission = mock(EngineAdmissionService.class);
      when(admission.attach(any())).thenAnswer(call -> {
        var work = mock(EngineWorkHandle.class);
        when(work.context()).thenReturn(call.getArgument(0));
        when(work.retain()).thenReturn(work);
        return work;
      });
      var service = new SettingsServiceImpl(fixture.settings(), runner, () -> {}, admission);
      var operation = new CoreOperationCatalog().findById(new OperationRef("core.reconfigure")).orElseThrow();
      var handlers = new HandlerRegistry();
      handlers.register(operation.id(), new ReconfigureHandler(() -> service));
      var context = TestEngineContexts.internal().withWorkId(java.util.UUID.randomUUID());
      var provenance = EngineProvenance.invocation(context, ExecutorTag.UI, Instant.now(), Optional.empty());
      var dispatch = new RuntimeIntentTestFixture.DispatchHarness(
          new OperationExecutorImpl(runner, admission, handlers), operation, context, provenance);
      var first = patch(fixture, "{\"ui\":{\"vimMode\":true}}");
      var second = patch(fixture, "{\"ui\":{\"theme\":\"dark\"}}");
      try (var pool = Executors.newSingleThreadExecutor()) {
        var saving = pool.submit(() -> dispatch.dispatch(arguments(first), first.operationKey()));
        try {
          assertTrue(entered.await(5, TimeUnit.SECONDS));
          var refused = assertDoesNotThrow(() -> dispatch.dispatch(arguments(second), second.operationKey()),
              "An accepted overlap must return a conflict instead of escaping as an exception");
          assertEquals("RECONFIGURE_IN_PROGRESS", refused.errorCode().orElseThrow());
          assertFalse(refused.retryable().orElse(false), "An accepted terminal conflict needs a new reviewed attempt");
          var replay = dispatch.dispatch(arguments(second), second.operationKey());
          assertEquals("RECONFIGURE_IN_PROGRESS", replay.errorCode().orElseThrow(),
              "The same operation key must retain its conflict, never UNCAUGHT_EXCEPTION");
        } finally { release.countDown(); }
        assertTrue(saving.get(5, TimeUnit.SECONDS).success());
      }
      assertTrue(fixture.settings().inspect().settings().isVimMode());
      assertEquals("system", fixture.settings().inspect().settings().getTheme());
      var fresh = patch(fixture, "{\"ui\":{\"theme\":\"dark\"}}");
      assertTrue(dispatch.dispatch(arguments(fresh), fresh.operationKey()).success());
      assertEquals("dark", fixture.settings().inspect().settings().getTheme());
      assertTrue(fixture.settings().inspect().settings().isVimMode());
      assertEquals(2, fixture.settings().inspect().witness().acceptedRevision());
    }
  }

  private static SettingsV2 patch(RuntimeIntentTestFixture fixture, String body) {
    ObjectNode tree = (ObjectNode) JSON.readTree(body);
    tree.set("witness", JSON.valueToTree(fixture.settings().inspect().witness()));
    tree.put("operationKey", OperationKeys.generate(Clock.systemUTC()));
    return JSON.treeToValue(tree, SettingsV2.class);
  }

  private static String arguments(SettingsV2 settings) {
    return JSON.writeValueAsString(new ReconfigureHandler.Envelope(settings, null, false));
  }
}
