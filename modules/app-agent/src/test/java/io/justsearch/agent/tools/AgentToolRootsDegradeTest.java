/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.EngineContextTestFixtures;
import io.justsearch.core.context.EngineContext;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.knowledge.FolderBrowseResponse;
import io.justsearch.app.api.knowledge.KnowledgeIngestResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponseBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 877 §2.4 — the ONE degrade behaviour, asserted across all four tools that read the
 * indexed roots.
 *
 * <p>Before {@code AgentToolPaths.RootsView} there were five guarded copies with four different
 * answers to "the roots supplier misbehaved" and four unguarded call sites, so the same Worker
 * hiccup surfaced as a silent pass in one tool, a rejected path in another and a {@code "Browse
 * error"} in a third. This binds the single answer: a null supplier, a supplier returning null, a
 * throwing supplier and an empty list are all "cannot say" — never an exception, and never a
 * rejection (degrade OPEN; the Worker's own index-membership check is the real boundary).
 */
final class AgentToolRootsDegradeTest {

  @TempDir Path tempDir;

  /** The four ways the roots can be unusable, as suppliers. */
  private static List<Function<EngineContext, List<BrowseTool.RootInfo>>> brokenSuppliers() {
    List<Function<EngineContext, List<BrowseTool.RootInfo>>> suppliers = new ArrayList<>();
    suppliers.add(null);
    suppliers.add(context -> null);
    suppliers.add(
        context -> {
          throw new IllegalStateException("worker unavailable");
        });
    suppliers.add(context -> List.of());
    return suppliers;
  }

  private static String label(int index) {
    return switch (index) {
      case 0 -> "null supplier";
      case 1 -> "supplier returning null";
      case 2 -> "throwing supplier";
      default -> "empty root list";
    };
  }

  @Test
  @DisplayName("search: unusable roots never throw and never reject a relative path_prefix")
  void searchDegradesOpen() {
    KnowledgeSearchResponse response = KnowledgeSearchResponseBuilder.builder().tookMs(1).build();
    List<Function<EngineContext, List<BrowseTool.RootInfo>>> suppliers = brokenSuppliers();
    for (int i = 0; i < suppliers.size(); i++) {
      var called = new boolean[1];
      SearchTool.SearchCallback search =
          (req, context) -> {
            called[0] = true;
            return response;
          };
      SearchTool tool = new SearchTool(search, suppliers.get(i));

      OperationResult result =
          tool.execute("{\"query\":\"anything\",\"path_prefix\":\"docs/explanation\"}", EngineContextTestFixtures.AGENT_LOOP);

      assertTrue(result.success(), label(i) + " must not reject: " + result.message());
      assertTrue(called[0], label(i) + " must still reach the index");
    }
  }

  @Test
  @DisplayName("browse: unusable roots never throw and never reject a relative parent_path")
  void browseDegradesOpen() {
    List<Function<EngineContext, List<BrowseTool.RootInfo>>> suppliers = brokenSuppliers();
    for (int i = 0; i < suppliers.size(); i++) {
      var called = new boolean[1];
      BrowseTool.BrowseCallback browse =
          (req, context) -> {
            called[0] = true;
            return new FolderBrowseResponse(
                List.of(new FolderBrowseResponse.Folder("/docs/sub", "sub", 1, 10, 0)), 1, false);
          };
      BrowseTool tool = new BrowseTool(browse, suppliers.get(i));

      OperationResult result = tool.execute("{\"parent_path\":\"docs/explanation\"}", EngineContextTestFixtures.AGENT_LOOP);

      assertTrue(result.success(), label(i) + " must not reject: " + result.message());
      assertTrue(called[0], label(i) + " must still reach the Worker");
    }
  }

  @Test
  @DisplayName("browse: unusable roots leave the top-level listing empty, not thrown")
  void browseTopLevelDegradesOpen() {
    List<Function<EngineContext, List<BrowseTool.RootInfo>>> suppliers = brokenSuppliers();
    for (int i = 0; i < suppliers.size(); i++) {
      BrowseTool.BrowseCallback browse = (req, context) -> new FolderBrowseResponse(List.of(), 0, false);
      BrowseTool tool = new BrowseTool(browse, null, suppliers.get(i));

      OperationResult result = tool.execute("{}", EngineContextTestFixtures.AGENT_LOOP);

      assertNotNull(result, label(i) + " must produce a result, not an exception");
      assertTrue(
          result.message().contains("No indexed folders found"),
          label(i) + " must answer with the empty listing: " + result.message());
    }
  }

  @Test
  @DisplayName("ingest: unusable roots never throw and never block an absolute path")
  void ingestDegradesOpen() throws IOException {
    Path file = tempDir.resolve("note.md");
    Files.writeString(file, "content");
    List<Function<EngineContext, List<BrowseTool.RootInfo>>> suppliers = brokenSuppliers();
    for (int i = 0; i < suppliers.size(); i++) {
      var accepted = new ArrayList<Path>();
      IngestTool.IngestCallback ingest =
          (files, collection, context) -> {
            accepted.addAll(files);
            return new KnowledgeIngestResponse(files.size(), null);
          };
      IngestTool tool = new IngestTool(ingest, localScan(ingest), suppliers.get(i));

      OperationResult result =
          tool.execute(
              "{\"paths\":[\"" + file.toString().replace("\\", "\\\\") + "\"]}", EngineContextTestFixtures.AGENT_LOOP);

      assertTrue(result.success(), label(i) + " must not block an absolute path: " + result.message());
      assertTrue(accepted.contains(file.normalize()), label(i) + " must ingest the named file");
    }
  }

  @Test
  @DisplayName("read-document: unusable roots never throw and never reject a relative path")
  void readDocumentDegradesOpen() {
    List<Function<EngineContext, List<BrowseTool.RootInfo>>> suppliers = brokenSuppliers();
    for (int i = 0; i < suppliers.size(); i++) {
      var seen = new String[1];
      ReadDocumentTool.SliceFetcher fetch =
          (docId, offset, max, context) -> {
            seen[0] = docId;
            return CompletableFuture.completedFuture(
                new DocumentService.DocumentSlice(
                    docId, "page text", Map.of(), true, false, 9, 9, null));
          };
      ReadDocumentTool tool = new ReadDocumentTool(fetch, suppliers.get(i));

      OperationResult result = tool.execute("{\"path\":\"docs/explanation/overview.md\"}", EngineContextTestFixtures.AGENT_LOOP);

      assertTrue(result.success(), label(i) + " must not reject: " + result.message());
      assertFalse(
          result.message().contains("Invalid path"),
          label(i) + " must not produce a rejection message: " + result.message());
      assertNotNull(seen[0], label(i) + " must still reach the Worker fetch");
    }
  }

  /** Mirrors {@code IngestToolTest}'s local-walk stand-in for the Worker-side scan RPC. */
  private static IngestTool.ScanRootCallback localScan(IngestTool.IngestCallback ingest) {
    return (rootPath, collection, excludeGlobs, engineContext) -> {
      List<Path> expanded = new ArrayList<>();
      try (Stream<Path> stream = Files.walk(Path.of(rootPath))) {
        stream.filter(Files::isRegularFile).limit(1000).forEach(expanded::add);
      } catch (IOException e) {
        return new KnowledgeIngestResponse(0, e.getMessage());
      }
      return ingest.ingest(expanded, collection, engineContext);
    };
  }
}
