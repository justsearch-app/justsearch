/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.adapters.lucene.runtime.PathPrefixScope;

import static io.justsearch.indexerworker.services.IngestResponses.*;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.SwapReason;
import io.justsearch.app.api.operations.AppliedIndexGeneration;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.ipc.logging.MdcContext;
import io.justsearch.ipc.BatchRequest;
import io.justsearch.ipc.BatchResponse;
import io.justsearch.ipc.DeleteByCollectionRequest;
import io.justsearch.ipc.DeleteByCollectionResponse;
import io.justsearch.ipc.DeleteByIdRequest;
import io.justsearch.ipc.DeleteByIdResponse;
import io.justsearch.ipc.DeleteByPathRequest;
import io.justsearch.ipc.DeleteByPathResponse;
import io.justsearch.ipc.StatusRequest;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.UpdateVduResultResponse;
import io.justsearch.ipc.QueryPendingVduRequest;
import io.justsearch.ipc.QueryPendingVduResponse;
import io.justsearch.ipc.MarkVduProcessingRequest;
import io.justsearch.ipc.MarkVduProcessingResponse;
import io.justsearch.ipc.MigrationCutoverRequest;
import io.justsearch.ipc.MigrationCutoverResponse;
import io.justsearch.ipc.MigrationPauseRequest;
import io.justsearch.ipc.MigrationPauseResponse;
import io.justsearch.ipc.MigrationRollbackRequest;
import io.justsearch.ipc.MigrationRollbackResponse;
import io.justsearch.ipc.MigrationResumeRequest;
import io.justsearch.ipc.MigrationResumeResponse;
import io.justsearch.ipc.MigrationStartRequest;
import io.justsearch.ipc.MigrationStartResponse;
import io.justsearch.ipc.FailedJob;
import io.justsearch.ipc.IndexGcRequest;
import io.justsearch.ipc.IndexGcResponse;
import io.justsearch.ipc.ListFailedJobsRequest;
import io.justsearch.ipc.ListFailedJobsResponse;
import io.justsearch.ipc.ClearFailedJobsRequest;
import io.justsearch.ipc.ClearFailedJobsResponse;
import io.justsearch.ipc.ResetIndexRequest;
import io.justsearch.ipc.ResetIndexResponse;
import io.justsearch.ipc.SettleIndexRequest;
import io.justsearch.ipc.SettleIndexResponse;
import io.justsearch.ipc.RecoverVduProcessingRequest;
import io.justsearch.ipc.RecoverVduProcessingResponse;
import io.justsearch.ipc.UpdatePathsRequest;
import io.justsearch.ipc.UpdatePathsResponse;
import io.justsearch.ipc.UpgradeQuiescenceRequest;
import io.justsearch.ipc.UpgradeQuiescenceResponse;
import io.justsearch.ipc.PathMapping;
import io.justsearch.ipc.PruneRequest;
import io.justsearch.ipc.PruneResponse;
import io.justsearch.ipc.SyncDirectoryRequest;
import io.justsearch.ipc.SyncDirectoryResponse;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.IndexingJobChangeFeed;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.queue.WatcherRootReconciliation;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.index.MigrationProgressSnapshot;
import io.justsearch.indexerworker.util.ParseUtils;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.ort.OrtCudaStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ingest service implementation for batch file ingestion.
 *
 * <p>Receives file paths from the main process and enqueues them for indexing.
 * The actual indexing is performed asynchronously by the IndexingLoop.
 *
 * <p>Lane F stage A item A3: this is a plain service — it returns its response object and reports
 * failure by throwing {@link WorkerServiceException}. While the wire is still up (through A9) it is
 * reached, until item A9 deleted it, through a wire adapter that did the transport framing and
 * maps each failure back onto the identical status code.
 *
 * <p><b>Security:</b> All paths are validated and sanitized before processing.
 * <p><b>Rate Limiting:</b> Batch size is capped to prevent queue flooding.
 */
public final class WorkerIngestService {
  private static final Logger log = LoggerFactory.getLogger(WorkerIngestService.class);
  /** Maximum files allowed in a single batch request. */
  private static final int MAX_BATCH_SIZE = 10_000;

  /** Maximum queue depth before rejecting new submissions. */
  private static final long MAX_QUEUE_DEPTH = 100_000;

  private static final tools.jackson.databind.ObjectMapper COMMITTED_INPUTS_JSON =
      tools.jackson.databind.json.JsonMapper.builder()
          .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  /** Opens an MDC scope with the trace_id and request_id the caller propagated. */
  private MdcContext openRequestMdc(
      CallContext ctx) { // NOPMD - AutoCloseable for logging context side-effect
    return MdcContext.request(ctx.traceId(), ctx.requestId());
  }

  /** Dangerous path components that indicate traversal attempts. */
  private static final Set<String> DANGEROUS_COMPONENTS = Set.of("..", "..\\", "../");

  private final java.util.concurrent.ConcurrentMap<String, Object> collectionRemovals =
      new java.util.concurrent.ConcurrentHashMap<>();

  private Object collectionRemoval(String collection) {
    return collectionRemovals.get(collection == null || collection.isBlank() ? "default" : collection);
  }

  private final JobQueue jobQueue;
  private final IndexingLoop indexingLoop;
  // Where force-reindex marks go. By default the loop's own set; a read-only (deferred) service set
  // has no loop, so its composer points this at the set the upgraded writer's loop will consume.
  private java.util.function.Consumer<java.util.Collection<String>> forcedPathSink;
  /** Tempdoc 885 item 3: foreground-contention duty cycle for the prune / sync walks. */
  private final IndexingPacing indexingPacing;
  private final io.justsearch.adapters.lucene.runtime.RunningRuntime ingestLifecycle;
  private final IndexGenerationManager indexGenerationManager;
  /** Volume each root was first pruned from, kept across restarts (#758). */
  private final RootVolumeLedger rootVolumes;
  /** #758: the root-identity check every deletion decision asks. */
  private final RootWitness rootWitness;
  private final boolean ingestIsServing;
  private final boolean hasServingRuntime;
  private final io.justsearch.adapters.lucene.runtime.LuceneRuntime searchLifecycle;
  private final Path capturedServingPath;
  private final OperationalMetrics metrics = OperationalMetrics.getInstance();
  private final IndexStatusOps statusOps;
  private final SyncDirectoryOps syncOps;
  private final SyncDirectoryOps candidateDiscoveryOps;
  private final IngestSwitchBufferOps switchBufferOps;
  private final ConfirmedDeletionMarker confirmedDeletionMarker;
  /** Serializes one no-file identity's journal admission with its serving projection. */
  private final java.util.concurrent.locks.ReentrantLock[] projectionLocks =
      java.util.stream.IntStream.range(0, 64)
          .mapToObj(ignored -> new java.util.concurrent.locks.ReentrantLock())
          .toArray(java.util.concurrent.locks.ReentrantLock[]::new);
  private WorkerMutationAdmission mutationAdmission;
  private Object mutationOwner;
  private java.util.function.BooleanSupplier projectionSemanticDeferral = () -> false;
  private final MigrationControlOps migrationOps;
  private final WorkerUpgradeQuiescence upgradeQuiescence;
  private final IndexSettleOps settleOps;
  private RootWatcherRegistry rootWatcherRegistry = new RootWatcherRegistry();
  // Tempdoc 419 / T5.3 (ADR-0028): scoped reverse-lookup store. Defaults to NOOP so any
  // composition that hasn't wired it sees found=false on every lookup. DefaultWorkerAppServices
  // injects the real SqlitePathResolutionStore via setPathResolutionStore at boot.
  private io.justsearch.indexerworker.path.PathResolutionStore pathResolutionStore =
      io.justsearch.indexerworker.path.PathResolutionStore.NOOP;
  private io.justsearch.indexerworker.identity.DocumentIdentityStore documentIdentityStore =
      io.justsearch.indexerworker.identity.DocumentIdentityStore.UNAVAILABLE;

  private static final String VDU_MAX_RETRIES_EXCEEDED_ERROR = "Max retries exceeded";
  private static final String VDU_MAX_RETRIES_EXCEEDED_ENRICHMENT =
      "{\"error\": \"Max retries exceeded\"}";
  private static final int RECOVER_VDU_QUERY_LIMIT = 1000;
  /** VDU claims are process-local work authority, never durable document identity. */
  private final Object vduMutationMonitor = new Object();
  private final Map<String, VduProcessingClaim> vduProcessingClaims = new HashMap<>();

  private record VduProcessingClaim(
      java.util.UUID owner, String attemptId, Map<String, Object> expectedRevision,
      int previousRetryCount, int claimedRetryCount) {}

  /**
   * Creates a new WorkerIngestService backed by the specified lifecycle managers.
   *
   * @param jobQueue The job queue for persisting ingest jobs
   * @param indexingLoop The indexing loop for commit status
   * @param signalBus The signal bus for coordination metrics
   * @param indexingPacing The foreground-contention duty cycle (tempdoc 885 item 3)
   * @param indexPath The path to the Lucene index directory (for size calculation)
   * @param ingestLifecycle The lifecycle manager for write/mutation operations
   * @param searchLifecycle The lifecycle manager for search/status reads
   */
  public WorkerIngestService(
      JobQueue jobQueue,
      IndexingLoop indexingLoop,
      WorkerSignalBus signalBus,
      IndexingPacing indexingPacing,
      Path indexBasePath,
      Path indexPath,
      io.justsearch.adapters.lucene.runtime.RunningRuntime ingestLifecycle,
      io.justsearch.adapters.lucene.runtime.LuceneRuntime searchLifecycle,
      Supplier<MigrationProgressSnapshot> migrationProgressSupplier,
      long migrationSwitchingMaxDurationMs) {
    this.jobQueue = jobQueue;
    this.indexingLoop = indexingLoop;
    this.forcedPathSink = paths -> this.indexingLoop.markForced(paths);
    this.indexingPacing =
        Objects.requireNonNull(indexingPacing, "indexingPacing");
    this.ingestLifecycle = ingestLifecycle;
    this.ingestIsServing = ingestLifecycle != null && ingestLifecycle == searchLifecycle;
    this.hasServingRuntime = searchLifecycle != null;
    this.searchLifecycle = searchLifecycle;
    this.capturedServingPath = indexPath;
    this.indexGenerationManager = indexBasePath == null ? null : new IndexGenerationManager(indexBasePath);
    this.rootVolumes = RootVolumeLedger.under(indexBasePath);
    this.rootWitness = RootWitness.of(rootVolumes, this::registeredRootIdentity,
        folder -> rootWatcherRegistry.declaresRootCovering(folder));
    if (jobQueue != null) this.rootWitness.useRelease030ExactFiles(jobQueue::isRelease030ExactFile);
    this.migrationOps = new MigrationControlOps(this.indexGenerationManager);
    this.upgradeQuiescence =
        new WorkerUpgradeQuiescence(jobQueue, indexingLoop, this.indexGenerationManager);
    this.settleOps =
        new IndexSettleOps(ingestLifecycle, this.indexGenerationManager, this.upgradeQuiescence);
    io.justsearch.adapters.lucene.runtime.IndexCountOps ingestCountOps =
        ingestLifecycle != null ? ingestLifecycle.indexCountOps() : null;
    io.justsearch.adapters.lucene.runtime.IndexCountOps searchCountOps =
        searchLifecycle != null ? searchLifecycle.indexCountOps() : null;
    this.statusOps =
        new IndexStatusOps(
            jobQueue,
            indexPath,
            ingestCountOps,
            searchCountOps,
            ingestLifecycle != null ? ingestLifecycle::configuredVectorFormat : null,
            // Everything STORED — the committed identity of the index — describes the generation the
            // user's queries reach, so it is read off the SEARCH runtime. Off the ingest runtime it
            // described Green during a migration (freshly stamped with the CURRENT shape, so the
            // compat surface reported COMPATIBLE while every query was answered from the stale-shape
            // Blue), and nothing at all in the exhausted-brake and deferred-open states, where there
            // is no write runtime and the supplier was null (so the stored fingerprint came back
            // empty — which the status path reads as BLOCKED_LEGACY, the wrong remedy). The search
            // runtime is non-null in every one of those states. Live validation, 2026-09-03, D1/D3.
            searchLifecycle != null ? searchLifecycle::storedVectorFormat : null,
            ingestLifecycle != null ? ingestLifecycle::queryVectorFormatActual : null,
            searchLifecycle != null ? searchLifecycle::openTimeCommitUserData : null,
            searchLifecycle != null ? searchLifecycle::latestCommitUserDataBestEffort : null,
            this.indexGenerationManager,
            migrationProgressSupplier,
            metrics,
            indexingLoop,
            signalBus,
            migrationSwitchingMaxDurationMs);
    this.statusOps.setMigrationReplayFailure(() -> watcherReplayFailure);
    if (ingestLifecycle != null) {
      this.statusOps.setWriterTragic(ingestLifecycle::hasTragicWriterException);
    }
    this.statusOps.setOpenedRuntimePaths(
        searchLifecycle == null ? null : searchLifecycle.openedIndexPath(),
        ingestLifecycle == null ? null : ingestLifecycle.openedIndexPath());
    this.confirmedDeletionMarker = new ConfirmedDeletionMarker(() -> this.documentIdentityStore);
    this.syncOps = new SyncDirectoryOps(
        ingestLifecycle != null ? ingestLifecycle.readPathOps() : null,
        ingestLifecycle != null ? ingestLifecycle.pruneOps() : null,
        ingestLifecycle != null ? ingestLifecycle.commitOps() : null,
        jobQueue,
        this.indexingPacing,
        // Read through a supplier: the identity store is wired by setDocumentIdentityStore AFTER
        // this constructor runs, so capturing the field here would capture the UNAVAILABLE sentinel.
        confirmedDeletionMarker);
    this.syncOps.setForcedPathSink(paths -> this.forcedPathSink.accept(paths));
    this.syncOps.setPruneGuardFactory(this::pruneGuard);
    this.syncOps.setPruneWriterFence(pruneWriterFence);
    this.syncOps.setUserExclusions(this::isUserExcluded);
    this.syncOps.setRetirementExclusions(this::isUserExcludedForRetirement);
    this.syncOps.setPrefixScopeFactory(root -> prefixScope(root.toString()));
    this.candidateDiscoveryOps = new SyncDirectoryOps(
        searchLifecycle != null ? searchLifecycle.readPathOps() : null,
        null,
        null,
        jobQueue,
        this.indexingPacing,
        confirmedDeletionMarker);
    this.candidateDiscoveryOps.setPrefixScopeFactory(root -> prefixScope(root.toString()));
    this.switchBufferOps =
        new IngestSwitchBufferOps(jobQueue, this.indexGenerationManager);
    this.syncOps.useDeclaredCollectionRoots(this::declaredCollectionRoots);
    this.candidateDiscoveryOps.useDeclaredCollectionRoots(this::declaredCollectionRoots);
  }

  private Path declaredRootsDirectory;
  private DeclarationSnapshot declarationSnapshot;

  private record RegistryIdentity(Object fileKey, long size, java.nio.file.attribute.FileTime modified) {}
  private record DeclarationSnapshot(RegistryIdentity identity, Map<Path, String> collections) {}

  /** One metadata probe per use; official registry writes replace the file atomically. */
  private synchronized Map<Path, String> persistedDeclaredCollections() throws IOException {
    if (declaredRootsDirectory == null) return Map.of();
    Path file = declaredRootsDirectory.resolve("watched_roots.json");
    RegistryIdentity identity;
    try {
      var attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
      identity = new RegistryIdentity(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime());
    } catch (java.nio.file.NoSuchFileException absent) {
      if (!Files.notExists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw absent;
      identity = null;
    }
    if (declarationSnapshot == null || !Objects.equals(identity, declarationSnapshot.identity())) {
      declarationSnapshot = new DeclarationSnapshot(identity,
          io.justsearch.configuration.persistence.WatchedRootsFormat.declaredCollections(declaredRootsDirectory));
    }
    return declarationSnapshot.collections();
  }

  /** The authored declarations include roots whose worker watch has not initialized yet. */
  public synchronized void setDeclaredRootsDirectory(Path dataDir) {
    declaredRootsDirectory = dataDir;
    declarationSnapshot = null;
    if (jobQueue != null) jobQueue.useDeclaredCollectionRoots(this::declaredCollectionRoots);
  }

  private List<io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding> declaredCollectionRoots() {
    var bindings = new java.util.LinkedHashMap<Path, String>();
    configuredRecoveryRoots.forEach(root -> bindings.put(root, creatorRootCollection.apply(root)));
    rootWatcherRegistry.current().collectionBindings().forEach(binding -> bindings.put(binding.path(), binding.collection()));
    try {
      persistedDeclaredCollections().forEach(bindings::put);
    } catch (IOException | RuntimeException unreadable) {
      throw WorkerServiceException.unavailable("Watched-root declarations are unreadable");
    }
    return bindings.entrySet().stream().map(entry ->
        new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(entry.getKey(), entry.getValue())).toList();
  }

  public PathPrefixScope prefixScope(String prefix) {
    Path root = Path.of(prefix).toAbsolutePath().normalize();
    var declarations = new java.util.HashSet<>(rootWatcherRegistry.current().declaredRoots());
    declarations.addAll(configuredRecoveryRoots);
    try {
      if (declaredRootsDirectory == null) declarations.addAll(exclusionRoots.get());
      else declarations.addAll(persistedDeclaredCollections().keySet());
    } catch (IOException | RuntimeException unreadable) {
      throw WorkerServiceException.unavailable("Watched-root declarations are unreadable");
    }
    return new PathPrefixScope(root.toString(),
        declarations.stream().filter(path -> !path.equals(root) && path.startsWith(root))
            .map(Path::toString).toList());
  }

  /** Bound once by the composing Worker owner before any request is published. */
  public void setMutationAdmission(WorkerMutationAdmission admission, Object owner) {
    if (mutationAdmission != null) throw new IllegalStateException("Mutation admission already bound");
    mutationAdmission = Objects.requireNonNull(admission, "admission");
    mutationOwner = Objects.requireNonNull(owner, "owner");
  }

  private WorkerMutationAdmission.Lease mutationLease() {
    if (mutationAdmission == null) return null; // isolated service fixtures
    try { return mutationAdmission.enter(mutationOwner); }
    catch (IllegalStateException retired) {
      throw WorkerServiceException.unavailable("Mutation producer belongs to a retired generation");
    }
  }

  /** The composing owner routes force-reindex marks, so a service set without a loop keeps them. */
  public void setForcedPathSink(java.util.function.Consumer<java.util.Collection<String>> sink) {
    forcedPathSink = Objects.requireNonNull(sink, "sink");
  }

  /** The composing owner supplies the serving mode used by admitted no-file writes. */
  public void setProjectionSemanticDeferralSupplier(java.util.function.BooleanSupplier supplier) {
    projectionSemanticDeferral = Objects.requireNonNull(supplier, "supplier");
  }

  /** D1-9 no-file port. Durable receipts remain refused until D2-5's covering-commit owner lands. */
  public io.justsearch.app.api.indexing.ProjectionReceipt applyProjection(
      io.justsearch.app.api.indexing.AcceptedProjection projection,
      io.justsearch.app.api.indexing.ProjectionDurability durability, CallContext ctx) {
    Objects.requireNonNull(projection, "projection");
    Objects.requireNonNull(durability, "durability");
    if (durability == io.justsearch.app.api.indexing.ProjectionDurability.DURABLE) {
      throw WorkerServiceException.failedPrecondition(
          "Durable projection receipt requires the D2-5 covering-commit owner");
    }
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      if (ctx.cancelled()) throw WorkerServiceException.cancelled("Projection admission cancelled");
      var stripe = projectionLocks[Math.floorMod(
          projection.indexId().hashCode(), projectionLocks.length)];
      stripe.lock();
      try {
        io.justsearch.adapters.lucene.runtime.RunningRuntime serving =
            searchLifecycle instanceof io.justsearch.adapters.lucene.runtime.RunningRuntime active
                && active.isAcceptingWrites() ? active : null;
        if (serving == null || indexGenerationManager == null) {
          throw WorkerServiceException.unavailable("Serving projection writer is unavailable");
        }
        // Accepted no-file writes target active Blue and are also buffered for Green during a
        // build. Recorded file preparation's IDLE requirement does not apply to this port.
        String generationId = requireCapturedActiveGeneration(false);
        boolean semanticDeferred = projectionSemanticDeferral.getAsBoolean();
        String building = switchBufferOps.buildingGenerationForFileAdmission();
        if (building != null) {
          if (!(jobQueue instanceof SwitchBufferCapableQueue buffer)) {
            throw IngestSwitchBufferOps.switchBufferUnavailable();
          }
          var admitted = buffer.admitProjectionForGeneration(building, projection.encode());
          if (admitted == SwitchBufferCapableQueue.ProjectionAdmission.STALE) {
            throw WorkerServiceException.failedPrecondition("Projection source revision is stale");
          }
          if (admitted == SwitchBufferCapableQueue.ProjectionAdmission.CONFLICT) {
            throw WorkerServiceException.failedPrecondition("Projection source revision conflicts");
          }
        }
        if (projection.kind() == io.justsearch.app.api.indexing.AcceptedProjection.Kind.DELETE) {
          serving.indexingCoordinator().deleteByIdAndChunks(projection.indexId());
        } else {
          serving.indexingCoordinator().indexSingle(
              ProjectionDocumentMapper.toIndexDocument(projection));
        }
        serving.commitOps().maybeRefreshBlocking();
        return new io.justsearch.app.api.indexing.ProjectionReceipt(
            projection.sourceId(), projection.documentId(), projection.sourceRevision(),
            generationId, semanticDeferred
                    && projection.kind() == io.justsearch.app.api.indexing.AcceptedProjection.Kind.UPSERT
                ? io.justsearch.app.api.indexing.ProjectionReceipt.Visibility.TEXT_ONLY_SEMANTIC_AT_ACTIVATION
                : io.justsearch.app.api.indexing.ProjectionReceipt.Visibility.NRT);
      } finally {
        stripe.unlock();
      }
    }
  }

  private <T> T withFileMutationFence(Supplier<T> mutation) {
    return mutationAdmission == null || indexingLoop == null
        ? mutation.get() : indexingLoop.withFileMutationFence(mutation);
  }

  /**
   * A prune applies each batch of deletions inside the same fence as the file writers, and judges
   * again the files the loop's writers published while it scanned.
   */
  private final io.justsearch.adapters.lucene.runtime.PruneOps.WriterFence pruneWriterFence =
      new io.justsearch.adapters.lucene.runtime.PruneOps.WriterFence() {
        @Override
        public boolean hold(java.util.function.BooleanSupplier batch) {
          return withFileMutationFence(batch::getAsBoolean);
        }

        @Override
        public io.justsearch.adapters.lucene.runtime.PruneOps.Publications watch() {
          io.justsearch.indexerworker.loop.FileMutationFence fence = fileMutationFence();
          // No loop, or one without a fence of its own (a test double), records nothing.
          return fence == null
              ? io.justsearch.adapters.lucene.runtime.PruneOps.Publications.NONE : fence.watch();
        }
      };

  private io.justsearch.indexerworker.loop.FileMutationFence fileMutationFence() {
    return mutationAdmission == null || indexingLoop == null
        ? null : indexingLoop.fileMutationFence();
  }

  /**
   * Tells a running prune that rows of {@code path} were published, as the loop's writers do; call
   * it inside the fence, before it is released. A prune whose scan ran before these rows existed
   * judges {@code path} in its last pass.
   */
  private void publishedUnderFence(String path) {
    io.justsearch.indexerworker.loop.FileMutationFence fence = fileMutationFence();
    if (fence != null) {
      fence.published(path);
    }
  }

  /**
   * Runs {@code mutation} inside this Worker's file-mutation fence, the one its indexing loop's
   * writers and its prunes hold: for a replayed write that must not interleave with a prune. The
   * mutation checks, inside the fence, that what it writes for still exists.
   */
  public <T> T underFileMutationFence(Supplier<T> mutation) {
    return withFileMutationFence(mutation);
  }

  RootWatcherRegistry.PolicyRoots nativePolicyRoots() {
    return rootWatcherRegistry.current().policyRoots();
  }

  boolean nativePolicyRootScopeCurrent(RootWatcherRegistry.PolicyRoots captured, Path path, Path root) {
    return captured != null && rootWatcherRegistry.current() == captured.owner() && captured.current(path, root);
  }

  io.justsearch.indexerworker.loop.FileMutationFence holdNativePolicyFileWriter() throws IOException {
    var file = fileMutationFence();
    if (mutationAdmission == null || mutationAdmission.finalFenceScope() == null || file == null) {
      throw new IOException("Native ownership lacks its final producer fence");
    }
    file.lock();
    return file;
  }

  /** Both physical runtimes, under the actual final producer and file-writer exclusions. */
  NativePolicyOwnership nativePolicyOwnership(Set<String> paths,
      io.justsearch.adapters.lucene.runtime.RunningRuntime expectedTarget, long deadlineNanos) throws IOException {
    var file = fileMutationFence();
    if (mutationAdmission == null || mutationAdmission.finalFenceScope() == null
        || file == null || !file.isHeldByCurrentThread()
        || ingestLifecycle == null || ingestLifecycle != expectedTarget || searchLifecycle == null) {
      throw new IOException("Native ownership lacks continuous writer exclusion");
    }
    Object scope = mutationAdmission.finalFenceScope();
    var ingest = ingestLifecycle.readPathOps().inspectNativeOwnership(paths,
        io.justsearch.app.api.indexing.AcceptedProjection::isProjectionIndexId, deadlineNanos);
    var serving = searchLifecycle == ingestLifecycle ? ingest
        : searchLifecycle.readPathOps().inspectNativeOwnership(paths,
            io.justsearch.app.api.indexing.AcceptedProjection::isProjectionIndexId, deadlineNanos);
    java.util.function.BooleanSupplier held = () -> mutationAdmission.finalFenceScope() == scope && file.isHeldByCurrentThread();
    return new NativePolicyOwnership(path -> held.getAsBoolean() && ingest.ordinary(path) && serving.ordinary(path),
        path -> held.getAsBoolean() && ingest.absent(path));
  }

  record NativePolicyOwnership(java.util.function.Predicate<String> ordinary,
      java.util.function.Predicate<String> targetAbsent) {
    boolean ordinary(String path) { return ordinary.test(path); }
    boolean targetAbsent(String path) { return targetAbsent.test(path); }
  }

  /** Native policy certification never waits on a file writer under publication ownership. */
  public boolean tryNativePolicyFileProof(java.util.function.BooleanSupplier proof) {
    var file = fileMutationFence();
    if (file == null || !file.tryLock()) return false;
    try { return proof.getAsBoolean(); }
    finally { file.unlock(); }
  }

  /** Read-only proof uses the same real registered root, without waiting on a file writer. */
  public boolean tryNativePolicyFileProof(Path root, String collection, java.util.function.BooleanSupplier proof) {
    return tryNativePolicyFileProof(() -> {
      try {
        var registry = rootWatcherRegistry.current();
        var subscription = registry.subscription(root);
        requireCandidateSubscription(registry, root, subscription);
        if (!Objects.equals(WatcherReplay.rootOwnerCollection(collection),
            WatcherReplay.rootOwnerCollection(subscription.collection()))) return false;
        try (var held = RootIdentity.hold(root)) {
          if (!held.identity().equals(subscription.rootIdentity())) return false;
          requireNativePolicyVolume(root, held.identity());
          held.requireCurrent();
          boolean matched = proof.getAsBoolean();
          held.requireCurrent();
          requireNativePolicyVolume(root, held.identity());
          requireCandidateSubscription(registry, root, subscription);
          return matched;
        }
      } catch (IOException | RuntimeException unknown) { return false; }
    });
  }

  /** The live registered directory/volume and actual writer own this short ordinary admission. */
  public <T> T withNativePolicyReadmissionRoot(Path root, String collection, Supplier<T> action)
      throws IOException {
    var registry = rootWatcherRegistry.current();
    var subscription = registry.subscription(root);
    requireCandidateSubscription(registry, root, subscription);
    if (!Objects.equals(WatcherReplay.rootOwnerCollection(collection),
        WatcherReplay.rootOwnerCollection(subscription.collection()))) {
      throw new IOException("Native policy readmission root owner is unavailable");
    }
    try (var held = RootIdentity.hold(root)) {
      requireNativePolicyVolume(root, held.identity());
      if (!held.identity().equals(subscription.rootIdentity())) {
        throw new IOException("Native policy readmission needs its registered directory and volume");
      }
      try {
        return underFileMutationFence(() -> {
          try {
            held.requireCurrent();
            requireNativePolicyVolume(root, held.identity());
            requireCandidateSubscription(registry, root, subscription);
            T result = action.get();
            held.requireCurrent();
            requireNativePolicyVolume(root, held.identity());
            requireCandidateSubscription(registry, root, subscription);
            return result;
          } catch (IOException changed) { throw new java.io.UncheckedIOException(changed); }
        });
      } catch (java.io.UncheckedIOException changed) { throw changed.getCause(); }
    }
  }

  /** CURRENT UI/index omission of an admitted pathname, never historical DELETE/root absence. */
  public boolean tryCommittedBootNativePolicyOmission(Path root, java.util.function.BooleanSupplier proof) {
    if (mutationAdmission == null || indexingLoop == null || ingestLifecycle == null
        || ingestLifecycle != searchLifecycle || !ingestLifecycle.isAcceptingWrites()) return false;
    return tryNativePolicyFileProof(() -> {
      try (var held = RootIdentity.hold(root)) {
        held.requireCurrent();
        requireNativePolicyVolume(root, held.identity());
        boolean matched = proof.getAsBoolean();
        held.requireCurrent();
        requireNativePolicyVolume(root, held.identity());
        return matched;
      } catch (IOException | RuntimeException unknown) { return false; }
    });
  }

  /** No subscription is invented: committed boot's bound declarations own PRESENT additions only. */
  public <T> T withCommittedBootNativePolicyRoot(Path root, Supplier<T> action) throws IOException {
    if (mutationAdmission == null || indexingLoop == null || ingestLifecycle == null
        || ingestLifecycle != searchLifecycle || !ingestLifecycle.isAcceptingWrites()) {
      throw new IOException("Native boot readmission lacks its committed writable producer");
    }
    try (var held = RootIdentity.hold(root)) {
      requireNativePolicyVolume(root, held.identity());
      try {
        return underFileMutationFence(() -> {
          try {
            held.requireCurrent();
            requireNativePolicyVolume(root, held.identity());
            T result = action.get();
            held.requireCurrent();
            requireNativePolicyVolume(root, held.identity());
            return result;
          } catch (IOException changed) { throw new java.io.UncheckedIOException(changed); }
        });
      } catch (java.io.UncheckedIOException changed) { throw changed.getCause(); }
    }
  }

  private void requireNativePolicyVolume(Path root, RootIdentity held) throws IOException {
    String known = held.volume();
    var recorded = rootVolumes.recordedFor(root);
    if (known == null || recorded.isEmpty() || !known.equals(recorded.orElseThrow())) {
      throw new IOException("Native policy root lacks fresh equality to its recorded known volume");
    }
  }

  /** {@link #confirmedAbsent} under the root that holds the path; an invalid path is not. */
  private boolean confirmedGone(String normalizedPath) {
    try {
      return confirmedAbsent(normalizedPath, pruneRootFor(normalizedPath));
    } catch (InvalidPathException invalid) {
      return false;
    }
  }

  /**
   * A rename's Lucene half, inside the fence (the caller holds it, so the cleanup below takes no
   * fence of its own). A destination confirmed absent here was deleted after the move, possibly
   * after a prune already judged it: no row is moved, and the old path's rows are removed as a
   * prune removes them, each only when its own backing file is confirmed absent. Otherwise the rows
   * move, and the new path is reported to a running prune even when the move fails part way,
   * because rows it wrote before failing still reach a commit.
   *
   * @return whether the rename is settled in Lucene: rows moved, or the file is gone from both
   *     paths and rows of it were removed
   */
  private boolean moveRowsUnderFence(String oldPath, String newPath) {
    if (confirmedGone(newPath)) {
      // Renames are refused during a candidate build, so there is no serving copy to project to.
      // Each row of the old path is judged as a prune judges it, by its own backing path: a
      // chunk whose explicit path names a live file keeps its row.
      Path oldRoot;
      try {
        oldRoot = pruneRootFor(oldPath);
      } catch (InvalidPathException invalid) {
        return false;
      }
      int removed = ingestLifecycle.pruneOps().pruneFile(
          oldPath, oldRoot, pruneGuard(oldRoot),
          io.justsearch.adapters.lucene.runtime.PruneOps.WriterFence.NONE);
      log.info("updateDocumentPaths: {} is gone; rows of {} are not moved, {} removed",
          newPath, oldPath, removed);
      return removed > 0;
    }
    try {
      return ingestLifecycle.indexingCoordinator().updateDocumentPaths(oldPath, newPath) > 0;
    } finally {
      publishedUnderFence(newPath);
    }
  }

  /**
   * For a replay service that stands in for {@code publisher}, the live Worker whose indexing loop
   * writes while the replay runs: its prunes are vouched for by {@code publisher}'s root authority
   * (the identity a watch registered, the recorded volume) and applied inside {@code publisher}'s
   * file-mutation fence.
   */
  public void prunesUnder(WorkerIngestService publisher) {
    Objects.requireNonNull(publisher, "publisher");
    syncOps.setForcedPathSink(paths -> publisher.forcedPathSink.accept(paths));
    syncOps.setPruneGuardFactory(publisher::pruneGuard);
    syncOps.setRetirementExclusions(publisher::isUserExcludedForRetirement);
    syncOps.setPruneWriterFence(publisher.pruneWriterFence);
    syncOps.setPrefixScopeFactory(root -> publisher.prefixScope(root.toString()));
    candidateDiscoveryOps.setPrefixScopeFactory(root -> publisher.prefixScope(root.toString()));
    declaredRootsDirectory = publisher.declaredRootsDirectory;
    setRootWatcherRegistry(publisher.rootWatcherRegistry.current());
  }

  /**
   * Replays a buffered prune of {@code pathPrefix} into {@code target}: vouched for by the root
   * that holds the prefix, as this service's own prunes are (present, listable, the identity a
   * watch registered, the volume recorded when the person added it), and applied inside this
   * service's file-mutation fence, the one its indexing loop's writers hold. A file the prune
   * removed that is present again once the removal is committed is queued for indexing before
   * this returns: it was recreated after its last absence check.
   *
   * @return the prune's result and how many recreated files were queued again
   * @param firstRemoval runs once, at the prune's first removal, before anything is committed
   *     (the harness stop point); ordinary replays pass a no-op
   */
  public ReplayedPrune replayBufferedPrune(
      io.justsearch.adapters.lucene.runtime.RunningRuntime target, String pathPrefix,
      Runnable firstRemoval) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(firstRemoval, "firstRemoval");
    Path pruneRoot = pruneRootFor(pathPrefix);
    Set<String> removed = new java.util.LinkedHashSet<>();
    var result = target.pruneOps().pruneByPathPrefix(
        pathPrefix,
        () -> false,
        100,
        pruneGuard(pruneRoot),
        path -> {
          if (removed.isEmpty()) firstRemoval.run();
          removed.add(path);
        },
        pruneWriterFence);
    List<JobQueue.EnqueueEntry> reappeared = new ArrayList<>();
    for (String path : removed) {
      Path file = Path.of(path);
      if (io.justsearch.adapters.lucene.runtime.PathPresence.probe(file)
              == io.justsearch.adapters.lucene.runtime.PathPresence.PRESENT
          && file.toAbsolutePath().normalize().startsWith(pruneRoot)
          && !io.justsearch.indexerworker.ingest.IngestionSkipPolicy
              .shouldSkipWithinRoot(file, pruneRoot)) {
        reappeared.add(JobQueue.EnqueueEntry.stat(file).withinRoot(pruneRoot));
      }
    }
    int queued = 0;
    if (!reappeared.isEmpty()) {
      queued = jobQueue.enqueueEntries(reappeared);
      log.info("Replayed prune of {}: {} removed files were present again, {} queued for indexing",
          pathPrefix, reappeared.size(), queued);
    }
    return new ReplayedPrune(result, queued);
  }

  /**
   * Exact watcher/legacy replay shares the publisher's fence and root proof. Queue work is left
   * intact: a newer UPSERT can arrive after the final filesystem probe. A recreated source is
   * admitted even when an earlier replay crashed after deleting Lucene but before committing.
   * Anything but {@link BufferedDeleteReplay#SETTLED} keeps the exact receipt durable for retry,
   * unless the caller's surviving generation owns the file's root reconciliation.
   */
  public BufferedDeleteReplay replayBufferedFileDelete(
      io.justsearch.adapters.lucene.runtime.RunningRuntime target,
      io.justsearch.indexerworker.queue.SwitchBufferDelete deletion) {
    return withFileMutationFence(() -> {
      Path file = Path.of(deletion.path());
      Path root = bufferedDeleteRoot(deletion);
      var scope = prefixScope(root.toString());
      String normalizedFile = file.toAbsolutePath().normalize().toString();
      if (!scope.contains(normalizedFile)) {
        return scope.excludes(normalizedFile) ? BufferedDeleteReplay.SETTLED : BufferedDeleteReplay.UNCERTAIN;
      }
      var binding = rootWatcherRegistry.subscription(root);
      String collection = binding == null ? deletion.collection() : binding.collection();
      if (deletion.origin() == io.justsearch.indexerworker.queue.SwitchBufferDelete.Origin.LEGACY
          && collection == null) {
        try {
          collection = target.documentFieldOps().getDocumentFieldOrThrow(deletion.path(), SchemaFields.COLLECTION);
        } catch (IOException unreadable) { return BufferedDeleteReplay.FAILED; }
      }
      if (deletion.rootIdentity() != null) {
        try {
          if (!RootIdentity.capture(root).matchesReceipt(deletion.rootIdentity())) {
            return BufferedDeleteReplay.UNCERTAIN;
          }
        } catch (IOException unavailable) {
          return BufferedDeleteReplay.UNCERTAIN;
        }
      }
      var verdict = io.justsearch.indexerworker.loop.SourcePresence.classify(file, root, rootWitness);
      if (verdict == io.justsearch.indexerworker.loop.SourcePresence.Verdict.PRESENT) {
        if (deletion.origin() == io.justsearch.indexerworker.queue.SwitchBufferDelete.Origin.LEGACY) {
          log.warn("Legacy buffered DELETE skipped because its source exists: {}", file);
        }
        if (!rootWitness.admits(root)) return BufferedDeleteReplay.UNCERTAIN;
        // A nonregular replacement queues retirement of the indexed file, so present-file
        // admission alone cannot authorize it when deletion evidence is unavailable.
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            && (!rootWitness.vouches(root)
                || io.justsearch.adapters.lucene.runtime.PathPresence.absentBeneathListableFolder(
                    file, root, null) == io.justsearch.adapters.lucene.runtime.PathPresence.UNKNOWN)) {
          return BufferedDeleteReplay.UNCERTAIN;
        }
        if (!io.justsearch.indexerworker.ingest.IngestionSkipPolicy.shouldSkipWithinRoot(file, root)) {
          var entry = JobQueue.EnqueueEntry.stat(file);
          // Originless rows did not retain an admitting root. Preserve any existing job's root
          // rather than replacing it with an inferred parent; a live binding is authoritative.
          if (deletion.root() != null || binding != null) entry = entry.withinRoot(root);
          if (!jobQueue.enqueueReappearedFile(entry, collection)) {
            return BufferedDeleteReplay.FAILED;
          }
        }
        return BufferedDeleteReplay.SETTLED;
      }
      if (verdict != io.justsearch.indexerworker.loop.SourcePresence.Verdict.ABSENT) {
        return BufferedDeleteReplay.UNCERTAIN;
      }
      target.indexingCoordinator().deleteByIdAndChunks(deletion.path());
      target.commitOps().commitAndTrack(CommitReason.SWITCH_BUFFER_REPLAY);
      target.commitOps().maybeRefreshBlocking();
      // The filesystem is not locked by the writer fence. Check again after the durable effect;
      // on a crash the retained receipt performs this same admission on the next boot.
      if (io.justsearch.adapters.lucene.runtime.PathPresence.probe(file)
          == io.justsearch.adapters.lucene.runtime.PathPresence.PRESENT) {
        if (!rootWitness.admits(root)) return BufferedDeleteReplay.FAILED;
        var entry = JobQueue.EnqueueEntry.stat(file);
        if (deletion.root() != null || binding != null) entry = entry.withinRoot(root);
        return jobQueue.enqueueReappearedFile(entry, collection)
            ? BufferedDeleteReplay.SETTLED : BufferedDeleteReplay.FAILED;
      }
      return confirmedAbsent(deletion.path(), root)
          ? BufferedDeleteReplay.SETTLED : BufferedDeleteReplay.FAILED;
    });
  }

  /**
   * The root {@link #replayBufferedFileDelete} judges a buffered file delete under: the receipt's
   * root, else the watched root that holds the path; an explicit file's own folder when the root
   * names the file itself.
   */
  public Path bufferedDeleteRoot(io.justsearch.indexerworker.queue.SwitchBufferDelete deletion) {
    Path file = Path.of(deletion.path());
    Path root = deletion.root() == null ? pruneRootFor(deletion.path()) : Path.of(deletion.root());
    return root.equals(file.toAbsolutePath().normalize()) ? file.toAbsolutePath().getParent() : root;
  }

  /**
   * A buffered file delete's replay outcome. {@code UNCERTAIN} means nothing was changed because
   * the root or the source could not be proved; {@code FAILED} means a write or proof failed.
   */
  public enum BufferedDeleteReplay { SETTLED, UNCERTAIN, FAILED }

  /** A replayed prune's result, and the number of files it removed that were queued again. */
  public record ReplayedPrune(
      io.justsearch.adapters.lucene.runtime.PruneOps.PruneResult result, int requeued) {}

  private boolean hasDistinctWritableServingTarget() {
    return searchLifecycle instanceof io.justsearch.adapters.lucene.runtime.RunningRuntime active
        && active != ingestLifecycle && active.isAcceptingWrites();
  }

  /**
   * Owns the lock order for a live filesystem callback. Mutation admission is always entered
   * before the per-registration event lease; watch/unwatch use the same order, so the final
   * cutover fence cannot deadlock against a draining watcher incarnation. Identity is checked
   * again at the concrete upsert/delete boundary; filesystem replacement is not atomic with the
   * effect, but every replacement detected before or after it latches replay uncertainty.
   */
  public void acceptWatcherEvent(
      RootWatcherRegistry.Subscription witness, Runnable effect) {
    Objects.requireNonNull(witness, "witness");
    Objects.requireNonNull(effect, "effect");
    try (var ignoredMutation = mutationLease()) {
      RootWatcherRegistry registry = rootWatcherRegistry;
      try (RootWatcherRegistry.EventLease eventLease = registry.enterEvent(witness)) {
        if (eventLease == null) return; // stale epoch: intentionally ignored, successor stays clean
        effect.run();
        witness.rootIdentity().requireCurrent(witness.root());
      } catch (IOException unavailableOrRebound) {
        if (mutationAdmission != null) mutationAdmission.markReplayUncertain();
        log.warn(
            "Watcher event refused because its registered root identity is unavailable or changed");
      }
    }
  }

  /** Live UPSERT retains its registration identity through concrete queue admission. */
  public void acceptWatcherUpsert(
      RootWatcherRegistry.Subscription witness, String collection, Path path) {
    Objects.requireNonNull(witness, "witness");
    try (var ignoredMutation = mutationLease()) {
      requireWatcherRootCurrent(witness);
      if (io.justsearch.indexerworker.ingest.IngestionSkipPolicy.shouldSkipWithinRoot(
          path, witness.root())) return;
      acceptWatcherUpsertUnderLease(collection, path, witness.root());
      requireWatcherRootCurrent(witness);
    }
  }

  private boolean acceptWatcherUpsertUnderLease(String collection, Path path, Path root) {
    if (!prefixScope(root.toString()).contains(path.toAbsolutePath().normalize().toString())) return false;
    if (io.justsearch.indexerworker.ingest.IngestionSkipPolicy.shouldSkipWithinRoot(path, root)) {
      return false;
    }
    var entry = WorkerMethvinWatcher.entryForLiveEvent(path).withinRoot(root);
    String candidateGeneration = switchBufferOps.buildingGenerationForFileAdmission();
    if (candidateGeneration != null) {
      if (!(jobQueue instanceof SwitchBufferCapableQueue sbq)
          || !sbq.enqueueAndBufferFileForGeneration(
              candidateGeneration, entry, collection)) {
        throw IngestSwitchBufferOps.switchBufferUnavailable();
      }
    } else if (jobQueue.enqueueEntries(List.of(entry), collection) != 1) {
      throw WorkerServiceException.unavailable("QUEUE_ADMISSION_FAILED");
    }
    return true;
  }

  /** The watcher owns its deletion marker; this method owns its routing and effect fence. */
  public void acceptWatcherDelete(String normalizedPath, Runnable directEffect) {
    Objects.requireNonNull(directEffect, "directEffect");
    try (var ignoredMutation = mutationLease()) {
      acceptWatcherDeleteUnderLease(normalizedPath, directEffect, false, true, null, null);
    }
  }

  /** Live DELETE retains its registration identity through the concrete file-mutation fence. */
  public void acceptWatcherDelete(
      RootWatcherRegistry.Subscription witness,
      String normalizedPath,
      Runnable directEffect) {
    Objects.requireNonNull(witness, "witness");
    Objects.requireNonNull(directEffect, "directEffect");
    try (var ignoredMutation = mutationLease()) {
      acceptWatcherDeleteUnderLease(
          normalizedPath, directEffect, false, true, witness.root(), witness.rootIdentity());
    }
  }

  private boolean acceptWatcherDeleteUnderLease(
      String normalizedPath, Runnable directEffect, boolean requireConfirmedAbsence,
      boolean skipUnlessConfirmedAbsent, Path requiredRoot, RootIdentity expectedRootIdentity) {
    if (requiredRoot != null && !prefixScope(requiredRoot.toString())
        .contains(Path.of(normalizedPath).toAbsolutePath().normalize().toString())) return false;
    return withFileMutationFence(() -> {
      if (expectedRootIdentity != null) {
        requireCandidateRootIdentity(requiredRoot, expectedRootIdentity);
      }
      if (skipUnlessConfirmedAbsent && !confirmedAbsent(normalizedPath, requiredRoot)) {
        // #758: re-checked under the fence, because the file may have come back
        // (or its root gone away) since the watcher confirmed the delete. Nothing is removed; a
        // present file is re-admitted by its own CREATE/MODIFY event or the next sync.
        log.debug("Watcher delete skipped: absence of {} no longer confirmed", normalizedPath);
        return false;
      }
      if (requireConfirmedAbsence && !confirmedAbsent(normalizedPath, requiredRoot)) {
        throw WorkerServiceException.unavailable(
            "Candidate reconciliation lacks proven absence on its recorded volume");
      }
      Path root = requiredRoot == null ? Path.of(normalizedPath).getParent() : requiredRoot;
      if (switchBufferOps.isSwitching() && !hasDistinctWritableServingTarget()) {
        journalWatcherDelete(normalizedPath, root, expectedRootIdentity);
        return true;
      }
      if (ingestLifecycle == null || !ingestLifecycle.isAcceptingWrites()) {
        if (switchBufferOps.buildingGenerationForFileAdmission() != null) {
          journalWatcherDelete(normalizedPath, root, expectedRootIdentity);
          return true;
        }
        deferWatcherRootReconciliation(root, expectedRootIdentity);
        return true;
      }
      journalAndProjectWatcherDeleteToServing(normalizedPath, root, expectedRootIdentity);
      directEffect.run();
      if (expectedRootIdentity != null) {
        requireCandidateRootIdentity(requiredRoot, expectedRootIdentity);
      }
      return true;
    });
  }

  private static final String WATCHER_RECONCILE = WatcherRootReconciliation.OPERATION;
  private volatile String watcherReplayFailure;

  public void watcherReplayRefusal(String reason) { watcherReplayFailure = reason; }

  /** Failure callbacks cannot take admission: they can arrive while the final fence is held. */
  public void retainUnavailableWatcherRoot(WorkerMutationAdmission.RootUncertainty uncertainty) {
    var debt = uncertainty.debt();
    if (mutationAdmission == null || !(jobQueue instanceof SwitchBufferCapableQueue journal)) {
      throw WorkerServiceException.unavailable("Watcher root debt has no durable owner");
    }
    mutationAdmission.markRootUnavailable(uncertainty);
    if (journal.listSwitchBufferOpsStrict().stream().anyMatch(receipt ->
        debt.key().equals(receipt.key()) && WATCHER_RECONCILE.equals(receipt.op()))) return;
    if (!journal.putSwitchBuffer(debt.key(), WATCHER_RECONCILE, debt.encode())) {
      mutationAdmission.markReplayUncertain();
      throw IngestSwitchBufferOps.switchBufferUnavailable();
    }
  }

  /** Called only with committed per-path evidence, never with a receipt alone. */
  public void watcherRootReconciled(WatcherRootReconciliation debt,
      List<WorkerMutationAdmission.RootUncertainty> captured) {
    if (mutationAdmission == null) return;
    for (var exact : captured) {
      if (exact.debt().equals(debt)) mutationAdmission.reconciledRoot(exact);
    }
  }

  public List<WorkerMutationAdmission.RootUncertainty> unavailableWatcherRoots() {
    return mutationAdmission == null ? List.of() : mutationAdmission.unavailableRoots();
  }

  public boolean acknowledgeWatcherReplay(java.util.function.BooleanSupplier acknowledgement) {
    return mutationAdmission == null ? acknowledgement.getAsBoolean()
        : mutationAdmission.acknowledgeReconciledRoots(acknowledgement);
  }

  /** Restore observation before releasing a repaired root's uncertainty under the final fence. */
  public void resumeReconciledWatcherRoot(WatcherRootReconciliation debt) throws IOException {
    Path root = Path.of(debt.root());
    var registry = rootWatcherRegistry.current();
    var identity = registry.registeredIdentity(root);
    if (identity == null) {
      // On boot, fresh replay already matched this root against frozen durable membership.
      if (!registry.watch(root.toString(), debt.collection()).watching()) {
        throw new IOException("Reconciled watcher root could not be registered");
      }
      identity = registry.registeredIdentity(root);
    }
    if (identity == null || !identity.matchesReceipt(debt.rootIdentity())) {
      throw new IOException("Reconciled watcher registration identity is unavailable");
    }
    registry.resumeAwaitingVolume(root);
    var subscription = registry.subscription(root);
    if (subscription == null || !registry.isCurrentAndActive(subscription)
        || !subscription.rootIdentity().matchesReceipt(debt.rootIdentity())
        || !Objects.equals(WatcherReplay.rootOwnerCollection(subscription.collection()),
            WatcherReplay.rootOwnerCollection(debt.collection()))) {
      throw new IOException("Reconciled watcher root is not observed by its declared owner");
    }
  }

  private void deferWatcherRootReconciliation(Path root, RootIdentity expectedRootIdentity) {
    try {
      var subscription = rootWatcherRegistry.subscription(root);
      RootIdentity identity = expectedRootIdentity != null ? expectedRootIdentity
          : subscription != null ? subscription.rootIdentity() : RootIdentity.capture(root);
      identity.requireCurrent(root);
      var debt = new WatcherRootReconciliation(root.toAbsolutePath().normalize().toString(),
          identity.toString(), subscription == null ? null : subscription.collection());
      if (!(jobQueue instanceof SwitchBufferCapableQueue journal)
          || !journal.putSwitchBuffer(debt.key(), WATCHER_RECONCILE, debt.encode())) {
        throw IngestSwitchBufferOps.switchBufferUnavailable();
      }
    } catch (IOException unavailable) {
      throw WorkerServiceException.unavailable("Watcher reconciliation root identity is unavailable");
    }
  }

  /**
   * The retiring watcher has stopped before this runs. Its registry still owns root removals,
   * collection attribution and identity proof, so replay does not re-register a removed root.
   * The exact obligation is retained on any failure, including a crash before cleanup.
   */
  public void reconcileReloadedWatcherRootsFrom(WorkerIngestService retiring) {
    RootWatcherRegistry ownRegistry = rootWatcherRegistry;
    // A failed earlier attempt may already have handed the roots on; borrow their current owner.
    setRootWatcherRegistry(retiring.rootWatcherRegistry.current());
    try {
      reconcilePendingWatcherRoots();
    } finally {
      setRootWatcherRegistry(ownRegistry);
    }
  }

  private boolean canApplyWatcherReceipts() {
    return ingestLifecycle != null && ingestLifecycle.isAcceptingWrites()
        && !switchBufferOps.isSwitching()
        && (!io.justsearch.configuration.PlatformPaths.isLinux()
            || (searchLifecycle == ingestLifecycle && switchBufferOps.migratingGeneration() == null));
  }

  public void reconcilePendingWatcherRoots() {
    try (var ignoredMutation = mutationLease()) {
      reconcilePendingWatcherRootsUnderLease();
    }
  }

  private void reconcilePendingWatcherRootsUnderLease() {
    if (!(jobQueue instanceof SwitchBufferCapableQueue journal) || !canApplyWatcherReceipts()) {
      return;
    }
    for (var receipt : journal.listSwitchBufferOpsStrict()) {
      if (!WATCHER_RECONCILE.equals(receipt.op())) continue;
      var debt = WatcherRootReconciliation.decode(receipt.payload());
      Path root = Path.of(debt.root());
      if (!receipt.key().equals(debt.key())
          || (receipt.generation() != null && !receipt.generation().isBlank())) {
        throw new IllegalArgumentException("Invalid watcher reconciliation receipt");
      }
      var subscription = rootWatcherRegistry.subscription(root);
      if (subscription == null) continue; // Startup waits for the declared root's registration.
      // An unavailable or replaced root keeps its receipt for its return; it never fails the
      // caller (a reload, a start or another root's watch), and nothing is applied to it.
      try (var ignoredEvent = rootWatcherRegistry.enterEvent(subscription)) {
        if (ignoredEvent == null) continue;
        if (!subscription.rootIdentity().matchesReceipt(debt.rootIdentity())) {
          log.warn("Watcher reconciliation for {} waits: its root is another folder now", root);
          continue;
        }
        SyncDirectoryResponse result = io.justsearch.configuration.PlatformPaths.isLinux()
            ? syncOps.execute(root.toString(), false, null, () -> false, subscription.collection(),
                SyncDirectoryOps.AdmissionMode.MAINTENANCE)
            : syncDirectory(SyncDirectoryRequest.newBuilder()
                .setRootPath(root.toString()).setForce(false).build(), CallContext.none());
        if (!result.getError().isBlank() || result.getSkipped()
            || result.getDeleteDetectionUnverified()) {
          subscription.rootIdentity().requireCurrent(root);
          throw WorkerServiceException.unavailable("Watcher reconciliation is incomplete");
        }
        subscription.rootIdentity().requireCurrent(root);
        ingestLifecycle.commitOps().commitAndTrack(CommitReason.WATCHER_DELETE);
        ingestLifecycle.commitOps().maybeRefreshBlocking();
        subscription.rootIdentity().requireCurrent(root);
        if (journal.removeReplayedSwitchBufferOps(List.of(receipt)) != 1) {
          throw WorkerServiceException.unavailable("Watcher reconciliation receipt changed");
        }
      } catch (IOException unavailable) {
        log.warn("Watcher reconciliation for {} waits for its root: {}", root,
            unavailable.getMessage());
      }
    }
  }

  private void replayReturnedWatcherDelete(Path returnedRoot, SwitchBufferCapableQueue journal,
      SwitchBufferCapableQueue.SwitchBufferOp receipt,
      io.justsearch.adapters.lucene.runtime.RunningRuntime active) {
    var deletion = io.justsearch.indexerworker.queue.SwitchBufferDelete.decode(receipt.payload());
    if (deletion.origin() != io.justsearch.indexerworker.queue.SwitchBufferDelete.Origin.WATCHER) return;
    Path root = Path.of(deletion.root());
    if (!root.equals(returnedRoot)) return;
    var subscription = rootWatcherRegistry.subscription(root);
    if (subscription == null) return;
    if (!("path:" + deletion.path()).equals(receipt.key())) {
      throw new IllegalArgumentException("Invalid watcher delete receipt key");
    }
    try (var event = rootWatcherRegistry.enterEvent(subscription)) {
      if (event == null || replayBufferedFileDelete(active, deletion) != BufferedDeleteReplay.SETTLED) return;
      int removed = receipt.generation() == null || receipt.generation().isBlank()
          ? journal.removeReplayedSwitchBufferOps(List.of(receipt))
          : journal.removeReplayedSwitchBufferOpsForGeneration(receipt.generation(), List.of(receipt));
      if (removed != 1) throw WorkerServiceException.unavailable("Watcher delete receipt changed");
    } catch (IOException unavailable) {
      log.debug("Watcher delete waits for its root: {}", root);
    }
  }

  /**
   * A closing reload's successor takes over every root this service watched or held for its
   * volume, so the reload leaves the watch list as it was; removed roots stay removed.
   */
  public void handWatchedRootsTo(WorkerIngestService successor) {
    try (var ignoredMutation = successor.mutationLease()) {
      rootWatcherRegistry.handOverTo(successor.rootWatcherRegistry, (registry, root, collection) ->
          successor.rootOnAnotherVolume(root.toString()) != null
              ? registry.watchWhenVolumeReturns(root, collection)
              : registry.watch(root.toString(), collection));
    }
  }

  /**
   * Confirmed absent: not found while the root (or the file's folder) is present and is the same
   * folder and volume it was.
   */
  private boolean confirmedAbsent(String normalizedPath, Path root) {
    Path path;
    try {
      path = Path.of(normalizedPath);
    } catch (InvalidPathException invalid) {
      return false;
    }
    return io.justsearch.indexerworker.loop.SourcePresence.classify(path, root, rootWitness)
        == io.justsearch.indexerworker.loop.SourcePresence.Verdict.ABSENT;
  }

  /**
   * The root-identity check this service's deletion decisions ask; the composition root hands it to
   * the indexing loop and the watcher so every deletion decision asks the same one (#758).
   */
  public io.justsearch.indexerworker.loop.SourcePresence.RootCheck rootCheck() {
    return rootWitness;
  }

  /**
   * The reason a walk, a sync or a watch of a root refused it because the folder at its path is
   * not on the volume recorded for it (#758). The Head records it as the
   * root's walk error, which shows the root-level "unavailable" state.
   */
  static final String ROOT_ON_ANOTHER_VOLUME = "ROOT_NOT_DIRECTORY";

  /**
   * Round 8 (refined decision 2): the reason a root awaiting its one-time upgrade seeding is
   * refused while the drive at its path is not confirmed as the one its indexed files are on. The
   * Head shows it verbatim as the root's unavailable reason. The Head keeps the same text as
   * {@code RootLifecycleOps.ROOT_UNCONFIRMED_REASON} (app-services): no module both sides use
   * holds the root reason codes, so a change here must be made there too (round 8b, decision 7).
   */
  public static final String ROOT_UNCONFIRMED_AFTER_UPGRADE =
      ROOT_ON_ANOTHER_VOLUME + ": drive not confirmed after upgrade";

  /** How many of a root's indexed files the upgrade seeding compares with the drive. */
  static final int UPGRADE_EVIDENCE_SAMPLE = 50;

  /**
   * The root at {@code rootPath}, normalised, when it is a real folder that is not on the volume
   * recorded for it, has none recorded, or whose volume cannot be told; {@code null} otherwise,
   * including for a path that is not valid, missing, or a link, which keep their own handling.
   */
  private Path rootOnAnotherVolume(String rootPath) {
    Path root = parsedRoot(rootPath);
    return root != null && rootWitness.refusesRoot(root) ? root : null;
  }

  /**
   * The root-level reason {@code root} is refused with: the upgrade reason while it awaits its
   * one-time seeding, otherwise {@code ROOT_NOT_DIRECTORY} followed by {@code detail}.
   */
  private String refusalReason(Path root, String detail) {
    return rootWitness.awaitsUpgradeSeeding(root)
        ? ROOT_UNCONFIRMED_AFTER_UPGRADE
        : ROOT_ON_ANOTHER_VOLUME + detail;
  }

  private static Path parsedRoot(String rootPath) {
    try {
      return Path.of(rootPath).toAbsolutePath().normalize();
    } catch (InvalidPathException invalid) {
      return null;
    }
  }

  /**
   * #758: whether the folder at {@code rootPath} is a real folder
   * that is not on the volume recorded for it, or whose volume cannot be told. One volume
   * identity read, no walk: the Head asks it of every watched root each periodic cycle, so a
   * root whose turn to be walked is far off still reads unavailable within one cycle. It
   * records nothing, except that a root awaiting its upgrade seeding is first checked on evidence.
   */
  public boolean refusesRootVolume(String rootPath, CallContext ctx) {
    return !rootVolumeRefusal(rootPath, ctx).isEmpty();
  }

  /**
   * {@link #refusesRootVolume} with the reason: empty when the root is not refused, else the
   * root-level reason, which is {@link #ROOT_UNCONFIRMED_AFTER_UPGRADE} for a root whose drive is
   * not confirmed after an upgrade. A root awaiting its upgrade seeding is checked on evidence
   * first, so the periodic cycle adopts a returning drive without any action (round 8).
   */
  public String rootVolumeRefusal(String rootPath, CallContext ctx) {
    Objects.requireNonNull(ctx, "ctx");
    Path root = parsedRoot(rootPath);
    if (root == null) {
      return "";
    }
    seedRootAfterUpgrade(root);
    return rootWitness.refusesRootQuietly(root) ? refusalReason(root, "") : "";
  }

  /**
   * Round 8 (refined decision 2): the one-time upgrade seeding. When neither the volume record nor
   * its pending file exists (an upgrade from a version without the record), the {@code declared}
   * roots are marked as awaiting seeding; then each root still awaiting it is checked on evidence.
   * A failure to start records nothing: every root stays unproven and nothing is deleted.
   */
  public void seedRootVolumesAfterUpgrade(java.util.Collection<Path> declared) {
    List<Path> roots = new ArrayList<>();
    for (Path root : declared) {
      roots.add(root.toAbsolutePath().normalize());
    }
    bootExclusionRoots = List.copyOf(roots);
    try {
      if (rootVolumes.beginUpgradeSeeding(roots)) {
        log.info("Upgrade: the volume of {} declared roots is confirmed on evidence", roots.size());
      }
    } catch (IOException notStarted) {
      log.warn("Upgrade seeding of root volumes could not start; roots stay unconfirmed ({})",
          notStarted.getMessage());
      return;
    }
    for (Path root : roots) {
      seedRootAfterUpgrade(root);
    }
  }

  /**
   * Round 8b (decision 3): a configured collection root (and the bundled help folder) is the
   * person's configuration, not a declared root, so no add ever records its volume. Each one with
   * nothing recorded at or above it is seeded once by the upgrade evidence rule; one whose drive is
   * not confirmed records nothing, reads unavailable with {@link #ROOT_UNCONFIRMED_AFTER_UPGRADE}
   * and keeps awaiting, so a later boot or migration adopts its returning drive. Nothing is
   * deleted either way. Runs after the declared roots' upgrade seeding has begun.
   */
  public void seedConfiguredRoots(java.util.Collection<Path> configured) {
    List<Path> roots = new ArrayList<>();
    for (Path root : configured) {
      if (root != null) {
        roots.add(root.toAbsolutePath().normalize());
      }
    }
    configuredRecoveryRoots = List.copyOf(roots);
    List<Path> awaiting;
    try {
      awaiting = rootVolumes.awaitSeedingUnlessRecorded(roots);
    } catch (IOException notStarted) {
      log.warn("Seeding of configured collection roots could not start; they stay unconfirmed ({})",
          notStarted.getMessage());
      return;
    }
    for (Path root : awaiting) {
      seedRootAfterUpgrade(root);
    }
  }

  private void seedRootAfterUpgrade(Path root) {
    if (rootWitness.awaitsUpgradeSeeding(root)) {
      rootWitness.seedAfterUpgrade(root, this::indexedFilesOnDrive);
    }
  }

  /**
   * The upgrade evidence rule: up to {@link #UPGRADE_EVIDENCE_SAMPLE} of the root's indexed files
   * are compared with the drive now at its path; at least half must exist there with the indexed
   * size and modification time. A root with no indexed documents holds. An index that cannot be
   * read does not.
   */
  private boolean indexedFilesOnDrive(Path root) {
    List<SyncDirectoryOps.IndexedFileStamp> sample;
    try {
      sample = candidateDiscoveryOps.sampleIndexedFiles(root, UPGRADE_EVIDENCE_SAMPLE);
    } catch (IOException cannotTell) {
      log.warn("The indexed files of {} cannot be read for the upgrade check ({})", root,
          cannotTell.getMessage());
      return false;
    }
    if (sample.isEmpty()) {
      return true;
    }
    int matching = 0;
    for (SyncDirectoryOps.IndexedFileStamp stamp : sample) {
      if (stamp.matchesDisk()) {
        matching++;
      }
    }
    log.info("Upgrade check of {}: {} of {} sampled indexed files are on the drive", root,
        matching, sample.size());
    return matching * 2 >= sample.size();
  }

  /**
   * Round 8 (S8): whether the folder at {@code rootPath} is a real folder on the volume recorded
   * for it. Unlike {@link #refusesRootVolume} it never records: a root with nothing recorded is
   * unproven and answers {@code false}. The deferred-root check asks this, so a recovery path
   * never adopts whatever volume is at a root's path.
   */
  public boolean rootOnRecordedVolume(String rootPath) {
    Path root;
    try {
      root = Path.of(rootPath).toAbsolutePath().normalize();
    } catch (InvalidPathException invalid) {
      return false;
    }
    return rootWitness.provenOnRecordedVolume(root);
  }

  /** Resume a checkpointed root using bounded admissions, retaining unrelated retry deadlines. */
  public SyncDirectoryResponse recoverRelease030Root(Path root, String collection,
      java.util.function.BooleanSupplier cancelled) {
    return recoverRelease030Root(root,collection,cancelled,null);
  }
  public SyncDirectoryResponse recoverRelease030Root(Path root,String collection,
      java.util.function.BooleanSupplier cancelled,SwitchBufferCapableQueue.SwitchBufferOp originalRoot) {
    try (var ignoredMutation = mutationLease()) {
      RootWatcherRegistry registry = rootWatcherRegistry;
      // Any removal after this authority decision must invalidate the ensuing sync.
      long mark = registry.removalMark();
      SyncDirectoryResponse refused = refuseSyncOfRootOnAnotherVolume(root.toString());
      if (refused != null) return refused;
      if (!rootWitness.admits(root) || !canApplyWatcherReceipts()) {
        return syncDirectoryErrorResponse("Upgrade recovery waits for its writable root");
      }
      var binding = rootWatcherRegistry.subscription(root);
      String retained = originalRoot != null && jobQueue instanceof SwitchBufferCapableQueue queue
          ? queue.retirementRootCollection(originalRoot) : null;
      if (originalRoot != null && !((SwitchBufferCapableQueue)jobQueue).retirementRootPolicyKnown(originalRoot))
        return syncDirectoryErrorResponse("ROOT creator namespace is unknown");
      boolean legacy = originalRoot != null && ((SwitchBufferCapableQueue)jobQueue).retirementRootLegacyExecution(originalRoot);
      var intent = originalRoot == null ? null : io.justsearch.indexerworker.queue.SwitchBufferSyncRoot.decode(originalRoot.payload());
      return syncOps.execute(root.toString(), intent == null || intent.force(), intent == null ? null : intent.provenance(), cancelled,
          originalRoot != null && !legacy ? retained : binding == null ? collection : binding.collection(), SyncDirectoryOps.AdmissionMode.UPGRADE_RECOVERY,
          admission -> registry.admitRecovery(root, mark,
              () -> exclusionRoots.get().stream().anyMatch(path -> path.toAbsolutePath().normalize()
                  .equals(root.toAbsolutePath().normalize())) || configuredRecoveryRoots.contains(root), admission),originalRoot);
    }
  }

  /** Rebuild membership under the ORIGINAL intent without executing later ROOT prunes early. */
  public SyncDirectoryResponse enumerateRetirementRoot(SwitchBufferCapableQueue.SwitchBufferOp original,
      String configuredCollection,boolean automatic,java.util.function.BooleanSupplier cancelled) {
    try(var ignoredMutation=mutationLease()) {
      Path root=Path.of(io.justsearch.indexerworker.queue.SwitchBufferSyncRoot.decode(original.payload()).rootPath()).toAbsolutePath().normalize();
      var refused=refuseSyncOfRootOnAnotherVolume(root.toString());if(refused!=null)return refused;
      if(!rootWitness.admits(root)||!canApplyWatcherReceipts())return syncDirectoryErrorResponse("ROOT membership waits for its source");
      String retained=jobQueue instanceof SwitchBufferCapableQueue queue?queue.retirementRootCollection(original):null;
      if (!(jobQueue instanceof SwitchBufferCapableQueue queue) || !queue.retirementRootPolicyKnown(original))
        return syncDirectoryErrorResponse("ROOT creator namespace is unknown");
      var binding = rootWatcherRegistry.subscription(root);
      String collection=queue.retirementRootLegacyExecution(original) ? binding == null ? null : binding.collection() : retained;
      long mark=rootWatcherRegistry.removalMark();
      return syncOps.enumerateRetirementRoot(root.toString(),collection,cancelled,automatic,
          admission->automatic?rootWatcherRegistry.admitRecovery(root,mark,
              ()->exclusionRoots.get().stream().anyMatch(path->path.toAbsolutePath().normalize().equals(root))
                  ||configuredRecoveryRoots.contains(root),admission):admission.getAsInt(),original);
    }
  }

  /**
   * Round 8 (S8): the current count of root removals (unwatches); {@link #rootRemovedSince} tells
   * whether a root was removed after a count was taken.
   */
  public long rootRemovalMark() {
    return rootWatcherRegistry.removalMark();
  }

  /** Whether the person removed {@code root} (unwatched it) after {@code mark} was taken. */
  public boolean rootRemovedSince(Path root, long mark) {
    return rootWatcherRegistry.removedSince(root, mark);
  }

  /**
   * Round 5 (S8): a sync of a root whose path now holds another volume adds nothing, updates
   * nothing and removes nothing; it answers with the root-level reason the Head shows at once.
   * A sync that finds the recorded volume back registers the root's held watch first, so changes
   * from then on are seen, and then reconciles as usual.
   */
  private SyncDirectoryResponse refuseSyncOfRootOnAnotherVolume(String rootPath) {
    Path parsed = parsedRoot(rootPath);
    if (parsed != null) {
      // Round 8 (refined decision 2): the periodic sync retries a root's upgrade seeding.
      seedRootAfterUpgrade(parsed);
    }
    Path refused = rootOnAnotherVolume(rootPath);
    if (refused != null) {
      return syncDirectoryErrorResponse(refusalReason(refused,
          ": the folder at this path is not on its recorded volume, or has none recorded"));
    }
    try {
      resumeWatchAwaitingVolume(Path.of(rootPath).toAbsolutePath().normalize());
    } catch (InvalidPathException invalid) {
      // Nothing is held under a path that is not valid; the sync reports it below.
    }
    return null;
  }

  /** Registers a watch held for {@code root}'s volume, if any, now that the volume is back. */
  private void resumeWatchAwaitingVolume(Path root) {
    if (!rootWatcherRegistry.awaitsVolume(root)) {
      return;
    }
    markReplayUncertainForWatchChangeDuringMigration();
    try {
      rootWatcherRegistry.resumeAwaitingVolume(root);
    } finally {
      markReplayUncertainForWatchChangeDuringMigration();
    }
  }

  /** Linux return detection is independent of the Head's one-root-per-minute scan queue. */
  public Set<Path> watcherRecoveryRoots() {
    return rootWatcherRegistry.recoveryRoots();
  }

  /** Called through the current writer's admission gate, so a transition keeps this work pending. */
  public void recoverWatcherRoot(Path root) throws IOException {
    try (var ignoredMutation = mutationLease()) {
      if (!canApplyWatcherReceipts() || !rootWitness.provenOnRecordedVolume(root)) return;
      RootWatcherRegistry registry = rootWatcherRegistry;
      if (!registry.recoveryRoots().contains(root)) return;
      resumeWatchAwaitingVolume(root);
      // A dead published watcher is recovered directly; a held watch follows the same proof.
      if (!registry.awaitsVolume(root)) registry.resumeAwaitingVolume(root);
      var subscription = registry.subscription(root);
      if (subscription == null || !registry.isCurrentAndActive(subscription)) return;
      try (var event = registry.enterEvent(subscription)) {
        if (event == null) return;
        // Re-enumerate all present files, as startup does. The loop still skips unchanged stamps.
        var result = syncOps.execute(root.toString(), true, null, () -> false,
            subscription.collection(), SyncDirectoryOps.AdmissionMode.MAINTENANCE);
        if (!result.getError().isBlank() || result.getSkipped()
            || result.getDeleteDetectionUnverified()) return;
      }
      if (jobQueue instanceof SwitchBufferCapableQueue journal) {
        String activeGeneration = activeGenerationSupplier().get();
        for (var receipt : journal.listSwitchBufferOpsStrict()) {
          if ("DELETE".equals(receipt.op())
              && (receipt.generation() == null || receipt.generation().isBlank()
                  || receipt.generation().equals(activeGeneration))) {
            replayReturnedWatcherDelete(root, journal, receipt, ingestLifecycle);
          }
        }
      }
      reconcilePendingWatcherRoots();
      if (registry.isCurrentAndActive(subscription)) registry.recoveryCompleted(subscription);
    }
  }

  private static void requireWatcherRootCurrent(
      RootWatcherRegistry.Subscription witness) {
    requireCandidateRootIdentity(witness.root(), witness.rootIdentity());
  }

  private void journalWatcherDelete(String path, Path root, RootIdentity expectedRootIdentity) {
    try {
      RootWatcherRegistry.Subscription subscription = rootWatcherRegistry.subscription(root);
      RootIdentity identity = expectedRootIdentity != null ? expectedRootIdentity
          : subscription != null ? subscription.rootIdentity() : RootIdentity.capture(root);
      identity.requireCurrent(root);
      switchBufferOps.journalWatcherDelete(path, root, identity.toString(),
          subscription == null ? null : subscription.collection());
    } catch (IOException unavailable) {
      throw WorkerServiceException.unavailable("Watcher delete root identity is unavailable");
    }
  }

  private void journalAndProjectWatcherDeleteToServing(String path, Path root,
      RootIdentity expectedRootIdentity) {
    if (switchBufferOps.buildingGenerationForFileAdmission() == null) return;
    if (!(searchLifecycle instanceof io.justsearch.adapters.lucene.runtime.RunningRuntime active)
        || !active.isAcceptingWrites()) {
      throw WorkerServiceException.unavailable("Serving projection is unavailable during migration");
    }
    journalWatcherDelete(path, root, expectedRootIdentity);
    if (active != ingestLifecycle) {
      active.indexingCoordinator().deleteByIdAndChunks(path);
      active.commitOps().commitAndTrack(CommitReason.WATCHER_DELETE);
    }
  }

  private void journalAndProjectDeleteToServing(String normalizedId, CommitReason reason) {
    String candidateGeneration = switchBufferOps.buildingGenerationForFileAdmission();
    if (candidateGeneration == null) return;
    if (!(searchLifecycle instanceof io.justsearch.adapters.lucene.runtime.RunningRuntime active)
        || !active.isAcceptingWrites()) {
      throw WorkerServiceException.unavailable(
          "Serving projection is unavailable during migration; retry shortly");
    }
    switchBufferOps.journalDeleteForGeneration(candidateGeneration, normalizedId);
    // Before Green opens, the caller's direct effect still owns A. Its mutation
    // lease prevents handoff until that one effect finishes; B already has its journal row.
    if (active == ingestLifecycle) return;
    active.indexingCoordinator().deleteByIdAndChunks(normalizedId);
    active.commitOps().commitAndTrack(reason);
  }

  private void journalAndProjectPrefixDeleteToServing(PathPrefixScope scope) {
    String candidateGeneration = switchBufferOps.buildingGenerationForFileAdmission();
    if (!(searchLifecycle instanceof io.justsearch.adapters.lucene.runtime.RunningRuntime active)
        || !active.isAcceptingWrites()) {
      throw WorkerServiceException.unavailable(
          "Removal did not complete: the search index is read-only or unavailable. "
              + "Wait for index recovery, then retry Remove.");
    }
    if (candidateGeneration != null) {
      switchBufferOps.journalDeletePrefixForGeneration(candidateGeneration, scope);
    }
    if (active == ingestLifecycle) return;
    deletePrefix(active.indexingCoordinator(), scope);
    active.commitOps().commitAndTrack(CommitReason.GRPC_DELETE_BY_PATH);
  }

  private Integer journalAndProjectCollectionDeleteToServing(String collection) {
    String candidateGeneration = switchBufferOps.buildingGenerationForFileAdmission();
    if (candidateGeneration == null) return null;
    if (!(searchLifecycle instanceof io.justsearch.adapters.lucene.runtime.RunningRuntime active)
        || !active.isAcceptingWrites()) {
      throw WorkerServiceException.unavailable(
          "Serving projection is unavailable during migration; retry shortly");
    }
    switchBufferOps.journalDeleteCollectionForGeneration(candidateGeneration, collection);
    if (active == ingestLifecycle) return null;
    int visibleDeleted = active.indexingCoordinator().deleteByCollection(collection);
    active.commitOps().commitAndTrack(CommitReason.GRPC_DELETE_BY_COLLECTION);
    return visibleDeleted;
  }

  public UpgradeQuiescenceResponse prepareUpgrade(
      UpgradeQuiescenceRequest request, CallContext ctx) {
    return respondUpgrade(
        () -> upgradeQuiescence.prepare(request == null ? "" : request.getPreparationId()));
  }

  public UpgradeQuiescenceResponse upgradeStatus(
      UpgradeQuiescenceRequest request, CallContext ctx) {
    return respondUpgrade(
        () -> upgradeQuiescence.status(request == null ? "" : request.getPreparationId()));
  }

  public UpgradeQuiescenceResponse cancelUpgrade(
      UpgradeQuiescenceRequest request, CallContext ctx) {
    return respondUpgrade(
        () -> upgradeQuiescence.cancel(request == null ? "" : request.getPreparationId()));
  }

  private static UpgradeQuiescenceResponse respondUpgrade(
      Supplier<UpgradeQuiescenceResponse> action) {
    try {
      return action.get();
    } catch (RuntimeException e) {
      throw WorkerServiceException.failedPrecondition(e.getMessage());
    }
  }

  /**
   * Tempdoc 400 §22 Issue D / LR2-e.4 (Phase 6 / 6.7): expose a best-
   * effort supplier for the active Lucene IndexSearcher generation so
   * {@link SearchOrchestrator} can stamp {@code search.searcher_generation}
   * on every {@code search/retrieval} span. Null-safe — returns null
   * when the generation manager is unwired (tests, early-boot).
   */
  public Supplier<String> activeGenerationSupplier() {
    return () -> {
      if (indexGenerationManager == null) {
        return null;
      }
      IndexGenerationManager.State state = indexGenerationManager.readStateBestEffort();
      return state == null ? null : state.active_generation();
    };
  }

  /**
   * Sets the embedding compatibility controller for status reporting.
   *
   * @param controller the embedding compatibility controller
   */
  public void setEmbeddingCompatController(
      io.justsearch.indexerworker.embed.EmbeddingCompatibilityController controller) {
    statusOps.setEmbeddingCompatController(controller);
  }

  /**
   * F1: Sets the ORT CUDA status supplier for observability.
   *
   * @param supplier supplier that returns the current ORT CUDA status
   */
  public void setOrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    statusOps.setOrtCudaStatusSupplier(supplier);
  }

  /** Sets the SPLADE encoder ORT CUDA status supplier. */
  public void setSpladeOrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    statusOps.setSpladeOrtCudaStatusSupplier(supplier);
  }

  /** Sets the embedding encoder ORT CUDA status supplier. */
  public void setEmbedOrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    statusOps.setEmbedOrtCudaStatusSupplier(supplier);
  }

  /** Tempdoc 422 — sets the NER encoder ORT CUDA status supplier. */
  public void setNerOrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    statusOps.setNerOrtCudaStatusSupplier(supplier);
  }

  /** Tempdoc 422 — sets the citation-scorer encoder ORT CUDA status supplier. */
  public void setCitationOrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    statusOps.setCitationOrtCudaStatusSupplier(supplier);
  }

  /** Tempdoc 422 — sets the BGE-M3 encoder ORT CUDA status supplier. */
  public void setBgeM3OrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    statusOps.setBgeM3OrtCudaStatusSupplier(supplier);
  }

  /** Sets the embedding backend ID supplier (e.g. "onnx"). */
  public void setEmbedBackendSupplier(Supplier<String> supplier) {
    statusOps.setEmbedBackendSupplier(supplier);
  }

  /** Tempdoc 406 — wires the swap-aware runtime gauges supplier for /api/status. */
  public void setRuntimeGaugesSupplier(
      Supplier<io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeGaugesSnapshot>
          supplier) {
    statusOps.setRuntimeGaugesSupplier(supplier);
  }

  /**
   * Tempdoc 419 C3 V1 — wires the worker's RRD store so {@code IndexStatusOps.buildCore} can
   * backfill the recent-job-queue-depth trend.
   */
  public void setRrdStoreSupplier(Supplier<io.justsearch.telemetry.RrdMetricStore> supplier) {
    statusOps.setRrdStoreSupplier(supplier);
  }

  /**
   * Tempdoc 406 — wires the runtime reload trigger. Invoked by the {@code ReloadRuntime}
   * RPC; returns swap duration in ms. {@code null} (default) means reload is unavailable
   * and the RPC will return {@code FAILED_PRECONDITION}.
   */
  private volatile java.util.function.Function<SwapReason, Long> runtimeReloadTrigger;

  public void setRuntimeReloadTrigger(java.util.function.Function<SwapReason, Long> trigger) {
    this.runtimeReloadTrigger = trigger;
  }

  /**
   * Sets which enrichment stages are enabled. Called once at wire time from
   * {@code DefaultWorkerAppServices}. Flows through to the status endpoint so
   * consumers (e.g. jseval readiness polling) can skip coverage checks for
   * disabled stages. See tempdoc 394 / commit {@code c4e677f63}.
   */
  public void setStageEnabled(boolean embedding, boolean splade, boolean ner) {
    statusOps.setStageEnabled(embedding, splade, ner);
  }

  /** Sets the embedding GPU layers supplier. */
  public void setEmbedGpuLayersSupplier(Supplier<Integer> supplier) {
    statusOps.setEmbedGpuLayersSupplier(supplier);
  }

  /** Sets the SPLADE model path supplier. */
  public void setSpladeModelPathSupplier(Supplier<String> supplier) {
    statusOps.setSpladeModelPathSupplier(supplier);
  }

  /** Sets the reranker model path supplier. */
  public void setRerankerModelPathSupplier(Supplier<String> supplier) {
    statusOps.setRerankerModelPathSupplier(supplier);
  }

  /** Sets the NER model path supplier. */
  public void setNerModelPathSupplier(Supplier<String> supplier) {
    statusOps.setNerModelPathSupplier(supplier);
  }

  /** Sets the NER GPU enabled supplier. */
  public void setNerGpuEnabledSupplier(Supplier<Boolean> supplier) {
    statusOps.setNerGpuEnabledSupplier(supplier);
  }

  /**
   * Sets the {@link io.justsearch.ort.PolicySnapshot} supplier for the
   * {@code GetSessionPolicies} rpc (tempdoc 397 §14.28 U4). Returns Worker's authoritative
   * snapshot; Head's re-resolve path in {@code SessionPoliciesController} is deleted in favour
   * of reading this through the index port.
   */
  public void setPolicySnapshotSupplier(Supplier<io.justsearch.ort.PolicySnapshot> supplier) {
    this.policySnapshotSupplier = supplier;
  }

  private volatile Supplier<io.justsearch.ort.PolicySnapshot> policySnapshotSupplier;

  /** Sets the resolved config supplier for search config status reporting (343). */
  public void setResolvedConfigSupplier(
      Supplier<io.justsearch.configuration.resolved.ResolvedConfig> supplier) {
    statusOps.setResolvedConfigSupplier(supplier);
    this.resolvedConfigSupplier = supplier;
  }

  public void setExpectedCommitMetadataSupplier(Supplier<Map<String, Object>> supplier) {
    statusOps.setExpectedCommitMetadataSupplier(supplier);
  }

  /**
   * What the Worker's boot held back by the repeat-rebuild brake, or {@code null} when nothing
   * (#758). The status reports it for the rest of the boot.
   */
  public void setBootRebuildHoldSupplier(
      Supplier<IndexGenerationManager.AutoRebuildBrake> supplier) {
    statusOps.setBootRebuildHoldSupplier(supplier);
  }

  /** Physical target metadata from the Worker that owns the configured model files. */
  public void setIndexTargetMetadataSupplier(Supplier<Map<String, Object>> supplier) {
    indexTargetMetadataSupplier = Objects.requireNonNull(supplier, "supplier");
  }

  private volatile Supplier<Map<String, Object>> indexTargetMetadataSupplier;

  // Tempdoc 931 §E item 8: kept here too (not only forwarded to statusOps) so the VDU chunk
  // regeneration path can read rag.chunk_splade.enabled from the LIVE config on every write.
  private volatile Supplier<io.justsearch.configuration.resolved.ResolvedConfig>
      resolvedConfigSupplier;

  /** {@code rag.chunk_splade.enabled}; absent config reads as the flag's own default (false). */
  private boolean chunkSpladeEnabled() {
    Supplier<io.justsearch.configuration.resolved.ResolvedConfig> supplier = resolvedConfigSupplier;
    io.justsearch.configuration.resolved.ResolvedConfig config =
        supplier == null ? null : supplier.get();
    return config != null && config.rag() != null && config.rag().chunkSpladeEnabled();
  }

  /**
   * The "index runtime not available" response for {@code context}, or {@code null} when the
   * runtime IS available and the caller should carry on.
   */
  private <T> T indexRuntimeUnavailableReply(String context, T unavailableResponse) {
    if (ingestLifecycle != null) {
      return null;
    }
    log.error("{} failed: ingestLifecycle is null", context);
    return unavailableResponse;
  }

  /** The validation response when {@code value} is blank, else {@code null}. */
  private static <T> T blankReply(String value, T validationResponse) {
    if (value != null && !value.isBlank()) {
      return null;
    }
    return validationResponse;
  }

  public MigrationStartResponse startMigration(MigrationStartRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      return migrationOps.startMigration(request);
    }
  }

  public MigrationCutoverResponse requestCutover(
      MigrationCutoverRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      return migrationOps.requestCutover(request);
    }
  }

  public MigrationPauseResponse pauseMigration(MigrationPauseRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      return migrationOps.pauseMigration(request);
    }
  }

  public MigrationResumeResponse resumeMigration(MigrationResumeRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      return migrationOps.resumeMigration(request);
    }
  }

  public MigrationRollbackResponse rollbackMigration(
      MigrationRollbackRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      return migrationOps.rollbackMigration(request);
    }
  }

  public IndexGcResponse runIndexGc(IndexGcRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      return migrationOps.runIndexGc(request);
    }
  }

  public SettleIndexResponse settleIndex(SettleIndexRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      return settleOps.settleIndex(request);
    }
  }

  private volatile java.util.Collection<Path> bootExclusionRoots = List.of();
  private volatile java.util.Collection<Path> configuredRecoveryRoots = List.of();
  private volatile Supplier<java.util.Collection<Path>> exclusionRoots = () -> bootExclusionRoots;

  /**
   * The composing owner binds its current file policy and Head-owned watched membership before
   * publication. Construction alone must not bind: temporary replay services share the runtime
   * but never own its processing policy.
   */
  public void useExclusionRoots(Supplier<java.util.Collection<Path>> roots) {
    exclusionRoots = Objects.requireNonNull(roots, "roots");
    if (ingestLifecycle != null) {
      ingestLifecycle.useSourceProcessingPolicy(this::sourceProcessingAllowed);
    }
  }

  /** Current user policy, including saved roots not yet registered with a native watcher. */
  public boolean isUserExcluded(Path file, Path root) {
    var patterns = io.justsearch.configuration.excludes.ExclusionPatterns.current();
    if (patterns.isEmpty()) return false;
    if (patterns.isExcludedPath(root, file) || rootWatcherRegistry.isUserExcluded(file)) return true;
    for (Path watched : exclusionRoots.get()) {
      if (patterns.isExcludedPath(watched, file)) return true;
    }
    return false;
  }

  public static final String RETIREMENT_EXCLUDED_INDEXED =
      "0.3.0 retirement waits on excluded paths still indexed";

  /** Retirement must keep old content when the live policy cannot be read or parsed. */
  public boolean isUserExcludedForRetirement(Path file, Path root) {
    var store = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    if (store == null) throw new IllegalStateException("Retirement exclusion policy is unavailable");
    var snapshot = store.currentSnapshot();
    var patterns = io.justsearch.configuration.excludes.ExclusionPatterns.fromRawJsonStrict(
        snapshot.ui().excludePatterns(), io.justsearch.configuration.PlatformPaths.isWindows());
    if (!patterns.invalidPatterns().isEmpty())
      throw new IllegalStateException("Retirement exclusion policy is invalid");
    boolean excluded = isUserExcluded(file, root);
    if (store != io.justsearch.configuration.resolved.ConfigStore.globalOrNull()
        || snapshot != store.currentSnapshot())
      throw new IllegalStateException("Retirement exclusion policy changed during observation");
    return excluded;
  }

  private boolean sourceProcessingAllowed(String source) {
    if (source == null || source.isBlank()) return true;
    try {
      Path file = Path.of(source);
      // Projection and non-file document IDs have no filesystem exclusion scope.
      return !file.isAbsolute() || !isUserExcluded(file, file);
    } catch (InvalidPathException nonFile) {
      return true;
    }
  }

  /** Trusted index/control metadata, never the result hit's collection label or a disk stat. */
  public void validateForgetTarget(Path target,
      io.justsearch.configuration.excludes.LiteralExclusion.Scope scope) {
    var supplier = resolvedConfigSupplier;
    var current = supplier == null ? null : supplier.get();
    if (current == null || ingestLifecycle == null) {
      throw WorkerServiceException.unavailable("The indexed target cannot be verified");
    }
    Path path = target.toAbsolutePath().normalize();
    var internal = new ArrayList<Path>();
    internal.add(current.paths().dataDir().resolve("agent-history").toAbsolutePath().normalize());
    var help = current.collections().bundledHelp();
    if (help != null) internal.add(help.path().toAbsolutePath().normalize());
    for (var collection : current.collections().items()) {
      if (io.justsearch.configuration.InternalCollections.isReserved(collection.name())) {
        for (Path protectedRoot : collection.roots()) internal.add(protectedRoot.toAbsolutePath().normalize());
      }
    }
    for (Path protectedRoot : internal) {
      if (path.startsWith(protectedRoot)
          || scope == io.justsearch.configuration.excludes.LiteralExclusion.Scope.FOLDER
              && protectedRoot.startsWith(path)) {
        throw WorkerServiceException.invalidArgument("Help and agent-history content is managed by its own data controls");
      }
    }
    String key = PathNormalizer.normalizeKey(path);
    var membership = new org.apache.lucene.search.BooleanQuery.Builder();
    if (scope == io.justsearch.configuration.excludes.LiteralExclusion.Scope.FILE) {
      membership.add(new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term(
          SchemaFields.DOC_ID, key)), org.apache.lucene.search.BooleanClause.Occur.SHOULD);
      membership.add(new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term(
          SchemaFields.PARENT_DOC_ID, key)), org.apache.lucene.search.BooleanClause.Occur.SHOULD);
    } else {
      membership.add(new org.apache.lucene.search.PrefixQuery(new org.apache.lucene.index.Term(
          SchemaFields.PATH,
          io.justsearch.adapters.lucene.runtime.QueryFilterBuilder.normalizePathPrefix(key))),
          org.apache.lucene.search.BooleanClause.Occur.SHOULD);
      membership.add(new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term(
          SchemaFields.PATH, key)), org.apache.lucene.search.BooleanClause.Occur.SHOULD);
    }
    var reserved = new org.apache.lucene.search.BooleanQuery.Builder();
    for (String collection : io.justsearch.configuration.InternalCollections.RESERVED) {
      reserved.add(new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term(
          SchemaFields.COLLECTION, collection)), org.apache.lucene.search.BooleanClause.Occur.SHOULD);
    }
    try {
      ingestLifecycle.commitOps().maybeRefreshBlocking();
      int protectedRows = scope == io.justsearch.configuration.excludes.LiteralExclusion.Scope.FOLDER
          ? ingestLifecycle.indexCountOps().countOwnedPathPrefixOrThrow(key, reserved.build())
          : ingestLifecycle.indexCountOps().countQueryOrThrow(new org.apache.lucene.search.BooleanQuery.Builder()
              .add(membership.build(), org.apache.lucene.search.BooleanClause.Occur.FILTER)
              .add(reserved.build(), org.apache.lucene.search.BooleanClause.Occur.FILTER).build());
      if (protectedRows > 0) {
        throw WorkerServiceException.invalidArgument("Help and agent-history content cannot be forgotten here");
      }
    } catch (IOException unreadable) {
      throw WorkerServiceException.unavailable("The indexed target's ownership could not be verified");
    }
  }

  public boolean ownsForgetQueue(JobQueue expected) { return expected != null && jobQueue == expected; }

  /** Must be checked before entering Head roots or server physical ownership. */
  public boolean forgetEntryAllowed() {
    return jobQueue.forgetEntryAllowed() && indexingLoop != null
        && !indexingLoop.fileMutationFence().isHeldByCurrentThread();
  }

  /** TRY both inner owners: never wait behind a writer/callback while holding runtime/config. */
  public java.util.Optional<ForgetMutation> tryBeginForgetMutation() {
    if (!forgetEntryAllowed() || mutationAdmission == null
        || ingestLifecycle != searchLifecycle
        || !(ingestLifecycle instanceof io.justsearch.adapters.lucene.runtime.RunningRuntime runtime)
        || !runtime.isAcceptingWrites()) return java.util.Optional.empty();
    var file = indexingLoop.fileMutationFence();
    if (!file.tryLock()) return java.util.Optional.empty();
    boolean retained = false;
    try {
      var queue = jobQueue.tryForgetQueueFence();
      if (queue.isEmpty()) return java.util.Optional.empty();
      retained = true;
      return java.util.Optional.of(new ForgetMutation(file, queue.orElseThrow(), runtime));
    } finally { if (!retained) file.unlock(); }
  }

  public final class ForgetMutation {
    private final io.justsearch.indexerworker.loop.FileMutationFence file;
    private final JobQueue.ForgetQueueFence queue;
    private final io.justsearch.adapters.lucene.runtime.RunningRuntime runtime;
    private final Thread holder = Thread.currentThread();
    private boolean fileReleased;
    private boolean queueReleased;
    private ForgetMutation(io.justsearch.indexerworker.loop.FileMutationFence file,
        JobQueue.ForgetQueueFence queue, io.justsearch.adapters.lucene.runtime.RunningRuntime runtime) {
      this.file = file; this.queue = queue; this.runtime = runtime;
    }

    /** Existing exact/prefix delete/claim invalidation, then strict queue/index absence proof. */
    public boolean removeAndVerify(io.justsearch.app.api.operations.ForgetPlan plan) {
      requireHolder();
      if (fileReleased || queueReleased) throw new IllegalStateException("Forget mutation owners are released");
      String key = PathNormalizer.normalizeKey(Path.of(plan.path()));
      boolean folder = plan.scope() == io.justsearch.configuration.excludes.LiteralExclusion.Scope.FOLDER;
      // A FOLDER literal includes equality. A former file at this path can still own parent,
      // chunk and queue rows after the physical path has become a directory.
      runtime.indexingCoordinator().deleteByIdAndChunks(key);
      if (folder) runtime.indexingCoordinator().deleteByPathPrefix(key);
      if (jobQueue.deleteByExactPath(key) < 0
          || folder && jobQueue.deleteByPathPrefix(key) < 0) {
        throw new IllegalStateException("Forget queue removal did not complete");
      }
      runtime.commitOps().commitAndTrack(folder ? CommitReason.GRPC_DELETE_BY_PATH : CommitReason.GRPC_DELETE_BY_ID);
      runtime.commitOps().maybeRefreshBlocking();
      try {
        return runtime.indexCountOps().countByIdAndChunksStrict(key) == 0
            && (!folder || runtime.indexCountOps().countPathPrefixExcludingAcceptedSurvivorsStrict(
                key, List.of(), List.of()) == 0)
            && jobQueue.forgetJobsAbsent(key, folder);
      } catch (IOException unknown) {
        throw new java.io.UncheckedIOException("Forget index absence could not be verified", unknown);
      }
    }

    /** Release file before unwinding configuration/generation/runtime/root; retain queue throughout. */
    public void releaseFile() {
      requireHolder();
      if (fileReleased) return;
      fileReleased = true;
      file.unlock();
    }

    /** Guaranteed caller finally AFTER all outer owners. Existing queue deliveries run here. */
    public void afterOwnersReleased() {
      requireHolder();
      if (!fileReleased) throw new IllegalStateException("Release the file and outer owners before queue delivery");
      if (queueReleased) return;
      queueReleased = true;
      queue.close();
    }

    private void requireHolder() {
      if (Thread.currentThread() != holder) throw new IllegalStateException("Forget mutation is synchronous and thread-owned");
    }
  }

  public BatchResponse submitBatch(BatchRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      List<String> filePaths = request.getFilePathsList();
      String collection = request.getTargetCollection().isBlank() ? null : request.getTargetCollection();
      Object collectionAtStart = collectionRemoval(collection);
      var removalsAtStart = collection == null ? Map.copyOf(collectionRemovals) : Map.of();

      // Validate: empty batch
      if (filePaths.isEmpty()) {
        log.debug("Received empty batch request");
        return batchSuccessResponse(0);
      }

      // Rate limit: batch size
      if (filePaths.size() > MAX_BATCH_SIZE) {
        metrics.recordBatchRejected();
        log.warn("Batch too large: {} files (max {})", filePaths.size(), MAX_BATCH_SIZE);
        throw WorkerServiceException.invalidArgument(
            "Batch size " + filePaths.size() + " exceeds maximum " + MAX_BATCH_SIZE);
      }

      // Backpressure: check queue depth
      long currentDepth = jobQueue.queueDepth();
      if (currentDepth >= MAX_QUEUE_DEPTH) {
        metrics.recordBatchRejected();
        log.warn("Queue full: depth {} (max {})", currentDepth, MAX_QUEUE_DEPTH);
        throw WorkerServiceException.resourceExhausted(
            "Queue depth " + currentDepth + " exceeds maximum " + MAX_QUEUE_DEPTH);
      }

      log.debug("Received batch request with {} files", filePaths.size());

      try {
        // Sanitize and validate paths
        List<Path> validPaths = new ArrayList<>(filePaths.size());
        int rejected = 0;

        for (String pathStr : filePaths) {
          Path sanitized = sanitizePath(pathStr);
          if (sanitized == null) {
            rejected++;
          } else if (!isUserExcluded(sanitized, sanitized)) {
            validPaths.add(sanitized);
          }
        }

        if (rejected > 0) {
          log.warn("Rejected {} invalid/unsafe paths", rejected);
        }

        if (validPaths.isEmpty()) {
          return rejected == filePaths.size()
              ? batchErrorResponse("All paths were invalid or unsafe") : batchSuccessResponse(0);
        }
        if (request.getRootAdded()) {
          // Round 8b (decision 1): an explicit ingest request is a deliberate add and records the
          // volume of each file's folder; a batch without the marker records nothing.
          validPaths.stream().map(Path::getParent).filter(Objects::nonNull).distinct()
              .forEach(rootWitness::recordExplicitFileFolder);
        }

        // Force bypasses unchanged extraction for selected files. It cannot attest to untouched
        // vectors: whole-index legacy recovery or generation migration owns compatibility.

        // The candidate queue remains the producer through SWITCHING until the final fence.
        // Admit its job and exact source witness together; the loop projects A before ACK.
        if (switchBufferOps.isSwitching()
            && !(jobQueue instanceof SwitchBufferCapableQueue)) {
          throw IngestSwitchBufferOps.switchingUnavailable();
        }

        // 813 Slice B: stat each admitted path for its byte size. A stat failure degrades that
        // entry to unknown size (NULL) — it never rejects the enqueue.
        var entries = validPaths.stream()
            .map(path -> JobQueue.EnqueueEntry.stat(path, ctx.provenance()).withinRoot(path)).toList();
        String candidateGeneration = switchBufferOps.buildingGenerationForFileAdmission();
        int accepted = withFileMutationFence(() -> {
          if (ctx.cancelled() || collectionAtStart != collectionRemoval(collection)
              || collection == null && !removalsAtStart.equals(collectionRemovals)) return 0;
          return candidateGeneration == null
              ? jobQueue.enqueueEntries(entries, collection)
              : jobQueue instanceof SwitchBufferCapableQueue sbq
                  ? sbq.enqueueAndBufferFilesForGeneration(
                      candidateGeneration, entries, collection, null) : 0;
        });
        if (candidateGeneration != null && accepted != entries.size()) {
          throw IngestSwitchBufferOps.switchBufferUnavailable();
        }

        // Mark paths for force reindex if requested (bypasses "unchanged" check).
        // The key must be the one JobBatchExtractor looks the forced set up by — the envelope's —
        // so it is derived through the shared PathNormalizer#normalizeKey rather than re-spelled
        // here. sanitizePath currently keeps a `..` or relative shape from ever reaching this line,
        // which is what made the old hand-rolled derivation agree by accident; that agreement is now
        // structural and survives a change to either side (tempdoc 821 §P/P3).
        if (request.getForceReindex() && accepted > 0) {
          List<String> normalizedPaths =
              validPaths.stream().map(PathNormalizer::normalizeKey).toList();
          forcedPathSink.accept(normalizedPaths);
          log.info("Marked {} paths for force reindex", normalizedPaths.size());
        }

        // Record metrics
        metrics.recordBatchSubmitted(accepted);
        metrics.setQueueDepth(jobQueue.queueDepth());

        log.info("Accepted {} of {} files for indexing (rejected {})",
            accepted, filePaths.size(), rejected);

        return batchSuccessResponse(accepted);

      } catch (WorkerServiceException e) {
        // Must precede the IllegalStateException / RuntimeException catches below: the SWITCHING
        // branch above throws UNAVAILABLE ("Switch buffer write failed..." / "Migration is
        // switching...") from inside this try, and the catch-all would silently degrade it to
        // INTERNAL "Batch processing failed: ..." — a different wire status AND a different
        // message for the Head's retry logic.
        throw e;
      } catch (IllegalStateException e) {
        log.error("Queue error", e);
        throw WorkerServiceException.internal("Queue error: " + e.getMessage());
      } catch (InvalidPathException e) {
        log.error("Invalid path", e);
        throw WorkerServiceException.invalidArgument("Invalid path: " + e.getMessage());
      } catch (RuntimeException e) {
        log.error("Failed to process batch request", e);
        throw WorkerServiceException.internal("Batch processing failed: " + e.getMessage());
      }
    }
  }

  /**
   * Sanitizes and validates a file path.
   *
   * <p>Security checks:
   * <ul>
   *   <li>Rejects null/blank paths</li>
   *   <li>Rejects paths containing ".." (traversal attempts)</li>
   *   <li>Normalizes the path to resolve any symbolic links</li>
   *   <li>Requires the path to be absolute</li>
   *   <li>Requires the file to exist (prevents indexing non-existent files)</li>
   * </ul>
   *
   * @param pathStr the raw path string from the client
   * @return sanitized Path, or null if invalid/unsafe
   */
  private Path sanitizePath(String pathStr) {
    if (pathStr == null || pathStr.isBlank()) {
      return null;
    }

    // Check for traversal attempts in raw string
    for (String dangerous : DANGEROUS_COMPONENTS) {
      if (pathStr.contains(dangerous)) {
        log.warn("Rejected path with traversal attempt: {}", pathStr);
        return null;
      }
    }

    try {
      Path path = Path.of(pathStr);

      // Normalize to resolve any remaining oddities
      Path normalized = path.normalize();

      // After normalization, double-check for traversal
      if (normalized.toString().contains("..")) {
        log.warn("Rejected normalized path with traversal: {}", normalized);
        return null;
      }

      // Must be absolute path
      if (!normalized.isAbsolute()) {
        log.debug("Rejected relative path: {}", pathStr);
        return null;
      }

      // File must exist
      if (!Files.exists(normalized)) {
        log.debug("File does not exist: {}", normalized);
        return null;
      }

      // File must be readable
      if (!Files.isReadable(normalized)) {
        log.debug("File not readable: {}", normalized);
        return null;
      }

      return normalized;

    } catch (InvalidPathException e) {
      log.warn("Invalid path syntax: {}", pathStr);
      return null;
    }
  }

  public StatusResponse indexStatus(StatusRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      try {
        return statusOps.buildStatusResponse();
      } catch (RuntimeException e) {
        log.error("Failed to get index status", e);
        return StatusResponse.newBuilder()
            .setCore(
                io.justsearch.ipc.CoreStatus.newBuilder()
                    .setIsHealthy(false)
                    .setState("ERROR")
                    .build())
            .build();
      }
    }
  }

  private void requireServingVduTarget() {
    try {
      if (ingestIsServing && (indexGenerationManager == null
          || indexGenerationManager.isIdleActiveGeneration(capturedServingPath))) return;
    } catch (IOException | RuntimeException failure) {
      throw new WorkerServiceException(WorkerServiceException.Status.UNAVAILABLE,
          "VDU target state is unavailable; retry after the index is ready", failure);
    }
    throw WorkerServiceException.unavailable(
        "VDU requires the active serving generation; retry after the index transition");
  }

  /** Strict read for recorded ingestion preparation; this observation is not a generation lease. */
  public String captureServingGeneration(CallContext ctx) {
    return captureActiveGeneration(ctx, true);
  }

  /** Read-only active witness for the recovery rebuild; this never grants ordinary ingestion. */
  public String captureRebuildGeneration(CallContext ctx) {
    return captureActiveGeneration(ctx, false);
  }

  /**
   * Strictly observes the committed fingerprint/input pair served by the captured search runtime.
   * The authoritative active pointer is fenced before and after the commit read; a build may be in
   * progress because Blue remains the applied generation until promotion.
   */
  public AppliedIndexGeneration captureAppliedGeneration(CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      requireAppliedGenerationNotCancelled(ctx);
      if (!hasServingRuntime || searchLifecycle == null || indexGenerationManager == null
          || capturedServingPath == null) {
        throw WorkerServiceException.unavailable("Applied generation authority is unavailable");
      }

      String generationBefore = requireCapturedActiveGeneration(false);
      Map<String, String> metadata;
      try {
        metadata = searchLifecycle.latestCommitUserDataBestEffort();
      } catch (RuntimeException failure) {
        throw new WorkerServiceException(
            WorkerServiceException.Status.UNAVAILABLE,
            "Committed index metadata could not be read",
            failure);
      }
      requireAppliedGenerationNotCancelled(ctx);
      AppliedIndexGeneration observation = appliedGeneration(generationBefore, metadata);
      String generationAfter = requireCapturedActiveGeneration(true);
      if (!generationBefore.equals(generationAfter)) {
        throw WorkerServiceException.aborted("Active generation moved during applied observation");
      }
      requireAppliedGenerationNotCancelled(ctx);
      return observation;
    }
  }

  private String requireCapturedActiveGeneration(boolean observationStarted) {
    final java.util.Optional<String> generation;
    try {
      generation = indexGenerationManager.activeGeneration(capturedServingPath);
    } catch (IOException | RuntimeException failure) {
      throw new WorkerServiceException(
          WorkerServiceException.Status.UNAVAILABLE,
          "Active generation state could not be established",
          failure);
    }
    return generation.orElseThrow(() -> observationStarted
        ? WorkerServiceException.aborted("Captured serving generation is no longer active")
        : WorkerServiceException.unavailable("Captured serving generation is not active"));
  }

  private static AppliedIndexGeneration appliedGeneration(
      String generationId, Map<String, String> metadata) {
    if (metadata == null) {
      throw WorkerServiceException.unavailable("Committed index metadata is unavailable");
    }
    String fingerprint = metadata.get(IndexFingerprint.COMMIT_META_KEY);
    String inputs = metadata.get(IndexFingerprint.COMMIT_META_INPUTS_KEY);
    if (fingerprint == null || inputs == null) {
      throw WorkerServiceException.unavailable(
          "Committed index fingerprint evidence is incomplete");
    }
    try {
      IndexTargetSnapshot target = new IndexTargetSnapshot(fingerprint, inputs);
      tools.jackson.databind.JsonNode parsed = COMMITTED_INPUTS_JSON.readTree(inputs);
      if (parsed == null || !parsed.isObject()) {
        throw new IllegalArgumentException("Canonical index inputs must be a JSON object");
      }
      return new AppliedIndexGeneration(generationId, target);
    } catch (tools.jackson.core.JacksonException | IllegalArgumentException invalid) {
      throw new WorkerServiceException(
          WorkerServiceException.Status.UNAVAILABLE,
          "Committed index fingerprint evidence is invalid",
          invalid);
    }
  }

  private static void requireAppliedGenerationNotCancelled(CallContext ctx) {
    if (ctx.cancelled()) {
      throw WorkerServiceException.cancelled("Applied generation capture cancelled");
    }
  }

  private String captureActiveGeneration(CallContext ctx, boolean requireWriter) {
    try (var ignored = openRequestMdc(ctx)) {
      if (ctx.cancelled()) {
        throw new WorkerServiceException(WorkerServiceException.Status.CANCELLED, "Generation capture cancelled");
      }
      boolean projectedServingWriter = searchLifecycle instanceof
          io.justsearch.adapters.lucene.runtime.RunningRuntime active
          && active.isAcceptingWrites() && ingestLifecycle != null;
      if ((requireWriter ? ((!ingestIsServing && !projectedServingWriter)
              || ingestLifecycle == null) : !hasServingRuntime)
          || indexGenerationManager == null || capturedServingPath == null) {
        throw WorkerServiceException.unavailable("Serving generation authority is unavailable");
      }
      java.util.Optional<String> generation;
      try {
        // During a recorded build, the source remains the active A generation and has
        // its own lexical writer. The admitted Green producer projects accepted writes
        // into A; requiring IDLE here would reject every such prepared ingest.
        generation = requireWriter && !ingestIsServing
            ? indexGenerationManager.activeGeneration(capturedServingPath)
            : indexGenerationManager.idleActiveGeneration(capturedServingPath);
      } catch (IOException | RuntimeException failure) {
        throw new WorkerServiceException(WorkerServiceException.Status.UNAVAILABLE,
            "Serving generation state could not be established", failure);
      }
      if (ctx.cancelled()) {
        throw new WorkerServiceException(WorkerServiceException.Status.CANCELLED, "Generation capture cancelled");
      }
      return generation.orElseThrow(() -> WorkerServiceException.unavailable(
          "Recorded ingestion requires the current idle serving generation"));
    }
  }

  /** Captures the Worker-computed physical index target, independently of Blue/Green serving state. */
  public IndexTargetSnapshot captureIndexTarget(CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      if (ctx.cancelled()) {
        throw WorkerServiceException.cancelled("Index target capture cancelled");
      }
      if (!hasServingRuntime || indexGenerationManager == null || capturedServingPath == null) {
        throw WorkerServiceException.unavailable("Index target authority is unavailable");
      }

      Map<String, Object> metadata = null;
      RuntimeException metadataFailure = null;
      try {
        Supplier<Map<String, Object>> supplier = indexTargetMetadataSupplier;
        metadata = supplier == null ? new SsotCommitMetadataSource().build() : supplier.get();
      } catch (RuntimeException failure) {
        metadataFailure = failure;
      }
      if (ctx.cancelled()) {
        throw WorkerServiceException.cancelled("Index target capture cancelled");
      }
      if (metadataFailure != null) {
        throw new WorkerServiceException(
            WorkerServiceException.Status.UNAVAILABLE,
            "Physical index target could not be established",
            metadataFailure);
      }
      if (metadata == null) {
        throw WorkerServiceException.unavailable("Physical index target metadata is unavailable");
      }

      Object fingerprint = metadata.get(IndexFingerprint.COMMIT_META_KEY);
      Object inputs = metadata.get(IndexFingerprint.COMMIT_META_INPUTS_KEY);
      if (!(fingerprint instanceof String fingerprintText)
          || !(inputs instanceof String inputsJson)) {
        throw WorkerServiceException.unavailable("Physical index target is indeterminate");
      }
      final IndexTargetSnapshot snapshot;
      try {
        snapshot = new IndexTargetSnapshot(fingerprintText, inputsJson);
      } catch (IllegalArgumentException invalid) {
        throw new WorkerServiceException(
            WorkerServiceException.Status.UNAVAILABLE,
            "Physical index target evidence is invalid",
            invalid);
      }
      if (ctx.cancelled()) {
        throw WorkerServiceException.cancelled("Index target capture cancelled");
      }
      return snapshot;
    }
  }

  public UpdateVduResultResponse updateVduResult(
      UpdateVduResultRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      synchronized (vduMutationMonitor) {
        String docId = request.getDocId();
        try {
          VduResultWriter.validate(request);
        } catch (IllegalArgumentException e) {
          return updateVduErrorResponse(e.getMessage());
        }
        requireServingVduTarget();
        var procedureOwner = ctx.engineContext().workId();
        VduProcessingClaim claim = vduProcessingClaims.get(docId);
        if (procedureOwner.isEmpty() || claim == null
            || !procedureOwner.get().equals(claim.owner())
            || !request.getAttemptId().equals(claim.attemptId())) {
          if (request.getAttemptId().isBlank()) {
            try {
              withFileMutationFence(() -> {
                try { refreshLegacyVduSource(docId); return true; }
                catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
              });
            } catch (java.io.UncheckedIOException failure) {
              return updateVduErrorResponse(failure.getCause().getMessage());
            }
          }
          return updateVduErrorResponse("VDU procedure no longer owns this document: " + docId);
        }
        try {
          // The chunk replacement holds the file-mutation fence, so a prune of this file never
          // sees part of it and no replacement chunk lands after the file was removed.
          String refusal;
          try {
            refusal = withFileMutationFence(() -> {
              try {
                ingestLifecycle.commitOps().maybeRefreshBlocking();
                if (ingestLifecycle.documentFieldOps().excludedProcessingIds(List.of(docId)).contains(docId)) {
                  // Already under the mutation lease, monitor and file fence: reset this exact
                  // claim directly instead of re-entering the public release admission path.
                  if (resetVduStatusToPending(docId, claim.owner(), Set.of(claim.attemptId()))) {
                    ingestLifecycle.commitOps().commitAndTrack(CommitReason.VDU_RECOVERY);
                    ingestLifecycle.commitOps().maybeRefreshBlocking();
                  }
                  requireServingVduTarget();
                  return "Source is excluded by the current policy";
                }
                if (!vduClaimMatches(docId, claim)
                    || !VduResultWriter.apply(ingestLifecycle, request, chunkSpladeEnabled(), claim.expectedRevision())) {
                  return "VDU source revision changed: " + docId;
                }
                return null;
              } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
              }
            });
          } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
          }
          if (refusal != null) {
            vduProcessingClaims.remove(docId, claim);
            return updateVduErrorResponse(refusal);
          }
          vduProcessingClaims.remove(docId);
          ingestLifecycle.commitOps().commitAndTrack(CommitReason.VDU_UPDATE);
          ingestLifecycle.commitOps().maybeRefreshBlocking();
          requireServingVduTarget();
          return updateVduSuccessResponse();
        } catch (WorkerServiceException failure) {
          throw failure;
        } catch (Exception e) {
          log.error("updateVduResult failed for doc: {}", docId, e);
          return updateVduErrorResponse(e.getMessage());
        }
      }
    }
  }

  private static void deletePrefix(io.justsearch.adapters.lucene.runtime.IndexingCoordinator writer,
      PathPrefixScope scope) {
    if (scope.excludedSubtrees().isEmpty()) writer.deleteByPathPrefix(scope.prefix());
    else writer.deleteByPathPrefixExcludingAcceptedSurvivors(
        scope.prefix(), List.of(), scope.excludedSubtrees());
  }

  public DeleteByPathResponse deleteByPath(DeleteByPathRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      synchronized (rootWatcherRegistry.current()) {
        String pathPrefix = request.getPath();
        log.info("deleteByPath RPC called for prefix: {}", pathPrefix);

        DeleteByPathResponse blank =
            blankReply(pathPrefix, deleteByPathResponse(0, "Path prefix is required"));
        if (blank != null) {
          return blank;
        }

        DeleteByPathResponse unavailable =
            indexRuntimeUnavailableReply(
                "deleteByPath", deleteByPathResponse(-1, "Index runtime not available"));
        if (unavailable != null) {
          throw WorkerServiceException.unavailable(
              "Removal did not complete: the search index is not ready. "
                  + "Wait for index recovery, then retry Remove.");
        }

        try {
          var scope = prefixScope(pathPrefix);
          boolean removingRoot = io.justsearch.configuration.persistence.WatchedRootsFormat
              .pendingRemovals(declaredRootsDirectory)
              .contains(Path.of(pathPrefix).toAbsolutePath().normalize());
          if (declaredRootsDirectory == null && rootWatcherRegistry.current().declaredRoots()
              .contains(Path.of(pathPrefix).toAbsolutePath().normalize())) {
            throw WorkerServiceException.unavailable(
                "Removal intent is not confirmed; retry Remove after watched-root declarations recover");
          }
          if (switchBufferOps.isSwitching() && removingRoot) {
            return deleteByPathResponse(0, io.justsearch.configuration.persistence.WatchedRootsFormat
                .REMOVAL_REFUSED_DURING_SWITCHING);
          }
          if (switchBufferOps.isSwitching() && !hasDistinctWritableServingTarget()) {
            if (jobQueue instanceof SwitchBufferCapableQueue sbq) {
              return switchBufferOps.bufferDeleteByPathDuringSwitching(sbq, scope);
            }
            throw IngestSwitchBufferOps.switchingUnavailable();
          }

          // Only the Head's durable removal intent authorizes watcher retirement here. Ordinary
          // unwatch remains uncertain; a plain prefix deletion leaves the registration alone.
          if (removingRoot) {
            String generation = switchBufferOps.buildingGenerationForFileAdmission();
            if (generation != null) switchBufferOps.journalDeletePrefixForGeneration(generation, scope);
            // No file fence while draining callbacks: an issued DELETE may need that fence.
            forgetWatchRoot(pathPrefix);
          }

          return withFileMutationFence(() -> {
            // Reissue after the watcher drain, ordering the removal after its last issued event.
            // The admission lease also keeps promotion out through commit and queue cleanup.
            journalAndProjectPrefixDeleteToServing(scope);
            if (scope.excludedSubtrees().isEmpty()) ingestLifecycle.indexingCoordinator().deleteByPathPrefix(pathPrefix);
            else deletePrefix(ingestLifecycle.indexingCoordinator(), scope);
            int jobsDeleted = scope.excludedSubtrees().isEmpty()
                ? jobQueue.deleteByPathPrefix(pathPrefix)
                : jobQueue.deleteByPathPrefixExcludingAcceptedSurvivors(
                    scope.prefix(), List.of(), scope.excludedSubtrees());
            if (jobsDeleted < 0) throw new IllegalStateException("Path-prefix queue deletion failed");
            ingestLifecycle.commitOps().commitAndTrack(CommitReason.GRPC_DELETE_BY_PATH);
            ingestLifecycle.commitOps().maybeRefreshBlocking();
            if (searchLifecycle instanceof io.justsearch.adapters.lucene.runtime.RunningRuntime serving
                && serving != ingestLifecycle) serving.commitOps().maybeRefreshBlocking();
            log.info("deleteByPath complete: {} jobs deleted for prefix: {}", jobsDeleted, pathPrefix);
            return deleteByPathResponse(jobsDeleted, "");
          });

        } catch (WorkerServiceException e) {
          // Must precede the catch-all: the SWITCHING branch above throws UNAVAILABLE from inside
          // this try. Swallowed, it would come back as a successful-looking response carrying
          // error=<message> and deleted=-1 instead of the UNAVAILABLE status the caller retries on.
          throw e;
        } catch (Exception e) {
          log.error("deleteByPath failed for prefix: {}", pathPrefix, e);
          return deleteByPathResponse(-1, e.getMessage());
        }
      }
    }
  }

  /**
   * Tempdoc 811 (C-2a) — the removal route for collection-tagged ad-hoc ingests. {@link
   * #deleteByPath} is watched-root-prefix driven and can never reach a document ingested from a path
   * under no watched root; this deletes by the {@code collection} term instead.
   *
   * <p>WHICH collections are deletable is decided by ONE Head-side authority ({@code
   * IngestCollectionPolicy#isDeletable} — refuses the reserved app-internal corpora and the untagged
   * default bucket). The worker deliberately does not fork that list; it only refuses a blank value.
   */
  public DeleteByCollectionResponse deleteByCollection(
      DeleteByCollectionRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      String collection = request.getCollection();
      log.info("deleteByCollection RPC called for collection: {}", collection);

      DeleteByCollectionResponse blank =
          blankReply(collection, deleteByCollectionResponse(-1, "Collection is required"));
      if (blank != null) {
        return blank;
      }
      DeleteByCollectionResponse unavailable =
          indexRuntimeUnavailableReply(
              "deleteByCollection",
              deleteByCollectionResponse(-1, "Index runtime not available"));
      if (unavailable != null) {
        return unavailable;
      }

      try {
        if (switchBufferOps.isSwitching() && !hasDistinctWritableServingTarget()) {
          // Fail closed rather than buffer: a collection-scoped bulk delete replayed against a
          // freshly-switched index could race the migration's own document set.
          throw IngestSwitchBufferOps.switchingUnavailable();
        }

        return withFileMutationFence(() -> {
          collectionRemovals.put(collection, new Object());
          jobQueue.deleteByCollection(collection);
          Integer visibleDeleted = journalAndProjectCollectionDeleteToServing(collection);
          int deleted = ingestLifecycle.indexingCoordinator().deleteByCollection(collection);
          ingestLifecycle.commitOps().commitAndTrack(CommitReason.GRPC_DELETE_BY_COLLECTION);
          ingestLifecycle.commitOps().maybeRefreshBlocking();
          if (searchLifecycle instanceof io.justsearch.adapters.lucene.runtime.RunningRuntime serving
              && serving != ingestLifecycle) serving.commitOps().maybeRefreshBlocking();
          log.info("deleteByCollection complete: {} documents deleted for {}", deleted, collection);
          return deleteByCollectionResponse(visibleDeleted == null ? deleted : visibleDeleted, "");
        });
      } catch (WorkerServiceException e) {
        // Must precede the catch-all: this endpoint deliberately FAILS CLOSED during SWITCHING,
        // and the catch-all would turn that refusal into a deleted=-1 response, hiding the
        // UNAVAILABLE the caller is meant to retry on.
        throw e;
      } catch (Exception e) {
        log.error("deleteByCollection failed for collection: {}", collection, e);
        return deleteByCollectionResponse(-1, e.getMessage());
      }
    }
  }

  public DeleteByIdResponse deleteById(DeleteByIdRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
    String docId = request.getDocId();
    log.info("deleteById RPC called for doc_id: {}", docId);

    DeleteByIdResponse blank = blankReply(docId, deleteByIdResponse(false, "doc_id is required"));
    if (blank != null) {
      return blank;
    }

    DeleteByIdResponse unavailable =
        indexRuntimeUnavailableReply(
            "deleteById", deleteByIdResponse(false, "Index runtime not available"));
    if (unavailable != null) {
      return unavailable;
    }

    try {
      // Normalize the path (lowercase on Windows)
      String normalizedId = normalizeDocIdForMutation(docId);

      if (switchBufferOps.isSwitching() && !hasDistinctWritableServingTarget()) {
        if (request.getRequireSettled()) throw IngestSwitchBufferOps.switchingUnavailable();
        if (jobQueue instanceof SwitchBufferCapableQueue sbq) {
          return switchBufferOps.bufferDeleteByIdDuringSwitching(sbq, normalizedId);
        }
        throw IngestSwitchBufferOps.switchingUnavailable();
      }

      return withFileMutationFence(() -> {
        journalAndProjectDeleteToServing(normalizedId, CommitReason.GRPC_DELETE_BY_ID);
        ingestLifecycle.indexingCoordinator().deleteByIdAndChunks(normalizedId);
        if (jobQueue.deleteByExactPath(normalizedId) < 0) {
          throw new IllegalStateException("Could not remove pending ingestion for deleted document");
        }
        ingestLifecycle.commitOps().commitAndTrack(CommitReason.GRPC_DELETE_BY_ID);
        if (request.getRequireSettled()) {
          ingestLifecycle.commitOps().maybeRefreshBlocking();
          if (searchLifecycle instanceof io.justsearch.adapters.lucene.runtime.RunningRuntime serving
              && serving != ingestLifecycle) serving.commitOps().maybeRefreshBlocking();
        }
        log.info("deleteById complete for doc_id: {}", normalizedId);
        return deleteByIdResponse(true, "");
      });

    } catch (WorkerServiceException e) {
      // Must precede the catch-all: the SWITCHING branch above throws UNAVAILABLE from inside
      // this try, and swallowing it would report success=false with the message inline instead
      // of the UNAVAILABLE status.
      throw e;
    } catch (Exception e) {
      log.error("deleteById failed for doc_id: {}", docId, e);
      return deleteByIdResponse(false, e.getMessage());
    }
    }
  }

  /** Read-only ownership enumeration from the serving view used by Apply Excludes. */
  public long scanIndexedSources(java.util.function.Consumer<String> consumer, CallContext ctx) {
    Objects.requireNonNull(consumer, "consumer");
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      var source = searchLifecycle != null ? searchLifecycle : ingestLifecycle;
      if (source == null) throw WorkerServiceException.unavailable("Index runtime not available");
      source.commitOps().maybeRefreshBlocking();
      return source.documentFieldOps().scanIndexedSources(256, batch -> {
        for (var row : batch) {
          if (ctx.cancelled()) throw WorkerServiceException.unavailable("Indexed source enumeration cancelled");
          consumer.accept(row.path());
        }
      });
    }
  }

  /** Periodic corpus backstop for completed ad-hoc sources, including with no watched roots. */
  public PruneResponse reconcileIndexedSources(CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      if (ingestLifecycle == null) return pruneErrorResponse("Index runtime not available");
      if (switchBufferOps.buildingGenerationForFileAdmission() != null) {
        return pruneErrorResponse("Indexed source reconciliation waits for migration activation");
      }
      var roots = declaredCollectionRoots().stream().map(binding -> binding.path()).toList();
      int[] removed = {0}, unknown = {0};
      var marker = confirmedDeletionMarker;
      var checkedPaths = new java.util.HashSet<String>();
      ingestLifecycle.commitOps().maybeRefreshBlocking();
      long skipped = ingestLifecycle.documentFieldOps().scanIndexedSources(256, batch -> {
        for (var identity : batch) {
          if (ctx.cancelled()) return;
          if (!checkedPaths.add(identity.path())) continue;
          Path file;
          try { file = Path.of(identity.path()); }
          catch (InvalidPathException invalid) { continue; }
          if (!file.isAbsolute() || roots.stream().anyMatch(file::startsWith)) continue;
          if (io.justsearch.indexerworker.loop.SourcePresence.classify(file, null, rootWitness)
              == io.justsearch.indexerworker.loop.SourcePresence.Verdict.PRESENT) continue;
          withFileMutationFence(() -> {
            try {
              var fields = ingestLifecycle.documentFieldOps();
              String projection = fields.getDocumentFieldOrThrow(identity.docId(), SchemaFields.PROJECTION_SOURCE_ID);
              String uid = fields.getDocumentFieldOrThrow(identity.docId(), SchemaFields.DOC_UID);
              String backing = fields.getDocumentFieldOrThrow(identity.docId(), SchemaFields.PATH);
              if (projection != null && !projection.isBlank()
                  || !Objects.equals(identity.docUid(), uid)
                  || fields.getDocumentFieldOrThrow(identity.docId(), SchemaFields.DOC_ID) == null
                  || backing != null && !backing.isBlank() && !backing.equals(identity.path())) return false;
              var verdict = io.justsearch.indexerworker.loop.SourcePresence.classify(file, null, rootWitness);
              if (verdict == io.justsearch.indexerworker.loop.SourcePresence.Verdict.ABSENT) {
                var guard = new io.justsearch.adapters.lucene.runtime.PruneOps.PresenceGuard() {
                  @Override public boolean rootCurrent() { return rootWitness.vouchesForFile(file.getParent()); }
                };
                int pruned = ingestLifecycle.pruneOps().pruneFile(identity.path(), file.getParent(),
                    guard, io.justsearch.adapters.lucene.runtime.PruneOps.WriterFence.NONE);
                if (pruned > 0) marker.markIfAbsent(file);
                removed[0] += pruned;
              } else if (verdict != io.justsearch.indexerworker.loop.SourcePresence.Verdict.PRESENT) unknown[0]++;
              return true;
            } catch (IOException unreadable) {
              throw new java.io.UncheckedIOException(unreadable);
            }
          });
        }
      });
      if (removed[0] > 0) {
        ingestLifecycle.commitOps().commitAndTrack(CommitReason.PRUNE);
        ingestLifecycle.commitOps().maybeRefreshBlocking();
      }
      if (ctx.cancelled()) return pruneResultResponse(removed[0], true);
      if (unknown[0] > 0 || skipped > 0) return pruneIncompleteResponse(removed[0], unknown[0]);
      return pruneResultResponse(removed[0], false);
    } catch (Exception failure) {
      return pruneErrorResponse(failure.getMessage());
    }
  }

  public PruneResponse pruneMissing(PruneRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
    String pathPrefix = request.getPathPrefix();
    log.info("pruneMissing RPC called for prefix: {}", pathPrefix);

    PruneResponse blank = blankReply(pathPrefix, pruneErrorResponse("path_prefix is required"));
    if (blank != null) {
      return blank;
    }

    try {
      if (switchBufferOps.isSwitching()) {
        if (hasDistinctWritableServingTarget()) {
          throw WorkerServiceException.unavailable(
              "Prune requires candidate-scoped delete projection during migration; retry after activation");
        }
        // Legacy single-runtime switching retains its historical deferred contract.
        if (jobQueue instanceof SwitchBufferCapableQueue sbq) {
          return switchBufferOps.bufferPruneMissingDuringSwitching(sbq, pathPrefix);
        }
        throw IngestSwitchBufferOps.switchingUnavailable();
      }
      if (switchBufferOps.migratingGeneration() != null) {
        throw WorkerServiceException.unavailable(
            "Prune requires candidate-scoped delete projection during migration; retry after activation");
      }

      PruneResponse unavailable =
          indexRuntimeUnavailableReply(
              "pruneMissing", pruneErrorResponse("Index runtime not available"));
      if (unavailable != null) {
        return unavailable;
      }

      // Prune orphan documents - abort if user becomes active
      // Tempdoc 885 item 3: the throttle callback is the pacing tick and never aborts — a prune
      // that stops half-way on user activity leaves orphans behind, which is what the breath-hold
      // did. `aborted` therefore stays false on this path; the response field remains for the
      // force/abort contract PruneOps still exposes.
      // #758: a document is pruned only when its file is reported not found
      // while the root holding it (its registered identity, re-checked every batch) is present.
      Path pruneRoot = pruneRootFor(pathPrefix);
      io.justsearch.adapters.lucene.runtime.PruneOps.PruneResult result =
          ingestLifecycle.pruneOps().pruneByPathPrefix(
              pathPrefix,
              indexingPacing::paceAndContinue,
              100, // Throttle batch size
              pruneGuard(pruneRoot),
              confirmedDeletionMarker::markIfAbsent,
              pruneWriterFence);

      switch (result.completion()) {
        case ABORTED -> {
          log.info("pruneMissing aborted for prefix: {} (user activity)", pathPrefix);
          return pruneResultResponse(result.pruned(), true);
        }
        case INCOMPLETE -> {
          log.warn(
              "pruneMissing incomplete for prefix: {} ({} pruned, {} kept because their files"
                  + " could not be checked)",
              pathPrefix,
              result.pruned(),
              result.keptUnknown());
          return pruneIncompleteResponse(result.pruned(), result.keptUnknown());
        }
        default -> {
          log.info(
              "pruneMissing complete: {} orphans pruned for prefix: {}", result.pruned(), pathPrefix);
          return pruneResultResponse(result.pruned(), false);
        }
      }

    } catch (WorkerServiceException e) {
      // Must precede the catch-all: the SWITCHING branch above throws UNAVAILABLE from inside
      // this try, and swallowing it would answer with an ordinary prune error response instead
      // of the UNAVAILABLE status.
      throw e;
    } catch (Exception e) {
      log.error("pruneMissing failed for prefix: {}", pathPrefix, e);
      return pruneErrorResponse(e.getMessage());
    }
    }
  }

  /**
   * Bidirectional sync: delete orphaned documents + add missing files.
   *
   * <p>This replaces the one-directional PruneMissing with full reconciliation:
   * <ol>
   *   <li>Delete documents for files that no longer exist on disk</li>
   *   <li>Enqueue files that exist on disk but are not in the index</li>
   * </ol>
   *
   * <p>Used for: OVERFLOW events, periodic maintenance, Windows DELETE workaround.
   *
   * <p><b>Throttling:</b> Checks user activity every 100 files, sleeps 1ms every 100 files.
   */
  public SyncDirectoryResponse syncDirectory(SyncDirectoryRequest request, CallContext ctx) {
    JobQueue.EnqueueProvenance provenance =
        ctx.engineContext().clientKind() == io.justsearch.core.context.EngineContext.ClientKind.INTERNAL
            && "SYSTEM_INTERNAL".equals(ctx.provenance().transport()) ? null : ctx.provenance();
    return syncDirectoryCommon(request, ctx, provenance, false, provenance == null
        && ctx.engineContext().survival() == io.justsearch.core.context.EngineContext.Survival.DURABLE
        && ctx.engineContext().urgency() == io.justsearch.core.context.EngineContext.Urgency.BACKGROUND
        ? SyncDirectoryOps.AdmissionMode.MAINTENANCE : SyncDirectoryOps.AdmissionMode.DEFAULT, true,null);
  }

  /** Replays a durable sync with its persisted descriptive attribution. */
  public SyncDirectoryResponse syncDirectoryForReplay(
      SyncDirectoryRequest request, JobQueue.EnqueueProvenance provenance) {
    return syncDirectoryCommon(request, CallContext.none(), provenance, false,
        SyncDirectoryOps.AdmissionMode.DEFAULT, false,null);
  }

  /** Retirement replay retains exact owners and retry work without a new acknowledgement. */
  public SyncDirectoryResponse syncDirectoryForRelease030Replay(
      SyncDirectoryRequest request, JobQueue.EnqueueProvenance provenance,
      SwitchBufferCapableQueue.SwitchBufferOp originalRoot) {
    return syncDirectoryCommon(request, CallContext.none(), provenance, true,
        SyncDirectoryOps.AdmissionMode.RETIREMENT_REPLAY, false,originalRoot);
  }

  /** The final cutover fence owns SWITCHING and replays this version directly onto Green. */
  public SyncDirectoryResponse syncDirectoryForFinalCutoverReplay(
      SyncDirectoryRequest request, JobQueue.EnqueueProvenance provenance) {
    return syncDirectoryCommon(request, CallContext.none(), provenance, true,
        SyncDirectoryOps.AdmissionMode.DEFAULT, false,null);
  }

  private SyncDirectoryResponse syncDirectoryCommon(
      SyncDirectoryRequest request, CallContext ctx, JobQueue.EnqueueProvenance provenance,
      boolean finalCutoverReplay, SyncDirectoryOps.AdmissionMode admissionMode, boolean liveSync,SwitchBufferCapableQueue.SwitchBufferOp originalRoot) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      String rootPath = request.getRootPath();
      boolean force = request.getForce();
      String producerCollection = creatorRootCollection(rootPath);
      Object collectionAtStart = collectionRemoval(producerCollection);
      java.util.function.BooleanSupplier cancelled = () -> ctx.cancelled()
          || collectionAtStart != collectionRemoval(producerCollection);
      log.info("syncDirectory RPC called for root: {} (force={})", rootPath, force);

      // Validate input (protobuf returns empty string, not null)
      SyncDirectoryResponse blank =
          blankReply(rootPath, syncDirectoryErrorResponse("root_path is required"));
      if (blank != null) {
        return blank;
      }
      SyncDirectoryResponse otherVolume = refuseSyncOfRootOnAnotherVolume(rootPath);
      if (otherVolume != null) {
        return otherVolume;
      }

      if (!finalCutoverReplay && switchBufferOps.isSwitching()) {
        if (hasDistinctWritableServingTarget()) {
          return reconcileCandidateRoot(rootPath, force, cancelled);
        }
        // Legacy single-runtime switching retains its historical deferred contract.
        return switchBufferOps.bufferDuringSwitchingOrThrow(
            "syncDirectory",
            sbq ->
                switchBufferOps.bufferSyncDirectoryDuringSwitching(
                    sbq, rootPath, force, provenance, creatorRootCollection(rootPath)));
      }
      if (!finalCutoverReplay && switchBufferOps.migratingGeneration() != null) {
        if (hasDistinctWritableServingTarget()) {
          return reconcileCandidateRoot(rootPath, force, cancelled);
        }
        throw WorkerServiceException.unavailable(
            "Directory sync requires a writable serving generation during migration; retry shortly");
      }

      SyncDirectoryResponse unavailable =
          indexRuntimeUnavailableReply(
              "syncDirectory", syncDirectoryErrorResponse("Index runtime not available"));
      if (unavailable != null) {
        return unavailable;
      }

      String retirement = jobQueue instanceof SwitchBufferCapableQueue queue
          ? queue.release030RetirementGeneration() : null;
      SyncDirectoryOps.AdmissionMode effectiveMode = retirement == null ? admissionMode
          : !liveSync ? SyncDirectoryOps.AdmissionMode.RETIREMENT_REPLAY
          : admissionMode == SyncDirectoryOps.AdmissionMode.MAINTENANCE
              ? SyncDirectoryOps.AdmissionMode.RETIREMENT_MAINTENANCE
              : SyncDirectoryOps.AdmissionMode.RETIREMENT_LIVE;
      java.util.function.Function<SwitchBufferCapableQueue.SwitchBufferOp,SyncDirectoryResponse> sync = origin -> {
        var binding = rootWatcherRegistry.subscription(parsedRoot(rootPath));
        if (binding == null && effectiveMode == SyncDirectoryOps.AdmissionMode.DEFAULT) {
          return syncOps.execute(rootPath, force, provenance, cancelled);
        }
        String collection=origin!=null && jobQueue instanceof SwitchBufferCapableQueue queue
            ? queue.retirementRootCollection(origin) : binding==null?null:binding.collection();
        if(origin!=null && !((SwitchBufferCapableQueue)jobQueue).retirementRootPolicyKnown(origin))return syncDirectoryErrorResponse("ROOT creator namespace is unknown");
        if(origin!=null && ((SwitchBufferCapableQueue)jobQueue).retirementRootLegacyExecution(origin))
          collection=binding==null?null:binding.collection();
        return syncOps.execute(rootPath, force, provenance, cancelled,collection,effectiveMode,origin);
      };
      if (liveSync && retirement != null) {
        return withFileMutationFence(() -> {
          var origin=switchBufferOps.journalSyncRootForGeneration(retirement,rootPath,force,provenance,creatorRootCollection(rootPath));
          return sync.apply(origin);
        });
      }
      return sync.apply(originalRoot);
    }
  }

  private java.util.function.Function<Path,String> creatorRootCollection = root -> null;

  /** The composition root supplies its boot/configuration policy for fresh public ROOTs. */
  public void useCreatorRootCollection(java.util.function.Function<Path,String> policy) {
    creatorRootCollection = Objects.requireNonNull(policy);
  }

  private String creatorRootCollection(String rootPath) {
    var binding=rootWatcherRegistry.subscription(parsedRoot(rootPath));
    return binding==null?creatorRootCollection.apply(parsedRoot(rootPath)):binding.collection();
  }

  /**
   * Reconciles a complete root snapshot through the same candidate-scoped routes as watcher
   * events. The caller owns one mutation lease from validation through every admission, so the
   * final fence cannot split discovery from its durable B obligations and A projections.
   */
  private SyncDirectoryResponse reconcileCandidateRoot(String rootPath, boolean force,
      java.util.function.BooleanSupplier cancelled) {
    int added = 0;
    int deleted = 0;
    try {
      SyncDirectoryOps.checkCancelled(cancelled);
      Path root = Path.of(rootPath).toAbsolutePath().normalize();
      RootWatcherRegistry registry = rootWatcherRegistry;
      RootWatcherRegistry.Subscription subscription = registry.subscription(root);
      requireCandidateSubscription(registry, root, subscription);
      try (SyncDirectoryOps.RootDifference difference =
          candidateDiscoveryOps.discoverCandidateDifference(rootPath, force, cancelled)) {
        requireCandidateSubscription(registry, root, subscription);
        String collection = subscription.collection();
        for (Path path : difference.additions()) {
          SyncDirectoryOps.checkCancelled(cancelled);
          requireCandidateSubscription(registry, root, subscription);
          difference.requireCurrentRootIdentity();
          if (acceptWatcherUpsertUnderLease(collection, path, root)) added++;
        }
        for (String path : difference.deletions()) {
          SyncDirectoryOps.checkCancelled(cancelled);
          requireCandidateSubscription(registry, root, subscription);
          difference.requireCurrentRootIdentity();
          if (acceptWatcherDeleteUnderLease(
              path, () -> deleteCandidateAndMark(path), true, false, root,
              difference.rootIdentity())) {
            deleted++;
          }
        }
        SyncDirectoryOps.checkCancelled(cancelled);
        requireCandidateSubscription(registry, root, subscription);
        difference.requireCurrentRootIdentity();
      }
      return syncDirectoryResultResponse(deleted, added);
    } catch (Exception incomplete) {
      if (mutationAdmission != null) mutationAdmission.markReplayUncertain();
      if (incomplete instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
        Thread.currentThread().interrupt();
      }
      log.warn("Candidate root reconciliation failed closed for {}", rootPath, incomplete);
      throw WorkerServiceException.unavailable(
          "Candidate root reconciliation was incomplete; cutover remains fenced");
    }
  }

  /**
   * Re-establishes a declared watched root on a committed successor before that successor is
   * published. The strict disk/index difference is evaluated against the serving B runtime; all
   * eligible files are re-admitted, including modifications that had no switch-buffer event.
   */
  public boolean reconcileCommittedBootRoot(
      io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding binding)
      throws IOException {
    return reconcileCommittedBootRoot(binding, () -> false);
  }

  /**
   * Returns {@code false}, changing nothing, when the root is deferred (#758): the folder at its
   * path is not on the volume recorded for it. Its watch is then
   * held until that volume is back, nothing of the other volume is read, and the caller leaves
   * the root out of its convergence check and keeps the previous generation until the root has
   * been reconciled through {@link #reconcileReturnedBootRoot}. A deferral is not a failure, so
   * it leaves the replay certificate as it was. Any other failure throws as before.
   *
   * <p>Task e74253: a root an ordinary restart would not reconcile either is deferred the same
   * way. A folder missing from its path has its watch held, as {@link #watchRoot} holds it; a
   * root whose walk could not read a member keeps its watch, and B keeps every file under it, as
   * an ordinary sync keeps the files it could not verify.
   */
  public boolean reconcileCommittedBootRoot(
      io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding binding,
      java.util.function.BooleanSupplier cancelled) throws IOException {
    return reconcileBootRoot(binding, cancelled, true);
  }

  /**
   * Round 7 (S8): reconciles a root the committed boot deferred, on the running server, once the
   * volume recorded for it may be back. The same pass as {@link #reconcileCommittedBootRoot}, but
   * a failure leaves the replay certificate alone: there is no cutover left for it to fence, and
   * the caller retries. Round 8: it returns {@code false}, changing nothing, while the root is not
   * proven on its recorded volume (still another volume, or nothing recorded) and when the person
   * removed the root after {@code removalMark} was taken; a removed root never gets its watch back.
   */
  public boolean reconcileReturnedBootRoot(
      io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding binding,
      long removalMark, java.util.function.BooleanSupplier cancelled) throws IOException {
    return reconcileBootRoot(binding, cancelled, false, removalMark);
  }

  private boolean reconcileBootRoot(
      io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding binding,
      java.util.function.BooleanSupplier cancelled, boolean certifying) throws IOException {
    return reconcileBootRoot(binding, cancelled, certifying, Long.MAX_VALUE);
  }

  private boolean reconcileBootRoot(
      io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding binding,
      java.util.function.BooleanSupplier cancelled, boolean certifying, long removalMark)
      throws IOException {
    Path root = binding.path().toAbsolutePath().normalize();
    try (var ignoredMutation = mutationLease()) {
      SyncDirectoryOps.checkCancelled(cancelled);
      if (searchLifecycle == null || searchLifecycle != ingestLifecycle
          || !ingestLifecycle.isAcceptingWrites()) {
        throw new IOException("Committed boot root requires a writable serving successor");
      }
      RootWatcherRegistry registry = rootWatcherRegistry;
      RootWatcherRegistry.WatchResult watched;
      if (certifying) {
        // Rounds 6 and 7 (S8): the same volume check as every other watch registration. No
        // watcher is registered on another volume at the root's path; the watch is held and
        // registered once the recorded volume is back, and the root is deferred rather than
        // failing the boot.
        if (rootOnAnotherVolume(root.toString()) != null
            || io.justsearch.configuration.PlatformPaths.isLinux() && !Files.isDirectory(root)) {
          registry.watchWhenVolumeReturns(root, binding.collection());
          return false;
        }
        watched = registry.watch(root.toString(), binding.collection());
      } else {
        // Round 8 (S8): a deferred root is reconciled only while it is still declared and proven
        // on its recorded volume; nothing here records a volume or holds a watch again.
        if (registry.removedSince(root, removalMark)
            || !rootWitness.provenOnRecordedVolume(root)) {
          return false;
        }
        watched = registry.watchUnlessRemovedSince(root, binding.collection(), removalMark);
        if (!watched.watching() && registry.removedSince(root, removalMark)) {
          return false;
        }
      }
      if (!watched.watching()) {
        throw new IOException("Committed boot root watcher registration failed: "
            + watched.errorMessage());
      }
      RootWatcherRegistry.Subscription subscription = registry.subscription(root);
      SyncDirectoryOps.RootDifference discovered;
      try {
        discovered = candidateDiscoveryOps.discoverCandidateDifference(root.toString(), true, cancelled);
      } catch (SyncDirectoryOps.UnreadableWalkMemberException unreadable) {
        if (!certifying) throw unreadable;
        log.warn("Committed boot root {} is deferred: {}", root, unreadable.getMessage());
        return false;
      }
      try (SyncDirectoryOps.RootDifference difference = discovered) {
        requireCandidateSubscription(registry, root, subscription);
        for (Path path : difference.additions()) {
          SyncDirectoryOps.checkCancelled(cancelled);
          requireCandidateSubscription(registry, root, subscription);
          difference.requireCurrentRootIdentity();
          if (!committedBootFileMatches(path, subscription.collection())
              || !jobQueue.matchesExpectedCollection(path, subscription.collection())) {
            SyncDirectoryOps.checkCancelled(cancelled);
            var liveEntry = WorkerMethvinWatcher.entryForLiveEvent(path);
            // Root convergence is content-bound even when size and mtime were preserved.
            // Reuse the queue's captured source witness so extraction cannot skip this claim.
            var capturedEntry = new JobQueue.EnqueueEntry(
                liveEntry.path(), liveEntry.sizeBytes(), liveEntry.provenance(),
                io.justsearch.indexerworker.loop.SourceContentHash.sha256(path)).withinRoot(root);
            SyncDirectoryOps.checkCancelled(cancelled);
            if (switchBufferOps.buildingGenerationForFileAdmission() != null
                || jobQueue.enqueueEntriesWithExactCollection(
                    List.of(capturedEntry),
                    subscription.collection()) != 1) {
              throw new IOException("Committed boot root admission failed");
            }
          }
        }
        for (String path : difference.deletions()) {
          SyncDirectoryOps.checkCancelled(cancelled);
          requireCandidateSubscription(registry, root, subscription);
          difference.requireCurrentRootIdentity();
          if (!confirmedAbsent(path, root)) {
            throw new IOException("Committed boot delete lacks proven absence on its recorded volume");
          }
          ingestLifecycle.indexingCoordinator().deleteByIdAndChunks(path);
          confirmedDeletionMarker.markIfAbsent(path);
        }
        SyncDirectoryOps.checkCancelled(cancelled);
        requireCandidateSubscription(registry, root, subscription);
        difference.requireCurrentRootIdentity();
      }
      return true;
    } catch (IOException | RuntimeException failure) {
      if (certifying && mutationAdmission != null) mutationAdmission.markReplayUncertain();
      if (failure instanceof IOException incomplete) throw incomplete;
      throw new IOException("Committed boot root reconciliation failed", failure);
    }
  }

  /** Final-fence certificate that this exact watcher still matches a complete B/disk snapshot. */
  public boolean committedBootRootConverged(
      io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding binding)
      throws IOException {
    return committedBootRootConverged(binding, () -> false);
  }

  public boolean committedBootRootConverged(
      io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding binding,
      java.util.function.BooleanSupplier cancelled) throws IOException {
    return bootRootConverged(binding, cancelled, true);
  }

  /**
   * Round 7 (S8): {@link #committedBootRootConverged} for a deferred root reconciled on the
   * running server; a failure leaves the replay certificate alone and the caller retries.
   */
  public boolean returnedBootRootConverged(
      io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding binding,
      java.util.function.BooleanSupplier cancelled) throws IOException {
    return bootRootConverged(binding, cancelled, false);
  }

  private boolean bootRootConverged(
      io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding binding,
      java.util.function.BooleanSupplier cancelled, boolean certifying) throws IOException {
    try {
      SyncDirectoryOps.checkCancelled(cancelled);
      Path root = binding.path().toAbsolutePath().normalize();
      RootWatcherRegistry registry = rootWatcherRegistry;
      RootWatcherRegistry.Subscription subscription = registry.subscription(root);
      requireCandidateSubscription(registry, root, subscription);
      if (!Objects.equals(subscription.collection(),
          binding.collection() == null || binding.collection().isBlank() ? null : binding.collection())) {
        throw new IOException("Committed boot root collection changed");
      }
      try (SyncDirectoryOps.RootDifference difference =
          candidateDiscoveryOps.discoverCandidateDifference(root.toString(), true, cancelled)) {
        requireCandidateSubscription(registry, root, subscription);
        difference.requireCurrentRootIdentity();
        if (!difference.deletions().isEmpty()) return false;
        // Force mode enumerates every eligible disk file. A path-only comparison would certify a
        // stale B document after an in-place write that the watcher never delivered.
        for (Path file : difference.additions()) {
          SyncDirectoryOps.checkCancelled(cancelled);
          requireCandidateSubscription(registry, root, subscription);
          difference.requireCurrentRootIdentity();
          if (!committedBootFileMatches(file, binding.collection())
              || !jobQueue.matchesExpectedCollection(file, binding.collection())) return false;
        }
        SyncDirectoryOps.checkCancelled(cancelled);
        difference.requireCurrentRootIdentity();
        return true;
      }
    } catch (IOException | RuntimeException failure) {
      if (certifying && mutationAdmission != null) mutationAdmission.markReplayUncertain();
      if (failure instanceof IOException incomplete) throw incomplete;
      throw new IOException("Committed boot root convergence failed", failure);
    }
  }

  private boolean committedBootFileMatches(Path file, String collection) throws IOException {
    String id = PathNormalizer.normalizeKey(file);
    var fields = ingestLifecycle.documentFieldOps();
    String source = fields.getDocumentField(
        id, SchemaFields.SOURCE_SHA256);
    String indexedCollection = fields.getDocumentField(
        id, SchemaFields.COLLECTION);
    return io.justsearch.indexerworker.loop.SourceContentHash.sha256(file).equals(source)
        && Objects.equals(normalizeRootCollection(collection),
            normalizeRootCollection(indexedCollection));
  }

  private static String normalizeRootCollection(String collection) {
    return collection == null || collection.isBlank() ? null : collection;
  }

  private void deleteCandidateAndMark(String path) {
    if (ingestLifecycle == null
        || ingestLifecycle == searchLifecycle
        || !ingestLifecycle.isAcceptingWrites()) {
      throw WorkerServiceException.unavailable(
          "Candidate writer became unavailable during root reconciliation");
    }
    ingestLifecycle.indexingCoordinator().deleteByIdAndChunks(path);
    confirmedDeletionMarker.markIfAbsent(path);
  }

  private void requireCandidateSubscription(
      RootWatcherRegistry expectedRegistry,
      Path root,
      RootWatcherRegistry.Subscription expectedSubscription) throws IOException {
    if (rootWatcherRegistry != expectedRegistry
        || expectedSubscription == null
        || !root.equals(expectedSubscription.root())
        || !expectedRegistry.isCurrentAndActive(expectedSubscription)) {
      String reason = rootWatcherRegistry != expectedRegistry
          ? "REGISTRY_CHANGED"
          : expectedSubscription == null
              ? "MISSING_SUBSCRIPTION"
              : !root.equals(expectedSubscription.root())
                  ? "ROOT_MISMATCH"
                  : expectedRegistry.currentnessFailure(expectedSubscription);
      log.warn("Candidate reconciliation subscription rejected (reason={})", reason);
      throw WorkerServiceException.unavailable(
          "Candidate reconciliation root subscription changed during admission");
    }
  }

  /**
   * The presence authority for one prune of {@code root}: present, listable, the registered
   * identity while watched, and the volume recorded at the root's first prune (#758).
   */
  private io.justsearch.adapters.lucene.runtime.PruneOps.PresenceGuard pruneGuard(Path root) {
    var authority = RootPresenceGuard.capture(root, registeredRootIdentity(root), rootVolumes);
    var scope = prefixScope(root.toString());
    return new io.justsearch.adapters.lucene.runtime.PruneOps.PresenceGuard() {
      @Override public boolean rootCurrent() { return authority.rootCurrent(); }
      @Override public io.justsearch.adapters.lucene.runtime.PathPresence presence(Path file) {
        return scope.excludes(file.toString())
            ? io.justsearch.adapters.lucene.runtime.PathPresence.PRESENT : authority.presence(file);
      }
    };
  }

  /**
   * The identity a watched root was registered with, or {@code null} when no registration covers
   * exactly this root. Reads the registry field at call time; it is replaceable by a setter.
   */
  private RootIdentity registeredRootIdentity(Path root) {
    return rootWatcherRegistry.registeredIdentity(root);
  }

  /**
   * The watched root that holds {@code path}, or the prefix itself when no registration covers it.
   * A prune is vouched for by this root: it must be present, listable and the registered volume.
   */
  private Path pruneRootFor(String pathPrefix) {
    Path prefix = Path.of(pathPrefix).toAbsolutePath().normalize();
    Path best = null;
    for (Path candidate = prefix; candidate != null; candidate = candidate.getParent()) {
      if (rootWatcherRegistry.subscription(candidate) != null || rootWatcherRegistry.awaitsVolume(candidate)) {
        best = candidate;
        break;
      }
    }
    return best == null ? prefix : best;
  }

  private static void requireCandidateRootIdentity(
      Path root, RootIdentity expectedRootIdentity) {
    try {
      expectedRootIdentity.requireCurrent(root);
    } catch (IOException changed) {
      throw WorkerServiceException.unavailable(
          "Candidate reconciliation root became unavailable or changed");
    }
  }

  /**
   * Tempdoc 626 §Axis-A — in-process reconcile entry point for the Worker-side watcher's
   * OVERFLOW/burst recovery. Routes through the full {@link #syncDirectory} pipeline (switch-buffer,
   * user-activity, and index-runtime guards), so the Worker watcher no longer needs the retired
   * Head watcher's cross-process {@code syncDirectory} RPC. Best-effort: outcomes are logged and
   * dropped; the periodic sync remains the backstop.
   */
  public void reconcileRoot(Path root, boolean force) {
    try { reconcileRootStrict(root, force); }
    catch (RuntimeException e) {
      log.warn("In-process reconcile failed for {} (force={}): {}", root, force,
          e.getMessage());
    }
  }

  /** Watcher-owned route: a swallowed failure would make final cutover miss this root. */
  public void reconcileRootStrict(Path root, boolean force) {
    if (root == null) {
      return;
    }
    SyncDirectoryRequest request =
        SyncDirectoryRequest.newBuilder().setRootPath(root.toString()).setForce(force).build();
    SyncDirectoryResponse value = syncDirectory(request, CallContext.none());
    if (!value.getError().isBlank()) {
      throw WorkerServiceException.unavailable("In-process reconcile was not accepted: " + value.getError());
    }
    log.debug(
        "In-process reconcile for {} (force={}): {} added, {} deleted, skipped={}",
        root.toString(), force, value.getFilesAdded(), value.getFilesDeleted(), value.getSkipped());
  }

  public QueryPendingVduResponse queryPendingVdu(
      QueryPendingVduRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
    int limit = request.getLimit();
    if (limit <= 0) {
      limit = 100;  // Default limit
    }

    log.debug("queryPendingVdu RPC called with limit: {}", limit);

    requireEnrichmentReader(ctx);

    try {
      // Query documents with vdu_status=PENDING
      List<String> docIds = ingestLifecycle.documentFieldOps().queryDocIdsByFieldOrThrow(
          SchemaFields.VDU_STATUS,
          SchemaFields.VDU_STATUS_PENDING,
          limit);

      // Get total count (may be more than returned)
      int totalCount = ingestLifecycle.indexCountOps().countByFieldOrThrow(
          SchemaFields.VDU_STATUS,
          SchemaFields.VDU_STATUS_PENDING);

      log.info("queryPendingVdu: returning {} of {} pending docs", docIds.size(), totalCount);
      requireEnrichmentReader(ctx);

      return QueryPendingVduResponse.newBuilder()
          .addAllDocIds(docIds)
          .setTotalCount(totalCount)
          .build();

    } catch (IOException e) {
      throw new WorkerServiceException(WorkerServiceException.Status.INTERNAL,
          "Pending VDU could not be read", e);
    }
    }
  }

  /** Strict procedure control read; status metrics remain a separate best-effort projection. */
  public int countPendingEmbeddings(CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      requireEnrichmentReader(ctx);
      try {
        int count = ingestLifecycle.indexCountOps().countByFieldOrThrow(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
        requireEnrichmentReader(ctx);
        return count;
      } catch (IOException e) {
        throw new WorkerServiceException(WorkerServiceException.Status.INTERNAL,
            "Pending embeddings could not be read", e);
      }
    }
  }

  private void requireEnrichmentReader(CallContext ctx) {
    if (ctx.cancelled()) {
      throw new WorkerServiceException(WorkerServiceException.Status.CANCELLED,
          "Enrichment read cancelled");
    }
    if (ingestLifecycle == null) {
      throw WorkerServiceException.unavailable("Index runtime not available");
    }
  }

  public MarkVduProcessingResponse markVduProcessing(
      MarkVduProcessingRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      synchronized (vduMutationMonitor) {
        String docId = request.getDocId();
        int maxRetries = resolveMaxRetries(request.getMaxRetries());

        log.debug("markVduProcessing RPC called for doc: {}, maxRetries: {}", docId, maxRetries);

        MarkVduProcessingResponse blank =
            blankReply(docId, markVduErrorResponse("doc_id is required"));
        if (blank != null) {
          return blank;
        }

        requireServingVduTarget();

        try {
          if (ctx.engineContext().workId().isEmpty() || request.getAttemptId().isBlank()) {
            return markVduErrorResponse("VDU requires a procedure owner and unique attempt_id");
          }
          return withFileMutationFence(() -> {
            try {
              ingestLifecycle.commitOps().maybeRefreshBlocking();
              if (ingestLifecycle.documentFieldOps().excludedProcessingIds(List.of(docId)).contains(docId)) {
                return markVduErrorResponse("Source is excluded by the current policy");
              }
              var input = ingestLifecycle.documentFieldOps().getEnrichmentInputs(List.of(docId)).get(docId);
              if (input == null) return markVduErrorResponse("Document not found: " + docId);
              if (input.fields().getOrDefault(SchemaFields.SOURCE_SHA256, "").isBlank()) {
                refreshLegacyVduSource(docId);
                return markVduErrorResponse("VDU source revision needs a targeted refresh: " + docId);
              }
              var previousClaim = vduProcessingClaims.get(docId);
              if (previousClaim != null) {
                if (vduClaimMatches(docId, previousClaim)) {
                  return markVduErrorResponse("VDU document already has a claim: " + docId);
                }
                vduProcessingClaims.remove(docId, previousClaim);
              }
              return applyMarkVduProcessing(docId, readVduRetryCount(docId), maxRetries,
                  ctx.engineContext().workId().orElseThrow(), request.getAttemptId(), input.fields());
            } catch (RuntimeException failure) {
              throw failure;
            } catch (Exception failure) {
              throw new java.io.UncheckedIOException(new IOException(failure.getMessage(), failure));
            }
          });
        } catch (WorkerServiceException failure) {
          throw failure;
        } catch (Exception e) {
          log.error("markVduProcessing failed for doc: {}", docId, e);
          return markVduErrorResponse(e instanceof java.io.UncheckedIOException io
              ? io.getCause().getMessage() : e.getMessage());
        }
      }
    }
  }

  private static int resolveMaxRetries(int requestedMaxRetries) {
    return requestedMaxRetries <= 0 ? SchemaFields.VDU_MAX_RETRIES : requestedMaxRetries;
  }

  private int readVduRetryCount(String docId) throws IOException {
    String currentCountStr = ingestLifecycle.documentFieldOps().getDocumentFieldOrThrow(docId, SchemaFields.VDU_RETRY_COUNT);
    return ParseUtils.parseIntSafe(currentCountStr, 0);
  }

  private MarkVduProcessingResponse applyMarkVduProcessing(
      String docId, int currentCount, int maxRetries, java.util.UUID procedureOwner,
      String attemptId, Map<String, String> fields) throws Exception {
    MarkVduRetryDecision decision = decideMarkVduRetry(currentCount, maxRetries);

    // Check if max retries exceeded.
    if (decision.maxRetriesExceeded()) {
      log.warn("markVduProcessing: max retries ({}) exceeded for doc: {}", maxRetries, docId);

      // Mark as FAILED due to max retries.
      Map<String, Object> updates = new HashMap<>();
      updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_FAILED);
      updates.put(SchemaFields.VDU_ENRICHMENT, VDU_MAX_RETRIES_EXCEEDED_ENRICHMENT);
      if (!ingestLifecycle.indexingCoordinator().updateDocument(docId, updates)) {
        return markVduErrorResponse("Document not found: " + docId);
      }
      vduProcessingClaims.remove(docId);
      ingestLifecycle.commitOps().commitAndTrack(CommitReason.VDU_MARK_PROCESSING);
      ingestLifecycle.commitOps().maybeRefreshBlocking();
      requireServingVduTarget();
      return markVduErrorResponse(VDU_MAX_RETRIES_EXCEEDED_ERROR);
    }

    // Increment retry count and mark as PROCESSING.
    Map<String, Object> updates = new HashMap<>();
    updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PROCESSING);
    updates.put(SchemaFields.VDU_RETRY_COUNT, String.valueOf(decision.retryCount()));

    boolean updated = ingestLifecycle.indexingCoordinator().updateDocument(docId, updates);
    if (updated) {
      Map<String, Object> expected = vduRevision(fields);
      vduProcessingClaims.put(docId,
          new VduProcessingClaim(procedureOwner, attemptId, expected, currentCount, decision.retryCount()));
      ingestLifecycle.commitOps().commitAndTrack(CommitReason.VDU_MARK_PROCESSING);
      ingestLifecycle.commitOps().maybeRefreshBlocking();
      requireServingVduTarget();
      log.debug(
          "markVduProcessing: doc {} marked PROCESSING, retry {}/{}",
          docId,
          decision.retryCount(),
          maxRetries);
      return markVduSuccessResponse(decision.retryCount());
    }
    return markVduErrorResponse("Document not found: " + docId);
  }

  /**
   * Returns a document a pass marked PROCESSING but could not attempt (the model refused generation
   * admission) to PENDING and gives back the attempt its mark counted, so temporary refusals neither
   * strand it nor spend the retries that stop a document that really fails. False when the document
   * is absent or no longer PROCESSING; nothing is changed then.
   */
  public boolean releaseVduProcessing(String docId, CallContext ctx) {
    return releaseVduProcessing(docId, "", ctx);
  }

  public boolean releaseVduProcessing(String docId, String attemptId, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      if (docId == null || docId.isBlank()) {
        throw new WorkerServiceException(WorkerServiceException.Status.INVALID_ARGUMENT, "doc_id is required");
      }
      requireEnrichmentReader(ctx);
      requireServingVduTarget();
      synchronized (vduMutationMonitor) {
        var claim = vduProcessingClaims.get(docId);
        if (claim == null || !claim.attemptId().equals(attemptId)
            || ctx.engineContext().workId().filter(claim.owner()::equals).isEmpty()) return false;
        try {
          boolean reset = withFileMutationFence(() -> {
            try { return resetVduStatusToPending(docId, claim.owner(), Set.of(attemptId)); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
          });
          if (reset) {
            ingestLifecycle.commitOps().commitAndTrack(CommitReason.VDU_RECOVERY);
            ingestLifecycle.commitOps().maybeRefreshBlocking();
          }
          requireServingVduTarget();
          return reset;
        } catch (WorkerServiceException failure) {
          throw failure;
        } catch (Exception failure) {
          throw new WorkerServiceException(WorkerServiceException.Status.INTERNAL, "VDU release failed", failure);
        }
      }
    }
  }

  private static Map<String, Object> vduRevision(Map<String, String> fields) {
    Map<String, Object> expected = new HashMap<>();
    for (String field : List.of(SchemaFields.DOC_UID, SchemaFields.SOURCE_SHA256,
        SchemaFields.CONTENT, SchemaFields.CONTENT_SHA256)) expected.put(field, fields.get(field));
    return expected;
  }

  private boolean vduClaimMatches(String docId, VduProcessingClaim claim) throws IOException {
    var input = ingestLifecycle.documentFieldOps().getEnrichmentInputs(List.of(docId)).get(docId);
    return input != null && claim.expectedRevision().equals(vduRevision(input.fields()))
        && SchemaFields.VDU_STATUS_PROCESSING.equals(input.fields().get(SchemaFields.VDU_STATUS))
        && Integer.toString(claim.claimedRetryCount()).equals(input.fields().get(SchemaFields.VDU_RETRY_COUNT));
  }

  /** Refresh just an unverifiable legacy source, with the queue's existing durable source witness. */
  public void refreshLegacyVduSource(String docId) throws IOException {
    ingestLifecycle.commitOps().maybeRefreshBlocking();
    if (ingestLifecycle.documentFieldOps().excludedProcessingIds(List.of(docId)).contains(docId)) return;
    var input = ingestLifecycle.documentFieldOps().getEnrichmentInputs(List.of(docId)).get(docId);
    if (input == null || !input.fields().getOrDefault(SchemaFields.SOURCE_SHA256, "").isBlank()) return;
    var expected = vduRevision(input.fields());
    ingestLifecycle.indexingCoordinator().updateDocumentIf(docId, expected,
        Map.of(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PENDING));
    ingestLifecycle.commitOps().commitAndTrack(CommitReason.VDU_RECOVERY);
    if (jobQueue == null) return;
    try {
      Path path = Path.of(docId);
      var stat = JobQueue.EnqueueEntry.stat(path);
      var entry = new JobQueue.EnqueueEntry(path, stat.sizeBytes(), stat.provenance(),
          io.justsearch.indexerworker.loop.SourceContentHash.sha256(path));
      if (jobQueue.enqueueEntriesWithExactCollection(List.of(entry), input.fields().get(SchemaFields.COLLECTION)) != 1) {
        throw new IOException("Legacy VDU source refresh was not admitted: " + docId);
      }
    } catch (java.nio.file.FileSystemException unavailable) {
      log.info("Legacy VDU source refresh deferred for {}: {}", docId, unavailable.getMessage());
    }
  }

  private List<String> processingDocIdsForRecovery() throws IOException {
    return ingestLifecycle.documentFieldOps().queryDocIdsByFieldOrThrow(
        SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PROCESSING, RECOVER_VDU_QUERY_LIMIT);
  }

  private boolean resetVduStatusToPending(String docId, java.util.UUID procedureOwner, Set<String> attemptIds)
      throws IOException {
    synchronized (vduMutationMonitor) {
      VduProcessingClaim claim = vduProcessingClaims.get(docId);
      if (procedureOwner != null
          && (claim == null || !procedureOwner.equals(claim.owner()) || !attemptIds.contains(claim.attemptId()))) {
        return false;
      }
      Map<String, Object> expected = procedureOwner == null ? new HashMap<>() : new HashMap<>(claim.expectedRevision());
      expected.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PROCESSING);
      Map<String, Object> updates = new HashMap<>();
      updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PENDING);
      if (procedureOwner != null) {
        // An orderly cancellation abandons this attempt without spending its retry budget.
        // Match the exact claimed count as well as PROCESSING before returning that budget.
        expected.put(SchemaFields.VDU_RETRY_COUNT, String.valueOf(claim.claimedRetryCount()));
        updates.put(SchemaFields.VDU_RETRY_COUNT, String.valueOf(claim.previousRetryCount()));
      }
      boolean reset = ingestLifecycle.indexingCoordinator().updateDocumentIf(
          docId, expected, updates);
      // A lost comparison means the claimed state changed or a terminal update/removal won.
      // Retire the selected claim so its abandoned result cannot subsequently replace text.
      vduProcessingClaims.remove(docId);
      // A lost comparison on a present document needs no reset and is resolved. An absent
      // selected document stays a miss, so a replayed recovery keeps its row for a later retry.
      if (!reset && !docId.equals(ingestLifecycle.documentFieldOps()
          .getDocumentFieldOrThrow(docId, SchemaFields.DOC_ID))) {
        throw new IllegalStateException("Selected VDU document was not recovered: " + docId);
      }
      return reset;
    }
  }

  @FunctionalInterface
  interface VduProcessingResetOp {
    boolean resetToPending(String docId) throws Exception;
  }

  record VduRecoveryResult(int recovered, Exception failure) {}

  static VduRecoveryResult recoverProcessingDocsWithResetOp(
      List<String> processingDocIds, VduProcessingResetOp resetOp) {
    int recovered = 0;
    Exception firstFailure = null;
    for (String docId : processingDocIds) {
      try {
        // Scoped cancellation returns the retry budget; startup recovery keeps the spent count.
        if (resetOp.resetToPending(docId)) {
          recovered++;
        }
      } catch (Exception e) {
        log.warn("Failed to recover doc: {}", docId, e);
        if (firstFailure == null) firstFailure = e;
      }
    }
    return new VduRecoveryResult(recovered, firstFailure);
  }

  private VduRecoveryResult recoverProcessingDocs(
      List<String> processingDocIds, java.util.UUID procedureOwner, Set<String> attemptIds) {
    // The conditional RMW already compares source/UID/status/retry under the coordinator lock.
    // Keep the same monitor order as claims/results; taking the file fence before this monitor
    // would invert their monitor -> fence order during concurrent cancellation.
    return recoverProcessingDocsWithResetOp(
        processingDocIds, docId -> resetVduStatusToPending(docId, procedureOwner, attemptIds));
  }

  private static java.util.UUID recoveryProcedureOwner(
      RecoverVduProcessingRequest request, CallContext ctx) {
    if (request.getProcedureOwner().isEmpty()) return null;
    final java.util.UUID owner;
    try { owner = java.util.UUID.fromString(request.getProcedureOwner()); }
    catch (IllegalArgumentException malformed) {
      throw WorkerServiceException.invalidArgument("VDU recovery procedure owner is invalid");
    }
    if (ctx.engineContext().workId().filter(owner::equals).isEmpty()) {
      throw WorkerServiceException.failedPrecondition(
          "VDU recovery must retain its admitted procedure's authority");
    }
    return owner;
  }

  public RecoverVduProcessingResponse recoverVduProcessing(
      RecoverVduProcessingRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
    log.info("recoverVduProcessing RPC called");

    requireEnrichmentReader(ctx);

    requireServingVduTarget();
    var procedureOwner = recoveryProcedureOwner(request, ctx);

    try {
      List<String> processingDocIds = processingDocIdsForRecovery();
      requireServingVduTarget();

      if (processingDocIds.isEmpty()) {
        log.info("recoverVduProcessing: no stuck documents found");
        return recoverVduCountResponse(0);
      }

      log.info("recoverVduProcessing: found {} stuck documents", processingDocIds.size());

      VduRecoveryResult result = recoverProcessingDocs(processingDocIds, procedureOwner, new java.util.HashSet<>(request.getAttemptIdsList()));
      int recovered = result.recovered();

      if (recovered > 0) {
        ingestLifecycle.commitOps().commitAndTrack(CommitReason.VDU_RECOVERY);
        ingestLifecycle.commitOps().maybeRefreshBlocking();
      }

      log.info("recoverVduProcessing: recovered {} of {} documents", recovered, processingDocIds.size());

      requireServingVduTarget();
      if (result.failure() != null) {
        throw new IllegalStateException("Selected VDU recovery is incomplete", result.failure());
      }
      return recoverVduCountResponse(recovered);

    } catch (WorkerServiceException failure) {
      throw failure;
    } catch (Exception e) {
      log.error("recoverVduProcessing failed", e);
      throw new WorkerServiceException(
          WorkerServiceException.Status.INTERNAL, "VDU recovery failed", e);
    }
    }
  }

  public UpdatePathsResponse updateDocumentPaths(
      UpdatePathsRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
    log.info("updateDocumentPaths RPC called with {} mappings", request.getMappingsCount());

    if (request.getMappingsCount() == 0) {
      return UpdatePathsResponse.newBuilder().setUpdatedCount(0).build();
    }

    // Rename is a two-authority mutation (identity store plus Lucene). Unlike simple path
    // upserts/deletes, the existing switch buffer cannot replay both halves atomically. Refuse
    // throughout a candidate build before touching either authority and let the caller retry.
    if (switchBufferOps.buildingGenerationForFileAdmission() != null) {
      throw IngestSwitchBufferOps.switchingUnavailable();
    }

    UpdatePathsResponse unavailable =
        indexRuntimeUnavailableReply(
            "updateDocumentPaths", UpdatePathsResponse.newBuilder().setUpdatedCount(0).build());
    if (unavailable != null) {
      return unavailable;
    }

    try {
      int updatedCount = 0;
      List<String> failedPaths = new ArrayList<>();

      for (PathMapping mapping : request.getMappingsList()) {
        String rawOldPath = mapping.getOldPath();
        String rawNewPath = mapping.getNewPath();
        if (rawOldPath.isBlank() || rawNewPath.isBlank()) {
          failedPaths.add(rawOldPath);
          continue;
        }
        Path destination = Path.of(rawNewPath);
        if (isUserExcluded(destination, destination)) {
          failedPaths.add(rawOldPath);
          continue;
        }
        String oldPath = PathNormalizer.normalizeKey(Path.of(rawOldPath));
        String newPath = PathNormalizer.normalizeKey(Path.of(rawNewPath));

        if (oldPath.equals(newPath)) { updatedCount++; continue; }
        String oldPathHash =
            io.justsearch.indexerworker.identity.DocumentIdentityStore.pathHash(oldPath);
        String newPathHash =
            io.justsearch.indexerworker.identity.DocumentIdentityStore.pathHash(newPath);
        // Policy, identity and Lucene publication share the deletion fence. A keep-out installed
        // after the optimistic check above must prevent BOTH indexed authorities from moving.
        boolean settled = withFileMutationFence(() -> {
          // The physical file operation can finish after its caller's bounded index update
          // returns. Never let that surviving old call recreate a path after a later forget.
          if (ctx.cancelled() || ctx != CallContext.none()
              && System.nanoTime() - ctx.deadlineNanos() >= 0) return false;
          if (isUserExcluded(destination, destination)) return false;
          var identityRekey = documentIdentityStore.rekey(
              oldPathHash, newPathHash, System.currentTimeMillis());
          boolean rowsSettled = moveRowsUnderFence(oldPath, newPath);
          return identityRekey
              != io.justsearch.indexerworker.identity.DocumentIdentityStore.RekeyResult.NOT_FOUND
              || rowsSettled;
        });
        // Green may not contain the document yet while Blue still serves it. Moving the durable
        // identity is therefore a successful rename even when this generation has no Lucene row.
        // Store-first is deliberate: if Lucene throws after the move, the RPC fails and retry or
        // boot reconciliation converges from the store-authoritative uid without ever re-minting.
        if (settled) {
          updatedCount++;
        } else {
          failedPaths.add(mapping.getOldPath());
        }
      }

      ingestLifecycle.commitOps().commitAndTrack(CommitReason.GRPC_UPDATE_PATHS);
      ingestLifecycle.commitOps().maybeRefreshBlocking();

      log.info(
          "updateDocumentPaths: updated {} paths, {} failed",
          updatedCount,
          failedPaths.size());

      return UpdatePathsResponse.newBuilder()
          .setUpdatedCount(updatedCount)
          .addAllFailedPaths(failedPaths)
          .build();

    } catch (Exception e) {
      log.error("updateDocumentPaths failed", e);
      throw WorkerServiceException.internal(
          "Failed to update document paths: " + e.getMessage());
    }
    }
  }

  public ListFailedJobsResponse listFailedJobs(
      ListFailedJobsRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      int limit = request.getLimit();
      List<JobQueue.FailedJobInfo> jobs = jobQueue.listFailedJobs(limit);
      long totalCount = jobQueue.failureSummary().failedCount();

      ListFailedJobsResponse.Builder resp =
          ListFailedJobsResponse.newBuilder().setTotalCount(totalCount);
      for (JobQueue.FailedJobInfo job : jobs) {
        resp.addJobs(
            FailedJob.newBuilder()
                .setPath(job.path() != null ? job.path() : "")
                .setErrorMessage(job.errorMessage() != null ? job.errorMessage() : "")
                .setAttempts(job.attempts())
                .setLastUpdatedMs(job.lastUpdatedMs())
                .setCollection(job.collection() != null ? job.collection() : "")
                .setState(job.state() != null ? job.state() : "")
                .setScanId(job.scanId() != null ? job.scanId() : "")
                .build());
      }
      return resp.build();
    }
  }

  public io.justsearch.ipc.CountJobsByPathPrefixResponse countJobsByPathPrefix(
      io.justsearch.ipc.CountJobsByPathPrefixRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      JobQueue.JobStateCounts counts = jobQueue.countByPathPrefix(request.getPathPrefix());
      io.justsearch.ipc.IndexingJobCounts wire =
          io.justsearch.ipc.IndexingJobCounts.newBuilder()
              .setPendingCount(counts.pendingCount())
              .setProcessingCount(counts.processingCount())
              .setFailedCount(counts.failedCount())
              .build();
      return io.justsearch.ipc.CountJobsByPathPrefixResponse.newBuilder()
          .setCounts(wire)
          .setCoverage(rootCoverage(request.getPathPrefix()))
          .build();
    }
  }

  /**
   * Per-root enrichment coverage for {@link #countJobsByPathPrefix} (tempdoc 813 §1c). The queue
   * counts above come from SQLite and are always available; these come from the Lucene index, so
   * an absent index runtime degrades this leg to all-zero rather than failing the whole response —
   * the Library row still gets its truthful in-flight/failed numbers.
   */
  private io.justsearch.ipc.RootCoverageCounts rootCoverage(String pathPrefix) {
    io.justsearch.adapters.lucene.runtime.IndexCountOps countOps =
        ingestLifecycle == null ? null : ingestLifecycle.indexCountOps();
    if (countOps == null) {
      return io.justsearch.ipc.RootCoverageCounts.getDefaultInstance();
    }
    try {
      io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RootCoverageCounts c =
          countOps.queryRootCoverageCounts(pathPrefix);
      return io.justsearch.ipc.RootCoverageCounts.newBuilder()
          .setParentDocsTotalEmbedding(c.parentDocsTotalEmbedding())
          .setParentDocsSettledEmbedding(c.parentDocsSettledEmbedding())
          .setParentDocsTotalSplade(c.parentDocsTotalSplade())
          .setParentDocsSettledSplade(c.parentDocsSettledSplade())
          .setParentDocsTotalNer(c.parentDocsTotalNer())
          .setParentDocsSettledNer(c.parentDocsSettledNer())
          .setChunkDocsTotal(c.chunkDocsTotal())
          .setChunkDocsSettled(c.chunkDocsSettled())
          .build();
    } catch (RuntimeException e) {
      log.warn("countJobsByPathPrefix: root coverage unavailable: {}", e.getMessage());
      return io.justsearch.ipc.RootCoverageCounts.getDefaultInstance();
    }
  }

  public ListFailedJobsResponse listFailedJobsByPathPrefix(
      io.justsearch.ipc.ListFailedJobsByPathPrefixRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      List<JobQueue.FailedJobInfo> jobs =
          jobQueue.listFailedJobsByPathPrefix(request.getPathPrefix(), request.getLimit());
      ListFailedJobsResponse.Builder resp =
          ListFailedJobsResponse.newBuilder().setTotalCount(jobs.size());
      for (JobQueue.FailedJobInfo job : jobs) {
        resp.addJobs(
            FailedJob.newBuilder()
                .setPath(job.path() != null ? job.path() : "")
                .setErrorMessage(job.errorMessage() != null ? job.errorMessage() : "")
                .setAttempts(job.attempts())
                .setLastUpdatedMs(job.lastUpdatedMs())
                .setCollection(job.collection() != null ? job.collection() : "")
                .setState(job.state() != null ? job.state() : "")
                .setScanId(job.scanId() != null ? job.scanId() : "")
                .build());
      }
      return resp.build();
    }
  }

  public ClearFailedJobsResponse clearFailedJobs(
      ClearFailedJobsRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      int deleted = jobQueue.clearFailedJobs();
      return ClearFailedJobsResponse.newBuilder().setDeletedCount(deleted).build();
    }
  }

  public io.justsearch.ipc.SessionPoliciesResponse getSessionPolicies(
      io.justsearch.ipc.SessionPoliciesRequest request, CallContext ctx) {
    io.justsearch.ipc.SessionPoliciesResponse.Builder resp =
        io.justsearch.ipc.SessionPoliciesResponse.newBuilder();
    Supplier<io.justsearch.ort.PolicySnapshot> supplier = policySnapshotSupplier;
    io.justsearch.ort.PolicySnapshot snap = supplier != null ? supplier.get() : null;
    if (snap == null) {
      resp.setConfigStatus("surface-unavailable");
      return resp.build();
    }
    try {
      var serialized = snap.toJson();
      resp.setConfigStatus("ok");
      resp.setRuntimePolicyJson(serialized.runtime());
      resp.putAllModelPoliciesJson(serialized.models());
      return resp.build();
    } catch (RuntimeException e) {
      log.warn("getSessionPolicies: JSON serialization failed", e);
      throw WorkerServiceException.internal(
          "policy serialization failed: " + e.getMessage());
    }
  }

  /**
   * Tempdoc 406 admin endpoint — triggers a holder swap on the ingest runtime.
   * Calls {@code KnowledgeServer.swapRuntime(...)} via the wired
   * {@code runtimeReloadTrigger}; returns the swap duration in ms.
   */
  public io.justsearch.ipc.ReloadRuntimeResponse reloadRuntime(
      io.justsearch.ipc.ReloadRuntimeRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      java.util.function.Function<SwapReason, Long> trigger = this.runtimeReloadTrigger;
      if (trigger == null) {
        log.warn("reloadRuntime: trigger not wired; rejecting with FAILED_PRECONDITION");
        throw WorkerServiceException.failedPrecondition(
            "Runtime reload trigger not wired on this Worker");
      }
      String wire = request.getReason();
      SwapReason reason =
          wire.isBlank() ? SwapReason.ADMIN_TRIGGERED : SwapReason.fromWire(wire);
      log.info("reloadRuntime: starting swap (reason={})", reason.wireValue());
      long durationMs = trigger.apply(reason);
      log.info(
          "reloadRuntime: swap complete in {}ms (reason={})", durationMs, reason.wireValue());
      return io.justsearch.ipc.ReloadRuntimeResponse.newBuilder()
          .setSwapDurationMs(durationMs)
          .build();
    } catch (WorkerServiceException e) {
      // Must precede the catch-all: the unwired-trigger refusal above throws FAILED_PRECONDITION
      // from inside this try-with-resources, and the catch-all would relabel it INTERNAL
      // "Reload failed: ..." — the Head distinguishes "reload unavailable here" from "reload
      // broke" by exactly that code.
      throw e;
    } catch (Exception e) {
      log.error("reloadRuntime failed", e);
      throw WorkerServiceException.internal("Reload failed: " + e.getMessage());
    }
  }

  public io.justsearch.ipc.RecentIngestionEventsResponse recentIngestionEvents(
      io.justsearch.ipc.RecentIngestionEventsRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      int limit = request.getLimit();
      List<JobQueue.IngestionEventView> events = jobQueue.recentIngestionEvents(limit);
      io.justsearch.ipc.RecentIngestionEventsResponse.Builder resp =
          io.justsearch.ipc.RecentIngestionEventsResponse.newBuilder();
      for (JobQueue.IngestionEventView event : events) {
        io.justsearch.ipc.IngestionEvent.Builder b =
            io.justsearch.ipc.IngestionEvent.newBuilder()
                .setId(event.id())
                .setPathHash(event.pathHash() != null ? event.pathHash() : "")
                .setCollection(event.collection() != null ? event.collection() : "")
                .setOutcomeClass(event.outcomeClass() != null ? event.outcomeClass() : "")
                .setReasonCode(event.reasonCode() != null ? event.reasonCode() : "")
                .setRetryPolicy(event.retryPolicy() != null ? event.retryPolicy() : "")
                .setDiagnosticSummary(
                    event.diagnosticSummary() != null ? event.diagnosticSummary() : "")
                .setObservedAtMs(event.observedAtMs())
                .setSourceKind(event.sourceKind() != null ? event.sourceKind() : "")
                .setArtifactStatus(event.artifactStatus() != null ? event.artifactStatus() : "")
                .setPolicyId(event.policyId() != null ? event.policyId() : "")
                .setParserId(event.parserId() != null ? event.parserId() : "");
        if (event.sourceSizeBytes() != null) b.setSourceSizeBytes(event.sourceSizeBytes());
        if (event.sourceModifiedAtMs() != null) b.setSourceModifiedAtMs(event.sourceModifiedAtMs());
        resp.addEvents(b.build());
      }
      return resp.build();
    }
  }

  public io.justsearch.ipc.IngestionOutcomeSummaryResponse ingestionOutcomeSummary(
      io.justsearch.ipc.IngestionOutcomeSummaryRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      List<JobQueue.IngestionOutcomeSummary> rollups =
          jobQueue.ingestionOutcomeSummary(request.getSinceMs());
      io.justsearch.ipc.IngestionOutcomeSummaryResponse.Builder resp =
          io.justsearch.ipc.IngestionOutcomeSummaryResponse.newBuilder();
      for (JobQueue.IngestionOutcomeSummary rollup : rollups) {
        resp.addRollups(
            io.justsearch.ipc.IngestionOutcomeRollup.newBuilder()
                .setOutcomeClass(rollup.outcomeClass() != null ? rollup.outcomeClass() : "")
                .setReasonCode(rollup.reasonCode() != null ? rollup.reasonCode() : "")
                .setRetryPolicy(rollup.retryPolicy() != null ? rollup.retryPolicy() : "")
                .setCount(rollup.count())
                .setLastObservedAtMs(rollup.lastObservedAtMs())
                .build());
      }
      return resp.build();
    }
  }

  // ==================== Tempdoc 418 Phase A/B: Worker-owned filesystem traversal ====================

  /**
   * Phase B — replaces the registry-only Phase A scaffolding with one backed by a
   * {@link WorkerMethvinWatcher}. Called by {@code DefaultWorkerAppServices} after the
   * watcher is constructed so all subsequent {@code WatchRoot}/{@code UnwatchRoot} RPCs
   * route real filesystem events into {@link JobQueue}. Must be invoked before any client
   * issues a {@code WatchRoot}.
   */
  public void setRootWatcherRegistry(RootWatcherRegistry registry) {
    this.rootWatcherRegistry = Objects.requireNonNull(registry, "registry");
  }

  /**
   * Tempdoc 419 / T5.3 (ADR-0028) — wires the scoped reverse-lookup store. Production
   * composition (DefaultWorkerAppServices) calls this with the SqlitePathResolutionStore
   * from InfraContext. Defaults to {@link io.justsearch.indexerworker.path.PathResolutionStore#NOOP}.
   */
  public void setPathResolutionStore(io.justsearch.indexerworker.path.PathResolutionStore store) {
    this.pathResolutionStore =
        store == null ? io.justsearch.indexerworker.path.PathResolutionStore.NOOP : store;
  }

  /** Wires the durable authority that preserves document identity across path renames. */
  public void setDocumentIdentityStore(
      io.justsearch.indexerworker.identity.DocumentIdentityStore store) {
    this.documentIdentityStore =
        store == null
            ? io.justsearch.indexerworker.identity.DocumentIdentityStore.UNAVAILABLE
            : store;
  }

  public io.justsearch.ipc.LookupPathByHashResponse lookupPathByHash(
      io.justsearch.ipc.LookupPathByHashRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      String pathHash = request.getPathHash();
      if (pathHash.isBlank()) {
        throw WorkerServiceException.invalidArgument(
            "LookupPathByHashRequest.path_hash is required");
      }
      io.justsearch.ipc.LookupPathByHashResponse.Builder resp =
          io.justsearch.ipc.LookupPathByHashResponse.newBuilder();
      pathResolutionStore
          .lookup(pathHash)
          .ifPresentOrElse(
              r ->
                  resp.setFound(true)
                      .setPath(r.normalizedPath() != null ? r.normalizedPath() : "")
                      .setLastSeenAtMs(r.lastSeenAtMs())
                      .setRemovedAtMs(r.removedAtMs() != null ? r.removedAtMs() : 0L),
              () -> resp.setFound(false));
      return resp.build();
    }
  }

  /**
   * Server-streaming: emits every {@code ScanRootProgress} frame through {@code sink} and returns
   * when the scan is over. The caller (the transport adapter while the wire is up) completes the
   * stream — this method never does.
   */
  public void scanRoot(
      io.justsearch.ipc.ScanRootRequest request,
      java.util.function.Consumer<io.justsearch.ipc.ScanRootProgress> sink,
      CallContext ctx) {
    scanRoot(request, sink, ctx, null);
  }

  /** Captured bulk records all source identities before any member may be claimed. */
  public enum RecordedScanMode { STREAMING, CAPTURED }

  /** Java-only projection of one accepted root; the wire schema never carries operation identity. */
  public record RecordedRootScan(io.justsearch.ipc.ScanRootRequest request,
      String operationKey, long epoch, String expectedGeneration, boolean singleFile, List<Path> excludedSubtrees,
      RecordedScanMode mode) {
    public RecordedRootScan {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(mode, "mode");
      if (mode == RecordedScanMode.CAPTURED
          && (singleFile || request.getMode() != io.justsearch.ipc.ScanMode.SCAN_MODE_FORCE_REINDEX)) {
        throw new IllegalArgumentException("Captured bulk requires forced directory traversal");
      }
      if (operationKey == null || operationKey.isBlank() || operationKey.length() > 256 || epoch < 1) {
        throw new IllegalArgumentException("Invalid recorded scan membership");
      }
      if (expectedGeneration == null || expectedGeneration.isBlank() || expectedGeneration.length() > 128) {
        throw new IllegalArgumentException("Invalid recorded generation");
      }
      Path root = Path.of(request.getRootPath());
      if (!root.isAbsolute() || !root.normalize().equals(root) || root.toString().length() > 32768) {
        throw new IllegalArgumentException("Recorded root must be absolute and normalized");
      }
      excludedSubtrees = List.copyOf(excludedSubtrees);
      if (excludedSubtrees.size() > 1024 || request.getExcludeGlobsCount() > 1024) {
        throw new IllegalArgumentException("Recorded root policy exceeds its bound");
      }
      for (Path subtree : excludedSubtrees) {
        if (!subtree.isAbsolute() || !subtree.normalize().equals(subtree)
            || subtree.toString().length() > 32768 || subtree.equals(root) || !subtree.startsWith(root)) {
          throw new IllegalArgumentException("Excluded subtree must be a normalized descendant");
        }
      }
      for (String glob : request.getExcludeGlobsList()) {
        if (glob.isBlank() || glob.length() > 4096) throw new IllegalArgumentException("Invalid recorded root glob");
      }
    }

    public RecordedRootScan(io.justsearch.ipc.ScanRootRequest request, String operationKey, long epoch,
        String expectedGeneration, boolean singleFile, List<Path> excludedSubtrees) {
      this(request, operationKey, epoch, expectedGeneration, singleFile, excludedSubtrees, RecordedScanMode.STREAMING);
    }
  }

  /** Synchronous actual enumeration; the Engine supplies the admitted owner and bounded executor. */
  public void scanRecordedRoot(RecordedRootScan recorded,
      java.util.function.Consumer<io.justsearch.ipc.ScanRootProgress> sink, CallContext ctx) {
    Objects.requireNonNull(recorded, "recorded");
    if (ctx.cancelled()) {
      throw new WorkerServiceException(WorkerServiceException.Status.CANCELLED, "Generation capture cancelled");
    }
    if (!awaitRecordedGenerationBeforeTraversal(recorded, ctx)) return;
    scanRoot(recorded.request(), sink, ctx, recorded);
  }

  private boolean awaitRecordedGenerationBeforeTraversal(RecordedRootScan recorded, CallContext ctx) {
    // The existing Engine root producer owns this wait, its deadline and actual-exit completion.
    // No traversal or queue admission has begun. Never replay scanRoot or a completed batch here.
    while (!ctx.cancelled()) {
      try {
        validateRecordedGeneration(recorded, ctx);
        return true;
      } catch (WorkerServiceException unavailable) {
        if (ctx.cancelled()) return false;
        if (unavailable.status() != WorkerServiceException.Status.UNAVAILABLE) throw unavailable;
        Throwable cause = unavailable.getCause();
        if (cause instanceof io.justsearch.configuration.persistence.ContendedFileReads.FileReadContendedException) {
          // The strict read already spent its bounded, interruptible lock wait.
          continue;
        }
        if (!(cause instanceof java.nio.file.NoSuchFileException)) throw unavailable;
        // The generation owner rotates current to .prev before publishing its completed temp.
        // Reread only current; never authorize from .prev, and do not spin if absence persists.
        try {
          Thread.sleep(10);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          if (ctx.cancelled()) return false;
          throw new WorkerServiceException(WorkerServiceException.Status.CANCELLED,
              "Generation preflight interrupted", interrupted);
        }
      }
    }
    return false;
  }

  private void validateRecordedGeneration(RecordedRootScan recorded, CallContext ctx) {
    String source = recorded.mode() == RecordedScanMode.CAPTURED
        ? captureRebuildGeneration(ctx) : captureServingGeneration(ctx);
    if (!recorded.expectedGeneration().equals(source)) {
      throw WorkerServiceException.unavailable("RECORDED_GENERATION_CHANGED");
    }
  }

  private void scanRoot(io.justsearch.ipc.ScanRootRequest request,
      java.util.function.Consumer<io.justsearch.ipc.ScanRootProgress> sink,
      CallContext ctx, RecordedRootScan recorded) {
    // Tempdoc 419 / T2 — Allocate the scanId at RPC entry. The same value is stamped on every
    // emitted ScanRootProgress event so SSE consumers (T4) can subscribe by scanId, and so
    // log entries from this scan correlate via MDC.
    String scanId = recorded == null ? java.util.UUID.randomUUID().toString() : recorded.operationKey();
    try (var ignored = openRequestMdc(ctx);
        var ignored2 = MdcContext.scan(scanId);
        var ignoredMutation = mutationLease()) {
      String rootPath = request.getRootPath();
      Object collectionAtStart = collectionRemoval(request.getCollection());
      boolean untagged = request.getCollection().isBlank();
      var removalsAtStart = untagged ? Map.copyOf(collectionRemovals) : Map.of();
      if (rootPath.isBlank()) {
        throw WorkerServiceException.invalidArgument("ScanRootRequest.root_path is required");
      }
      Path root;
      try {
        root = Path.of(rootPath).toAbsolutePath().normalize();
      } catch (InvalidPathException e) {
        throw WorkerServiceException.invalidArgument(
            "ScanRootRequest.root_path is not a valid path: " + e.getMessage());
      }
      boolean singleFile = recorded != null && recorded.singleFile();
      if (request.getRootAdded()) {
        // Round 8b (decision 1): a deliberate add (the person's add of this root, or an explicit
        // ingest request of a folder or a file) alone records a volume.
        rootWitness.recordDeliberateAdd(root, singleFile);
      }
      // Round 8b (decision 2): no walk records. A walk refuses its folder unless a volume recorded
      // for its root, or at or above it, holds; one that cannot tell its root (before the roots
      // are registered again after a restart) is deferred and deletes nothing.
      boolean refused = !singleFile
          && (request.getRootAdded()
              ? rootWitness.refusesRoot(root)
              : rootWitness.refusesWalk(root));
      if (refused) {
        // #758: a drive letter that now holds another volume, or a
        // root whose volume cannot be told, shows the root-level unavailable state; its files are
        // not taken for the original's and nothing of the original's is touched. A root that is
        // itself a link walks as before.
        sink.accept(
            io.justsearch.ipc.ScanRootProgress.newBuilder()
                .setComplete(true)
                .setTerminalReasonCode(refusalReason(root, ""))
                .setScanId(scanId)
                .build());
        return;
      }
      if (!singleFile) {
        resumeWatchAwaitingVolume(root);
      }
      WorkerScanOps.ScanMode mode =
          switch (request.getMode()) {
            case SCAN_MODE_RESCAN -> WorkerScanOps.ScanMode.RESCAN;
            case SCAN_MODE_FORCE_REINDEX -> WorkerScanOps.ScanMode.FORCE_REINDEX;
            default -> WorkerScanOps.ScanMode.INITIAL;
          };
      WorkerScanOps.ScanRequest scanRequest =
          new WorkerScanOps.ScanRequest(
              root, request.getCollection(), mode, request.getExcludeGlobsList(), scanId, ctx.provenance(),
              recorded == null ? null : recorded.epoch(),
              recorded == null ? List.of() : recorded.excludedSubtrees(), recorded != null && recorded.singleFile(),
              recorded == null ? RecordedScanMode.STREAMING : recorded.mode());
      // Tempdoc 418 B-H.3 — Worker owns backpressure + cancellation. The call's cancellation
      // signal lets WorkerScanOps stop walking when the caller drops the stream (e.g.,
      // RootLifecycleOps removes the watched root mid-scan).
      java.util.function.LongSupplier queueDepth = jobQueue::queueDepth;
      java.util.function.BooleanSupplier isCancelled = () -> ctx.cancelled()
          || collectionAtStart != collectionRemoval(request.getCollection())
          || untagged && !removalsAtStart.equals(collectionRemovals);
      try {
        // Tempdoc 821 §3-C3 — a FORCE_REINDEX scan marks its admitted paths through the SAME
        // forced-path set submitBatch's force_reindex flag feeds (see #submitBatch above).
        // The marks go through the same sink as submitBatch's, so a forced scan admitted by a
        // read-only (deferred) service set reaches the loop its writer upgrade creates.
        var scanner = new WorkerScanOps(jobQueue, queueDepth, isCancelled, paths -> forcedPathSink.accept(paths),
            () -> validateRecordedGeneration(Objects.requireNonNull(recorded), ctx),
            switchBufferOps::buildingGenerationForFileAdmission);
        scanner.setAdmissionFence(admit -> withFileMutationFence(() ->
            isCancelled.getAsBoolean() ? 0 : admit.getAsInt()));
        scanner.scan(scanRequest, sink);
      } catch (IOException e) {
        log.warn("ScanRoot walk failed for {}: {}", rootPath, e.getMessage());
        sink.accept(
            io.justsearch.ipc.ScanRootProgress.newBuilder()
                .setComplete(true)
                .setTerminalReasonCode("IO_ERROR")
                .setScanId(scanId)
                .build());
      }
    }
  }

  public io.justsearch.ipc.WatchRootResponse watchRoot(
      io.justsearch.ipc.WatchRootRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      synchronized (rootWatcherRegistry.current()) {
        String rootPath = request.getRootPath();
        if (rootPath.isBlank()) {
          throw WorkerServiceException.invalidArgument("WatchRootRequest.root_path is required");
        }
        // A registration change can leave an event gap after an earlier root scan even if that
        // scan's incarnation check succeeded. Keep the existing final cutover fence conservative.
        markReplayUncertainForRegistrationDuringMigration(rootPath);
        try {
          // #921: no watcher is registered on another volume at the
          // root's path, so its creates and changes are never indexed into the root. The watch is
          // held and registered once a walk or sync finds the recorded volume back. Only the
          // person's add records the root's volume; a root with none recorded
          // is held the same way.
          if (request.getRootAdded()) {
            Path added = parsedRoot(rootPath);
            if (added != null) {
              rootWitness.recordAdded(added);
            }
          }
          Path held = rootOnAnotherVolume(rootPath);
          Path parsed = parsedRoot(rootPath);
          if (held == null && io.justsearch.configuration.PlatformPaths.isLinux()
              && parsed != null && !Files.isDirectory(parsed)) held = parsed;
          RootWatcherRegistry.WatchResult result =
              held != null
                  ? rootWatcherRegistry.watchWhenVolumeReturns(held, request.getCollection())
                  : rootWatcherRegistry.watch(rootPath, request.getCollection());
          if (result.watching()) {
            // A watch that reaches a service which cannot apply receipts (a deferred view whose
            // writer upgrade already ran its reconcile before this subscription existed) queues
            // them for whichever writer is current when the watcher's reconcile thread runs them.
            if (canApplyWatcherReceipts()) reconcilePendingWatcherRoots();
            else rootWatcherRegistry.applyPendingReceiptsLater();
          }
          return io.justsearch.ipc.WatchRootResponse.newBuilder()
              .setWatching(result.watching())
              .setErrorMessage(result.errorMessage() == null ? "" : result.errorMessage())
              .build();
        } finally {
          // A migration can begin after the first check but before registry mutation completes.
          // Recheck while this request still holds its mutation lease so the final fence sees it.
          markReplayUncertainForRegistrationDuringMigration(rootPath);
        }
      }
    }
  }

  public io.justsearch.ipc.UnwatchRootResponse unwatchRoot(
      io.justsearch.ipc.UnwatchRootRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      synchronized (rootWatcherRegistry.current()) {
        markReplayUncertainForWatchChangeDuringMigration();
        try {
          if (request.getClearAllRoots()) {
            var roots = new java.util.HashSet<>(rootWatcherRegistry.current().declaredRoots());
            try {
              roots.addAll(io.justsearch.configuration.persistence.WatchedRootsFormat.declaredPaths(declaredRootsDirectory));
            } catch (IOException unreadable) {
              throw WorkerServiceException.unavailable("Watched-root declarations are unreadable");
            }
            if (!roots.isEmpty() && (ingestLifecycle == null || !ingestLifecycle.isAcceptingWrites())) {
              throw WorkerServiceException.unavailable("Root cleanup requires a writable index");
            }
            // Drain issued watcher events before taking the writer fence they may need.
            for (Path root : roots) forgetWatchRoot(root.toString());
            return withFileMutationFence(() -> {
              for (Path root : roots) {
                var scope = new PathPrefixScope(root.toString(), List.of());
                journalAndProjectPrefixDeleteToServing(scope);
                deletePrefix(ingestLifecycle.indexingCoordinator(), scope);
                if (jobQueue.deleteByPathPrefix(scope.prefix()) < 0) {
                  throw WorkerServiceException.unavailable("Root queue cleanup failed");
                }
                ingestLifecycle.commitOps().commitAndTrack(CommitReason.GRPC_DELETE_BY_PATH);
              }
              return io.justsearch.ipc.UnwatchRootResponse.newBuilder()
                  .setUnwatched(!roots.isEmpty()).setRootsCleared(true).build();
            });
          }
          boolean removed = forgetWatchRoot(request.getRootPath());
          return io.justsearch.ipc.UnwatchRootResponse.newBuilder().setUnwatched(removed).build();
        } finally {
          markReplayUncertainForWatchChangeDuringMigration();
        }
      }
    }
  }

  private boolean forgetWatchRoot(String rootPath) {
    if (rootPath == null || rootPath.isBlank()) {
      throw WorkerServiceException.invalidArgument("UnwatchRootRequest.root_path is required");
    }
    boolean removed = rootWatcherRegistry.unwatch(rootPath);
    Path removedRoot = Path.of(rootPath).toAbsolutePath().normalize();
    bootExclusionRoots = bootExclusionRoots.stream().filter(root -> !root.equals(removedRoot)).toList();
    forgetRemovedRoot(rootPath);
    if (jobQueue instanceof SwitchBufferCapableQueue journal) {
      String key = "watcher-reconcile:" + Path.of(rootPath).toAbsolutePath().normalize();
      var obsolete = journal.listSwitchBufferOpsStrict().stream()
          .filter(receipt -> (key.equals(receipt.key()) && WATCHER_RECONCILE.equals(receipt.op()))
              || (io.justsearch.configuration.PlatformPaths.isLinux()
                  && "DELETE".equals(receipt.op())
                  && watcherDeleteForRemovedRoot(receipt, rootPath)))
          .toList();
      if (journal.removeReplayedSwitchBufferOps(obsolete) != obsolete.size()) {
        throw WorkerServiceException.unavailable("Removed-root reconciliation cleanup changed");
      }
    }
    return removed;
  }

  private static boolean watcherDeleteForRemovedRoot(
      SwitchBufferCapableQueue.SwitchBufferOp receipt, String rootPath) {
    var deletion = io.justsearch.indexerworker.queue.SwitchBufferDelete.decode(receipt.payload());
    return deletion.origin() == io.justsearch.indexerworker.queue.SwitchBufferDelete.Origin.WATCHER
        && Path.of(rootPath).toAbsolutePath().normalize().equals(Path.of(deletion.root()).normalize());
  }

  /**
   * #758: the person removed this root (the Head's removal unwatches it
   * before deleting its documents), so the volume recorded for it and anything awaiting its upgrade
   * seeding no longer stand; the same folder added again is recorded at that add. A deletion of
   * documents by path alone is not a removal and keeps the record of a root that stays declared.
   */
  private void forgetRemovedRoot(String rootPath) {
    if (rootPath == null || rootPath.isBlank()) {
      return;
    }
    try {
      rootWitness.forgetUnder(rootPath, prefixScope(rootPath).excludedSubtrees());
    } catch (IOException | RuntimeException notForgotten) {
      log.warn("unwatchRoot: could not drop the recorded volume under {}: {}", rootPath,
          notForgotten.getMessage());
      throw WorkerServiceException.unavailable("Removed-root volume cleanup failed; retry shortly");
    }
  }

  private void markReplayUncertainForRegistrationDuringMigration(String rootPath) {
    markReplayUncertainForWatchChangeDuringMigration();
    if (mutationAdmission == null || !(jobQueue instanceof SwitchBufferCapableQueue journal)) return;
    Path root = parsedRoot(rootPath);
    if (root == null) return;
    String generation = switchBufferOps.buildingGenerationForFileAdmission();
    if (generation != null && journal.listSwitchBufferOpsStrictForGeneration(generation).stream()
        .anyMatch(receipt -> "DELETE_PREFIX".equals(receipt.op())
            && PathPrefixScope.decode(receipt.payload()).contains(root.toString()))) {
      // Replaying an earlier removal could erase this later registration. Keep serving the
      // current index and refuse promotion, including before a distinct candidate writer opens.
      mutationAdmission.markReplayUncertain();
    }
  }

  private void markReplayUncertainForWatchChangeDuringMigration() {
    if (mutationAdmission != null
        && hasDistinctWritableServingTarget()
        && (switchBufferOps.migratingGeneration() != null || switchBufferOps.isSwitching())) {
      mutationAdmission.markReplayUncertain();
    }
  }

  public ResetIndexResponse resetIndex(ResetIndexRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      log.info("resetIndex: starting profiling reset");
      if (switchBufferOps.isSwitching() || switchBufferOps.migratingGeneration() != null) {
        return ResetIndexResponse.newBuilder().setSuccess(false).build();
      }

      indexingLoop.resetForProfiling(
          () -> {
            try {
              // External cleanup — runs while the loop is stopped
              jobQueue.clearAll();
              ingestLifecycle.indexingCoordinator().deleteAll();
              ingestLifecycle.commitOps().commitAndTrack(CommitReason.RESET);
              // Force searcher refresh so docCount()/isUnmodified() see the empty index.
              // Without this, the stale SearcherManager shows old docs and the loop skips
              // re-submitted files as "unchanged".
              ingestLifecycle.commitOps().maybeRefreshBlocking();
              var ds = indexingLoop.getDisambiguationService();
              if (ds != null) {
                ds.reset();
              }
              OperationalMetrics.getInstance().resetAll();
            } catch (Exception e) {
              throw new RuntimeException("Reset cleanup failed", e);
            }
          });

      log.info("resetIndex: profiling reset complete");
      return ResetIndexResponse.newBuilder().setSuccess(true).build();
    } catch (Exception e) {
      log.error("resetIndex failed", e);
      return ResetIndexResponse.newBuilder().setSuccess(false).build();
    }
  }

  // ==================== Slice 445 — Job-queue TABULAR Resource ====================

  /**
   * Accept live terminal outcomes before any recoverable view handoff. No history, row snapshot,
   * pending-snapshot queue or SSE observer is involved. The empty initial frame acknowledges
   * registration; committed deltas remain serialized with it and cancellation fences delivery.
   */
  public void subscribeIndexingJobOutcomes(
      java.util.function.Consumer<io.justsearch.ipc.IndexingJobsFrame> sink, CallContext ctx) {
    IndexingJobChangeFeed feed = jobQueue.indexingJobChangeFeed().orElseThrow(() ->
        WorkerServiceException.unimplemented("Job queue does not support change-feed"));
    Object emitLock = new Object();
    var closed = new java.util.concurrent.atomic.AtomicBoolean();
    var subscription = new java.util.concurrent.atomic.AtomicReference<IndexingJobChangeFeed.Subscription>();
    var sequence = new java.util.concurrent.atomic.AtomicLong();
    Runnable close = () -> {
      closed.set(true);
      var current = subscription.getAndSet(null);
      if (current != null) current.close();
    };
    ctx.onCancel(close);
    // Nothing can invoke this new consumer before registration, so acquiring the queue owner
    // lock here cannot invert locks with a pre-existing delivery to this subscription.
    synchronized (emitLock) {
      if (closed.get() || ctx.cancelled()) return;
      var registered = feed.subscribe(delta -> {
        IndexingJobChangeFeed.JobRow row = switch (delta) {
          case IndexingJobChangeFeed.Delta.Insert insert -> insert.row();
          case IndexingJobChangeFeed.Delta.Update update -> update.row();
          default -> null; // Resync is view state, never a replay of terminal activity.
        };
        if (row == null || !(row.state().equals("DONE") || row.state().equals("FAILED")
            || row.state().equals("RETRY_EXHAUSTED"))) return;
        synchronized (emitLock) {
          if (closed.get() || ctx.cancelled()) return;
          sink.accept(io.justsearch.ipc.IndexingJobsFrame.newBuilder().setSeq(sequence.incrementAndGet())
              .setDelta(io.justsearch.ipc.IndexingJobsDelta.newBuilder().setUpdate(toJobView(row))).build());
        }
      });
      subscription.set(registered);
      if (closed.get() || ctx.cancelled()) { close.run(); return; }
      try {
        sink.accept(io.justsearch.ipc.IndexingJobsFrame.newBuilder().setSeq(sequence.incrementAndGet())
            .setSnapshot(io.justsearch.ipc.IndexingJobsSnapshot.getDefaultInstance()).build());
      } catch (RuntimeException | Error failure) { close.run(); throw failure; }
    }
  }

  /**
   * Streams the indexing-jobs collection: one snapshot frame followed by
   * per-row delta frames as the SqliteJobQueue mutates. Backs the
   * {@code core.indexing-jobs} TABULAR Resource (Category × SSE_STREAM ×
   * Privacy.HASHED_REQUIRES_RESOLVER).
   *
   * <p>Wire shape (per indexing.proto §1184): each frame is a oneof
   * {snapshot, delta} plus a monotonic seq. Snapshot is one frame; subsequent
   * frames are deltas.
   *
   * <p>Privacy: every row carries SHA-256 hex {@code path_hash}, never raw
   * paths. Path resolution is the separate {@code core.resolve-path-hash}
   * Operation pinned by ADR-0028 / {@code LibraryResolveHashOnlyCallerPin}.
   *
   * <p>Server-streaming, and this method RETURNS WHILE THE STREAM IS STILL OPEN: it registers a
   * change-feed subscription whose deltas are emitted from the feed's own threads. The caller must
   * NOT complete the stream when this returns — cancellation does that.
   */
  public void subscribeIndexingJobs(
      io.justsearch.ipc.SubscribeIndexingJobsRequest request,
      java.util.function.Consumer<io.justsearch.ipc.IndexingJobsFrame> sink,
      CallContext ctx) {
    var feedOpt = jobQueue.indexingJobChangeFeed();
    if (feedOpt.isEmpty()) {
      throw WorkerServiceException.unimplemented(
          "Job queue does not support change-feed (non-SQLite implementation)");
    }
    IndexingJobChangeFeed feed = feedOpt.get();

    // The snapshot is emitted on this (RPC) thread while deltas arrive on the change-feed's
    // threads; both go through the same sink, so they need one monitor between them. Before the
    // conversion that monitor was the ServerCallStreamObserver itself — nothing else ever
    // synchronized on it, so a private lock is the same mutual exclusion without the transport.
    final Object emitLock = new Object();
    // This brief handoff covers subscribe-return through snapshot emission. It is separate from
    // the port's steady-state delivery queue and must never block the jobs-table writer.
    final int pendingSnapshotLimit = 256;
    java.util.ArrayDeque<IndexingJobChangeFeed.Delta> pendingSnapshot = new java.util.ArrayDeque<>();
    java.util.concurrent.atomic.AtomicBoolean snapshotSent = new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.atomic.AtomicBoolean snapshotOverflow = new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.atomic.AtomicReference<IndexingJobChangeFeed.Subscription> subRef =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicBoolean closeRequested = new java.util.concurrent.atomic.AtomicBoolean();
    Runnable closeSubscription = () -> {
      closeRequested.set(true);
      IndexingJobChangeFeed.Subscription subscription = subRef.getAndSet(null);
      if (subscription != null) subscription.close();
    };
    java.util.concurrent.atomic.AtomicLong frameSeq =
        new java.util.concurrent.atomic.AtomicLong(0L);

    java.util.function.Consumer<IndexingJobChangeFeed.Delta> emitDelta =
        delta -> {
          if (ctx.cancelled() || closeRequested.get()) return;
          io.justsearch.ipc.IndexingJobsDelta.Builder deltaBuilder =
              io.justsearch.ipc.IndexingJobsDelta.newBuilder();
          if (delta instanceof IndexingJobChangeFeed.Delta.Resync resync) {
            var replacement = io.justsearch.ipc.IndexingJobsSnapshot.newBuilder();
            for (var row : resync.items()) replacement.addItems(toJobView(row));
            synchronized (emitLock) {
              try {
                sink.accept(io.justsearch.ipc.IndexingJobsFrame.newBuilder()
                    .setSnapshot(replacement.build()).setSeq(frameSeq.incrementAndGet())
                    .setStatePresence(io.justsearch.ipc.IndexingJobStatePresence.newBuilder()
                        .addAllStates(resync.presentStates() == null ? Set.of() : resync.presentStates()))
                    .build());
              } catch (RuntimeException e) {
                log.warn("subscribeIndexingJobs: resync delivery failed; closing subscription", e);
                closeSubscription.run();
              }
            }
            return;
          }
          switch (delta) {
            case IndexingJobChangeFeed.Delta.Resync ignored -> throw new IllegalStateException("Resync already handled");
            case IndexingJobChangeFeed.Delta.Insert ins ->
                deltaBuilder.setInsert(toJobView(ins.row()));
            case IndexingJobChangeFeed.Delta.Update upd ->
                deltaBuilder.setUpdate(toJobView(upd.row()));
            case IndexingJobChangeFeed.Delta.Delete del ->
                deltaBuilder.setDeletePathHash(del.pathHash());
          }
          synchronized (emitLock) {
            try {
              var frame = io.justsearch.ipc.IndexingJobsFrame.newBuilder()
                  .setDelta(deltaBuilder.build()).setSeq(frameSeq.incrementAndGet());
              if (delta.presentStates() != null) {
                frame.setStatePresence(io.justsearch.ipc.IndexingJobStatePresence.newBuilder()
                    .addAllStates(delta.presentStates()));
              }
              sink.accept(frame.build());
            } catch (RuntimeException e) {
              log.warn("subscribeIndexingJobs: delta delivery failed; closing subscription", e);
              closeSubscription.run();
            }
          }
        };

    java.util.function.Consumer<IndexingJobChangeFeed.Delta> consumer = delta -> {
      synchronized (emitLock) {
        if (ctx.cancelled() || closeRequested.get()) return;
        if (snapshotSent.get()) {
          emitDelta.accept(delta);
        } else if (pendingSnapshot.size() < pendingSnapshotLimit) {
          pendingSnapshot.addLast(delta);
        } else {
          snapshotOverflow.set(true);
          pendingSnapshot.clear();
          closeSubscription.run();
        }
      }
    };

    ctx.onCancel(closeSubscription);

    try {
      var snap = feed.subscribeWithSnapshot(consumer);
      subRef.set(snap.subscription());
      if (snapshotOverflow.get()) {
        throw WorkerServiceException.unavailable("indexing-jobs snapshot handoff overflow; resubscribe");
      }
      if (closeRequested.get() || ctx.cancelled()) {
        closeSubscription.run();
        return;
      }

      io.justsearch.ipc.IndexingJobsSnapshot.Builder snapBuilder =
          io.justsearch.ipc.IndexingJobsSnapshot.newBuilder();
      for (IndexingJobChangeFeed.JobRow row : snap.items()) {
        snapBuilder.addItems(toJobView(row));
      }
      synchronized (emitLock) {
        if (snapshotOverflow.get()) {
          throw WorkerServiceException.unavailable("indexing-jobs snapshot handoff overflow; resubscribe");
        }
        if (closeRequested.get() || ctx.cancelled()) {
          closeSubscription.run();
          return;
        }
        sink.accept(
            io.justsearch.ipc.IndexingJobsFrame.newBuilder()
                .setSnapshot(snapBuilder.build())
                .setStatePresence(io.justsearch.ipc.IndexingJobStatePresence.newBuilder()
                    .addAllStates(snap.presentStates() == null ? Set.of() : snap.presentStates()))
                .setSeq(frameSeq.incrementAndGet())
                .build());
        // Reentrant sink writes also join this queue, behind already buffered deltas.
        while (!pendingSnapshot.isEmpty() && !closeRequested.get() && !ctx.cancelled()) {
          emitDelta.accept(pendingSnapshot.removeFirst());
        }
        pendingSnapshot.clear();
        if (snapshotOverflow.get()) {
          throw WorkerServiceException.unavailable("indexing-jobs snapshot handoff overflow; resubscribe");
        }
        snapshotSent.set(true);
      }
    } catch (java.sql.SQLException e) {
      closeSubscription.run();
      log.error("subscribeIndexingJobs: snapshot read failed", e);
      throw WorkerServiceException.internal("snapshot read failed: " + e.getMessage());
    } catch (RuntimeException | Error failure) {
      closeSubscription.run();
      throw failure;
    }
  }

  /**
   * Slice 445 §A.9: cancel an indexing job by path_hash. Resolves the hash
   * via {@code pathResolutionStore} (ADR-0028 reverse-lookup), then marks the
   * row terminal via {@code markDone} with a CANCELLED outcome. The
   * change-feed emits an UPDATE delta to subscribers.
   *
   * <p>Returns {@code cancelled=false} if the path_hash is unknown to the
   * resolution store; the previous_state is reported diagnostically.
   */
  public io.justsearch.ipc.CancelIndexingJobResponse cancelIndexingJob(
      io.justsearch.ipc.CancelIndexingJobRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      String pathHash = request.getPathHash();
      if (pathHash.isBlank()) {
        throw WorkerServiceException.invalidArgument(
            "CancelIndexingJobRequest.path_hash is required");
      }
      var resolved = pathResolutionStore.lookup(pathHash);
      if (resolved.isEmpty()) {
        return io.justsearch.ipc.CancelIndexingJobResponse.newBuilder()
            .setCancelled(false)
            .setPreviousState("UNKNOWN")
            .build();
      }
      String pathStr = resolved.get().normalizedPath();
      if (pathStr == null || pathStr.isBlank()) {
        return io.justsearch.ipc.CancelIndexingJobResponse.newBuilder()
            .setCancelled(false)
            .setPreviousState("REMOVED")
            .build();
      }
      Path path = Path.of(pathStr);
      var outcome =
          io.justsearch.indexerworker.ingest.IngestionOutcome.of(
              io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SKIPPED_POLICY,
              "CANCELLED",
              io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE,
              "cancelled via core.cancel-indexing-job");
      jobQueue.markDone(path, outcome);
      return io.justsearch.ipc.CancelIndexingJobResponse.newBuilder()
          .setCancelled(true)
          .setPreviousState("PENDING_OR_PROCESSING")
          .build();
    } catch (WorkerServiceException e) {
      // Must precede the RuntimeException catch-all: the blank-path_hash check above throws
      // INVALID_ARGUMENT from inside this try, and the catch-all would degrade it to INTERNAL
      // "cancel failed: ..." — a malformed request would read as a server fault.
      throw e;
    } catch (RuntimeException e) {
      log.error("cancelIndexingJob failed", e);
      throw WorkerServiceException.internal("cancel failed: " + e.getMessage());
    }
  }

  /**
   * Slice 445 §A.9: retry a terminal or backing-off indexing job by path_hash. Resolves the hash,
   * then re-enqueues. SqliteJobQueue overwrites the existing row with a fresh PENDING entry (path is
   * the primary key in the jobs table), reporting the state it replaced — read in the same
   * transaction, because the overwrite destroys it (tempdoc 885 §UD open item 1).
   */
  public io.justsearch.ipc.RetryIndexingJobResponse retryIndexingJob(
      io.justsearch.ipc.RetryIndexingJobRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx); var ignoredMutation = mutationLease()) {
      String pathHash = request.getPathHash();
      if (pathHash.isBlank()) {
        throw WorkerServiceException.invalidArgument(
            "RetryIndexingJobRequest.path_hash is required");
      }
      var resolved = pathResolutionStore.lookup(pathHash);
      if (resolved.isEmpty()) {
        return io.justsearch.ipc.RetryIndexingJobResponse.newBuilder()
            .setRetried(false)
            .setPreviousState("UNKNOWN")
            .build();
      }
      String pathStr = resolved.get().normalizedPath();
      if (pathStr == null || pathStr.isBlank()) {
        return io.justsearch.ipc.RetryIndexingJobResponse.newBuilder()
            .setRetried(false)
            .setPreviousState("REMOVED")
            .build();
      }
      Path path = Path.of(pathStr);
      JobQueue.ReenqueueResult result = jobQueue.reenqueue(JobQueue.EnqueueEntry.stat(path));
      if (result.accepted() == 0) {
        return io.justsearch.ipc.RetryIndexingJobResponse.newBuilder()
            .setRetried(false)
            .setPreviousState("NOT_RETRYABLE")
            .build();
      }
      // Tempdoc 885 §UD open item 1: the state the row ACTUALLY held, read in the same
      // transaction that overwrote it. This used to be the literal "FAILED", which was already
      // wrong for a PENDING-in-backoff job and became wrong for RETRY_EXHAUSTED too. A queue with
      // no row-state authority reports null, which surfaces as UNKNOWN rather than as a guess.
      String previousState = result.previousState();
      return io.justsearch.ipc.RetryIndexingJobResponse.newBuilder()
          .setRetried(true)
          .setPreviousState(
              previousState == null || previousState.isBlank() ? "UNKNOWN" : previousState)
          .build();
    } catch (WorkerServiceException e) {
      // Must precede the RuntimeException catch-all: the blank-path_hash check above throws
      // INVALID_ARGUMENT from inside this try, and the catch-all would degrade it to INTERNAL
      // "retry failed: ..." — a malformed request would read as a server fault.
      throw e;
    } catch (RuntimeException e) {
      log.error("retryIndexingJob failed", e);
      throw WorkerServiceException.internal("retry failed: " + e.getMessage());
    }
  }

  private static io.justsearch.ipc.IndexingJobView toJobView(IndexingJobChangeFeed.JobRow row) {
    return io.justsearch.ipc.IndexingJobView.newBuilder()
        .setPathHash(row.pathHash())
        .setState(row.state())
        .setAttempts(row.attempts())
        .setLastUpdatedMs(row.lastUpdatedMs())
        .setErrorMessage(row.errorMessage() == null ? "" : row.errorMessage())
        .setRetryAfterMs(row.retryAfterMs())
        .setCollection(row.collection())
        // Tempdoc 812 D2 — the rollup key: which directory scan enqueued this job (empty when none).
        .setScanId(row.scanId())
        .build();
  }

}
