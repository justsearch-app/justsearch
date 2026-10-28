package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import io.justsearch.app.services.lease.RecordingOperationLeaseService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 617 §10.8: model install is the primary path by which ~9 GB reaches AI Home, and it runs
 * on a virtual thread that outlives its HTTP request. The request-scoped mutation lease is
 * therefore already released while {@code DownloadExecutor.moveAtomicBestEffort} is still promoting
 * partial files into place, so without a lease of its own {@code POST /api/upgrade/prepare} reports
 * no blocker and the installer can launch mid-download — the outcome D2's "an update never touches
 * models" invariant exists to prevent.
 */
final class AiInstallServiceUpgradeLeaseTest {

  @TempDir Path tmp;

  @Test
  void modelInstallHoldsAnOperationLeaseForTheInstallThreadLifetime() throws Exception {
    AiInstallService svc = spy(new AiInstallService(null, null, null, null, tmp));
    doThrow(new IllegalStateException("fixture preflight failure")).when(svc).getManifest();
    RecordingOperationLeaseService leases = new RecordingOperationLeaseService();
    svc.setOperationLeaseService(leases);

    String caller = Thread.currentThread().getName();
    // Fail before acquisition on every platform; null collaborators permit Linux search downloads.
    svc.startInstall(true);

    assertEquals(
        caller,
        leases.registerThread(),
        "lease must be registered on the calling thread, before the install thread starts — "
            + "otherwise upgrade prepare can observe no blocker while the download is starting");

    long deadline = System.currentTimeMillis() + 10_000;
    while (leases.events().size() < 2 && System.currentTimeMillis() < deadline) {
      Thread.sleep(25);
    }

    assertEquals(2, leases.events().size(), "lease must be released exactly once: " + leases.events());
    assertTrue(
        leases.events().get(1).startsWith("release:"),
        "second event must be the release: " + leases.events());
    assertNotEquals(
        caller,
        leases.releaseThread(),
        "lease must be released by the install thread, not the caller — releasing on the caller "
            + "would end the blocker while the model download is still in flight");
  }
}
