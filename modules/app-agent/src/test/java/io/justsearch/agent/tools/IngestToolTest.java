package io.justsearch.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.EngineContextTestFixtures;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.operations.RecordedIngestionService;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.core.context.EngineContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IngestToolTest {

  @TempDir Path tempDir;

  @Test
  void prepareCapturesDirectoryAndNestedWatchedCollection() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("docs"));
    Path nested = Files.createDirectories(root.resolve("private"));
    Files.writeString(nested.resolve("note.md"), "content");
    IngestTool tool = tool(List.of(new RootBinding(root, "public"), new RootBinding(nested, "private")),
        new RecordingIngestion(), ignored -> "generation-1", () -> List.of("*.tmp"));

    OperationPreparation prepared = tool.prepare(arguments(root, null),
        InvocationProvenance.agentLoop(java.time.Instant.EPOCH), EngineContextTestFixtures.AGENT_LOOP);
    RecordedRootPlan plan = RecordedRootPlan.fromReplayPayload(prepared.replayPayloadJson());

    assertEquals("generation-1", plan.generation());
    assertEquals(2, plan.roots().size());
    assertTrue(plan.roots().stream().noneMatch(RecordedRootPlan.Root::singleFile));
    assertEquals(root.toAbsolutePath().normalize(), plan.roots().get(0).path());
    assertEquals("public", plan.roots().get(0).collection());
    assertEquals(nested.toAbsolutePath().normalize(), plan.roots().get(1).path());
    assertEquals("private", plan.roots().get(1).collection());
    assertTrue(plan.roots().stream().allMatch(r -> r.excludePatterns().equals(List.of("*.tmp"))));
  }

  @Test
  void explicitCollectionCollapsesNestedPolicy() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("docs"));
    Path nested = Files.createDirectories(root.resolve("private"));
    Files.writeString(nested.resolve("note.md"), "content");
    IngestTool tool = tool(List.of(new RootBinding(root, "public"), new RootBinding(nested, "private")),
        new RecordingIngestion(), ignored -> "generation-1", List::of);

    OperationPreparation prepared = tool.prepare(arguments(root, " research "), InvocationProvenance.agentLoop(java.time.Instant.EPOCH),
        EngineContextTestFixtures.AGENT_LOOP);
    RecordedRootPlan.Root planned = RecordedRootPlan.fromReplayPayload(prepared.replayPayloadJson())
        .roots().get(0);

    assertEquals("research", planned.collection());
    assertTrue(planned.excludedSubtrees().isEmpty(), "same-policy nested root collapses into its parent");
    assertEquals(1, RecordedRootPlan.fromReplayPayload(prepared.replayPayloadJson()).roots().size());
  }

  @Test
  void relativeNameResolutionUsesExistingRootCandidate() throws IOException {
    Path emptyDocsRoot = Files.createDirectories(tempDir.resolve("A").resolve("docs"));
    Path realFile = tempDir.resolve("B").resolve("docs").resolve("x.md");
    Files.createDirectories(realFile.getParent());
    Files.writeString(realFile, "content");
    IngestTool tool = tool(List.of(new RootBinding(emptyDocsRoot, null),
        new RootBinding(tempDir.resolve("B"), null)), new RecordingIngestion(),
        ignored -> "generation-1", List::of);

    OperationPreparation prepared = tool.prepare("{\"paths\":[\"docs/x.md\"]}",
        InvocationProvenance.agentLoop(java.time.Instant.EPOCH), EngineContextTestFixtures.AGENT_LOOP);
    RecordedRootPlan plan = RecordedRootPlan.fromReplayPayload(prepared.replayPayloadJson());

    assertEquals(realFile.toAbsolutePath().normalize(), plan.roots().get(0).path());
  }

  @Test
  void invalidPathRefusesBeforeAnyPartialPreparationEffect() throws IOException {
    Path valid = tempDir.resolve("valid.md");
    Files.writeString(valid, "content");
    RecordingIngestion ingestion = new RecordingIngestion();
    IngestTool tool = tool(List.of(new RootBinding(tempDir, "docs")), ingestion,
        ignored -> "generation-1", List::of);

    OperationPreparationRefused refused = assertThrows(OperationPreparationRefused.class,
        () -> tool.prepare(toJson(Map.of("paths", List.of(valid.toString(), "missing.md"))),
            InvocationProvenance.agentLoop(java.time.Instant.EPOCH), EngineContextTestFixtures.AGENT_LOOP));

    assertEquals("BAD_REQUEST", refused.refusal().errorCode().orElseThrow());
    assertTrue(refused.refusal().message().contains("missing.md"), "must reject the unresolved path, not malformed JSON");
    assertEquals(0, ingestion.calls);
  }

  @Test
  void rootSupplierFailureRefusesPreparation() {
    IngestTool tool = new IngestTool(RecordedIngestionService.unavailable(),
        ignored -> { throw new IllegalStateException("roots unavailable"); }, ignored -> "generation-1",
        List::of);

    assertThrows(IllegalStateException.class,
        () -> tool.prepare("{\"paths\":[\"/tmp/note.md\"]}", InvocationProvenance.agentLoop(java.time.Instant.EPOCH),
            EngineContextTestFixtures.AGENT_LOOP));
  }

  @Test
  void replayUsesFrozenPlanAfterInputAndSuppliersChange() throws IOException {
    Path file = tempDir.resolve("note.md");
    Files.writeString(file, "content");
    AtomicInteger rootReads = new AtomicInteger();
    AtomicInteger generationReads = new AtomicInteger();
    AtomicInteger exclusionReads = new AtomicInteger();
    RecordingIngestion ingestion = new RecordingIngestion();
    IngestTool tool = new IngestTool(ingestion,
        ignored -> { rootReads.incrementAndGet(); return List.of(new RootBinding(tempDir, "docs")); },
        ignored -> { generationReads.incrementAndGet(); return "generation-1"; },
        () -> { exclusionReads.incrementAndGet(); return List.of("*.tmp"); });

    OperationPreparation prepared = tool.prepare(arguments(file, null),
        InvocationProvenance.agentLoop(java.time.Instant.EPOCH), EngineContextTestFixtures.AGENT_LOOP);
    Files.delete(file);
    OperationRecordHandle record = new OperationRecordHandle() {
      @Override public long id() { return 7; }
      @Override public String key() { return "ingest-key"; }
      @Override public void checkpoint(String cursor, long completed, long failed) {}
    };
    OperationExecution execution = tool.executePrepared(prepared, InvocationProvenance.agentLoop(java.time.Instant.EPOCH),
        EngineContextTestFixtures.AGENT_LOOP, record);

    assertSame(ingestion.execution, execution);
    assertSame(record, ingestion.record);
    assertSame(EngineContextTestFixtures.AGENT_LOOP, ingestion.context);
    assertEquals(1, ingestion.calls);
    assertEquals(1, rootReads.get());
    assertEquals(1, generationReads.get());
    assertEquals(1, exclusionReads.get());
    assertFalse(ingestion.completion.isDone());
  }

  @Test
  void directExecutionAndMissingAcceptedRecordHaveNoEffect() throws IOException {
    Path file = tempDir.resolve("note.md");
    Files.writeString(file, "content");
    RecordingIngestion ingestion = new RecordingIngestion();
    IngestTool tool = tool(List.of(new RootBinding(tempDir, "docs")), ingestion,
        ignored -> "generation-1", List::of);
    OperationPreparation prepared = tool.prepare(arguments(file, null),
        InvocationProvenance.agentLoop(java.time.Instant.EPOCH), EngineContextTestFixtures.AGENT_LOOP);

    assertThrows(IllegalStateException.class,
        () -> tool.execute(prepared.argumentsJson(), EngineContextTestFixtures.AGENT_LOOP));
    assertThrows(NullPointerException.class,
        () -> tool.executePrepared(prepared, InvocationProvenance.agentLoop(java.time.Instant.EPOCH),
            EngineContextTestFixtures.AGENT_LOOP, null));
    assertEquals(0, ingestion.calls);
  }

  @Test
  void validationChecksPublicArgumentsAgainstFrozenPayload() throws IOException {
    Path file = tempDir.resolve("note.md");
    Files.writeString(file, "content");
    IngestTool tool = tool(List.of(new RootBinding(tempDir, "docs")), new RecordingIngestion(),
        ignored -> "generation-1", List::of);
    OperationPreparation prepared = tool.prepare(arguments(file, "docs"), InvocationProvenance.agentLoop(java.time.Instant.EPOCH),
        EngineContextTestFixtures.AGENT_LOOP);
    OperationPreparation tampered = new OperationPreparation(
        arguments(file, "other"),
        prepared.replaySchema(), prepared.replayPayloadJson());

    assertThrows(IllegalArgumentException.class, () -> tool.validatePreparation(tampered));
  }

  private static String arguments(Path path, String collection) {
    return toJson(collection == null ? Map.of("paths", List.of(path.toString()))
        : Map.of("paths", List.of(path.toString()), "collection", collection));
  }

  private static String toJson(Map<String, ?> value) {
    return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value);
  }

  private static IngestTool tool(List<RootBinding> bindings, RecordingIngestion ingestion,
      java.util.function.Function<EngineContext, String> generation,
      java.util.function.Supplier<List<String>> exclusions) {
    return new IngestTool(ingestion, ignored -> bindings, generation, exclusions);
  }

  private static final class RecordingIngestion implements RecordedIngestionService {
    private int calls;
    private OperationRecordHandle record;
    private EngineContext context;
    private final CompletableFuture<OperationResult> completion = new CompletableFuture<>();
    private final OperationExecution execution = new OperationExecution(OperationResult.success("started"), completion);

    @Override
    public OperationExecution execute(OperationRecordHandle record, EngineContext context) {
      calls++;
      this.record = record;
      this.context = context;
      return execution;
    }

    @Override
    public void maintain() {}
  }
}
