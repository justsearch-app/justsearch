package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.IndexingService;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice 450 §2.1 — head-side fallback resolution against watched roots.
 *
 * <p>Per the calibration finding: the worker's PathResolutionStore only
 * registers per-file hashes. Watched-root parent folders shown by the
 * {@code core.indexed-roots} Resource never reach the worker, so the
 * resolver must fall back to walking watched roots head-side.
 */
@DisplayName("ResolvePathHashHandler")
final class ResolvePathHashHandlerTest {

  private static class FakeIndexingService implements IndexingService {
    private final List<WatchedRoot> roots;
    private final boolean workerKnowsHash;

    FakeIndexingService(List<WatchedRoot> roots, boolean workerKnowsHash) {
      this.roots = roots;
      this.workerKnowsHash = workerKnowsHash;
    }

    @Override
    public List<Path> getWatchedPaths(io.justsearch.core.context.EngineContext engineContext) {
      return roots.stream().map(WatchedRoot::path).toList();
    }

    @Override
    public List<WatchedRoot> getWatchedRoots(io.justsearch.core.context.EngineContext engineContext) {
      return roots;
    }

    @Override
    public Map<String, Object> resolvePathHash(String pathHash, io.justsearch.core.context.EngineContext engineContext) {
      if (workerKnowsHash) {
        Map<String, Object> r = new HashMap<>();
        r.put("found", true);
        r.put("path", "/worker/path/file.txt");
        r.put("lastSeenAtMs", 12345L);
        r.put("removedAtMs", 0L);
        return r;
      }
      return Map.of("found", false);
    }

    @Override
    public void addWatchedPath(Path path, io.justsearch.core.context.EngineContext engineContext) {}

    @Override
    public int removeWatchedPath(Path path, io.justsearch.core.context.EngineContext engineContext) {
      return 0;
    }

    @Override
    public void flush(io.justsearch.core.context.EngineContext engineContext) {}
  }

  private static String sha256Hex(String value) throws Exception {
    var md = java.security.MessageDigest.getInstance("SHA-256");
    return HexFormat.of()
        .formatHex(md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  @Test
  @DisplayName("worker hit returns straight through (no fallback)")
  void workerHitPassesThrough() {
    var handler =
        new ResolvePathHashHandler(
            () -> new FakeIndexingService(List.of(), /* workerKnowsHash */ true));
    OperationResult r = handler.execute("{\"pathHash\":\"deadbeef\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(r.success());
    assertTrue(r.message().equals("Path resolved"));
    assertEquals("/worker/path/file.txt", r.structuredData().get("path"));
  }

  @Test
  @DisplayName("worker miss + matching watched-root falls back head-side (§2.1)")
  void watchedRootFallbackFindsHash() throws Exception {
    Path root = Path.of("F:/JustSearch/scripts/jseval/tmp/eval-corpora/scifact");
    String hash = sha256Hex(root.toString());
    var handler =
        new ResolvePathHashHandler(
            () ->
                new FakeIndexingService(
                    List.of(new IndexingService.WatchedRoot(null, root)),
                    /* workerKnowsHash */ false));
    OperationResult r = handler.execute("{\"pathHash\":\"" + hash + "\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(r.success(), "Expected success, got: " + r.message());
    assertTrue(
        r.message().contains("head-side"),
        "Message must indicate the head-side path was taken: " + r.message());
    assertEquals(true, r.structuredData().get("found"));
    assertEquals(root.toString(), r.structuredData().get("path"));
  }

  @Test
  @DisplayName("worker miss + no matching watched-root returns not-found")
  void watchedRootFallbackMissReturnsNotFound() throws Exception {
    Path root = Path.of("/some/other/dir");
    String unknownHash = sha256Hex("/totally/different/path");
    var handler =
        new ResolvePathHashHandler(
            () ->
                new FakeIndexingService(
                    List.of(new IndexingService.WatchedRoot(null, root)),
                    /* workerKnowsHash */ false));
    OperationResult r = handler.execute("{\"pathHash\":\"" + unknownHash + "\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(r.success(), "non-fatal not-found is still a success result");
    assertEquals(false, r.structuredData().get("found"));
  }

  @Test
  @DisplayName("missing pathHash arg fails with the documented message")
  void missingArgFails() {
    var handler =
        new ResolvePathHashHandler(() -> new FakeIndexingService(List.of(), false));
    OperationResult r = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(r.success());
    assertTrue(r.message().toLowerCase().contains("pathhash"));
  }
}
