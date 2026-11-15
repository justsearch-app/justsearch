/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.api.IndexingService;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/** M5: both root-list interfaces preserve the bounded disk count's cap, not a searchable count. */
final class IndexingControllerCountPresentationTest {
  @TempDir Path root;

  @Test void bothRootResponsesKeepCountAndCap() throws Exception {
    Files.createFile(root.resolve("one.txt"));
    assertRootCount(1L, false);
    for (int i = 1; i < 50_000; i++) Files.createFile(root.resolve("file-" + i));
    assertRootCount(50_000L, true);
  }

  @Test void anUnreadableSubfolderKeepsTheReadableCountAsALowerBound() throws Exception {
    Files.createFile(root.resolve("one.txt"));
    Files.createFile(root.resolve("two.txt"));
    Path locked = Files.createDirectory(root.resolve("locked"));
    Files.createFile(locked.resolve("hidden.txt"));
    org.junit.jupiter.api.Assumptions.assumeTrue(
        locked.toFile().setReadable(false, false) && !Files.isReadable(locked),
        "needs a file system that can deny reading a folder (not root, not Windows)");
    try {
      assertRootCount(2L, true);
    } finally {
      locked.toFile().setReadable(true, false);
    }
  }

  @Test void missingDiskObservationIsUnknownRatherThanAnEmptyFolder() throws Exception {
    Files.delete(root);
    assertRootCount(-1L, false);
  }

  private void assertRootCount(long count, boolean capped) {
    var indexing = mock(IndexingService.class);
    when(indexing.getWatchedRoots(any(EngineContext.class)))
        .thenReturn(List.of(new IndexingService.WatchedRoot("notes", root)));
    var controller = new IndexingController(() -> indexing, null, root, null);
    var capability = mock(io.justsearch.app.api.lifecycle.Capability.class);
    when(capability.available()).thenReturn(true);
    controller.setWorkerCapability(capability);
    for (boolean substrate : List.of(false, true)) {
      var ctx = mock(Context.class);
      when(ctx.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(TestRequestContexts.browser());
      when(ctx.queryParam("counts")).thenReturn("true");
      if (substrate) controller.handleListRootsSubstrate(ctx); else controller.handleListRoots(ctx);
      var payload = ArgumentCaptor.forClass(Object.class);
      verify(ctx).json(payload.capture());
      @SuppressWarnings("unchecked")
      var body = (Map<String, Object>) payload.getValue();
      @SuppressWarnings("unchecked")
      var items = (List<Map<String, Object>>) body.get(substrate ? "items" : "roots");
      assertEquals(count, items.getFirst().get("fileCount"));
      assertEquals(capped, items.getFirst().get("fileCountCapped"), "do not discard the filesystem count cap");
    }
  }
}
