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

/** Tests for {@link ReindexHandler} (slice 3a-1-2 closure). */
final class ReindexHandlerTest {

  /**
   * Minimal IndexingService base — no-ops every method except the ones the handler
   * under test actually calls. Subclasses override only what they need.
   */
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
  void executeCallsReindexWatchedRootsWithForceFalseByDefault() {
    AtomicReference<Boolean> capturedForce = new AtomicReference<>(null);
    AtomicBoolean flushed = new AtomicBoolean(false);
    ReindexHandler handler =
        new ReindexHandler(
            () ->
                new FakeIndexingService() {
                  @Override
                  public void reindexWatchedRoots(boolean force, io.justsearch.core.context.EngineContext engineContext) {
                    capturedForce.set(force);
                  }

                  @Override
                  public void flush(io.justsearch.core.context.EngineContext engineContext) {
                    flushed.set(true);
                  }
                });

    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(result.success());
    assertEquals(Boolean.FALSE, capturedForce.get());
    assertTrue(flushed.get(), "flush() should be called after reindex");
    assertTrue(result.message().toLowerCase().contains("reindex"));
  }

  @Test
  void executeRespectsForceTrueArg() {
    AtomicReference<Boolean> capturedForce = new AtomicReference<>(null);
    ReindexHandler handler =
        new ReindexHandler(
            () ->
                new FakeIndexingService() {
                  @Override
                  public void reindexWatchedRoots(boolean force, io.justsearch.core.context.EngineContext engineContext) {
                    capturedForce.set(force);
                  }
                });

    OperationResult result = handler.execute("{\"force\":true}", io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(result.success());
    assertEquals(Boolean.TRUE, capturedForce.get());
    assertTrue(result.message().contains("force"));
  }

  @Test
  void executeRespectsForceFalseArg() {
    AtomicReference<Boolean> capturedForce = new AtomicReference<>(null);
    ReindexHandler handler =
        new ReindexHandler(
            () ->
                new FakeIndexingService() {
                  @Override
                  public void reindexWatchedRoots(boolean force, io.justsearch.core.context.EngineContext engineContext) {
                    capturedForce.set(force);
                  }
                });

    OperationResult result = handler.execute("{\"force\":false}", io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(result.success());
    assertEquals(Boolean.FALSE, capturedForce.get());
  }

  @Test
  void executeTreatsMalformedJsonAsForceFalse() {
    AtomicReference<Boolean> capturedForce = new AtomicReference<>(null);
    ReindexHandler handler =
        new ReindexHandler(
            () ->
                new FakeIndexingService() {
                  @Override
                  public void reindexWatchedRoots(boolean force, io.justsearch.core.context.EngineContext engineContext) {
                    capturedForce.set(force);
                  }
                });

    OperationResult result = handler.execute("not-json", io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(result.success());
    assertEquals(Boolean.FALSE, capturedForce.get());
  }

  @Test
  void executeReturnsFailureWhenServiceUnavailable() {
    ReindexHandler handler = new ReindexHandler(() -> null);
    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertTrue(result.message().contains("Indexing service unavailable"));
  }

  @Test
  void executeReturnsFailureWhenReindexThrowsUnsupported() {
    ReindexHandler handler = new ReindexHandler(IndexingService::unavailable);
    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertTrue(result.message().contains("Reindex failed"));
  }

  @Test
  void executeReturnsFailureWhenReindexThrowsRuntime() {
    ReindexHandler handler =
        new ReindexHandler(
            () ->
                new FakeIndexingService() {
                  @Override
                  public void reindexWatchedRoots(boolean force, io.justsearch.core.context.EngineContext engineContext) {
                    throw new RuntimeException("boom");
                  }
                });

    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertTrue(result.message().contains("boom"));
  }
}
