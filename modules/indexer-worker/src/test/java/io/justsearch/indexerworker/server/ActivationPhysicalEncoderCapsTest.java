/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.modality.nlp.Vocabulary;
import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.AiRuntimeActivationStatus;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.app.api.settings.SettingsCandidateContext;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.ai.runtime.RuntimeActivationService;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.gpu.CudaDevice;
import io.justsearch.app.services.gpu.DeviceFacts;
import io.justsearch.app.services.gpu.GpuProfileResolver;
import io.justsearch.app.services.settings.SettingsCommitCoordinator;
import io.justsearch.app.services.settings.SettingsComponentComposer;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.ModelPackage;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.ModelRegistry;
import io.justsearch.configuration.model.ModelRegistryLoader;
import io.justsearch.configuration.model.ModelVariant;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.EngineComponentRegistry;
import io.justsearch.core.component.EngineComponentSnapshot;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.embed.onnx.EmbeddingAssembly;
import io.justsearch.indexerworker.embed.onnx.EmbeddingShape;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder;
import io.justsearch.indexerworker.splade.SpladeAssembly;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexerworker.splade.SpladeShape;
import io.justsearch.indexerworker.tokenizer.HuggingFaceNativeRuntime;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.ort.Composition;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.ModelCapabilities;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.SessionHandle;
import io.justsearch.ort.telemetry.OrtSessionTelemetryEvents;
import io.justsearch.reranker.CrossEncoderReranker;
import io.justsearch.reranker.RerankSkipCause;
import io.justsearch.reranker.RerankerAssembly;
import io.justsearch.reranker.RerankerShape;
import io.justsearch.reranker.RerankerTokenizer;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

/**
 * Activation through the real settings coordinator and physical encoder owner. Native model
 * loading, tokenizers, and warm-up inference are stand-ins; policy resolution, composition,
 * serving-owner retirement, and publication run normally. No application stack is launched.
 */
final class ActivationPhysicalEncoderCapsTest {
  private static final long MIB = 1024L * 1024L;
  private static final List<EncoderRole> ROLES =
      List.of(EncoderRole.EMBEDDING, EncoderRole.SPLADE, EncoderRole.RERANKER);

  @Test
  void compactToStandardActivationReplacesPhysicalBulkAndQueryCaps(@TempDir Path dir)
      throws Exception {
    assertActivation(dir, ChatModelProfile.COMPACT, ChatModelProfile.STANDARD);
  }

  @Test
  void standardToCompactActivationReplacesPhysicalBulkAndQueryCaps(@TempDir Path dir)
      throws Exception {
    assertActivation(dir, ChatModelProfile.STANDARD, ChatModelProfile.COMPACT);
  }

  @Test
  void precommitRefusalRestoresSourcePhysicalCapsAndWarmQuery(@TempDir Path dir)
      throws Exception {
    try (var fixture = new Fixture(dir, ChatModelProfile.COMPACT)) {
      fixture.refuseValidation.set(true);
      fixture.activation.startActivate("cuda12", "standard");

      var status = awaitDone(fixture.activation);
      assertEquals("failed", status.state, "validation refuses after physical preparation");
      assertEquals("RUNTIME_ACTIVATION_FAILED", status.errorCode, status.message);
      assertTrue(fixture.preparationCompleted.get(), "fault injection follows physical preparation");
      assertSame(fixture.initial, ConfigStore.global().get(),
          "precommit refusal must retain the original resolved configuration");
      assertSame(fixture.initialIndexPlan, readField(fixture.server, "initialIndexCompositionPlan"),
          "rollback must retain the source's captured recovery plan");

      var active = Collections.newSetFromMap(new IdentityHashMap<SessionHandle, Boolean>());
      try (var serving = fixture.server.captureServingView()) {
        var indexSurface = serving.encoderSet().surfaceForOwner();
        var querySurface = queryOwner(serving).surfaceForOwner();
        active.addAll(indexSurface.handles());
        active.addAll(querySurface.handles());
        var expected = caps(fixture.initial);
        for (int i = 0; i < ROLES.size(); i++) {
          EncoderRole role = ROLES.get(i);
          InferenceSurface physical = role == EncoderRole.RERANKER ? querySurface : indexSurface;
          long expectedBytes = expected.get(i) * MIB;
          assertEquals(expectedBytes, physical.policies().models().get(role).gpu().arenaCapBytes(),
              "refused activation must restore source physical " + role + " cap");
          assertEquals(expectedBytes, fixture.constructionCaps.get(physical.handleRoles().get(role)),
              "restored " + role + " handle must have the source construction cap");
        }
        assertTrue(fixture.warmed.get(querySurface.handleRoles().get(EncoderRole.RERANKER)).get(),
            "restored query session must complete warm-up before publication");
      }
      assertEquals(1, fixture.server.retainedEncoderCount(),
          "refused activation leaves one physical encoder owner");
      fixture.closed.forEach((handle, closed) -> assertTrue(closed.get() || active.contains(handle),
          "every refused candidate handle must retire"));
    }
  }

  private static void assertActivation(Path dir, ChatModelProfile from, ChatModelProfile to)
      throws Exception {
    try (var fixture = new Fixture(dir, from)) {
      var expected = caps(ConfigStoreRebuilder.prepare(new UiSettings(), to, null));
      assertNotEquals(caps(fixture.initial), expected, "profiles must reserve different chat footprints");
      fixture.activation.startActivate("cuda12", to.id());
      var status = awaitDone(fixture.activation);
      assertEquals("completed", status.state, status.message);
      assertEquals(expected, caps(ConfigStore.global().get()), "resolved target plan reached commitment");

      try (var serving = fixture.server.captureServingView()) {
        var indexSurface = serving.encoderSet().surfaceForOwner();
        var querySurface = queryOwner(serving).surfaceForOwner();
        for (int i = 0; i < ROLES.size(); i++) {
          EncoderRole role = ROLES.get(i);
          InferenceSurface physical = role == EncoderRole.RERANKER ? querySurface : indexSurface;
          long expectedBytes = expected.get(i) * MIB;
          assertEquals(expectedBytes, physical.policies().models().get(role).gpu().arenaCapBytes(),
              from.id() + " to " + to.id() + " physical " + role + " session must carry target cap");
          SessionHandle handle = physical.handleRoles().get(role);
          assertNotNull(handle, "published physical handle for " + role);
          assertEquals(expectedBytes, fixture.constructionCaps.get(handle),
              role + " handle construction must receive the target cap");
        }
      }
      assertTrue(fixture.sourceClosedBeforeCandidate.stream().allMatch(Boolean::booleanValue),
          "each source session must retire before its corresponding replacement is constructed");
      assertEquals(3, fixture.sourceClosedBeforeCandidate.size(),
          "one physical replacement session is constructed for every changed role");
      verify(fixture.producer).parkProducerModelsForEncoderRecovery();
      verify(fixture.successor).resumeProducerAfterCutover();
    }
  }

  private static List<Integer> caps(ResolvedConfig config) {
    return List.of(config.ai().embedding().gpuMemMb(), config.ai().splade().gpuMemMb(),
        config.ai().reranker().gpuMemMb());
  }

  private static AiRuntimeActivationStatus awaitDone(RuntimeActivationService service)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < deadline) {
      var status = service.getActivationStatus();
      if (!"running".equals(status.state)) return status;
      Thread.sleep(10);
    }
    fail("activation did not finish within the fixture budget");
    return service.getActivationStatus();
  }

  private static final class Fixture implements AutoCloseable {
    private final Map<String, String> properties = new LinkedHashMap<>();
    private final ConfigStore priorStore = ConfigStore.globalOrNull();
    private final TestEngineExecutors executors = new TestEngineExecutors();
    private final Map<SessionHandle, Long> constructionCaps =
        Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<SessionHandle, String> constructionConsumers =
        Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<SessionHandle, AtomicBoolean> closed =
        Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<SessionHandle, AtomicBoolean> warmed =
        Collections.synchronizedMap(new IdentityHashMap<>());
    private final AtomicBoolean refuseValidation = new AtomicBoolean();
    private final AtomicBoolean preparationCompleted = new AtomicBoolean();
    private final List<Boolean> sourceClosedBeforeCandidate = Collections.synchronizedList(new ArrayList<>());
    private final List<SessionHandle> sourceHandles = new ArrayList<>();
    private final DefaultWorkerAppServices producer = mock(DefaultWorkerAppServices.class);
    private final DefaultWorkerAppServices successor = mock(DefaultWorkerAppServices.class);
    private final AtomicReference<Runnable> producerLease = new AtomicReference<>();
    private final ResolvedConfig initial;
    private final IndexCompositionPlan initialIndexPlan;
    private final KnowledgeServer server;
    private final SqliteOperationStore operations;
    private final RuntimeActivationService activation;

    Fixture(Path dir, ChatModelProfile from) throws Exception {
      Path models = dir.resolve("models").toAbsolutePath();
      property("justsearch.home", dir.toAbsolutePath().toString());
      property("justsearch.data.dir", dir.toAbsolutePath().toString());
      property("justsearch.models.dir", models.toString());
      property("justsearch.chat.profile", from.id());
      property("justsearch.context.size", "32768");
      property("justsearch.server.exe", null);
      property("justsearch.server.exe.source", null);
      property("justsearch.ner.enabled", "false");
      property("justsearch.bgem3.enabled", "false");
      property("justsearch.citation.scorer.enabled", "false");
      var packages = new ArrayList<ModelPackage>();
      for (var profile : List.of(ChatModelProfile.STANDARD, ChatModelProfile.COMPACT)) {
        Path file = models.resolve(profile.modelFile());
        Files.createDirectories(file.getParent());
        Files.writeString(file, "fixture");
        Files.writeString(models.resolve(profile.mmprojFile()), "projector");
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest
            .getInstance("SHA-256").digest(Files.readAllBytes(file)));
        io.justsearch.app.inference.ChatArtifactIdentity.rememberWritten(file, Files.size(file), digest);
        packages.add(new ModelPackage(profile.id(), "fixture", "fixture",
            models.relativize(file.getParent()).toString(), List.of(new ModelVariant(
                file.getFileName().toString(), ModelPrecision.GGUF, ExecutionProvider.LLAMA_SERVER,
                digest, Files.size(file), null)), List.of(), 0, null));
      }
      for (String role : List.of("embed", "splade", "rerank")) {
        Path model = models.resolve(role);
        Files.createDirectories(model);
        Files.writeString(model.resolve("model_fp16.onnx"), "native model stand-in");
        Files.writeString(model.resolve("tokenizer.json"), "tokenizer stand-in");
        Files.writeString(model.resolve("vocab.txt"), "fixture");
        property("justsearch." + (role.equals("embed") ? "embed.onnx" : role)
            + ".model_path", model.toString());
      }
      Path exe = dir.resolve("native-bin/llama-server/variants/cuda12/llama-server.exe");
      Files.createDirectories(exe.getParent());
      Files.writeString(exe, "self-test executable stand-in");
      ConfigStoreRebuilder.rememberAutoDetected(Map.of("justsearch.gpu.layers", "99",
          "justsearch.gpu.enabled", "true"));
      var device = new CudaDevice(0, Optional.empty(), Optional.empty(), Optional.empty(), 12_282L * MIB);
      var facts = new DeviceFacts(DeviceFacts.ProbeStatus.RAN_OK, 0, 12_080,
          List.of(device), Optional.of(device));
      var planner = GpuProfileResolver.planner(() -> facts, ignored -> Map.of(
          EncoderRole.EMBEDDING, 598.3, EncoderRole.SPLADE, 474.2,
          EncoderRole.RERANKER, 600.3));
      var manifest = new ModelRegistry(2, "fixture identities", packages);
      ConfigStoreRebuilder.rememberDevicePlanner(config -> {
        try (var registry = mockStatic(ModelRegistryLoader.class, CALLS_REAL_METHODS)) {
          registry.when(() -> ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json"))
              .thenReturn(manifest);
          return planner.plan(config);
        }
      });
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
          dir.resolve("settings.json"));
      initial = ConfigStoreRebuilder.prepare(settings.load());
      ConfigStore.setGlobal(new ConfigStore(initial));
      var projection = EncoderConfigurationProjection.from(initial);
      var capturedPlan = new AtomicReference<InferenceCompositionRoot.CapturedCompositionPlan>();
      InferenceSurface sourceSurface;
      try (var _ = new NativeStandins(this, false)) {
        sourceSurface = InferenceCompositionRoot.compose(projection, HardwareProfile.gpuFull(0),
            null, models, () -> true, OrtSessionTelemetryEvents.NOOP, null, null,
            ignored -> {}, capturedPlan::set);
      }
      assertEquals(Set.copyOf(ROLES), sourceSurface.policies().models().keySet(),
          "source fixture composes all three physical roles");
      sourceHandles.addAll(sourceSurface.handles());
      warmed.get(sourceSurface.handleRoles().get(EncoderRole.RERANKER)).set(true);
      initialIndexPlan = capturedPlan.get().indexPlan();
      var partition = sourceSurface.partitionQueryRoles(projection);
      var absent = IndexFingerprint.ModelFingerprint.notConfigured();
      var index = new EncoderSet(partition.index(),
          new EncoderSet.ModelIdentity(absent, absent, absent, false, 768));
      index.releaseModelReady();
      var query = new QueryRoleSet(partition.query());
      var sourceReranker = mock(CrossEncoderReranker.class);
      when(sourceReranker.isGpuAvailable()).thenReturn(true);
      query.bindReranker(query.own(sourceReranker));
      settings.replacePrepared(settings.prepareExact(settings.load(), new SettingsWitness(0, null),
          partition.query().componentObservation().querySelection().orElseThrow()));
      var runtime = mock(RunningRuntime.class);
      var component = mock(ComponentHandle.class);
      var spec = new ComponentSpec("encoders", false, InferenceCompositionRoot.componentDependencies(),
          ComponentSpec.ComposeCapability.CHOOSES_PER_APPLY, Duration.ZERO, 2);
      when(component.snapshot()).thenReturn(new EngineComponentSnapshot.Component(spec,
          ComponentState.READY, null, Instant.now(), 0L, projection.digest(), projection.digest(),
          null, 0, null));
      when(component.prepareReplacement(any())).thenReturn(mock(EngineComponentRegistry.PreparedBatch.class));
      server = new KnowledgeServer(executors, WorkerBootFixture.workerConfig(dir), null,
          io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(),
          null, component, initial, ConfigStore.global()::get,
          new java.util.concurrent.locks.ReentrantReadWriteLock(),
          () -> new io.justsearch.core.component.DeviceMemoryLine(24_000L * MIB, 20_000L * MIB));
      server.signalBus = mock(io.justsearch.indexerworker.coordination.WorkerSignalBus.class);
      server.infraCtx = new InfraContext(WorkerBootFixture.workerConfig(dir),
          mock(io.justsearch.indexerworker.queue.JobQueue.class), () -> runtime, () -> runtime,
          server.signalBus, null, null, dir, dir.resolve("active"), () -> null, 5_000L);
      when(successor.ingestService()).thenReturn(mock(WorkerIngestService.class));
      when(producer.prepareQueryServingSuccessor(any(), any(), any())).thenReturn(successor);
      when(producer.prepareTextOnlyEncoderRecoveryView(runtime)).thenReturn(mock(WorkerAppServices.class));
      when(producer.pauseProducerForCutover(anyLong())).thenReturn(true);
      when(producer.prepareProducerTransferTo(successor))
          .thenReturn(mock(DefaultWorkerAppServices.ProducerTransfer.class));
      doAnswer(call -> { releaseProducerLease(); producerLease.set(call.getArgument(0)); return null; })
          .when(producer).replaceProducerModelLease(any());
      doAnswer(call -> { releaseProducerLease(); return null; }).when(producer).parkProducerModelsForEncoderRecovery();
      doAnswer(call -> { releaseProducerLease(); return null; }).when(producer).close();
      Class<?> viewType = Class.forName(KnowledgeServer.class.getName() + "$ServingView");
      var constructor = viewType.getDeclaredConstructors()[0];
      constructor.setAccessible(true);
      Object view = constructor.newInstance(producer, runtime, runtime, dir.resolve("active"));
      attach(view, "attachEncoderSet", EncoderSet.class, index);
      attach(view, "attachQueryRoleSet", QueryRoleSet.class, query);
      field(server, "servingView", view);
      field(server, "initialEncoderSet", index);
      field(server, "initialQueryRoleSet", query);
      field(server, "initialIndexCompositionPlan", initialIndexPlan);
      field(server, "startupConfiguration", initial);
      server.appServices = producer;
      server.startDeferredModelInitialization(() -> {});
      server.deferredModelInit.join();
      var components = new PhysicalComposer(this, settings);
      operations = new SqliteOperationStore(dir.resolve("operations.db"));
      var owner = new SettingsCommitCoordinator(settings, ConfigStore.global(),
          () -> fail("profile activation must use settings recomposition"),
          ignored -> OperationResult.success("committed"), () -> false, components);
      var runner = new OperationAttemptRunnerImpl(operations, Clock.systemUTC(),
          Set.of(OperationKind.SETTINGS_APPLY, OperationKind.RECONFIGURE), owner);
      activation = new RuntimeActivationService(executors, OnlineAiService.unavailable(), settings,
          null, null, null, null, null, null, null, new SettingsServiceImpl(settings, runner));
      installPassingSelfTest(activation);
    }

    private void property(String name, String value) {
      properties.putIfAbsent(name, System.getProperty(name));
      if (value == null) System.clearProperty(name);
      else System.setProperty(name, value);
    }

    private void releaseProducerLease() {
      Runnable release = producerLease.getAndSet(null);
      if (release != null) release.run();
    }

    @Override public void close() throws Exception {
      try {
        activation.close();
        operations.close();
        releaseProducerLease();
        server.close();
      } finally {
        executors.close();
        ConfigStoreRebuilder.rememberDevicePlanner(null);
        ConfigStoreRebuilder.rememberAutoDetected(Map.of());
        ConfigStore.restoreGlobal(ConfigStore.global(), priorStore);
        properties.forEach((name, value) -> {
          if (value == null) System.clearProperty(name);
          else System.setProperty(name, value);
        });
      }
    }
  }

  private static final class PhysicalComposer implements SettingsComponentComposer {
    private final Fixture fixture;
    private final UiSettingsStore settings;
    PhysicalComposer(Fixture fixture, UiSettingsStore settings) {
      this.fixture = fixture;
      this.settings = settings;
    }
    @Override public Prepared prepare(UiSettings candidate, ResolvedConfig desired,
        Map<String, Set<String>> affected) {
      return prepare(candidate, desired, affected, SettingsCandidateContext.NONE);
    }
    @Override public Prepared prepare(UiSettings candidate, ResolvedConfig desired,
        Map<String, Set<String>> affected, SettingsCandidateContext context) {
      assertTrue(affected.containsKey("encoders"), "activation must select the encoder owner");
      var nativeBoundary = new NativeStandins(fixture, true);
      KnowledgeServer.PreparedQueryRoleSettings prepared;
      try {
        prepared = fixture.server.prepareQueryRoleSettings(candidate, desired,
            affected.get("encoders"), settings.inspect().queryRoles());
        fixture.preparationCompleted.set(true);
      } catch (RuntimeException | Error failure) {
        nativeBoundary.close();
        throw failure;
      }
      return new Prepared() {
        @Override public Optional<QueryRoleSelection> queryRoleSelection() { return Optional.of(prepared.selection()); }
        @Override public Optional<io.justsearch.core.component.ComposeEvidence> composition() { return Optional.of(prepared.composition()); }
        @Override public void includeObservation(EngineComponentSnapshot.Component observation) { }
        @Override public void withOwnerLocks(Runnable publication) { prepared.withOwnerLocks(publication); }
        @Override public void validate() {
          prepared.validate();
          if (fixture.refuseValidation.get()) {
            throw new IllegalStateException("fixture refuses activation after physical preparation");
          }
        }
        @Override public void install() { prepared.install(); }
        @Override public void notifyObservers() { prepared.notifyObservers(); }
        @Override public void retire() { try { prepared.retire(); } finally { nativeBoundary.close(); } }
        @Override public void abort() { try { prepared.abort(); } finally { nativeBoundary.close(); } }
        @Override public void abort(Throwable cause) { try { prepared.abort(cause); } finally { nativeBoundary.close(); } }
      };
    }
  }

  /** Only boundaries that would read native graphs or invoke JNI are replaced. */
  private static final class NativeStandins implements AutoCloseable {
    private final MockedStatic<OrtSessionAssembler> assembler = mockStatic(OrtSessionAssembler.class);
    private final MockedStatic<OnnxEmbeddingEncoder> embedding = mockStatic(OnnxEmbeddingEncoder.class);
    private final MockedStatic<SpladeEncoder> splade = mockStatic(SpladeEncoder.class);
    private final MockedStatic<CrossEncoderReranker> reranker = mockStatic(CrossEncoderReranker.class);
    private final MockedStatic<HuggingFaceNativeRuntime> tokenizer = mockStatic(HuggingFaceNativeRuntime.class);
    private final MockedConstruction<CrossEncoderReranker> inference;
    private boolean closed;

    NativeStandins(Fixture fixture, boolean candidate) {
      inference = mockConstruction(CrossEncoderReranker.class, (mock, context) -> {
        SessionHandle handle = (SessionHandle) context.arguments().getFirst();
        when(mock.isGpuAvailable()).thenAnswer(ignored -> fixture.warmed.get(handle).get()
            && !fixture.closed.get(handle).get());
        when(mock.rerank(anyString(), anyList(), anyLong())).thenAnswer(ignored -> {
          fixture.warmed.get(handle).set(true);
          return new CrossEncoderReranker.RerankedResult(
              List.of(0), List.of(0.25f), RerankSkipCause.NONE, 0);
        });
      });
      assembler.when(() -> OrtSessionAssembler.buildManager(anyString(), any(), any(), any()))
          .thenAnswer(call -> {
            String consumer = call.getArgument(0);
            if (candidate) fixture.sourceClosedBeforeCandidate.add(fixture.sourceHandles.stream()
                .filter(handle -> consumer.equals(fixture.constructionConsumers.get(handle)))
                .allMatch(handle -> fixture.closed.get(handle).get()));
            var policy = call.<Composition>getArgument(1).modelSession();
            SessionHandle handle = mock(SessionHandle.class);
            var retired = new AtomicBoolean();
            fixture.closed.put(handle, retired);
            fixture.warmed.put(handle, new AtomicBoolean());
            fixture.constructionCaps.put(handle, policy.gpu().arenaCapBytes());
            fixture.constructionConsumers.put(handle, consumer);
            when(handle.isGpuAvailable()).thenAnswer(ignored -> !retired.get());
            when(handle.retainedGpuWeightsBytes()).thenReturn(OptionalLong.of(8L));
            when(handle.retirementStatus()).thenAnswer(ignored -> retired.get()
                ? SessionHandle.RetirementStatus.RETIRED : SessionHandle.RetirementStatus.ACTIVE);
            doAnswer(ignored -> { retired.set(true); return null; }).when(handle).close();
            return handle;
          });
      embedding.when(() -> OnnxEmbeddingEncoder.buildAssembly(any(), any(), anyInt(), anyInt(), anyBoolean(), any()))
          .thenAnswer(call -> new EmbeddingAssembly(call.getArgument(0),
              new EmbeddingShape(512, false, OnnxEmbeddingEncoder.PoolingStrategy.MEAN_POOL, 512, 768),
              mock(HuggingFaceTokenizer.class), new ModelCapabilities(ModelCapabilities.PoolingMode.MEAN,
                  512, 768, ModelPrecision.FP32, ModelPrecision.FP16, "", "", Map.of(), List.of())));
      splade.when(() -> SpladeEncoder.buildAssembly(any(), any(), any()))
          .thenAnswer(call -> new SpladeAssembly(call.getArgument(0),
              mock(SpladeShape.class),
              mock(HuggingFaceTokenizer.class), mock(Vocabulary.class), null));
      reranker.when(() -> CrossEncoderReranker.buildAssembly(any(), any(), any(), anyInt()))
          .thenAnswer(call -> new RerankerAssembly(call.getArgument(0), new RerankerShape(512, false),
              mock(RerankerTokenizer.class)));
    }

    @Override public void close() {
      if (closed) return;
      closed = true;
      inference.close();
      tokenizer.close();
      reranker.close();
      splade.close();
      embedding.close();
      assembler.close();
    }
  }

  private static void installPassingSelfTest(RuntimeActivationService service) throws Exception {
    Class<?> resultType = Class.forName(RuntimeActivationService.class.getName() + "$SelfTestResult");
    var constructor = resultType.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    Object result = constructor.newInstance("passed", 65001, 0L, 128L * MIB, 128L * MIB,
        List.of(), "12gb_plus", "nvml");
    var method = RuntimeActivationService.class.getDeclaredMethod("setSelfTestOverrideForTest",
        java.util.function.BiFunction.class);
    method.setAccessible(true);
    method.invoke(service, (java.util.function.BiFunction<Path, Path, Object>) (exe, model) -> result);
  }

  private static QueryRoleSet queryOwner(KnowledgeServer.ServingLease lease) throws Exception {
    Field captured = lease.getClass().getDeclaredField("captured");
    captured.setAccessible(true);
    Object view = captured.get(lease);
    Field query = view.getClass().getDeclaredField("queryRoleSet");
    query.setAccessible(true);
    return (QueryRoleSet) query.get(view);
  }

  private static void attach(Object view, String name, Class<?> type, Object value) throws Exception {
    var method = view.getClass().getDeclaredMethod(name, type);
    method.setAccessible(true);
    method.invoke(view, value);
  }

  private static void field(Object owner, String name, Object value) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }

  private static Object readField(Object owner, String name) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }
}
