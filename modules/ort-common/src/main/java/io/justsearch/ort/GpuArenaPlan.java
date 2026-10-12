/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import io.justsearch.configuration.model.ChatModelProfile;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The device-memory plan of Spec E revision 4 §2.2: one activation budget per GPU run, not a share
 * per role. The device seat lets one GPU run happen at a time (Spec D G1) and arena shrinkage
 * returns each run's working memory, so every run may use the whole activation budget:
 *
 * <pre>
 * A_total = D − E − C(plan) − F − N − R
 * A_run   = A_total ÷ deviceSeats
 * P_run   = 0.95 × A_run                  (the packing budget)
 * cap(r)  = w(r) + A_run                  (gpu_mem_limit of every GPU role r)
 * a call  : padded items × f(r, padded length) ≤ P_run, and items ≤ today's count cap for r
 * lateMax = the largest multiple of 512, at most 8,192, with f(embedding, L) ≤ P_run
 * profile = the first plan, in order, whose P_run ≥ A_min(plan)
 * </pre>
 *
 * <p>{@link #resolve} is the rule, a pure function of device facts, installed models, explicit
 * settings and deviceSeats. It never reads free memory, chat state, pressure or start order (S9).
 * The coefficients are the final measured values of M2, M2b and M2c (step E7b), each named below
 * with its source: Spec E revision 4 section 2.2 and its "M2c outcome and rulings" (measure README,
 * section M2c, and raw/M2c/recompute.json).
 *
 * <p>Since step E7b the plan is applied: the Engine resolves it at every configuration build,
 * contributes its caps, chat profile, pinned rung and projector placement at the auto-detected
 * ordinal (where every explicit setting outranks them), and publishes it per device through
 * {@link DeviceArenaPlans} for the packers and in status.
 *
 * @param profile the device's profile
 * @param planned whether caps and call shapes follow the rule; false means today's constants
 * @param chat the chat plan the budget reserves memory for
 * @param budget the terms of the rule; empty when no budget was computed (no GPU, or no plan fits)
 * @param roles the plan of each GPU role, by role
 * @param lateMaxTokens the longest late-chunking input one call may take on a planned profile
 * @param unmetSettings the {@code user_setting_unmet} tokens: explicit settings the plan cannot meet,
 *     each kept as set
 */
public record GpuArenaPlan(
    Profile profile,
    boolean planned,
    Chat chat,
    Optional<Budget> budget,
    Map<EncoderRole, Role> roles,
    int lateMaxTokens,
    Set<String> unmetSettings) {

  // ---------------------------------------------------------------------------------------------
  // Final constants (Spec E revision 4 section 2.2 and the M2c rulings of 2026-10-07).
  // ---------------------------------------------------------------------------------------------

  /** E, the planned external allowance in MiB (Spec E revision 4 section 0). */
  public static final double EXTERNAL_ALLOWANCE_MIB = 1_024;

  /** F, the free margin in MiB (Spec E revision 4 section 0). */
  public static final double FREE_MARGIN_MIB = 512;

  /**
   * N, the encoder process's memory outside the arenas, in MiB. M2b measured 378 and M2c 379.3 on
   * the integrated build; the M2c ruling keeps 400 (E-MEMr checks it in the running Engine).
   */
  public static final double ENGINE_NON_ARENA_MIB = 400;

  /** P_run = PACKING_FRACTION × A_run: a 5% margin for coefficient error (challenge S2). */
  public static final double PACKING_FRACTION = 0.95;

  /** C of P16_PLUS: the 9B at 32k, projector on the GPU; measured maximum under long prompts (M2c). */
  public static final double CHAT_9B_32K_PROJECTOR_GPU_MIB = 7_675;

  /** C of P12: the 9B at 32k, projector on the CPU; measured maximum under long prompts (M2c). */
  public static final double CHAT_9B_32K_MIB = 6_549;

  /** C of the first P8 plan: the 4B at 16k, projector on the CPU; measured maximum (M2c). */
  public static final double CHAT_4B_16K_MIB = 3_703;

  /** C of the second P8 plan: the 4B at 8k, projector on the CPU; measured maximum (M2c). */
  public static final double CHAT_4B_8K_MIB = 3_533;

  /**
   * Chat KV growth between measured context rungs, in MiB per 1,024 tokens: the M2c 4B rungs,
   * (3,703 − 3,533) ÷ 8 = 21.25.
   */
  public static final double CHAT_KV_MIB_PER_1K_TOKENS = (CHAT_4B_16K_MIB - CHAT_4B_8K_MIB) / 8;

  /** Added to file size and KV for a chat model without a measured footprint (§2.4). */
  public static final double UNMEASURED_CHAT_OVERHEAD_MIB = 770;

  /** The chat projector's footprint on the GPU, in MiB: 7,675 − 6,549 for the 9B (M2c). */
  public static final double CHAT_PROJECTOR_MIB =
      CHAT_9B_32K_PROJECTOR_GPU_MIB - CHAT_9B_32K_MIB;

  /** The longest late-chunking input on any profile (today's single-pass limit). */
  public static final int LATE_CHUNKING_LIMIT_TOKENS = 8_192;

  /** lateMax is a multiple of this many tokens. */
  public static final int LATE_CHUNKING_STEP_TOKENS = 512;

  /**
   * Embedding per-item points M2c measured above the fit; each replaces the fit at its length (a
   * measured value above the table replaces it). Late chunking at 2,048, 3,072 and 4,096 tokens;
   * the measured [2, 2,048] batch (610.4) is within 2 × 305.3.
   */
  private static final Map<Integer, Double> EMBEDDING_MEASURED_MIB =
      Map.of(2_048, 305.3, 3_072, 602.0, 4_096, 994.6);

  /** Memory per padded item (M2b fit, M2c points; §2.2 table). BGE-M3 is not measured: none. */
  private static final Map<EncoderRole, Activation> ACTIVATIONS =
      Collections.unmodifiableMap(new EnumMap<>(Map.of(
          // 0.0552 L + 0.0000458 L², raised to the M2c points: 40 at 512, 305.3 at 2,048.
          EncoderRole.EMBEDDING, new Activation(0.0552, 0.0000458, EMBEDDING_MEASURED_MIB),
          // The embedding model's base architecture: 40.3 per pair at 512. M2c measured every
          // reranker point at or below the fit.
          EncoderRole.RERANKER, new Activation(0.0552, 0.0000458),
          // 1.24 L (635 at 512). M2c measured 1 to 4 items at or below it.
          EncoderRole.SPLADE, new Activation(1.24, 0),
          // 0.0514 L (26 at 512). One measured point.
          EncoderRole.NER, new Activation(0.0514, 0))));

  /** Today's count cap per call (Spec D §3.3); the plan only lowers calls below it. */
  private static final Map<EncoderRole, Integer> COUNT_CAPS =
      Collections.unmodifiableMap(new EnumMap<>(Map.of(
          EncoderRole.EMBEDDING, 8,
          EncoderRole.SPLADE, 4,
          EncoderRole.NER, 16,
          EncoderRole.RERANKER, 64,
          EncoderRole.BGE_M3, 4)));

  /** Today's gpu_mem_limit per role in MiB: what every profile without a plan keeps. */
  private static final Map<EncoderRole, Double> TODAYS_CAPS_MIB =
      Collections.unmodifiableMap(new EnumMap<>(Map.of(
          EncoderRole.EMBEDDING, 6_144.0,
          EncoderRole.SPLADE, 4_096.0,
          EncoderRole.NER, 2_048.0,
          EncoderRole.RERANKER, 2_048.0,
          EncoderRole.BGE_M3, 3_072.0)));

  /** The longest padded item of today's calls, per role (tokens). */
  private static final Map<EncoderRole, Integer> LONGEST_ITEM_TOKENS =
      Collections.unmodifiableMap(new EnumMap<>(Map.of(
          EncoderRole.EMBEDDING, 2_048,
          EncoderRole.SPLADE, 512,
          EncoderRole.NER, 512,
          EncoderRole.RERANKER, 512)));

  /** Rounding allowance when the plan's peak is compared with its envelope. */
  private static final double ENVELOPE_TOLERANCE_MIB = 1e-6;

  /** The reranker pads a call's pair count to its smallest batch bucket at least. */
  private static final int RERANKER_SMALLEST_PADDED_PAIRS = 4;

  /**
   * The status reason of BGE-M3 on the CPU: M2c could not measure it, so a plan never puts it on the
   * GPU (Owner decision 9(a), its stated fallback; M2c ruling).
   */
  public static final String BGE_M3_UNMEASURED = "bge_m3_unmeasured";

  /** {@code user_setting_unmet} tokens (Spec E revision 3 §2.4, Q9). */
  public static final String UNMET_CONTEXT = "context";

  public static final String UNMET_LAYERS = "layers";
  public static final String UNMET_CHAT_PROFILE = "chat_profile";
  public static final String UNMET_ENCODER_MEMORY = "encoder_memory";

  /** The chat plans, in the order the resolver tries them (§2.2 "Plans and profiles"). */
  private static final List<ChatPlan> PLANS =
      List.of(
          new ChatPlan(Profile.P16_PLUS, ChatModelProfile.STANDARD, 32_768, true,
              CHAT_9B_32K_PROJECTOR_GPU_MIB),
          new ChatPlan(Profile.P12, ChatModelProfile.STANDARD, 32_768, false, CHAT_9B_32K_MIB),
          new ChatPlan(Profile.P8, ChatModelProfile.COMPACT, 16_384, false, CHAT_4B_16K_MIB),
          new ChatPlan(Profile.P8, ChatModelProfile.COMPACT, 8_192, false, CHAT_4B_8K_MIB));

  /** The device profiles of Spec E. UNRESOLVED (no probe yet) has no plan at all. */
  public enum Profile {
    P16_PLUS,
    P12,
    P8,
    BELOW_P8,
    NO_GPU
  }

  /** Whether an external chat endpoint is configured, and where it runs. */
  public enum ExternalChat {
    NONE,
    /** Configured and not marked remote: it may share the device (a local llama or Ollama). */
    LOCAL,
    /** Configured and explicitly marked remote. */
    MARKED_REMOTE
  }

  public GpuArenaPlan {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(chat, "chat");
    Objects.requireNonNull(budget, "budget");
    roles = Collections.unmodifiableMap(roles.isEmpty()
        ? new EnumMap<>(EncoderRole.class) : new EnumMap<>(roles));
    unmetSettings = Collections.unmodifiableSet(new LinkedHashSet<>(unmetSettings));
    if (lateMaxTokens < 0) throw new IllegalArgumentException("lateMaxTokens must not be negative");
  }

  /** The plan of {@code role}, when the plan has one. */
  public Optional<Role> role(EncoderRole role) {
    return Optional.ofNullable(roles.get(role));
  }

  /**
   * Whether a VDU launch places its projector on the GPU (§2.2 "VDU projector"): only on a planned
   * profile, and only when the plan puts the projector there or when the unused external allowance
   * covers it. It never follows live free memory, which does not show the reserved N + R.
   *
   * @param liveExternalMiB external use of the device measured at VDU entry
   * @param projectorMiB the VDU projector's footprint
   */
  public boolean vduProjectorOnGpu(double liveExternalMiB, double projectorMiB) {
    if (!planned) return false;
    double allowance = budget.map(Budget::externalMiB).orElse(0.0);
    return chat.projectorOnGpu() || allowance - liveExternalMiB >= projectorMiB;
  }

  // ---------------------------------------------------------------------------------------------
  // Records
  // ---------------------------------------------------------------------------------------------

  /**
   * Memory per padded item in MiB: {@code linear × L + quadratic × L²} for a padded length L, or
   * the measured value at L where one was measured above the fit.
   *
   * @param linear MiB per token
   * @param quadratic MiB per token squared
   * @param measuredMiB per-item values measured above the fit, by padded length
   */
  public record Activation(double linear, double quadratic, Map<Integer, Double> measuredMiB) {
    public Activation {
      measuredMiB = Map.copyOf(measuredMiB);
    }

    /** A fit without measured points above it. */
    public Activation(double linear, double quadratic) {
      this(linear, quadratic, Map.of());
    }

    public double perItemMiB(int paddedLength) {
      if (paddedLength < 1) throw new IllegalArgumentException("padded length must be positive");
      double fit = linear * paddedLength + quadratic * (double) paddedLength * paddedLength;
      return Math.max(fit, measuredMiB.getOrDefault(paddedLength, 0.0));
    }
  }

  /**
   * One GPU role's plan.
   *
   * @param weightsMiB w(r), the size of the model file the session loads
   * @param capMiB the role's gpu_mem_limit
   * @param packingMiB the activation one call of the role may use
   * @param activation the role's memory per padded item; empty when it was not measured, in which
   *     case its calls are not packed
   * @param countCap today's count cap per call
   * @param bulkCpuReason why the role's bulk calls run on the CPU, when they do
   * @param explicitCap whether {@code capMiB} is the user's explicit setting, kept as set
   */
  public record Role(
      double weightsMiB,
      double capMiB,
      double packingMiB,
      Optional<Activation> activation,
      int countCap,
      Optional<String> bulkCpuReason,
      boolean explicitCap) {
    public Role {
      Objects.requireNonNull(activation, "activation");
      Objects.requireNonNull(bulkCpuReason, "bulkCpuReason");
      if (countCap < 1) throw new IllegalArgumentException("countCap must be at least 1");
    }
  }

  /**
   * The chat plan memory is reserved for.
   *
   * @param model the managed model the plan launches; empty for today's launch
   * @param contextTokens the pinned top rung; empty for today's ladder
   * @param projectorOnGpu whether the chat projector is placed on the GPU
   * @param footprintMiB C, the chat footprint reserved on the device
   * @param extrapolated whether C is an estimate for a model without a measured footprint
   */
  public record Chat(
      Optional<ChatModelProfile> model,
      OptionalInt contextTokens,
      boolean projectorOnGpu,
      double footprintMiB,
      boolean extrapolated) {}

  /**
   * The terms of the rule, in MiB.
   *
   * @param deviceMiB D, the total memory of the pinned device
   * @param externalMiB E
   * @param chatMiB C
   * @param freeMarginMiB F
   * @param nonArenaMiB N
   * @param weightsMiB R, the weights of every role the plan puts on the GPU
   * @param deviceSeats GPU runs the device allows at once
   */
  public record Budget(
      double deviceMiB,
      double externalMiB,
      double chatMiB,
      double freeMarginMiB,
      double nonArenaMiB,
      double weightsMiB,
      int deviceSeats) {
    public Budget {
      if (deviceSeats < 1) throw new IllegalArgumentException("deviceSeats must be at least 1");
    }

    /** A_total. */
    public double activationTotalMiB() {
      return deviceMiB - externalMiB - chatMiB - freeMarginMiB - nonArenaMiB - weightsMiB;
    }

    /** A_run, the activation one run may use. */
    public double activationPerRunMiB() {
      return activationTotalMiB() / deviceSeats;
    }

    /** P_run, the budget calls are packed against. */
    public double packingMiB() {
      return PACKING_FRACTION * activationPerRunMiB();
    }
  }

  /**
   * The resolver's inputs about the machine. Free memory, chat state (running, on or off),
   * pressure and start order are deliberately not among them (S9).
   *
   * @param deviceTotalMiB D of the pinned device; empty when the probe found no usable device
   * @param gpuRoleWeightsMiB the encoder roles placed on the GPU, each with the size in MiB of the
   *     model file its session actually loads
   * @param installedChatModels the managed chat models installed
   * @param chatModelFileMiB the size of each chat model's file, for a model without a measured
   *     footprint
   * @param externalChat the configured external chat endpoint
   * @param deviceSeats GPU runs the device allows at once (Spec D G1; Owner decision 8)
   */
  public record Facts(
      OptionalDouble deviceTotalMiB,
      Map<EncoderRole, Double> gpuRoleWeightsMiB,
      Set<ChatModelProfile> installedChatModels,
      Map<ChatModelProfile, Double> chatModelFileMiB,
      ExternalChat externalChat,
      int deviceSeats) {
    public Facts {
      Objects.requireNonNull(deviceTotalMiB, "deviceTotalMiB");
      gpuRoleWeightsMiB = Collections.unmodifiableMap(gpuRoleWeightsMiB.isEmpty()
          ? new EnumMap<>(EncoderRole.class) : new EnumMap<>(gpuRoleWeightsMiB));
      installedChatModels = Set.copyOf(installedChatModels);
      chatModelFileMiB = Map.copyOf(chatModelFileMiB);
      Objects.requireNonNull(externalChat, "externalChat");
      if (deviceSeats < 1) throw new IllegalArgumentException("deviceSeats must be at least 1");
      if (gpuRoleWeightsMiB.containsKey(EncoderRole.CITATION)) {
        throw new IllegalArgumentException("the citation scorer never runs on the GPU");
      }
    }
  }

  /**
   * The user's explicit settings (Spec E revision 3 §2.4). Each is kept as set.
   *
   * @param chatProfile an explicit CHAT_PROFILE
   * @param contextTokens an explicit chat context size
   * @param gpuLayers an explicit chat GPU layer count
   * @param roleGpuMemMiB explicit {@code *_GPU_MEM_MB} values, by role. For BGE-M3, which a plan
   *     keeps on the CPU, an explicit {@code bgem3.gpu_enabled=true} counts as an explicit cap at
   *     its effective {@code bgem3.gpu_mem_mb}
   * @param gpuOff the user's GPU-off setting
   * @param projectorOnGpu an explicit chat projector placement ({@code justsearch.mmproj.offload})
   */
  public record Settings(
      Optional<ChatModelProfile> chatProfile,
      OptionalInt contextTokens,
      OptionalInt gpuLayers,
      Map<EncoderRole, Double> roleGpuMemMiB,
      boolean gpuOff,
      Optional<Boolean> projectorOnGpu) {
    public Settings {
      Objects.requireNonNull(chatProfile, "chatProfile");
      Objects.requireNonNull(contextTokens, "contextTokens");
      Objects.requireNonNull(gpuLayers, "gpuLayers");
      roleGpuMemMiB = Map.copyOf(roleGpuMemMiB);
      Objects.requireNonNull(projectorOnGpu, "projectorOnGpu");
    }

    /** Settings without an explicit projector placement. */
    public Settings(
        Optional<ChatModelProfile> chatProfile,
        OptionalInt contextTokens,
        OptionalInt gpuLayers,
        Map<EncoderRole, Double> roleGpuMemMiB,
        boolean gpuOff) {
      this(chatProfile, contextTokens, gpuLayers, roleGpuMemMiB, gpuOff, Optional.empty());
    }

    /** No explicit setting. */
    public static Settings none() {
      return new Settings(Optional.empty(), OptionalInt.empty(), OptionalInt.empty(), Map.of(),
          false);
    }

    private boolean chatExplicit() {
      return chatProfile.isPresent() || contextTokens.isPresent() || gpuLayers.isPresent()
          || projectorOnGpu.isPresent();
    }
  }

  /** One chat plan of the §2.2 table. */
  private record ChatPlan(
      Profile profile, ChatModelProfile model, int contextTokens, boolean projectorOnGpu,
      double footprintMiB) {}

  // ---------------------------------------------------------------------------------------------
  // The rule
  // ---------------------------------------------------------------------------------------------

  /**
   * Resolves the plan for {@code facts} under {@code settings} (Spec E revision 4 §2.2, §2.4).
   *
   * <ul>
   *   <li>GPU off, or no usable device: {@link Profile#NO_GPU}.
   *   <li>Otherwise the first plan, in the table's order, whose P_run reaches its A_min. The 4B
   *       plans need COMPACT installed. When none fits, the profile keeps today's constants: P8
   *       when the device reaches the P8 boundary (the 9B without COMPACT), else BELOW_P8.
   *   <li>C is 0 only when no chat can run on the device: no managed chat model is installed and
   *       no external endpoint is configured unless it is marked remote. Turning chat off does not
   *       change C; chat state is not an input.
   *   <li>Explicit chat settings change C. When the plan then misses its A_min, the result is
   *       USER_SETTING_UNMET with the settings' tokens plus {@code encoder_memory}, and the encoders
   *       keep today's constants.
   *   <li>An explicit GPU memory setting is kept as that role's cap, and the role's calls are packed
   *       against 0.95 × (cap − w). When N + R + deviceSeats × the largest per-role activation
   *       budget exceeds D − E − C − F, the result adds {@code encoder_memory}.
   *   <li>BGE-M3 has no measured activation (M2c could not measure it): a plan never puts it on
   *       the GPU. Its role has cap 0 and a CPU reason, its weights are not in R, and the other
   *       roles keep the plan. An explicit BGE-M3 cap is kept as set, like any explicit cap.
   * </ul>
   */
  public static GpuArenaPlan resolve(Facts facts, Settings settings) {
    Objects.requireNonNull(facts, "facts");
    Objects.requireNonNull(settings, "settings");
    if (settings.gpuOff() || facts.deviceTotalMiB().isEmpty()) {
      Set<String> unmet = new LinkedHashSet<>();
      // The CPU placement veto keeps the preference, but cannot apply positive offload layers.
      settings.gpuLayers().ifPresent(layers -> { if (layers > 0) unmet.add(UNMET_LAYERS); });
      return new GpuArenaPlan(Profile.NO_GPU, false,
          new Chat(settings.chatProfile(),
              OptionalInt.of(settings.contextTokens().orElse(8_192)), false, 0, false),
          Optional.empty(), Map.of(), 0, unmet);
    }
    double device = facts.deviceTotalMiB().getAsDouble();
    double weights = facts.gpuRoleWeightsMiB().entrySet().stream()
        .filter(placed -> onGpuInPlan(placed.getKey(), settings))
        .mapToDouble(Map.Entry::getValue)
        .sum();
    boolean chatCanRun = !facts.installedChatModels().isEmpty()
        || facts.externalChat() == ExternalChat.LOCAL;

    ChatPlan selected = null;
    ChatPlan defaultSelected = null;
    for (ChatPlan plan : PLANS) {
      if (plan.model() == ChatModelProfile.COMPACT
          && !facts.installedChatModels().contains(ChatModelProfile.COMPACT)
          && settings.chatProfile().isEmpty()) {
        continue;
      }
      if (defaultSelected == null
          && budget(device, chatCanRun ? plan.footprintMiB() : 0, weights, facts).packingMiB()
              >= minimumActivation(plan.profile())) {
        defaultSelected = plan;
      }
      Chat candidate = chatFor(plan, settings, facts, chatCanRun);
      Budget budget = budget(device, candidate.footprintMiB(), weights, facts);
      if (budget.packingMiB() >= minimumActivation(plan.profile())) {
        selected = plan;
        break;
      }
    }
    if (selected == null) {
      ChatPlan floor = PLANS.get(PLANS.size() - 1);
      Budget atFloor = budget(device, chatCanRun ? floor.footprintMiB() : 0, weights, facts);
      Profile profile = atFloor.packingMiB() >= minimumActivation(Profile.P8)
          ? Profile.P8 : Profile.BELOW_P8;
      if (settings.chatExplicit()) {
        ChatPlan fallback = defaultSelected != null ? defaultSelected : PLANS.get(1);
        Chat chat = chatFor(fallback, settings, facts, chatCanRun);
        Set<String> unmet = new LinkedHashSet<>();
        settings.contextTokens().ifPresent(ignored -> unmet.add(UNMET_CONTEXT));
        settings.gpuLayers().ifPresent(ignored -> unmet.add(UNMET_LAYERS));
        settings.chatProfile().ifPresent(ignored -> unmet.add(UNMET_CHAT_PROFILE));
        unmet.add(UNMET_ENCODER_MEMORY);
        return todaysConstants(defaultSelected != null ? defaultSelected.profile() : profile, chat,
            Optional.of(budget(device, chat.footprintMiB(), weights, facts)), facts, settings, unmet);
      }
      // No safe rule exists here (§2.2 "The other rows"): today's launch and today's constants.
      return todaysConstants(profile, todaysChat(), Optional.empty(), facts, settings, Set.of());
    }

    Chat chat = chatFor(selected, settings, facts, chatCanRun);
    Budget budget = budget(device, chat.footprintMiB(), weights, facts);
    Set<String> unmet = new LinkedHashSet<>();
    if (settings.chatExplicit() && budget.packingMiB() < minimumActivation(selected.profile())) {
      settings.contextTokens().ifPresent(ignored -> unmet.add(UNMET_CONTEXT));
      settings.gpuLayers().ifPresent(ignored -> unmet.add(UNMET_LAYERS));
      settings.chatProfile().ifPresent(ignored -> unmet.add(UNMET_CHAT_PROFILE));
      unmet.add(UNMET_ENCODER_MEMORY);
      return todaysConstants(selected.profile(), chat, Optional.of(budget), facts, settings, unmet);
    }

    Map<EncoderRole, Role> roles = new EnumMap<>(EncoderRole.class);
    double largestRunBudget = budget.activationPerRunMiB();
    for (Map.Entry<EncoderRole, Double> placed : facts.gpuRoleWeightsMiB().entrySet()) {
      EncoderRole role = placed.getKey();
      double w = placed.getValue();
      Double explicit = settings.roleGpuMemMiB().get(role);
      if (!onGpuInPlan(role, settings)) {
        roles.put(role, new Role(w, 0, 0, Optional.empty(), COUNT_CAPS.get(role),
            Optional.of(BGE_M3_UNMEASURED), false));
        continue;
      }
      double cap = explicit != null ? explicit : w + budget.activationPerRunMiB();
      double packing = explicit != null
          ? PACKING_FRACTION * Math.max(0, explicit - w) : budget.packingMiB();
      if (explicit != null) largestRunBudget = Math.max(largestRunBudget, explicit - w);
      Optional<Activation> activation = Optional.ofNullable(ACTIVATIONS.get(role));
      Optional<String> bulkCpuReason =
          activation.isEmpty() ? Optional.of(BGE_M3_UNMEASURED) : Optional.empty();
      roles.put(role, new Role(w, cap, packing, activation, COUNT_CAPS.get(role), bulkCpuReason,
          explicit != null));
    }
    double envelope = budget.deviceMiB() - budget.externalMiB() - budget.chatMiB()
        - budget.freeMarginMiB();
    // The plan's own peak equals the envelope by construction; only an explicit cap exceeds it.
    if (budget.nonArenaMiB() + budget.weightsMiB() + budget.deviceSeats() * largestRunBudget
        > envelope + ENVELOPE_TOLERANCE_MIB) {
      unmet.add(UNMET_ENCODER_MEMORY);
    }
    Role embedding = roles.get(EncoderRole.EMBEDDING);
    int lateMax = embedding == null ? LATE_CHUNKING_LIMIT_TOKENS : lateMax(embedding);
    return new GpuArenaPlan(selected.profile(), true, chat, Optional.of(budget), roles, lateMax,
        unmet);
  }

  /**
   * A_min (§0): what a plan's P_run must reach. P16_PLUS must fit every one of today's shapes; P12
   * and P8 must fit every role's smallest call, the largest of which is one SPLADE item at 512.
   */
  public static double minimumActivation(Profile profile) {
    return switch (profile) {
      case P16_PLUS -> todaysLargestCall();
      case P12, P8 -> largestSmallestCall();
      case BELOW_P8, NO_GPU -> throw new IllegalArgumentException(profile + " has no plan");
    };
  }

  /**
   * Whether a plan places {@code role} on the GPU: every measured role does; BGE-M3, never measured,
   * only under an explicit cap (M2c ruling on Owner decision 9).
   */
  private static boolean onGpuInPlan(EncoderRole role, Settings settings) {
    return role != EncoderRole.BGE_M3 || settings.roleGpuMemMiB().containsKey(role);
  }

  /**
   * This plan as one session of {@code role} may use it: the session's call budget is limited to
   * 0.95 × (its own cap − w). A plan whose budget falls applies to calls at once; one whose budget
   * rises applies only once the role is recomposed with the higher cap.
   *
   * @param role the session's role
   * @param sessionCapMiB the gpu_mem_limit the session was created with
   */
  public GpuArenaPlan withRoleCapAtMost(EncoderRole role, double sessionCapMiB) {
    Role current = roles.get(role);
    if (current == null || !planned) return this;
    double packing =
        Math.min(current.packingMiB(),
            PACKING_FRACTION * Math.max(0, sessionCapMiB - current.weightsMiB()));
    if (packing == current.packingMiB()) return this;
    Map<EncoderRole, Role> limited = new EnumMap<>(roles);
    limited.put(role, new Role(current.weightsMiB(), current.capMiB(), packing,
        current.activation(), current.countCap(), current.bulkCpuReason(),
        current.explicitCap()));
    int late = role == EncoderRole.EMBEDDING && current.activation().isPresent()
        ? Math.min(lateMaxTokens, lateMax(limited.get(role))) : lateMaxTokens;
    return new GpuArenaPlan(profile, planned, chat, budget, limited, late, unmetSettings);
  }

  private static double todaysLargestCall() {
    double largest =
        ACTIVATIONS.get(EncoderRole.EMBEDDING).perItemMiB(LATE_CHUNKING_LIMIT_TOKENS);
    for (Map.Entry<EncoderRole, Activation> role : ACTIVATIONS.entrySet()) {
      int items = COUNT_CAPS.get(role.getKey());
      largest = Math.max(largest,
          items * role.getValue().perItemMiB(LONGEST_ITEM_TOKENS.get(role.getKey())));
    }
    return largest;
  }

  private static double largestSmallestCall() {
    double largest = 0;
    for (Map.Entry<EncoderRole, Activation> role : ACTIVATIONS.entrySet()) {
      int items = role.getKey() == EncoderRole.RERANKER ? RERANKER_SMALLEST_PADDED_PAIRS : 1;
      largest = Math.max(largest,
          items * role.getValue().perItemMiB(LONGEST_ITEM_TOKENS.get(role.getKey())));
    }
    return largest;
  }

  private static int lateMax(Role embedding) {
    Activation activation = embedding.activation().orElseThrow();
    int limit = 0;
    for (int length = LATE_CHUNKING_STEP_TOKENS; length <= LATE_CHUNKING_LIMIT_TOKENS;
        length += LATE_CHUNKING_STEP_TOKENS) {
      if (activation.perItemMiB(length) > embedding.packingMiB()) break;
      limit = length;
    }
    return limit;
  }

  private static Budget budget(double device, double chatMiB, double weights, Facts facts) {
    return new Budget(device, EXTERNAL_ALLOWANCE_MIB, chatMiB, FREE_MARGIN_MIB,
        ENGINE_NON_ARENA_MIB, weights, facts.deviceSeats());
  }

  /**
   * The chat footprint of {@code plan} with the explicit chat settings applied (§2.4). A rung
   * between measured points is interpolated at {@link #CHAT_KV_MIB_PER_1K_TOKENS}; a model without
   * a measured footprint is estimated as file + KV + {@link #UNMEASURED_CHAT_OVERHEAD_MIB}, marked
   * extrapolated. An explicit layer count keeps the full-offload footprint, an upper bound, and is
   * marked extrapolated as well.
   */
  private static Chat chatFor(ChatPlan plan, Settings settings, Facts facts, boolean chatCanRun) {
    ChatModelProfile model = settings.chatProfile().orElse(plan.model());
    int context = settings.contextTokens().orElse(plan.contextTokens());
    boolean projectorOnGpu = settings.projectorOnGpu().orElse(plan.projectorOnGpu());
    if (!chatCanRun) {
      return new Chat(Optional.of(model), OptionalInt.of(context), projectorOnGpu, 0, false);
    }
    ChatPlan nearest = null;
    for (ChatPlan measured : PLANS) {
      if (measured.model() != model) continue;
      if (nearest == null || Math.abs(measured.contextTokens() - context)
          < Math.abs(nearest.contextTokens() - context)) {
        nearest = measured;
      }
    }
    boolean extrapolated = settings.gpuLayers().isPresent();
    double footprint;
    if (nearest != null) {
      footprint = nearest.footprintMiB()
          + CHAT_KV_MIB_PER_1K_TOKENS * (context - nearest.contextTokens()) / 1_024.0;
      if (nearest.projectorOnGpu() != projectorOnGpu) {
        footprint += projectorOnGpu ? CHAT_PROJECTOR_MIB : -CHAT_PROJECTOR_MIB;
      }
    } else {
      double file = facts.chatModelFileMiB().getOrDefault(model, 0.0);
      footprint = file + CHAT_KV_MIB_PER_1K_TOKENS * context / 1_024.0
          + UNMEASURED_CHAT_OVERHEAD_MIB;
      extrapolated = true;
    }
    return new Chat(Optional.of(model), OptionalInt.of(context), projectorOnGpu, footprint,
        extrapolated);
  }

  /** Today's launch: the 9B, today's ladder top and step-down, the projector on the CPU. */
  private static Chat todaysChat() {
    return new Chat(Optional.empty(), OptionalInt.empty(), false, 0, false);
  }

  /** A profile without a plan: every GPU role keeps today's cap and count cap, unpacked. */
  private static GpuArenaPlan todaysConstants(
      Profile profile, Chat chat, Optional<Budget> budget, Facts facts, Settings settings,
      Set<String> unmet) {
    Map<EncoderRole, Role> roles = new EnumMap<>(EncoderRole.class);
    for (Map.Entry<EncoderRole, Double> placed : facts.gpuRoleWeightsMiB().entrySet()) {
      EncoderRole role = placed.getKey();
      Double explicit = settings.roleGpuMemMiB().get(role);
      double cap = explicit != null ? explicit : TODAYS_CAPS_MIB.get(role);
      roles.put(role, new Role(placed.getValue(), cap, 0, Optional.empty(), COUNT_CAPS.get(role),
          Optional.empty(), explicit != null));
    }
    return new GpuArenaPlan(profile, false, chat, budget, roles, LATE_CHUNKING_LIMIT_TOKENS,
        unmet);
  }
}
