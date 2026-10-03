/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.inference.ContextWindowPolicy;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link HeadlessApp#augmentDerivedContextWindow} (tempdoc 883 decision 1).
 *
 * <p>Two wrong-gate hazards live in this function and each has a test below: reading
 * {@code gpu.layers} BEFORE the GPU auto-detect pass would derive the CPU rung on every GPU machine,
 * and returning early on an empty probe map (as its GPU sibling does) would leave a fresh non-GPU
 * data dir with no window provenance at all.
 */
@DisplayName("HeadlessApp.augmentDerivedContextWindow")
final class HeadlessAppContextWindowAutoDetectTest {

  private static final String CONTEXT_SIZE_KEY = "justsearch.context.size";
  private static final String GPU_LAYERS_KEY = "justsearch.gpu.layers";
  private static final String GPU_ENABLED_KEY = "justsearch.gpu.enabled";

  /** Every sysprop the two augment passes write, so one case cannot leak into the next. */
  private static final String[] TOUCHED_SYSPROPS = {
    GPU_LAYERS_KEY,
    GPU_ENABLED_KEY,
    "justsearch.onnxruntime.native_path",
    "justsearch.embed.gpu.enabled",
    "justsearch.splade.gpu_enabled",
    "justsearch.ner.gpu_enabled",
  };

  /** Comfortably above HardwareProfile.MINIMUM_VRAM_FOR_GGUF, so Phase F populates layers. */
  private static final long TWELVE_GB = 12L * 1024 * 1024 * 1024;

  /**
   * What {@code UiSettings.getGpuLayers()} returns when the user has not set one. {@code 0} is
   * "unset" in this store — {@code ConfigStoreRebuilder.contributeUiSettings} only contributes the
   * key when {@code > 0} — so the ordinal-300 rung must be skipped for it.
   */
  private static final Integer NO_SETTINGS_GPU_LAYERS = null;

  private final Map<String, String> saved = new HashMap<>();

  @BeforeEach
  void clearSysprops() {
    for (String key : TOUCHED_SYSPROPS) {
      saved.put(key, System.getProperty(key));
      System.clearProperty(key);
    }
  }

  @AfterEach
  void restoreSysprops() {
    for (var entry : saved.entrySet()) {
      if (entry.getValue() == null) {
        System.clearProperty(entry.getKey());
      } else {
        System.setProperty(entry.getKey(), entry.getValue());
      }
    }
    saved.clear();
  }

  @Test
  @DisplayName("auto-detected GPU layers give the GPU top rung")
  void gpuLayersFromAutoDetectGiveGpuRung() {
    Map<String, String> autoDetected = new HashMap<>();
    autoDetected.put("justsearch.gpu.enabled", "true");
    autoDetected.put(GPU_LAYERS_KEY, "99");

    Map<String, String> augmented =
        HeadlessApp.augmentDerivedContextWindow(autoDetected, NO_SETTINGS_GPU_LAYERS);

    assertEquals(
        String.valueOf(ContextWindowPolicy.GPU_TOP_RUNG), augmented.get(CONTEXT_SIZE_KEY));
  }

  @Test
  @DisplayName("no GPU layers give the CPU top rung")
  void noGpuLayersGiveCpuRung() {
    Map<String, String> augmented =
        HeadlessApp.augmentDerivedContextWindow(
            Map.of("justsearch.gpu.enabled", "false"), NO_SETTINGS_GPU_LAYERS);

    assertEquals(
        String.valueOf(ContextWindowPolicy.CPU_TOP_RUNG), augmented.get(CONTEXT_SIZE_KEY));
  }

  @Test
  @DisplayName("an empty probe map still gets a window: a fresh non-GPU data dir must be explained")
  void emptyProbeMapStillContributes() {
    Map<String, String> augmented =
        HeadlessApp.augmentDerivedContextWindow(Map.of(), NO_SETTINGS_GPU_LAYERS);

    assertTrue(
        augmented.containsKey(CONTEXT_SIZE_KEY),
        "returning the map unchanged (as the GPU augment pass does when empty) would leave"
            + " effective-config with no provenance for the window at all");
    assertEquals(
        String.valueOf(ContextWindowPolicy.CPU_TOP_RUNG), augmented.get(CONTEXT_SIZE_KEY));
  }

  @Test
  @DisplayName("a null probe map is tolerated")
  void nullProbeMapIsTolerated() {
    Map<String, String> augmented =
        HeadlessApp.augmentDerivedContextWindow(null, NO_SETTINGS_GPU_LAYERS);

    assertEquals(
        String.valueOf(ContextWindowPolicy.CPU_TOP_RUNG), augmented.get(CONTEXT_SIZE_KEY));
  }

  @Test
  @DisplayName("an explicit gpu.layers sysprop wins over the probe map")
  void explicitGpuLayersSyspropWins() {
    System.setProperty(GPU_LAYERS_KEY, "35");

    Map<String, String> augmented =
        HeadlessApp.augmentDerivedContextWindow(
            Map.of(GPU_LAYERS_KEY, "0"), NO_SETTINGS_GPU_LAYERS);

    assertEquals(
        String.valueOf(ContextWindowPolicy.GPU_TOP_RUNG),
        augmented.get(CONTEXT_SIZE_KEY),
        "the user's layer count decides the backend, so it decides the top rung");
  }

  @Test
  @DisplayName("an explicit gpu.layers of 0 gives the CPU rung even when the probe found a GPU")
  void explicitZeroLayersGivesCpuRung() {
    System.setProperty(GPU_LAYERS_KEY, "0");

    Map<String, String> augmented =
        HeadlessApp.augmentDerivedContextWindow(
            Map.of(GPU_LAYERS_KEY, "99"), NO_SETTINGS_GPU_LAYERS);

    assertEquals(
        String.valueOf(ContextWindowPolicy.CPU_TOP_RUNG), augmented.get(CONTEXT_SIZE_KEY));
  }

  @Test
  @DisplayName("an unparseable gpu.layers degrades to the CPU rung instead of throwing at boot")
  void unparseableGpuLayersDegrades() {
    Map<String, String> augmented =
        HeadlessApp.augmentDerivedContextWindow(
            Map.of(GPU_LAYERS_KEY, "many"), NO_SETTINGS_GPU_LAYERS);

    assertEquals(
        String.valueOf(ContextWindowPolicy.CPU_TOP_RUNG), augmented.get(CONTEXT_SIZE_KEY));
  }

  @Test
  @DisplayName("end to end: GPU detection then window derivation yields the GPU rung")
  void gpuDetectionThenWindowDerivationYieldsTheGpuRung() {
    // What HeadlessApp.resolveConfig actually composes. The GPU probe reports a CUDA machine but
    // says nothing about layers; Phase F of the GPU pass is what decides `gpu.layers = 99` from
    // the VRAM tier, and only after that is "is this GPU-backed?" answerable.
    Map<String, String> probe = Map.of(GPU_ENABLED_KEY, "true");

    Map<String, String> composed =
        HeadlessApp.augmentDerivedContextWindow(
            HeadlessApp.augmentGpuAutoDetectionAndMirrorProbeFlags(probe, () -> TWELVE_GB),
            NO_SETTINGS_GPU_LAYERS);

    assertEquals("99", composed.get(GPU_LAYERS_KEY), "Phase F must have run first");
    assertEquals(
        String.valueOf(ContextWindowPolicy.GPU_TOP_RUNG),
        composed.get(CONTEXT_SIZE_KEY),
        "the derived window must see the layers the GPU pass just decided");
  }

  @Test
  @DisplayName("the two passes are ORDER-DEPENDENT: swapping them derives the CPU rung on a GPU box")
  void swappingTheTwoPassesDerivesTheWrongRung() {
    // The wrong-gate this ordering hides: derive the window before GPU auto-detection and the
    // probe map has no gpu.layers yet, so a 12 GB CUDA machine is read as CPU-only and gets 8192.
    // Asserting the two orders DIFFER is what makes swapping the calls in resolveConfig a test
    // failure rather than a silent 4x context loss.
    Map<String, String> probe = Map.of(GPU_ENABLED_KEY, "true");

    Map<String, String> swapped =
        HeadlessApp.augmentGpuAutoDetectionAndMirrorProbeFlags(
            HeadlessApp.augmentDerivedContextWindow(probe, NO_SETTINGS_GPU_LAYERS),
            () -> TWELVE_GB);

    assertEquals(
        String.valueOf(ContextWindowPolicy.CPU_TOP_RUNG),
        swapped.get(CONTEXT_SIZE_KEY),
        "deriving the window before GPU detection reads a GPU machine as CPU-only");
    assertNotEquals(
        swapped.get(CONTEXT_SIZE_KEY),
        String.valueOf(ContextWindowPolicy.GPU_TOP_RUNG),
        "if these two orders ever agree, this test has stopped guarding the ordering");
  }

  @Test
  @DisplayName("a settings.json gpu.layers beats the auto-detected map for the rung")
  void settingsGpuLayersBeatTheProbeMap() {
    // Tempdoc 883 decision 4 slice 2. Before it, a user's 20 layers reached this function only
    // because SettingsController promoted the value into the gpu.layers SYSPROP, so it arrived
    // inside EnvRegistry.GPU_LAYERS. With the promotion deleted the settings value has to be
    // passed in explicitly — and if it is dropped, this GPU box derives the CPU rung.
    Map<String, String> augmented =
        HeadlessApp.augmentDerivedContextWindow(Map.of(GPU_LAYERS_KEY, "0"), 20);

    assertEquals(
        String.valueOf(ContextWindowPolicy.GPU_TOP_RUNG),
        augmented.get(CONTEXT_SIZE_KEY),
        "the user set 20 layers at ordinal 300; the probe's 0 sits at 150 and must lose");
  }

  @Test
  @DisplayName("an absent GPU override preserves automatic detection")
  void absentSettingsGpuLayersMeansAutomatic() {
    // Schema4 represents automatic selection as null; the legacy0 migration preserves it.
    Map<String, String> augmented =
        HeadlessApp.augmentDerivedContextWindow(Map.of(GPU_LAYERS_KEY, "99"), null);

    assertEquals(
        String.valueOf(ContextWindowPolicy.GPU_TOP_RUNG), augmented.get(CONTEXT_SIZE_KEY));
  }

  @Test
  @DisplayName("an explicit sysprop still beats settings.json — 500 over 300")
  void explicitSyspropBeatsSettings() {
    System.setProperty(GPU_LAYERS_KEY, "0");

    Map<String, String> augmented =
        HeadlessApp.augmentDerivedContextWindow(Map.of(GPU_LAYERS_KEY, "99"), 20);

    assertEquals(
        String.valueOf(ContextWindowPolicy.CPU_TOP_RUNG),
        augmented.get(CONTEXT_SIZE_KEY),
        "an operator's -Djustsearch.gpu.layers=0 outranks the GUI's 20, as ordinal 500 > 300");
  }

  @Test
  void explicitCpuOverridesTheGpuProbeForTheDerivedContext() {
    var derived = HeadlessApp.augmentDerivedContextWindow(Map.of(GPU_LAYERS_KEY, "99"), 0);
    assertEquals(String.valueOf(ContextWindowPolicy.CPU_TOP_RUNG), derived.get(CONTEXT_SIZE_KEY));
  }

  @Test
  @DisplayName("the probe map's own entries are preserved")
  void probeEntriesArePreserved() {
    Map<String, String> augmented =
        HeadlessApp.augmentDerivedContextWindow(
            Map.of("justsearch.onnxruntime.native_path", "C:/x", GPU_LAYERS_KEY, "99"),
            NO_SETTINGS_GPU_LAYERS);

    assertEquals("C:/x", augmented.get("justsearch.onnxruntime.native_path"));
    assertEquals("99", augmented.get(GPU_LAYERS_KEY));
  }
}
