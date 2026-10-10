/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

import io.justsearch.app.api.status.operating.Profile;
import io.justsearch.app.api.status.operating.ProfileSource;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.resolved.ConfigResolution;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.scheduling.BulkWorkGate;
import io.justsearch.ort.DeviceGatePolicy;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.GpuArenaPlan;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Resolves the device-memory plan from a configuration and the Engine's device facts, and turns it
 * into the configuration it applies (Spec E revision 4 section 2.2 and 2.4, step E7b).
 *
 * <p>The plan's inputs are the pinned device's total memory, the model files of the encoder roles
 * on the GPU, the installed chat models, the external endpoint and the user's explicit settings.
 * A setting is explicit when it resolved above the auto-detected ordinal, so the plan's own
 * contributions (made at that ordinal) never read back as user settings. Free memory, chat state,
 * pressure and start order are not inputs (S9).
 *
 * <p>The plan applies through configuration: every value it sets is contributed at the
 * auto-detected ordinal, where any explicit setting outranks it (P4).
 */
public final class GpuProfileResolver {

  private static final double MIB = 1024.0 * 1024.0;

  /** The per-role GPU memory keys, by role. */
  private static final Map<EncoderRole, String> CAP_KEYS = Map.of(
      EncoderRole.EMBEDDING, "justsearch.embed.gpu_mem_mb",
      EncoderRole.SPLADE, "justsearch.splade.gpu_mem_mb",
      EncoderRole.NER, "justsearch.ner.gpu_mem_mb",
      EncoderRole.RERANKER, "justsearch.rerank.gpu_mem_mb",
      EncoderRole.BGE_M3, "justsearch.bgem3.gpu_mem_mb");

  static final String MEMORY_PLAN_KEY = "justsearch.gpu.memory_plan";
  static final String PROJECTOR_KEY = "justsearch.mmproj.offload";
  static final String CHAT_PROFILE_KEY = "justsearch.chat.profile";
  static final String CONTEXT_KEY = "justsearch.context.size";
  static final String BGE_M3_GPU_KEY = "justsearch.bgem3.gpu_enabled";

  private GpuProfileResolver() {}

  /**
   * One resolution: the plan, or empty while the profile is UNRESOLVED (the device probe has not
   * run, threw, or could not choose a device), and where it came from.
   *
   * @param plan the plan; empty when UNRESOLVED
   * @param source USER_OVERRIDE when an explicit setting changed the plan, else MEASURED; null when
   *     UNRESOLVED
   * @param cudaDeviceId the pinned device's CUDA ordinal the plan is for; -1 when UNRESOLVED
   */
  public record Resolution(Optional<GpuArenaPlan> plan, ProfileSource source, int cudaDeviceId) {
    public Resolution {
      Objects.requireNonNull(plan, "plan");
      if (plan.isPresent() != (source != null)) {
        throw new IllegalArgumentException("a source is present exactly when a plan resolved");
      }
    }

    /** No plan: the profile is UNRESOLVED. */
    public static Resolution unresolved() {
      return new Resolution(Optional.empty(), null, -1);
    }

    /** The status profile (Spec C vocabulary: below P8 reads as the P8 class). */
    public Profile statusProfile() {
      return plan.map(p -> GpuProfileResolver.statusProfile(p.profile())).orElse(Profile.UNRESOLVED);
    }

    /** The bulk-work gate's profile. */
    public BulkWorkGate.Profile gateProfile() {
      return BulkWorkGate.Profile.valueOf(statusProfile().name());
    }

    /**
     * Whether the pinned device is below the P8 boundary: no plan applies and today's constants
     * stay, while the status profile reads P8 (Spec E revision 3 section 2.2, the below-P8 row).
     */
    public boolean belowP8() {
      return plan.map(p -> p.profile() == GpuArenaPlan.Profile.BELOW_P8).orElse(false);
    }

    /** The {@code user_setting_unmet} tokens; empty when every explicit setting is met. */
    public Set<String> unmetSettings() {
      return plan.map(GpuArenaPlan::unmetSettings).orElse(Set.of());
    }
  }

  /**
   * Resolves the plan for {@code cfg}.
   *
   * @param cfg the configuration, built without this plan's contributions or with them (they are
   *     at the auto-detected ordinal and are not read as settings)
   * @param device the Engine's device probe
   * @param gpuRoleWeightsMiB the encoder roles placed on the GPU, with their loaded file sizes
   * @param installedChatModels the managed chat models installed
   */
  public static Resolution resolve(
      ResolvedConfig cfg,
      DeviceFacts device,
      Map<EncoderRole, Double> gpuRoleWeightsMiB,
      Set<ChatModelProfile> installedChatModels) {
    Objects.requireNonNull(cfg, "cfg");
    Objects.requireNonNull(device, "device");
    GpuArenaPlan.Settings settings = settings(cfg);
    OptionalDouble total;
    int ordinal;
    switch (device.status()) {
      case RAN_OK -> {
        if (device.pinned().isEmpty() || device.pinned().get().totalBytes() <= 0) {
          return Resolution.unresolved();
        }
        CudaDevice pinned = device.pinned().get();
        double mib = pinned.totalBytes() / MIB;
        Long ceilingMb = cfg.ai().deviceMemoryCeilingMb();
        if (ceilingMb != null) mib = Math.min(mib, ceilingMb);
        total = OptionalDouble.of(mib);
        ordinal = pinned.ordinal();
      }
      case RAN_NO_DEVICE -> {
        total = OptionalDouble.empty();
        ordinal = 0;
      }
      default -> {
        return Resolution.unresolved();
      }
    }
    String endpoint = cfg.ai().inferenceExternalEndpoint();
    GpuArenaPlan.Facts facts = new GpuArenaPlan.Facts(
        total,
        gpuRoleWeightsMiB,
        installedChatModels,
        chatModelFileMiB(cfg),
        endpoint == null || endpoint.isBlank()
            ? GpuArenaPlan.ExternalChat.NONE : GpuArenaPlan.ExternalChat.LOCAL,
        DeviceGatePolicy.DEFAULTS.seats());
    GpuArenaPlan plan = GpuArenaPlan.resolve(facts, settings);
    boolean overridden = !plan.equals(GpuArenaPlan.resolve(facts, GpuArenaPlan.Settings.none()));
    return new Resolution(Optional.of(plan),
        overridden ? ProfileSource.USER_OVERRIDE : ProfileSource.MEASURED, ordinal);
  }

  /**
   * The user's explicit settings: values resolved above the auto-detected ordinal (Spec E §2.4).
   * BGE-M3 explicitly placed on the GPU counts as an explicit cap at its effective memory setting.
   */
  public static GpuArenaPlan.Settings settings(ResolvedConfig cfg) {
    Optional<ChatModelProfile> chatProfile = explicit(cfg, CHAT_PROFILE_KEY)
        ? Optional.of(ChatModelProfile.resolve(cfg.ai().chatProfile())) : Optional.empty();
    OptionalInt context = explicit(cfg, CONTEXT_KEY) && cfg.ai().contextSize() > 0
        ? OptionalInt.of(cfg.ai().contextSize()) : OptionalInt.empty();
    OptionalInt layers = explicit(cfg, "justsearch.gpu.layers")
        ? OptionalInt.of(cfg.ai().gpuLayers()) : OptionalInt.empty();
    Map<EncoderRole, Double> caps = new EnumMap<>(EncoderRole.class);
    putExplicitCap(cfg, caps, EncoderRole.EMBEDDING, cfg.ai().embedding().gpuMemMb());
    putExplicitCap(cfg, caps, EncoderRole.SPLADE, cfg.ai().splade().gpuMemMb());
    putExplicitCap(cfg, caps, EncoderRole.NER, cfg.ai().ner().gpuMemMb());
    putExplicitCap(cfg, caps, EncoderRole.RERANKER, cfg.ai().reranker().gpuMemMb());
    putExplicitCap(cfg, caps, EncoderRole.BGE_M3, cfg.ai().bgeM3().gpuMemMb());
    if (explicit(cfg, BGE_M3_GPU_KEY) && cfg.ai().bgeM3().gpuEnabled()) {
      caps.putIfAbsent(EncoderRole.BGE_M3, (double) cfg.ai().bgeM3().gpuMemMb());
    }
    boolean gpuOff = !cfg.ai().gpuAccelerationAllowed()
        || (explicit(cfg, "justsearch.gpu.enabled") && !cfg.ai().masterGpuEnabled());
    Optional<Boolean> projector = explicit(cfg, PROJECTOR_KEY)
        ? Optional.of(cfg.ai().mmprojOffload()) : Optional.empty();
    return new GpuArenaPlan.Settings(chatProfile, context, layers, caps, gpuOff, projector);
  }

  /**
   * The configuration the plan applies, to be contributed at the auto-detected ordinal:
   *
   * <ul>
   *   <li>planned profile: {@code justsearch.gpu.memory_plan}, each GPU role's cap (rounded up to
   *       whole MiB), BGE-M3 off the GPU, the 4B's chat profile, the pinned rung, and the
   *       projector's placement;
   *   <li>P8 and below without a plan (today's constants): the projector on the CPU only, so the
   *       launch is today's apart from {@code --no-mmproj-offload};
   *   <li>NO_GPU: the CPU chat tier and its authoritative device selection;
   *   <li>UNRESOLVED: nothing.
   * </ul>
   *
   * @param resolution the plan
   * @param gpuBackedChat whether chat offloads layers to the GPU (the pinned rung applies then)
   */
  public static Map<String, String> contributions(Resolution resolution, boolean gpuBackedChat) {
    Map<String, String> out = new LinkedHashMap<>();
    if (resolution.plan().isEmpty()) return out;
    GpuArenaPlan plan = resolution.plan().get();
    if (plan.profile() == GpuArenaPlan.Profile.NO_GPU) {
      out.put(MEMORY_PLAN_KEY, "no_gpu");
      // Device facts also own automatic availability; a loadable CUDA backend is not a device.
      out.put("justsearch.gpu.enabled", "false");
      out.put("justsearch.gpu.layers", "0");
      out.put(PROJECTOR_KEY, "false");
      plan.chat().contextTokens().ifPresent(t -> out.put(CONTEXT_KEY, String.valueOf(t)));
      return out;
    }
    if (!plan.chat().projectorOnGpu()) out.put(PROJECTOR_KEY, "false");
    if (!plan.planned()) return out;
    out.put(MEMORY_PLAN_KEY, plan.profile().name().toLowerCase(Locale.ROOT));
    for (Map.Entry<EncoderRole, GpuArenaPlan.Role> entry : plan.roles().entrySet()) {
      GpuArenaPlan.Role role = entry.getValue();
      if (role.explicitCap()) continue;
      if (role.capMiB() <= 0) {
        if (entry.getKey() == EncoderRole.BGE_M3) out.put(BGE_M3_GPU_KEY, "false");
        continue;
      }
      String key = CAP_KEYS.get(entry.getKey());
      if (key != null) out.put(key, String.valueOf((long) Math.ceil(role.capMiB())));
    }
    plan.chat().model().filter(m -> m != ChatModelProfile.STANDARD)
        .ifPresent(m -> out.put(CHAT_PROFILE_KEY, m.id()));
    if (gpuBackedChat) {
      plan.chat().contextTokens().ifPresent(t -> out.put(CONTEXT_KEY, String.valueOf(t)));
    }
    return out;
  }

  /**
   * The Engine's planner: the plan of a configuration from the process's device probe, the encoder
   * weights {@code gpuRoleWeightsMiB} gives for it and the chat models installed in its models
   * directory (the one the chat launch resolves its model files in).
   */
  public static io.justsearch.app.services.config.ConfigStoreRebuilder.DevicePlanner planner(
      java.util.function.Supplier<DeviceFacts> device,
      java.util.function.Function<ResolvedConfig, Map<EncoderRole, Double>> gpuRoleWeightsMiB) {
    Objects.requireNonNull(device, "device");
    Objects.requireNonNull(gpuRoleWeightsMiB, "gpuRoleWeightsMiB");
    return cfg -> {
      java.nio.file.Path modelsDir = io.justsearch.configuration.resolved.ResolvedPathResolver
          .resolveModelsDir(cfg, io.justsearch.configuration.resolved.ResolvedPathResolver
              .resolveBaseDir(cfg, io.justsearch.configuration.SystemAccess.sysProp("user.dir")));
      return resolve(cfg, device.get(), gpuRoleWeightsMiB.apply(cfg),
          installedChatModels(modelsDir));
    };
  }

  /** The managed chat models whose model file is installed under {@code modelsDir}. */
  public static Set<ChatModelProfile> installedChatModels(java.nio.file.Path modelsDir) {
    if (modelsDir == null) return Set.of();
    java.util.EnumSet<ChatModelProfile> installed = java.util.EnumSet.noneOf(ChatModelProfile.class);
    for (ChatModelProfile profile : ChatModelProfile.values()) {
      if (java.nio.file.Files.isRegularFile(modelsDir.resolve(profile.modelFile()))) {
        installed.add(profile);
      }
    }
    return installed;
  }

  private static Map<ChatModelProfile, Double> chatModelFileMiB(ResolvedConfig cfg) {
    java.nio.file.Path modelsDir = io.justsearch.configuration.resolved.ResolvedPathResolver
        .resolveModelsDir(cfg, io.justsearch.configuration.resolved.ResolvedPathResolver
            .resolveBaseDir(cfg, io.justsearch.configuration.SystemAccess.sysProp("user.dir")));
    Map<ChatModelProfile, Double> sizes = new EnumMap<>(ChatModelProfile.class);
    for (ChatModelProfile profile : ChatModelProfile.values()) {
      java.nio.file.Path file = modelsDir.resolve(profile.modelFile());
      if (!java.nio.file.Files.isRegularFile(file)) continue;
      try {
        sizes.put(profile, java.nio.file.Files.size(file) / MIB);
      } catch (java.io.IOException failure) {
        throw new java.io.UncheckedIOException("Cannot measure chat model " + file, failure);
      }
    }
    return sizes;
  }

  static Profile statusProfile(GpuArenaPlan.Profile profile) {
    return switch (profile) {
      case P16_PLUS -> Profile.P16_PLUS;
      case P12 -> Profile.P12;
      case P8, BELOW_P8 -> Profile.P8;
      case NO_GPU -> Profile.NO_GPU;
    };
  }

  private static void putExplicitCap(
      ResolvedConfig cfg, Map<EncoderRole, Double> caps, EncoderRole role, int valueMb) {
    if (explicit(cfg, CAP_KEYS.get(role))) caps.put(role, (double) valueMb);
  }

  /** Whether {@code key} resolved from a source above the auto-detected ordinal. */
  static boolean explicit(ResolvedConfig cfg, String key) {
    ConfigResolution resolution = cfg.resolution(key);
    return resolution != null
        && resolution.isResolved()
        && resolution.sourceOrdinal() > ResolvedConfigBuilder.ORDINAL_AUTO_DETECT;
  }
}
