/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.javalin.http.Context;
import io.justsearch.app.api.IndexingService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PathLabelObservationTest {
  @Test void watchedRootLabelsKeepTheCoreResolverFallbackAndRequestScope() throws Exception {
    var root = Path.of("C:/fixture/watched-folder");
    var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(root.toString().getBytes(StandardCharsets.UTF_8)));
    var indexing = mock(IndexingService.class);
    var context = TestRequestContexts.browser();
    when(indexing.resolvePathHash(hash, context)).thenReturn(Map.of("found", false));
    when(indexing.getWatchedRoots(context)).thenReturn(List.of(new IndexingService.WatchedRoot(null, root)));
    var ctx = request(hash, context);
    new IndexingController(() -> indexing, null, null, null).handlePathLabel(ctx);
    var payload = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(payload.capture());
    assertEquals(true, ((Map<?, ?>) payload.getValue()).get("found"));
    assertEquals(root.toString(), ((Map<?, ?>) payload.getValue()).get("path"));
    verify(indexing).getWatchedRoots(context);
    // A removed root stays not-found, rather than reusing a stale path or a wider scope.
    when(indexing.getWatchedRoots(context)).thenReturn(List.of());
    var removed = request(hash, context);
    new IndexingController(() -> indexing, null, null, null).handlePathLabel(removed);
    verify(removed).json(Map.of("found", false));
  }

  @Test void unavailableResolverIsAnHttpFailureSoHydrationCanRetry() {
    var indexing = mock(IndexingService.class);
    var context = TestRequestContexts.browser();
    when(indexing.resolvePathHash("fixture", context)).thenThrow(new IllegalStateException("unavailable"));
    var ctx = request("fixture", context);
    new IndexingController(() -> indexing, null, null, null).handlePathLabel(ctx);
    verify(ctx).status(503);
  }

  private static Context request(String hash, io.justsearch.core.context.EngineContext context) {
    var ctx = mock(Context.class);
    when(ctx.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(context);
    when(ctx.path()).thenReturn("/api/library/path-label");
    when(ctx.status(anyInt())).thenReturn(ctx);
    when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("pathHash", hash));
    when(ctx.body()).thenReturn("{\"pathHash\":\"" + hash + "\"}");
    return ctx;
  }
}
