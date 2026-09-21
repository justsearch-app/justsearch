package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for deterministic server executable discovery in InferenceConfig.findServerExecutable().
 */
class InferenceConfigServerExeTest {

  @TempDir Path tempDir;

  private String prevServerExe;
  private String prevRepoRoot;
  private ConfigStore prevStore;

  @BeforeEach
  void clearEnv() {
    prevServerExe = System.getProperty("justsearch.server.exe");
    prevRepoRoot = System.getProperty("justsearch.repo.root");
    prevStore = ConfigStore.globalOrNull();
    System.clearProperty("justsearch.server.exe");
    System.clearProperty("justsearch.repo.root");
    // Initialize ConfigStore so findServerExecutable() can read from it
    TestResolvedConfigHelper.storeFromEnvironment();
  }

  @AfterEach
  void restoreEnv() {
    if (prevServerExe == null) {
      System.clearProperty("justsearch.server.exe");
    } else {
      System.setProperty("justsearch.server.exe", prevServerExe);
    }
    if (prevRepoRoot == null) {
      System.clearProperty("justsearch.repo.root");
    } else {
      System.setProperty("justsearch.repo.root", prevRepoRoot);
    }
    TestResolvedConfigHelper.restoreGlobal(prevStore);
  }

  @Test
  @DisplayName("Finds canonical baseline at native-bin/llama-server/llama-server.exe first")
  void findsCanonicalBaselineFirst() throws Exception {
    // Create canonical path
    Path nativeBin = tempDir.resolve("native-bin").resolve("llama-server");
    Files.createDirectories(nativeBin);
    Path canonical = nativeBin.resolve("llama-server.exe");
    Files.writeString(canonical, "canonical");

    // Also create subdirectory with exe that would come first alphabetically
    Path aaaDir = nativeBin.resolve("aaa-build");
    Files.createDirectories(aaaDir);
    Files.writeString(aaaDir.resolve("llama-server.exe"), "aaa");

    Path result = invokeFindServerExecutable(tempDir);
    assertEquals(canonical, result, "Should find canonical path first, not subdirectory");
  }

  @Test
  @DisplayName("Finds CUDA variant when GPU preferred and no canonical path")
  void findsCudaVariantWhenGpuPreferred() throws Exception {
    Path nativeBin = tempDir.resolve("native-bin").resolve("llama-server");
    Path cudaDir = nativeBin.resolve("variants").resolve("cuda12");
    Files.createDirectories(cudaDir);
    Files.writeString(cudaDir.resolve("llama-server.exe"), "cuda12");

    Path result = invokeFindServerExecutable(tempDir, true);
    assertEquals(cudaDir.resolve("llama-server.exe"), result,
        "Should find CUDA variant when GPU preferred");
  }

  @Test
  @DisplayName("Falls back to CUDA variant even without GPU preference as last resort")
  void fallsBackToCudaVariantAsLastResort() throws Exception {
    Path nativeBin = tempDir.resolve("native-bin").resolve("llama-server");
    Path cudaDir = nativeBin.resolve("variants").resolve("cuda12");
    Files.createDirectories(cudaDir);
    Files.writeString(cudaDir.resolve("llama-server.exe"), "cuda12");

    Path result = invokeFindServerExecutable(tempDir, false);
    assertEquals(cudaDir.resolve("llama-server.exe"), result,
        "Should fall back to CUDA variant when nothing else exists");
  }

  @Test
  @DisplayName("Prefers CUDA variant over canonical when GPU preferred")
  void prefersCudaOverCanonicalWhenGpuPreferred() throws Exception {
    Path nativeBin = tempDir.resolve("native-bin").resolve("llama-server");
    Files.createDirectories(nativeBin);
    Files.writeString(nativeBin.resolve("llama-server.exe"), "canonical");

    Path cudaDir = nativeBin.resolve("variants").resolve("cuda12");
    Files.createDirectories(cudaDir);
    Files.writeString(cudaDir.resolve("llama-server.exe"), "cuda12");

    Path result = invokeFindServerExecutable(tempDir, true);
    assertEquals(cudaDir.resolve("llama-server.exe"), result,
        "Should prefer CUDA variant over canonical when GPU preferred");
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

    Path result = invokeFindServerExecutable(tempDir);
    assertEquals(aaaDir.resolve("llama-server.exe"), result,
        "Should pick first alphabetically sorted subdir (aaa-build)");
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
    Path first = invokeFindServerExecutable(tempDir);
    for (int i = 0; i < 5; i++) {
      Path next = invokeFindServerExecutable(tempDir);
      assertEquals(first, next, "Result should be deterministic across invocations");
    }

    // Should always pick "aaa" (first alphabetically)
    assertEquals(nativeBin.resolve("aaa").resolve("llama-server.exe"), first,
        "Should consistently pick alphabetically first subdir");
  }

  @Test
  @DisplayName("Returns fallback path when no executable exists")
  void returnsFallbackWhenNoExeExists() throws Exception {
    Path nativeBin = tempDir.resolve("native-bin").resolve("llama-server");
    Files.createDirectories(nativeBin);

    Path result = invokeFindServerExecutable(tempDir);
    assertEquals(nativeBin.resolve("llama-server.exe"), result,
        "Should return canonical path as fallback even if it doesn't exist");
  }

  @Test
  @DisplayName("Environment variable override takes precedence")
  void envOverrideTakesPrecedence() throws Exception {
    // Create canonical path
    Path nativeBin = tempDir.resolve("native-bin").resolve("llama-server");
    Files.createDirectories(nativeBin);
    Files.writeString(nativeBin.resolve("llama-server.exe"), "canonical");

    // Create custom path
    Path customExe = tempDir.resolve("custom").resolve("llama-server.exe");
    Files.createDirectories(customExe.getParent());
    Files.writeString(customExe, "custom");

    System.setProperty("justsearch.server.exe", customExe.toString());
    TestResolvedConfigHelper.storeFromEnvironment();

    Path result = invokeFindServerExecutable(tempDir);
    assertEquals(customExe, result, "Environment override should take precedence");
  }

  @Test
  @DisplayName("Explicit repo root provides fallback server executable")
  void repoRootFallbackProvidesServerExecutable() throws Exception {
    Path isolatedBase = tempDir.resolve("isolated");
    Files.createDirectories(isolatedBase);
    Path repoRoot = tempDir.resolve("repo-root");
    Path nativeBin = repoRoot.resolve("native-bin").resolve("llama-server");
    Files.createDirectories(nativeBin);
    Path repoExe = nativeBin.resolve("llama-server.exe");
    Files.writeString(repoExe, "repo");

    System.setProperty("justsearch.repo.root", repoRoot.toString());
    TestResolvedConfigHelper.storeFromEnvironment();

    Path result = invokeFindServerExecutable(isolatedBase);
    assertEquals(repoExe, result);
  }

  @Test
  @DisplayName("369: Finds dev-layout binary in Tauri shell resources")
  void findsDevLayoutTauriResources() throws Exception {
    Path isolatedBase = tempDir.resolve("isolated");
    Files.createDirectories(isolatedBase);
    Path repoRoot = tempDir.resolve("repo-root");
    // Don't create {repoRoot}/native-bin/ — force fallthrough to dev layout
    Path devNativeBin = repoRoot.resolve(
        "modules/shell/src-tauri/resources/headless/native-bin/llama-server");
    Files.createDirectories(devNativeBin);
    Path devExe = devNativeBin.resolve("llama-server.exe");
    Files.writeString(devExe, "dev-bundled");

    System.setProperty("justsearch.repo.root", repoRoot.toString());
    TestResolvedConfigHelper.storeFromEnvironment();

    Path result = invokeFindServerExecutable(isolatedBase);
    assertEquals(devExe, result);
  }

  @Test
  @DisplayName("369: Prefers CUDA variant in dev layout when GPU preferred")
  void prefersCudaVariantInDevLayout() throws Exception {
    Path isolatedBase = tempDir.resolve("isolated");
    Files.createDirectories(isolatedBase);
    Path repoRoot = tempDir.resolve("repo-root");
    Path devNativeBin = repoRoot.resolve(
        "modules/shell/src-tauri/resources/headless/native-bin/llama-server");
    Files.createDirectories(devNativeBin);
    Files.writeString(devNativeBin.resolve("llama-server.exe"), "cpu");
    Path cudaDir = devNativeBin.resolve("variants/cuda12");
    Files.createDirectories(cudaDir);
    Files.writeString(cudaDir.resolve("llama-server.exe"), "cuda12");

    System.setProperty("justsearch.repo.root", repoRoot.toString());
    TestResolvedConfigHelper.storeFromEnvironment();

    Path result = invokeFindServerExecutable(isolatedBase, true);
    assertEquals(cudaDir.resolve("llama-server.exe"), result);
  }

  /**
   * Uses reflection to invoke the private static findServerExecutable method (CPU mode).
   */
  private static Path invokeFindServerExecutable(Path baseDir) throws Exception {
    return invokeFindServerExecutable(baseDir, false);
  }

  /**
   * Uses reflection to invoke the private static findServerExecutable method.
   */
  private static Path invokeFindServerExecutable(Path baseDir, boolean preferCuda) throws Exception {
    Method method = InferenceConfig.class.getDeclaredMethod(
        "findServerExecutable", io.justsearch.configuration.resolved.ResolvedConfig.class, Path.class, boolean.class);
    method.setAccessible(true);
    return (Path) method.invoke(null, ConfigStore.global().get(), baseDir, preferCuda);
  }
}
