/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.mockito.Mockito.*;
import io.justsearch.app.api.runtime.containment.EngineContainment;
import io.justsearch.app.api.runtime.containment.WindowsEngineJob;
import io.justsearch.app.api.runtime.power.SystemPowerEvents;
import io.justsearch.contracts.BootContractRunner;
import java.nio.file.*;
import java.util.Optional;

/** Disposable JVM: invoke the real main, fail before any stack/native workload starts. */
public final class HeadlessAppWitnessFailureProbe {
  private HeadlessAppWitnessFailureProbe() {}
  public static void main(String[] args) throws Exception {
    Path dir = Path.of(args[0]);
    System.setProperty("justsearch.data.dir", dir.toString());
    Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
      try { Files.writeString(dir.resolve("normal-exit"), "reached"); }
      catch (java.io.IOException e) { throw new AssertionError(e); }
    }));
    var containment = mock(WindowsEngineJob.class);
    when(containment.status()).thenReturn(EngineContainment.Status.CONTAINED);
    when(containment.unavailableReason()).thenReturn(Optional.empty());
    doThrow(new IllegalStateException("fixture witness storage still active"))
        .when(containment).finishProcessRecording(any());
    try (var installed = mockStatic(EngineContainment.class);
         var power = mockStatic(SystemPowerEvents.class);
         var contracts = mockStatic(BootContractRunner.class)) {
      installed.when(EngineContainment::installAtBoot).thenReturn(containment);
      power.when(SystemPowerEvents::installAtBoot).thenReturn(mock(SystemPowerEvents.class));
      contracts.when(BootContractRunner::validateAll).thenThrow(new IllegalStateException("fixture startup failure"));
      HeadlessApp.main(new String[0]);
    }
  }
}
