/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.NativeQuiescence;
import io.justsearch.app.api.chat.DeviceLostRecovery;
import io.justsearch.app.api.chat.FailedCode;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.runtime.ledger.*;
import io.justsearch.app.engine.EngineRoot;
import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.bootstrap.BootstrapInferenceFactory;
import io.justsearch.app.services.chat.ChatServerControllerImpl;
import io.justsearch.ort.DeviceHealth;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real EngineRoot -> inference factory -> chat -> Headless recorder, without starting a server. */
@Timeout(45)
final class HeadlessAppEngineDeviceLossChainTest {
  @Test void aRefusedCommitThroughEngineRootKeepsChatRetryable(@TempDir Path dir) throws Exception {
    try (var f = new Fixture(dir, true)) {
      DeviceHealth.forDevice(91).reportLost(719);
      assertTrue(f.completed.await(10, TimeUnit.SECONDS), "the actual restart dispatcher must finish");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (f.chat.desiredInputs().terminal() == null
          && f.manager.chatActuator().failureLatch() == null && System.nanoTime() < deadline) Thread.sleep(10);
      assertNull(f.chat.desiredInputs().terminal(), "EngineRoot must deliver refusal before chat becomes terminal DEVICE_LOST");
      assertEquals(FailedCode.DEVICE_LOST_LIMIT, f.manager.chatActuator().failureLatch(), "chat stays latched and retryable");
      assertTrue(ExitLedgerHealth.current(LedgerFamily.directory(dir)).isPresent(), "the ledger refusal reason remains visible");
      assertEquals(-1, f.halt.get()); assertEquals(-1, f.exit.get(), "the Engine keeps running");
      assertEquals(0, f.handoffs.get()); assertEquals(0, f.closed.get());
      f.restoreLedger();
      assertEquals(List.of(), DeviceLostRestartHistory.read(LedgerFamily.directory(dir)), "a refused commit spends no budget");
    }
  }

  @Test void aCommittedRestartThroughEngineRootCommitsThenEndsChatThenHandsOff(@TempDir Path dir) throws Exception {
    try (var f = new Fixture(dir, false)) {
      DeviceHealth.forDevice(91).reportLost(719);
      assertTrue(f.completed.await(10, TimeUnit.SECONDS));
      assertTrue(f.committedWhileChatOperable.get(), "the durable DEVICE_LOST commit must precede terminal chat shutdown");
      assertEquals(List.of("commit", "chat", "handoff"), f.order);
      assertEquals(StopReason.DEVICE_LOST, f.chat.desiredInputs().terminal());
      assertEquals(1, f.handoffs.get()); assertEquals(1, f.closed.get());
      assertEquals(4, f.exit.get()); assertEquals(-1, f.halt.get());
      assertEquals(1, DeviceLostRestartHistory.read(LedgerFamily.directory(dir)).size());
    }
  }

  private static final class Fixture implements AutoCloseable {
    final Path dir, ledger, saved;
    final EngineRoot root;
    final InferenceLifecycleManager manager;
    final ChatServerControllerImpl chat;
    final CountDownLatch completed = new CountDownLatch(1);
    final AtomicInteger halt = new AtomicInteger(-1), exit = new AtomicInteger(-1), handoffs = new AtomicInteger(), closed = new AtomicInteger();
    final AtomicBoolean committedWhileChatOperable = new AtomicBoolean();
    final List<String> order = new CopyOnWriteArrayList<>();
    final DeviceIncarnation incarnation = new DeviceIncarnation();

    Fixture(Path dir, boolean failCommit) throws Exception {
      this.dir = dir; ledger = LedgerFamily.ENGINE.current(LedgerFamily.directory(dir));
      saved = ledger.resolveSibling("saved-engine.jsonl");
      root = testRoot();
      var intent = HeadlessApp.engineSelfIntent(dir, "root-chain");
      var controller = new AtomicReference<ChatServerControllerImpl>();
      var sequence = new EngineShutdownSequence(dir, List.of(new EngineShutdownSequence.Step("owner", reason -> {
        closed.incrementAndGet(); return "GRACEFUL";
      })), exit::set, exit::set, () -> NativeQuiescence.QUIESCED, result -> {}, intent);
      var restart = HeadlessApp.deviceLostRestartAction(CompletableFuture.completedFuture(sequence), reason -> {
        if (controller.get().desiredInputs().terminal() == StopReason.DEVICE_LOST) order.add("handoff");
        handoffs.incrementAndGet(); return null;
      }, halt::set, action -> { try { action.run(); } finally { completed.countDown(); } });
      var recovery = HeadlessApp.deviceLostRecovery(dir, intent, restart);
      // Inject storage failure immediately after the real reservation; forward both request forms.
      root.bindDeviceLostRecovery(new DeviceLostRecovery() {
        public List<Instant> priorRestarts() throws IOException { return recovery.priorRestarts(); }
        public void reserveRestart() throws IOException {
          recovery.reserveRestart();
          if (failCommit) { Files.move(ledger, saved); Files.createDirectory(ledger); }
        }
        public void requestRestart() { requestRestart(() -> {}, failure -> {}); }
        public void requestRestart(Runnable accepted, java.util.function.Consumer<RuntimeException> refused) {
          recovery.requestRestart(() -> {
            try {
              committedWhileChatOperable.set(!DeviceLostRestartHistory.read(LedgerFamily.directory(dir)).isEmpty()
                  && controller.get().desiredInputs().terminal() == null);
            } catch (IOException unavailable) { throw new IllegalStateException(unavailable); }
            order.add("commit"); accepted.run();
            if (controller.get().desiredInputs().terminal() == StopReason.DEVICE_LOST) order.add("chat");
          }, refused);
        }
      });
      manager = BootstrapInferenceFactory.createInferenceManager(root.executors(), true, inferenceConfig(dir),
          dir.toString(), null, org.slf4j.LoggerFactory.getLogger(HeadlessAppEngineDeviceLossChainTest.class));
      assertNotNull(manager);
      var field = InferenceLifecycleManager.class.getDeclaredField("actuationRouter"); field.setAccessible(true);
      chat = (ChatServerControllerImpl) field.get(manager); controller.set(chat);
    }
    void restoreLedger() throws IOException {
      if (Files.exists(saved)) { Files.delete(ledger); Files.move(saved, ledger); }
    }
    public void close() throws Exception {
      try { restoreLedger(); manager.close(); }
      finally { try { root.close(); root.processResources().close(); } finally { incarnation.close(); } }
    }
  }

  private static EngineRoot testRoot() throws Exception {
    Class<?> factory = Class.forName("io.justsearch.app.engine.EngineRoot$ServerFactory");
    Object index = Proxy.newProxyInstance(factory.getClassLoader(), new Class<?>[] {factory}, (proxy, method, args) -> {
      if (method.getName().equals("captureConfiguration")) return null;
      throw new AssertionError("the index half is never started by this check");
    });
    var constructor = EngineRoot.class.getDeclaredConstructor(OperationStore.class, OperationAttemptRunner.class,
        factory, long.class, int.class); constructor.setAccessible(true);
    return constructor.newInstance(mock(OperationStore.class), mock(OperationAttemptRunner.class), index, 30_000L, 5_000);
  }

  private static io.justsearch.configuration.resolved.ResolvedConfig inferenceConfig(Path dir) throws IOException {
    var config = new io.justsearch.configuration.resolved.ResolvedConfigBuilder();
    config.putDefault("justsearch.home", dir.toString());
    if (io.justsearch.configuration.PlatformPaths.isWindows()) {
      config.putDefault("justsearch.server.exe", dir.resolve("missing-server.exe").toString());
    }
    config.put("justsearch.llm.model_path", 400, "env_var", "test", dir.resolve("missing-model.gguf").toString());
    config.putDefault("justsearch.context.size", "4096"); config.putDefault("justsearch.gpu.layers", "0");
    config.putDefault("justsearch.ai.disabled", "false"); config.putDefault("justsearch.llm.enabled", "true");
    return config.build();
  }

  /** Simulate a fresh Engine incarnation, restoring other tests' device state afterwards. */
  private static final class DeviceIncarnation implements AutoCloseable {
    final Map<Integer, DeviceHealth> devices;
    final Map<Integer, DeviceHealth> previous;
    final Object lock;
    @SuppressWarnings("unchecked")
    DeviceIncarnation() throws Exception {
      var state = DeviceHealth.class.getDeclaredField("DEVICES"); state.setAccessible(true);
      devices = (Map<Integer, DeviceHealth>) state.get(null);
      var monitor = DeviceHealth.class.getDeclaredField("LOCK"); monitor.setAccessible(true); lock = monitor.get(null);
      synchronized (lock) { previous = Map.copyOf(devices); devices.clear(); }
    }
    public void close() { synchronized (lock) { devices.clear(); devices.putAll(previous); } }
  }
}
