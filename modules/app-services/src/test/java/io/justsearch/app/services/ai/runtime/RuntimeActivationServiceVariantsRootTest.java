/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.policy.EnterprisePolicyServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 913 H1 — variants-root resolution order.
 *
 * <p>The defect: on an agent worktree stack, {@code ai_activate} failed {@code
 * RUNTIME_VARIANT_NOT_INSTALLED} with {@code installedVariants: []} while the dev-runner had
 * already resolved the shared main-checkout cuda12 build into {@code JUSTSEARCH_SERVER_EXE}. Both
 * of the old candidates miss in a worktree: {@code JUSTSEARCH_HOME} points at the worktree's
 * {@code .dev-data} (no {@code native-bin} under it) and {@code RepoRootLocator} resolves the
 * WORKTREE root, which has no {@code modules/ui/native-bin} because worktrees share the main
 * checkout's build rather than copying 10 MB of it. The resolved exe was the one fact that named
 * the right tree, and nothing looked at it.
 *
 * <p>Three tiers, because each alone passes for the wrong reason:
 *
 * <ol>
 *   <li>the pure-resolver cases pin the ORDER, but call the static directly and so say nothing
 *       about how the instance reaches it;
 *   <li>{@link #statusListsTheSharedVariantWithoutJunctions} pins the READ PATH end to end — that
 *       {@code getStatus()} reaches the config-resolved exe at all — but it stages the exe and
 *       publishes the {@code ConfigStore} BEFORE constructing the service, so an eagerly-cached
 *       root would be cached from an already-correct world and it would still pass;
 *   <li>{@link #statusReDerivesOnceTheExeBecomesResolvable} is the one that pins LAZINESS: it
 *       builds the service in an empty world and only then makes the exe resolvable. The field was
 *       {@code final} and computed in the constructor, which is exactly the state this fails in.
 * </ol>
 *
 * <p>Tier 3 was added by review of #617: the original two-tier claim was wrong. The falsification
 * offered for it restored the eager cache while ALSO passing {@code null} for the exe, so it proved
 * exe-blindness rather than eagerness; restoring the eager cache with the resolver intact left all
 * of tiers 1-2 green.
 */
class RuntimeActivationServiceVariantsRootTest {

  private final TestEngineExecutors processExecutors = new TestEngineExecutors();

  @TempDir Path tmp;

  private String prevHome;
  private boolean homeCaptured;
  private ConfigStore prevStore;
  private boolean storeCaptured;

  @AfterEach
  void restore() {
    if (homeCaptured) {
      if (prevHome == null) {
        System.clearProperty("justsearch.home");
      } else {
        System.setProperty("justsearch.home", prevHome);
      }
      homeCaptured = false;
    }
    if (storeCaptured) {
      io.justsearch.configuration.resolved.TestResolvedConfigHelper.restoreGlobal(prevStore);
      storeCaptured = false;
    }
    processExecutors.close();
  }

  /** A {@code .../variants/<id>/llama-server.exe} tree, returning the exe. */
  private Path stageVariantExe(Path checkoutRoot, String variantId) throws Exception {
    Path exe =
        checkoutRoot
            .resolve("modules")
            .resolve("ui")
            .resolve("native-bin")
            .resolve("llama-server")
            .resolve("variants")
            .resolve(variantId)
            .resolve("llama-server.exe");
    Files.createDirectories(exe.getParent());
    Files.writeString(exe, "not-a-real-exe");
    return exe;
  }

  @Test
  @DisplayName("aiHome wins when it holds a variants dir — production resolution is unchanged")
  void aiHomeStillWinsWhenPresent() throws Exception {
    Path aiHome = tmp.resolve("home");
    Path installed = aiHome.resolve("native-bin/llama-server/variants/cuda12");
    Files.createDirectories(installed);
    Path foreignExe = stageVariantExe(tmp.resolve("main"), "cuda12");

    assertEquals(
        aiHome.resolve("native-bin/llama-server/variants"),
        RuntimeActivationService.resolveVariantsRoot(aiHome, tmp.resolve("worktree"), foreignExe),
        "a real install must not be re-rooted at wherever the last activation launched from");
  }

  @Test
  @DisplayName("THE DEFECT: worktree aiHome + worktree repoRoot both miss → the exe names the root")
  void resolvedExeSuppliesTheRootWhenBothLegacyCandidatesMiss() throws Exception {
    Path worktreeDataDir = tmp.resolve("worktree/modules/ui-web/.dev-data");
    Files.createDirectories(worktreeDataDir);
    Path sharedExe = stageVariantExe(tmp.resolve("main"), "cuda12");

    Path resolved =
        RuntimeActivationService.resolveVariantsRoot(
            worktreeDataDir, tmp.resolve("worktree"), sharedExe);

    assertEquals(
        sharedExe.getParent().getParent(),
        resolved,
        "the variants root must be derived from the resolved server exe");
    assertTrue(
        Files.isDirectory(resolved.resolve("cuda12")),
        "and it must be the root that actually contains the cuda12 variant");
  }

  @Test
  @DisplayName("repoRoot dev layout still wins over the exe when it exists (non-worktree checkout)")
  void repoRootIsTriedAndTheExeOnlyFillsTheGap() throws Exception {
    Path repoRoot = tmp.resolve("checkout");
    stageVariantExe(repoRoot, "cuda12");
    // No exe supplied at all: the pre-existing chain must be untouched.
    assertEquals(
        repoRoot.resolve("modules/ui/native-bin/llama-server/variants"),
        RuntimeActivationService.resolveVariantsRoot(tmp.resolve("empty-home"), repoRoot, null),
        "a plain dev checkout must resolve exactly as before");
  }

  @Test
  @DisplayName("an exe outside a variants/ tree contributes nothing (BYO binary)")
  void exeNotUnderVariantsIsIgnored() throws Exception {
    Path byo = tmp.resolve("elsewhere/llama-server.exe");
    Files.createDirectories(byo.getParent());
    Files.writeString(byo, "byo");
    Path aiHome = tmp.resolve("empty-home");

    assertEquals(
        aiHome.resolve("native-bin/llama-server/variants"),
        RuntimeActivationService.resolveVariantsRoot(aiHome, tmp.resolve("no-repo"), byo),
        "falls through to the standard path, which is what produces the consistent error message");
  }

  @Test
  @DisplayName("a variants-shaped exe whose root is not on disk is not trusted")
  void exeUnderNonexistentVariantsDirIsIgnored() {
    Path ghost = tmp.resolve("gone/llama-server/variants/cuda12/llama-server.exe");
    Path aiHome = tmp.resolve("empty-home");

    assertEquals(
        aiHome.resolve("native-bin/llama-server/variants"),
        RuntimeActivationService.resolveVariantsRoot(aiHome, null, ghost),
        "a path that names a variants dir but is not one must not become the root");
  }

  @Test
  @DisplayName("a null aiHome yields the exe-derived root, or null when there is none")
  void nullAiHomeIsNotAnNpe() throws Exception {
    Path sharedExe = stageVariantExe(tmp.resolve("main"), "cuda12");
    assertEquals(
        sharedExe.getParent().getParent(),
        RuntimeActivationService.resolveVariantsRoot(null, null, sharedExe));
    assertNull(RuntimeActivationService.resolveVariantsRoot(null, null, null));
  }

  /**
   * The tier that a correct-but-eagerly-cached resolver would fail: build the service while nothing
   * is on disk under {@code justsearch.home}, with the shared exe published through the config
   * store exactly as the dev-runner's {@code JUSTSEARCH_SERVER_EXE} reaches it, and read the
   * variants back through the public status surface the {@code ai_activate} failure came from.
   */
  @Test
  @DisplayName("getStatus() lists the shared cuda12 variant on a worktree-shaped install")
  void statusListsTheSharedVariantWithoutJunctions() throws Exception {
    Path aiHome = tmp.resolve("worktree/modules/ui-web/.dev-data");
    Files.createDirectories(aiHome);
    Path sharedExe = stageVariantExe(tmp.resolve("main"), "cuda12");

    prevHome = System.getProperty("justsearch.home");
    homeCaptured = true;
    System.setProperty("justsearch.home", aiHome.toAbsolutePath().toString());

    prevStore = ConfigStore.globalOrNull();
    storeCaptured = true;
    ConfigStore.setGlobal(
        new ConfigStore(
            ResolvedConfig.builder()
                .putDefault("justsearch.server.exe", sharedExe.toAbsolutePath().toString())
                .build()));

    RuntimeActivationService service =
        new RuntimeActivationService(processExecutors,
            OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
            null,
            new EnterprisePolicyServiceImpl());

    assertTrue(
        service.getStatus().installedVariants().stream()
            .anyMatch(v -> "cuda12".equals(v.variantId())),
        "installedVariants was [] on every worktree stack — the shared cuda12 build must be listed"
            + " without the two junctions the live validation needed as a workaround");
  }

  /**
   * The tier that actually fails on an eager cache (added by #617 review, S2-1).
   *
   * <p>Builds the service in an empty world — no {@code ConfigStore} global, nothing under
   * {@code justsearch.home} — so any value computed at construction is {@code []}. THEN makes the
   * shared exe resolvable, exactly as a dev stack does when the config store is published after
   * boot, or as a fresh profile does when Install AI finishes. A correct resolver behind a
   * construction-time cache answers {@code []} forever here; only re-derivation recovers.
   */
  @Test
  @DisplayName("getStatus() re-derives once the exe becomes resolvable, rather than freezing []")
  void statusReDerivesOnceTheExeBecomesResolvable() throws Exception {
    Path aiHome = tmp.resolve("worktree/modules/ui-web/.dev-data");
    Files.createDirectories(aiHome);

    prevHome = System.getProperty("justsearch.home");
    homeCaptured = true;
    System.setProperty("justsearch.home", aiHome.toAbsolutePath().toString());

    prevStore = ConfigStore.globalOrNull();
    storeCaptured = true;
    // No global at all: resolvedServerExeOrNull() must degrade to null, not throw.
    io.justsearch.configuration.resolved.TestResolvedConfigHelper.restoreGlobal(null);

    RuntimeActivationService service =
        new RuntimeActivationService(processExecutors,
            OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
            null,
            new EnterprisePolicyServiceImpl());

    assertTrue(
        service.getStatus().installedVariants().isEmpty(),
        "nothing is installed and no exe is resolvable yet, so the status must say so");

    Path sharedExe = stageVariantExe(tmp.resolve("main"), "cuda12");
    ConfigStore.setGlobal(
        new ConfigStore(
            ResolvedConfig.builder()
                .putDefault("justsearch.server.exe", sharedExe.toAbsolutePath().toString())
                .build()));

    assertTrue(
        service.getStatus().installedVariants().stream()
            .anyMatch(v -> "cuda12".equals(v.variantId())),
        "an eagerly-cached variants root would still answer [] here — the memo must re-derive"
            + " once the world it was computed in has changed");
  }
}
