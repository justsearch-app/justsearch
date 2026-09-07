/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.UiSettings;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.ort.OrtCudaHelper;
import io.justsearch.ort.OrtCudaHelper.OrtNativePackStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lane F review B2 — the Engine points ONNX Runtime at the CUDA native pack.
 *
 * <p>Item A6 moved the index half into the Head's JVM but left {@code
 * OrtCudaHelper.applyOrtNativePackProperty} behind in {@code IndexerWorker.main}, the entry point
 * that stopped being on the live path at that same item. The result was invisible by construction:
 * ORT falls back to the CPU natives in the jar when {@code onnxruntime.native.path} is unset, so a
 * machine with a complete, consent-installed CUDA pack simply ran every encoder on CPU and logged
 * nothing about it. No unit test could fail, because the defect was the <em>absence</em> of a call.
 *
 * <p>So these are wiring tests, and the second one is the important one: it pins the <em>ordering</em>
 * against the config rebuild, which is where the equivalent bug (tempdoc 883 §C.5c) already
 * happened once to the sibling field.
 *
 * <p>The {@code onnxruntime.native.path} property is process-global and ORT reads it at class-init,
 * so every test here saves and restores it and none of them touches ORT.
 */
@DisplayName("HeadlessApp — ORT CUDA native pack (lane F review B2)")
final class HeadlessAppOrtNativePackTest {

  private static final String ORT_PROP = OrtCudaHelper.ORT_NATIVE_PATH_PROPERTY;
  private static final String CONFIG_KEY = "justsearch.onnxruntime.native_path";

  @TempDir Path tempDir;

  private String savedOrtProp;
  private String savedConfigProp;

  @BeforeEach
  void saveProps() {
    savedOrtProp = System.getProperty(ORT_PROP);
    savedConfigProp = System.getProperty(CONFIG_KEY);
    System.clearProperty(ORT_PROP);
    System.clearProperty(CONFIG_KEY);
  }

  @AfterEach
  void restoreProps() {
    restore(ORT_PROP, savedOrtProp);
    restore(CONFIG_KEY, savedConfigProp);
  }

  private static void restore(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  /** A directory that passes every check in {@code evaluateOrtNativePack}. */
  private Path completePack(String name) throws Exception {
    Path pack = Files.createDirectories(tempDir.resolve(name));
    for (String dll :
        new String[] {
          "onnxruntime.dll",
          "onnxruntime4j_jni.dll",
          "onnxruntime_providers_shared.dll",
          "onnxruntime_providers_cuda.dll"
        }) {
      Files.createFile(pack.resolve(dll));
    }
    Files.writeString(
        pack.resolve(OrtCudaHelper.ORT_NATIVE_VERSION_MARKER),
        OrtCudaHelper.EXPECTED_ORT_NATIVE_VERSION);
    return pack;
  }

  private static ConfigStore storeWithPack(Path packDir) {
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.putSettings(CONFIG_KEY, packDir.toAbsolutePath().toString());
    builder.contributeEnvRegistry();
    return new ConfigStore(builder.build());
  }

  @Test
  @DisplayName("an installed, version-matched pack sets ORT's native path")
  void installedPackIsApplied() throws Exception {
    Path pack = completePack("cuda12");

    var decision = HeadlessApp.applyOrtNativePack(storeWithPack(pack).get());

    assertEquals(OrtNativePackStatus.SET, decision.status());
    assertEquals(
        pack.toAbsolutePath().normalize().toString(),
        System.getProperty(ORT_PROP),
        "the whole point of the call: ORT must load its natives from the pack, not the jar");
  }

  /**
   * The regression that matters. {@code maybeMirrorOrtNativePath} writes the config key as a system
   * property at ordinal 500 — <em>after</em> {@code resolveConfig} has already built its
   * {@code ResolvedConfig}. Applying the ORT property from that pre-write config would find nothing
   * and silently leave the Engine on CPU, which is exactly the tempdoc 883 §C.5c shape one field
   * over. Only the config {@code rebuildAfterPostBuildWrites} produces carries the late write.
   */
  @Test
  @DisplayName("a pack detected after the initial build still reaches ORT")
  void packDetectedAfterTheBuildStillReachesOrt() throws Exception {
    Path pack = completePack("cuda12-late");

    // The boot build, before the mirror step runs: nothing knows about a pack.
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.contributeEnvRegistry();
    ConfigStore store = new ConfigStore(builder.build());
    assertNull(
        store.get().paths().ortNativePath(),
        "precondition: the pre-write config must not already name a pack");

    // What maybeMirrorOrtNativePath does: writes the sysprop, does NOT rebuild.
    System.setProperty(CONFIG_KEY, pack.toAbsolutePath().toString());

    ResolvedConfig effective = HeadlessApp.rebuildAfterPostBuildWrites(store, new UiSettings());
    var decision = HeadlessApp.applyOrtNativePack(effective);

    assertEquals(
        OrtNativePackStatus.SET,
        decision.status(),
        "applied from the pre-rebuild config this is DIR_ABSENT and the GPU pack is never used");
    assertEquals(pack.toAbsolutePath().normalize().toString(), System.getProperty(ORT_PROP));
  }

  @Test
  @DisplayName("no pack installed leaves the property alone (the CPU-only path)")
  void noPackLeavesThePropertyUnset() {
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.contributeEnvRegistry();

    var decision = HeadlessApp.applyOrtNativePack(new ConfigStore(builder.build()).get());

    assertEquals(OrtNativePackStatus.DIR_ABSENT, decision.status());
    assertNull(
        System.getProperty(ORT_PROP),
        "setting the property to a directory that is not there would break ORT init outright,"
            + " turning a CPU-only install from working into broken");
  }

  @Test
  @DisplayName("an incomplete pack is refused rather than half-applied")
  void incompletePackIsRefused() throws Exception {
    Path pack = Files.createDirectories(tempDir.resolve("cuda12-partial"));
    Files.createFile(pack.resolve("onnxruntime.dll"));

    var decision = HeadlessApp.applyOrtNativePack(storeWithPack(pack).get());

    assertEquals(OrtNativePackStatus.INCOMPLETE, decision.status());
    assertNull(System.getProperty(ORT_PROP));
  }

  // ============================================================
  // The variant-derived candidate (config-surface follow-up to item A11)
  // ============================================================

  /**
   * {@code WorkerSpawner.resolveOnnxRuntimeNativePathBestEffort} derived the ORT native path from
   * the ONNX Runtime variant id and set it on the Worker child's command line. Item A11 deleted the
   * spawner, and with it the only reader of {@code ResolvedConfig.ai().onnxruntimeVariantId} — an
   * operator override that still resolved, was still reachable, and changed nothing. The
   * config-surface gate is what found it, which is the point of that gate; these are the assertions
   * that make the re-homing real rather than a moved comment.
   */
  @Test
  @DisplayName("the variant id names an ONNX Runtime directory, and it is parsed from the exe path")
  void variantIdIsParsedFromTheLlamaServerExePath() {
    assertEquals(
        "cuda12",
        HeadlessApp.variantIdFromLlamaServerExe(
            "C:/data/native-bin/llama-server/variants/cuda12/llama-server.exe"));
    assertEquals(
        "cuda12",
        HeadlessApp.variantIdFromLlamaServerExe(
            "C:\\data\\native-bin\\llama-server\\variants\\cuda12\\llama-server.exe"),
        "the separator is the platform's; the parse must not depend on which one the config used");
    assertNull(
        HeadlessApp.variantIdFromLlamaServerExe("C:/data/native-bin/llama-server/llama-server.exe"),
        "no variants segment means no variant, not a guess at the parent directory's name");
    assertNull(HeadlessApp.variantIdFromLlamaServerExe(""));
    assertNull(HeadlessApp.variantIdFromLlamaServerExe(null));
  }

  @Test
  @DisplayName("a malformed exe path is 'no variant', not a boot failure")
  void aMalformedExePathIsSurvivable() {
    // This runs during config resolution, before anything is serving. An InvalidPathException here
    // would take the Engine down over a GPU convenience.
    assertNull(HeadlessApp.variantIdFromLlamaServerExe("C:/data/\u0000/llama-server/variants/x/a.exe"));
  }

  @Test
  @DisplayName("a boot with no config at all does not take the Engine down")
  void nullConfigIsSurvivable() {
    // resolveConfig cannot produce null, but applyOrtNativePack is the last boot step before the
    // phases that can create an ORT session, and a GPU convenience must never be the reason the
    // Engine fails to boot.
    var decision = HeadlessApp.applyOrtNativePack(null);

    assertEquals(OrtNativePackStatus.DIR_ABSENT, decision.status());
    assertNull(System.getProperty(ORT_PROP));
  }

  /** {@code HeadlessApp.java}, read as text, resolved from the module the test runs in. */
  private static final Path HEADLESS_APP_SOURCE =
      Path.of("src", "main", "java", "io", "justsearch", "ui", "HeadlessApp.java");

  @Test
  @DisplayName("boot still CALLS applyOrtNativePack — the defect was an absent call, not a broken one")
  void bootStillCallsApplyOrtNativePack() throws Exception {
    // Every other test in this file exercises applyOrtNativePack directly, so all of them stay
    // green if someone deletes the one line in resolveConfig that invokes it — which is precisely
    // the shape of the original B2 defect: the helper was fine, nothing called it, and ORT silently
    // ran on CPU. A test that can only fail when the helper misbehaves cannot see that. This one
    // reads the boot path's source and asserts the call is still there, in the right place.
    assertTrue(
        Files.isRegularFile(HEADLESS_APP_SOURCE),
        "HeadlessApp.java not found at " + HEADLESS_APP_SOURCE.toAbsolutePath()
            + " — if the file moved, this pin must follow it rather than silently checking nothing");
    String src = Files.readString(HEADLESS_APP_SOURCE);

    int rebuildAt = src.indexOf("effectiveConfig = rebuildAfterPostBuildWrites(");
    int callAt = src.indexOf("applyOrtNativePack(effectiveConfig);");

    assertTrue(
        callAt >= 0,
        "nothing on the boot path calls applyOrtNativePack(effectiveConfig). ORT then falls back to "
            + "the CPU natives bundled in the jar, every encoder runs on CPU, and no log line and no "
            + "other test in this file says so. Restore the call in resolveConfig.");
    assertTrue(
        rebuildAt >= 0,
        "rebuildAfterPostBuildWrites is gone from the boot path; applyOrtNativePack reads the "
            + "config it produces, so this pin's ordering check no longer means anything. Re-derive "
            + "both sides before editing this assertion away.");
    // Belt and braces, and honestly weaker than it looks: because the call takes effectiveConfig by
    // name, javac already rejects moving it above the declaration (verified — the reordering
    // mutation fails compilation, not this assertion). The assertion that does real work is callAt
    // >= 0 above, which also catches the subtler edit of passing the PRE-rebuild resolvedConfig:
    // that changes the literal, so the search misses and this test reds.
    assertTrue(
        callAt > rebuildAt,
        "applyOrtNativePack must run AFTER the post-build-writes rebuild: the CUDA variant it keys "
            + "off is one of the values that rebuild folds in, so calling it earlier reads a config "
            + "that predates the variant selection and points ORT at the wrong pack (or none).");
  }
}
