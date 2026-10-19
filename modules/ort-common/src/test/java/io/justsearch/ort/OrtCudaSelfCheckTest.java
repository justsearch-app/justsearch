package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for ORT CUDA self-check logic.
 *
 * <p>Validates:
 *
 * <ul>
 *   <li>OrtCudaStatus factory methods produce correct states
 *   <li>DLL check logic in OrtCudaHelper
 *   <li>Status summaries are informative
 * </ul>
 */
@DisplayName("ORT CUDA Self-Check")
class OrtCudaSelfCheckTest {

  @TempDir Path tempDir;

  // ==================== OrtCudaStatus Factory Tests ====================

  @Test
  @DisplayName("notConfigured() produces correct state")
  void notConfiguredProducesCorrectState() {
    OrtCudaStatus status = OrtCudaStatus.notConfigured();

    assertFalse(status.configured());
    assertFalse(status.attempted());
    assertFalse(status.available());
    assertNull(status.variantId());
    assertNull(status.nativePath());
    assertNotNull(status.failureReason());
    assertTrue(status.failureReason().contains("not configured"));
    assertTrue(status.missingDlls().isEmpty());
    assertFalse(status.isHealthy());
  }

  @Test
  @DisplayName("ready() produces healthy state")
  void readyProducesHealthyState() {
    Path nativePath = Path.of("/cuda/native");
    OrtCudaStatus status = OrtCudaStatus.ready("onnxruntime-gpu", nativePath);

    assertTrue(status.configured());
    assertTrue(status.attempted());
    assertTrue(status.available());
    assertEquals("onnxruntime-gpu", status.variantId());
    assertEquals(nativePath, status.nativePath());
    assertNull(status.failureReason());
    assertTrue(status.missingDlls().isEmpty());
    assertTrue(status.isHealthy());
  }

  @Test
  @DisplayName("missingDlls() lists missing files in failure reason")
  void missingDllsListsMissingFiles() {
    List<String> missing = List.of(io.justsearch.configuration.NativeTargets.current().libraryName("cudart"), io.justsearch.configuration.NativeTargets.current().libraryName("cublas"));
    OrtCudaStatus status = OrtCudaStatus.missingDlls("onnxruntime-gpu", tempDir, missing);

    assertTrue(status.configured());
    assertTrue(status.attempted());
    assertFalse(status.available());
    assertEquals(missing, status.missingDlls());
    assertNotNull(status.failureReason());
    assertTrue(status.failureReason().contains(io.justsearch.configuration.NativeTargets.current().libraryName("cudart")));
    assertTrue(status.failureReason().contains(io.justsearch.configuration.NativeTargets.current().libraryName("cublas")));
    assertFalse(status.isHealthy());
  }

  @Test
  @DisplayName("providerFailed() records failure reason")
  void providerFailedRecordsReason() {
    String reason = "CUDA driver version insufficient";
    OrtCudaStatus status = OrtCudaStatus.providerFailed("onnxruntime-gpu", tempDir, reason);

    assertTrue(status.configured());
    assertTrue(status.attempted());
    assertFalse(status.available());
    assertEquals(reason, status.failureReason());
    assertTrue(status.missingDlls().isEmpty());
    assertFalse(status.isHealthy());
  }

  @Test
  @DisplayName("released() indicates temporary unavailability")
  void releasedIndicatesTemporaryUnavailability() {
    OrtCudaStatus status = OrtCudaStatus.released("onnxruntime-gpu", tempDir);

    assertTrue(status.configured());
    assertTrue(status.attempted());
    assertFalse(status.available());
    assertNotNull(status.failureReason());
    assertTrue(status.failureReason().contains("released"));
    assertFalse(status.isHealthy());
  }

  @Test
  @DisplayName("pending() produces configured-but-not-attempted state")
  void pendingProducesConfiguredPendingState() {
    OrtCudaStatus pending = OrtCudaStatus.pending("onnxruntime-gpu", tempDir);

    assertTrue(pending.configured());
    assertFalse(pending.attempted());
    assertFalse(pending.available());
    assertFalse(pending.isHealthy());

    // Must NOT produce "not configured" summary
    assertFalse(pending.toSummary().contains("not configured"));
    assertTrue(pending.toSummary().contains("pending"));
  }

  // ==================== toSummary() Tests ====================

  @Test
  @DisplayName("toSummary() formats not configured state")
  void toSummaryFormatsNotConfigured() {
    OrtCudaStatus status = OrtCudaStatus.notConfigured();
    assertEquals("ORT CUDA: not configured", status.toSummary());
  }

  @Test
  @DisplayName("toSummary() formats ready state with variant")
  void toSummaryFormatsReady() {
    OrtCudaStatus status = OrtCudaStatus.ready("onnxruntime-gpu", tempDir);
    String summary = status.toSummary();

    assertTrue(summary.contains("ready"));
    assertTrue(summary.contains("onnxruntime-gpu"));
  }

  @Test
  @DisplayName("toSummary() formats missing DLLs with count")
  void toSummaryFormatsMissingDlls() {
    List<String> missing = List.of("a.dll", "b.dll", "c.dll");
    OrtCudaStatus status = OrtCudaStatus.missingDlls("onnxruntime-gpu", tempDir, missing);
    String summary = status.toSummary();

    assertTrue(summary.contains("missing DLLs"));
    assertTrue(summary.contains("3 files"));
  }

  @Test
  @DisplayName("toSummary() formats provider failure with reason")
  void toSummaryFormatsProviderFailure() {
    OrtCudaStatus status =
        OrtCudaStatus.providerFailed("onnxruntime-gpu", tempDir, "Driver error");
    String summary = status.toSummary();

    assertTrue(summary.contains("failed"));
    assertTrue(summary.contains("Driver error"));
  }

  // ==================== DLL Check Logic Tests (OrtCudaHelper) ====================

  @Test
  @DisplayName("checkMissingCudaDlls identifies the host libraries missing from an empty directory")
  void checkMissingCudaDllsIdentifiesMissingHostLibraries() {
    List<String> missing = OrtCudaHelper.checkMissingCudaDlls(tempDir);

    assertEquals(5, missing.size());
    assertTrue(missing.contains(io.justsearch.configuration.NativeTargets.current().libraryName("ort-cuda")));
  }

  @Test
  @DisplayName("checkMissingCudaDlls returns empty for null path")
  void checkMissingCudaDllsReturnsEmptyForNullPath() {
    List<String> missing = OrtCudaHelper.checkMissingCudaDlls(null);
    assertTrue(missing.isEmpty());
  }

  @Test
  @DisplayName("checkMissingCudaDlls returns empty for non-existent directory")
  void checkMissingCudaDllsReturnsEmptyForNonExistentDir() {
    Path nonExistent = Path.of("/this/path/does/not/exist");
    List<String> missing = OrtCudaHelper.checkMissingCudaDlls(nonExistent);
    assertTrue(missing.isEmpty());
  }

  @Test
  @DisplayName("checkMissingCudaDlls detects missing host libraries")
  void checkMissingCudaDllsDetectsMissingFiles() throws Exception {
    Path dllDir = tempDir.resolve("dlls");
    Files.createDirectories(dllDir);
    Files.createFile(dllDir.resolve(io.justsearch.configuration.NativeTargets.current().libraryName("ort-cuda")));
    Files.createFile(dllDir.resolve(io.justsearch.configuration.NativeTargets.current().libraryName("ort-shared")));

    List<String> missing = OrtCudaHelper.checkMissingCudaDlls(dllDir);

    assertFalse(missing.isEmpty());
    assertTrue(missing.contains(io.justsearch.configuration.NativeTargets.current().libraryName("cudart")));
    assertTrue(missing.contains(io.justsearch.configuration.NativeTargets.current().libraryName("cublas")));
    assertFalse(missing.contains(io.justsearch.configuration.NativeTargets.current().libraryName("ort-cuda")));
    assertFalse(missing.contains(io.justsearch.configuration.NativeTargets.current().libraryName("ort-shared")));
  }

  // ==================== Defensive Copy Tests ====================

  @Test
  @DisplayName("missingDlls list is immutable")
  void missingDllsListIsImmutable() {
    List<String> mutable = new java.util.ArrayList<>(List.of("a.dll", "b.dll"));
    OrtCudaStatus status = OrtCudaStatus.missingDlls("v", tempDir, mutable);

    mutable.add("c.dll");

    assertEquals(2, status.missingDlls().size());
    assertThrows(UnsupportedOperationException.class, () -> status.missingDlls().add("d.dll"));
  }
}
