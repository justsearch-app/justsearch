/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.configuration.SystemAccess;
import io.justsearch.core.harness.HarnessBarrierProtocol;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.ort.SessionHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** Harness-only hold of the exact native A session after an in-place refusal recomposes it. */
final class RestoredNativeLeaseProbe {
  static final String FAMILY = "restored-a-native-lease";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Logger log = LoggerFactory.getLogger(RestoredNativeLeaseProbe.class);

  private RestoredNativeLeaseProbe() {}

  static void start(Path dataDir, EncoderSet restored) {
    if (!"1".equals(SystemAccess.rawEnvVar("JUSTSEARCH_RESTORED_A_NATIVE_LEASE_PROBE"))) return;
    if (!"1".equals(SystemAccess.rawEnvVar("JUSTSEARCH_SUPERVISOR_HARNESS"))) {
      throw new IllegalStateException("Restored native lease probe requires supervisor harness mode");
    }
    var handles = restored.surfaceForOwner().handles();
    if (handles.isEmpty()) {
      throw new IllegalStateException("Restored A has no native session to hold");
    }
    SessionHandle handle = handles.getFirst();
    Thread.ofPlatform().daemon().name("restored-a-native-lease-probe")
        .start(() -> holdUntilReleased(dataDir, handle));
  }

  private static void holdUntilReleased(Path dataDir, SessionHandle handle) {
    Path proof = dataDir.resolve("runtime").resolve(FAMILY + "-proof.json");
    try (var lease = handle.acquireCpu(SessionAcquisitionRequest.within(
        SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofMinutes(2)))) {
      var inputs = lease.session().getInputNames();
      if (!lease.isCpu() || inputs.isEmpty()) {
        throw new IllegalStateException("Restored A did not issue a readable CPU session");
      }
      HarnessBarrierProtocol.await(dataDir, FAMILY,
          JSON.writeValueAsString(Map.of("pid", ProcessHandle.current().pid(),
              "inputCount", inputs.size())), false);
      // The harness can approve B while its component is RELOADING, before B actually starts
      // retiring A. Hold the issued lease until this exact native handle enters retirement.
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (handle.retirementStatus() == SessionHandle.RetirementStatus.ACTIVE) {
        if (System.nanoTime() >= deadline) {
          throw new IllegalStateException("Restored A native retirement did not begin");
        }
        Thread.sleep(10);
      }
      if (handle.retirementStatus() != SessionHandle.RetirementStatus.RETIRING
          || !inputs.equals(lease.session().getInputNames())) {
        throw new IllegalStateException("Restored A was not held open during B retirement");
      }
      Files.writeString(proof, JSON.writeValueAsString(Map.of("ok", true,
          "retirementStatus", handle.retirementStatus().name(),
          "inputCount", inputs.size())));
    } catch (Exception failure) {
      try {
        Files.createDirectories(proof.getParent());
        Files.writeString(proof, JSON.writeValueAsString(Map.of("ok", false,
            "reason", failure.toString())));
      } catch (Exception markerFailure) {
        failure.addSuppressed(markerFailure);
        log.error("Restored A native lease probe failed without proof marker", failure);
      }
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
    }
  }
}
