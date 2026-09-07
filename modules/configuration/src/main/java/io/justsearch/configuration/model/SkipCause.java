/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

/**
 * Why a model package was not installed — the typed classification beside the prose
 * {@code skipReason} (tempdoc 840 Phase 2).
 *
 * <p>The prose stays for display; this is what logic reads. Classifying by parsing a human-readable
 * reason string is the prose-as-classification defect Phase 0 removed elsewhere, and it is exactly
 * the kind that survives a reworded message.
 *
 * <p>Produced once, by {@link InstallPlanner}, on the {@link InstallPlan.SkippedPackage} it emits;
 * carried unchanged into {@link InstallContract.InstalledModel}. The planner is the only authority
 * for the decision, so the contract writer never re-derives it.
 *
 * <p>There is deliberately no {@code POLICY} value. Administrator policy does not skip a package —
 * {@code policyBlocksDownloads()} fails the whole install with {@code DOWNLOADS_DISABLED}, so no
 * producer could ever emit it. A declared-but-unproduced value reads as wired when it is not; the
 * repo gates that phantom-value class elsewhere (tempdoc 837). Add it when something skips for it.
 */
public enum SkipCause {
  /** The machine cannot run it: no CUDA, or not enough VRAM for the GGUF floor. */
  HARDWARE("hardware", true),
  /** The active {@link InstallIntent} does not want this package's capability tier. */
  INTENT("intent", false),
  /** The user declined this component (only possible for a {@link Necessity#userDeclinable} one). */
  USER_DECLINED("user-declined", false),
  /**
   * The package exists for development stacks only ({@link ModelPackage#devOnly}, tempdoc 842) and
   * is never part of a user install plan — independent of intent, hardware and user choice.
   */
  DEV_ONLY("dev-only", false);

  private final String id;
  private final boolean limitsInstall;

  SkipCause(String id, boolean limitsInstall) {
    this.id = id;
    this.limitsInstall = limitsInstall;
  }

  /** The kebab-case identifier this cause is published under on the wire. */
  public String id() {
    return id;
  }

  /**
   * Whether a skip for this cause leaves the install SHORT of what the user asked for — the only
   * kind {@code AiInstallStatus.installedFully} may count, and the only kind the "Installed with
   * limitations: … skipped on this hardware." banner may name (tempdoc 941 round 19, F3).
   *
   * <p>Only {@link #HARDWARE} is a limitation. The other three are decisions, and describing a
   * decision as a shortfall of the machine is how a clean 10.89 GB install came to report {@code
   * installedFully:false} with a hardware excuse for a package the PACKAGER excluded:
   *
   * <ul>
   *   <li>{@link #DEV_ONLY} — never in any user plan, so counting it made {@code installedFully}
   *       unreachable on every machine: no install could satisfy the product's own contract.
   *   <li>{@link #USER_DECLINED} — "an install the user shaped by declining a component is
   *       COMPLETE, not partial" is already the rule {@code InstallCompleteness} states and
   *       enforces; this is that rule reaching the second truth claim.
   *   <li>{@link #INTENT} — a mode that does not want a tier is not missing it.
   * </ul>
   *
   * <p>An UNKNOWN cause is deliberately not represented here: a consumer that cannot classify a skip
   * must fail closed onto {@link #HARDWARE}'s answer rather than assume the skip was harmless.
   */
  public boolean limitsInstall() {
    return limitsInstall;
  }

  /**
   * The cause published under {@code id}, or {@code null} when the id is absent or unrecognised —
   * "unknown", which every caller must resolve fail-closed (see {@link #limitsInstall()}).
   */
  public static SkipCause fromId(String id) {
    if (id == null || id.isBlank()) return null;
    for (SkipCause c : values()) {
      if (c.id.equals(id)) return c;
    }
    return null;
  }
}
