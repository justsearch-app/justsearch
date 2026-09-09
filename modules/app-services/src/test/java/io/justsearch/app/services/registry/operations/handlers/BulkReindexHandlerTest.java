package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.OperationLeaseService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BulkReindexHandler}'s real implementation (slice 429 follow-up).
 *
 * <p>Replaces the prior NOT_IMPLEMENTED-stub tests with delegation tests against a
 * lambda-implemented {@link IndexingService}.
 */
final class BulkReindexHandlerTest {

  /** Tempdoc 542 Phase 3: handlers now take an OperationLeaseService; no-op for tests. */
  private static final OperationLeaseService LEASE = OperationLeaseService.noOp();

  /**
   * Minimal IndexingService base for tests — no-ops every method except the ones the
   * handler under test actually calls. Subclasses override only what they need.
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
  void executeReturnsSuccessWhenIndexingServiceStartsMigration() {
    BulkReindexHandler handler =
        new BulkReindexHandler(
            () ->
                new FakeIndexingService() {
                  @Override
                  public MigrationOutcome startMigration(String reason, io.justsearch.core.context.EngineContext engineContext) {
                    return new MigrationOutcome(true, true);
                  }
                },
            LEASE);

    OperationResult result = handler.execute("{\"corpusIds\":[\"a\",\"b\"]}", io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(result.success());
    assertTrue(result.message().contains("started"));
    assertEquals(true, result.structuredData().get("restartRequired"));
  }

  @Test
  void executeReturnsFailureWhenStartMigrationReturnsFalse() {
    BulkReindexHandler handler =
        new BulkReindexHandler(
            () ->
                new FakeIndexingService() {
                  @Override
                  public MigrationOutcome startMigration(String reason, io.justsearch.core.context.EngineContext engineContext) {
                    return new MigrationOutcome(false, false);
                  }
                },
            LEASE);

    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertTrue(result.message().contains("could not be started"));
  }

  @Test
  void executeReturnsFailureWhenIndexingServiceUnavailable() {
    BulkReindexHandler handler = new BulkReindexHandler(() -> null, LEASE);
    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertTrue(result.message().contains("Indexing service unavailable"));
  }

  @Test
  void executeReturnsFailureWhenStartMigrationThrowsUnsupported() {
    BulkReindexHandler handler =
        new BulkReindexHandler(IndexingService::unavailable, LEASE);
    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertTrue(result.message().contains("Bulk reindex failed"));
  }

  @Test
  void executeHasNoExecutionId() {
    BulkReindexHandler handler =
        new BulkReindexHandler(
            () ->
                new FakeIndexingService() {
                  @Override
                  public MigrationOutcome startMigration(String reason, io.justsearch.core.context.EngineContext engineContext) {
                    return new MigrationOutcome(true, true);
                  }
                },
            LEASE);
    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertEquals(java.util.Optional.empty(), result.executionId());
  }
}
