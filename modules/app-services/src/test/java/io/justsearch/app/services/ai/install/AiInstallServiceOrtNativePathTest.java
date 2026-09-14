package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ort.OrtCudaHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the production codepath of {@link AiInstallService#writeOrtNativePathSysprop} (extracted
 * from the alpha.13 {@code applyOrtNativePath} method during alpha.14 fix B).
 *
 * <p>Without this test, alpha.13 shipped a "defensive guard" that called
 * {@link io.justsearch.ort.OrtCudaHelper#checkMissingCudaDlls} against the cuda12 variant
 * directory — which always tripped because the ORT EP DLLs live inside the JAR, not in cuda12.
 * The fix never ran in production. The lack of a regression test on this codepath was a CLAUDE.md
 * violation ("Audit-driven fixes need a runnable test, not just a passing audit") that round-5
 * sandbox validation surfaced.
 */
// Windows-specific: asserts the Windows ORT native-library path layout/format.
// Runs on the windows-native lane (tempdoc 668 option B).
@Tag("windows")
final class AiInstallServiceOrtNativePathTest {

  private static final String SYSPROP_KEY = "justsearch.onnxruntime.native_path";

  @TempDir Path tmp;

  private final Map<String, String> savedSysprops = new HashMap<>();
  private RuntimeIntentTestFixture fixture;
  private ConfigStore previousConfigStore;

  @BeforeEach
  void clearSysprops() {
    previousConfigStore = ConfigStore.globalOrNull();
    savedSysprops.put(SYSPROP_KEY, System.getProperty(SYSPROP_KEY));
    System.clearProperty(SYSPROP_KEY);
  }

  @AfterEach
  void restoreSysprops() {
    if (fixture != null) {
      fixture.close();
      fixture = null;
    }
    TestResolvedConfigHelper.restoreGlobal(previousConfigStore);
    previousConfigStore = null;
    for (var entry : savedSysprops.entrySet()) {
      if (entry.getValue() == null) {
        System.clearProperty(entry.getKey());
      } else {
        System.setProperty(entry.getKey(), entry.getValue());
      }
    }
    savedSysprops.clear();
  }

  /**
   * Happy path: a cuda12-shaped directory containing all three CUDA runtime DLLs
   * (cudart64_12.dll, cublas64_12.dll, cublasLt64_12.dll) is accepted, the sysprop
   * is written, and the helper returns true.
   *
   * <p>This is the case alpha.13's broken precondition silently rejected.
   */
  @Test
  void cuda12DirWithRuntimeDlls_writesSysprop() throws IOException {
    Path cuda12Dir = tmp.resolve("cuda12");
    Files.createDirectories(cuda12Dir);
    touch(cuda12Dir.resolve("cudart64_12.dll"));
    touch(cuda12Dir.resolve("cublas64_12.dll"));
    touch(cuda12Dir.resolve("cublasLt64_12.dll"));

    boolean wrote = AiInstallService.writeOrtNativePathSysprop(cuda12Dir);

    assertTrue(wrote, "should write sysprop when all CUDA runtime DLLs are present");
    assertEquals(
        cuda12Dir.toAbsolutePath().toString(),
        System.getProperty(SYSPROP_KEY),
        "sysprop should point at cuda12Dir");
  }

  /**
   * Wrong-flag guard: a cuda12 directory missing one runtime DLL must NOT have the sysprop set.
   * Pointing ORT at an incomplete runtime would surface as a hard LoadLibrary crash inside
   * native code; the precondition prevents that.
   */
  @Test
  void missingCudartDll_skipsWithWarn() throws IOException {
    Path cuda12Dir = tmp.resolve("cuda12-incomplete");
    Files.createDirectories(cuda12Dir);
    // Only 2 of 3 — cudart64_12.dll deliberately omitted.
    touch(cuda12Dir.resolve("cublas64_12.dll"));
    touch(cuda12Dir.resolve("cublasLt64_12.dll"));

    boolean wrote = AiInstallService.writeOrtNativePathSysprop(cuda12Dir);

    assertFalse(wrote, "should refuse to write sysprop when a runtime DLL is missing");
    assertNull(System.getProperty(SYSPROP_KEY), "sysprop must remain unset");
  }

  /**
   * Wrong-flag guard (regression catcher for alpha.13): if the precondition were still based on
   * the alpha.13 logic (looking for {@code onnxruntime_providers_cuda.dll} +
   * {@code onnxruntime_providers_shared.dll}), this case — a cuda12 dir with all the right
   * runtime DLLs but no ORT EP DLLs — would FAIL. With the alpha.14 fix it must PASS.
   */
  @Test
  void cuda12DirWithoutOrtEpDlls_stillWritesSysprop() throws IOException {
    Path cuda12Dir = tmp.resolve("cuda12-no-ort-ep");
    Files.createDirectories(cuda12Dir);
    touch(cuda12Dir.resolve("cudart64_12.dll"));
    touch(cuda12Dir.resolve("cublas64_12.dll"));
    touch(cuda12Dir.resolve("cublasLt64_12.dll"));
    // Deliberately NOT creating onnxruntime_providers_cuda.dll or
    // onnxruntime_providers_shared.dll — those live in the JAR.

    boolean wrote = AiInstallService.writeOrtNativePathSysprop(cuda12Dir);

    assertTrue(
        wrote,
        "alpha.14 must accept a cuda12 dir that has runtime DLLs but no ORT EP DLLs"
            + " — the EP DLLs auto-extract from the JAR and never live in cuda12/."
            + " (Alpha.13 incorrectly required them and silently disabled the fix.)");
  }

  /**
   * Boundary: directory doesn't exist (e.g. CPU-only build with no cuda12 staging). Helper must
   * skip silently and return false.
   */
  @Test
  void cuda12DirAbsent_skipsSilently() {
    Path cuda12Dir = tmp.resolve("does-not-exist");

    boolean wrote = AiInstallService.writeOrtNativePathSysprop(cuda12Dir);

    assertFalse(wrote);
    assertNull(System.getProperty(SYSPROP_KEY));
  }

  /**
   * Boundary: directory exists but is empty. Same outcome as the missing-DLL case.
   */
  @Test
  void cuda12DirEmpty_skipsWithWarn() throws IOException {
    Path cuda12Dir = tmp.resolve("cuda12-empty");
    Files.createDirectories(cuda12Dir);

    boolean wrote = AiInstallService.writeOrtNativePathSysprop(cuda12Dir);

    assertFalse(wrote);
    assertNull(System.getProperty(SYSPROP_KEY));
  }

  /**
   * Idempotency: when the sysprop is already set (e.g. user passed
   * {@code -Djustsearch.onnxruntime.native_path=...} or
   * {@code JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH} env var, picked up by an earlier resolution
   * pass), the helper must NOT overwrite it. {@code SystemPropertyUtils.setSysPropIfBlank}
   * is the underlying gate.
   */
  @Test
  void existingSysprop_isRespected() throws IOException {
    Path cuda12Dir = tmp.resolve("cuda12");
    Files.createDirectories(cuda12Dir);
    touch(cuda12Dir.resolve("cudart64_12.dll"));
    touch(cuda12Dir.resolve("cublas64_12.dll"));
    touch(cuda12Dir.resolve("cublasLt64_12.dll"));

    String preExisting = "C:\\custom\\ort\\path";
    System.setProperty(SYSPROP_KEY, preExisting);

    boolean wrote = AiInstallService.writeOrtNativePathSysprop(cuda12Dir);

    // The helper still returns true (the path is valid; the sysprop is set; it just doesn't
    // overwrite). The caller's promise is "if true, the sysprop holds a usable value" — which
    // is satisfied because the user's pre-existing value is also usable.
    assertTrue(wrote);
    assertEquals(preExisting, System.getProperty(SYSPROP_KEY), "user override must not be clobbered");
  }

  /** Null directory must not blow up. */
  @Test
  void nullDir_returnsFalseSafely() {
    boolean wrote = AiInstallService.writeOrtNativePathSysprop(null);
    assertFalse(wrote);
    assertNull(System.getProperty(SYSPROP_KEY));
  }

  @Test
  void validSyspropFallbackLeavesPublishedConfigUntouched() throws Exception {
    Path cuda12Dir = tmp.resolve("cuda12-fallback");
    Files.createDirectories(cuda12Dir);
    touch(cuda12Dir.resolve("cudart64_12.dll"));
    touch(cuda12Dir.resolve("cublas64_12.dll"));
    touch(cuda12Dir.resolve("cublasLt64_12.dll"));

    UiSettingsStore settings =
        new UiSettingsStore(
            UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json"));
    ConfigStore config = new ConfigStore(ConfigStoreRebuilder.prepare(settings.load()));
    ConfigStore.setGlobal(config);
    fixture = new RuntimeIntentTestFixture(tmp.resolve("intent"), settings, config);
    var before = config.get();

    assertTrue(AiInstallService.writeOrtNativePathSysprop(cuda12Dir));
    assertSame(
        before,
        config.get(),
        "the sysprop fallback must not republish or overwrite the accepted ConfigStore snapshot");
    assertEquals(
        cuda12Dir.toAbsolutePath().normalize(),
        OrtCudaHelper.resolveOrtNativePath(tmp.resolve("default")),
        "ORT should resolve the new sysprop when the published config has no native path");
  }

  private static void touch(Path file) throws IOException {
    Files.createFile(file);
  }
}
