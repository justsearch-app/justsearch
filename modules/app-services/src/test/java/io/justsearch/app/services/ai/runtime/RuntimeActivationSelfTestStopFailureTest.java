/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.AiRuntimeActivationStatus;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.chat.SelfTestSession;
import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.ChildHandle;
import io.justsearch.app.api.runtime.child.ChildKind;
import io.justsearch.app.api.runtime.child.ChildSpec;
import io.justsearch.app.api.runtime.child.ChildSurvivedException;
import io.justsearch.app.api.runtime.child.ChildView;
import io.justsearch.app.api.runtime.child.ExitOutcome;
import io.justsearch.app.api.runtime.child.StopRequest;
import io.justsearch.app.api.settings.SettingsCandidateContext;
import io.justsearch.app.inference.chat.ChatHolds;
import io.justsearch.app.inference.chat.ChildSelfTestLauncher;
import io.justsearch.app.inference.chat.SelfTestRun;
import io.justsearch.app.inference.chat.SelfTestSpec;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.settings.SettingsComponentComposer;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec B step 6 (S19): the activation self-test returns only after its candidate exited. A candidate
 * that passes its exercise but survives its forced stop is not a passed self-test, and nothing is
 * activated while it may still hold the GPU. The candidate's process is the child control's (a fake
 * whose stop fails with {@link ChildSurvivedException}); its endpoints are a loopback server.
 */
final class RuntimeActivationSelfTestStopFailureTest {
  private String nativeFixtureOs;

  @BeforeEach
  void selectWindowsNativeFixture() {
    nativeFixtureOs = System.getProperty("os.name");
    System.setProperty("os.name", "Windows 11");
  }

  @AfterEach
  void restoreNativeFixtureOs() {
    if (nativeFixtureOs == null) System.clearProperty("os.name");
    else System.setProperty("os.name", nativeFixtureOs);
  }


  private static final long MIB = 1024L * 1024;

  private final TestEngineExecutors processExecutors = new TestEngineExecutors();
  private final Map<String, String> prevProps = new HashMap<>();
  private final List<RuntimeIntentTestFixture> intentFixtures = new ArrayList<>();
  private ConfigStore prevStore;

  @TempDir Path tmp;

  @AfterEach
  void restore() throws Exception {
    for (var fixture : intentFixtures) fixture.close();
    for (var e : prevProps.entrySet()) {
      if (e.getValue() == null) {
        System.clearProperty(e.getKey());
      } else {
        System.setProperty(e.getKey(), e.getValue());
      }
    }
    TestResolvedConfigHelper.restoreGlobal(prevStore);
    processExecutors.close();
  }

  @Test
  void selfTestStopFailurePreventsActivation() throws Exception {
    setProp("justsearch.home", tmp.toAbsolutePath().toString());
    setProp("justsearch.data.dir", tmp.toAbsolutePath().toString());
    setProp("justsearch.models.dir", tmp.resolve("models").toAbsolutePath().toString());
    clearProp("justsearch.chat.profile");
    clearProp("justsearch.server.exe");
    clearProp("justsearch.server.exe.source");
    prevStore = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeFromEnvironment();
    Path exeDir = tmp.resolve("native-bin").resolve("llama-server").resolve("variants").resolve("cuda12");
    Files.createDirectories(exeDir);
    Files.writeString(exeDir.resolve("llama-server.exe"), "not-a-real-exe", StandardCharsets.UTF_8);
    Path model = tmp.resolve("models").resolve(ChatModelProfile.COMPACT.modelFile());
    Files.createDirectories(model.getParent());
    Files.writeString(model, "gguf-bytes", StandardCharsets.UTF_8);

    UiSettingsStore store = new UiSettingsStore(
        UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json"));
    String executableBefore = store.load().getServerExecutablePath();
    String modelBefore = store.load().getLlmModelPath();
    RecordingComponents components = new RecordingComponents();
    AtomicBoolean exercised = new AtomicBoolean();
    // The candidate's model load shows on the GPU once it has been exercised.
    GpuCapabilitiesService gpu = mock(GpuCapabilitiesService.class);
    when(gpu.snapshot()).thenAnswer(call -> gpuSnapshot(exercised.get() ? 1536 * MIB : 1024 * MIB));
    SurvivingCandidateControl control = new SurvivingCandidateControl();
    ChatHolds holds = mock(ChatHolds.class);
    when(holds.beginSelfTest("runtime-variant-activation")).thenAnswer(call ->
        new SelfTestSession<SelfTestSpec, SelfTestRun>() {
          @Override public SelfTestRun run(SelfTestSpec spec) {
            HttpServer candidate;
            try {
              candidate = HttpServer.create(
                  new InetSocketAddress(InetAddress.getLoopbackAddress(), spec.port()), 0);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
            candidate.createContext("/health", exchange -> answer(exchange, "{\"status\":\"ok\"}"));
            candidate.createContext("/v1/chat/completions", exchange -> {
              exercised.set(true);
              answer(exchange, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
            });
            candidate.start();
            try {
              return new ChildSelfTestLauncher(control).run(spec);
            } finally {
              candidate.stop(0);
            }
          }

          @Override public void close() {}
        });

    ConfigStore.setGlobal(new ConfigStore(ConfigStoreRebuilder.prepare(store.load())));
    var fixture = new RuntimeIntentTestFixture(
        tmp.resolve("intent"), store, ConfigStore.globalOrNull(), components);
    intentFixtures.add(fixture);
    RuntimeActivationService svc = new RuntimeActivationService(processExecutors,
        OnlineAiService.unavailable(), store, gpu, null, null, null, null, holds, null,
        new SettingsServiceImpl(store, fixture.runner()));

    svc.startActivate("cuda12", "compact");
    AiRuntimeActivationStatus st = awaitDone(svc);

    assertTrue(exercised.get(), "the candidate was healthy and exercised");
    assertEquals(1, control.stops.get(), "the candidate's forced stop was attempted");
    assertEquals("failed", st.result,
        "a candidate that survived its stop is not a passed self-test: " + st.message);
    assertEquals(List.of(), components.installed, "nothing is committed");
    assertEquals(executableBefore, store.load().getServerExecutablePath(),
        "the activation settings are unchanged");
    assertEquals(modelBefore, store.load().getLlmModelPath());
  }

  private static void answer(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static GpuCapabilities gpuSnapshot(long usedBytes) {
    long total = 12L * 1024 * MIB;
    var nvml = new GpuCapabilities.Nvml(true, null, null, null, "560.00", 560, 0, 1, total,
        total - usedBytes, usedBytes, 0, 0);
    var effective = new GpuCapabilities.Effective(true, "nvml", GpuCapabilities.Confidence.HIGH,
        "560.00", 560, 0, 1, total, total - usedBytes, usedBytes, GpuCapabilities.Cuda.unknown());
    return new GpuCapabilities(nvml, null, effective);
  }

  private void setProp(String key, String value) {
    prevProps.putIfAbsent(key, System.getProperty(key));
    System.setProperty(key, value);
  }

  private void clearProp(String key) {
    prevProps.putIfAbsent(key, System.getProperty(key));
    System.clearProperty(key);
  }

  private static AiRuntimeActivationStatus awaitDone(RuntimeActivationService svc) throws Exception {
    long deadline = System.currentTimeMillis() + 60_000;
    while (System.currentTimeMillis() < deadline) {
      AiRuntimeActivationStatus st = svc.getActivationStatus();
      if (!"running".equalsIgnoreCase(st.state)) return st;
      Thread.sleep(25);
    }
    fail("Timed out waiting for runtime activation to finish");
    return svc.getActivationStatus();
  }

  /** A child control whose candidate runs, answers, and survives its forced stop. */
  private static final class SurvivingCandidateControl implements ChildControl {
    final AtomicInteger stops = new AtomicInteger();
    private final ChildHandle candidate = new ChildHandle() {
      private final CompletableFuture<ExitOutcome> exit = new CompletableFuture<>();
      @Override public String childId() { return "c-surviving"; }
      @Override public ChildKind kind() { return ChildKind.LLAMA_SELF_TEST; }
      @Override public long pid() { return Integer.MAX_VALUE; }
      @Override public Instant processStartedAt() { return Instant.EPOCH; }
      @Override public OutputStream stdin() { return OutputStream.nullOutputStream(); }
      @Override public InputStream stdout() { return InputStream.nullInputStream(); }
      @Override public InputStream stderr() { return InputStream.nullInputStream(); }
      @Override public CompletionStage<ExitOutcome> exit() { return exit; }
      @Override public State state() { return State.SURVIVED; }
    };

    @Override public ChildHandle start(ChildSpec spec, ManifestEntry manifest) {
      return candidate;
    }

    @Override public CompletionStage<ExitOutcome> stop(ChildHandle child, StopRequest request) {
      stops.incrementAndGet();
      return CompletableFuture.failedFuture(new ChildSurvivedException(new ChildView(
          child.childId(), child.kind(), child.pid(), child.processStartedAt(),
          ChildHandle.State.SURVIVED, true, "llama-self-test")));
    }

    @Override public CompletionStage<List<ExitOutcome>> stopAll(
        Collection<ChildHandle> children, StopRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override public void note(ChildHandle child, String token) {}

    @Override public boolean awaitGpuRelease(Duration max) {
      return true;
    }

    @Override public List<ChildView> list() {
      return List.of();
    }

    @Override public List<ExitOutcome> recentExits(int max) {
      return List.of();
    }

    @Override public void close() {}
  }

  /** Records what a committed activation would install; installs nothing else. */
  private static final class RecordingComponents implements SettingsComponentComposer {
    final List<SettingsCandidateContext> installed = new ArrayList<>();

    @Override
    public Prepared prepare(UiSettings candidate, ResolvedConfig desired,
        Map<String, Set<String>> affected) {
      return prepare(candidate, desired, affected, SettingsCandidateContext.NONE);
    }

    @Override
    public Prepared prepare(UiSettings candidate, ResolvedConfig desired,
        Map<String, Set<String>> affected, SettingsCandidateContext context) {
      return new Prepared() {
        @Override public void includeObservation(
            io.justsearch.core.component.EngineComponentSnapshot.Component observation) {
          throw new UnsupportedOperationException("this fixture has no generation projection");
        }
        @Override public void withOwnerLocks(Runnable publication) { publication.run(); }
        @Override public void validate() {}
        @Override public void install() { installed.add(context); }
        @Override public void notifyObservers() {}
        @Override public void retire() {}
        @Override public void abort() {}
      };
    }
  }
}
