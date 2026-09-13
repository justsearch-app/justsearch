package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.IndexingService.SettleIndexOutcome;
import io.justsearch.app.api.OperationLeaseService;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SettleIndexHandler} (tempdoc 931 §E item 10). Mirrors {@link IndexGcHandlerTest}'s
 * lazy-supplier + FakeIndexingService pattern.
 */
final class SettleIndexHandlerTest {

  private static final OperationLeaseService LEASE = OperationLeaseService.noOp();

  /** Minimal IndexingService base — subclasses override what each test needs. */
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
  void executeAcceptedSurfacesBeforeAndAfterCounts() {
    AtomicReference<Boolean> capturedExpunge = new AtomicReference<>(null);
    AtomicInteger capturedSegments = new AtomicInteger(-1);
    SettleIndexHandler handler =
        new SettleIndexHandler(
            () ->
                new FakeIndexingService() {
                  @Override
                  public SettleIndexOutcome settleIndex(
                      boolean expungeDeletesOnly, int maxSegments, io.justsearch.core.context.EngineContext engineContext) {
                    capturedExpunge.set(expungeDeletesOnly);
                    capturedSegments.set(maxSegments);
                    return new SettleIndexOutcome(true, 2851L, 222L, 222L, 222L, 4, 1234L, "");
                  }
                },
            LEASE);

    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success());
    assertEquals(Boolean.TRUE, capturedExpunge.get(), "default expungeDeletesOnly=true");
    assertEquals(0, capturedSegments.get(), "default maxSegments=0 (worker default)");
    assertEquals(Boolean.TRUE, result.structuredData().get("accepted"));
    assertEquals(2851L, result.structuredData().get("maxDocBefore"));
    assertEquals(222L, result.structuredData().get("numDocsBefore"));
    assertEquals(222L, result.structuredData().get("maxDocAfter"));
    assertEquals(222L, result.structuredData().get("numDocsAfter"));
    assertEquals(4, result.structuredData().get("segmentsAfter"));
    assertEquals(1234L, result.structuredData().get("elapsedMs"));
    assertTrue(result.message().contains("2851"), result.message());
  }

  @Test
  void executePropagatesArgsVerbatim() {
    AtomicReference<Boolean> capturedExpunge = new AtomicReference<>(null);
    AtomicInteger capturedSegments = new AtomicInteger(-1);
    SettleIndexHandler handler =
        new SettleIndexHandler(
            () ->
                new FakeIndexingService() {
                  @Override
                  public SettleIndexOutcome settleIndex(
                      boolean expungeDeletesOnly, int maxSegments, io.justsearch.core.context.EngineContext engineContext) {
                    capturedExpunge.set(expungeDeletesOnly);
                    capturedSegments.set(maxSegments);
                    return new SettleIndexOutcome(true, 10L, 8L, 8L, 8L, 1, 5L, "");
                  }
                },
            LEASE);

    OperationResult result =
        handler.execute("{\"expungeDeletesOnly\": false, \"maxSegments\": 3}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success());
    assertEquals(Boolean.FALSE, capturedExpunge.get());
    assertEquals(3, capturedSegments.get());
  }

  @Test
  void executeRefusalBecomesFailureCarryingTheWorkerReason() {
    SettleIndexHandler handler =
        new SettleIndexHandler(
            () ->
                new FakeIndexingService() {
                  @Override
                  public SettleIndexOutcome settleIndex(
                      boolean expungeDeletesOnly, int maxSegments, io.justsearch.core.context.EngineContext engineContext) {
                    return SettleIndexOutcome.refused("Index migration is MIGRATING");
                  }
                },
            LEASE);

    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertFalse(result.success());
    assertTrue(
        result.message().contains("MIGRATING"),
        "the worker's reason must reach the caller: " + result.message());
  }

  @Test
  void executeRejectsNegativeMaxSegmentsWithoutCallingTheWorker() {
    AtomicInteger calls = new AtomicInteger();
    SettleIndexHandler handler =
        new SettleIndexHandler(
            () ->
                new FakeIndexingService() {
                  @Override
                  public SettleIndexOutcome settleIndex(
                      boolean expungeDeletesOnly, int maxSegments, io.justsearch.core.context.EngineContext engineContext) {
                    calls.incrementAndGet();
                    return new SettleIndexOutcome(true, 0L, 0L, 0L, 0L, 1, 0L, "");
                  }
                },
            LEASE);

    OperationResult result = handler.execute("{\"maxSegments\": -1}", io.justsearch.app.services.TestEngineContexts.internal());

    assertFalse(result.success());
    assertEquals(0, calls.get(), "an invalid argument must never reach the Worker");
  }
}
