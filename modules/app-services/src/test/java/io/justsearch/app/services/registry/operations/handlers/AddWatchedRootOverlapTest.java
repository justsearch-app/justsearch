/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.IndexingService;
import io.justsearch.app.services.TestEngineContexts;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AddWatchedRootOverlapTest {
  @Test
  void explainsLifecycleRefusalWithStableInvalidRequestCode(@TempDir Path path) {
    var indexing = mock(IndexingService.class);
    String explanation = "Folder overlaps an already watched folder: enclosing. Remove that registration before adding this folder.";
    doThrow(new IllegalArgumentException(explanation)).when(indexing).addWatchedRoot(any(), any(), any());
    String quoted = path.toString().replace("\\", "\\\\");
    var result = new AddWatchedRootHandler(() -> indexing)
        .execute("{\"path\":\"" + quoted + "\"}", TestEngineContexts.internal());
    assertFalse(result.success());
    assertEquals(Optional.of("INVALID_REQUEST"), result.errorCode());
    assertEquals(explanation, result.message());
  }
}
