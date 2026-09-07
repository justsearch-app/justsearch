/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.resolved;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.EnvRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 885 item 3 — the forwarding proof.
 *
 * <p>[R1] recorded the failure this test exists to prevent: {@code
 * justsearch.eval.disable_breath_holding} was read with {@code Boolean.getBoolean} <i>inside the
 * Worker JVM</i> while the only setter was a system property on the <i>Head</i>. The key was absent
 * from {@code WorkerSpawner.WORKER_FORWARDED_PROPS}, was not an env var and was not an {@code
 * EnvRegistry} key, so the Worker could never see it and the hatch never fired. (Lane F stage A
 * items A6/A11 removed that process boundary and deleted the spawner; this test keeps the proof
 * because the config route below is what still carries the values, and item A19 is where the
 * snapshot tier itself is decided.)
 *
 * <p>The two pacing keys therefore travel the channel that did cross the process boundary: they
 * resolve onto {@link ResolvedConfig.Ai.BackfillPacing}, the Head writes every resolved value into
 * the worker config snapshot, and the Worker rebuilds its {@code ConfigStore} from that snapshot at
 * ordinal 450. This test walks that whole path rather than asserting a key exists.
 */
final class ForegroundPacingConfigForwardingTest {

  private static final String DUTY_KEY = "justsearch.indexing.foreground_duty_pct";
  private static final String COOLDOWN_KEY = "justsearch.indexing.foreground_cooldown_ms";

  @Test
  @DisplayName("the two keys are declared in EnvRegistry with the documented defaults")
  void keysAreDeclaredWithDefaults() {
    assertEquals(DUTY_KEY, EnvRegistry.INDEXING_FOREGROUND_DUTY_PCT.sysProp());
    assertEquals(
        "JUSTSEARCH_INDEXING_FOREGROUND_DUTY_PCT",
        EnvRegistry.INDEXING_FOREGROUND_DUTY_PCT.envVar());
    assertEquals("20", EnvRegistry.INDEXING_FOREGROUND_DUTY_PCT.defaultValue());

    assertEquals(COOLDOWN_KEY, EnvRegistry.INDEXING_FOREGROUND_COOLDOWN_MS.sysProp());
    assertEquals(
        "JUSTSEARCH_INDEXING_FOREGROUND_COOLDOWN_MS",
        EnvRegistry.INDEXING_FOREGROUND_COOLDOWN_MS.envVar());
    assertEquals("500", EnvRegistry.INDEXING_FOREGROUND_COOLDOWN_MS.defaultValue());
  }

  @Test
  @DisplayName("defaults resolve onto BackfillPacing and match the record's DEFAULTS")
  void defaultsResolveOntoTheRecord() {
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    builder.contributeEnvRegistry();
    ResolvedConfig.Ai.BackfillPacing pacing = builder.build().ai().backfillPacing();

    assertEquals(20, pacing.foregroundDutyPct());
    assertEquals(500L, pacing.foregroundCooldownMs());
    assertEquals(
        ResolvedConfig.Ai.BackfillPacing.DEFAULTS.foregroundDutyPct(), pacing.foregroundDutyPct());
    assertEquals(
        ResolvedConfig.Ai.BackfillPacing.DEFAULTS.foregroundCooldownMs(),
        pacing.foregroundCooldownMs());
  }

  @Test
  @DisplayName("a Head-side override survives the worker snapshot round-trip the Worker reads")
  void overrideReachesTheWorkerThroughTheSnapshot(@TempDir Path tmp) throws IOException {
    // Head side: an operator override at the JVM-arg ordinal.
    ResolvedConfigBuilder head = new ResolvedConfigBuilder();
    head.contributeEnvRegistry();
    head.put(DUTY_KEY, 500, "jvm_arg", DUTY_KEY, "45");
    head.put(COOLDOWN_KEY, 500, "jvm_arg", COOLDOWN_KEY, "1500");
    ResolvedConfig headConfig = head.build();
    assertEquals(45, headConfig.ai().backfillPacing().foregroundDutyPct());

    Path snapshot = tmp.resolve("worker-config-snapshot.json");
    headConfig.toWorkerSnapshot(snapshot);
    assertTrue(Files.exists(snapshot));
    String json = Files.readString(snapshot);
    assertTrue(json.contains(DUTY_KEY), "the key must actually be written to the snapshot");
    assertTrue(json.contains(COOLDOWN_KEY));

    // Worker side: exactly what IndexerWorker does — snapshot at ordinal 450 over EnvRegistry.
    ResolvedConfigBuilder worker = new ResolvedConfigBuilder();
    worker.contributeWorkerSnapshot(snapshot);
    worker.contributeEnvRegistry();
    ResolvedConfig.Ai.BackfillPacing workerPacing = worker.build().ai().backfillPacing();

    assertEquals(
        45,
        workerPacing.foregroundDutyPct(),
        "the Worker must see the Head's duty override, not the default");
    assertEquals(1500L, workerPacing.foregroundCooldownMs());
  }
}
