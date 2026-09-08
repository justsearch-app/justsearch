/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.engine.ShutdownRequest.Reason;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.services.HeadAssembly;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("HeadlessApp ordered shutdown wiring")
final class HeadlessAppShutdownWiringTest {

  @Test
  @DisplayName("every reason configures the inference close before HeadAssembly closes")
  void everyReasonConfiguresInferenceCloseBeforeAssemblyClose() {
    for (Reason reason : Reason.values()) {
      HeadAssembly assembly = mock(HeadAssembly.class);
      OperationLeaseService leases = mock(OperationLeaseService.class);
      var sequence =
          new EngineShutdownSequence(
              Path.of("build", "shutdown-wiring", reason.wire()),
              HeadlessApp.orderedShutdownSteps(
                  null, assembly, null, null, null, null, null, null, leases, () -> null),
              code -> {});

      sequence.run(reason);

      var order = inOrder(leases, assembly);
      order.verify(leases).freezeAdmission(reason.wire());
      order.verify(assembly).setStopGenerativeBackendOnClose(reason.stopsGenerativeBackend());
      order.verify(assembly).close();
    }
  }

  @Test
  @DisplayName("boot discards a request left by the prior Engine incarnation")
  void bootDiscardsPreexistingShutdownRequest(@TempDir Path tempDir) throws Exception {
    Path runtime = Files.createDirectories(tempDir.resolve("runtime"));
    new io.justsearch.app.engine.ShutdownRequest(
            Reason.RESTART, Long.MAX_VALUE, null, "prior-incarnation", null)
        .writeTo(runtime);
    var fired = new CountDownLatch(1);

    try (var watcher =
        HeadlessApp.startShutdownRequestWatcher(
            runtime, r -> true, r -> fired.countDown(), 20L, ignored -> {})) {
      assertFalse(fired.await(200, TimeUnit.MILLISECONDS));
    }

    assertFalse(Files.exists(io.justsearch.app.engine.ShutdownRequest.pathIn(runtime)));
  }
}
