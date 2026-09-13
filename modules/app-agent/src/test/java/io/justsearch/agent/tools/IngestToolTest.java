package io.justsearch.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.EngineContextTestFixtures;
import io.justsearch.core.context.EngineContext;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy;
import io.justsearch.app.api.knowledge.KnowledgeIngestResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IngestToolTest {

  @TempDir Path tempDir;

  /**
   * Test factory — substitutes the production Worker-side {@link IngestTool.ScanRootCallback}
   * with a local-walk fallback that mirrors the pre-Slice-D back-compat behaviour. Production
   * code now requires the 3-arg {@link IngestTool} constructor; tests that don't exercise the
   * scan path still need a callback that expands directories so the existing assertions hold.
   */
  private static IngestTool toolWithLocalScan(IngestTool.IngestCallback ingestCallback) {
    return toolWithLocalScan(ingestCallback, context -> List.of());
  }

  private static IngestTool toolWithLocalScan(
      IngestTool.IngestCallback ingestCallback,
      Function<EngineContext, List<BrowseTool.RootInfo>> rootsSupplier) {
    return new IngestTool(ingestCallback, localScan(ingestCallback), rootsSupplier);
  }

  /** Tempdoc 811 (C-2a): variant that also supplies the watched-root collection bindings. */
  private static IngestTool toolWithLocalScan(
      IngestTool.IngestCallback ingestCallback,
      Function<EngineContext, List<BrowseTool.RootInfo>> rootsSupplier,
      Function<EngineContext, List<IngestCollectionPolicy.RootBinding>> rootBindingsSupplier) {
    return new IngestTool(
        ingestCallback, localScan(ingestCallback), rootsSupplier, rootBindingsSupplier);
  }

  /**
   * Test-local ceiling on the stubbed directory walk, so a stray large temp directory can never
   * turn one of these unit tests into an unbounded scan. Deliberately test-local: the production
   * expansion cap it used to borrow from {@code IngestTool} was unreachable (MAX_PATHS = 100 caps
   * the array long before it) and was deleted in tempdoc 877 §2.1.
   */
  private static final int STUB_SCAN_CAP = 10_000;

  private static IngestTool.ScanRootCallback localScan(IngestTool.IngestCallback ingest) {
    return (rootPath, collection, excludeGlobs, engineContext) -> {
      List<Path> expanded = new ArrayList<>();
      try (Stream<Path> stream = Files.walk(Path.of(rootPath))) {
        stream
            .filter(Files::isRegularFile)
            .filter(Files::isReadable)
            .forEach(
                p -> {
                  if (expanded.size() < STUB_SCAN_CAP) {
                    expanded.add(p);
                  }
                });
      } catch (IOException e) {
        return new KnowledgeIngestResponse(0, "Local scan fallback failed: " + e.getMessage());
      }
      if (expanded.isEmpty()) {
        return new KnowledgeIngestResponse(0, "");
      }
      return ingest.ingest(expanded, collection, engineContext);
    };
  }

  @Test
  void executeWithValidPaths() throws IOException {
    Path file = tempDir.resolve("test.txt");
    Files.writeString(file, "content");

    var capturedFiles = new AtomicReference<List<Path>>();
    var tool =
        toolWithLocalScan(
            (files, collection, context) -> {
              capturedFiles.set(files);
              return new KnowledgeIngestResponse(files.size(), "");
            });

    String json =
        "{\"paths\": [\"%s\"]}"
            .formatted(file.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertNotNull(capturedFiles.get());
    assertEquals(1, capturedFiles.get().size());
    assertTrue(result.message().contains("1 files"));
  }

  @Test
  void executeWithEmptyPaths() {
    var tool = toolWithLocalScan((files, collection, context) -> new KnowledgeIngestResponse(0, ""));
    OperationResult result = tool.execute("{\"paths\": []}", EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("required"));
  }

  @Test
  void executeWithMissingPathsField() {
    var tool = toolWithLocalScan((files, collection, context) -> new KnowledgeIngestResponse(0, ""));
    OperationResult result = tool.execute("{}", EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("required"));
  }

  @Test
  void executeWithDirectoryExpansion() throws IOException {
    Path dir = tempDir.resolve("subdir");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("a.txt"), "aaa");
    Files.writeString(dir.resolve("b.txt"), "bbb");
    Files.createDirectories(dir.resolve("nested"));
    Files.writeString(dir.resolve("nested").resolve("c.txt"), "ccc");

    var capturedFiles = new AtomicReference<List<Path>>();
    var tool =
        toolWithLocalScan(
            (files, collection, context) -> {
              capturedFiles.set(files);
              return new KnowledgeIngestResponse(files.size(), "");
            });

    String json =
        "{\"paths\": [\"%s\"]}"
            .formatted(dir.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertEquals(3, capturedFiles.get().size(), "Should expand dir to 3 files");
    assertTrue(result.message().contains("3 files"));
  }

  @Test
  void executeWithNonexistentPaths() throws IOException {
    Path existing = tempDir.resolve("exists.txt");
    Files.writeString(existing, "data");
    Path missing = tempDir.resolve("missing.txt");

    var tool =
        toolWithLocalScan(
            (files, collection, context) -> new KnowledgeIngestResponse(files.size(), ""));

    String json =
        "{\"paths\": [\"%s\", \"%s\"]}"
            .formatted(
                existing.toString().replace("\\", "\\\\"),
                missing.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertTrue(result.message().contains("1 files"), "Should ingest only existing file: " + result.message());
    assertTrue(result.message().contains("skipped"), "Should mention skipped: " + result.message());
  }

  @Test
  void executeAllPathsMissing() {
    var tool = toolWithLocalScan((files, collection, context) -> new KnowledgeIngestResponse(0, ""));

    String json = "{\"paths\": [\"/no/such/file.txt\"]}";
    OperationResult result = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("No readable files"));
  }

  @Test
  void executeWithIngestError() throws IOException {
    Path file = tempDir.resolve("error-test.txt");
    Files.writeString(file, "data");

    var tool =
        toolWithLocalScan(
            (files, collection, context) -> new KnowledgeIngestResponse(0, "Worker connection lost"));

    String json =
        "{\"paths\": [\"%s\"]}"
            .formatted(file.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), "Should succeed even with error (accepted=0 is valid response)");
    assertTrue(result.message().contains("Worker connection lost"), result.message());
  }

  @Test
  void executeInvalidJson() {
    var tool = toolWithLocalScan((files, collection, context) -> new KnowledgeIngestResponse(0, ""));
    OperationResult result = tool.execute("not json", EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("error"));
  }

  @Test
  void executeBatchLimitExceeded() {
    var tool = toolWithLocalScan((files, collection, context) -> new KnowledgeIngestResponse(0, ""));

    var sb = new StringBuilder("{\"paths\": [");
    for (int i = 0; i <= IngestTool.MAX_PATHS; i++) {
      if (i > 0) sb.append(",");
      sb.append("\"file-").append(i).append(".txt\"");
    }
    sb.append("]}");

    OperationResult result = tool.execute(sb.toString(), EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("exceeds limit"), result.message());
    assertTrue(result.message().contains(String.valueOf(IngestTool.MAX_PATHS)));
  }

  @Test
  void executeNoArgs() {
    var tool = toolWithLocalScan((files, collection, context) -> new KnowledgeIngestResponse(0, ""));
    OperationResult result = tool.execute("", EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
  }

  @Test
  void relativePathResolvedAgainstRoot() throws IOException {
    // Create a file under tempDir that simulates an indexed root
    Path docsDir = tempDir.resolve("docs");
    Files.createDirectories(docsDir);
    Files.writeString(docsDir.resolve("readme.md"), "content");

    var capturedFiles = new AtomicReference<List<Path>>();
    var roots = List.of(new BrowseTool.RootInfo(tempDir.toString(), "root"));
    var tool =
        toolWithLocalScan(
            (files, collection, context) -> {
              capturedFiles.set(files);
              return new KnowledgeIngestResponse(files.size(), "");
            },
            context -> roots);

    // Pass a relative path — should resolve against the root
    String json = "{\"paths\": [\"docs/readme.md\"]}";
    OperationResult result = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertEquals(1, capturedFiles.get().size());
    assertEquals(
        docsDir.resolve("readme.md").normalize(),
        capturedFiles.get().get(0).normalize());
  }

  @Test
  void relativePathWithRootNamePrefixStripped() throws IOException {
    // Simulates: indexed root = ".../docs", user passes "docs/explanation/file.md"
    // The leading "docs/" component matches the root folder name → should be stripped.
    Path docsRoot = tempDir.resolve("docs");
    Path explanationDir = docsRoot.resolve("explanation");
    Files.createDirectories(explanationDir);
    Files.writeString(explanationDir.resolve("file.md"), "content");

    var capturedFiles = new AtomicReference<List<Path>>();
    var roots = List.of(new BrowseTool.RootInfo(docsRoot.toString(), "docs"));
    var tool =
        toolWithLocalScan(
            (files, collection, context) -> {
              capturedFiles.set(files);
              return new KnowledgeIngestResponse(files.size(), "");
            },
            context -> roots);

    // Path includes the root folder name as a prefix — IngestTool should strip it
    String json = "{\"paths\": [\"docs/explanation/file.md\"]}";
    OperationResult result = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertEquals(1, capturedFiles.get().size());
    assertEquals(
        explanationDir.resolve("file.md").normalize(),
        capturedFiles.get().get(0).normalize());
  }

  @Test
  void relativePathNoMatchingRoot() {
    var roots = List.of(new BrowseTool.RootInfo(tempDir.toString(), "root"));
    var tool =
        toolWithLocalScan(
            (files, collection, context) -> new KnowledgeIngestResponse(0, ""),
            context -> roots);

    // Relative path that doesn't exist under any root
    String json = "{\"paths\": [\"nonexistent/file.txt\"]}";
    OperationResult result = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("No readable files"));
  }

  // ===================================================================================
  // Tempdoc 811 (C-2a) — collection tagging. Pre-811 EVERY ingest through this tool wrote
  // collection=null: unlabeled, absent from Library>Folders, passing every collection clause,
  // and unreachable by any prune path.
  // ===================================================================================

  @Test
  void outOfRootIngestIsTaggedMcpIngest() throws IOException {
    Path file = tempDir.resolve("loose.txt");
    Files.writeString(file, "content");

    var capturedCollection = new AtomicReference<String>("<never called>");
    var tool =
        toolWithLocalScan(
            (files, collection, context) -> {
              capturedCollection.set(collection);
              return new KnowledgeIngestResponse(files.size(), "");
            },
            context -> List.of(),
            context -> List.of()); // no watched roots → every path is out-of-root

    OperationResult result =
        tool.execute("{\"paths\": [\"%s\"]}".formatted(file.toString().replace("\\", "\\\\")), EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertEquals(
        IngestCollectionPolicy.OUT_OF_ROOT,
        capturedCollection.get(),
        "an out-of-root ingest must carry a real collection, not the pre-811 null");
  }

  @Test
  void inRootIngestInheritsTheRootCollection() throws IOException {
    Path rootDir = tempDir.resolve("watched");
    Files.createDirectories(rootDir);
    Path file = rootDir.resolve("note.txt");
    Files.writeString(file, "content");

    var capturedCollection = new AtomicReference<String>("<never called>");
    var tool =
        toolWithLocalScan(
            (files, collection, context) -> {
              capturedCollection.set(collection);
              return new KnowledgeIngestResponse(files.size(), "");
            },
            context -> List.of(),
            context -> List.of(new IngestCollectionPolicy.RootBinding(rootDir, "work-notes")));

    OperationResult result =
        tool.execute("{\"paths\": [\"%s\"]}".formatted(file.toString().replace("\\", "\\\\")), EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertEquals(
        "work-notes",
        capturedCollection.get(),
        "a path under a watched root must inherit that root's collection, not fork a second label");
  }

  @Test
  void explicitCollectionIsThreadedThrough() throws IOException {
    Path file = tempDir.resolve("explicit.txt");
    Files.writeString(file, "content");

    var capturedCollection = new AtomicReference<String>("<never called>");
    var tool =
        toolWithLocalScan(
            (files, collection, context) -> {
              capturedCollection.set(collection);
              return new KnowledgeIngestResponse(files.size(), "");
            },
            context -> List.of(),
            context -> List.of(new IngestCollectionPolicy.RootBinding(tempDir, "root-collection")));

    OperationResult result =
        tool.execute(
            "{\"paths\": [\"%s\"], \"collection\": \"  research  \"}"
                .formatted(file.toString().replace("\\", "\\\\")), EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertEquals(
        "research",
        capturedCollection.get(),
        "an explicit collection wins over root inheritance and is trimmed");
  }

  @Test
  void reservedCollectionIsRejectedServerSide() throws IOException {
    Path file = tempDir.resolve("impersonator.txt");
    Files.writeString(file, "content");

    var called = new AtomicReference<Boolean>(false);
    var tool =
        toolWithLocalScan(
            (files, collection, context) -> {
              called.set(true);
              return new KnowledgeIngestResponse(files.size(), "");
            });

    for (String reserved : List.of("agent-history", "justsearch-help", "AGENT-HISTORY")) {
      OperationResult result =
          tool.execute(
              "{\"paths\": [\"%s\"], \"collection\": \"%s\"}"
                  .formatted(file.toString().replace("\\", "\\\\"), reserved), EngineContextTestFixtures.AGENT_LOOP);
      assertFalse(result.success(), "reserved collection " + reserved + " must be rejected");
      assertTrue(result.message().contains("reserved"), result.message());
    }
    assertFalse(called.get(), "a rejected ingest must not reach the worker");
  }

  @Test
  void blankExplicitCollectionIsRejected() throws IOException {
    Path file = tempDir.resolve("blank.txt");
    Files.writeString(file, "content");

    var tool = toolWithLocalScan((files, collection, context) -> new KnowledgeIngestResponse(1, ""));
    OperationResult result =
        tool.execute(
            "{\"paths\": [\"%s\"], \"collection\": \"   \"}"
                .formatted(file.toString().replace("\\", "\\\\")), EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("non-empty"), result.message());
  }

  @Test
  void mixedInRootAndOutOfRootFilesAreGroupedByCollection() throws IOException {
    Path rootDir = tempDir.resolve("watched2");
    Files.createDirectories(rootDir);
    Path inRoot = rootDir.resolve("in.txt");
    Files.writeString(inRoot, "a");
    Path outside = tempDir.resolve("out.txt");
    Files.writeString(outside, "b");

    var byCollection = new java.util.LinkedHashMap<String, List<Path>>();
    var tool =
        toolWithLocalScan(
            (files, collection, context) -> {
              byCollection.put(String.valueOf(collection), List.copyOf(files));
              return new KnowledgeIngestResponse(files.size(), "");
            },
            context -> List.of(),
            context -> List.of(new IngestCollectionPolicy.RootBinding(rootDir, "watched-coll")));

    OperationResult result =
        tool.execute(
            "{\"paths\": [\"%s\", \"%s\"]}"
                .formatted(
                    inRoot.toString().replace("\\", "\\\\"),
                    outside.toString().replace("\\", "\\\\")), EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertEquals(
        java.util.Set.of("watched-coll", IngestCollectionPolicy.OUT_OF_ROOT),
        byCollection.keySet(),
        "one call must not force a single label onto paths with different containment");
    assertEquals(List.of(inRoot), byCollection.get("watched-coll"));
    assertEquals(List.of(outside), byCollection.get(IngestCollectionPolicy.OUT_OF_ROOT));
  }

  // Tempdoc 877 §2.1: the "schema advertises the optional collection tag" assertion moved to
  // AgentToolCatalogContractTest#ingestFilesAdvertisesTheOptionalCollectionTag (app-services) —
  // the catalog Interface is the only schema the model is shown, so that is the schema that has to
  // advertise `collection`.
}
