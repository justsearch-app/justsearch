/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.DefaultChildControl;
import io.justsearch.app.api.runtime.child.ExitClass;
import io.justsearch.app.api.runtime.child.ProcessTerminator;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * Spec B T21: llama-server runs through {@link io.justsearch.app.api.runtime.child.ChildControl}.
 * A stand-in server ({@link HealthyFakeLlamaServer}) is started and stopped by a real {@link
 * DefaultChildControl} with a recording ledger: each stop is recorded with its caller's reason
 * before the terminate, ends with the control's stop code as REQUESTED, and the crash monitor, which
 * stays armed through the stop, ignores it. An exit nobody asked for is still a crash.
 */
final class LlamaServerChildControlTest {
  private record Entry(LedgerRecordType type, Map<String, ?> fields) {}

  @TempDir Path tmp;
  private Path fakeDir;
  private final List<Entry> ledger = new CopyOnWriteArrayList<>();
  private final AtomicInteger crashes = new AtomicInteger();
  private DefaultChildControl control;
  private LlamaServerOps ops;

  @AfterEach
  void tearDown() throws Exception {
    try {
      if (ops != null) ops.shutdown();
      if (control != null) control.close();
    } finally {
      if (fakeDir != null) HealthyFakeLlamaServer.endAll(fakeDir);
    }
  }

  @Test
  void aRequestedStopIsRecordedWithItsReasonAndIsNotACrash() throws Exception {
    LlamaServerOps.StartResult started = startHealthy();
    String childId = only(LedgerRecordType.CHILD_START).get("childId").toString();

    ops.stopLlamaServer(InferenceLifecycleManager.chatStop(StopReason.DEACTIVATE));

    Map<String, ?> intent = only(LedgerRecordType.STOP_INTENT);
    Map<String, ?> exit = only(LedgerRecordType.CHILD_EXIT);
    assertEquals(childId, intent.get("childId"));
    assertEquals(StopReason.DEACTIVATE, intent.get("reason"));
    assertEquals("chat-controller", intent.get("requestedBy"));
    assertTrue(indexOf(LedgerRecordType.STOP_INTENT) < indexOf(LedgerRecordType.CHILD_EXIT),
        "the intent is recorded before the exit");
    assertEquals(ExitClass.REQUESTED, exit.get("exitClass"));
    assertEquals(java.util.Optional.of(StopReason.DEACTIVATE), exit.get("reason"));
    assertEquals(java.util.OptionalInt.of(ProcessTerminator.forCurrentOs().ourCode()),
        exit.get("code"), "the server ends with the control's stop code");
    Thread.sleep(1_000);
    assertEquals(0, crashes.get(), "a requested stop is not reported as a crash");
    assertTrue(ops.activeStartResult().isEmpty() || ops.activeStartResult().get() != started);
  }

  @Test
  void anExitNobodyAskedForIsStillACrash() throws Exception {
    startHealthy();

    HealthyFakeLlamaServer.crashLatest(fakeDir);

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (crashes.get() == 0 && System.nanoTime() < deadline) Thread.sleep(20);
    assertEquals(1, crashes.get(), "an unrequested exit reaches the crash handling");
    assertTrue(entries(LedgerRecordType.STOP_INTENT).isEmpty(), "nobody asked it to stop");
    assertEquals(ExitClass.UNEXPECTED, only(LedgerRecordType.CHILD_EXIT).get("exitClass"));
  }

  /** Spec B §2.8: every transition reason maps to a known stop reason; UNKNOWN is refused. */
  @Test
  void everyTransitionReasonStopsForAKnownReason() {
    Map<TransitionReason, StopReason> expected = new EnumMap<>(TransitionReason.class);
    expected.put(TransitionReason.USER_SWITCH, StopReason.DEACTIVATE);
    expected.put(TransitionReason.AUTO_START, StopReason.DEACTIVATE);
    expected.put(TransitionReason.RESIDENCY_RELOAD, StopReason.DEACTIVATE);
    expected.put(TransitionReason.RESIDENCY_UNLOAD, StopReason.RESIDENCY_PRESSURE);
    expected.put(TransitionReason.SELF_TEST, StopReason.SELF_TEST);
    expected.put(TransitionReason.VDU_ENTER, StopReason.VDU_ENTER);
    expected.put(TransitionReason.VDU_EXIT, StopReason.VDU_EXIT);
    expected.put(TransitionReason.CRASH_RECOVERY, StopReason.CRASH_RECOVERY);
    expected.put(TransitionReason.CONFIG_APPLY, StopReason.RECONFIGURE);
    expected.put(TransitionReason.EXTERNAL_DETACH, StopReason.RECONFIGURE);
    expected.put(TransitionReason.ADMIN_TRIGGERED, StopReason.RECONFIGURE);
    expected.put(TransitionReason.SHUTDOWN, StopReason.QUIT);
    for (TransitionReason reason : TransitionReason.values()) {
      if (reason == TransitionReason.UNKNOWN) {
        assertThrows(IllegalArgumentException.class,
            () -> InferenceLifecycleManager.stopReasonFor(reason));
      } else {
        assertEquals(expected.get(reason), InferenceLifecycleManager.stopReasonFor(reason),
            reason.name());
      }
    }
    assertEquals(TransitionReason.values().length - 1, expected.size(),
        "every known transition reason has an expected stop reason");
  }

  private LlamaServerOps.StartResult startHealthy() throws Exception {
    fakeDir = tmp.resolve("fake-llama");
    Path executable = HealthyFakeLlamaServer.install(fakeDir);
    Path model = Files.createFile(tmp.resolve("model.gguf"));
    ResolvedConfig resolved = TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.context.size", "4096",
        "justsearch.data.dir", tmp.resolve("data").toString()));
    InferenceConfig inference =
        new InferenceConfig(executable, model, null, freePort(), 4096, 0, false);
    control = DefaultChildControl.of((type, fields) -> {
      ledger.add(new Entry(type, Map.copyOf(withoutNulls(fields))));
      return true;
    }, ProcessTerminator.forCurrentOs(), ManagedChildRegistry.noop());
    ops = new LlamaServerOps(
        new InferenceExecutorRegistrations(new io.justsearch.core.execution.TestEngineExecutors()),
        HttpClient.newHttpClient(), new ObjectMapper(), null, () -> Mode.ONLINE,
        new NoopPropsObserver(), stillOwned -> crashes.incrementAndGet(), (ignored, guard) -> {},
        NoopInferenceTelemetryEvents.INSTANCE, control);
    LlamaServerOps.StartResult started = ops.startLlamaServer(new LlamaServerOps.StartRequest(
        new LlamaServerConfigContext(inference, resolved),
        LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS));
    ops.waitForServerHealth(started);
    return started;
  }

  private static Map<String, ?> withoutNulls(Map<String, ?> fields) {
    Map<String, Object> copy = new java.util.LinkedHashMap<>();
    fields.forEach((key, value) -> {
      if (value != null) copy.put(key, value);
    });
    return copy;
  }

  private List<Map<String, ?>> entries(LedgerRecordType type) {
    return ledger.stream().filter(entry -> entry.type() == type)
        .<Map<String, ?>>map(Entry::fields).toList();
  }

  private Map<String, ?> only(LedgerRecordType type) {
    List<Map<String, ?>> found = entries(type);
    assertEquals(1, found.size(), type + " records: " + ledger);
    return found.getFirst();
  }

  private int indexOf(LedgerRecordType type) {
    for (int index = 0; index < ledger.size(); index++) {
      if (ledger.get(index).type() == type) return index;
    }
    return -1;
  }

  private static int freePort() throws java.io.IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static final class NoopPropsObserver implements PropsObserver {
    @Override
    public void onModelIdObserved(String modelId, LlamaServerConfigContext context) {}

    @Override
    public void onContextTokensObserved(int contextTokens) {}

    @Override
    public String observedModelId() {
      return null;
    }

    @Override
    public Integer observedContextTokens() {
      return null;
    }
  }
}
