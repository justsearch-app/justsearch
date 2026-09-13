package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.IndexingService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Slice 450 §2.3 — Operation handler must reject paths that don't resolve
 * to an existing directory, mirroring the REST handler's
 * {@code Files.isDirectory} check.
 */
@DisplayName("AddWatchedRootHandler")
final class AddWatchedRootHandlerTest {

  private static class CountingIndexingService implements IndexingService {
    int addCalled = 0;
    String lastCollection;

    @Override
    public List<Path> getWatchedPaths(io.justsearch.core.context.EngineContext engineContext) {
      return List.of();
    }

    @Override
    public void addWatchedPath(Path path, io.justsearch.core.context.EngineContext engineContext) {}

    @Override
    public int removeWatchedPath(Path path, io.justsearch.core.context.EngineContext engineContext) {
      return 0;
    }

    @Override
    public void flush(io.justsearch.core.context.EngineContext engineContext) {}

    @Override
    public void addWatchedRoot(String collection, Path path, io.justsearch.core.context.EngineContext engineContext) {
      addCalled++;
      lastCollection = collection;
    }
  }

  @Test
  @DisplayName("non-existent path fails with INVALID_PATH-shaped message; service NOT called (§2.3)")
  void rejectsNonExistentPath() {
    var fake = new CountingIndexingService();
    var handler = new AddWatchedRootHandler(() -> fake);
    OperationResult r =
        handler.execute("{\"path\":\"C:\\\\NonExistentTestPath_xyz_slice450\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(r.success(), "must NOT succeed for missing dir");
    assertTrue(
        r.message().toLowerCase().contains("does not exist")
            || r.message().toLowerCase().contains("not a directory"),
        "Message must indicate the path-not-directory failure: " + r.message());
    assertTrue(
        fake.addCalled == 0,
        "addWatchedRoot must NOT be called for invalid paths");
  }

  @Test
  @DisplayName("existing directory passes validation and reaches the service")
  void acceptsExistingDirectory(@TempDir Path tmp) throws Exception {
    var fake = new CountingIndexingService();
    var handler = new AddWatchedRootHandler(() -> fake);
    String quoted = tmp.toAbsolutePath().toString().replace("\\", "\\\\");
    OperationResult r = handler.execute("{\"path\":\"" + quoted + "\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(r.success(), "Expected success for existing temp dir, got: " + r.message());
    assertTrue(fake.addCalled == 1, "service must be called once");
  }

  @Test
  @DisplayName("regular file (not a directory) is rejected")
  void rejectsRegularFile(@TempDir Path tmp) throws Exception {
    Path file = Files.createFile(tmp.resolve("not-a-dir.txt"));
    var fake = new CountingIndexingService();
    var handler = new AddWatchedRootHandler(() -> fake);
    String quoted = file.toAbsolutePath().toString().replace("\\", "\\\\");
    OperationResult r = handler.execute("{\"path\":\"" + quoted + "\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(r.success());
    assertTrue(fake.addCalled == 0);
  }

  /**
   * Tempdoc 913 D6. {@code POST /api/indexing/roots} has guarded reserved collections since 811
   * (IndexingController routes a supplied value through {@code
   * IngestCollectionPolicy.normalizeRequested}), but THIS handler is the path every UI and agent
   * invocation of {@code core.add-watched-root} takes, and it passed the value straight through.
   * A root tagged {@code agent-history} would have tagged every document its scan admits with the
   * transcript corpus's label — which {@code QueryFilterBuilder.addCollectionScope}
   * default-EXCLUDES, so the folder would index and then be invisible to search.
   *
   * <p>Driven through {@code execute} rather than by calling the policy directly: the policy was
   * already correct and already unit-tested, and the defect was entirely that nothing on this path
   * called it. A test of the policy would have been green throughout.
   */
  @Test
  @DisplayName("913 D6: a reserved collection is rejected and never reaches the service")
  void rejectsReservedCollection(@TempDir Path tmp) {
    for (String reserved :
        io.justsearch.app.api.knowledge.IngestCollectionPolicy.reservedCollections()) {
      var fake = new CountingIndexingService();
      var handler = new AddWatchedRootHandler(() -> fake);
      String quoted = tmp.toAbsolutePath().toString().replace("\\", "\\\\");
      OperationResult r =
          handler.execute("{\"path\":\"" + quoted + "\",\"collection\":\"" + reserved + "\"}", io.justsearch.app.services.TestEngineContexts.internal());

      assertFalse(r.success(), "must reject the reserved collection '" + reserved + "'");
      assertTrue(
          r.message().contains(reserved) && r.message().contains("reserved"),
          "the message must name the offending collection and the reason, as the REST route's does: "
              + r.message());
      assertEquals(
          java.util.Optional.of(io.justsearch.app.api.ApiErrorCode.INVALID_REQUEST.name()),
          r.errorCode(),
          "the Operation-layer spelling of the 400 the REST route returns");
      assertEquals(
          0,
          fake.addCalled,
          "the root must not be created — a rejected collection that still registers the root is"
              + " the same defect wearing an error message");
    }
  }

  @Test
  @DisplayName("913 D6: a reserved name is matched case- and whitespace-insensitively")
  void reservedMatchIsNotLiteral(@TempDir Path tmp) {
    var fake = new CountingIndexingService();
    var handler = new AddWatchedRootHandler(() -> fake);
    String quoted = tmp.toAbsolutePath().toString().replace("\\", "\\\\");
    OperationResult r =
        handler.execute("{\"path\":\"" + quoted + "\",\"collection\":\"  Agent-History \"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(r.success(), "casing and padding must not walk past the guard");
    assertEquals(0, fake.addCalled);
  }

  @Test
  @DisplayName("913 D6: an ordinary collection still reaches the service, trimmed")
  void ordinaryCollectionStillPasses(@TempDir Path tmp) {
    var fake = new CountingIndexingService();
    var handler = new AddWatchedRootHandler(() -> fake);
    String quoted = tmp.toAbsolutePath().toString().replace("\\", "\\\\");
    OperationResult r =
        handler.execute("{\"path\":\"" + quoted + "\",\"collection\":\"  my-notes \"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(r.success(), "a normal collection must be unaffected: " + r.message());
    assertEquals(1, fake.addCalled);
    assertEquals(
        "my-notes",
        fake.lastCollection,
        "the value handed to the service is the policy-normalized one, not the raw arg");
  }
}
