/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.ArrayList;
import java.util.List;

/**
 * v2 install progress state — package-oriented (not per-asset).
 *
 * <p>Mutable, synchronized externally by the install service. Serialized to JSON for the
 * {@code GET /api/ai/install/status} endpoint. This object is <em>session-ephemeral</em> — it is
 * NOT persisted to disk. After a restart, "is installed" is recomputed from on-disk model presence
 * by {@code AiInstallService.maybeRecomputeInstalledFromDisk} (tempdoc 562): install state is a
 * function of what is on disk, not a remembered event.
 *
 * <p>Moved from {@code io.justsearch.ui.ai.install} to {@code app-api} as part of tempdoc 519 §9
 * Block B2. The {@link AiInstallService} interface returns this type; the DTO must be reachable
 * from {@code app-services} for the interface contract to be honored across module boundaries.
 */
public final class AiInstallStatus {

  // Overall state
  public String state = "idle";
  public String phase = "idle";
  public String message = "";
  public long startedAtEpochMs;
  public long updatedAtEpochMs;
  public boolean cancelRequested;
  public String lastError = "";
  public String errorCode = "";

  // Download profile
  public String downloadProfile = "";

  // Aggregate progress
  public long totalBytes;
  public long downloadedBytes;

  /**
   * Bytes an interrupted earlier run left staged in {@code .partial} files that a resume will keep.
   *
   * <p>Deliberately NOT derived from this object's own {@code state}: {@code state == "cancelled"}
   * is session-ephemeral (see the class note above) and reads {@code "idle"} again after a restart,
   * so a UI keyed on it would tell a returning user their multi-GB progress is gone. This field is
   * recomputed from DISK by {@code InstallPlanner} (which probes the {@code .partial} staging paths)
   * whenever the plan is re-derived — at boot, on cancellation, and on every plan preview — so it
   * survives a restart the way the bytes themselves do.
   */
  public long resumableBytes;

  /**
   * True while an in-flight run is halted between files by {@code POST /api/ai/install/pause}
   * (tempdoc 840 Phase 4). Read live from the acquisition pause gate on every status read, so it
   * cannot disagree with the gate the run actually waits on.
   *
   * <p>Deliberately NOT a {@link #state} value: a paused run is still {@code running} — it holds its
   * op-lease, its plan and its place in the set — and every existing consumer keyed on {@code state}
   * would have had to learn a new terminal-looking word to keep working.
   */
  public boolean paused;

  /**
   * Measured transfer rate in bytes per second, or {@code -1} when there is no honest basis for one.
   *
   * <p><b>{@code -1} is "unknown", not a value, and it is never {@code 0}.</b> {@code
   * AcquisitionRate} returns its {@code UNKNOWN} sentinel for a window with too few samples, a
   * window too short to measure, a transfer that has stopped reporting, and a window over which no
   * bytes arrived — and this field carries that sentinel through unchanged rather than flattening it
   * to zero. A fabricated {@code 0 B/s} on a transfer that is merely young is the most confident lie
   * the surface could tell, which is the defect the sentinel exists to prevent; {@code -1} is
   * visibly not a rate, so a consumer that forgets to test it fails loudly instead of plausibly.
   * Same convention as {@code startupEstimate.ts}'s {@code < 0} arm, which renders no number at all.
   *
   * <p>Reset to {@code -1} on every non-running state, because a rate measured by a run that has
   * ended describes a transfer that is no longer happening.
   */
  public double bytesPerSecond = -1d;

  /**
   * Seconds until the WHOLE run finishes at {@link #bytesPerSecond}, or {@code -1} when unknown.
   *
   * <p>{@code 0} is a value here (nothing left to move); {@code -1} is the sentinel. Never derived
   * from anything but the measured rate — an unknown rate yields an unknown horizon, never a guessed
   * one.
   *
   * <p>Run-wide, not stage-wide: each stage's scheduler measures only its own slice, so its native
   * horizon answers "time left in this stage". This is the same measured rate re-horizoned onto the
   * run's remaining bytes ({@code AcquisitionRate.Estimate.reHorizon}) — one estimator asked two
   * questions, rather than a second estimator that could disagree with the first.
   */
  public long remainingSeconds = -1L;

  /**
   * True only when state == "completed" AND no package failed AND no package was skipped for a
   * reason that LIMITS the install AND all required runtime config keys were written. Distinguishes
   * "installed cleanly" from "installed with limitations" without breaking the existing state enum.
   * Tempdoc 374 sandbox round 2 finding #8.
   *
   * <p>When false but state == "completed", the message field describes which
   * limitations apply (e.g. "Installed with limitations: chat (no CUDA).").
   *
   * <p><b>Not every skip is a limitation</b> ({@code SkipCause.limitsInstall}, tempdoc 941 round 19
   * F3). Counting all of them made this flag unsatisfiable by any install on any machine: one
   * {@code devOnly} registry package the planner excludes from every user plan was enough to hold
   * it false forever, under a message blaming the user's hardware. Only a HARDWARE skip is a
   * limitation; a packaging exclusion, a mode exclusion and the user's own decline are not.
   */
  public boolean installedFully;

  /**
   * Package ids the CURRENT registry declares but the install contract that recorded this
   * installation never covered — i.e. artifacts a NEWER app version added after the user installed
   * (tempdoc 804 §B8, round-10 F2: one new cuda-runtime package made a complete 16 GB installation
   * read {@code installedFully: false, packages: []} until a full re-run).
   *
   * <p>Completeness is a claim about the contract that installed it, so a registry addition is a
   * distinct state — "extra AI components are available" — not retroactive non-installation.
   * Empty whenever the plan has nothing left to download (the ordinary complete install) and after
   * any install run terminates.
   */
  public final List<String> pendingRegistryAdditions = new ArrayList<>();

  /**
   * True when ANY file the current registry requires for the selected profile is missing from disk
   * — deliberately independent of whether the install contract claimed it (tempdoc 805 G.3 W-TRUTH,
   * derisk U3 amendment).
   *
   * <p>Round 11's upgraded machine is why the two axes are separate: the ORT CUDA natives PR #276
   * added were a registry addition (so {@code installedFully} stays truthfully true and {@code
   * pendingRegistryAdditions} names the package), yet their absence had a real consequence — every
   * ONNX encoder silently ran on CPU. Completeness is a claim about history; this is a claim about
   * consequence, and the UI routes it to the existing Repair flow (which collects terms acceptance —
   * no auto-download).
   */
  public boolean repairNeeded;

  /**
   * Registry files this profile declares that are absent from disk and that no consumer requires
   * (tempdoc 824 §3.3b) — {@code splade/config.json} and the other metadata sidecars carrying
   * {@code "required": false}.
   *
   * <p>Its own list precisely because it must NOT feed {@link #repairNeeded}: round 16 lost one
   * 872-byte optional file and the product said "a required component is missing" while SPLADE was
   * serving 1 660 inferences on CUDA. Surfaced so nothing is hidden; never alarming, and never a
   * reason to offer Repair.
   */
  public final List<OptionalGap> optionalGaps = new ArrayList<>();

  /** One optional registry file absent from disk. */
  public static final class OptionalGap {
    public String packageId = "";
    public String fileName = "";

    public OptionalGap() {}

    public OptionalGap(String packageId, String fileName) {
      this.packageId = packageId == null ? "" : packageId;
      this.fileName = fileName == null ? "" : fileName;
    }

    OptionalGap copy() {
      return new OptionalGap(packageId, fileName);
    }
  }

  /**
   * The acquisition stage in flight ({@code core} | {@code enrichment} | {@code chat}), or empty
   * outside a staged run (tempdoc 840 Phase 3).
   *
   * <p>The install is delivered in ordered stages so search works after the first ~1.3 GB instead of
   * after all ~7 GB. A caller that wants to say "search is ready, still downloading enrichment"
   * reads this together with {@link #stages} and {@link #readyCapabilities}.
   */
  public String currentStage = "";

  /**
   * Every acquisition stage of this run, in run order — including the ones with nothing to do.
   *
   * <p>A projection of the plan's own partition, not a second progress authority: a stage's bytes
   * are its slice of {@link #totalBytes} and its {@code downloadedBytes} is the same acquisition
   * counter {@link #downloadedBytes} is written from.
   */
  public final List<StageStatus> stages = new ArrayList<>();

  /**
   * Capability-tier ids ({@code retrieval-core}, {@code runtime}, {@code retrieval-enrichment},
   * {@code llm}) that are already usable — the tiers of every stage that completed, acquired its
   * files and had the Worker restarted onto them.
   *
   * <p>Fail-closed: a stage that ended with a failed package contributes nothing, because a
   * capability whose model did not land is not usable no matter how far the run got. Empty until the
   * first stage completes.
   */
  public final List<String> readyCapabilities = new ArrayList<>();

  /** One acquisition stage's progress. */
  public static final class StageStatus {
    /** Stage id: {@code core} | {@code enrichment} | {@code chat}. */
    public String stage = "";

    public String label = "";

    /**
     * {@code pending} | {@code running} | {@code completed} | {@code failed} | {@code skipped} |
     * {@code cancelled} | {@code blocked}. {@code skipped} means the stage had nothing to acquire
     * (already installed, hardware-skipped or user-declined) — distinct from {@code completed}, which
     * means this run delivered something. {@code blocked} means a precondition refused it before it
     * started, so nothing was attempted and nothing broke; see {@link #blockedReason}.
     */
    public String state = "pending";

    /**
     * Why a {@code blocked} stage was refused, in words a user can act on — today, that the disk has
     * too little room for what this stage would fetch (tempdoc 840 U2). Empty for every other state.
     *
     * <p>Carried per STAGE rather than on the run, because staging makes the answer per-stage: a disk
     * that fits the retrieval core but not the chat model blocks one stage and completes the other,
     * and a single run-level message could not say that.
     */
    public String blockedReason = "";

    /** Capability-tier ids this stage delivers. */
    public final List<String> capabilities = new ArrayList<>();

    public long totalBytes;
    public long downloadedBytes;

    public StageStatus() {}

    StageStatus copy() {
      StageStatus c = new StageStatus();
      c.stage = stage;
      c.label = label;
      c.state = state;
      c.blockedReason = blockedReason;
      c.capabilities.addAll(capabilities);
      c.totalBytes = totalBytes;
      c.downloadedBytes = downloadedBytes;
      return c;
    }
  }

  // Per-package progress
  public final List<PackageStatus> packages = new ArrayList<>();

  /** Per-package progress tracking. */
  public static final class PackageStatus {
    public String packageId = "";
    public String label = "";

    /**
     * The registry's one-line explanation of what this package is for — e.g. "Vector embeddings for
     * semantic search" (tempdoc 840 Phase 4).
     *
     * <p>It has existed on {@code ModelPackage} since the v2 registry and has never reached a
     * surface, which is half of why a multi-GB install reads as an opaque progress bar: the user is
     * shown seven file names and no statement of what any of them does.
     */
    public String description = "";

    /**
     * How badly the product needs this package, as {@code Necessity}'s kebab-case registry id:
     * {@code required} | {@code improves-results} | {@code adds-feature} | {@code infrastructure}
     * (tempdoc 840 Phase 4). The axis a component list is categorised on — it answers the only
     * question a user can act on, "what do I lose if I say no?".
     */
    public String necessity = "";

    /**
     * Whether a user may decline this package at all — {@code Necessity.userDeclinable()}, projected.
     *
     * <p>Carried so the surface does not have to re-derive declinability from {@link #necessity} and
     * cannot get it wrong in the direction that matters (offering a switch for {@code embedding} or
     * {@code cuda-runtime}). The backend refuses such a decline anyway; this keeps the affordance
     * from being drawn in the first place.
     */
    public boolean declinable;

    /**
     * Whether the user has currently declined this package ({@code UiSettings.declinedAiPackages}).
     *
     * <p>A live preference, not a record of what happened, so it is re-read on every status read
     * rather than stamped when the package list was built: a decline made between two polls must be
     * visible on the next one. The historical counterpart is the {@code skipReason} a run records
     * when the planner drops a declined package.
     */
    public boolean declined;

    /**
     * Capability-tier id (tempdoc 657): {@code retrieval-core} | {@code retrieval-enrichment} |
     * {@code llm} | {@code runtime}, or null for an untagged package. Lets the UI group the
     * download by tier (retrieval vs the optional LLM) without hardcoding the package taxonomy.
     */
    public String tier;

    /**
     * The acquisition stage that delivers this package ({@code core} | {@code enrichment} | {@code
     * chat}), or null when the run is not staged. A projection of {@link #tier} through the install
     * run's tier→stage mapping — never independently assigned, so the two cannot disagree.
     */
    public String stage;

    public String state = "pending";
    public long bytesDownloaded;
    public long bytesTotal;
    public String skipReason = "";

    /**
     * The planner's TYPED reason this package was skipped, as {@code SkipCause}'s kebab-case id:
     * {@code hardware} | {@code intent} | {@code user-declined} | {@code dev-only}. Empty for a
     * package that was not skipped, and for a skip whose cause is unknown (tempdoc 941 round 19,
     * F3).
     *
     * <p>{@link #skipReason} is prose for display; this is what logic reads. The completion message
     * used to hardcode "skipped on this hardware" for every skip, so a package the PACKAGER excluded
     * ({@code chat-compact}, {@code devOnly}) and a component the USER declined were both reported
     * to the user as limitations of their machine — the prose-as-classification defect this
     * codebase removed elsewhere, arriving instead as prose-as-assumption.
     *
     * <p>Unknown is empty, never a guess: a consumer must fail closed onto the hardware verdict
     * rather than read an unclassified skip as harmless.
     */
    public String skipCause = "";

    public String error = "";

    /**
     * True when at least one of this package's files continued a download an earlier run had left
     * on disk (an HTTP {@code Range} resume or a resumed BITS job) rather than starting from byte
     * zero. Projects {@code ResumableFetch.Outcome.firstAction} so the UI can tell a returning user
     * their cancelled progress was kept — the reassurance the resumable-cancel work earns but which
     * is otherwise invisible.
     */
    public boolean resumed;

    /**
     * What the RUNTIME observes about this package's capability, projected from the same authority
     * {@code GET /api/ai/runtime/status} publishes ({@code onnxFeatures[].status} ×
     * {@code modelActive}, derived by {@code EncoderRuntimeExplainer}): {@code "active"} |
     * {@code "inactive"} | {@code "unknown"} (tempdoc 824 §3.3c).
     *
     * <p>Bookkeeping ("a file is missing") and consequence ("the capability is down") are two
     * different claims, and round 16 asserted the second using only the first. Defaults to
     * {@code "unknown"} and degrades to it whenever nothing has observed the capability — never to
     * a positive claim in either direction, so the alarming copy stays the fail-closed default.
     */
    public String functionalStatus = "unknown";

    /**
     * Why this package will not converge on its own (tempdoc 824 §3.4). {@code
     * "TRANSPORT_UNAVAILABLE"} after {@link #attempts} spread over three consecutive repair passes
     * all failed the same file at transport; empty while automatic repair is still worth offering.
     *
     * <p>An affordance that cannot succeed must not be presented as the remedy — that is round
     * 16's user-facing defect, independent of the network. {@code repairNeeded} stays true (a
     * required file IS missing); the REMEDY becomes the manual fallback below.
     */
    public String terminalReason = "";

    /** Total transport attempts spent on this package's still-missing file, across repair passes. */
    public int attempts;

    /** Direct download URL of the file that will not transfer — the manual fallback's source. */
    public String url = "";

    /** Where that file has to land — the manual fallback's destination. */
    public String targetPath = "";

    PackageStatus copy() {
      PackageStatus c = new PackageStatus();
      c.packageId = packageId;
      c.label = label;
      c.description = description;
      c.necessity = necessity;
      c.declinable = declinable;
      c.declined = declined;
      c.tier = tier;
      c.stage = stage;
      c.state = state;
      c.bytesDownloaded = bytesDownloaded;
      c.bytesTotal = bytesTotal;
      c.skipReason = skipReason;
      c.skipCause = skipCause;
      c.error = error;
      c.resumed = resumed;
      c.functionalStatus = functionalStatus;
      c.terminalReason = terminalReason;
      c.attempts = attempts;
      c.url = url;
      c.targetPath = targetPath;
      return c;
    }
  }

  /**
   * An independent deep copy, safe to hand across a boundary that serializes without the install
   * lock held.
   *
   * <p>The install thread mutates the live instance under a lock — including {@code
   * packages.clear()} followed by a repopulate — while the {@code GET /api/ai/install/status}
   * handler serializes at 1 Hz outside it. Returning the live object let Jackson iterate that list
   * while it was being cleared, i.e. a {@code ConcurrentModificationException} in the one moment the
   * user is definitely watching the download. Every scalar is copied and every collection is rebuilt
   * from copied ELEMENTS, because {@link PackageStatus} and {@link OptionalGap} are mutable too — a
   * shallow list copy would still expose the live elements.
   */
  public AiInstallStatus snapshot() {
    AiInstallStatus c = new AiInstallStatus();
    c.state = state;
    c.phase = phase;
    c.message = message;
    c.startedAtEpochMs = startedAtEpochMs;
    c.updatedAtEpochMs = updatedAtEpochMs;
    c.cancelRequested = cancelRequested;
    c.lastError = lastError;
    c.errorCode = errorCode;
    c.downloadProfile = downloadProfile;
    c.totalBytes = totalBytes;
    c.downloadedBytes = downloadedBytes;
    c.resumableBytes = resumableBytes;
    c.paused = paused;
    c.bytesPerSecond = bytesPerSecond;
    c.remainingSeconds = remainingSeconds;
    c.installedFully = installedFully;
    c.repairNeeded = repairNeeded;
    c.currentStage = currentStage;
    c.readyCapabilities.addAll(readyCapabilities);
    for (StageStatus st : stages) {
      c.stages.add(st.copy());
    }
    c.pendingRegistryAdditions.addAll(pendingRegistryAdditions);
    for (OptionalGap gap : optionalGaps) {
      c.optionalGaps.add(gap.copy());
    }
    for (PackageStatus ps : packages) {
      c.packages.add(ps.copy());
    }
    return c;
  }
}
