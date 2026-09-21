/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.lifecycle.LifecycleSnapshotV2;
import io.justsearch.core.component.ComponentState;
import io.justsearch.app.api.runtime.RuntimeContract;
import io.justsearch.app.api.status.GpuStatusView;
import io.justsearch.contract.wire.LifecycleState;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DiagnosticSummaryComposerTest {

  @TempDir Path tempDir;

  private final DiagnosticSummaryComposer composer = new DiagnosticSummaryComposer();

  @Test
  void composesAllowlistedFieldsInDeterministicOrder() {
    var lifecycle =
        new DiagnosticSummaryComposer.LifecycleMetadata(
            new LifecycleSnapshotV2.Lifecycle(
                LifecycleState.LIFECYCLE_STATE_DEGRADED, "worker.lost", "excluded message"),
            new LifecycleSnapshotV2.Components(
                component(ComponentState.READY, null),
                component(ComponentState.UNAVAILABLE, "worker.lost"),
                component(ComponentState.ABSENT, null),
                component(ComponentState.ABSENT, "inference.deactivated")));
    var inputs =
        new DiagnosticSummaryComposer.Inputs(
            "1.2.3",
            RuntimeContract.current(),
            new DiagnosticSummaryComposer.PlatformMetadata("WINDOWS", "11.0", "amd64", "25.0.2"),
            lifecycle,
            new DiagnosticSummaryComposer.GpuMetadata(
                "NVIDIA", "GeForce RTX 4090", "CUDA_FUNCTIONAL"),
            new DiagnosticSummaryComposer.CrashMetadata(
                Instant.parse("2026-09-04T06:00:00Z"),
                "worker",
                "java.lang.IllegalStateException"));

    String first = composer.compose(inputs);
    String second = composer.compose(inputs);

    assertEquals(first, second);
    assertOrdered(
        first,
        "app.version: 1.2.3",
        "runtime-contract.version: ",
        "platform.os-family: WINDOWS",
        "lifecycle.overall.state: LIFECYCLE_STATE_DEGRADED",
        "lifecycle.api.state: READY",
        "lifecycle.index.state: UNAVAILABLE",
        "lifecycle.encoders.state: ABSENT",
        "lifecycle.generative.state: ABSENT",
        "gpu.vendor: NVIDIA",
        "latest-crash.timestamp: 2026-09-04T06:00:00Z",
        "note: " + DiagnosticSummaryComposer.LOCAL_ONLY_NOTE);
    assertFalse(first.contains("excluded message"));
  }

  @Test
  void unknownFreeFormLifecycleReasonsAreOmitted() {
    var lifecycle =
        new DiagnosticSummaryComposer.LifecycleMetadata(
            new LifecycleSnapshotV2.Lifecycle(
                LifecycleState.LIFECYCLE_STATE_DEGRADED,
                "C:\\Users\\Alice\\token=must-not-escape",
                null),
            new LifecycleSnapshotV2.Components(
                component(ComponentState.READY, null),
                component(ComponentState.UNAVAILABLE, null),
                component(ComponentState.ABSENT, null),
                component(ComponentState.ABSENT, null)));

    String summary =
        composer.compose(
            new DiagnosticSummaryComposer.Inputs(null, null, null, lifecycle, null, null));

    assertFalse(summary.contains("must-not-escape"));
    assertFalse(summary.contains("lifecycle.overall.reason"));
  }

  @Test
  void hostileCrashFieldsNeverEscapeAndNewestParseableReportWins() throws Exception {
    Path crashes = Files.createDirectories(tempDir.resolve("crashes"));
    Files.writeString(
        crashes.resolve("crash-head-old.json"),
        """
        {
          "schema":"crash-report.v1",
          "timestamp":"2026-09-01T10:15:30Z",
          "process":"head",
          "pid":99,
          "thread":{"name":"C:\\\\Users\\\\Alice Smith\\\\private.txt"},
          "exception":{
            "type":"java.lang.IllegalArgumentException",
            "message":"token=ghp_must_not_escape",
            "stackTrace":"/home/alice/private/key.pem\\nSECRET_VALUE"
          },
          "environment":{"AUTH_TOKEN":"must-not-escape"}
        }
        """);
    Files.writeString(
        crashes.resolve("crash-worker-new.json"),
        """
        {
          "schema":"crash-report.v1",
          "timestamp":"2026-09-02T10:15:30Z",
          "process":"worker",
          "exception":{"type":"java.lang.OutOfMemoryError","message":"C:\\\\secret"}
        }
        """);

    DiagnosticSummaryComposer.CrashMetadata crash =
        DiagnosticSummaryComposer.latestCrash(crashes);
    String summary =
        composer.compose(
            new DiagnosticSummaryComposer.Inputs(null, null, null, null, null, crash));

    assertEquals("worker", crash.process());
    assertTrue(summary.contains("latest-crash.exception-type: java.lang.OutOfMemoryError"));
    assertFalse(summary.contains("Alice"));
    assertFalse(summary.contains("private"));
    assertFalse(summary.contains("ghp_"));
    assertFalse(summary.contains("SECRET_VALUE"));
    assertFalse(summary.contains("AUTH_TOKEN"));
  }

  @Test
  void malformedUnknownAndOversizedCrashReportsAreOmitted() throws Exception {
    Path crashes = Files.createDirectories(tempDir.resolve("crashes"));
    Files.writeString(crashes.resolve("crash-broken.json"), "{not-json");
    Files.writeString(
        crashes.resolve("crash-hostile.json"),
        """
        {"schema":"crash-report.v1","timestamp":"not-an-instant","process":"head",
         "exception":{"type":"java.lang.IllegalStateException"}}
        """);
    Files.writeString(
        crashes.resolve("crash-unknown-process.json"),
        """
        {"schema":"crash-report.v1","timestamp":"2026-09-03T10:15:30Z",
         "process":"token=must-not-escape","exception":{"type":"java.lang.Error"}}
        """);
    Files.writeString(crashes.resolve("crash-oversized.json"), "x".repeat(70 * 1024));

    assertNull(DiagnosticSummaryComposer.latestCrash(crashes));
  }

  @Test
  void oversizedOpenCrashHandleIsReadOnlyThroughTheBoundedWindow() {
    int ceiling = 64 * 1024;
    ByteArrayInputStream growingHandle =
        new ByteArrayInputStream("x".repeat(ceiling * 2).getBytes(StandardCharsets.UTF_8));
    int availableBefore = growingHandle.available();

    assertNull(DiagnosticSummaryComposer.readCrash(growingHandle));

    assertEquals(ceiling + 1, availableBefore - growingHandle.available());
  }

  @Test
  void sanitizesControlsRejectsPathShapedValuesAndCapsUtf8Payload() {
    String oversizedModel = "GPU\u0000\n" + "界".repeat(10_000);
    var inputs =
        new DiagnosticSummaryComposer.Inputs(
            "C:\\Users\\Alice\\token.txt",
            new RuntimeContract("1.0.0\rsecret", RuntimeContract.current().constituents()),
            new DiagnosticSummaryComposer.PlatformMetadata(
                "WINDOWS", "/home/alice/private", "unknown", "25.0.2"),
            null,
            new DiagnosticSummaryComposer.GpuMetadata(
                "NVIDIA", oversizedModel, "CUDA_AVAILABLE"),
            null);

    String summary = composer.compose(inputs);

    assertTrue(
        summary.getBytes(StandardCharsets.UTF_8).length
            <= DiagnosticSummaryComposer.MAX_UTF8_BYTES);
    assertFalse(summary.contains("C:\\Users"));
    assertFalse(summary.contains("/home/alice"));
    assertFalse(summary.contains("secret"));
    assertFalse(summary.contains("\u0000"));
    assertFalse(summary.contains("\r"));
    assertFalse(summary.contains("\nsecret"));
    assertFalse(summary.contains("platform.architecture"));
    assertTrue(summary.endsWith("note: " + DiagnosticSummaryComposer.LOCAL_ONLY_NOTE + "\n"));
  }

  @Test
  void absentOptionalSnapshotsStillProduceOnlyFixedSafeAuthorities() {
    String summary =
        composer.compose(
            new DiagnosticSummaryComposer.Inputs(
                null, RuntimeContract.current(), null, null, null, null));

    assertFalse(summary.contains("lifecycle."));
    assertFalse(summary.contains("gpu."));
    assertFalse(summary.contains("latest-crash."));
    assertTrue(summary.contains("runtime-contract.version: "));
    assertTrue(summary.contains(DiagnosticSummaryComposer.LOCAL_ONLY_NOTE));
  }

  @Test
  void untypedOptionalStatusSnapshotIsOmitted() {
    Map<String, Object> hostileSnapshot =
        Map.of(
            "lifecycle", "C:\\Users\\Alice\\private",
            "message", "token=must-not-escape");

    assertNull(DiagnosticSummaryComposer.lifecycleFrom(hostileSnapshot));
    assertNull(DiagnosticSummaryComposer.safeGpuFrom(hostileSnapshot));
  }

  @Test
  void safeGpuProjectionOmitsUnavailableAndUnknownModelValues() {
    assertNull(DiagnosticSummaryComposer.safeGpuFrom(GpuStatusView.unavailable()));

    GpuStatusView available =
        new GpuStatusView(
            true,
            null,
            null,
            null,
            null,
            null,
            "excluded-driver-version",
            1,
            new double[0],
            new double[0],
            true,
            "excluded-source",
            "excluded-confidence");

    DiagnosticSummaryComposer.GpuMetadata safe =
        DiagnosticSummaryComposer.safeGpuFrom(available);
    String summary =
        composer.compose(
            new DiagnosticSummaryComposer.Inputs(null, null, null, null, safe, null));

    assertTrue(summary.contains("gpu.vendor: NVIDIA"));
    assertTrue(summary.contains("gpu.capability-tier: CUDA_FUNCTIONAL"));
    assertFalse(summary.contains("gpu.model"));
    assertFalse(summary.contains("excluded-"));
  }

  private static void assertOrdered(String value, String... fragments) {
    int previous = -1;
    for (String fragment : fragments) {
      int current = value.indexOf(fragment);
      assertTrue(current > previous, () -> "missing/out-of-order fragment: " + fragment);
      previous = current;
    }
  }
  private static LifecycleSnapshotV2.Component component(ComponentState state, String reason) {
    return new LifecycleSnapshotV2.Component(state, reason, Instant.EPOCH.toString());
  }

}
