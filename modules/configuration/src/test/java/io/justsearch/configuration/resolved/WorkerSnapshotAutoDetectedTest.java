/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.resolved;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Worker still receives {@code justsearch.gpu.layers} once the Head stops mirroring it into a
 * system property (tempdoc 883 decision 4 slice 2).
 *
 * <p>{@code WorkerSpawner.WORKER_FORWARDED_PROPS} forwarded {@code -D} flags via
 * {@code EnvRegistry.get()} (the spawner was deleted at lane F stage A item A11; {@code EnvRegistry}
 * now reads this JVM's own values), which reads the sysprop then the env var — and after slice 2 neither
 * carries a settings-sourced or auto-detected layer count. The value's actual route is the config
 * SNAPSHOT, which the Worker loads at ordinal 450. A static read of
 * {@link ResolvedConfig#toWorkerSnapshot} says every non-null resolution is written; this test is
 * the proof, because the claim is what the whole slice rests on.
 */
@DisplayName("worker snapshot carries auto-detected + settings values with no sysprop")
final class WorkerSnapshotAutoDetectedTest {

  private static final String GPU_LAYERS = "justsearch.gpu.layers";

  @Test
  @DisplayName("a value contributed ONLY by contributeAutoDetected reaches the snapshot")
  void autoDetectedOnlyValueReachesTheSnapshot(@TempDir Path dir) throws IOException {
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.contributeAutoDetected(Map.of(GPU_LAYERS, "99"));
    builder.contributeBaseSources();
    ResolvedConfig config = builder.build();

    assertEquals(
        "auto_detected",
        config.resolution(GPU_LAYERS).sourceName(),
        "precondition: nothing in this environment may be overriding the probe value");

    JsonNode snapshot = snapshotOf(config, dir.resolve("worker-config-snapshot.json"));

    assertTrue(snapshot.has(GPU_LAYERS), "the Worker's only route to the value is this file");
    assertEquals("99", snapshot.get(GPU_LAYERS).asString());
  }

  @Test
  @DisplayName("a settings.json contribution at 300 beats the probe at 150, in the snapshot too")
  void settingsBeatAutoDetectedInTheSnapshot(@TempDir Path dir) throws IOException {
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.contributeAutoDetected(Map.of(GPU_LAYERS, "99"));
    builder.contributeBaseSources();
    builder.putSettings(GPU_LAYERS, "20");
    ResolvedConfig config = builder.build();

    assertEquals("settings.json", config.resolution(GPU_LAYERS).sourceName());
    assertEquals(20, config.ai().gpuLayers());

    JsonNode snapshot = snapshotOf(config, dir.resolve("worker-config-snapshot.json"));

    assertEquals(
        "20",
        snapshot.get(GPU_LAYERS).asString(),
        "the Worker must see the user's number, not the probe's");
  }

  private static JsonNode snapshotOf(ResolvedConfig config, Path path) throws IOException {
    config.toWorkerSnapshot(path);
    return JsonMapper.builder().build().readTree(Files.readString(path));
  }
}
