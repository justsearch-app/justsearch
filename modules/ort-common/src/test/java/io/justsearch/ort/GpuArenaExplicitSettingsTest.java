/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.model.ChatModelProfile;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** P4 also applies where the default 9B has no fitting measured profile. */
final class GpuArenaExplicitSettingsTest {
  private static final double EPS = 1e-6;

  @ParameterizedTest
  @CsvSource({"6144, BELOW_P8", "10240, P8"})
  void anUnplannedDeviceStillKeepsAndExplainsItsExplicitContext(
      double deviceMiB, GpuArenaPlan.Profile profile) {
    var plan = GpuArenaPlan.resolve(
        GpuArenaPlanTest.facts(deviceMiB, GpuArenaPlanTest.STANDARD_ONLY),
        new GpuArenaPlan.Settings(Optional.empty(), OptionalInt.of(131_072),
            OptionalInt.empty(), Map.of(), false));
    assertEquals(profile, plan.profile(), "the device class cannot be raised by an unmet setting");
    assertFalse(plan.planned());
    assertEquals(OptionalInt.of(131_072), plan.chat().contextTokens(), "keep the explicit window");
    assertEquals(Set.of(GpuArenaPlan.UNMET_CONTEXT, GpuArenaPlan.UNMET_ENCODER_MEMORY),
        plan.unmetSettings(), "the context must also be explained on an already-unplanned device");
  }

  @Test
  void anExplicitCompactChatRepeatsTheProfileRuleWithItsOwnFootprint() {
    GpuArenaPlan plan = GpuArenaPlan.resolve(GpuArenaPlanTest.facts(12_282, GpuArenaPlanTest.WITH_COMPACT),
        new GpuArenaPlan.Settings(Optional.of(ChatModelProfile.COMPACT), OptionalInt.of(16_384),
            OptionalInt.empty(), Map.of(), false, Optional.of(false)));
    assertEquals(GpuArenaPlan.Profile.P16_PLUS, plan.profile(), "the pinned small chat fits all encoder shapes");
    assertTrue(plan.planned());
    assertEquals(3_703, plan.chat().footprintMiB(), EPS);
    assertEquals(4_713, plan.budget().orElseThrow().activationPerRunMiB(), EPS);
    assertEquals(598.3 + 4_713, plan.role(EncoderRole.EMBEDDING).orElseThrow().capMiB(), EPS);
    assertTrue(plan.unmetSettings().isEmpty());
  }

  @Test
  void anExplicitNineBOnACompactSizedDeviceIsKeptAndReportedUnmet() {
    GpuArenaPlan plan = GpuArenaPlan.resolve(GpuArenaPlanTest.facts(10_240, GpuArenaPlanTest.WITH_COMPACT),
        new GpuArenaPlan.Settings(Optional.of(ChatModelProfile.STANDARD), OptionalInt.of(32_768),
            OptionalInt.empty(), Map.of(), false));
    assertFalse(plan.planned());
    assertEquals(Optional.of(ChatModelProfile.STANDARD), plan.chat().model());
    assertEquals(Set.of(GpuArenaPlan.UNMET_CHAT_PROFILE, GpuArenaPlan.UNMET_CONTEXT,
        GpuArenaPlan.UNMET_ENCODER_MEMORY), plan.unmetSettings());
  }

}
