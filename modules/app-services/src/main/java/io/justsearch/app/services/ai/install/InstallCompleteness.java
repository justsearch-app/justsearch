/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.model.InstallPlan;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure per-FILE install-completeness decision (tempdoc 805 G.3 W-TRUTH).
 *
 * <p>Four authorities meet here and each answers exactly one question:
 *
 * <ul>
 *   <li>the <b>registry × profile</b> (via {@link InstallPlan}) — what is required now;
 *   <li><b>disk</b> (via the planner's own already-installed probe) — what is actually present: a
 *       required file is missing iff the plan still wants to download it;
 *   <li>the <b>{@link InstallContract}</b> — what the run that installed this machine claimed, at
 *       file granularity ({@code InstalledModel.installedFiles});
 *   <li>the <b>declined set</b> — what the user currently does not want (tempdoc 840 Phase 2).
 * </ul>
 *
 * <p><b>The declined set is INTENT, not the record.</b> It arrives as a parameter, read by the
 * service from settings; this function stays pure. It is deliberately the user's <em>current</em>
 * preference and not the contract's historical {@code SkipCause.USER_DECLINED}: if a user declines
 * the reranker today and re-enables it next month, intent flips and its missing files become a
 * real, offerable gap again. Reading the contract instead would freeze the old decision forever.
 *
 * <p>The resulting classification per missing file:
 *
 * <ul>
 *   <li>{@code MISSING_CONTRACTED} — the contract named this file as installed and it is gone. A
 *       real gap: {@code installedFully} must read false.
 *   <li>{@code MISSING_UNCONTRACTED} — the contract never named this file, so its absence is a
 *       newer app version's registry addition, not retroactive non-installation. Reported as a
 *       pending registry addition.
 * </ul>
 *
 * <p><b>Entry-kind rule (tempdoc 805 Part H, U3).</b> {@code ModelPackage.selectVariant} returns
 * null for a variantless package, so the contract writer recorded {@code cuda-runtime} as {@code
 * skipped("No variant")} with an EMPTY {@code installedFiles} list in both 0.1.0 and 0.2.0. A
 * skipped-kind entry therefore claims no files at all: its package's missing files classify as
 * {@code MISSING_UNCONTRACTED}. Round 11 read {@code installedFully:false} precisely because a
 * package-level {@code containsKey} matched that skipped entry and called it a contracted gap.
 *
 * <p>The same rule holds for an installed-kind entry: a file absent from its {@code installedFiles}
 * is uncontracted <em>even though the package is contracted</em> — the contract is a record of what
 * was installed, and it cannot have claimed a file that did not exist when it was written. This is
 * what makes the forward-fixed contract (see {@code AiInstallService.buildContract}, which now
 * records variantless-but-downloaded packages as installed-with-files) safe: adding per-file
 * authority for {@code cuda-runtime} does not turn a future added file into a false "real gap".
 *
 * <p>{@link #repairNeeded()} is deliberately independent of that classification: on a real upgraded
 * machine the missing ORT natives ARE registry additions, and repair must still be offered.
 * Contractedness decides only the {@code installedFully} truth claim and the additions list.
 *
 * <p><b>Required/optional axis (tempdoc 824 §3.3b).</b> A second, orthogonal axis runs beside
 * contractedness: whether the capability needs the file at all ({@code
 * InstallPlan.PlannedDownload.required}, carried from the registry). Round 16 lost an 872-byte
 * {@code splade/config.json} — a file no required-file list names and no resolver call site reads —
 * and the machine reported the same full-strength "a required component is missing" it would report
 * for a missing 500 MB model, while SPLADE was demonstrably serving on CUDA. So both truth claims
 * here count only REQUIRED files, and optional gaps get their own non-alarming list ({@link
 * #optionalGaps()}). This is strictly a relaxation on files that were never consumed as
 * requirements: for a required file every verdict is bit-for-bit what it was before.
 */
public record InstallCompleteness(
    List<FileState> files, boolean anyPackageInstalled, boolean contractPresent) {

  /** How one required file stands against disk, the install contract, and the user's intent. */
  public enum Classification {
    /** Present on disk. */
    SATISFIED,
    /** Missing from disk, and the contract claimed it — a real gap. */
    MISSING_CONTRACTED,
    /** Missing from disk, and the contract never claimed it — a registry addition. */
    MISSING_UNCONTRACTED,
    /** A kept archive still needs applying, independently of historical contract claims. */
    INCOMPLETE_ARCHIVE,
    /**
     * Absent because the user declined the package (tempdoc 840 Phase 2). Not a gap of any kind: a
     * deliberate decline must never read as an incomplete install nor raise a Repair prompt.
     */
    DECLINED;

    /**
     * Whether this classification is an unwanted absence — the only kind either truth claim counts.
     * Exists so DECLINED cannot be swept into a gap by a {@code != SATISFIED} test, which is how a
     * user's own choice would turn into "a required component is missing".
     */
    boolean isMissingGap() {
      return this == MISSING_CONTRACTED || this == MISSING_UNCONTRACTED || this == INCOMPLETE_ARCHIVE;
    }
  }

  /**
   * One required file.
   *
   * @param packageId the registry package the file belongs to
   * @param fileName the file's name (basename of the planned target path). Null only for a
   *     package the plan reports fully installed while the contract enumerates no files for it —
   *     "present, filenames unknown to this computation". Never null for a missing file.
   * @param classification the verdict for this file
   * @param required whether the package's capability needs this file (tempdoc 824 §3.3a). Always
   *     true for a satisfied file — the axis only ever decides how a MISSING file is reported.
   */
  public record FileState(
      String packageId, String fileName, Classification classification, boolean required) {

    /** Backwards-compat constructor — a required file (the pre-824 shape). */
    public FileState(String packageId, String fileName, Classification classification) {
      this(packageId, fileName, classification, true);
    }
  }

  /**
   * One optional file the registry declares that is absent from disk. Reported so the surface can
   * say what is missing without calling the capability broken.
   */
  public record OptionalGap(String packageId, String fileName) {}

  public InstallCompleteness {
    files = files == null ? List.of() : List.copyOf(files);
  }

  /**
   * Classifies every file the plan knows about.
   *
   * @param plan the current registry × hardware × disk plan; its {@code downloads()} are exactly the
   *     required-but-missing files, its {@code alreadyInstalled()} the fully-present packages
   * @param contract the install contract that recorded this machine's install, or null when absent
   *     / unreadable (a fresh machine, or a pre-contract install)
   */
  public static InstallCompleteness compute(InstallPlan plan, InstallContract contract) {
    return compute(plan, contract, Set.of());
  }

  /**
   * Classifies every file the plan knows about, against the user's current per-component intent.
   *
   * @param plan the current registry × hardware × disk plan; its {@code downloads()} are exactly the
   *     required-but-missing files, its {@code alreadyInstalled()} the fully-present packages
   * @param contract the install contract that recorded this machine's install, or null when absent
   *     / unreadable (a fresh machine, or a pre-contract install)
   * @param declined package ids the user currently does not want (tempdoc 840 Phase 2). Their
   *     absence is classified {@link Classification#DECLINED} and counts toward neither truth claim.
   *     Handled in BOTH plan positions on purpose: a plan computed with the same declined set
   *     reports those packages under {@code skipped()}, while a plan computed without it still lists
   *     their files under {@code downloads()} — either way the user's choice wins here.
   */
  public static InstallCompleteness compute(
      InstallPlan plan, InstallContract contract, Set<String> declined) {
    Set<String> declinedIds = declined == null ? Set.of() : declined;
    List<FileState> files = new ArrayList<>();
    boolean contractPresent = contract != null && !contract.models().isEmpty();

    if (plan != null) {
      for (String packageId : plan.alreadyInstalled()) {
        List<String> claimed = contractedFiles(contract, packageId);
        if (claimed.isEmpty()) {
          files.add(new FileState(packageId, null, Classification.SATISFIED));
        } else {
          for (String fileName : claimed) {
            files.add(new FileState(packageId, fileName, Classification.SATISFIED));
          }
        }
      }
      for (InstallPlan.PlannedDownload dl : plan.downloads()) {
        String fileName = fileNameOf(dl.targetPath());
        if (declinedIds.contains(dl.packageId())) {
          files.add(
              new FileState(dl.packageId(), fileName, Classification.DECLINED, dl.required()));
          continue;
        }
        boolean claimed = contractedFiles(contract, dl.packageId()).contains(fileName);
        files.add(
            new FileState(
                dl.packageId(),
                fileName,
                dl.extractionOnly() ? Classification.INCOMPLETE_ARCHIVE
                    : claimed ? Classification.MISSING_CONTRACTED : Classification.MISSING_UNCONTRACTED,
                dl.required()));
      }
      for (InstallPlan.SkippedPackage sk : plan.skipped()) {
        // A package the planner skipped BECAUSE the user declined it, recorded at package
        // granularity (the plan carries no filenames for a package it never planned) so {@link
        // #files} tells the whole story of why this machine's set is what it is. A package skipped
        // for hardware or intent reasons is NOT recorded here — it was never the user's choice and
        // has its own surfaces. The user-facing authority on declines is the per-component
        // `declined` flag on the plan preview, not this classification.
        if (declinedIds.contains(sk.packageId())) {
          files.add(new FileState(sk.packageId(), null, Classification.DECLINED));
        }
      }
    }

    boolean anyInstalled = plan != null && !plan.alreadyInstalled().isEmpty();
    return new InstallCompleteness(files, anyInstalled, contractPresent);
  }

  /**
   * The files the contract claims for a package. Empty for an absent entry AND for a skipped-kind
   * entry — a skipped entry recorded no install, so it claims nothing (the U3 entry-kind rule).
   */
  private static List<String> contractedFiles(InstallContract contract, String packageId) {
    if (contract == null || packageId == null) return List.of();
    InstallContract.InstalledModel entry = contract.models().get(packageId);
    if (entry == null || entry.skipped()) return List.of();
    return entry.installedFiles();
  }

  /** The basename of a planned target path (relative or absolute — installRoot packages emit absolute). */
  private static String fileNameOf(String targetPath) {
    if (targetPath == null) return "";
    int cut = Math.max(targetPath.lastIndexOf('/'), targetPath.lastIndexOf('\\'));
    return cut < 0 ? targetPath : targetPath.substring(cut + 1);
  }

  /**
   * Missing REQUIRED files — the only kind either truth claim counts (tempdoc 824 §3.3b). An
   * optional file's absence is reported by {@link #optionalGaps()} and nowhere else.
   */
  private List<FileState> missing() {
    return files.stream().filter(f -> f.classification().isMissingGap() && f.required()).toList();
  }

  /**
   * Whether the installation this machine's contract recorded is complete.
   *
   * <p>True when something is installed and no CONTRACTED required file is missing. Without a
   * contract the plan is the only authority, so any remaining required download keeps this false —
   * the pre-805 behaviour for a fresh/pre-contract machine, unchanged.
   *
   * <p>Declined packages are ignored (tempdoc 840 Phase 2): an install the user shaped by declining
   * a component is COMPLETE, not partial. Counting a decline here would leave every such machine
   * permanently reporting "Not Installed".
   */
  public boolean installedFully() {
    if (!anyPackageInstalled) return false;
    if (missing().isEmpty()) return true;
    if (!contractPresent) return false;
    return missing().stream().noneMatch(f -> f.classification() == Classification.MISSING_CONTRACTED
        || f.classification() == Classification.INCOMPLETE_ARCHIVE);
  }

  /**
   * Package ids (dedup, plan order) whose missing REQUIRED files the contract never claimed.
   *
   * <p>Optional gaps are deliberately excluded (tempdoc 824 §3.3b): they have their own list, and
   * reporting one here too would render it a second time as "new AI components are available — run
   * Install to add them", which is not what an 872-byte metadata file that failed to download is.
   */
  public List<String> pendingRegistryAdditions() {
    Set<String> ids = new LinkedHashSet<>();
    for (FileState f : files) {
      if (f.classification() == Classification.MISSING_UNCONTRACTED && f.required()) {
        ids.add(f.packageId());
      }
    }
    return List.copyOf(ids);
  }

  /**
   * Package ids (dedup, plan order) with at least one missing REQUIRED file — the packages disk
   * considers genuinely incomplete.
   *
   * <p>The per-package form of {@link #repairNeeded()}, so the completion MESSAGE can be derived
   * from the same authority as {@code installedFully} instead of from the run's own bookkeeping. A
   * package whose only casualty was optional is absent here, and must not be counted as failed in a
   * message that sits next to {@code installedFully: true}.
   */
  public List<String> packagesWithMissingRequiredFiles() {
    Set<String> ids = new LinkedHashSet<>();
    for (FileState f : missing()) {
      ids.add(f.packageId());
    }
    return List.copyOf(ids);
  }

  /**
   * Optional files the registry declares that are absent from disk (tempdoc 824 §3.3b). Surfaced,
   * never alarming: no truth claim here counts them, and no repair prompt is owed for them.
   */
  public List<OptionalGap> optionalGaps() {
    return files.stream()
        .filter(f -> f.classification().isMissingGap() && !f.required())
        .map(f -> new OptionalGap(f.packageId(), f.fileName()))
        .toList();
  }

  /**
   * Whether a repair is warranted: ANY file the current registry requires for this profile is
   * missing from disk — contracted or not. Round 11's machine had {@code installedFully} true and
   * an unusable GPU at the same time; that combination is exactly this signal's job.
   *
   * <p>Declined packages are excluded (tempdoc 840 Phase 2): a Repair prompt for a component the
   * user deliberately switched off is nagging, not a signal. It returns the moment intent flips
   * back, because this reads the current preference and not the contract's historical record.
   */
  public boolean repairNeeded() {
    return !missing().isEmpty();
  }
}
