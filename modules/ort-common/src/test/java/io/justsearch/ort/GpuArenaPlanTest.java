/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ort.GpuArenaPlan.ExternalChat;
import io.justsearch.ort.GpuArenaPlan.Facts;
import io.justsearch.ort.GpuArenaPlan.Profile;
import io.justsearch.ort.GpuArenaPlan.Settings;
import io.justsearch.configuration.model.ChatModelProfile;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Spec E revision 4, check E-U7: the resolver's formula, plan order, C = 0 condition, BGE-M3
 * handling, VDU placement, fit check and USER_SETTING_UNMET (structure rows), and the final
 * coefficients and boundaries of the M2c rulings (number rows, step E7b): C 7,675 / 6,549 / 3,703 /
 * 3,533 MiB, N 400, boundaries 15,253 / 11,084 / 8,238 (16k) / 8,068 (8k).
 *
 * <p>The four default models weigh 1,930 MiB together (M2b); the per-role sizes are the reference
 * caps' weights (598 / 474 / 257 / 600), with the fractions the measured files carry.
 */
final class GpuArenaPlanTest {

  private static final double EPS = 1e-6;

  static final Map<EncoderRole, Double> DEFAULT_WEIGHTS =
      Map.of(
          EncoderRole.EMBEDDING, 598.3,
          EncoderRole.SPLADE, 474.2,
          EncoderRole.NER, 257.2,
          EncoderRole.RERANKER, 600.3);

  static final Set<ChatModelProfile> STANDARD_ONLY = Set.of(ChatModelProfile.STANDARD);
  static final Set<ChatModelProfile> WITH_COMPACT =
      Set.of(ChatModelProfile.STANDARD, ChatModelProfile.COMPACT);

  static Facts facts(double deviceMiB, Set<ChatModelProfile> installed) {
    return facts(deviceMiB, installed, ExternalChat.NONE, DEFAULT_WEIGHTS);
  }

  static Facts facts(
      double deviceMiB,
      Set<ChatModelProfile> installed,
      ExternalChat external,
      Map<EncoderRole, Double> weights) {
    return new Facts(OptionalDouble.of(deviceMiB), weights, installed, Map.of(), external, 1);
  }

  static GpuArenaPlan resolve(double deviceMiB, Set<ChatModelProfile> installed) {
    return GpuArenaPlan.resolve(facts(deviceMiB, installed), Settings.none());
  }

  @Test
  void theMinimumActivationsAreTodaysLargestCallAndTheLargestSmallestCall() {
    // P16_PLUS: late chunking at 8,192 (3,526) is the largest of today's shapes.
    assertEquals(
        0.0552 * 8192 + 0.0000458 * 8192.0 * 8192.0,
        GpuArenaPlan.minimumActivation(Profile.P16_PLUS),
        EPS);
    // P12 and P8: one SPLADE item at 512 (635) is the largest smallest call of any role.
    assertEquals(1.24 * 512, GpuArenaPlan.minimumActivation(Profile.P12), EPS);
    assertEquals(1.24 * 512, GpuArenaPlan.minimumActivation(Profile.P8), EPS);
  }

  @Test
  void theFormulaGivesTheReferenceDevicesTheirBudgetsAndCaps() {
    GpuArenaPlan p12 = resolve(12_282, STANDARD_ONLY);
    assertEquals(Profile.P12, p12.profile());
    assertTrue(p12.planned());
    GpuArenaPlan.Budget budget = p12.budget().orElseThrow();
    // A_total = D − E − C − F − N − R = 12,282 − 1,024 − 6,549 − 512 − 400 − 1,930 (M2c).
    assertEquals(1_867, budget.activationTotalMiB(), EPS);
    assertEquals(1_867, budget.activationPerRunMiB(), EPS);
    assertEquals(1_773.65, budget.packingMiB(), EPS);
    assertEquals(6_549, budget.chatMiB(), EPS);
    assertEquals(400, budget.nonArenaMiB(), EPS);
    assertEquals(1_930, budget.weightsMiB(), EPS);
    // cap(r) = w(r) + A_run: 2,465 / 2,341 / 2,124 / 2,467.
    assertEquals(598.3 + 1_867, p12.role(EncoderRole.EMBEDDING).orElseThrow().capMiB(), EPS);
    assertEquals(474.2 + 1_867, p12.role(EncoderRole.SPLADE).orElseThrow().capMiB(), EPS);
    assertEquals(257.2 + 1_867, p12.role(EncoderRole.NER).orElseThrow().capMiB(), EPS);
    assertEquals(600.3 + 1_867, p12.role(EncoderRole.RERANKER).orElseThrow().capMiB(), EPS);
    for (GpuArenaPlan.Role role : p12.roles().values()) {
      assertEquals(budget.packingMiB(), role.packingMiB(), EPS, "every role packs against P_run");
    }
    assertEquals(5_632, p12.lateMaxTokens());
    assertTrue(p12.unmetSettings().isEmpty());
    // Owner verdict: 12 GB cards run the 9B at 32k with the projector on the CPU.
    assertEquals(Optional.of(ChatModelProfile.STANDARD), p12.chat().model());
    assertEquals(OptionalInt.of(32_768), p12.chat().contextTokens());
    assertFalse(p12.chat().projectorOnGpu());

    GpuArenaPlan p16 = resolve(16_376, STANDARD_ONLY);
    assertEquals(Profile.P16_PLUS, p16.profile());
    assertEquals(7_675, p16.budget().orElseThrow().chatMiB(), EPS);
    assertEquals(4_835, p16.budget().orElseThrow().activationPerRunMiB(), EPS);
    assertEquals(0.95 * 4_835, p16.budget().orElseThrow().packingMiB(), EPS);
    assertEquals(598.3 + 4_835, p16.role(EncoderRole.EMBEDDING).orElseThrow().capMiB(), EPS);
    assertEquals(8_192, p16.lateMaxTokens());
    assertTrue(p16.chat().projectorOnGpu());

    // The 8,188 MiB card takes the 4B at 8k: 16k leaves A_run 619, below the P8 minimum.
    GpuArenaPlan p8 = resolve(8_188, WITH_COMPACT);
    assertEquals(Profile.P8, p8.profile());
    assertTrue(p8.planned());
    assertEquals(3_533, p8.budget().orElseThrow().chatMiB(), EPS);
    assertEquals(789, p8.budget().orElseThrow().activationPerRunMiB(), EPS);
    assertEquals(749.55, p8.budget().orElseThrow().packingMiB(), EPS);
    assertEquals(Optional.of(ChatModelProfile.COMPACT), p8.chat().model());
    assertEquals(OptionalInt.of(8_192), p8.chat().contextTokens());
    assertFalse(p8.chat().projectorOnGpu());
    // cap(r) = w(r) + A_run: 1,387 / 1,263 / 1,046 / 1,389.
    assertEquals(598.3 + 789, p8.role(EncoderRole.EMBEDDING).orElseThrow().capMiB(), EPS);
    assertEquals(474.2 + 789, p8.role(EncoderRole.SPLADE).orElseThrow().capMiB(), EPS);
    assertEquals(257.2 + 789, p8.role(EncoderRole.NER).orElseThrow().capMiB(), EPS);
    assertEquals(600.3 + 789, p8.role(EncoderRole.RERANKER).orElseThrow().capMiB(), EPS);
    assertEquals(3_072, p8.lateMaxTokens());
  }

  @Test
  void theFinalChatFootprintsAndKvSlopeAreTheMeasuredOnes() {
    // C, the measured maximum under long prompts, as each plan reserves it.
    assertEquals(7_675, resolve(16_376, STANDARD_ONLY).chat().footprintMiB(), EPS);
    assertEquals(6_549, resolve(12_282, STANDARD_ONLY).chat().footprintMiB(), EPS);
    assertEquals(3_703, resolve(8_238, WITH_COMPACT).chat().footprintMiB(), EPS);
    assertEquals(3_533, resolve(8_188, WITH_COMPACT).chat().footprintMiB(), EPS);
    assertEquals(400, GpuArenaPlan.ENGINE_NON_ARENA_MIB, EPS);
    // The 4B's 8k to 16k step: 170 MiB over 8k tokens.
    assertEquals(21.25, GpuArenaPlan.CHAT_KV_MIB_PER_1K_TOKENS, EPS);
    assertEquals(1_126, GpuArenaPlan.CHAT_PROJECTOR_MIB, EPS);
  }

  @Test
  void theEmbeddingActivationIsNeverBelowTheMeasuredPoints() {
    GpuArenaPlan.Activation embedding =
        resolve(16_376, STANDARD_ONLY).role(EncoderRole.EMBEDDING).orElseThrow().activation()
            .orElseThrow();
    assertTrue(embedding.perItemMiB(2_048) >= 305.3 - EPS);
    assertTrue(embedding.perItemMiB(3_072) >= 602.0 - EPS);
    assertTrue(embedding.perItemMiB(4_096) >= 994.6 - EPS);
    // Two items at 2,048 measured 610.4 MiB.
    assertTrue(2 * embedding.perItemMiB(2_048) >= 610.4 - EPS);
  }

  @Test
  void theFirstPlanInOrderThatFitsIsTakenAtTheDerivedBoundaries() {
    assertEquals(Profile.P16_PLUS, resolve(15_253, STANDARD_ONLY).profile());
    assertEquals(Profile.P12, resolve(15_252, STANDARD_ONLY).profile());
    assertEquals(Profile.P12, resolve(11_084, STANDARD_ONLY).profile());
    assertEquals(Profile.P8, resolve(11_083, WITH_COMPACT).profile());

    GpuArenaPlan p8At16k = resolve(8_238, WITH_COMPACT);
    assertEquals(Profile.P8, p8At16k.profile());
    assertEquals(OptionalInt.of(16_384), p8At16k.chat().contextTokens());
    GpuArenaPlan p8At8k = resolve(8_237, WITH_COMPACT);
    assertEquals(Profile.P8, p8At8k.profile());
    assertTrue(p8At8k.planned());
    assertEquals(OptionalInt.of(8_192), p8At8k.chat().contextTokens());
    assertTrue(resolve(8_068, WITH_COMPACT).planned());

    GpuArenaPlan belowP8 = resolve(8_067, WITH_COMPACT);
    assertEquals(Profile.BELOW_P8, belowP8.profile());
    assertFalse(belowP8.planned());
  }

  @Test
  void withoutCompactADeviceBelowP12KeepsTodaysConstants() {
    GpuArenaPlan p8With9b = resolve(11_083, STANDARD_ONLY);
    assertEquals(Profile.P8, p8With9b.profile());
    assertFalse(p8With9b.planned(), "no safe rule exists for the 9B on a P8 device");
    assertTodaysConstants(p8With9b);
    assertEquals(Profile.P8, resolve(8_068, STANDARD_ONLY).profile());
    GpuArenaPlan below = resolve(8_067, STANDARD_ONLY);
    assertEquals(Profile.BELOW_P8, below.profile());
    assertTodaysConstants(below);
  }

  @Test
  void noGpuWhenTheGpuIsOffOrNoDeviceIsUsable() {
    Settings gpuOff = new Settings(Optional.empty(), OptionalInt.empty(), OptionalInt.empty(),
        Map.of(), true);
    assertEquals(Profile.NO_GPU, GpuArenaPlan.resolve(facts(16_376, STANDARD_ONLY), gpuOff).profile());
    Facts noDevice = new Facts(OptionalDouble.empty(), DEFAULT_WEIGHTS, STANDARD_ONLY, Map.of(),
        ExternalChat.NONE, 1);
    GpuArenaPlan none = GpuArenaPlan.resolve(noDevice, Settings.none());
    assertEquals(Profile.NO_GPU, none.profile());
    assertFalse(none.planned());
    assertTrue(none.roles().isEmpty());
  }

  @Test
  void chatMemoryIsZeroOnlyWhenNoChatCanRunOnTheDevice() {
    // No managed chat model installed and no endpoint: C = 0.
    GpuArenaPlan noChat =
        GpuArenaPlan.resolve(facts(12_282, Set.of(), ExternalChat.NONE, DEFAULT_WEIGHTS),
            Settings.none());
    assertEquals(0, noChat.budget().orElseThrow().chatMiB(), EPS);
    // An endpoint explicitly marked remote: C = 0.
    GpuArenaPlan remote =
        GpuArenaPlan.resolve(facts(12_282, Set.of(), ExternalChat.MARKED_REMOTE, DEFAULT_WEIGHTS),
            Settings.none());
    assertEquals(0, remote.budget().orElseThrow().chatMiB(), EPS);
    // A local endpoint (a user's own llama or Ollama) keeps the managed plan's C.
    GpuArenaPlan local =
        GpuArenaPlan.resolve(facts(12_282, Set.of(), ExternalChat.LOCAL, DEFAULT_WEIGHTS),
            Settings.none());
    assertEquals(Profile.P12, local.profile());
    assertEquals(6_549, local.budget().orElseThrow().chatMiB(), EPS);
    // An installed managed model keeps C even beside a remote endpoint.
    GpuArenaPlan installed =
        GpuArenaPlan.resolve(
            facts(12_282, STANDARD_ONLY, ExternalChat.MARKED_REMOTE, DEFAULT_WEIGHTS),
            Settings.none());
    assertEquals(6_549, installed.budget().orElseThrow().chatMiB(), EPS);
  }

  /** S9: the resolver's inputs are facts, models, settings and seats; no live state. */
  @Test
  void theResolverReadsNoFreeMemoryChatStatePressureOrStartOrder() {
    List<String> facts =
        Arrays.stream(Facts.class.getRecordComponents()).map(RecordComponent::getName).toList();
    assertEquals(
        List.of("deviceTotalMiB", "gpuRoleWeightsMiB", "installedChatModels", "chatModelFileMiB",
            "externalChat", "deviceSeats", "kvType"),
        facts);
    List<String> settings =
        Arrays.stream(Settings.class.getRecordComponents()).map(RecordComponent::getName).toList();
    assertEquals(
        List.of("chatProfile", "contextTokens", "gpuLayers", "roleGpuMemMiB", "gpuOff",
            "projectorOnGpu"),
        settings);
    // Pure: the same inputs give the same plan.
    assertEquals(resolve(12_282, STANDARD_ONLY), resolve(12_282, STANDARD_ONLY));
  }

  @Test
  void deviceSeatsDivideTheActivationBudget() {
    Facts twoSeats = new Facts(OptionalDouble.of(16_376), DEFAULT_WEIGHTS, STANDARD_ONLY, Map.of(),
        ExternalChat.NONE, 2);
    GpuArenaPlan plan = GpuArenaPlan.resolve(twoSeats, Settings.none());
    GpuArenaPlan.Budget budget = plan.budget().orElseThrow();
    assertEquals(budget.activationTotalMiB() / 2, budget.activationPerRunMiB(), EPS);
    assertEquals(Profile.P12, plan.profile(), "two seats halve A_run, so P16_PLUS no longer fits");
  }

  @Test
  void bgeM3BulkRunsOnTheCpuWithAReasonAndTheOtherRolesKeepThePlan() {
    Map<EncoderRole, Double> weights = new LinkedHashMap<>(DEFAULT_WEIGHTS);
    weights.remove(EncoderRole.EMBEDDING);
    weights.put(EncoderRole.BGE_M3, 1_100.0);
    GpuArenaPlan plan =
        GpuArenaPlan.resolve(facts(16_376, STANDARD_ONLY, ExternalChat.NONE, weights),
            Settings.none());
    assertTrue(plan.planned());
    GpuArenaPlan.Role bgeM3 = plan.role(EncoderRole.BGE_M3).orElseThrow();
    assertEquals(Optional.of(GpuArenaPlan.BGE_M3_UNMEASURED), bgeM3.bulkCpuReason());
    assertTrue(bgeM3.activation().isEmpty(), "no guessed coefficient is used");
    // M2c: BGE-M3 is never on the GPU in a plan, so its weights are not in R and it has no cap.
    assertEquals(0, bgeM3.capMiB(), EPS);
    assertEquals(474.2 + 257.2 + 600.3, plan.budget().orElseThrow().weightsMiB(), EPS);
    for (EncoderRole other : List.of(EncoderRole.SPLADE, EncoderRole.NER, EncoderRole.RERANKER)) {
      GpuArenaPlan.Role role = plan.role(other).orElseThrow();
      assertTrue(role.bulkCpuReason().isEmpty(), other + " keeps the plan");
      assertEquals(plan.budget().orElseThrow().packingMiB(), role.packingMiB(), EPS);
    }
  }

  @Test
  void theVduProjectorFollowsThePlanNeverLiveFreeMemory() {
    assertTrue(resolve(16_376, STANDARD_ONLY).vduProjectorOnGpu(900, 1_126),
        "P16_PLUS places the projector on the GPU");
    GpuArenaPlan p12 = resolve(12_282, STANDARD_ONLY);
    assertFalse(p12.vduProjectorOnGpu(0, 1_126),
        "on P12 the external allowance (1,024) never covers the projector (1,126)");
    assertTrue(p12.vduProjectorOnGpu(0, 1_000),
        "a projector the unused external allowance covers may go on the GPU");
    assertFalse(p12.vduProjectorOnGpu(100, 1_000));
    assertFalse(resolve(11_083, STANDARD_ONLY).vduProjectorOnGpu(0, 100),
        "a profile without a plan never places it from the plan");
  }

  @Test
  void anExplicitRoleCapIsKeptAndItsRoleIsPackedAgainstIt() {
    double smaller = 598.3 + 1_000;
    GpuArenaPlan kept =
        GpuArenaPlan.resolve(
            facts(12_282, STANDARD_ONLY),
            new Settings(Optional.empty(), OptionalInt.empty(), OptionalInt.empty(),
                Map.of(EncoderRole.EMBEDDING, smaller), false));
    GpuArenaPlan.Role embedding = kept.role(EncoderRole.EMBEDDING).orElseThrow();
    assertTrue(embedding.explicitCap());
    assertEquals(smaller, embedding.capMiB(), EPS);
    assertEquals(0.95 * 1_000, embedding.packingMiB(), EPS);
    assertTrue(kept.unmetSettings().isEmpty(), "a smaller explicit cap fits the envelope");

    // The fit check: an explicit (cap − w) above A_run exceeds D − E − C − F.
    double larger = 598.3 + 1_867 + 500;
    GpuArenaPlan unmet =
        GpuArenaPlan.resolve(
            facts(12_282, STANDARD_ONLY),
            new Settings(Optional.empty(), OptionalInt.empty(), OptionalInt.empty(),
                Map.of(EncoderRole.EMBEDDING, larger), false));
    assertEquals(Set.of(GpuArenaPlan.UNMET_ENCODER_MEMORY), unmet.unmetSettings());
    assertEquals(larger, unmet.role(EncoderRole.EMBEDDING).orElseThrow().capMiB(), EPS,
        "the explicit setting is kept as set");
  }

  @Test
  void explicitChatSettingsChangeCAndAPlanTheyBreakIsUserSettingUnmet() {
    // 65,536 tokens on the 9B adds 32 × 21.25 MiB of KV; P12 still fits.
    GpuArenaPlan fits =
        GpuArenaPlan.resolve(
            facts(12_282, STANDARD_ONLY),
            new Settings(Optional.empty(), OptionalInt.of(65_536), OptionalInt.empty(), Map.of(),
                false));
    assertTrue(fits.planned());
    assertEquals(6_549 + 32 * 21.25, fits.budget().orElseThrow().chatMiB(), EPS);

    GpuArenaPlan context =
        GpuArenaPlan.resolve(
            facts(12_282, STANDARD_ONLY),
            new Settings(Optional.empty(), OptionalInt.of(131_072), OptionalInt.empty(), Map.of(),
                false));
    assertEquals(Profile.P12, context.profile(), "the profile is kept");
    assertFalse(context.planned());
    assertEquals(
        Set.of(GpuArenaPlan.UNMET_CONTEXT, GpuArenaPlan.UNMET_ENCODER_MEMORY),
        context.unmetSettings());
    assertTodaysConstants(context);

    GpuArenaPlan profile =
        GpuArenaPlan.resolve(
            facts(8_188, WITH_COMPACT),
            new Settings(Optional.of(ChatModelProfile.STANDARD), OptionalInt.empty(),
                OptionalInt.of(99), Map.of(), false));
    assertEquals(Profile.P8, profile.profile());
    assertFalse(profile.planned());
    assertEquals(
        Set.of(GpuArenaPlan.UNMET_LAYERS, GpuArenaPlan.UNMET_CHAT_PROFILE,
            GpuArenaPlan.UNMET_ENCODER_MEMORY),
        profile.unmetSettings());
    assertTodaysConstants(profile);
  }

  @Test
  void aChatModelWithoutAMeasuredFootprintIsEstimatedAndMarkedExtrapolated() {
    Facts withPaddle =
        new Facts(OptionalDouble.of(16_376), DEFAULT_WEIGHTS,
            Set.of(ChatModelProfile.STANDARD, ChatModelProfile.PADDLE_OCR_VL),
            Map.of(ChatModelProfile.PADDLE_OCR_VL, 1_000.0), ExternalChat.NONE, 1);
    GpuArenaPlan plan =
        GpuArenaPlan.resolve(
            withPaddle,
            new Settings(Optional.of(ChatModelProfile.PADDLE_OCR_VL), OptionalInt.empty(),
                OptionalInt.empty(), Map.of(), false));
    assertTrue(plan.chat().extrapolated());
    // file + KV at the plan's 32k rung + 770.
    assertEquals(1_000 + 21.25 * 32 + 770, plan.chat().footprintMiB(), EPS);
  }

  private static void assertTodaysConstants(GpuArenaPlan plan) {
    assertFalse(plan.planned());
    assertEquals(GpuArenaPlan.LATE_CHUNKING_LIMIT_TOKENS, plan.lateMaxTokens());
    assertEquals(6_144, plan.role(EncoderRole.EMBEDDING).orElseThrow().capMiB(), EPS);
    assertEquals(4_096, plan.role(EncoderRole.SPLADE).orElseThrow().capMiB(), EPS);
    assertEquals(2_048, plan.role(EncoderRole.NER).orElseThrow().capMiB(), EPS);
    assertEquals(2_048, plan.role(EncoderRole.RERANKER).orElseThrow().capMiB(), EPS);
    for (GpuArenaPlan.Role role : plan.roles().values()) {
      assertTrue(role.activation().isEmpty(), "calls are not packed without a plan");
    }
  }
}
