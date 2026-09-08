/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.engine.ShutdownRequest.Reason;
import io.justsearch.app.services.HeadAssembly;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HeadlessApp ordered shutdown wiring")
final class HeadlessAppShutdownWiringTest {

  @Test
  @DisplayName("every reason configures the inference close before HeadAssembly closes")
  void everyReasonConfiguresInferenceCloseBeforeAssemblyClose() {
    for (Reason reason : Reason.values()) {
      HeadAssembly assembly = mock(HeadAssembly.class);
      var sequence =
          new EngineShutdownSequence(
              Path.of("build", "shutdown-wiring", reason.wire()),
              HeadlessApp.orderedShutdownSteps(
                  null, assembly, null, null, null, null, null, null),
              code -> {});

      sequence.run(reason);

      var order = inOrder(assembly);
      order.verify(assembly).setStopGenerativeBackendOnClose(reason.stopsGenerativeBackend());
      order.verify(assembly).close();
    }
  }
}
