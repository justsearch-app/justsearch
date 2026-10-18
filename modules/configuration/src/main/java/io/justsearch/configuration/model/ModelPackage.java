/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

import java.util.List;

/**
 * A complete model package in the registry — one logical model with all its variants and supporting
 * files.
 *
 * <p>Example: the "embedding" package contains an FP32 variant (CPU), an FP16 variant (CUDA), plus
 * a tokenizer and pooling config as supporting files.
 *
 * @param id unique model identifier (e.g., "embedding", "splade", "reranker", "ner",
 *     "citation-scorer", "chat")
 * @param label human-readable label for UI (e.g., "Embedding model")
 * @param description short description for UI (e.g., "Vector embeddings for semantic search")
 * @param targetDir directory under {@code modelsDir} (or under {@code homeDir/installRoot} when
 *     {@code installRoot} is set) where files are placed (e.g., "onnx/gte-multilingual-base")
 * @param variants model file variants (one per precision/EP combination)
 * @param supportingFiles non-model files (tokenizer, config, etc.) — always downloaded
 * @param minVramBytes minimum VRAM to include this package (0 = always include). Used for GGUF
 *     models that require GPU VRAM to be usable.
 * @param termsUrl URL to model license/terms page (nullable)
 * @param installRoot when non-null, files install relative to {@code homeDir.resolve(installRoot)}
 *     instead of {@code modelsDir}. Tempdoc 374 alpha.15 fix B — used by the {@code cuda-runtime}
 *     package which lands DLLs at {@code native-bin/llama-server/variants/cuda12} alongside the
 *     bundled cuda12 llama-server variant, NOT under {@code models/}.
 * @param license SPDX license identifier for this package's artifacts (e.g. {@code "Apache-2.0"},
 *     {@code "AFL-3.0"}, {@code "LicenseRef-NVIDIA-CUDA-EULA"}). Single-sources model attribution so
 *     the generated NOTICE projects from the registry rather than a hand-maintained fork (tempdoc 632).
 *     Nullable for backward-compatibility (registries predating the field).
 * @param tier capability tier this package serves (tempdoc 657) — the axis an {@link InstallIntent}
 *     selects over, orthogonal to the hardware {@link DownloadProfile}. Nullable for
 *     backward-compatibility (registries predating the field); an untagged package is treated as
 *     always-wanted by every intent.
 * @param requiresCuda true if this package requires a CUDA-capable GPU regardless of any other
 *     hardware threshold — distinct from {@code minVramBytes} (a VRAM floor within a
 *     hardware-eligible package); false (default) means this package's hardware-eligibility is
 *     governed by tier/VRAM only. Added for RUNTIME-tier packages that may need to be
 *     hardware-independent (tempdoc 772 Q3).
 * @param devOnly true if this package exists for development stacks only and is never part of a
 *     user install plan (tempdoc 842) — unconditional, independent of intent, tier and hardware.
 *     Dev tooling fetches it directly from the registry; false (default) is every user-facing
 *     package, so registries predating the field are unchanged.
 * @param necessity how badly the product needs this package (tempdoc 840 Phase 2) — the axis a
 *     per-component install decision is offered on, and the sole source of whether the user may
 *     decline it ({@link Necessity#userDeclinable()}). Never null: an unclassified package
 *     normalizes to {@link Necessity#REQUIRED}, so nobody's omission can make a package
 *     switch-off-able.
 * @param nativeTarget native-targets descriptor key for all artifacts in this package; null means portable
 * @param dependsOn ids of packages this one cannot function without (tempdoc 840 Phase 2). Today
 *     the only edge is "…→ cuda-runtime", declared by every package that can select a CUDA/FP16
 *     variant: it makes the previously implicit H1 invariant (no CUDA variant is selected unless
 *     the CUDA runtime is installed) checkable rather than merely true-by-construction via
 *     {@code profile.usesCuda()}. Never null; empty means no declared dependency.
 */
public record ModelPackage(
    String id,
    String label,
    String description,
    String targetDir,
    List<ModelVariant> variants,
    List<SupportingFile> supportingFiles,
    long minVramBytes,
    String termsUrl,
    String installRoot,
    String license,
    CapabilityTier tier,
    boolean requiresCuda,
    boolean devOnly,
    Necessity necessity,
    List<String> dependsOn,
    String nativeTarget) {

  /**
   * Compact constructor — normalize nulls to empty lists, and an unclassified {@code necessity} to
   * the fail-closed {@link Necessity#REQUIRED}. Normalizing here (not only at the registry loader)
   * means no construction path can produce a package whose declinability is undefined.
   */
  public ModelPackage {
    if (variants == null) variants = List.of();
    if (supportingFiles == null) supportingFiles = List.of();
    if (dependsOn == null) dependsOn = List.of();
    if (necessity == null) necessity = Necessity.REQUIRED;
    if (nativeTarget != null) io.justsearch.configuration.NativeTargets.forTarget(nativeTarget);
  }

  /** Registries without a native target contain portable artifacts, as before. */
  public ModelPackage(String id, String label, String description, String targetDir,
      List<ModelVariant> variants, List<SupportingFile> supportingFiles, long minVramBytes,
      String termsUrl, String installRoot, String license, CapabilityTier tier,
      boolean requiresCuda, boolean devOnly, Necessity necessity, List<String> dependsOn) {
    this(id, label, description, targetDir, variants, supportingFiles, minVramBytes, termsUrl,
        installRoot, license, tier, requiresCuda, devOnly, necessity, dependsOn, null);
  }

  /** Backwards-compat constructor — no installRoot (existing default behavior under modelsDir). */
  public ModelPackage(
      String id,
      String label,
      String description,
      String targetDir,
      List<ModelVariant> variants,
      List<SupportingFile> supportingFiles,
      long minVramBytes,
      String termsUrl) {
    this(id, label, description, targetDir, variants, supportingFiles, minVramBytes, termsUrl, null, null);
  }

  /** Backwards-compat constructor — installRoot but no license (predates tempdoc 632's license field). */
  public ModelPackage(
      String id,
      String label,
      String description,
      String targetDir,
      List<ModelVariant> variants,
      List<SupportingFile> supportingFiles,
      long minVramBytes,
      String termsUrl,
      String installRoot) {
    this(
        id, label, description, targetDir, variants, supportingFiles, minVramBytes, termsUrl,
        installRoot, null);
  }

  /** Backwards-compat constructor — installRoot + license but no tier (predates tempdoc 657). */
  public ModelPackage(
      String id,
      String label,
      String description,
      String targetDir,
      List<ModelVariant> variants,
      List<SupportingFile> supportingFiles,
      long minVramBytes,
      String termsUrl,
      String installRoot,
      String license) {
    this(
        id, label, description, targetDir, variants, supportingFiles, minVramBytes, termsUrl,
        installRoot, license, null);
  }

  /**
   * Backwards-compat constructor — no requiresCuda (predates tempdoc 772's hardware-independence
   * field); defaults to false, preserving prior tier-based-only hardware gating for any existing
   * caller. devOnly (tempdoc 842) defaults to false — user-installable. Necessity and dependsOn
   * (tempdoc 840) default via the compact constructor to REQUIRED and empty — the conservative
   * pair: not declinable, no declared dependency.
   */
  public ModelPackage(
      String id,
      String label,
      String description,
      String targetDir,
      List<ModelVariant> variants,
      List<SupportingFile> supportingFiles,
      long minVramBytes,
      String termsUrl,
      String installRoot,
      String license,
      CapabilityTier tier) {
    this(
        id, label, description, targetDir, variants, supportingFiles, minVramBytes, termsUrl,
        installRoot, license, tier, false, false, null, null);
  }

  /**
   * Backwards-compat constructor — no devOnly (predates tempdoc 842's dev-only exclusion flag);
   * defaults to false, so every existing caller keeps producing a user-installable package.
   * Necessity and dependsOn (tempdoc 840) default via the compact constructor to REQUIRED and
   * empty.
   */
  public ModelPackage(
      String id,
      String label,
      String description,
      String targetDir,
      List<ModelVariant> variants,
      List<SupportingFile> supportingFiles,
      long minVramBytes,
      String termsUrl,
      String installRoot,
      String license,
      CapabilityTier tier,
      boolean requiresCuda) {
    this(
        id, label, description, targetDir, variants, supportingFiles, minVramBytes, termsUrl,
        installRoot, license, tier, requiresCuda, false, null, null);
  }

  /** Returns true if this package requires a minimum VRAM threshold to be useful. */
  public boolean hasVramRequirement() {
    return minVramBytes > 0;
  }

  /**
   * Selects the best variant for the given download profile.
   *
   * <p>If the profile uses CUDA, prefers a CUDA-targeted variant (FP16). Otherwise, prefers a
   * CPU-targeted variant (FP32 or INT8). If no variant matches the preferred EP, falls back to the
   * first available variant. This ensures CPU-only models (e.g., citation-scorer with only an INT8
   * CPU variant) are included in GPU download profiles.
   *
   * @return the selected variant, or null only if the package has no variants at all
   */
  public ModelVariant selectVariant(DownloadProfile profile) {
    ExecutionProvider targetEP =
        profile.usesCuda() ? ExecutionProvider.CUDA : ExecutionProvider.CPU;
    return variants.stream()
        .filter(v -> v.targetEP() == targetEP)
        .findFirst()
        .orElse(variants.isEmpty() ? null : variants.get(0));
  }
}
