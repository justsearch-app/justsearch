/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import io.justsearch.app.api.DownloadedFiles;
import io.justsearch.app.api.UiSettings;
import io.justsearch.configuration.model.CapabilityTier;
import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.model.InstallPlanner;
import io.justsearch.configuration.model.ModelPackage;
import io.justsearch.configuration.model.ModelRegistry;
import io.justsearch.configuration.model.ModelVariant;
import io.justsearch.configuration.model.SupportingFile;
import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.configuration.persistence.WindowsOwnerOnlyDirectory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
 * path or any parent folder, (3) it is a regular file with one link, (4) its content hash equals the
 * registry's (checked in full immediately before deletion), and (5) no other wanted part lists the
 * same file. A file failing any rule is kept and listed with its reason. Partial downloads are the
 * installer's own {@code .partial} staging files beside those canonical paths.
 *
 * <p>Capture before verify (decided): each file is first recorded in the journal, then atomically
 * renamed to a private name in a removal folder inside the models folder, then verified (same file
 * identity as resolved before the fence, regular, one link, registry hash) and only then deleted. A
 * failed rename keeps the file (a Windows sharing violation refuses that file); a failed check puts
 * the file back only if its place is free. Recovery after a crash reports the interrupted removal
 * and puts captured files back; it never deletes.
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
  static final String KEPT_INTERRUPTED = "kept - the removal was interrupted";
  static final String KEPT_PUT_BACK = "kept - the removal was interrupted; the file was put back";

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
  private final Object journalLock = new Object();
  private volatile boolean recovered;

  ModelFileRemoval(Path homeDir, Path modelsRoot, Supplier<ModelRegistry> registry,
      Supplier<InstallContract> contract, ModelFileRemovalFence fence) {
    this.homeDir = Objects.requireNonNull(homeDir, "homeDir");
    this.root = Objects.requireNonNull(modelsRoot, "modelsRoot").toAbsolutePath().normalize();
    this.registry = Objects.requireNonNull(registry, "registry");
    this.contract = Objects.requireNonNull(contract, "contract");
    this.fence = Objects.requireNonNull(fence, "fence");
  }

  void setCheckpointHookForTest(Consumer<String> hook) {
    this.checkpointHook = hook == null ? name -> {} : hook;
  }

  // ---------------------------------------------------------------------------
  // Inventory and preview
  // ---------------------------------------------------------------------------

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
        fill(part, found);
        String why = part.delete.isEmpty() ? "No downloaded files to remove"
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
      if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) continue;
      any = true;
      try {
        total = Math.addExact(total, Files.size(p));
      } catch (IOException | ArithmeticException unreadable) {
        unknown = true;
      }
    }
    part.state = any ? "downloaded" : "not-downloaded";
    part.sizeOnDiskBytes = measured(any, unknown, total);
  }

  /** Zero when nothing is on disk; unknown ({@code null}) when any present file was unreadable. */
  private static Long measured(boolean any, boolean unknown, long total) {
    if (!any) return 0L;
    if (unknown) return null;
    return total;
  }

  private static void fill(DownloadedFiles.Part part, List<Found> found) {
    long total = 0;
    boolean unknown = false;
    for (Found f : found) {
      if (f.size() == null) unknown = true;
      else total += f.size();
      var entry = new DownloadedFiles.FileEntry(f.expected().rel(), f.size(), f.reason());
      if (f.reason() == null) part.delete.add(entry);
      else part.kept.add(entry);
    }
    part.state = found.stream().anyMatch(f -> !f.expected().partial()) ? "downloaded" : "not-downloaded";
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

  /** Present files for {@code pkg}, each classified by rules 1-3 and 5 (4 is checked at deletion). */
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
      if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) continue;
      found.add(classifyOne(e, p, others.get(p)));
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

  /** Rule 2: a link or junction anywhere below the models folder down to {@code p}. */
  private boolean linkedOnTheWay(Path p) {
    Path real;
    try {
      real = root.toRealPath();
    } catch (IOException missing) {
      return true;
    }
    Path current = root;
    for (Path part : root.relativize(p)) {
      current = current.resolve(part);
      try {
        BasicFileAttributes attrs =
            Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isSymbolicLink() || attrs.isOther()) return true;
        if (isWindows()) WindowsOwnerOnlyDirectory.requireNonReparsePoint(current);
      } catch (IOException unreadable) {
        return true;
      }
    }
    try {
      return !p.toRealPath().startsWith(real);
    } catch (IOException unreadable) {
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

  /** A prepared removal: the fence is held, the decline committed, the journal written. */
  final class Run {
    private final ModelFileRemovalFence.Removal held;
    private final Journal journal;
    private final List<Target> targets;

    private Run(ModelFileRemovalFence.Removal held, Journal journal, List<Target> targets) {
      this.held = held;
      this.journal = journal;
      this.targets = targets;
    }

    DownloadedFiles.Removal started() {
      return journal.report();
    }

    /** Capture, verify, delete each file; the report says what is actually left. */
    DownloadedFiles.Removal finish() {
      try {
        for (Target t : targets) removeOne(journal, t);
        journal.state = outcome(journal, targets);
        journal.finishedAtEpochMs = System.currentTimeMillis();
        journal.message = summary(journal);
        write(journal);
        return journal.report();
      } catch (RuntimeException failure) {
        journal.state = outcome(journal, targets);
        journal.finishedAtEpochMs = System.currentTimeMillis();
        journal.message = "Removal stopped: " + failure.getMessage() + ". " + summary(journal);
        try {
          write(journal);
        } catch (RuntimeException unwritten) {
          failure.addSuppressed(unwritten);
        }
        log.warn("Model file removal stopped", failure);
        return journal.report();
      } finally {
        held.close();
      }
    }
  }

  /**
   * {@code removed} when every target was deleted or failed an ownership check at deletion (it
   * did not pass, so it is not JustSearch's to delete); {@code not-removed} when nothing was
   * deleted; otherwise {@code partly-removed}. Never "removed" while a file that passed remains.
   */
  private static String outcome(Journal journal, List<Target> targets) {
    Set<String> rels = new java.util.HashSet<>();
    for (Target t : targets) rels.add(t.expected().rel());
    long deleted = 0;
    boolean passingLeft = false;
    for (JournalFile f : journal.files) {
      if (!rels.contains(f.path)) continue;
      if ("deleted".equals(f.state)) deleted++;
      else if (!OWNERSHIP_FAILURES.contains(f.reason)) passingLeft = true;
    }
    return deleted == 0 ? "not-removed" : passingLeft ? "partly-removed" : "removed";
  }

  private static final Set<String> OWNERSHIP_FAILURES =
      Set.of(KEPT_CHANGED, KEPT_NOT_VERIFIED, KEPT_LINKED, KEPT_LINKED_FOLDER, KEPT_NOT_FILE);

  /** One file to remove, with the identity resolved before the fence. */
  private record Target(Expected expected, Path path, Object identity) {}

  /**
   * Prepares a removal of {@code packageId}. The order is the decided one: resolve file identities,
   * take the lock, re-check eligibility under it, record the decline through the settings owner
   * (so Repair does not restore the part), write the journal.
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
    try {
      checkpointHook.accept("fenced");
      UiSettingsStoreView read = settings.inspect();
      Optional<String> refused = eligibility.reason(packageId, read.settings());
      if (refused.isEmpty()) refused = probe.inUse(packageId);
      if (refused.isPresent()) throw new Refusal(refused.get());
      Journal journal = new Journal();
      journal.version = JOURNAL_VERSION;
      journal.packageId = packageId;
      journal.label = pkg.label() == null ? packageId : pkg.label();
      journal.state = "running";
      journal.startedAtEpochMs = System.currentTimeMillis();
      for (Found f : found) {
        JournalFile file = new JournalFile();
        file.path = f.expected().rel();
        file.sizeBytes = f.size();
        file.state = f.reason() == null ? "planned" : "kept";
        file.reason = f.reason();
        journal.files.add(file);
      }
      journal.message = "Removing files";
      write(journal);
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
      return new Run(held, journal, targets);
    } catch (RuntimeException | Error failure) {
      held.close();
      throw failure;
    }
  }

  private void removeOne(Journal journal, Target t) {
    JournalFile entry = journal.files.stream().filter(f -> f.path.equals(t.expected().rel()))
        .findFirst().orElseThrow();
    Path folder = root.resolve(REMOVAL_FOLDER);
    String stagedName = UUID.randomUUID() + STAGED_SUFFIX;
    Path staged = folder.resolve(stagedName);
    // Rule 2 is applied to the file before capture.
    if (linkedOnTheWay(t.path())) {
      keep(journal, entry, KEPT_LINKED_FOLDER);
      return;
    }
    try {
      ensureRemovalFolder(folder);
    } catch (IOException unavailable) {
      keep(journal, entry, KEPT_IN_USE);
      return;
    }
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
      putBack(journal, entry, staged, t.path(), failed);
      return;
    }
    checkpointHook.accept("verified:" + entry.path);
    try {
      Files.delete(staged);
    } catch (IOException notDeleted) {
      putBack(journal, entry, staged, t.path(), KEPT_NOT_DELETED);
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
      String sha = sha256(staged);
      return t.expected().sha256() != null && t.expected().sha256().equalsIgnoreCase(sha)
          ? null : KEPT_NOT_VERIFIED;
    } catch (IOException unreadable) {
      return KEPT_UNREADABLE;
    }
  }

  /** Puts a captured file back only if its place is free; never overwrites. */
  private void putBack(Journal journal, JournalFile entry, Path staged, Path canonical, String why) {
    if (restoreWithoutOverwrite(staged, canonical)) {
      entry.staged = null;
      keep(journal, entry, why);
    } else {
      keep(journal, entry, KEPT_IN_REMOVAL_FOLDER);
    }
  }

  /** Link-then-unlink is an atomic no-replace; a plain move without REPLACE_EXISTING is the fallback. */
  static boolean restoreWithoutOverwrite(Path staged, Path canonical) {
    try {
      Files.createLink(canonical, staged);
      Files.delete(staged);
      return true;
    } catch (FileAlreadyExistsException taken) {
      return false;
    } catch (IOException | UnsupportedOperationException noLinks) {
      try {
        if (Files.exists(canonical, LinkOption.NOFOLLOW_LINKS)) return false;
        Files.move(staged, canonical);
        return true;
      } catch (IOException notMoved) {
        return false;
      }
    }
  }

  private void keep(Journal journal, JournalFile entry, String reason) {
    entry.state = "kept";
    entry.reason = reason;
    write(journal);
  }

  private void ensureRemovalFolder(Path folder) throws IOException {
    try {
      Files.createDirectory(folder);
    } catch (FileAlreadyExistsException exists) {
      // verified below
    }
    BasicFileAttributes attrs =
        Files.readAttributes(folder, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()) {
      throw new IOException("Removal folder is not a plain folder");
    }
    if (isWindows()) WindowsOwnerOnlyDirectory.requireNonReparsePoint(folder);
  }

  /** File key where the platform has one; otherwise size plus creation and change times. */
  static Object identity(Path p) {
    try {
      BasicFileAttributes attrs =
          Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      Object key = attrs.fileKey();
      return key != null ? key
          : List.of(attrs.size(), attrs.creationTime().toMillis(), attrs.lastModifiedTime().toMillis());
    } catch (IOException unreadable) {
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

  private void write(Journal journal) {
    synchronized (journalLock) {
      try {
        Files.createDirectories(homeDir);
        AtomicFileWrites.replace(journalPath(), JSON.writeValueAsBytes(journal));
      } catch (IOException failure) {
        throw new java.io.UncheckedIOException("Removal record could not be saved", failure);
      }
    }
  }

  private Journal readJournal() {
    Path path = journalPath();
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null;
    try {
      Journal journal = JSON.readValue(path.toFile(), Journal.class);
      return journal != null && journal.version == JOURNAL_VERSION ? journal : unreadable();
    } catch (RuntimeException corrupt) {
      return unreadable();
    }
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
      Journal journal = readJournal();
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
    Set<String> named = new java.util.HashSet<>();
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
      Journal journal = readJournal();
      if (journal != null && "running".equals(journal.state)) {
        for (JournalFile f : journal.files) recoverOne(f);
        journal.state = "interrupted";
        journal.finishedAtEpochMs = System.currentTimeMillis();
        journal.message = "The removal was interrupted: " + summary(journal)
            + ". Nothing more will be deleted.";
        try {
          write(journal);
        } catch (RuntimeException unwritten) {
          log.warn("Interrupted removal could not be recorded", unwritten);
          return;
        }
        log.warn("Reported an interrupted model file removal of '{}'", journal.packageId);
      }
      recovered = true;
    }
  }

  private void recoverOne(JournalFile f) {
    Path canonical = root.resolve(f.path).normalize();
    switch (f.state) {
      case "deleted", "kept" -> { /* final */ }
      case "planned" -> {
        // Never captured: the file is left where it was (or something else moved it).
        f.state = Files.exists(canonical, LinkOption.NOFOLLOW_LINKS) ? "kept" : "missing";
        f.reason = "kept".equals(f.state) ? KEPT_INTERRUPTED : null;
      }
      default -> {
        Path staged = f.staged == null ? null : root.resolve(f.staged).normalize();
        if (staged != null && staged.startsWith(root.resolve(REMOVAL_FOLDER))
            && Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
          if (restoreWithoutOverwrite(staged, canonical)) {
            f.staged = null;
            f.state = "kept";
            f.reason = KEPT_PUT_BACK;
          } else {
            f.state = "kept";
            f.reason = KEPT_IN_REMOVAL_FOLDER;
          }
        } else if (Files.exists(canonical, LinkOption.NOFOLLOW_LINKS)) {
          f.staged = null;
          f.state = "kept";
          f.reason = KEPT_INTERRUPTED;
        } else {
          f.staged = null;
          f.state = "deleted";
          f.reason = null;
        }
      }
    }
  }

}
