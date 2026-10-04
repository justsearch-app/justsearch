/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import io.justsearch.configuration.model.CapabilityTier;
import io.justsearch.configuration.model.InstallPlan;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ordered acquisition stages an install run is delivered in, and the ONE place the capability
 * tiers are mapped onto them (tempdoc 840 Phase 3).
 *
 * <p>A new user used to wait for ~7 GB before anything worked, even though everything search needs
 * is ~1.3 GB of it. The set is acquired in three ordered stages. Each stage applies the
 * configuration enabled by its downloaded artifacts. Encoder paths are resolved at Engine
 * configuration build time; acquisition does not hot-reload them. {@link ConfigurationStage}
 * reports that JustSearch must be restarted to use the new configuration.
 *
 * <p><b>Why {@link #CORE} carries {@link CapabilityTier#RUNTIME} as well as {@link
 * CapabilityTier#RETRIEVAL_CORE}.</b> The runtime tier is the CUDA runtime DLL payload, and the core
 * embedding model's FP16 variant needs those DLLs to run on the GPU — {@code
 * AiInstallService.applyOrtNativePath} refuses to write {@code justsearch.onnxruntime.native_path}
 * while {@code OrtCudaHelper.checkMissingCudaRuntimeDlls} reports any of them missing, and every
 * ONNX encoder then silently falls back to CPU. Shipping a "search is ready" stage without the
 * runtime would therefore put the one REQUIRED encoder on CPU and call that ready. The pairing is
 * the reason this mapping is derived from the tier rather than declared per package: the registry
 * says what a package is FOR, and this says which of those capabilities have to arrive together.
 *
 * <p>The mapping is derived from {@link CapabilityTier} on purpose — no {@code stage} field is added
 * to the registry. A registry that named its own stages would be a second authority over an ordering
 * that only the install run has any use for, and it would drift from the tier the moment the two
 * disagreed.
 */
enum InstallStage {

  /** Everything search needs: the dense embedding model plus the GPU runtime it needs to run on. */
  CORE("core", "Search core"),

  /** SPLADE, reranker, NER, citation-scorer — retrieval degrades gracefully without them. */
  ENRICHMENT("enrichment", "Retrieval enrichment"),

  /** The GGUF chat model — the largest payload and the last thing search waits on. */
  CHAT("chat", "Chat & AI answers");

  private static final Logger log = LoggerFactory.getLogger(InstallStage.class);

  private final String id;
  private final String label;

  InstallStage(String id, String label) {
    this.id = id;
    this.label = label;
  }

  /** The kebab-case identifier the wire DTO carries. */
  String id() {
    return id;
  }

  /** Human-readable label for a stage-aware surface. */
  String label() {
    return label;
  }

  /**
   * The capability tiers this stage delivers — <b>the mapping</b>, in the one place it lives.
   *
   * <p>A switch over the constants rather than per-constant state so the compiler's exhaustiveness
   * check makes a new stage an immediate error here instead of a silently tier-less stage. See the
   * class javadoc for why {@link CapabilityTier#RUNTIME} is paired with {@link
   * CapabilityTier#RETRIEVAL_CORE} rather than standing alone or riding with the LLM.
   */
  Set<CapabilityTier> tiers() {
    return switch (this) {
      case CORE ->
          Collections.unmodifiableSet(
              EnumSet.of(CapabilityTier.RETRIEVAL_CORE, CapabilityTier.RUNTIME));
      case ENRICHMENT ->
          Collections.unmodifiableSet(EnumSet.of(CapabilityTier.RETRIEVAL_ENRICHMENT));
      case CHAT -> Collections.unmodifiableSet(EnumSet.of(CapabilityTier.LLM));
    };
  }

  /** The tier ids this stage delivers, in declaration order — the wire projection of {@link #tiers()}. */
  List<String> tierIds() {
    Set<CapabilityTier> tiers = tiers();
    List<String> ids = new ArrayList<>(tiers.size());
    for (CapabilityTier tier : tiers) {
      ids.add(tier.id());
    }
    return ids;
  }

  /**
   * The stage a package of {@code tier} belongs to.
   *
   * <p>An untagged package (null tier — the back-compat case {@link CapabilityTier#fromId} keeps
   * open) lands in the LAST stage. Deliberately not the first: an unclassified package put in {@link
   * #CORE} would silently inflate the time to first working search, which is the whole defect this
   * staging removes, and nothing about "untagged" claims search needs it. Independent of this
   * choice, a genuinely required file that is missing is still caught — {@code InstallCompleteness}
   * derives {@code repairNeeded} from the registry's required-file set, not from stage membership.
   */
  static InstallStage forTier(CapabilityTier tier) {
    if (tier != null) {
      for (InstallStage stage : values()) {
        if (stage.tiers().contains(tier)) {
          return stage;
        }
      }
    }
    return last();
  }

  /** The stage that runs first. */
  static InstallStage first() {
    return values()[0];
  }

  /** The stage that runs last — where anything unclassifiable is deferred to. */
  static InstallStage last() {
    return values()[values().length - 1];
  }

  /**
   * The op-lease class this stage registers under. One lease per stage rather than one blanket lease
   * per run: {@code OperationLeaseServiceImpl} keys leases by an opaque per-call {@code opId} and
   * never dedupes {@code opClass}, so the stages' leases coexist, and each can state a duration its
   * own bytes actually support instead of a run-wide guess.
   */
  String leaseOpClass() {
    return "ai.model-install." + id;
  }

  /**
   * One stage's share of a plan.
   *
   * @param stage which stage
   * @param downloads the planned files, in the plan's own order — a stage never reorders the plan,
   *     it only cuts it
   * @param bytes the sum of {@code downloads}' sizes; the denominator this stage's progress means
   *     something against
   * @param packageIds the registry packages this stage delivers, in first-seen order
   */
  record Slice(
      InstallStage stage, List<InstallPlan.PlannedDownload> downloads, long bytes, Set<String> packageIds) {

    Slice {
      downloads = List.copyOf(downloads);
      packageIds = Collections.unmodifiableSet(new LinkedHashSet<>(packageIds));
    }

    /** True when this stage has nothing to acquire — already installed, skipped, or declined. */
    boolean isEmpty() {
      return downloads.isEmpty();
    }

    /**
     * Bytes this stage still has to pull over the network, resume-adjusted.
     *
     * <p>Deliberately NOT the same number as {@link #bytes()}, and the two are not interchangeable.
     * {@code bytes()} is the full file-size sum, which is the denominator the progress counter is
     * measured against — an item is credited its FULL size when it is placed, whether it was
     * resumed or fetched from zero, so a stage total in any other unit would leave the bar unable
     * to reach its own end. This is what a COST question asks instead: a 6.34 GB file with 5.5 GB
     * already in its {@code .partial} owes the network and the disk ~0.84 GB, and charging the full
     * size would refuse (or over-quote) exactly the resume the staging design exists to preserve.
     */
    long remainingBytes() {
      long sum = 0L;
      for (InstallPlan.PlannedDownload dl : downloads) {
        sum += Math.max(0L, dl.remainingBytes());
      }
      return sum;
    }
  }

  /**
   * Cuts a plan's downloads into the ordered stages, preserving plan order within each stage.
   *
   * <p>Takes the tier lookup as a function rather than a {@code ModelRegistry} so the partitioning —
   * the part with the interesting decisions in it — is exercisable without a registry, the same seam
   * pattern the rest of this package uses.
   *
   * @param downloads the plan's planned files
   * @param tierByPackageId resolves a package id to its declared tier; may return null for an
   *     untagged or unknown package
   * @return every stage, in run order, including the ones with nothing in them — an empty stage is a
   *     fact the run reports rather than a gap in the sequence
   */
  static List<Slice> partition(
      List<InstallPlan.PlannedDownload> downloads,
      Function<String, CapabilityTier> tierByPackageId) {
    Map<InstallStage, List<InstallPlan.PlannedDownload>> byStage = new LinkedHashMap<>();
    Map<InstallStage, Set<String>> packagesByStage = new LinkedHashMap<>();
    for (InstallStage stage : values()) {
      byStage.put(stage, new ArrayList<>());
      packagesByStage.put(stage, new LinkedHashSet<>());
    }
    for (InstallPlan.PlannedDownload dl : downloads == null ? List.<InstallPlan.PlannedDownload>of() : downloads) {
      CapabilityTier tier = tierByPackageId == null ? null : tierByPackageId.apply(dl.packageId());
      if (tier == null) {
        log.warn(
            "Package [{}] declares no capability tier — deferring it to the {} stage so it cannot"
                + " delay the first working search",
            dl.packageId(),
            last().id());
      }
      InstallStage stage = forTier(tier);
      byStage.get(stage).add(dl);
      packagesByStage.get(stage).add(dl.packageId());
    }
    List<Slice> slices = new ArrayList<>(values().length);
    for (InstallStage stage : values()) {
      List<InstallPlan.PlannedDownload> stageDownloads = byStage.get(stage);
      long bytes = 0L;
      for (InstallPlan.PlannedDownload dl : stageDownloads) {
        bytes += Math.max(0L, dl.sizeBytes());
      }
      slices.add(new Slice(stage, stageDownloads, bytes, packagesByStage.get(stage)));
    }
    return slices;
  }
}
