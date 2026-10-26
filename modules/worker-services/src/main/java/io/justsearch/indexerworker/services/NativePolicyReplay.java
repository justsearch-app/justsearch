/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.adapters.lucene.runtime.PathPrefixScope;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy;
import io.justsearch.configuration.excludes.ExclusionPatterns;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.ingest.IngestionSkipPolicy;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.NativePolicyReadmission;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue.SwitchBufferOp;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.queue.SwitchBufferUpsert;
import io.justsearch.indexerworker.queue.WatcherReconciliationFile;
import io.justsearch.indexerworker.queue.WatcherRootReconciliation;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermRangeQuery;

/** One native replay attempt's published policy and exact absence proofs; never durable state. */
public final class NativePolicyReplay {
  private final ConfigStore owner;
  private final ReentrantReadWriteLock publication;
  private final ResolvedConfig snapshot;
  private final ExclusionPatterns exclusions;
  private final SwitchBufferCapableQueue queue;
  private final RunningRuntime target;
  private final WorkerIngestService publisher;
  private final String candidate;
  private final List<ResolvedConfig.FileSource> roots;
  private final List<Path> protectedPaths;
  private final boolean uiOmission;
  private final boolean committedBoot;
  private java.util.function.Predicate<List<SwitchBufferOp>> committedAcknowledgement;
  private final Map<SwitchBufferOp, Omission> omissions = new LinkedHashMap<>();
  private List<SwitchBufferOp> frozen;
  private final Map<String, RootOmission> rootOmissions = new LinkedHashMap<>();
  private record RootOmission(WatcherReconciliationFile file,
      RootIdentity identity) {}

  private record Omission(List<SwitchBufferOp> journal, SwitchBufferOp op, Map<String, String> row) {
    Omission {
      journal = List.copyOf(journal);
      row = Collections.unmodifiableMap(new LinkedHashMap<>(row));
    }
  }

  public NativePolicyReplay(ConfigStore owner, ReentrantReadWriteLock publication,
      JobQueue queue, RunningRuntime target, WorkerIngestService publisher,
      String candidate, List<ResolvedConfig.FileSource> roots) {
    this(owner, publication, queue, target, publisher, candidate, roots,
        owner == null ? null : owner.currentSnapshot(), false);
  }

  public NativePolicyReplay(ConfigStore owner, ReentrantReadWriteLock publication,
      JobQueue queue, RunningRuntime target, WorkerIngestService publisher,
      String candidate, List<ResolvedConfig.FileSource> roots, ResolvedConfig snapshot) {
    this(owner, publication, queue, target, publisher, candidate, roots, snapshot, false);
  }

  /** Only the committed writable boot caller supplies its exact already-bound declarations. */
  public static NativePolicyReplay forCommittedBoot(ConfigStore owner,
      ReentrantReadWriteLock publication, JobQueue queue, RunningRuntime target,
      WorkerIngestService publisher, String candidate, List<ResolvedConfig.FileSource> roots,
      ResolvedConfig snapshot) {
    return new NativePolicyReplay(owner, publication, queue, target, publisher,
        candidate, roots, snapshot, true);
  }

  private NativePolicyReplay(ConfigStore owner, ReentrantReadWriteLock publication,
      JobQueue queue, RunningRuntime target, WorkerIngestService publisher,
      String candidate, List<ResolvedConfig.FileSource> roots, ResolvedConfig snapshot,
      boolean committedBoot) {
    if (owner == null || owner.publicationLock() != publication
        || !(queue instanceof SwitchBufferCapableQueue scoped) || target == null || publisher == null
        || !publisher.ownsForgetQueue(queue) || target.openedIndexPath() == null
        || candidate == null || candidate.isBlank()
        || IndexGenerationManager.isRecordedGenerationIdentity(candidate)
        || !candidate.equals(target.openedIndexPath().getFileName().toString()) || roots == null) {
      throw new IllegalArgumentException("Native policy replay lacks its actual bound owner");
    }
    this.owner = owner; this.publication = publication; this.queue = scoped;
    this.target = target; this.publisher = publisher; this.candidate = candidate;
    this.roots = List.copyOf(roots);
    this.snapshot = Objects.requireNonNull(snapshot);
    if (owner.currentSnapshot() != snapshot) throw new IllegalArgumentException("Native policy publication changed");
    this.committedBoot = committedBoot;
    var resolution = snapshot.resolution("justsearch.ui.exclude_patterns");
    uiOmission = resolution == null || resolution.sourceOrdinal() <= 300;
    var internal = new ArrayList<Path>();
    internal.add(snapshot.paths().dataDir().resolve("agent-history").toAbsolutePath().normalize());
    var help = snapshot.collections().bundledHelp();
    if (help != null) internal.add(help.path().toAbsolutePath().normalize());
    for (var collection : snapshot.collections().items()) {
      if (io.justsearch.configuration.InternalCollections.isReserved(collection.name())) {
        collection.roots().forEach(path -> internal.add(path.toAbsolutePath().normalize()));
      }
    }
    protectedPaths = List.copyOf(internal);
    exclusions = ExclusionPatterns.fromRawJsonStrict(snapshot.ui().excludePatterns(),
        io.justsearch.configuration.PlatformPaths.isWindows());
    if (!exclusions.invalidPatterns().isEmpty()) {
      throw new IllegalArgumentException("Incomplete exclusion policy cannot certify native omission");
    }
  }

  /** Only KnowledgeServer's synchronous committed-boot owner binds the final decision. */
  public void bindCommittedAcknowledgement(java.util.function.Predicate<List<SwitchBufferOp>> decision) {
    if (!committedBoot || committedAcknowledgement != null) {
      throw new IllegalStateException("Committed native finalization is already bound or unavailable");
    }
    committedAcknowledgement = Objects.requireNonNull(decision);
  }

  public boolean hasOmissions() { return !omissions.isEmpty() || !rootOmissions.isEmpty(); }

  public boolean matchesAuthority(ConfigStore expected, ReentrantReadWriteLock publication,
      List<ResolvedConfig.FileSource> declarations) {
    return owner == expected && this.publication == publication && current()
        && new java.util.HashSet<>(roots).equals(new java.util.HashSet<>(declarations));
  }

  /** Never turn a missing final owner into permission to acknowledge a new omission. */
  public boolean confirmCommittedAcknowledgement(List<SwitchBufferOp> selected) {
    if (!hasOmissions()) return true;
    return committedAcknowledgement != null && committedAcknowledgement.test(List.copyOf(selected));
  }

  /** Called only inside the owning generation/publication scope, before exact notifying ACK. */
  public boolean reproveCommittedAcknowledgement(List<SwitchBufferOp> selected) {
    if (!committedBoot || !publication.isWriteLockedByCurrentThread()
        || !current() || frozen == null || !frozen.containsAll(selected)) return false;
    for (var proof : omissions.values()) {
      if (!selected.contains(proof.op()) || !prove(proof)) return false;
    }
    for (var proof : rootOmissions.values()) {
      if (!selected.contains(proof.file().rootReceipt()) || !proveRoot(proof)) return false;
    }
    return hasOmissions();
  }

  public boolean freeze(List<SwitchBufferOp> journal) {
    if (frozen == null) frozen = List.copyOf(journal);
    return frozen.equals(journal);
  }

  public boolean current() {
    return owner.publicationLock() == publication && owner.currentSnapshot() == snapshot;
  }

  public boolean owns(Path path, Path root, String collection) {
    if (root == null || path == null || IngestCollectionPolicy.isReserved(collection)
        || path.toAbsolutePath().normalize().equals(root.toAbsolutePath().normalize())
        || protectedPaths.stream().anyMatch(protectedPath -> path.toAbsolutePath().normalize().startsWith(protectedPath))) return false;
    String boundary = PathNormalizer.normalizeKey(root);
    var binding = roots.stream().filter(value -> boundary.equals(PathNormalizer.normalizeKey(value.path()))
        && Objects.equals(WatcherReplay.rootOwnerCollection(collection),
            WatcherReplay.rootOwnerCollection(value.collection()))).findFirst();
    if (binding.isEmpty()) return false;
    var scope = new PathPrefixScope(boundary, roots.stream().map(value -> PathNormalizer.normalizeKey(value.path()))
        .filter(value -> !value.equals(boundary) && Path.of(value).startsWith(Path.of(boundary))).toList());
    return scope.contains(PathNormalizer.normalizeKey(path));
  }

  public boolean excludes(Path path, Path root, String collection) {
    return uiOmission && current() && owns(path, root, collection) && exclusions.isExcludedPath(root, path);
  }

  boolean excludesDirectory(Path directory, Path root, String collection) {
    return uiOmission && current() && owns(directory, root, collection)
        && protectedPaths.stream().noneMatch(protectedPath -> protectedPath.startsWith(directory.toAbsolutePath().normalize()))
        && exclusions.isExcludedDirectory(root, directory);
  }

  /** Ordinary current eligibility; no fail-open global exclusion cache supplies this proof. */
  public boolean allows(Path path, Path root, String collection) {
    if (!current() || !owns(path, root, collection) || exclusions.isExcludedPath(root, path)
        || IngestionSkipPolicy.active().shouldSkipName(path)) return false;
    for (Path parent = path.toAbsolutePath().normalize().getParent();
        parent != null && !parent.equals(root.toAbsolutePath().normalize()); parent = parent.getParent()) {
      if (!parent.startsWith(root.toAbsolutePath().normalize())) return false;
      if (IngestionSkipPolicy.isSkippedDirectoryName(parent.getFileName().toString())) return false;
    }
    return true;
  }

  /** Stored physical/path membership is independent of the queue/result collection label. */
  private boolean ordinaryIndexOwner(Path path) throws IOException {
    if (!current() || protectedPaths.stream().anyMatch(path.toAbsolutePath().normalize()::startsWith)) return false;
    String key = PathNormalizer.normalizeKey(path);
    if (frozen == null) return false;
    // A buffered projection is an owner even before its rows have reached B. It cannot
    // acquire a file admission merely because the declared parent has a user label.
    for (var op : frozen) {
      if (!"PROJECTION".equals(op.op())) continue;
      var projection = AcceptedProjection.decode(op.payload());
      if (projection.kind() != AcceptedProjection.Kind.UPSERT) continue;
      var fields = ProjectionDocumentMapper.toIndexDocument(projection).fields();
      Object projected = fields.get(SchemaFields.PATH);
      if (key.equals(projection.indexId()) || projected != null && key.equals(projected.toString())) return false;
      if (projected != null) {
        try {
          Path projectedPath = Path.of(projected.toString());
          if (projectedPath.isAbsolute() && key.equals(PathNormalizer.normalizeKey(projectedPath))) return false;
        } catch (java.nio.file.InvalidPathException nonFileMetadata) {
          // A non-filesystem metadata value cannot equal this canonical absolute file key.
        }
      }
    }
    var membership = new BooleanQuery.Builder();
    for (String field : List.of(SchemaFields.DOC_ID,
        SchemaFields.PARENT_DOC_ID, SchemaFields.PATH)) {
      membership.add(new TermQuery(new Term(field, key)),
          Occur.SHOULD);
    }
    var internal = new BooleanQuery.Builder();
    for (String collection : io.justsearch.configuration.InternalCollections.RESERVED) {
      internal.add(new TermQuery(new Term(
          SchemaFields.COLLECTION, collection)), Occur.SHOULD);
    }
    internal.add(TermRangeQuery.newStringRange(
        SchemaFields.PROJECTION_SOURCE_ID, null, null, true, true),
        Occur.SHOULD);
    return publisher.nativePolicyProtectedMembershipAbsent(new BooleanQuery.Builder()
        .add(membership.build(), Occur.FILTER)
        .add(internal.build(), Occur.FILTER).build());
  }

  private record Ancestor(Path path, Object fileKey) {}

  /** Candidate-only ancestry guard; this is not an atomic external-filesystem transaction. */
  private static List<Ancestor> currentAncestors(Path root, Path file) throws IOException {
    Path boundary = root.toAbsolutePath().normalize();
    Path source = file.toAbsolutePath().normalize();
    if (source.equals(boundary) || !source.startsWith(boundary)) throw new IOException("Native source escaped its proper root");
    var result = new ArrayList<Ancestor>();
    for (Path parent = source.getParent(); parent != null && parent.startsWith(boundary); parent = parent.getParent()) {
      var attributes = Files.readAttributes(parent,
          BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
          || attributes.fileKey() == null) throw new IOException("Native source ancestry is linked or unknown");
      result.add(new Ancestor(parent, attributes.fileKey()));
      if (parent.equals(boundary)) return List.copyOf(result);
    }
    throw new IOException("Native source ancestry lacks its held root");
  }

  /** One actual-root batch; replacement invalidates the attempt, and boot resumes before waiting. */
  public boolean readmit(List<SwitchBufferOp> journal, List<SwitchBufferOp> survivingFiles)
      throws IOException {
    if (publication.isWriteLockedByCurrentThread()) {
      throw new IllegalStateException("Native readmission cannot notify under publication ownership");
    }
    if (!freeze(journal)) throw new IOException("Native policy journal changed during the attempt");
    record Group(Path root, String collection) {}
    record Pending(SwitchBufferOp op, Map<String, String> row) {}
    var groups = new LinkedHashMap<Group, List<Pending>>();
    for (var op : survivingFiles) {
      var file = SwitchBufferUpsert.decode(op.payload());
      Path path = Path.of(file.path());
      if (!allows(path, file.ingestionRoot(), file.collection())) continue;
      var row = queue.watcherReconciliationRow(file.path());
      if (row == null || !queue.tryMatchesNativePolicySkippedAdmission(candidate, journal, op, row)) continue;
      var group = new Group(file.ingestionRoot(), WatcherReplay.rootOwnerCollection(file.collection()));
      var values = groups.computeIfAbsent(group, ignored -> new ArrayList<>());
      if (values.size() < 2_000) values.add(new Pending(op, row));
    }
    for (var group : groups.entrySet()) {
      java.util.function.Supplier<Boolean> admission = () -> {
        try {
          var entries = new ArrayList<NativePolicyReadmission>();
          var ancestry = new LinkedHashMap<Path, List<Ancestor>>();
          for (var old : group.getValue()) {
            var file = SwitchBufferUpsert.decode(old.op().payload());
            Path path = Path.of(file.path());
            if (!allows(path, file.ingestionRoot(), file.collection()) || !ordinaryIndexOwner(path)) return false;
            var ancestors = currentAncestors(file.ingestionRoot(), path);
            var before = Files.readAttributes(path,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || SyncDirectoryOps.isCloudPlaceholder(path)) return false;
            String hash = SourceContentHash.sha256(path);
            var after = Files.readAttributes(path,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())
                || !ancestors.equals(currentAncestors(file.ingestionRoot(), path))) {
              throw new IOException("Native policy source changed during normal witness capture");
            }
            ancestry.put(path, ancestors);
            entries.add(new NativePolicyReadmission(old.op(),
                old.row(), new JobQueue.EnqueueEntry(path, after.size(), file.provenance(),
                    hash, file.ingestionRoot()), group.getKey().collection()));
          }
          if (!current()) return false;
          // Recheck every member immediately before the atomic queue admission.
          for (var entry : entries) {
            if (!ancestry.get(entry.entry().path()).equals(
                currentAncestors(entry.entry().ingestionRoot(), entry.entry().path()))) {
              throw new IOException("Native source ancestry changed before readmission");
            }
          }
          var actual = queue.readmitNativePolicySkippedAdmissions(candidate, journal, entries);
          if (actual.isEmpty()) return false;
          try {
            for (var entry : entries) {
              if (!ancestry.get(entry.entry().path()).equals(
                  currentAncestors(entry.entry().ingestionRoot(), entry.entry().path()))) {
                throw new IOException("Native source ancestor identity changed after commit");
              }
            }
          } catch (IOException changed) {
            // The atomic receipt already committed. Retain it; the old batch no longer
            // owns any reset/cleanup/retry authority, even when this observation fails.
            throw new IOException("Native readmission committed but source ancestry is unconfirmed", changed);
          }
          return true;
        } catch (IOException unknown) { throw new UncheckedIOException(unknown); }
      };
      boolean replaced = committedBoot
          ? publisher.withCommittedBootNativePolicyRoot(group.getKey().root(), admission)
          : publisher.withNativePolicyReadmissionRoot(group.getKey().root(), group.getKey().collection(), admission);
      if (replaced) return true; // Never use this attempt's superseded journal after a replacement.
    }
    return false;
  }

  public boolean certify(SwitchBufferOp op) {
    if (frozen == null) return false;
    final SwitchBufferUpsert file;
    try { file = SwitchBufferUpsert.decode(op.payload()); }
    catch (RuntimeException invalid) { return false; }
    if (!excludes(Path.of(file.path()), file.ingestionRoot(), file.collection())) return false;
    final Map<String, String> row;
    try { row = queue.watcherReconciliationRow(file.path()); }
    catch (RuntimeException unknown) { return false; }
    if (row == null) return false;
    var proof = new Omission(frozen, op, row);
    if (!prove(proof)) return false;
    omissions.put(op, proof);
    return true;
  }

  private boolean prove(Omission proof) {
    var file = SwitchBufferUpsert.decode(proof.op().payload());
    java.util.function.BooleanSupplier absence = () -> {
      try {
        target.commitOps().maybeRefreshBlocking();
        return current() && ordinaryIndexOwner(Path.of(file.path()))
            && target.indexCountOps().countByIdAndChunksStrict(file.path()) == 0
            && queue.tryMatchesNativePolicySkippedAdmission(candidate,
                proof.journal(), proof.op(), proof.row());
      } catch (IOException | RuntimeException unknown) { return false; }
    };
    return current() && (committedBoot
        ? publisher.tryCommittedBootNativePolicyOmission(file.ingestionRoot(), absence)
        : publisher.tryNativePolicyFileProof(file.ingestionRoot(), file.collection(), absence));
  }

  /** A root receipt may own an encoded predecessor; it is never an ordinary native unit. */
  boolean certifyRoot(WatcherReconciliationFile file,
      RootIdentity identity) {
    if (frozen == null || !frozen.equals(file.journal()) || !file.policyOmitted()) return false;
    var proof = new RootOmission(file, identity);
    if (!proveRoot(proof)) return false;
    rootOmissions.put(file.path(), proof);
    return true;
  }

  private boolean proveRoot(RootOmission proof) {
    var file = proof.file();
    var debt = WatcherRootReconciliation.decode(file.rootReceipt().payload());
    return current() && publisher.tryNativePolicyFileProof(Path.of(debt.root()), debt.collection(), () -> {
      try {
        if (!excludes(Path.of(file.path()), Path.of(debt.root()), debt.collection())) return false;
        // Only the directory's held identity is re-observed, never the omitted source's
        // content/stat. No discovery walk or service callback occurs under publication.
        var returned = RootIdentity.capture(Path.of(debt.root()));
        if (!proof.identity().equals(returned) || returned.volume() == null
            || !returned.matchesReceipt(debt.rootIdentity())) return false;
        target.commitOps().maybeRefreshBlocking();
        if (!ordinaryIndexOwner(Path.of(file.path()))
            || target.indexCountOps().countByIdAndChunksStrict(file.path()) != 0) return false;
        return queue.reconcileWatcherFile(file, () -> {
          try {
            var lockedRoot = RootIdentity.capture(Path.of(debt.root()));
            if (!current() || !excludes(Path.of(file.path()), Path.of(debt.root()), debt.collection())
                || !proof.identity().equals(lockedRoot) || lockedRoot.volume() == null
                || !lockedRoot.matchesReceipt(debt.rootIdentity())
                || !ordinaryIndexOwner(Path.of(file.path()))
                || target.indexCountOps().countByIdAndChunksStrict(file.path()) != 0) {
              throw new IllegalStateException("Published native root policy or absence changed");
            }
          } catch (IOException unknown) { throw new UncheckedIOException(unknown); }
        }, () -> { throw new IllegalStateException("Policy omission must not mutate the index"); });
      } catch (IOException | RuntimeException unknown) { return false; }
    });
  }

  /** Final call is nonblocking on file/queue, under actual generation and publication ownership. */
  public boolean reproveBeforePointer() {
    if (omissions.isEmpty() && rootOmissions.isEmpty()) return true;
    if (!publication.isWriteLockedByCurrentThread() || !current()) return false;
    for (var proof : omissions.values()) if (!prove(proof)) return false;
    for (var proof : rootOmissions.values()) if (!proveRoot(proof)) return false;
    return true;
  }

  /** Queue-only allowances are re-proved at each broad consumer, never indexed survivors. */
  public List<String> omittedPaths() {
    for (var proof : omissions.values()) {
      if (!prove(proof)) throw new IllegalStateException("Native omitted queue allowance changed");
    }
    for (var proof : rootOmissions.values()) {
      if (!proveRoot(proof)) throw new IllegalStateException("Native root queue allowance changed");
    }
    List<String> paths = new ArrayList<>();
    for (var op : omissions.keySet()) paths.add(
        SwitchBufferUpsert.decode(op.payload()).path());
    paths.addAll(rootOmissions.keySet());
    return List.copyOf(paths);
  }
}
