/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static io.justsearch.app.services.chat.ManagedChatSeam.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.chat.ManagedChatSeam;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.TestEngineExecutors;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec A O2 at the real seam, seen from outside: an offline enrichment pass that finds embeddings
 * pending while chat is up no longer stops the chat server to hand the work off. The fake server's
 * launch records and the manager's mode show chat staying up through the whole pass.
 */
final class OfflinePassChatSeamTest {

  private static final long START_DELAY_MS = 300;

  @TempDir Path directory;

  private final TestEngineExecutors executors = new TestEngineExecutors();
  private ManagedChatSeam seam;
  private RuntimeIntentTestFixture intent;
  private RuntimeReconciler reconciler;
  private AutoCloseable following;

  @AfterEach
  void tearDown() throws Exception {
    try {
      if (following != null) following.close();
      if (reconciler != null) reconciler.close();
      if (intent != null) intent.close();
    } finally {
      try {
        if (seam != null) seam.close();
      } finally {
        executors.close(); // also ends the coordinator's procedure executor
      }
    }
  }

  // O2: pending embeddings after a pass never stop chat.
  @Test
  void aPassWithEmbeddingsPendingLeavesChatRunning() throws Exception {
    seam = new ManagedChatSeam(directory, START_DELAY_MS);
    InferenceLifecycleManager manager = seam.manager();
    intent = new RuntimeIntentTestFixture(directory.resolve("intent"), true);
    reconciler = new RuntimeReconciler(
        manager,
        manager::getCurrentMode,
        () -> manager.view().usingExternalLlamaServer(),
        null,
        intent.spec(),
        manager::setActivation,
        manager::componentRecoveryPending);
    Object holds = invokeIfPresent(manager, "chatHolds");
    if (holds != null) following = (AutoCloseable) invokeIfPresent(reconciler, "followHolds", holds);
    reconciler.start();
    reconciler.requestBootConvergence();
    await(manager::isOnline, "boot intent to bring chat online");
    assertTrue(reconciler.awaitQuiescent(30_000));
    assertEquals(List.of("model.gguf"), seam.models());

    EngineContext context = TestEngineContexts.durableInternal();
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.countPendingVdu(context)).thenReturn(0);
    when(client.countPendingEmbeddings(context)).thenReturn(12);
    OfflineCoordinator coordinator = newCoordinator(manager, context, client);

    AtomicBoolean stop = new AtomicBoolean();
    AtomicInteger offlineSamples = new AtomicInteger();
    Thread sampler = Thread.ofPlatform().daemon().start(() -> {
      while (!stop.get()) {
        if (!manager.isOnline()) offlineSamples.incrementAndGet();
        try {
          Thread.sleep(2);
        } catch (InterruptedException interrupted) {
          return;
        }
      }
    });
    try {
      coordinator.startOfflineProcessing(context, ignored -> { })
          .toCompletableFuture().get(60, TimeUnit.SECONDS);
      assertTrue(reconciler.awaitQuiescent(30_000));
      Thread.sleep(4 * START_DELAY_MS); // room for a relaunch to record itself, were one made
    } finally {
      stop.set(true);
      sampler.join(5_000);
    }

    assertEquals(0, offlineSamples.get(), "chat stays up through the pass");
    assertEquals(List.of("model.gguf"), seam.models(), "the chat server is never relaunched");
    assertTrue(manager.isOnline());
  }

  /**
   * The coordinator as the Engine composes it, chosen by reflection so this check runs against
   * either side of Spec A step 6: with the manager and reconciler where the coordinator still
   * drives chat, without them where it does not.
   */
  private OfflineCoordinator newCoordinator(InferenceLifecycleManager manager,
      EngineContext context, KnowledgeClient client) throws Exception {
    EngineAdmissionService admission = mock(EngineAdmissionService.class);
    EngineWorkHandle work = mock(EngineWorkHandle.class);
    when(work.context()).thenReturn(context);
    when(work.cancellationReason()).thenReturn(Optional.empty());
    when(work.onCancel(any())).thenReturn(mock(EngineWorkHandle.Registration.class));
    when(admission.attach(context)).thenReturn(work);
    VduBatchProcessor batch = mock(VduBatchProcessor.class);
    Supplier<KnowledgeClient> clients = () -> client;
    Constructor<?> constructor = OfflineCoordinator.class.getConstructors()[0];
    Class<?>[] types = constructor.getParameterTypes();
    Object[] args = new Object[types.length];
    for (int i = 0; i < types.length; i++) {
      Class<?> type = types[i];
      if (type == EngineExecutorRegistry.class) args[i] = executors;
      else if (type == EngineAdmissionService.class) args[i] = admission;
      else if (type == OnlineAiLifecycleControl.class) args[i] = manager;
      else if (type == RuntimeReconciler.class) args[i] = reconciler;
      else if (type == VduBatchProcessor.class) args[i] = batch;
      else if (type == Supplier.class) args[i] = clients;
      else if (type == VduCapabilityState.class) args[i] = new VduCapabilityState();
      else throw new AssertionError("unexpected coordinator dependency " + type);
    }
    return (OfflineCoordinator) constructor.newInstance(args);
  }

  private static Object invokeIfPresent(Object target, String name, Object... args)
      throws Exception {
    for (Method method : target.getClass().getMethods()) {
      if (method.getName().equals(name) && method.getParameterCount() == args.length) {
        return method.invoke(target, args);
      }
    }
    return null;
  }
}
