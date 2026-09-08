package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the four boundary cases of {@link HeadlessApp#augmentGpuAutoDetectionAndMirrorProbeFlags} (tempdoc 374
 * alpha.13 follow-up Phases E + F).
 *
 * <p>Without this test, my prior round of "wrong-gate" mistakes — A1's {@code layers > 0} proxy
 * (no-op for default install) and A3's claim about VRAM-tier auto-population (the function I cited
 * doesn't even write {@code gpu_layers}) — would still be possible: a static read can be wrong, a
 * test exercising the actual logic can't.
 */
final class HeadlessAppGpuAutoPopulateTest {

  // Sysprop keys the augment method writes / reads. Mirrored here so a key
  // rename in EnvRegistry triggers a test compile failure.
  private static final String GPU_ENABLED_KEY = "justsearch.gpu.enabled";
  private static final String GPU_LAYERS_KEY = "justsearch.gpu.layers";
  private static final String LLM_GPU_LAYERS_KEY = "justsearch.llm.gpu_layers";
  private static final String ORT_NATIVE_PATH_KEY = "justsearch.onnxruntime.native_path";

  private final Map<String, String> savedSysprops = new HashMap<>();

  @BeforeEach
  void saveSysprops() {
    for (String key :
        new String[] {GPU_ENABLED_KEY, GPU_LAYERS_KEY, LLM_GPU_LAYERS_KEY, ORT_NATIVE_PATH_KEY}) {
      savedSysprops.put(key, System.getProperty(key));
      System.clearProperty(key);
    }
  }

  @AfterEach
  void restoreSysprops() {
    for (var entry : savedSysprops.entrySet()) {
      if (entry.getValue() == null) {
        System.clearProperty(entry.getKey());
      } else {
        System.setProperty(entry.getKey(), entry.getValue());
      }
    }
  }

  /**
   * Phase F happy path: probe says CUDA available, NVML reports 12 GB VRAM (above the 7.5 GB
   * threshold), no user override → gpu.layers="99" lands in the augmented MAP and in no sysprop.
   * Phase E side effect: gpu.enabled is sysprop-mirrored.
   */
  @Test
  void autoDetectedGpu_with12gbVram_populatesGpuLayersToFullOffload() {
    Map<String, String> autoDetected = new LinkedHashMap<>();
    autoDetected.put(GPU_ENABLED_KEY, "true");
    LongSupplier twelveGbVram = () -> 12L * 1024 * 1024 * 1024;

    Map<String, String> result =
        HeadlessApp.augmentGpuAutoDetectionAndMirrorProbeFlags(autoDetected, twelveGbVram);

    // The augmented map carries gpu.layers=99 so contributeAutoDetected propagates
    // it at ord-150 alongside gpu.enabled.
    assertEquals("99", result.get(GPU_LAYERS_KEY), "augmented map should contain gpu.layers=99");
    // Phase E: gpu.enabled mirrored to sysprop, so it survives ConfigStoreRebuilder.
    // (The mirror's second reason — -D forwarding to the Worker child — went with the
    // child process at lane F stage A item A11.)
    assertEquals("true", System.getProperty(GPU_ENABLED_KEY));
    // Tempdoc 883 decision 4 slice 2: this used to assert "99" here too. The mirror is DELETED.
    // A sysprop puts this derived probe number at ordinal 500, above the user's own setting at
    // 300 — masked only while the settings→sysprop promotion ran first and setSysPropIfBlank
    // no-opped. With that promotion gone, mirroring would silently override the user's choice.
    // Ordinal 150 via the map (kept across rebuilds by rememberAutoDetected) is the honest slot.
    assertNull(
        System.getProperty(GPU_LAYERS_KEY),
        "the probe's layer count must not be written as a system property");
    // Regression guard (tempdoc 799 §N.2): justsearch.llm.gpu_layers was a DEAD DUPLICATE —
    // resolved and documented, but read by nothing once rc.llm().llmGpuLayers was removed.
    // This previously asserted "99" because both keys were mirrored. Asserting null now keeps
    // the duplicate from being re-introduced; justsearch.gpu.layers is the one live key.
    assertNull(System.getProperty(LLM_GPU_LAYERS_KEY));
  }

  /**
   * The precedence the deleted mirror would have inverted, asserted on the RESOLVED value rather
   * than on the absence of a sysprop.
   *
   * <p>"No sysprop" alone would also pass on a version that dropped the auto-detected value
   * entirely, so this runs the real Phase F, feeds its map to a real {@link ResolvedConfigBuilder}
   * at ordinal 150 alongside the user's {@code UiSettings} at 300, and demands the USER's number
   * with the USER's source. Re-add {@code setSysPropIfBlank("justsearch.gpu.layers", …)} to Phase F
   * and {@code contributeEnvRegistry} picks it up at ordinal 500: value becomes 99, source
   * {@code jvm_arg}, and this fails on both.
   */
  @Test
  void probeLayersDoNotOutrankTheUsersSetting() {
    Map<String, String> probe = new LinkedHashMap<>();
    probe.put(GPU_ENABLED_KEY, "true");

    Map<String, String> autoDetected =
        HeadlessApp.augmentGpuAutoDetectionAndMirrorProbeFlags(probe, () -> 12L * 1024 * 1024 * 1024);
    assertEquals("99", autoDetected.get(GPU_LAYERS_KEY), "Phase F must have produced a value");

    UiSettings settings = new UiSettings();
    settings.setGpuLayers(20);

    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.contributeAutoDetected(autoDetected);
    builder.contributeEnvRegistry(); // ordinal 500/400 — would see a mirrored sysprop if one existed
    ConfigStoreRebuilder.contributeUiSettings(builder, settings);
    ResolvedConfig resolved = builder.build();

    assertEquals(20, resolved.ai().gpuLayers(), "the user's 20 must win over the probe's 99");
    assertEquals(
        "settings.json",
        resolved.resolution("justsearch.gpu.layers").sourceName(),
        "and it must be REPORTED as the GUI setting it is, never as jvm_arg");
  }

  /**
   * Idempotency: if the user has already set {@code justsearch.gpu.layers=0} (e.g. via env
   * {@code JUSTSEARCH_GPU_LAYERS=0} mirrored by HeadlessApp's earlier code path), the augment must
   * NOT overwrite it. Otherwise an explicit "force CPU" gets silently flipped to "force GPU".
   */
  @Test
  void userExplicitGpuLayersZero_isPreserved() {
    System.setProperty(GPU_LAYERS_KEY, "0");
    Map<String, String> autoDetected = new LinkedHashMap<>();
    autoDetected.put(GPU_ENABLED_KEY, "true");
    LongSupplier twelveGbVram = () -> 12L * 1024 * 1024 * 1024;

    Map<String, String> result =
        HeadlessApp.augmentGpuAutoDetectionAndMirrorProbeFlags(autoDetected, twelveGbVram);

    // User's explicit 0 stands. The augmented map does NOT contain gpu.layers.
    assertNull(result.get(GPU_LAYERS_KEY), "augmented map must not override explicit user value");
    assertEquals("0", System.getProperty(GPU_LAYERS_KEY));
    // llm.gpu_layers also untouched (Phase F skipped entirely when user set either key).
    assertNull(System.getProperty(LLM_GPU_LAYERS_KEY));
  }

  /**
   * Wrong-gate guard: if the user has explicitly disabled GPU via
   * {@code justsearch.gpu.enabled=false}, the auto-populate must skip even when the probe says
   * CUDA is available. The user wins.
   */
  @Test
  void userExplicitGpuDisabled_skipsAutoPopulateEvenWhenProbeSaysAvailable() {
    System.setProperty(GPU_ENABLED_KEY, "false");
    Map<String, String> autoDetected = new LinkedHashMap<>();
    autoDetected.put(GPU_ENABLED_KEY, "true"); // probe disagrees with user
    LongSupplier twelveGbVram = () -> 12L * 1024 * 1024 * 1024;

    Map<String, String> result =
        HeadlessApp.augmentGpuAutoDetectionAndMirrorProbeFlags(autoDetected, twelveGbVram);

    // No gpu.layers in augmented map (Phase F skipped because shouldUseGpu=false).
    assertNull(result.get(GPU_LAYERS_KEY));
    // No sysprops written for gpu.layers / llm.gpu_layers.
    assertNull(System.getProperty(GPU_LAYERS_KEY));
    assertNull(System.getProperty(LLM_GPU_LAYERS_KEY));
    // Phase E mirror also skips gpu.enabled because user already has a sysprop value.
    assertEquals(
        "false",
        System.getProperty(GPU_ENABLED_KEY),
        "user's explicit false must not be overwritten by autoDetected true");
  }

  /**
   * Threshold guard: VRAM below {@link HardwareProfile#MINIMUM_VRAM_FOR_GGUF} (7.5 GB) means
   * Qwen3.5-9B Q4_K_M ({@code ~5.5 GB} weights plus context) wouldn't fit safely. Auto-populate
   * skips; user can still force layers via env var.
   */
  @Test
  void belowVramThreshold_skipsAutoPopulate() {
    Map<String, String> autoDetected = new LinkedHashMap<>();
    autoDetected.put(GPU_ENABLED_KEY, "true");
    LongSupplier fourGbVram = () -> 4L * 1024 * 1024 * 1024; // < 7.5 GB threshold

    Map<String, String> result =
        HeadlessApp.augmentGpuAutoDetectionAndMirrorProbeFlags(autoDetected, fourGbVram);

    assertNull(result.get(GPU_LAYERS_KEY), "below-threshold VRAM must not auto-populate layers");
    assertNull(System.getProperty(GPU_LAYERS_KEY));
    assertNull(System.getProperty(LLM_GPU_LAYERS_KEY));
    // gpu.enabled mirror still happens — Phase E is independent of VRAM tier.
    assertEquals("true", System.getProperty(GPU_ENABLED_KEY));
  }

  /**
   * Pin the threshold to the canonical {@link HardwareProfile#MINIMUM_VRAM_FOR_GGUF} constant —
   * if someone tunes the threshold there (e.g. for a smaller chat model), this test will surface
   * the coupling and force a coordinated update.
   */
  @Test
  void thresholdMatchesHardwareProfileMinimumVramForGguf() {
    Map<String, String> autoDetected = new LinkedHashMap<>();
    autoDetected.put(GPU_ENABLED_KEY, "true");
    LongSupplier oneByteAboveThreshold = () -> HardwareProfile.MINIMUM_VRAM_FOR_GGUF;
    LongSupplier oneByteBelowThreshold = () -> HardwareProfile.MINIMUM_VRAM_FOR_GGUF - 1;

    Map<String, String> aboveResult =
        HeadlessApp.augmentGpuAutoDetectionAndMirrorProbeFlags(autoDetected, oneByteAboveThreshold);
    assertEquals(
        "99",
        aboveResult.get(GPU_LAYERS_KEY),
        "exactly at threshold must auto-populate (>= comparison)");

    // Reset sysprops between cases (the first run set them).
    System.clearProperty(GPU_LAYERS_KEY);
    System.clearProperty(LLM_GPU_LAYERS_KEY);
    System.clearProperty(GPU_ENABLED_KEY);

    Map<String, String> belowResult =
        HeadlessApp.augmentGpuAutoDetectionAndMirrorProbeFlags(autoDetected, oneByteBelowThreshold);
    assertNotEquals("99", belowResult.get(GPU_LAYERS_KEY));
    assertFalse(belowResult.containsKey(GPU_LAYERS_KEY));
  }

  /**
   * Empty/null autoDetected (probe found no CUDA capability — typical CPU-only host) must not
   * auto-populate anything and must not write sysprops.
   */
  @Test
  void emptyAutoDetected_doesNothing() {
    Map<String, String> result =
        HeadlessApp.augmentGpuAutoDetectionAndMirrorProbeFlags(
            new LinkedHashMap<>(), () -> 12L * 1024 * 1024 * 1024);

    assertEquals(0, result.size());
    assertNull(System.getProperty(GPU_ENABLED_KEY));
    assertNull(System.getProperty(GPU_LAYERS_KEY));
    assertNull(System.getProperty(LLM_GPU_LAYERS_KEY));
  }

  /**
   * NVML supplier failing (throws) must not propagate the exception. Auto-populate skips silently
   * and the rest of the boot continues. Phase E mirroring still applies.
   */
  @Test
  void nvmlSupplierThrowing_doesNotBreakBoot() {
    Map<String, String> autoDetected = new LinkedHashMap<>();
    autoDetected.put(GPU_ENABLED_KEY, "true");
    LongSupplier failingSupplier =
        () -> {
          throw new RuntimeException("NVML simulated failure");
        };

    Map<String, String> result =
        HeadlessApp.augmentGpuAutoDetectionAndMirrorProbeFlags(autoDetected, failingSupplier);

    // Phase F skipped (no VRAM info), Phase E still mirrored.
    assertNull(result.get(GPU_LAYERS_KEY));
    assertNull(System.getProperty(GPU_LAYERS_KEY));
    assertEquals("true", System.getProperty(GPU_ENABLED_KEY));
  }
}
