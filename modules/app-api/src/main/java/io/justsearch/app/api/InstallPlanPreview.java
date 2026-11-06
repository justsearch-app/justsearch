/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Pre-install, side-effect-free projection of the download plan, grouped by capability tier
 * (tempdoc 657). Served at {@code GET /api/ai/install/plan-preview} so the UI can show an honest
 * first-run weight breakdown — "Core retrieval (already installed)" vs "Chat &amp; AI answers — 6.4 GB
 * to download" — <em>before</em> the user commits, instead of a single opaque "several GB" string.
 *
 * <p>Realizes tempdoc 381 §F ("present the plan to the user before download") by reusing the pure
 * {@code InstallPlanner}; computing it runs no downloads.
 *
 * <p>Mutable plain DTO, serialized to JSON (no schema/codegen — the FE reads it untyped).
 */
public final class InstallPlanPreview {

  /** The active {@code InstallIntent} id ({@code full-desktop} | {@code headless} | {@code mcp-lite}). */
  public String intent = "";

  /** The hardware-selected {@code DownloadProfile} name (e.g. {@code GPU_FULL}). */
  public String downloadProfile = "";

  /**
   * Bytes that still have to come over the network across all wanted tiers on this hardware —
   * {@code InstallPlan.remainingBytes()}, i.e. already-complete files excluded AND bytes an earlier
   * interrupted run left staged in {@code .partial} files excluded. The number a consent surface
   * must state, because it is what the download will actually cost.
   */
  public long totalDownloadBytes;

  /**
   * Of the planned downloads, bytes already on disk from an interrupted earlier run that a resume
   * will keep. Non-zero means "there is a paused download here"; the pause promise ("everything
   * already downloaded stays on disk") is only checkable by the user if this is stated.
   */
  public long resumableBytes;

  /**
   * For a "Download again" preview only: the identity of this exact plan (the parts included, the
   * declines it was planned with, every file it downloads and the settings witness those declines
   * were read against). The accepted start presents it back and refuses when the plan changed;
   * empty for an ordinary preview.
   */
  public String planDigest = "";

  /** Per-tier estimates, in canonical tier order. */
  public final List<TierEstimate> tiers = new ArrayList<>();

  /**
   * One row per registry component, in registry order (tempdoc 840 Phase 4 follow-up).
   *
   * <p>This lives on the PREVIEW rather than on {@code AiInstallStatus.packages} because the
   * component list is a question about the PLAN — "what would be installed on this machine, and where
   * does each piece stand" — not about a run. {@code status.packages} is run bookkeeping: it is empty
   * on an idle machine that is not fully installed, so a surface that asked it "what components are
   * there" before the first install would be told "none". The preview already reads the registry, the
   * hardware profile, the intent and the declined set, so it can answer completely without a run and
   * without a second computation.
   *
   * <p>During a run, {@code AiInstallStatus} remains the live-progress overlay; these rows are the
   * standing list it overlays onto.
   */
  public final List<ComponentEstimate> components = new ArrayList<>();

  /**
   * One component as the user will meet it: what it is, whether they need it, what it costs, and
   * where it currently stands. Every field is projected from the registry or the pure plan — nothing
   * here is a second authority.
   */
  public static final class ComponentEstimate {
    /** Registry package id ({@code embedding}, {@code reranker}, …). */
    public String id = "";

    /** Human label ({@code "Search reranker"}). */
    public String label = "";

    /**
     * One line on what this component actually does. Carried on {@code ModelPackage} since the v2
     * registry was written and rendered nowhere until now — which is half of why the install reads as
     * an opaque multi-GB wait rather than a set of nameable capabilities.
     */
    public String description = "";

    /** Capability-tier id, for grouping. */
    public String tier = "";

    /**
     * {@code required} | {@code improves-results} | {@code adds-feature} | {@code infrastructure}.
     * The axis that decides how a component is presented — NOT a synonym for optional. "Improves
     * results" is the dangerous one to mislabel: a user who turns off the reranker to save 340 MB
     * gets measurably worse search and no way to connect cause to effect.
     */
    public String necessity = "";

    /**
     * Whether the user may turn this component off at all — derived from {@code necessity}, never
     * stored twice. False for the required core and for infrastructure.
     */
    public boolean declinable;

    /** Whether the user HAS turned it off (their standing preference, not this run's history). */
    public boolean declined;

    /** Full footprint at the selected profile, whether or not it still needs downloading. */
    public long totalBytes;

    /** Bytes still to fetch ({@code 0} ⇒ already on disk, declined, or excluded). */
    public long downloadBytes;

    /**
     * {@code installed} | {@code to-download} | {@code declined} | {@code unavailable} |
     * {@code not-in-mode}. Derived from the plan, so it cannot disagree with what an install would
     * actually do.
     *
     * <p>{@code unavailable} is deliberately distinct from {@code declined}: hardware the machine
     * does not have is not a choice the user made, and presenting it as an unticked box would imply
     * an option that does not exist.
     */
    public String state = "";

    /**
     * Why an {@code unavailable} component cannot run here, in the planner's own words (it already
     * writes user-facing prose naming the actual constraint). Empty for every other state.
     */
    public String unavailableReason = "";
  }

  /** One capability tier's weight estimate. */
  public static final class TierEstimate {
    /** Tier id ({@code retrieval-core} | {@code retrieval-enrichment} | {@code llm} | {@code runtime}). */
    public String tier = "";

    /** Human-readable tier label. */
    public String label = "";

    /** Whether the active intent wants this tier at all (false ⇒ excluded by mode). */
    public boolean includedByIntent;

    /** Full footprint of this tier's packages at the selected profile (informational). */
    public long totalBytes;

    /** Bytes still to download for this tier ({@code 0} ⇒ already present / not applicable). */
    public long downloadBytes;
  }
}
