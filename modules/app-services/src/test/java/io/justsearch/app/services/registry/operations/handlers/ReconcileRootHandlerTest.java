package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.IndexingService;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Tests for {@link ReconcileRootHandler} (tempdoc 626 §Recency, Move C). */
final class ReconcileRootHandlerTest {

  /** Minimal IndexingService base — no-ops every method except the ones under test. */
  private static class FakeIndexingService implements IndexingService {
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
  }

  @Test
  void executeResolvesPathHashAndForcesReconcile() {
    AtomicReference<String> capturedHash = new AtomicReference<>(null);
    AtomicReference<Boolean> capturedForce = new AtomicReference<>(null);
    AtomicBoolean flushed = new AtomicBoolean(false);
    ReconcileRootHandler handler =
        new ReconcileRootHandler(
            () ->
                new FakeIndexingService() {
                  @Override
                  public boolean reconcileRoot(String pathHash, boolean force, io.justsearch.core.context.EngineContext engineContext) {
                    capturedHash.set(pathHash);
                    capturedForce.set(force);
                    return true;
                  }

                  @Override
                  public void flush(io.justsearch.core.context.EngineContext engineContext) {
                    flushed.set(true);
                  }
                });

    OperationResult result = handler.execute("{\"pathHash\":\"abc123\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(result.success());
    assertEquals("abc123", capturedHash.get());
    assertEquals(Boolean.TRUE, capturedForce.get(), "scoped verify always forces a full re-converge");
    assertTrue(flushed.get(), "flush() should be called after the reconcile");
  }

  @Test
  void executeFailsWhenPathHashMissing() {
    ReconcileRootHandler handler = new ReconcileRootHandler(FakeIndexingService::new);
    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertTrue(result.message().contains("pathHash is required"));
  }

  @Test
  void executeFailsWhenNoRootMatches() {
    ReconcileRootHandler handler =
        new ReconcileRootHandler(
            () ->
                new FakeIndexingService() {
                  @Override
                  public boolean reconcileRoot(String pathHash, boolean force, io.justsearch.core.context.EngineContext engineContext) {
                    return false; // no watched root hashes to this value
                  }
                });
    OperationResult result = handler.execute("{\"pathHash\":\"nope\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertTrue(result.message().contains("No watched root"));
  }

  @Test
  void executeReturnsFailureWhenServiceUnavailable() {
    ReconcileRootHandler handler = new ReconcileRootHandler(() -> null);
    OperationResult result = handler.execute("{\"pathHash\":\"abc\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertTrue(result.message().contains("Indexing service unavailable"));
  }

  @Test
  void executeReturnsFailureWhenReconcileThrowsUnsupported() {
    ReconcileRootHandler handler = new ReconcileRootHandler(IndexingService::unavailable);
    OperationResult result = handler.execute("{\"pathHash\":\"abc\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertTrue(result.message().contains("Folder verification failed"));
  }
}
