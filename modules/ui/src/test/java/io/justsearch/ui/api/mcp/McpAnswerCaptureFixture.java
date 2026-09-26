/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.DocumentService;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.configuration.resolved.ConfigStore;

/** Gives answer tests the same one-operation capture boundary as the live MCP route. */
final class McpAnswerCaptureFixture {
  private McpAnswerCaptureFixture() {}

  static HeadAssembly.ServingCapture bind(HeadAssembly facade, DocumentService documents) {
    var capture = mock(HeadAssembly.ServingCapture.class);
    when(facade.captureServingView()).thenReturn(capture);
    when(capture.documents()).thenReturn(documents);
    when(capture.config()).thenAnswer(ignored -> {
      var store = ConfigStore.globalOrNull();
      return store == null ? null : store.get();
    });
    return capture;
  }
}
