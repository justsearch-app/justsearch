/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.services.TestEngineContexts;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class RemoveWatchedRootHandlerTest {
  @Test
  void errorSentinelIsIncompleteRemoval() {
    var indexing = mock(IndexingService.class);
    var context = TestEngineContexts.internal();
    when(indexing.removeWatchedRoot("default", Path.of("watched"), context)).thenReturn(-1);
    var result = new RemoveWatchedRootHandler(() -> indexing)
        .execute("{\"path\":\"watched\"}", context);
    assertFalse(result.success());
    assertTrue(result.message().contains("incomplete"));
  }

  @Test
  void admissionRefusalIsPreservedAfterRemovalStarts() {
    var indexing = mock(IndexingService.class);
    var context = TestEngineContexts.internal();
    var refusal = new EngineAdmissionException(EngineAdmissionException.Reason.ENGINE_LIMIT, 3);
    when(indexing.removeWatchedRoot("default", Path.of("watched"), context)).thenThrow(refusal);
    var handler = new RemoveWatchedRootHandler(() -> indexing);
    assertSame(refusal, assertThrows(EngineAdmissionException.class,
        () -> handler.execute("{\"path\":\"watched\"}", context)));
  }

  @Test
  void successfulRemovalIncludesActualDeletedCount() {
    var indexing = mock(IndexingService.class);
    var context = TestEngineContexts.internal();
    when(indexing.removeWatchedRoot("default", Path.of("watched"), context)).thenReturn(4);
    var result = new RemoveWatchedRootHandler(() -> indexing)
        .execute("{\"path\":\"watched\"}", context);
    assertTrue(result.success());
    assertEquals(4, result.structuredData().get("deletedJobs"));
  }

  @Test
  void successfulRemovalRecordsTheFolderItRemovedForTheReceipt() {
    var indexing = mock(IndexingService.class);
    var context = TestEngineContexts.internal();
    when(indexing.removeWatchedRoot("reports", Path.of("watched"), context)).thenReturn(0);
    var result = new RemoveWatchedRootHandler(() -> indexing)
        .execute("{\"path\":\"watched\",\"collection\":\"reports\"}", context);
    assertTrue(result.success());
    assertEquals(new OperationReceipt.RemovedFolder(Path.of("watched").toAbsolutePath().toString(), "reports"),
        result.structuredData().get(OperationReceipt.REMOVED_FOLDER_KEY));
  }

  @Test
  void aDefaultCollectionRemovalRecordsNoCollectionAndAFailureRecordsNoFolder() {
    var indexing = mock(IndexingService.class);
    var context = TestEngineContexts.internal();
    when(indexing.removeWatchedRoot("default", Path.of("watched"), context)).thenReturn(1, -1);
    var handler = new RemoveWatchedRootHandler(() -> indexing);
    var removed = handler.execute("{\"path\":\"watched\"}", context);
    assertEquals(new OperationReceipt.RemovedFolder(Path.of("watched").toAbsolutePath().toString(), null),
        removed.structuredData().get(OperationReceipt.REMOVED_FOLDER_KEY));
    var incomplete = handler.execute("{\"path\":\"watched\"}", context);
    assertFalse(incomplete.structuredData().containsKey(OperationReceipt.REMOVED_FOLDER_KEY));
  }
}
