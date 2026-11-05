/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import io.justsearch.app.api.DownloadedFiles;
import io.justsearch.app.api.OpCriticality;
import io.justsearch.app.api.OpLeaseOutcome;
import io.justsearch.app.api.OperationAdmissionClosedException;
import io.justsearch.app.api.OperationLeaseHandle;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.configuration.model.CapabilityTier;
import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.model.InstallPlanner;
import io.justsearch.configuration.model.ModelPackage;
import io.justsearch.configuration.model.ModelRegistry;
import io.justsearch.configuration.model.ModelVariant;
import io.justsearch.configuration.model.SupportingFile;
import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.configuration.persistence.OwnerOnlyDirectory;
import io.justsearch.configuration.persistence.WindowsOwnerOnlyDirectory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Remove files" for one downloaded part (design D4, the narrow option): it deletes exactly that
 * part's downloaded files when nothing uses them, otherwise it refuses with a plain reason and
 * deletes nothing. No drain, no deferred deletion, no automatic garbage collection.
 *
 * <p>Ownership evidence (decided): a file is removable only when (1) the registry lists it for
 * that part (any of its variants, or the historical folder the install contract records), (2) it
 * sits at that canonical path inside the managed models folder with no link or junction in the
 * path, any folder between it and the models folder, the models folder itself or any folder above
 * it (a linked models folder refuses the whole removal), (3) it is a regular file with one link,
 * (4) its content hash equals the registry's (checked in full immediately before deletion), and
 * (5) no other wanted part lists the same file. A file failing any rule is kept and listed with
 * its reason. Partial downloads are the installer's own {@code .partial} staging files beside
 * those canonical paths.
 *
 * <p>Capture before verify (decided): each file is first recorded in the journal, then atomically
 * renamed to a private name in a removal folder inside the models folder, then verified (same file
 * identity as resolved before the fence, regular, one link, registry hash) and only then deleted. A
 * failed rename keeps the file (a Windows sharing violation refuses that file); a failed check puts
 * the file back only if its place is free, by an atomic no-replace link (never an overwriting
 * move). Recovery after a crash acts only on a well-formed record whose paths lie inside the models
 * folder and whose folders are all plain; it reports the interrupted removal, puts captured files
 * back where it can, keeps everything else untouched and reported, and never deletes.
 *
 * <p>The removal folder is private (repair round 2): it is created owner-only (0700 on POSIX, a
 * protected owner-and-SYSTEM DACL on Windows), and before each capture, put-back and deletion it
 * must still be the same plain folder, owned by the Engine's user and writable by no one else;
 * otherwise the whole removal refuses or stops and nothing in that folder is touched. On Windows a
 * captured file is deleted through the one file handle its identity, links and hash were read
 * through (repair round 3); on POSIX its entry is checked and then unlinked by name relative to the
 * checked removal folder's own handle; where neither exists it is kept. Afterwards every canonical
 * place of the part is looked at again (repair round 4: also places that were empty when the
 * removal was prepared): a file that appeared there is kept and reported, and the removal is not
 * reported complete. Only "no such file" counts as absent (repair round 5): a place whose presence
 * cannot be determined (an inaccessible folder, say), when the files are listed, before the outcome
 * is reported and in recovery, is kept and reported "could not be checked", is never reported
 * deleted, and keeps the removal from being reported complete.
 *
 * <p>Threat model (decided by the user): programs running under the user's own account are
 * trusted; other accounts are kept out by the owner-only removal folder. Two steps still act on a
 * captured file by name: the POSIX check-then-unlink above, and dropping the captured name after a
 * put-back by hard link ({@link #restoreWithoutOverwrite}, also in recovery). Another program
 * running under the user's account could swap a file at the moment of deletion; such a program can
 * already change the user's files directly.
 * The removal record is replaced only atomically (a disk without atomic replacement refuses the
 * removal before anything is moved), and a removal holds an upgrade-admission lease, so an
 * application upgrade waits for it or it refuses while one is being prepared.
 */
final class ModelFileRemoval {
  private static final Logger log = LoggerFactory.getLogger(ModelFileRemoval.class);

  /** Parts this step can remove (fourth decision 1). */
  static final Set<String> REMOVABLE = Set.of("reranker", "chat");

  /** Shown, measured, but not removable in this step. */
  static final Set<String> NOT_YET = Set.of("splade", "ner", "citation-scorer");

  static final String JOURNAL_FILENAME = "model-file-removal.v1.json";
  static final int JOURNAL_VERSION = 1;
  /** The private removal folder inside the models folder (same filesystem as the files). */
  static final String REMOVAL_FOLDER = ".removing";
  private static final String STAGED_SUFFIX = ".removing";

  static final String KEPT_NOT_VERIFIED = "kept - not verified as JustSearch's download";
  static final String KEPT_LINKED = "kept - linked elsewhere";
  static final String KEPT_LINKED_FOLDER = "kept - in a linked folder or outside the models folder";
  static final String KEPT_NOT_FILE = "kept - not a regular file";
  static final String KEPT_IN_USE = "kept - in use or could not be moved aside";
  static final String KEPT_CHANGED = "kept - it changed while being removed";
  static final String KEPT_UNREADABLE = "kept - could not be read";
  static final String KEPT_NOT_DELETED = "kept - could not be deleted";
  static final String KEPT_IN_REMOVAL_FOLDER = "kept in the removal folder - its original place is taken";
  /** A file appeared at a canonical place after its own file was captured (repair round 3). */
  static final String KEPT_NEW_FILE = "kept - a new file appeared here";
  static final String KEPT_INTERRUPTED = "kept - the removal was interrupted";
  static final String KEPT_PUT_BACK = "kept - the removal was interrupted; the file was put back";
  static final String KEPT_UNCERTAIN = "kept - not touched: its place could not be checked";
  static final String KEPT_STOPPED = "kept - the removal stopped before this file";
  /**
   * Whether a file is at one of the part's places could not be determined (an inaccessible folder,
   * say): it is not known to be gone, so it is reported and keeps the removal from being complete.
   */
  static final String KEPT_UNCHECKED = "kept - could not be checked";

  /** The removal folder is not private to the Engine's user: the whole removal refuses or stops. */
  static final String FOLDER_NOT_PRIVATE =
      "The removal folder can be changed by another account, so nothing can be removed";
  /** The removal record cannot be replaced atomically on this disk: nothing is moved. */
  static final String RECORD_NOT_ATOMIC =
      "Nothing was removed: this disk can't save the removal record safely";
  /** Upgrade admission is closed: an application update is being prepared. */
  static final String UPDATE_PREPARING = "An update is being prepared - try again after it";
  /** The op-lease class a removal registers under (upgrade admission). */
  static final String LEASE_OP_CLASS = "ai.model-file-removal";

  /** The whole removal refuses when the models folder itself is a link or sits in a linked folder. */
  static final String ROOT_LINKED =
      "The models folder is a link or inside a linked folder, so nothing can be removed here";

  private static final Set<String> JOURNAL_STATES =
      Set.of("running", "removed", "partly-removed", "not-removed", "refused", "interrupted");
  private static final Set<String> FILE_STATES =
      Set.of("planned", "capturing", "captured", "deleted", "kept", "missing");
  private static final java.util.regex.Pattern STAGED_NAME = java.util.regex.Pattern.compile(
      java.util.regex.Pattern.quote(REMOVAL_FOLDER + "/") + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
          + "[0-9a-f]{4}-[0-9a-f]{12}" + java.util.regex.Pattern.quote(STAGED_SUFFIX));

  private static final JsonMapper JSON = JsonMapper.builder()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .enable(SerializationFeature.INDENT_OUTPUT)
      .build();

  /** What the runtime owners say about a part; a plain reason while something uses its files. */
  @FunctionalInterface
  interface UsageProbe {
    Optional<String> inUse(String packageId);

    /** Fail closed until composition binds the real owners. */
    UsageProbe UNBOUND = packageId ->
        Optional.of("JustSearch can't confirm nothing uses these files yet - try again in a moment");
  }

  /** The settings owner as removal needs it. */
  interface Settings {
    UiSettingsStoreView inspect();

    /** Records {@code packageId} as declined against the witness of {@code read}; throws if refused. */
    void decline(UiSettingsStoreView read, String packageId);
  }

  /** One settings read: the settings and the opaque witness the decline must be committed against. */
  record UiSettingsStoreView(UiSettings settings, Object witness) {}

  /** A refusal: nothing was deleted. */
  static final class Refusal extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Refusal(String message) {
      super(message);
    }
  }

  /** Test seam: runs at each named checkpoint (e.g. {@code captured:<path>}). */
  private volatile Consumer<String> checkpointHook = name -> {};

  private final Path homeDir;
  private final Path root;
  private final Supplier<ModelRegistry> registry;
  private final Supplier<InstallContract> contract;
  private final ModelFileRemovalFence fence;
  private final Supplier<OperationLeaseService> leases;
  private final Object journalLock = new Object();
  private volatile boolean recovered;

  ModelFileRemoval(Path homeDir, Path modelsRoot, Supplier<ModelRegistry> registry,
      Supplier<InstallContract> contract, ModelFileRemovalFence fence) {
    this(homeDir, modelsRoot, registry, contract, fence, OperationLeaseService::noOp);
  }

  ModelFileRemoval(Path homeDir, Path modelsRoot, Supplier<ModelRegistry> registry,
      Supplier<InstallContract> contract, ModelFileRemovalFence fence,
      Supplier<OperationLeaseService> leases) {
    this.homeDir = Objects.requireNonNull(homeDir, "homeDir");
    this.root = Objects.requireNonNull(modelsRoot, "modelsRoot").toAbsolutePath().normalize();
    this.registry = Objects.requireNonNull(registry, "registry");
    this.contract = Objects.requireNonNull(contract, "contract");
    this.fence = Objects.requireNonNull(fence, "fence");
    this.leases = Objects.requireNonNull(leases, "leases");
  }

  void setCheckpointHookForTest(Consumer<String> hook) {
    this.checkpointHook = hook == null ? name -> {} : hook;
  }

  // ---------------------------------------------------------------------------
  // Inventory and preview
  // ---------------------------------------------------------------------------

  /** What looking at a path found: a file, confirmed absence, or neither could be determined. */
  enum Seen { PRESENT, ABSENT, UNKNOWN }

  /**
   * Looks at {@code p} without following links. Only "no such file" is absence: an access or I/O
   * failure (an inaccessible folder, say) leaves it undetermined, never absent.
   */
  static Seen lookAt(Path p) {
    try {
      Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      return Seen.PRESENT;
    } catch (java.nio.file.NoSuchFileException absent) {
      return Seen.ABSENT;
    } catch (IOException | SecurityException undetermined) {
      return Seen.UNKNOWN;
    }
  }

  /** One file the registry places for a part. */
  record Expected(String rel, String sha256, long size, boolean partial) {}

  /** One present file with its classification; {@code reason == null} means "will be deleted". */
  record Found(Expected expected, Path path, Long size, String reason) {}

  /** The Downloaded files view: measured sizes, removability and the latest removal. */
  DownloadedFiles view(UiSettings settings, Eligibility refusal) {
    recoverIfNeeded();
    DownloadedFiles view = new DownloadedFiles();
    ModelRegistry reg = registry.get();
    InstallContract recorded = contractOrNull();
    Set<String> declined = settings == null ? Set.of() : Set.copyOf(settings.getDeclinedAiPackages());
    for (ModelPackage pkg : reg.packages()) {
      if (pkg.devOnly()) continue;
      DownloadedFiles.Part part = new DownloadedFiles.Part();
      part.id = pkg.id();
      part.label = pkg.label() == null ? pkg.id() : pkg.label();
      part.declined = declined.contains(pkg.id());
      part.unavailableAfter = unavailableAfter(pkg.id());
      if (installRooted(pkg)) {
        // Runtime libraries live outside the models folder; they are neither measured nor removed.
        part.reason = "Runtime libraries are not removed";
        part.state = "not-measured";
        part.sizeOnDiskBytes = null;
      } else if (!REMOVABLE.contains(pkg.id())) {
        part.reason = NOT_YET.contains(pkg.id()) ? "Removal not available yet"
            : "Needed for search by meaning";
        measureOnly(part, pkg, recorded);
      } else {
        List<Found> found = classify(reg, pkg, recorded, declined);
        fill(part, found, usable(pkg, recorded));
        String why = !found.isEmpty() && rootLinked() ? ROOT_LINKED
            : part.delete.isEmpty() ? "No downloaded files to remove"
            : refusal.reason(pkg.id(), settings).orElse(null);
        part.removable = why == null;
        part.reason = why;
        part.previewDigest = digest(pkg.id(), found);
      }
      view.parts.add(part);
    }
    view.removal = readReport();
    return view;
  }

  /** Part-level refusal supplied by the owner (switched on, busy, in use). */
  @FunctionalInterface
  interface Eligibility {
    Optional<String> reason(String packageId, UiSettings settings);
  }

  private void measureOnly(DownloadedFiles.Part part, ModelPackage pkg, InstallContract recorded) {
    long total = 0;
    boolean any = false;
    boolean unknown = false;
    for (Expected e : expected(pkg, recorded)) {
      Path p = root.resolve(e.rel()).normalize();
      Seen seen = lookAt(p);
      if (seen == Seen.ABSENT) continue;
      any = true;
      if (seen == Seen.UNKNOWN) {
        unknown = true;
        continue;
      }
      try {
        total = Math.addExact(total, Files.size(p));
      } catch (IOException | ArithmeticException unreadable) {
        unknown = true;
      }
    }
    part.state = presence(usable(pkg, recorded), any);
    part.sizeOnDiskBytes = measured(any, unknown, total);
  }

  /**
   * Usable presence comes from the part's required file set, not from any file being left: a part
   * missing a required file is {@code incomplete} (not usable) and can be downloaded again.
   */
  private static String presence(boolean usable, boolean anyFileLeft) {
    return usable ? "downloaded" : anyFileLeft ? "incomplete" : "not-downloaded";
  }

  /**
   * Whether a usable copy of {@code pkg} is on disk: one variant's model file and every required
   * supporting file beside it, in a candidate folder or the historical folder, as regular files.
   */
  boolean usable(ModelPackage pkg, InstallContract recorded) {
    List<String> dirs = new ArrayList<>();
    for (ModelVariant v : pkg.variants()) dirs.add(InstallPlanner.effectiveTargetDir(pkg, v));
    dirs.add(pkg.targetDir() == null ? "" : pkg.targetDir());
    for (String dir : dirs) {
      if (!requiredSupportingPresent(pkg, dir)) continue;
      if (pkg.variants().isEmpty()) return true;
      for (ModelVariant v : pkg.variants()) {
        if (Files.isRegularFile(root.resolve(join(dir, v.filename())).normalize(),
            LinkOption.NOFOLLOW_LINKS)) {
          return true;
        }
      }
    }
    return false;
  }

  /** {@link #usable(ModelPackage, InstallContract)} for a part id, with the current registry. */
  boolean usable(String packageId) {
    ModelPackage pkg = registry.get().findPackage(packageId);
    return pkg != null && usable(pkg, contractOrNull());
  }

  private boolean requiredSupportingPresent(ModelPackage pkg, String dir) {
    for (SupportingFile sf : pkg.supportingFiles()) {
      if (sf.filename() == null || sf.extract() || !sf.required()) continue;
      if (!Files.isRegularFile(root.resolve(join(dir, sf.filename())).normalize(),
          LinkOption.NOFOLLOW_LINKS)) {
        return false;
      }
    }
    return true;
  }

  /** Zero when nothing is on disk; unknown ({@code null}) when any present file was unreadable. */
  private static Long measured(boolean any, boolean unknown, long total) {
    if (!any) return 0L;
    if (unknown) return null;
    return total;
  }

  private static void fill(DownloadedFiles.Part part, List<Found> found, boolean usable) {
    long total = 0;
    boolean unknown = false;
    for (Found f : found) {
      if (f.size() == null) unknown = true;
      else total += f.size();
      var entry = new DownloadedFiles.FileEntry(f.expected().rel(), f.size(), f.reason());
      if (f.reason() == null) part.delete.add(entry);
      else part.kept.add(entry);
    }
    // Any file left, a partial download's staging file too, makes the part incomplete: a download
    // cancelled before its first file completed is listed and its staging files can be removed.
    part.state = presence(usable, !found.isEmpty());
    part.sizeOnDiskBytes = measured(!found.isEmpty(), unknown, total);
  }

  private static String unavailableAfter(String packageId) {
    return switch (packageId) {
      case "reranker" -> "Best match first";
      case "chat" -> "Answers and Text in scans and images";
      default -> "";
    };
  }

  private static boolean installRooted(ModelPackage pkg) {
    return (pkg.installRoot() != null && !pkg.installRoot().isBlank())
        || pkg.tier() == CapabilityTier.RUNTIME;
  }

  /** Every canonical file (and installer staging file) the registry places for {@code pkg}. */
  List<Expected> expected(ModelPackage pkg, InstallContract recorded) {
    Map<String, Expected> byRel = new LinkedHashMap<>();
    List<String> dirs = new ArrayList<>();
    for (ModelVariant v : pkg.variants()) {
      add(byRel, InstallPlanner.effectiveTargetDir(pkg, v), v, pkg);
      dirs.add(InstallPlanner.effectiveTargetDir(pkg, v));
    }
    InstallContract.InstalledModel installed = recorded == null ? null : recorded.getModel(pkg.id());
    String legacy = pkg.targetDir() == null ? "" : pkg.targetDir();
    if (installed != null && !installed.skipped() && legacy.equals(installed.targetDir())
        && !dirs.contains(legacy)) {
      for (ModelVariant v : pkg.variants()) add(byRel, legacy, v, pkg);
    }
    if (pkg.variants().isEmpty()) add(byRel, legacy, null, pkg);
    List<Expected> all = new ArrayList<>(byRel.values());
    for (Expected e : List.copyOf(all)) {
      all.add(new Expected(e.rel() + InstallPlanner.PARTIAL_SUFFIX, null, -1, true));
      String partial = e.rel() + InstallPlanner.PARTIAL_SUFFIX;
      String sidecar = DownloadResume.sidecarFor(Path.of(partial)).getFileName().toString();
      int slash = partial.lastIndexOf('/');
      all.add(new Expected(slash < 0 ? sidecar : partial.substring(0, slash + 1) + sidecar,
          null, -1, true));
    }
    return all;
  }

  private static void add(Map<String, Expected> byRel, String dir, ModelVariant v, ModelPackage pkg) {
    if (v != null) {
      String rel = join(dir, v.filename());
      byRel.putIfAbsent(rel, new Expected(rel, v.sha256(), v.sizeBytes(), false));
    }
    for (SupportingFile sf : pkg.supportingFiles()) {
      if (sf.filename() == null || sf.extract()) continue;
      String rel = join(dir, sf.filename());
      byRel.putIfAbsent(rel, new Expected(rel, sf.sha256(), sf.sizeBytes(), false));
    }
  }

  private static String join(String dir, String name) {
    return dir == null || dir.isEmpty() ? name : dir + "/" + name;
  }

  /**
   * Present files for {@code pkg}, each classified by rules 1-3 and 5 (4 is checked at deletion). A
   * place whose presence could not be determined is listed, kept, as {@link #KEPT_UNCHECKED}.
   */
  List<Found> classify(ModelRegistry reg, ModelPackage pkg, InstallContract recorded,
      Set<String> declined) {
    Map<Path, String> others = new LinkedHashMap<>();
    for (ModelPackage other : reg.packages()) {
      if (other.id().equals(pkg.id()) || other.devOnly() || installRooted(other)
          || declined.contains(other.id())) {
        continue;
      }
      for (Expected e : expected(other, recorded)) {
        others.putIfAbsent(root.resolve(e.rel()).normalize(),
            other.label() == null ? other.id() : other.label());
      }
    }
    List<Found> found = new ArrayList<>();
    for (Expected e : expected(pkg, recorded)) {
      Path p = root.resolve(e.rel()).normalize();
      Seen seen = lookAt(p);
      if (seen == Seen.ABSENT) continue;
      found.add(seen == Seen.UNKNOWN ? new Found(e, p, null, KEPT_UNCHECKED)
          : classifyOne(e, p, others.get(p)));
    }
    return found;
  }

  private Found classifyOne(Expected e, Path p, String usedBy) {
    Long size = null;
    try {
      size = Files.size(p);
    } catch (IOException unreadable) {
      // size stays unknown
    }
    if (!p.startsWith(root) || linkedOnTheWay(p)) return new Found(e, p, size, KEPT_LINKED_FOLDER);
    BasicFileAttributes attrs;
    try {
      attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (IOException unreadable) {
      return new Found(e, p, size, KEPT_UNREADABLE);
    }
    if (!attrs.isRegularFile()) return new Found(e, p, size, KEPT_NOT_FILE);
    if (!singleLink(p)) return new Found(e, p, size, KEPT_LINKED);
    if (usedBy != null) return new Found(e, p, size, "kept - used by " + usedBy);
    if (!e.partial() && e.size() > 0 && attrs.size() != e.size()) {
      return new Found(e, p, size, KEPT_NOT_VERIFIED);
    }
    return new Found(e, p, size, null);
  }

  /**
   * Rule 2 for the models folder itself: it and every folder above it must be plain folders, read
   * without following links. A link or junction at the models folder or any ancestor refuses the
   * whole removal; otherwise registry-matching files behind it could be deleted.
   */
  boolean rootLinked() {
    Path current = root.getRoot();
    if (current == null) return true;
    for (Path name : root) {
      current = current.resolve(name);
      if (!plainFolder(current)) return true;
    }
    return false;
  }

  private static boolean plainFolder(Path dir) {
    try {
      BasicFileAttributes attrs =
          Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()) return false;
      if (isWindows()) WindowsOwnerOnlyDirectory.requireNonReparsePoint(dir);
      return true;
    } catch (IOException | RuntimeException unreadable) {
      return false;
    }
  }

  /**
   * Rule 2 for the folders holding {@code p}: the models folder and its ancestors, and every folder
   * between it and {@code p}, are plain folders; {@code p} itself is not checked (it may be absent).
   */
  private boolean linkedParents(Path p) {
    if (!p.startsWith(root) || p.equals(root) || rootLinked()) return true;
    Path current = root;
    Path rel = root.relativize(p);
    for (int i = 0; i < rel.getNameCount() - 1; i++) {
      current = current.resolve(rel.getName(i));
      if (!plainFolder(current)) return true;
    }
    return false;
  }

  /** Rule 2: a link or junction at {@code p}, on the way to it, at the models folder or above it. */
  private boolean linkedOnTheWay(Path p) {
    if (linkedParents(p)) return true;
    try {
      BasicFileAttributes attrs =
          Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attrs.isSymbolicLink() || attrs.isOther()) return true;
      if (isWindows()) WindowsOwnerOnlyDirectory.requireNonReparsePoint(p);
      return !p.toRealPath().startsWith(root.toRealPath());
    } catch (IOException | RuntimeException unreadable) {
      return true;
    }
  }

  /** Rule 3; on Windows through the platform's own link count. */
  private static boolean singleLink(Path p) {
    try {
      if (p.getFileSystem().supportedFileAttributeViews().contains("unix")) {
        return ((Number) Files.getAttribute(p, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() == 1;
      }
      if (isWindows()) {
        WindowsOwnerOnlyDirectory.requireSingleLink(p);
        return true;
      }
      return false;
    } catch (IOException | RuntimeException unknown) {
      return false;
    }
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  static String digest(String packageId, List<Found> found) {
    StringBuilder identity = new StringBuilder(packageId).append('\n');
    found.stream().filter(f -> f.reason() == null)
        .map(f -> f.expected().rel() + "\u0000" + f.size())
        .sorted().forEach(line -> identity.append(line).append('\n'));
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(identity.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private InstallContract contractOrNull() {
    try {
      return contract.get();
    } catch (RuntimeException unreadable) {
      return null;
    }
  }

  // ---------------------------------------------------------------------------
  // Execute
  // ---------------------------------------------------------------------------

  /**
   * A prepared removal: the fence and the upgrade-admission lease are held, the removal folder is
   * checked, the decline committed, the journal written.
   */
  final class Run {
    private final ModelFileRemovalFence.Removal held;
    private final OperationLeaseHandle lease;
    private final PrivateFolder folder;
    private final Journal journal;
    private final List<Target> targets;

    private Run(ModelFileRemovalFence.Removal held, OperationLeaseHandle lease, PrivateFolder folder,
        Journal journal, List<Target> targets) {
      this.held = held;
      this.lease = lease;
      this.folder = folder;
      this.journal = journal;
      this.targets = targets;
    }

    DownloadedFiles.Removal started() {
      return journal.report();
    }

    /**
     * Capture, verify, delete each file; the report says what is actually left. A failure (a
     * checkpoint that could not be saved, say) puts captured files back or keeps them in the
     * removal folder before the lock is released; a record that could not be finished is
     * reconciled by the next read rather than left "running" without an owner.
     */
    DownloadedFiles.Removal finish() {
      boolean settled = false;
      boolean finished = false;
      try {
        for (Target t : targets) removeOne(journal, t, folder);
        recheckCanonical(journal);
        journal.state = outcome(journal, targets);
        journal.finishedAtEpochMs = System.currentTimeMillis();
        journal.message = summary(journal);
        write(journal);
        settled = true;
        finished = true;
        return journal.report();
      } catch (RuntimeException failure) {
        // Settle every unfinished file in memory first: no checkpoint write may stop this. Nothing
        // in a removal folder that is no longer the checked private folder is touched.
        boolean folderPrivate = !(failure instanceof FolderNotPrivate) && stillPrivate(folder);
        for (JournalFile f : journal.files) {
          if (!"deleted".equals(f.state) && !"kept".equals(f.state) && !"missing".equals(f.state)) {
            settleOne(f, folderPrivate);
            if ("kept".equals(f.state) && KEPT_INTERRUPTED.equals(f.reason)) f.reason = KEPT_STOPPED;
          }
        }
        recheckCanonical(journal);
        journal.state = outcome(journal, targets);
        journal.finishedAtEpochMs = System.currentTimeMillis();
        journal.message = "Removal stopped: " + failure.getMessage() + ". " + summary(journal);
        settled = saveOrHold(journal, failure);
        log.warn("Model file removal stopped", failure);
        return journal.report();
      } finally {
        if (!settled) recovered = false; // the saved record may still say running
        try {
          lease.release(finished ? OpLeaseOutcome.SUCCESS : OpLeaseOutcome.FAILURE);
        } catch (RuntimeException notReleased) {
          log.warn("Removal lease could not be released", notReleased);
        } finally {
          held.close();
        }
      }
    }
  }

  /**
   * Repair rounds 3 and 4: once every file was handled (also when a removal stops, and in
   * recovery), every canonical place of the part is looked at again: each one the journal names and
   * the complete set recorded when the removal was prepared, so a place that was empty then is not
   * skipped. Anything there that is not the part's own kept file (one never captured, or put back)
   * arrived during the removal, possibly while a captured file was being hashed: it was not checked
   * and is not JustSearch's verified download, so it is kept, never deleted, and reported as a new
   * file, and the removal is not reported as complete while it is there.
   */
  private void recheckCanonical(Journal journal) {
    Set<String> places = new LinkedHashSet<>();
    for (JournalFile f : journal.files) places.add(f.path);
    if (journal.canonical != null) places.addAll(journal.canonical);
    List<JournalFile> appeared = new ArrayList<>();
    for (String rel : places) {
      if (accountedAtPlace(journal, rel)) continue;
      Path canonical = canonicalOf(rel);
      Seen seen = canonical == null ? Seen.ABSENT : lookAt(canonical);
      if (seen == Seen.ABSENT) continue;
      JournalFile arrived = new JournalFile();
      arrived.path = rel;
      arrived.state = "kept";
      if (seen == Seen.UNKNOWN) {
        // Not known to be gone: reported, and the removal is not complete.
        arrived.reason = KEPT_UNCHECKED;
        appeared.add(arrived);
        continue;
      }
      arrived.reason = KEPT_NEW_FILE;
      try {
        arrived.sizeBytes = Files.readAttributes(canonical, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS).size();
      } catch (IOException unreadable) {
        arrived.sizeBytes = null; // unknown stays unknown
      }
      appeared.add(arrived);
    }
    journal.files.addAll(appeared);
  }

  /**
   * Whether what sits at {@code rel} is already reported: a new file found earlier, the part's own
   * file kept at its place (never captured, or put back), or a captured file put back by a link
   * whose captured name could not be dropped (both names are the same file, which is not new).
   */
  private boolean accountedAtPlace(Journal journal, String rel) {
    for (JournalFile f : journal.files) {
      if (!f.path.equals(rel)) continue;
      if (KEPT_NEW_FILE.equals(f.reason) || KEPT_UNCHECKED.equals(f.reason)) return true;
      if ("kept".equals(f.state) && f.staged == null) return true;
      Path canonical = canonicalOf(rel);
      if (f.staged != null && canonical != null
          && putBackByLink(root.resolve(f.staged).normalize(), canonical)) {
        return true;
      }
    }
    return false;
  }

  /**
   * A put-back that linked the captured file to its place but could not drop its captured name:
   * both names are the same file, which is not a new one.
   */
  private static boolean putBackByLink(Path staged, Path canonical) {
    Object captured = identity(staged);
    return captured != null && captured.equals(identity(canonical));
  }

  /**
   * {@code removed} when every target was deleted or failed an ownership check at deletion (it
   * did not pass, so it is not JustSearch's to delete); {@code not-removed} when nothing was
   * deleted; otherwise {@code partly-removed}. Never "removed" while a file that passed remains,
   * nor while a new file sits at a canonical place ({@link #KEPT_NEW_FILE}) or a place could not be
   * checked ({@link #KEPT_UNCHECKED}): both are unresolved.
   */
  private static String outcome(Journal journal, List<Target> targets) {
    Set<String> rels = new HashSet<>();
    for (Target t : targets) rels.add(t.expected().rel());
    long deleted = 0;
    boolean passingLeft = false;
    for (JournalFile f : journal.files) {
      // A new file, or a place that could not be checked, at any of the part's places is
      // unresolved, also where nothing was a target.
      if (KEPT_NEW_FILE.equals(f.reason) || KEPT_UNCHECKED.equals(f.reason)) {
        passingLeft = true;
        continue;
      }
      if (!rels.contains(f.path) || "missing".equals(f.state)) continue;
      if ("deleted".equals(f.state)) deleted++;
      else if (f.reason == null || !OWNERSHIP_FAILURES.contains(f.reason)) passingLeft = true;
    }
    return deleted == 0 ? "not-removed" : passingLeft ? "partly-removed" : "removed";
  }

  private static final Set<String> OWNERSHIP_FAILURES =
      Set.of(KEPT_CHANGED, KEPT_NOT_VERIFIED, KEPT_LINKED, KEPT_LINKED_FOLDER, KEPT_NOT_FILE);

  /** One file to remove, with the identity resolved before the fence. */
  private record Target(Expected expected, Path path, Object identity) {}

  /**
   * Prepares a removal of {@code packageId}. The order is the decided one: resolve file identities,
   * take the lock and the upgrade-admission lease, re-check eligibility under them, check the
   * private removal folder, write the journal (only atomically), record the decline through the
   * settings owner (so Repair does not restore the part).
   *
   * @throws Refusal with a plain reason; nothing is deleted
   */
  Run prepare(String packageId, String previewDigest, Settings settings,
      Eligibility eligibility, UsageProbe probe) {
    recoverIfNeeded();
    if (!REMOVABLE.contains(packageId)) {
      throw new Refusal(NOT_YET.contains(packageId) ? "Removal not available yet"
          : "These files can't be removed here");
    }
    ModelRegistry reg = registry.get();
    ModelPackage pkg = reg.findPackage(packageId);
    if (pkg == null) throw new Refusal("No such part");
    if (rootLinked()) throw new Refusal(ROOT_LINKED);
    InstallContract recorded = contractOrNull();
    UiSettingsStoreView before = settings.inspect();
    Set<String> declined = Set.copyOf(before.settings().getDeclinedAiPackages());
    List<Found> found = classify(reg, pkg, recorded, declined);
    if (previewDigest == null || !previewDigest.equals(digest(packageId, found))) {
      throw new Refusal("The files changed since this list was shown - review it again");
    }
    List<Target> targets = new ArrayList<>();
    for (Found f : found) {
      if (f.reason() != null) continue;
      Object identity = identity(f.path());
      if (identity == null) throw new Refusal("A file could not be read - nothing was removed");
      targets.add(new Target(f.expected(), f.path(), identity));
    }
    if (targets.isEmpty()) throw new Refusal("No downloaded files to remove");

    ModelFileRemovalFence.Removal held = fence.begin(packageId);
    OperationLeaseHandle lease = null;
    try {
      // Upgrade admission: an update being prepared refuses the removal; a running removal is a
      // must-complete operation an update waits for (it is never interrupted mid-capture).
      try {
        lease = leases.get().register(LEASE_OP_CLASS, OpCriticality.MUST_COMPLETE, 1800,
            Map.of("source", LEASE_OP_CLASS, "packageId", packageId));
      } catch (OperationAdmissionClosedException closed) {
        throw new Refusal(UPDATE_PREPARING);
      }
      checkpointHook.accept("fenced");
      UiSettingsStoreView read = settings.inspect();
      Optional<String> refused = eligibility.reason(packageId, read.settings());
      if (refused.isEmpty()) refused = probe.inUse(packageId);
      if (refused.isPresent()) throw new Refusal(refused.get());
      PrivateFolder folder = privateRemovalFolder();
      Journal journal = new Journal();
      journal.version = JOURNAL_VERSION;
      journal.packageId = packageId;
      journal.label = pkg.label() == null ? packageId : pkg.label();
      journal.state = "running";
      journal.startedAtEpochMs = System.currentTimeMillis();
      for (Expected e : expected(pkg, recorded)) {
        if (!journal.canonical.contains(e.rel())) journal.canonical.add(e.rel());
      }
      for (Found f : found) {
        JournalFile file = new JournalFile();
        file.path = f.expected().rel();
        file.sizeBytes = f.size();
        file.state = f.reason() == null ? "planned" : "kept";
        file.reason = f.reason();
        journal.files.add(file);
      }
      journal.message = "Removing files";
      try {
        write(journal);
      } catch (java.io.UncheckedIOException notAtomic) {
        // Nothing was saved, captured or declined: a record that could be torn is never written.
        log.warn("Removal record could not be saved", notAtomic);
        throw new Refusal(notAtomic.getCause() instanceof java.nio.file.AtomicMoveNotSupportedException
            ? RECORD_NOT_ATOMIC : "Nothing was removed: the removal record could not be saved");
      }
      try {
        settings.decline(read, packageId);
      } catch (RuntimeException notSaved) {
        journal.state = "refused";
        journal.finishedAtEpochMs = System.currentTimeMillis();
        journal.message = "Nothing was removed: the choice could not be saved";
        for (JournalFile file : journal.files) {
          if ("planned".equals(file.state)) {
            file.state = "kept";
            file.reason = "kept - nothing was removed";
          }
        }
        write(journal);
        throw new Refusal("Nothing was removed: the choice could not be saved ("
            + notSaved.getMessage() + ")");
      }
      checkpointHook.accept("declined");
      return new Run(held, lease, folder, journal, targets);
    } catch (RuntimeException | Error failure) {
      // A record may have been saved as running: the next read reconciles it, never this lock.
      recovered = false;
      try {
        if (lease != null) lease.release(OpLeaseOutcome.CANCELLED);
      } catch (RuntimeException notReleased) {
        failure.addSuppressed(notReleased);
      } finally {
        held.close();
      }
      throw failure;
    }
  }

  private void removeOne(Journal journal, Target t, PrivateFolder folder) {
    JournalFile entry = journal.files.stream().filter(f -> f.path.equals(t.expected().rel()))
        .findFirst().orElseThrow();
    String stagedName = UUID.randomUUID() + STAGED_SUFFIX;
    Path staged = folder.path().resolve(stagedName);
    // A target that can no longer be looked at is not known to be gone or plain: kept, unresolved.
    if (lookAt(t.path()) == Seen.UNKNOWN) {
      keep(journal, entry, KEPT_UNCHECKED);
      return;
    }
    // Rule 2 is applied to the file before capture.
    if (linkedOnTheWay(t.path())) {
      keep(journal, entry, KEPT_LINKED_FOLDER);
      return;
    }
    requireStillPrivate(folder);
    entry.state = "capturing";
    entry.staged = REMOVAL_FOLDER + "/" + stagedName;
    write(journal);
    checkpointHook.accept("capturing:" + entry.path);
    try {
      Files.move(t.path(), staged, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException | UnsupportedOperationException notMoved) {
      entry.staged = null;
      keep(journal, entry, KEPT_IN_USE);
      return;
    }
    entry.state = "captured";
    write(journal);
    checkpointHook.accept("captured:" + entry.path);
    String failed = verify(t, staged);
    if (failed != null) {
      putBack(journal, entry, staged, t.path(), failed, folder);
      return;
    }
    checkpointHook.accept("verified:" + entry.path);
    final String kept;
    try {
      kept = deleteCaptured(folder, staged, t);
    } catch (IOException notDeleted) {
      putBack(journal, entry, staged, t.path(), KEPT_NOT_DELETED, folder);
      return;
    }
    if (KEPT_CHANGED.equals(kept)) {
      // What the captured name holds now is not the verified file: it is neither deleted nor put
      // back, and stays reported in the removal folder.
      keep(journal, entry, kept);
      return;
    }
    if (kept != null) {
      // Still the captured file, not deleted: it goes back to its place if that is free.
      putBack(journal, entry, staged, t.path(), kept, folder);
      return;
    }
    entry.state = "deleted";
    entry.staged = null;
    entry.reason = null;
    write(journal);
  }

  /** The captured file: same identity, regular, one link, registry hash (partials: no hash). */
  private String verify(Target t, Path staged) {
    try {
      BasicFileAttributes attrs =
          Files.readAttributes(staged, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attrs.isRegularFile()) return KEPT_CHANGED;
      if (!t.identity().equals(identity(staged))) return KEPT_CHANGED;
      if (!singleLink(staged)) return KEPT_LINKED;
      if (t.expected().partial()) return null;
      if (t.expected().size() > 0 && attrs.size() != t.expected().size()) return KEPT_NOT_VERIFIED;
      // On Windows the hash is computed through the one handle that also deletes the file.
      if (isWindows()) return t.expected().sha256() == null ? KEPT_NOT_VERIFIED : null;
      String sha = sha256(staged);
      return t.expected().sha256() != null && t.expected().sha256().equalsIgnoreCase(sha)
          ? null : KEPT_NOT_VERIFIED;
    } catch (IOException unreadable) {
      return KEPT_UNREADABLE;
    }
  }

  /**
   * Deletes the verified captured file, immediately after checking again that the removal folder is
   * the private folder checked for this run (repair round 3). POSIX: the models folder is opened
   * without following links, its removal folder's handle is checked to be that folder, and the
   * entry is checked to be the verified file and then unlinked by name relative to that handle: a
   * program running under the user's own account could swap the entry between the check and the
   * unlink (the decided threat model trusts such programs; they can change the user's files
   * directly). Windows: the captured file is opened once for reading and deletion with no write,
   * rename or delete sharing, its identity, link count, size and registry hash are read through
   * that handle, and that handle deletes it. Where neither is available the file is kept.
   *
   * @return {@code null} when deleted; {@link #KEPT_CHANGED} when the captured name no longer holds
   *     the verified file (it stays in the removal folder); otherwise why the still-captured file was
   *     not deleted (it is put back)
   * @throws IOException when it could not be deleted (it is put back)
   * @throws FolderNotPrivate when the removal folder is no longer the checked private folder
   */
  private String deleteCaptured(PrivateFolder folder, Path staged, Target t) throws IOException {
    requireStillPrivate(folder);
    if (isWindows()) return deleteThroughHandle(staged, t);
    if (!t.identity().equals(identity(staged))) return KEPT_CHANGED;
    try (DirectoryStream<Path> models = Files.newDirectoryStream(root)) {
      if (!(models instanceof SecureDirectoryStream<Path> modelsHandle)) {
        // No directory handle: a delete by name could unlink a file swapped in meanwhile.
        return t.identity().equals(identity(staged)) ? KEPT_NOT_DELETED : KEPT_CHANGED;
      }
      try (SecureDirectoryStream<Path> handle = modelsHandle.newDirectoryStream(
          Path.of(REMOVAL_FOLDER), LinkOption.NOFOLLOW_LINKS)) {
        Object opened = handle.getFileAttributeView(BasicFileAttributeView.class)
            .readAttributes().fileKey();
        if (opened == null || !opened.equals(folder.identity())) {
          throw new FolderNotPrivate();
        }
        Path name = staged.getFileName();
        BasicFileAttributes entry = handle.getFileAttributeView(name,
            BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
        if (!entry.isRegularFile() || !t.identity().equals(entry.fileKey())) return KEPT_CHANGED;
        handle.deleteFile(name);
        return null;
      }
    }
  }

  /** Windows: identity, links, size and hash read through the one handle that deletes the file. */
  private String deleteThroughHandle(Path staged, Target t) throws IOException {
    if (!(t.identity() instanceof String identity)) return KEPT_NOT_DELETED;
    WindowsOwnerOnlyDirectory.HandleCheck check = WindowsOwnerOnlyDirectory.deleteVerified(staged,
        identity, t.expected().size() > 0 ? t.expected().size() : -1,
        t.expected().partial() ? null : t.expected().sha256(),
        () -> checkpointHook.accept("deleting:" + t.expected().rel()));
    return switch (check) {
      case DELETED -> null;
      case CHANGED -> KEPT_CHANGED;
      case LINKED -> KEPT_LINKED;
      case NOT_VERIFIED -> KEPT_NOT_VERIFIED;
    };
  }

  /**
   * Puts a captured file back only if its place is free; never overwrites. A removal folder that is
   * no longer the checked private folder stops the whole removal instead: what its captured name
   * holds may not be the file that was captured.
   */
  private void putBack(Journal journal, JournalFile entry, Path staged, Path canonical, String why,
      PrivateFolder folder) {
    requireStillPrivate(folder);
    if (linkedParents(staged) || linkedParents(canonical)) {
      keep(journal, entry, KEPT_UNCERTAIN);
    } else if (restoreWithoutOverwrite(staged, canonical)) {
      entry.staged = null;
      keep(journal, entry, why);
    } else {
      keep(journal, entry, KEPT_IN_REMOVAL_FOLDER);
    }
  }

  /**
   * Link-then-unlink is an atomic no-replace publication. When it is unavailable (no hard links on
   * this file system, say) nothing else is tried: an existence check followed by a move could
   * overwrite a file that took the place meanwhile, so the captured file stays in the removal
   * folder and is reported. If the captured name cannot be dropped after linking, the file is at
   * its place and its captured name is still reported in the removal folder (nothing at the
   * canonical place is ever deleted to undo it). The captured name is dropped by name: under the
   * decided threat model a program running under the user's own account is trusted not to swap it
   * in that moment.
   */
  static boolean restoreWithoutOverwrite(Path staged, Path canonical) {
    try {
      Files.createLink(canonical, staged);
    } catch (IOException | UnsupportedOperationException | SecurityException notLinked) {
      return false;
    }
    try {
      Files.delete(staged);
      return true;
    } catch (IOException | SecurityException notDropped) {
      log.warn("A captured file was put back at {} but its captured name {} remains", canonical, staged);
      return false;
    }
  }

  private void keep(Journal journal, JournalFile entry, String reason) {
    entry.state = "kept";
    entry.reason = reason;
    write(journal);
  }

  // ---------------------------------------------------------------------------
  // The private removal folder (repair round 2)
  // ---------------------------------------------------------------------------

  /** The removal folder as checked for one run; its identity notices a replacement. */
  record PrivateFolder(Path path, Object identity) {}

  /** The removal folder is not, or no longer, the checked private folder: the removal stops. */
  static final class FolderNotPrivate extends RuntimeException {
    private static final long serialVersionUID = 1L;

    FolderNotPrivate() {
      super(FOLDER_NOT_PRIVATE);
    }
  }

  /** Rights that let an account add, rename, replace or delete entries, or take the folder over. */
  private static final Set<AclEntryPermission> WRITE_RIGHTS = Collections.unmodifiableSet(EnumSet.of(
      AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA,
      AclEntryPermission.DELETE_CHILD, AclEntryPermission.DELETE, AclEntryPermission.WRITE_ACL,
      AclEntryPermission.WRITE_OWNER, AclEntryPermission.WRITE_NAMED_ATTRS));

  /** The Engine's own account, and (Windows) the principals an owner-only folder grants. */
  private record EngineAccount(UserPrincipal owner, Set<UserPrincipal> ownerOnly) {}

  private volatile EngineAccount engineAccount;

  /**
   * Creates the removal folder owner-only when it is absent (an existing folder is never
   * loosened or repaired), then checks it.
   *
   * @throws Refusal unless it is a private folder of the Engine's user
   */
  PrivateFolder privateRemovalFolder() {
    Path folder = root.resolve(REMOVAL_FOLDER);
    try {
      if (lookAt(folder) == Seen.ABSENT) {
        OwnerOnlyDirectory.ensureOwnerOnlyDirectory(folder);
      }
      return new PrivateFolder(folder, checkPrivate(folder, null));
    } catch (IOException | RuntimeException notPrivate) {
      log.warn("Removal folder {} is not private: {}", folder, notPrivate.toString());
      throw new Refusal(FOLDER_NOT_PRIVATE);
    }
  }

  private void requireStillPrivate(PrivateFolder folder) {
    if (!stillPrivate(folder)) throw new FolderNotPrivate();
  }

  private boolean stillPrivate(PrivateFolder folder) {
    try {
      checkPrivate(folder.path(), folder.identity());
      return true;
    } catch (IOException | RuntimeException notPrivate) {
      return false;
    }
  }

  /** Recovery's check (no identity is known after a restart). */
  private boolean removalFolderPrivate() {
    Path folder = root.resolve(REMOVAL_FOLDER);
    try {
      checkPrivate(folder, null);
      return true;
    } catch (IOException | RuntimeException notPrivate) {
      return false;
    }
  }

  /**
   * A plain folder (no link, junction or reparse point) inside a plain models folder, owned by the
   * Engine's user and writable by no other account (POSIX: no group or other write; Windows: no
   * allow entry granting write rights to anyone an owner-only folder does not grant), and, when
   * {@code expected} is given, the same folder. Returns its identity.
   */
  private Object checkPrivate(Path folder, Object expected) throws IOException {
    if (rootLinked()) throw new IOException("models folder is linked");
    BasicFileAttributes attrs =
        Files.readAttributes(folder, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()) {
      throw new IOException("not a plain folder");
    }
    if (isWindows()) WindowsOwnerOnlyDirectory.requireNonReparsePoint(folder);
    // A folder's change times move with every entry: its identity is its file key, on Windows its
    // NTFS file index, elsewhere its creation time.
    Object identity = attrs.fileKey() != null ? attrs.fileKey()
        : isWindows() ? WindowsOwnerOnlyDirectory.fileIdentity(folder)
        : attrs.creationTime().toMillis();
    if (expected != null && !expected.equals(identity)) throw new IOException("replaced");
    EngineAccount engine = engineAccount();
    PosixFileAttributeView posix =
        Files.getFileAttributeView(folder, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (posix != null) {
      PosixFileAttributes perms = posix.readAttributes();
      if (!perms.owner().equals(engine.owner())) throw new IOException("owned by another account");
      if (perms.permissions().contains(PosixFilePermission.GROUP_WRITE)
          || perms.permissions().contains(PosixFilePermission.OTHERS_WRITE)) {
        throw new IOException("writable by other accounts");
      }
      return identity;
    }
    AclFileAttributeView acl =
        Files.getFileAttributeView(folder, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (acl == null) throw new IOException("its permissions cannot be read");
    if (!acl.getOwner().equals(engine.owner())) throw new IOException("owned by another account");
    for (AclEntry entry : acl.getAcl()) {
      if (entry.type() == AclEntryType.ALLOW && !Collections.disjoint(entry.permissions(), WRITE_RIGHTS)
          && !engine.ownerOnly().contains(entry.principal())) {
        throw new IOException("writable by " + entry.principal().getName());
      }
    }
    return identity;
  }

  /**
   * The account files created by this Engine belong to, read from a fresh owner-only probe folder
   * (on Windows also the principals that folder's protected DACL names), so the check needs no
   * platform account lookups.
   */
  private EngineAccount engineAccount() throws IOException {
    EngineAccount known = engineAccount;
    if (known != null) return known;
    Files.createDirectories(homeDir);
    Path probe = homeDir.resolve(".removal-owner-" + UUID.randomUUID());
    OwnerOnlyDirectory.ensureOwnerOnlyDirectory(probe);
    try {
      UserPrincipal owner = Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS);
      Set<UserPrincipal> ownerOnly = new HashSet<>();
      ownerOnly.add(owner);
      AclFileAttributeView acl =
          Files.getFileAttributeView(probe, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
      if (acl != null) {
        for (AclEntry entry : acl.getAcl()) {
          if (entry.type() == AclEntryType.ALLOW) ownerOnly.add(entry.principal());
        }
      }
      known = new EngineAccount(owner, Set.copyOf(ownerOnly));
    } finally {
      Files.deleteIfExists(probe);
    }
    engineAccount = known;
    return known;
  }

  /**
   * The file's identity: its file key where Java has one (device and inode on POSIX); on Windows the
   * NTFS volume serial and file index, which survive a rename on one volume and differ for any
   * other file. Elsewhere size plus creation and change times. {@code null} when unreadable.
   */
  static Object identity(Path p) {
    try {
      BasicFileAttributes attrs =
          Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      Object key = attrs.fileKey();
      if (key != null) return key;
      if (isWindows()) return WindowsOwnerOnlyDirectory.fileIdentity(p);
      return List.of(attrs.size(), attrs.creationTime().toMillis(), attrs.lastModifiedTime().toMillis());
    } catch (IOException | RuntimeException unreadable) {
      return null;
    }
  }

  static String sha256(Path file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        byte[] buffer = new byte[1 << 20];
        int read;
        while ((read = in.read(buffer)) >= 0) {
          if (read > 0) digest.update(buffer, 0, read);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static String summary(Journal journal) {
    long deleted = journal.files.stream().filter(f -> "deleted".equals(f.state)).count();
    long kept = journal.files.stream().filter(f -> "kept".equals(f.state)).count();
    return deleted + (deleted == 1 ? " file removed" : " files removed")
        + (kept == 0 ? "" : ", " + kept + (kept == 1 ? " file kept" : " files kept"));
  }

  // ---------------------------------------------------------------------------
  // Journal and recovery
  // ---------------------------------------------------------------------------

  /** The durable record of one removal, rewritten atomically at each checkpoint. */
  static final class Journal {
    public int version;
    public String packageId = "";
    public String label = "";
    /** running | removed | partly-removed | not-removed | refused | interrupted. */
    public String state = "";
    public String message = "";
    public long startedAtEpochMs;
    public long finishedAtEpochMs;
    public List<JournalFile> files = new ArrayList<>();
    /**
     * Every canonical place the registry gives the part when the removal was prepared (each file of
     * every variant considered and its staging files), present or not (repair round 4): before the
     * outcome is reported, and by recovery, each is looked at again.
     */
    public List<String> canonical = new ArrayList<>();

    DownloadedFiles.Removal report() {
      DownloadedFiles.Removal r = new DownloadedFiles.Removal();
      r.packageId = packageId;
      r.label = label;
      r.state = state;
      r.message = message;
      r.startedAtEpochMs = startedAtEpochMs;
      r.finishedAtEpochMs = finishedAtEpochMs;
      for (JournalFile f : files) {
        if ("deleted".equals(f.state)) {
          r.deleted.add(new DownloadedFiles.FileEntry(f.path, f.sizeBytes, null));
        } else if (!"missing".equals(f.state)
            && !("planned".equals(f.state) && "running".equals(state))) {
          // Every other file is still on disk: at its place, or captured in the removal folder.
          String reason = f.reason != null ? f.reason
              : f.staged != null ? KEPT_IN_REMOVAL_FOLDER : KEPT_INTERRUPTED;
          r.kept.add(new DownloadedFiles.FileEntry(f.staged != null ? f.staged : f.path, f.sizeBytes,
              "running".equals(state) && f.reason == null ? null : reason));
        }
      }
      return r;
    }
  }

  /** One file's checkpoint: planned, capturing, captured, deleted, kept or missing. */
  static final class JournalFile {
    public String path = "";
    /** The private name in the removal folder while captured; {@code null} otherwise. */
    public String staged;
    public Long sizeBytes;
    public String state = "";
    public String reason;
  }

  private Path journalPath() {
    return homeDir.resolve(JOURNAL_FILENAME);
  }

  /**
   * A finished or reconciled record whose save failed. It is what the files actually look like, so
   * reports show it, and the next recovery saves it instead of re-reading the stale record.
   */
  private volatile Journal unsaved;

  private void write(Journal journal) {
    synchronized (journalLock) {
      try {
        Files.createDirectories(homeDir);
        // Only an atomic replacement: a torn record could misreport what is left (repair round 2).
        AtomicFileWrites.replaceStrict(journalPath(), JSON.writeValueAsBytes(journal));
        unsaved = null;
      } catch (IOException failure) {
        throw new java.io.UncheckedIOException("Removal record could not be saved", failure);
      }
    }
  }

  /** Saves a settled record; when that fails it is held for reports and the next recovery. */
  private boolean saveOrHold(Journal journal, RuntimeException failure) {
    try {
      write(journal);
      return true;
    } catch (RuntimeException unwritten) {
      if (failure != null) failure.addSuppressed(unwritten);
      else log.warn("Settled removal record could not be saved", unwritten);
      unsaved = journal;
      return false;
    }
  }

  private Journal readJournal() {
    Path path = journalPath();
    Seen seen = lookAt(path);
    if (seen == Seen.UNKNOWN) return unreadable();
    if (seen == Seen.ABSENT || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null;
    try {
      Journal journal = JSON.readValue(path.toFile(), Journal.class);
      return wellFormed(journal) ? journal : unreadable();
    } catch (RuntimeException corrupt) {
      return unreadable();
    }
  }

  /**
   * The complete record is checked before anything acts on it: its version, part, states and every
   * file entry (present, a canonical path relative to and inside the models folder, a known state,
   * a captured name of this removal folder's own form). Anything else is an unreadable record:
   * reported, and its files in the removal folder kept untouched.
   */
  private boolean wellFormed(Journal journal) {
    if (journal == null || journal.version != JOURNAL_VERSION || journal.packageId == null
        || !REMOVABLE.contains(journal.packageId) || journal.state == null
        || !JOURNAL_STATES.contains(journal.state) || journal.files == null) {
      return false;
    }
    if (journal.canonical == null) journal.canonical = new ArrayList<>();
    for (String rel : journal.canonical) {
      if (canonicalOf(rel) == null) return false;
    }
    for (JournalFile f : journal.files) {
      if (f == null || f.state == null || !FILE_STATES.contains(f.state)
          || canonicalOf(f.path) == null
          || (f.staged != null && !STAGED_NAME.matcher(f.staged).matches())) {
        return false;
      }
    }
    if (journal.label == null) journal.label = journal.packageId;
    if (journal.message == null) journal.message = "";
    return true;
  }

  /** The canonical place a recorded relative path names, or {@code null} unless it is one. */
  private Path canonicalOf(String rel) {
    if (rel == null || rel.isBlank() || rel.indexOf('\\') >= 0) return null;
    final Path relative;
    try {
      relative = Path.of(rel);
    } catch (InvalidPathException invalid) {
      return null;
    }
    if (relative.isAbsolute() || relative.getRoot() != null
        || !relative.normalize().equals(relative)) {
      return null;
    }
    for (Path name : relative) {
      if (name.toString().equals("..") || name.toString().equals(".")) return null;
    }
    if (relative.getName(0).toString().equals(REMOVAL_FOLDER)) return null;
    Path canonical = root.resolve(relative).normalize();
    return canonical.startsWith(root) && !canonical.equals(root) ? canonical : null;
  }

  private static Journal unreadable() {
    Journal journal = new Journal();
    journal.version = JOURNAL_VERSION;
    journal.state = "interrupted";
    journal.message = "The removal record could not be read; files in the removal folder were kept";
    return journal;
  }

  private DownloadedFiles.Removal readReport() {
    synchronized (journalLock) {
      Journal journal = unsaved != null ? unsaved : readJournal();
      if (journal == null) {
        // No record, yet files sit in the removal folder: say so rather than hide them.
        Journal none = unreadable();
        none.message = "Files were found in the removal folder; they were kept";
        DownloadedFiles.Removal report = none.report();
        addStrays(report, none);
        return report.kept.isEmpty() ? null : report;
      }
      DownloadedFiles.Removal report = journal.report();
      addStrays(report, journal);
      return report;
    }
  }

  /** Files in the removal folder that no journal entry names are reported, never deleted. */
  private void addStrays(DownloadedFiles.Removal report, Journal journal) {
    Path folder = root.resolve(REMOVAL_FOLDER);
    if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return;
    Set<String> named = new HashSet<>();
    for (JournalFile f : journal.files) if (f.staged != null) named.add(f.staged);
    try (var listing = Files.list(folder)) {
      listing.map(p -> REMOVAL_FOLDER + "/" + p.getFileName()).filter(rel -> !named.contains(rel))
          .sorted().forEach(rel -> report.kept.add(
              new DownloadedFiles.FileEntry(rel, null, "kept in the removal folder")));
    } catch (IOException unreadable) {
      // nothing more can be said
    }
  }

  /**
   * After a crash or restart: a removal still marked running is reported as interrupted with the
   * files actually left; captured files are put back if their place is free, otherwise kept in the
   * removal folder. Nothing is deleted here.
   */
  void recoverIfNeeded() {
    if (recovered) return;
    synchronized (journalLock) {
      if (recovered) return;
      if (fence.removingPart().isPresent()) return; // a live removal in this process owns the journal
      Journal held = unsaved;
      if (held != null) {
        // Already settled in memory: only its save is outstanding.
        if (saveOrHold(held, null)) recovered = true;
        return;
      }
      Journal journal = readJournal();
      if (journal != null && "running".equals(journal.state)) {
        boolean folderPrivate = removalFolderPrivate();
        for (JournalFile f : journal.files) settleOne(f, folderPrivate);
        recheckCanonical(journal);
        journal.state = "interrupted";
        journal.finishedAtEpochMs = System.currentTimeMillis();
        journal.message = "The removal was interrupted: " + summary(journal)
            + ". Nothing more will be deleted.";
        if (!saveOrHold(journal, null)) return;
        log.warn("Reported an interrupted model file removal of '{}'", journal.packageId);
      }
      recovered = true;
    }
  }

  /**
   * Settles one unfinished entry without deleting anything: a captured file goes back to its
   * canonical place when that place is free, every folder on both paths is a plain folder and the
   * removal folder is still private ({@code folderPrivate}); otherwise it stays where it is and is
   * reported. An entry whose paths cannot be checked is reported as kept and not touched.
   */
  private void settleOne(JournalFile f, boolean folderPrivate) {
    if ("deleted".equals(f.state) || "kept".equals(f.state) || "missing".equals(f.state)) return;
    Path canonical = canonicalOf(f.path);
    if (canonical == null || linkedParents(canonical)) {
      uncertain(f);
      return;
    }
    if ("planned".equals(f.state)) {
      // Never captured: the file is left where it was (or something else moved it).
      Seen seen = lookAt(canonical);
      f.state = seen == Seen.ABSENT ? "missing" : "kept";
      f.reason = seen == Seen.ABSENT ? null
          : seen == Seen.UNKNOWN ? KEPT_UNCHECKED : KEPT_INTERRUPTED;
      return;
    }
    Path staged = f.staged == null ? null : root.resolve(f.staged).normalize();
    if (staged != null && (!STAGED_NAME.matcher(f.staged).matches() || linkedParents(staged))) {
      uncertain(f);
      return;
    }
    Seen stagedSeen = staged == null ? Seen.ABSENT : lookAt(staged);
    if (stagedSeen == Seen.UNKNOWN) {
      uncertain(f);
      return;
    }
    Seen canonicalSeen = stagedSeen == Seen.PRESENT ? Seen.PRESENT : lookAt(canonical);
    if (stagedSeen == Seen.PRESENT) {
      if (!folderPrivate || !plainSingleFile(staged)) {
        uncertain(f);
      } else if (restoreWithoutOverwrite(staged, canonical)) {
        f.staged = null;
        f.state = "kept";
        f.reason = KEPT_PUT_BACK;
      } else {
        f.state = "kept";
        f.reason = KEPT_IN_REMOVAL_FOLDER;
      }
    } else if (canonicalSeen == Seen.PRESENT) {
      f.staged = null;
      f.state = "kept";
      f.reason = KEPT_INTERRUPTED;
    } else if (canonicalSeen == Seen.UNKNOWN) {
      // Neither captured nor known to be gone: never reported as deleted.
      f.staged = null;
      f.state = "kept";
      f.reason = KEPT_UNCHECKED;
    } else {
      f.staged = null;
      f.state = "deleted";
      f.reason = null;
    }
  }

  private static void uncertain(JournalFile f) {
    f.state = "kept";
    f.reason = KEPT_UNCERTAIN;
  }

  /** A regular file (not a link) with one link: the only thing recovery puts back. */
  private static boolean plainSingleFile(Path p) {
    try {
      BasicFileAttributes attrs =
          Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      return attrs.isRegularFile() && !attrs.isSymbolicLink() && singleLink(p);
    } catch (IOException unreadable) {
      return false;
    }
  }

}
