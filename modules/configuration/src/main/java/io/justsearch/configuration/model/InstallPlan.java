/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

import java.util.List;
import java.util.Map;

/**
 * A computed download plan — what to download given the registry, hardware, and current state.
 *
 * <p>The plan is inspectable (can be shown to the user before downloading) and re-computable (run
 * the planner again after hardware changes to get a delta plan).
 *
 * @param profile the download profile selected for this hardware
 * @param downloads files to download (model variants + supporting files)
 * @param skipped model packages skipped due to hardware constraints
 * @param totalBytes total size of the planned downloads, in bytes — the denominator the install
 *     run's per-package progress is measured against (a resumed file still ends up this large)
 * @param alreadyInstalled model packages that are already correctly installed
 * @param resumableBytes of {@code totalBytes}, how many are already staged on disk in {@code
 *     .partial} files from an earlier, interrupted run. Bytes a resumed download will not fetch
 *     again — so {@link #remainingBytes()}, not {@code totalBytes}, is what the network still owes.
 */
public record InstallPlan(
    DownloadProfile profile,
    List<PlannedDownload> downloads,
    List<SkippedPackage> skipped,
    long totalBytes,
    List<String> alreadyInstalled,
    long resumableBytes) {

  public InstallPlan {
    if (downloads == null) downloads = List.of();
    if (skipped == null) skipped = List.of();
    if (alreadyInstalled == null) alreadyInstalled = List.of();
    if (resumableBytes < 0) resumableBytes = 0;
  }

  /** Backwards-compat constructor — a plan with nothing staged on disk. */
  public InstallPlan(
      DownloadProfile profile,
      List<PlannedDownload> downloads,
      List<SkippedPackage> skipped,
      long totalBytes,
      List<String> alreadyInstalled) {
    this(profile, downloads, skipped, totalBytes, alreadyInstalled, 0L);
  }

  /**
   * Bytes that still have to come over the network — {@code totalBytes} minus whatever an earlier
   * run already staged. This is the number a pre-download consent surface owes the user; {@code
   * totalBytes} is the file-size total the progress bar counts up to.
   */
  public long remainingBytes() {
    return Math.max(0L, totalBytes - resumableBytes);
  }

  /**
   * A single file to download.
   *
   * @param packageId which model package this belongs to
   * @param url download URL
   * @param targetPath relative path under modelsDir (e.g., "onnx/gte-multilingual-base/model.onnx")
   * @param sha256 expected SHA-256 hash
   * @param sizeBytes expected file size
   * @param isModelVariant true if this is the ONNX model file (vs supporting file)
   * @param extract when true, after download + SHA verification the file is unzipped into its
   *     parent directory. Used for the alpha.15 CUDA runtime package — bundled DLLs are too
   *     large for the NSIS installer, so they ship as a downloaded + extracted archive instead.
   *     The archive is kept on disk for extraction-only repair without downloading again.
   * @param required whether the package's capability needs this file (tempdoc 824 §3.3a). Carried
   *     through from {@code SupportingFile.required}; a model variant is always required. This is
   *     the axis {@code InstallCompleteness} reads to keep an optional metadata gap out of the
   *     "a required component is missing" verdict. Defaults to true everywhere it is unstated.
   * @param stagedBytes of {@code sizeBytes}, how many an earlier interrupted run already left in
   *     this file's {@code .partial} staging path. Carried PER FILE, not only as the plan-wide
   *     {@link InstallPlan#resumableBytes} total, because every consumer that asks "what does this
   *     file still cost?" — the disk precondition, a per-component cost row, a per-tier estimate —
   *     needs the answer at its own granularity, and each one deriving it separately is how the
   *     headline total came to disagree with the rows beneath it. Zero everywhere nothing is staged.
   * @param extractionOnly true when a kept archive needs applying; contributes no transfer bytes
   * @param destinationArchives for an archive, the file name and SHA-256 of every archive the current
   *     registry extracts into its directory. Receipts there of other archives are retired when it
   *     applies, so a renamed pack does not conflict with the one it replaced; entries may not land on
   *     these targets or their download temporaries; ORT's marker shows only when all have a receipt.
   *     Null when unknown: every receipt takes part in the overlap rule.
   */
  public record PlannedDownload(
      String packageId,
      String url,
      String targetPath,
      String sha256,
      long sizeBytes,
      boolean isModelVariant,
      boolean extract,
      boolean required,
      long stagedBytes,
      boolean extractionOnly,
      Map<String, String> destinationArchives) {

    public PlannedDownload {
      if (stagedBytes < 0) stagedBytes = 0;
      if (destinationArchives != null) destinationArchives = Map.copyOf(destinationArchives);
    }

    /** Compatibility constructor: every receipt in the destination takes part in the overlap rule. */
    public PlannedDownload(String packageId, String url, String targetPath, String sha256,
        long sizeBytes, boolean isModelVariant, boolean extract, boolean required, long stagedBytes,
        boolean extractionOnly) {
      this(packageId, url, targetPath, sha256, sizeBytes, isModelVariant, extract, required,
          stagedBytes, extractionOnly, null);
    }

    /** Compatibility constructor for ordinary acquisition. */
    public PlannedDownload(String packageId, String url, String targetPath, String sha256,
        long sizeBytes, boolean isModelVariant, boolean extract, boolean required, long stagedBytes) {
      this(packageId, url, targetPath, sha256, sizeBytes, isModelVariant, extract, required,
          stagedBytes, false);
    }

    /** Backwards-compat constructor — non-extracted, required file (existing behavior). */
    public PlannedDownload(
        String packageId,
        String url,
        String targetPath,
        String sha256,
        long sizeBytes,
        boolean isModelVariant) {
      this(packageId, url, targetPath, sha256, sizeBytes, isModelVariant, false, true, 0L);
    }

    /** Backwards-compat constructor — required file with an explicit extract flag. */
    public PlannedDownload(
        String packageId,
        String url,
        String targetPath,
        String sha256,
        long sizeBytes,
        boolean isModelVariant,
        boolean extract) {
      this(packageId, url, targetPath, sha256, sizeBytes, isModelVariant, extract, true, 0L);
    }

    /** Backwards-compat constructor — a file with nothing staged on disk. */
    public PlannedDownload(
        String packageId,
        String url,
        String targetPath,
        String sha256,
        long sizeBytes,
        boolean isModelVariant,
        boolean extract,
        boolean required) {
      this(packageId, url, targetPath, sha256, sizeBytes, isModelVariant, extract, required, 0L);
    }

    /**
     * Bytes this file still has to come over the network — its size minus what is already staged.
     * The number a cost estimate and a free-space precondition owe the user; {@link #sizeBytes} is
     * the file-size total a progress bar counts up to.
     */
    public long remainingBytes() {
      return extractionOnly ? 0L : Math.max(0L, sizeBytes - stagedBytes);
    }
  }

  /**
   * A model package that was skipped.
   *
   * @param packageId which model package
   * @param cause the typed classification of why (tempdoc 840 Phase 2) — what logic reads. Decided
   *     once, here in the planner, and carried into the install contract unchanged so the contract
   *     writer never re-derives it from the prose.
   * @param reason why it was skipped, for display (e.g., "Insufficient VRAM for GGUF (6 GB < 7.5
   *     GB)"). Display only: classifying by parsing this string is the defect {@code cause} exists
   *     to remove.
   */
  public record SkippedPackage(String packageId, SkipCause cause, String reason) {}
}
