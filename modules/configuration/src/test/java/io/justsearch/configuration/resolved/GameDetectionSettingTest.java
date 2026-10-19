/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.resolved;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Spec E section 4.3, step E4: {@code justsearch.gpu.game_detection} is {@code auto} by default and
 * {@code off} only when said plainly; any other value stays {@code auto}.
 */
final class GameDetectionSettingTest {

  // unset: auto, detection on
  @Test
  void defaultsToAuto() {
    var builder = new ResolvedConfigBuilder();
    builder.contributeEnvRegistry();
    assertEquals("auto", builder.build().ai().gpuGameDetection());
  }

  // off, in any case and with spaces, turns detection off
  @Test
  void offTurnsDetectionOff() {
    assertEquals("off", resolve("  OFF ").gpuGameDetection());
  }

  // anything else is auto: turning detection off must be said plainly
  @Test
  void anyOtherValueIsAuto() {
    for (String raw : new String[] {"false", "0", "disabled", "", "on"}) {
      assertEquals("auto", resolve(raw).gpuGameDetection(), () -> "game_detection='" + raw + "'");
    }
  }

  private static ResolvedConfig.Ai resolve(String raw) {
    var builder = new ResolvedConfigBuilder();
    builder.put("justsearch.gpu.game_detection", ResolvedConfigBuilder.ORDINAL_ENV_VAR, "env_var",
        "JUSTSEARCH_GPU_GAME_DETECTION", raw);
    return builder.build().ai();
  }
}
