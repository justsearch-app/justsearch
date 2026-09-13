package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.IndexingService;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the three slice 445 Operation handlers:
 * {@code core.cancel-indexing-job}, {@code core.retry-indexing-job},
 * {@code core.resolve-path-hash}. Each delegates to {@link IndexingService}
 * via a lazy supplier (closes over volatile field that's null until
 * Worker connects). Tests confirm:
 *
 * <ul>
 *   <li>Missing pathHash → failure (no service call)
 *   <li>Null service → failure
 *   <li>Service returns success-shaped map → success
 *   <li>Service throws UnsupportedOperationException → failure
 * </ul>
 */
@DisplayName("Slice 445 Operation handlers")
final class IndexingJobHandlersTest {

  private static final String VALID_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  // ---- core.cancel-indexing-job ----

  @Test
  @DisplayName("cancel: missing pathHash returns failure")
  void cancelMissingHash() {
    var handler = new CancelIndexingJobHandler(() -> stubIndexing());
    OperationResult r = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(r.success());
    assertTrue(r.message().contains("pathHash"), () -> "got: " + r.message());
  }

  @Test
  @DisplayName("cancel: null service returns failure")
  void cancelNullService() {
    var handler = new CancelIndexingJobHandler(() -> null);
    OperationResult r = handler.execute("{\"pathHash\":\"" + VALID_HASH + "\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(r.success());
    assertTrue(r.message().contains("Indexing service unavailable"));
  }

  @Test
  @DisplayName("cancel: service returns cancelled=true → success")
  void cancelSuccess() {
    var handler =
        new CancelIndexingJobHandler(
            () ->
                new BaseStub() {
                  @Override
                  public Map<String, Object> cancelIndexingJob(String pathHash, io.justsearch.core.context.EngineContext engineContext) {
                    assertEquals(VALID_HASH, pathHash);
                    return Map.of("cancelled", true, "previousState", "PROCESSING");
                  }
                });
    OperationResult r = handler.execute("{\"pathHash\":\"" + VALID_HASH + "\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(r.success());
    assertTrue(r.message().contains("PROCESSING"));
    assertNotNull(r.structuredData());
  }

  @Test
  @DisplayName("cancel: service returns cancelled=false → failure")
  void cancelDeclined() {
    var handler =
        new CancelIndexingJobHandler(
            () ->
                new BaseStub() {
                  @Override
                  public Map<String, Object> cancelIndexingJob(String pathHash, io.justsearch.core.context.EngineContext engineContext) {
                    return Map.of("cancelled", false, "previousState", "UNKNOWN");
                  }
                });
    OperationResult r = handler.execute("{\"pathHash\":\"" + VALID_HASH + "\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(r.success());
    assertTrue(r.message().contains("UNKNOWN"));
  }

  // ---- core.retry-indexing-job ----

  @Test
  @DisplayName("retry: missing pathHash returns failure")
  void retryMissingHash() {
    var handler = new RetryIndexingJobHandler(() -> stubIndexing());
    OperationResult r = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(r.success());
    assertTrue(r.message().contains("pathHash"));
  }

  @Test
  @DisplayName("retry: service returns retried=true → success")
  void retrySuccess() {
    var handler =
        new RetryIndexingJobHandler(
            () ->
                new BaseStub() {
                  @Override
                  public Map<String, Object> retryIndexingJob(String pathHash, io.justsearch.core.context.EngineContext engineContext) {
                    assertEquals(VALID_HASH, pathHash);
                    return Map.of("retried", true, "previousState", "FAILED");
                  }
                });
    OperationResult r = handler.execute("{\"pathHash\":\"" + VALID_HASH + "\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(r.success());
    assertTrue(r.message().contains("FAILED"));
  }

  @Test
  @DisplayName("retry: UnsupportedOperationException → failure with explanatory message")
  void retryDegraded() {
    var handler =
        new RetryIndexingJobHandler(
            () ->
                new BaseStub() {
                  @Override
                  public Map<String, Object> retryIndexingJob(String pathHash, io.justsearch.core.context.EngineContext engineContext) {
                    throw new UnsupportedOperationException("degraded mode");
                  }
                });
    OperationResult r = handler.execute("{\"pathHash\":\"" + VALID_HASH + "\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(r.success());
    assertTrue(r.message().contains("Retry indexing job failed"));
  }

  // ---- core.resolve-path-hash ----

  @Test
  @DisplayName("resolve: returns success with found=true row")
  void resolveFound() {
    var handler =
        new ResolvePathHashHandler(
            () ->
                new BaseStub() {
                  @Override
                  public Map<String, Object> resolvePathHash(String pathHash, io.justsearch.core.context.EngineContext engineContext) {
                    return Map.of(
                        "found", true,
                        "path", "/x/y/z.txt",
                        "lastSeenAtMs", 12345L,
                        "removedAtMs", 0L);
                  }
                });
    OperationResult r = handler.execute("{\"pathHash\":\"" + VALID_HASH + "\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(r.success());
    assertEquals("Path resolved", r.message());
    assertEquals("/x/y/z.txt", r.structuredData().get("path"));
  }

  @Test
  @DisplayName("resolve: not-found is still success (no path on record)")
  void resolveNotFound() {
    var handler =
        new ResolvePathHashHandler(
            () ->
                new BaseStub() {
                  @Override
                  public Map<String, Object> resolvePathHash(String pathHash, io.justsearch.core.context.EngineContext engineContext) {
                    return Map.of("found", false);
                  }
                });
    OperationResult r = handler.execute("{\"pathHash\":\"" + VALID_HASH + "\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(r.success(), "not-found is a valid result, not a failure");
    assertTrue(r.message().contains("No path on record"));
  }

  @Test
  @DisplayName("resolve: invalid JSON arguments → failure")
  void resolveBadJson() {
    var handler = new ResolvePathHashHandler(() -> stubIndexing());
    OperationResult r = handler.execute("not-json", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(r.success());
    assertTrue(r.message().contains("Invalid arguments JSON"));
  }

  private static IndexingService stubIndexing() {
    return new BaseStub();
  }

  /**
   * Base stub providing no-op implementations of {@link IndexingService}'s
   * abstract methods so anon subclasses below only need to override the ones
   * they care about. Mirrors the {@code FakeIndexingService} pattern used
   * elsewhere in this test directory.
   */
  private static class BaseStub implements IndexingService {
    @Override
    public java.util.List<java.nio.file.Path> getWatchedPaths(io.justsearch.core.context.EngineContext engineContext) {
      return java.util.List.of();
    }

    @Override
    public void addWatchedPath(java.nio.file.Path path, io.justsearch.core.context.EngineContext engineContext) {}

    @Override
    public int removeWatchedPath(java.nio.file.Path path, io.justsearch.core.context.EngineContext engineContext) {
      return 0;
    }

    @Override
    public void flush(io.justsearch.core.context.EngineContext engineContext) {}
  }
}
