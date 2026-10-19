package io.justsearch.app.services.ai.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for deterministic CPU baseline resolution in RuntimeActivationService.resolveCpuBaselineExe().
 */
class RuntimeActivationServiceBaselineTest {
  private String nativeFixtureOs;

  @org.junit.jupiter.api.BeforeEach
  void selectWindowsNativeFixture() {
    nativeFixtureOs = System.getProperty("os.name");
    System.setProperty("os.name", "Windows 11");
  }

  @org.junit.jupiter.api.AfterEach
  void restoreNativeFixtureOs() {
    if (nativeFixtureOs == null) System.clearProperty("os.name");
    else System.setProperty("os.name", nativeFixtureOs);
  }


  @TempDir Path tempDir;

  @Test
  @DisplayName("Resolves canonical baseline path first")
  void resolvesCanonicalBaselineFirst() throws Exception {
    // Create canonical path
    Path nativeBin = tempDir.resolve("native-bin").resolve("llama-server");
    Files.createDirectories(nativeBin);
    Path canonical = nativeBin.resolve("llama-server.exe");
    Files.writeString(canonical, "canonical");

    // Also create subdirectory with exe that would come first alphabetically
    Path aaaDir = nativeBin.resolve("aaa-build");
    Files.createDirectories(aaaDir);
    Files.writeString(aaaDir.resolve("llama-server.exe"), "aaa");

    Path result = invokeResolveCpuBaselineExe(tempDir);
    assertEquals(canonical, result, "Should find canonical path first, not subdirectory");
  }

  @Test
  @DisplayName("Ignores variants/ directory during subdirectory scan")
  void ignoresVariantsDirectory() throws Exception {
    // Create only variants/ subdir with exe
    Path nativeBin = tempDir.resolve("native-bin").resolve("llama-server");
    Files.createDirectories(nativeBin);

    Path variantsDir = nativeBin.resolve("variants");
    Files.createDirectories(variantsDir);
    Files.writeString(variantsDir.resolve("llama-server.exe"), "variants");

    // Create a valid subdir
    Path cpuDir = nativeBin.resolve("cpu");
    Files.createDirectories(cpuDir);
    Files.writeString(cpuDir.resolve("llama-server.exe"), "cpu");

    Path result = invokeResolveCpuBaselineExe(tempDir);
    assertEquals(cpuDir.resolve("llama-server.exe"), result,
        "Should ignore variants/ and find cpu/ subdir");
  }

  @Test
  @DisplayName("Falls back to sorted subdirectory when no canonical path exists")
  void fallsBackToSortedSubdirectory() throws Exception {
    Path nativeBin = tempDir.resolve("native-bin").resolve("llama-server");
    Files.createDirectories(nativeBin);

    // Create multiple subdirs - "bbb" comes after "aaa" alphabetically
    Path bbbDir = nativeBin.resolve("bbb-build");
    Files.createDirectories(bbbDir);
    Files.writeString(bbbDir.resolve("llama-server.exe"), "bbb");

    Path aaaDir = nativeBin.resolve("aaa-build");
    Files.createDirectories(aaaDir);
    Files.writeString(aaaDir.resolve("llama-server.exe"), "aaa");

    Path result = invokeResolveCpuBaselineExe(tempDir);
    assertEquals(aaaDir.resolve("llama-server.exe"), result,
        "Should pick first alphabetically sorted subdir (aaa-build)");
  }

  @Test
  @DisplayName("Returns null when no baseline exists")
  void returnsNullWhenNoBaselineExists() throws Exception {
    // Create empty native-bin/llama-server
    Path nativeBin = tempDir.resolve("native-bin").resolve("llama-server");
    Files.createDirectories(nativeBin);

    Path result = invokeResolveCpuBaselineExe(tempDir);
    assertNull(result, "Should return null when no baseline exe exists");
  }

  @Test
  @DisplayName("Returns null when native-bin directory doesn't exist")
  void returnsNullWhenNativeBinMissing() throws Exception {
    // Don't create any directories
    Path result = invokeResolveCpuBaselineExe(tempDir);
    assertNull(result, "Should return null when native-bin/llama-server doesn't exist");
  }

  @Test
  @DisplayName("Returns null when aiHome is null")
  void returnsNullWhenAiHomeIsNull() throws Exception {
    Path result = invokeResolveCpuBaselineExe(null);
    assertNull(result, "Should return null when aiHome is null");
  }

  @Test
  @DisplayName("Is deterministic with multiple subdirectories")
  void isDeterministicWithMultipleSubdirs() throws Exception {
    Path nativeBin = tempDir.resolve("native-bin").resolve("llama-server");
    Files.createDirectories(nativeBin);

    // Create multiple subdirs with different names
    for (String name : new String[]{"zzz", "mmm", "aaa", "bbb"}) {
      Path dir = nativeBin.resolve(name);
      Files.createDirectories(dir);
      Files.writeString(dir.resolve("llama-server.exe"), name);
    }

    // Run multiple times to ensure determinism
    Path first = invokeResolveCpuBaselineExe(tempDir);
    for (int i = 0; i < 5; i++) {
      Path next = invokeResolveCpuBaselineExe(tempDir);
      assertEquals(first, next, "Result should be deterministic across invocations");
    }

    // Should always pick "aaa" (first alphabetically)
    assertEquals(nativeBin.resolve("aaa").resolve("llama-server.exe"), first,
        "Should consistently pick alphabetically first subdir");
  }

  @Test
  @DisplayName("Only variants/ with exact case-insensitive match is ignored")
  void onlyVariantsExactMatchIgnored() throws Exception {
    Path nativeBin = tempDir.resolve("native-bin").resolve("llama-server");
    Files.createDirectories(nativeBin);

    // "VARIANTS" (uppercase) should also be ignored
    Path variantsUpper = nativeBin.resolve("VARIANTS");
    Files.createDirectories(variantsUpper);
    Files.writeString(variantsUpper.resolve("llama-server.exe"), "upper");

    // "variant" (singular, no s) should NOT be ignored
    Path variantDir = nativeBin.resolve("variant");
    Files.createDirectories(variantDir);
    Files.writeString(variantDir.resolve("llama-server.exe"), "variant");

    Path result = invokeResolveCpuBaselineExe(tempDir);
    assertEquals(variantDir.resolve("llama-server.exe"), result,
        "Should ignore VARIANTS but NOT variant (singular)");
  }

  /**
   * Uses reflection to invoke the private static resolveCpuBaselineExe method.
   */
  private static Path invokeResolveCpuBaselineExe(Path aiHome) throws Exception {
    Method method = RuntimeActivationService.class.getDeclaredMethod("resolveCpuBaselineExe", Path.class);
    method.setAccessible(true);
    return (Path) method.invoke(null, aiHome);
  }
}
