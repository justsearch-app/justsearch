package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ort.OrtCudaHelper.OrtNativePackDecision;
import io.justsearch.ort.OrtCudaHelper.OrtNativePackStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 772 §J item 2 — detection/wiring coverage for the relocated ORT CUDA execution-provider
 * native pack. The EP DLL is trimmed from the shipped jar and delivered via the consent-gated
 * cuda-runtime pack; {@link OrtCudaHelper#evaluateOrtNativePack(Path, String)} decides whether the
 * pack is present, complete, version-matched, and not already overridden before the worker points
 * ORT at it via {@code onnxruntime.native.path}.
 *
 * <p>Detection is deliberately file-existence-based (not OS-gated, not provider-list-based) — the
 * §J probe found {@code getAvailableProviders()} still lists CUDA when the EP DLL is absent, so a
 * provider-list check would give a false positive.
 */
@DisplayName("OrtCudaHelper ORT native pack detection (772 §J item 2)")
class OrtNativePackDetectionTest {

  @TempDir Path tmp;

  private Path completePackDir(String markerVersion) throws IOException {
    Path dir = tmp.resolve("cuda12-" + System.nanoTime());
    Files.createDirectories(dir);
    for (String dll : OrtCudaHelper.ORT_NATIVE_DLL_SET) {
      Files.writeString(dir.resolve(dll), "stub-native");
    }
    if (markerVersion != null) {
      Files.writeString(dir.resolve(OrtCudaHelper.ORT_NATIVE_VERSION_MARKER), markerVersion);
    }
    return dir;
  }

  @Test
  @DisplayName("complete dir + matching version marker + no existing property → SET (normalized path)")
  void completeAndMatchingVersion_returnsSet() throws IOException {
    Path dir = completePackDir(OrtCudaHelper.EXPECTED_ORT_NATIVE_VERSION + "\n");

    OrtNativePackDecision decision = OrtCudaHelper.evaluateOrtNativePack(dir, null);

    assertEquals(OrtNativePackStatus.SET, decision.status());
    assertEquals(dir.toAbsolutePath().normalize().toString(), decision.detail());
  }

  @Test
  @DisplayName("incomplete dir (missing a DLL) → INCOMPLETE naming the missing DLL")
  void incompleteDir_returnsIncomplete() throws IOException {
    Path dir = completePackDir(OrtCudaHelper.EXPECTED_ORT_NATIVE_VERSION);
    Files.delete(dir.resolve(io.justsearch.configuration.NativeTargets.current().libraryName("ort-cuda")));

    OrtNativePackDecision decision = OrtCudaHelper.evaluateOrtNativePack(dir, null);

    assertEquals(OrtNativePackStatus.INCOMPLETE, decision.status());
    assertTrue(
        decision.detail().contains(io.justsearch.configuration.NativeTargets.current().libraryName("ort-cuda")),
        "the missing DLL must be named in the decision detail: " + decision.detail());
  }

  @Test
  @DisplayName("complete dir but wrong version marker → VERSION_MISMATCH")
  void versionSkew_returnsVersionMismatch() throws IOException {
    Path dir = completePackDir("9.9.9");

    OrtNativePackDecision decision = OrtCudaHelper.evaluateOrtNativePack(dir, null);

    assertEquals(OrtNativePackStatus.VERSION_MISMATCH, decision.status());
    assertTrue(
        decision.detail().contains(OrtCudaHelper.EXPECTED_ORT_NATIVE_VERSION)
            && decision.detail().contains("9.9.9"),
        "detail must name both expected and found versions: " + decision.detail());
  }

  @Test
  @DisplayName("complete dir but missing version marker → VERSION_MISMATCH (guards against unmarked pack)")
  void missingMarker_returnsVersionMismatch() throws IOException {
    Path dir = completePackDir(null);

    OrtNativePackDecision decision = OrtCudaHelper.evaluateOrtNativePack(dir, null);

    assertEquals(OrtNativePackStatus.VERSION_MISMATCH, decision.status());
  }

  @Test
  @DisplayName("pre-existing onnxruntime.native.path (user override) → ALREADY_SET_EXTERNALLY, untouched")
  void preExistingProperty_isRespected() throws IOException {
    Path dir = completePackDir(OrtCudaHelper.EXPECTED_ORT_NATIVE_VERSION);

    OrtNativePackDecision decision =
        OrtCudaHelper.evaluateOrtNativePack(dir, "C:\\user\\custom\\ort");

    assertEquals(OrtNativePackStatus.ALREADY_SET_EXTERNALLY, decision.status());
    assertEquals("C:\\user\\custom\\ort", decision.detail());
  }

  @Test
  @DisplayName("null pack dir → DIR_ABSENT (CPU-only install path)")
  void nullDir_returnsDirAbsent() {
    OrtNativePackDecision decision = OrtCudaHelper.evaluateOrtNativePack(null, null);

    assertEquals(OrtNativePackStatus.DIR_ABSENT, decision.status());
  }

  @Test
  @DisplayName("nonexistent pack dir → DIR_ABSENT")
  void nonexistentDir_returnsDirAbsent() {
    OrtNativePackDecision decision =
        OrtCudaHelper.evaluateOrtNativePack(tmp.resolve("does-not-exist"), null);

    assertEquals(OrtNativePackStatus.DIR_ABSENT, decision.status());
  }

  @Test
  @DisplayName("applyOrtNativePackProperty: SET writes the property; ALREADY_SET leaves it untouched")
  void applyProperty_setsAndRespectsOverride() throws IOException {
    String prop = OrtCudaHelper.ORT_NATIVE_PATH_PROPERTY;
    String saved = System.getProperty(prop);
    try {
      // No pre-existing property → complete pack → property is written to the normalized dir.
      System.clearProperty(prop);
      Path dir = completePackDir(OrtCudaHelper.EXPECTED_ORT_NATIVE_VERSION);
      OrtNativePackDecision setDecision = OrtCudaHelper.applyOrtNativePackProperty(dir);
      assertEquals(OrtNativePackStatus.SET, setDecision.status());
      assertEquals(dir.toAbsolutePath().normalize().toString(), System.getProperty(prop));

      // Pre-existing property → a subsequent apply must not clobber the user's value.
      System.setProperty(prop, "C:\\user\\custom\\ort");
      Path dir2 = completePackDir(OrtCudaHelper.EXPECTED_ORT_NATIVE_VERSION);
      OrtNativePackDecision respectDecision = OrtCudaHelper.applyOrtNativePackProperty(dir2);
      assertEquals(OrtNativePackStatus.ALREADY_SET_EXTERNALLY, respectDecision.status());
      assertEquals("C:\\user\\custom\\ort", System.getProperty(prop));
    } finally {
      if (saved == null) {
        System.clearProperty(prop);
      } else {
        System.setProperty(prop, saved);
      }
    }
  }

  @Test
  @DisplayName("version marker parsing trims whitespace / blank lines")
  void versionMarker_trimsWhitespace() throws IOException {
    Path dir = completePackDir("\n  " + OrtCudaHelper.EXPECTED_ORT_NATIVE_VERSION + "  \n");

    OrtNativePackDecision decision = OrtCudaHelper.evaluateOrtNativePack(dir, null);

    assertEquals(OrtNativePackStatus.SET, decision.status());
    // A genuinely blank marker (only whitespace) still fails as a version mismatch.
    Path blank = completePackDir("   \n");
    assertEquals(
        OrtNativePackStatus.VERSION_MISMATCH,
        OrtCudaHelper.evaluateOrtNativePack(blank, null).status());
  }
}
