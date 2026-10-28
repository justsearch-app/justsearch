/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.preflight;

import io.justsearch.app.api.AiRuntimeStatusResponse;
import io.justsearch.app.services.ai.install.AiInstallService;
import io.justsearch.app.services.ai.runtime.RuntimeActivationService;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.InstallIntent;
import io.justsearch.configuration.model.InstallPlanner;
import io.justsearch.configuration.model.ModelPackage;
import io.justsearch.configuration.model.ModelRegistry;
import io.justsearch.configuration.model.ModelVariant;
import io.justsearch.configuration.model.SupportingFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tempdoc 656 Task 4: registry-driven preflight — answers "given the model registry (the
 * declared source of truth for what should exist, and where), what's actually present on disk
 * right now" without requiring a running/activating inference process.
 *
 * <p>This is deliberately a thin reconciliation over two already-existing, already-correct
 * primitives — {@link AiInstallService#getManifest()} + {@link AiInstallService#modelsDir()} for
 * model-file identity/location, and {@link RuntimeActivationService#getStatus()} for runtime-exe
 * presence — not a new independent prober. It does not download, install, or mutate anything.
 */
public final class AiPreflightService {

  private final AiInstallService installService;
  private final RuntimeActivationService runtimeService;

  public AiPreflightService(AiInstallService installService, RuntimeActivationService runtimeService) {
    this.installService = installService;
    this.runtimeService = runtimeService;
  }

  public AiPreflightResult getPreflight() {
    ModelRegistry registry = installService.getManifest();
    Path modelsDir = installService.modelsDir();
    Path aiHome = installService.aiHome();
    // Tempdoc 772 Q3: the severity signal (blockingIncomplete) needs the same intent + hardware the
    // planner uses to decide what's actually wanted on THIS machine. Reuse the install service's own
    // resolvers rather than re-deriving them here.
    HardwareProfile hardware = installService.buildHardwareProfile();
    InstallIntent intent = installService.installIntent();
    // Tempdoc 840 Phase 2: the user's per-component intent is the third axis the planner selects on,
    // so the severity signal has to see it too — otherwise a component the user deliberately turned
    // off comes back as a BLOCKING gap on this surface. Same reasoning as intent/hardware above: read
    // the install service's own resolver, never a second copy of the preference.
    Set<String> declined = installService.declinedPackages();
    AiRuntimeStatusResponse runtimeStatus = runtimeService.getStatus();

    return computePreflight(
        registry, modelsDir, aiHome, hardware, intent, declined, runtimeStatus);
  }

  /**
   * Pure reconciliation of registry + on-disk presence + hardware/intent into a preflight result —
   * package-private + static so the {@code blockingIncomplete} severity logic (tempdoc 772 Q3) can be
   * unit-tested deterministically by staging files under a temp {@code modelsDir} and passing a
   * chosen {@link HardwareProfile} / {@link InstallIntent}, without a live GPU probe or classpath
   * registry.
   */
  static AiPreflightResult computePreflight(
      ModelRegistry registry,
      Path modelsDir,
      Path aiHome,
      HardwareProfile hardware,
      InstallIntent intent,
      Set<String> declined,
      AiRuntimeStatusResponse runtimeStatus) {
    List<PackageStatus> packages = new ArrayList<>();
    for (ModelPackage pkg : registry.packages()) {
      // Tempdoc 842: dev-only packages are not part of any user install, so they have nothing to be
      // reconciled against — they are dropped from the reported list entirely rather than reported
      // incomplete-but-not-blocking. A never-installed chat-compact must not surface as something
      // repair/completeness UIs can offer to fix.
      if (pkg.devOnly()) {
        continue;
      }
      Path baseDir = pkg.installRoot() != null ? aiHome.resolve(pkg.installRoot()) : modelsDir;
      Path pkgDir = baseDir.resolve(pkg.targetDir());
      // Acquisition retains ONNX assets in a directory keyed by the selected variant and
      // supporting-file identities. Reconcile that same directory, including incomplete
      // candidates; retain flat-directory discovery for older installs.
      Path candidateDir = baseDir.resolve(
          InstallPlanner.effectiveTargetDir(pkg, pkg.selectVariant(hardware.downloadProfile())));
      if (Files.isDirectory(candidateDir)) {
        pkgDir = candidateDir;
      }

      List<String> presentVariantFiles = new ArrayList<>();
      for (ModelVariant v : pkg.variants()) {
        if (Files.isRegularFile(pkgDir.resolve(v.filename()))) {
          presentVariantFiles.add(v.filename());
        }
      }
      List<String> missingSupportingFiles = new ArrayList<>();
      for (SupportingFile sf : pkg.supportingFiles()) {
        if (!Files.isRegularFile(pkgDir.resolve(sf.filename()))) {
          missingSupportingFiles.add(sf.filename());
        }
      }

      // A package with no variants at all (e.g. cuda-runtime, which ships only supportingFiles)
      // is "present" purely on its supporting files being complete; otherwise at least one
      // variant (any precision/EP) must be present, mirroring how CPU vs. GPU variants are
      // alternatives, not all-required.
      boolean variantsSatisfied = pkg.variants().isEmpty() || !presentVariantFiles.isEmpty();
      boolean complete = variantsSatisfied && missingSupportingFiles.isEmpty();

      // Tempdoc 772 Q3: derived severity. A package is BLOCKING-incomplete iff it is wanted by the
      // current intent AND hardware-permitted (i.e. would be part of the plan — reuses the planner's
      // own include/skip verdict, not a re-implemented CUDA/tier check) YET not complete. A package
      // unwanted-by-intent or hardware-gated-off stays blockingIncomplete=false even when incomplete
      // (that's the implicit "that's fine" case); a wanted+permitted incomplete package is the new
      // severity signal.
      boolean blockingIncomplete =
          !complete && InstallPlanner.isIncludedByPlan(pkg, registry, intent, declined, hardware);

      packages.add(
          new PackageStatus(
              pkg.id(),
              pkg.label(),
              complete,
              blockingIncomplete,
              presentVariantFiles,
              missingSupportingFiles));
    }

    boolean runtimeInstalled =
        !runtimeStatus.installedVariants().isEmpty()
            || (runtimeStatus.active() != null
                && runtimeStatus.active().serverExecutablePath() != null
                && !runtimeStatus.active().serverExecutablePath().isBlank());

    // Tempdoc 656 (post-implementation review fix): canActivateDefault must mirror what
    // RuntimeActivationService.runActivate() actually checks — the chat GGUF variant file plus the
    // runtime executable — NOT full package completeness. The "chat" package's supportingFiles
    // include mmproj-F16.gguf, which is a separate, VDU-only concern (LifecycleReasonCode
    // .VDU_MISSING_MMPROJ / vdu.missing_mmproj already model it as its own capability); runActivate
    // never references it. Gating on PackageStatus.complete() here would report "cannot activate"
    // in a case where activation would actually succeed. presentVariantFiles() alone matches
    // runActivate's real precondition; complete() remains available on PackageStatus for other
    // consumers that legitimately want full-download-completeness, not activation-readiness.
    boolean chatVariantPresent =
        packages.stream()
            .filter(p -> "chat".equals(p.id()))
            .findFirst()
            .map(p -> !p.presentVariantFiles().isEmpty())
            .orElse(false);

    boolean canActivateDefault = runtimeInstalled && chatVariantPresent;

    return new AiPreflightResult(packages, runtimeInstalled, canActivateDefault);
  }

  /**
   * Per-package presence, projected from the registry's own identity fields (id/label).
   *
   * @param blockingIncomplete tempdoc 772 Q3 — true iff this package is wanted by the current install
   *     intent AND hardware-permitted (would be part of the plan) YET not complete. A package that is
   *     unwanted-by-intent or hardware-gated-off stays false even when incomplete; only a
   *     wanted+permitted+incomplete package is blocking. Lets a consumer distinguish an incompleteness
   *     that actually matters on this machine from one that is expected ("that's fine").
   */
  public record PackageStatus(
      String id,
      String label,
      boolean complete,
      boolean blockingIncomplete,
      List<String> presentVariantFiles,
      List<String> missingFiles) {}

  /**
   * Overall preflight result.
   *
   * @param canActivateDefault mirrors {@code RuntimeActivationService.runActivate()}'s actual
   *     precondition (runtime executable + chat GGUF variant present) — deliberately not the same
   *     as every package's {@code complete()}, since some supportingFiles (e.g. the chat package's
   *     mmproj) are required for other capabilities (VDU) but not for base activation.
   */
  public record AiPreflightResult(
      List<PackageStatus> packages, boolean runtimeInstalled, boolean canActivateDefault) {}
}
