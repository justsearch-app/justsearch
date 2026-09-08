/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static io.justsearch.adapters.lucene.runtime.LuceneRuntimeUtils.classifyIOException;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SoftDeletesMetrics;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.TelemetryEvents;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import io.justsearch.indexing.runtime.CommitMetadataValidator;
import io.justsearch.indexing.runtime.IndexOpenGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.search.ControlledRealTimeReopenThread;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.AlreadyClosedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-phase Lucene runtime session — tempdoc 406 substrate.
 *
 * <p>Owns all per-session state (Lucene resources, ops collaborators, config-derived knobs,
 * counters) and exposes them to the ops layer via direct fields. Single construction site
 * (the production ctor) and single release site ({@link #close()}) — eliminates the
 * audit-confusion bug-class that arose when {@code LuceneLifecycleManager} scattered field
 * declarations across its ctor + {@code applyComponents} + setter injection.
 *
 * <p>Tempdoc 406 Gap A folded the prior {@code RuntimeContext} field-bag onto this class.
 * Ops classes hold a {@code RuntimeSession} reference and read {@code session.X} for any
 * per-session state.
 *
 * <p>Single-shot lifecycle. Wrapped by sealed phase types
 * ({@link RunningRuntime} / {@link ReadOnlyRuntime} / {@link DeferredRuntime}) which
 * expose the public API. Build via {@link LuceneRuntimeBuilder}.
 */
final class RuntimeSession implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(RuntimeSession.class);

  /** Mode for the session — determines whether the writer is opened immediately. */
  enum Mode {
    /** Read + write. Writer opens, commit timer runs. All 11 ops available. */
    RUNNING,
    /** Read-only. No writer. Read-side ops only. */
    READ_ONLY,
    /** Read-only initially; transitions to read-write via {@link DeferredRuntime#upgradeWriter()}. */
    DEFERRED
  }

  // ==========================================================================
  // Schema-derived (final, set in ctor)
  // ==========================================================================

  final IndexSchema schema;
  final FieldMapper fieldMapper;
  final Supplier<CommitMetadataSource> metadataSourceSupplier;
  final CommitMetadataValidator metadataValidator;

  // ==========================================================================
  // Builder-time / lifecycle plumbing
  // ==========================================================================

  Path indexPath; // initially from builder; may be overwritten by Components.indexPath()
  Path fallbackIndexPath;
  Components prebuiltComponents;
  IndexOpenGuard indexOpenGuard;

  // ==========================================================================
  // Volatile lifecycle resources
  // ==========================================================================

  volatile LifecycleSnapshot snapshot;
  volatile ControlledRealTimeReopenThread<IndexSearcher> crtrt;
  volatile Map<String, String> openTimeCommitUserData;

  // ==========================================================================
  // Volatile telemetry sinks (builder-injected; tests may override)
  // ==========================================================================

  volatile TelemetryEvents telemetryEvents;
  volatile SoftDeletesMetrics softDeletesMetrics;

  // ==========================================================================
  // Config-derived knobs (set after Components open; volatile for cross-thread visibility).
  // No field-init defaults — both constructors assign each field explicitly so the audit
  // property "every field is initialised in the ctor body" holds literally
  // (critical-analysis fix item 3).
  // ==========================================================================

  volatile ResolvedConfig resolvedConfig;
  volatile boolean commitMetadataEnabled;
  volatile ValidationMode validationMode;
  volatile long maxQueueDepth;
  volatile long nrtTargetMaxStaleMs;
  // The raw index.nrt.* pair (tempdoc 885 item 19). Neither CRTRT construction site reads it
  // directly any more: both go through the mode-resolved nrtReopenTargetMs/nrtReopenHardMs below.
  volatile long nrtHardMaxStaleMs;
  // Tempdoc 885 item 19 cadence candidate: CONTINUOUS is today's behaviour; ON_DEMAND moves the
  // reopen onto the foreground acquire (SearcherBridge) and slows the background thread.
  volatile NrtMode nrtMode;
  volatile long nrtOnDemandMaxStaleMs;

  /**
   * The bounds the reopen thread was ACTUALLY built with, after {@link NrtMode} resolution — not
   * the raw {@code index.nrt.*} pair. {@code CommitOps.resumeNrtRefresh} rebuilds the thread after
   * every bulk-backfill suspend, and reading the raw pair there silently reverted on_demand's slow
   * background cadence to the continuous 500/50 on the first backfill (885 review B1).
   */
  volatile long nrtReopenTargetMs;

  volatile long nrtReopenHardMs;

  /**
   * Is a foreground (user-facing) RPC in flight? The reopen-on-demand seam gates on this so that
   * enrichment-backfill reads, which reach Lucene through the same {@link SearcherBridge}, cannot
   * trigger a refresh. Wired by the Worker from item 3's {@code ForegroundLoad}; defaults to
   * always-true (see {@link LuceneRuntimeBuilder#withForegroundActive}).
   */
  volatile java.util.function.BooleanSupplier foregroundActive;
  volatile KnnVectorsFormat knnVectorsFormat;
  volatile Integer vectorEfSearchOverrideOrNull;
  volatile String softDeleteField;
  String uidField;
  String hardDeleteField;

  // ==========================================================================
  // Atomic counters (final references)
  // ==========================================================================

  final AtomicLong lastRefreshNanos = new AtomicLong(0L);

  /**
   * Reopen counters + the on-demand freshness watermark (tempdoc 885 item 19). Final and created
   * once per session: {@link ComponentsFactory} installs the single refresh listener that writes
   * it, and re-opens within the session reuse the same instance so the counters are per-session,
   * not per-reader.
   */
  final NrtReopenStats nrtStats = new NrtReopenStats();

  final AtomicLong lastCommitNanos = new AtomicLong(0L);
  final AtomicLong lastRefreshTargetMs = new AtomicLong(-1L);
  final AtomicLong pendingDocs = new AtomicLong(0L);
  /**
   * Commits on this session, broken down by {@link CommitReason} (tempdoc 912 item 2). Not an
   * {@code AtomicLong}: the total is the sum of the per-reason slots, so attribution cannot drift
   * from the total. {@code commitCount.get()} still reads as the total for callers that want it.
   */
  final CommitCounters commitCount = new CommitCounters();
  final AtomicLong queueDepth = new AtomicLong(0L);

  /**
   * Monotonic counter of BULK deletions submitted to the writer — {@code deleteByPathPrefix} and
   * {@code deleteAll} (tempdoc 809 finding 3). Removing a watched root lands here as a
   * {@code deleteByPathPrefix} on the worker (Head {@code RootLifecycleOps.removeWatchedPath} →
   * {@code deleteByPath} RPC → {@code WorkerIngestService.deleteByPath} →
   * {@link IndexingCoordinator#deleteByPathPrefix}), so this is the removal signal itself rather
   * than a parallel flag mirroring it.
   *
   * <p>A long-running background batch captures this once and re-reads it at its interruption
   * points: a changed value means the document population the batch selected from no longer
   * describes the index, so continuing to enrich the rest of that batch spends GPU on documents
   * that may already be gone. Per-document deletes deliberately do NOT bump it — a single missing
   * document costs one no-op RMW, while a bulk delete can invalidate an entire batch.
   */
  final AtomicLong bulkDeleteEpoch = new AtomicLong(0L);

  // ==========================================================================
  // Ops collaborators. Final: assigned exactly once in each constructor.
  // In the test ctor, every ops field is explicitly assigned {@code null} — tests that need
  // a particular ops class construct it directly with the session reference.
  // (critical-analysis fix item 3: enforces single composition site via the type system.)
  // ==========================================================================

  final CommitOps commitOps;
  final IndexingCoordinator indexingCoordinator;
  final ReadPathOps readPathOps;
  final WritePathOps writePathOps; // null in READ_ONLY / DEFERRED / test ctor
  final HybridSearchOps hybridSearchOps;
  final TextQueryOps textQueryOps;
  final ChunkSearchOps chunkSearchOps;
  final SuggestOps suggestOps;
  final DocumentFieldOps documentFieldOps;
  final IndexCountOps indexCountOps;
  final FacetingEngine facetingEngine;
  final FolderBrowseEngine folderBrowseEngine;
  final PruneOps pruneOps; // null in READ_ONLY / DEFERRED / test ctor

  private volatile boolean closed;

  /** Linearizes one terminal-writer notification against this runtime's intentional retirement. */
  private final Object terminalWriterFailureLock = new Object();

  private Consumer<Throwable> terminalWriterFailureListener;
  private boolean terminalWriterFailureClaimed;
  private boolean terminalWriterFailureRetired;

  /**
   * Tempdoc 406 Gap G: write-side drain flag. When true, {@link WritePathOps#guardWritable()}
   * rejects new writes with ISE (the gRPC layer maps to UNAVAILABLE so callers retry on the
   * upgraded holder reference). Set by {@link RunningRuntime#drainAndClose}.
   */
  volatile boolean draining;

  /**
   * Critical-analysis fix (item 4): write-path serialization barrier. Mutating ops on
   * {@link IndexingCoordinator} acquire {@code writeBarrier.readLock()} for the duration of
   * each op so concurrent writes don't contend with each other. {@link
   * RunningRuntime#drainAndClose} acquires {@code writeBarrier.writeLock()} (with timeout) to
   * wait for all in-flight writes to complete before closing — eliminates the race window
   * where a writer could pass the {@code draining} check, then the runtime closes mid-write,
   * then the writer crashes on a null writer.
   *
   * <p>Lock ordering: outer = {@code writeBarrier.readLock()}, inner = {@link
   * IndexingCoordinator#dispatchLock}. {@code drainAndClose} only acquires the write lock —
   * never touches dispatchLock — so no deadlock potential.
   */
  final ReentrantReadWriteLock writeBarrier = new ReentrantReadWriteLock();

  void onTerminalWriterFailure(Consumer<Throwable> listener) {
    synchronized (terminalWriterFailureLock) {
      terminalWriterFailureListener = Objects.requireNonNull(listener, "listener");
    }
    reportTerminalWriterFailureIfPresent();
  }

  void retireTerminalWriterFailureNotifications() {
    synchronized (terminalWriterFailureLock) {
      terminalWriterFailureRetired = true;
    }
  }

  /**
   * Claims and reports this runtime's terminal writer once.
   *
   * <p>An absent listener never consumes the one-shot; installation rechecks a writer that became
   * terminal before its owner was bound.
   *
   * <p>A listener {@link RuntimeException} remains diagnostic so it cannot replace the Lucene
   * operation failure whose release path discovered the tragedy. {@link Error}s still propagate:
   * an inability to create the process-fault thread (for example, VM exhaustion) is itself a JVM
   * failure and must reach the uncaught-exception owner.
   */
  boolean reportTerminalWriterFailureIfPresent() {
    Consumer<Throwable> listener;
    Throwable cause;
    synchronized (terminalWriterFailureLock) {
      if (terminalWriterFailureRetired || terminalWriterFailureClaimed) return true;
      listener = terminalWriterFailureListener;
      LifecycleSnapshot snap = snapshot;
      org.apache.lucene.index.IndexWriter writer = snap != null ? snap.writer() : null;
      if (writer == null) return false;
      Throwable tragic = writer.getTragicException();
      if (tragic == null && writer.isOpen()) {
        return false;
      }
      // NRT starts during construction, before the enclosing owner can bind its listener.
      // Keep the one-shot available for installation's recheck without falling into raw JVM exit.
      if (listener == null) return true;
      terminalWriterFailureClaimed = true;
      cause =
          tragic != null
              ? tragic
              : new IllegalStateException("Lucene IndexWriter is permanently closed");
    }
    try {
      listener.accept(cause);
    } catch (RuntimeException listenerFailure) {
      log.error("Terminal writer failure listener failed", listenerFailure);
    }
    return true;
  }

  void routeTerminalWriterFailuresFrom(Thread thread) {
    Thread.UncaughtExceptionHandler fallback = thread.getUncaughtExceptionHandler();
    thread.setUncaughtExceptionHandler(
        (failedThread, failure) -> {
          if (!observedTerminalWriterFailure(failure) || !reportTerminalWriterFailureIfPresent()) {
            fallback.uncaughtException(failedThread, failure);
          }
        });
  }

  private static boolean observedTerminalWriterFailure(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof AlreadyClosedException) {
        return true;
      }
    }
    return false;
  }

  // ==========================================================================
  // Test-only constructor — barebones, does not open Lucene or construct ops
  // ==========================================================================

  /**
   * Test-only constructor for unit tests of ops classes.
   *
   * <p><b>Contract (read carefully):</b>
   *
   * <ul>
   *   <li>All ops fields ({@link #commitOps}, {@link #indexingCoordinator}, {@link
   *       #readPathOps}, etc.) are {@code null}. Tests that need an ops instance must
   *       construct it directly with this session reference.
   *   <li>All lifecycle resources ({@link #snapshot}, {@link #crtrt}, {@link
   *       #openTimeCommitUserData}, {@link #indexOpenGuard}, {@link #prebuiltComponents},
   *       {@link #fallbackIndexPath}, {@link #indexPath}) are {@code null}. Tests that need
   *       them mutate the relevant volatile fields directly.
   *   <li>Config knobs are initialised to the same defaults the production ctor would
   *       eventually set absent any config override.
   *   <li>Atomic counters and {@link #writeBarrier} are initialised normally (final fields).
   * </ul>
   *
   * <p>Production code MUST use {@link #RuntimeSession(LuceneRuntimeBuilder, Mode)}.
   */
  RuntimeSession(IndexSchema schema) {
    // Schema-derived
    this.schema = schema;
    this.fieldMapper = schema.fieldMapper();
    this.metadataSourceSupplier = schema.metadataSourceSupplier();
    this.metadataValidator = schema.metadataValidator();

    // Builder-time / lifecycle plumbing — tests start blank, mutate as needed
    this.indexPath = null;
    this.fallbackIndexPath = null;
    this.prebuiltComponents = null;
    this.indexOpenGuard = null;

    // Volatile lifecycle resources — null until tests set them
    this.snapshot = null;
    this.crtrt = null;
    this.openTimeCommitUserData = null;
    this.telemetryEvents = null;
    this.softDeletesMetrics = null;

    // Config-derived knobs — production-equivalent defaults, tests override as needed
    this.resolvedConfig = null;
    this.commitMetadataEnabled = true;
    this.validationMode = ValidationMode.FAIL;
    this.maxQueueDepth = 10_000L;
    this.nrtTargetMaxStaleMs = 500L;
    this.nrtHardMaxStaleMs = 50L;
    this.nrtMode = NrtMode.CONTINUOUS;
    this.nrtOnDemandMaxStaleMs = 1000L;
    this.nrtReopenTargetMs = 500L;
    this.nrtReopenHardMs = 50L;
    this.foregroundActive = () -> true;
    this.knnVectorsFormat = null;
    this.vectorEfSearchOverrideOrNull = null;
    this.softDeleteField = SchemaFields.SOFT_DELETE;
    this.uidField = SchemaFields.DOC_UID;
    this.hardDeleteField = SchemaFields.HARD_DELETE;

    // Ops collaborators — null in test mode; tests build the ones they need
    this.commitOps = null;
    this.indexingCoordinator = null;
    this.readPathOps = null;
    this.writePathOps = null;
    this.hybridSearchOps = null;
    this.textQueryOps = null;
    this.chunkSearchOps = null;
    this.suggestOps = null;
    this.documentFieldOps = null;
    this.indexCountOps = null;
    this.facetingEngine = null;
    this.folderBrowseEngine = null;
    this.pruneOps = null;
  }

  // ==========================================================================
  // Production constructor — single site for all per-session field initialization
  // ==========================================================================

  RuntimeSession(LuceneRuntimeBuilder builder, Mode mode) {
    this.schema = builder.schema();
    this.fieldMapper = schema.fieldMapper();
    this.metadataSourceSupplier = schema.metadataSourceSupplier();
    this.metadataValidator = schema.metadataValidator();

    // 1. Builder-supplied state.
    this.fallbackIndexPath = builder.fallbackIndexPath();
    ResolvedConfig resolvedOverride = builder.resolvedConfigOverride();
    this.resolvedConfig =
        resolvedOverride != null ? resolvedOverride : resolveFromConfigStore();
    this.indexPath = builder.indexPath();
    this.knnVectorsFormat = schema.knnVectorsFormatOverride();
    this.telemetryEvents = builder.telemetryEvents();
    this.foregroundActive = builder.foregroundActive();
    this.softDeletesMetrics = builder.softDeletesMetrics();
    // Schema-fixed field names — same constants regardless of config.
    this.uidField = SchemaFields.DOC_UID;
    this.hardDeleteField = SchemaFields.HARD_DELETE;
    // openTimeCommitUserData is captured at section 6 (after components open).
    this.openTimeCommitUserData = null;
    // Defaults for config-derived knobs. These are overwritten in section 4 (from
    // components) or section 7 (from resolved config) — but we set them here so the
    // production ctor's "every field assigned" property holds even if a config branch
    // is missing.
    this.commitMetadataEnabled = true;
    this.validationMode = ValidationMode.FAIL;
    this.maxQueueDepth = 10_000L;
    this.nrtTargetMaxStaleMs = 500L;
    this.nrtHardMaxStaleMs = 50L;
    this.nrtMode = NrtMode.CONTINUOUS;
    this.nrtOnDemandMaxStaleMs = 1000L;
    this.nrtReopenTargetMs = 500L;
    this.nrtReopenHardMs = 50L;
    // foregroundActive is NOT reset here: it is builder-supplied above, and this block runs
    // after it. The other knobs are config-derived and are overwritten again by applyComponents.
    this.softDeleteField = SchemaFields.SOFT_DELETE;
    this.vectorEfSearchOverrideOrNull = null;

    // Mode → readOnly flag passed through to ComponentsFactory.build.
    boolean openReadOnly = (mode == Mode.READ_ONLY) || (mode == Mode.DEFERRED);

    // 2. CommitOps + default IndexOpenGuard (closure references commitOps).
    this.commitOps = new CommitOps(this, builder.initialBuildState());
    this.indexOpenGuard =
        builder.indexOpenGuardOverride() != null
            ? builder.indexOpenGuardOverride()
            : new IndexMetadataParityGuard(
                this::resolvedIndexPathForGuards, commitOps::buildMetadataSnapshot);
    this.prebuiltComponents = builder.prebuiltComponentsForTests();

    // 3. Open Lucene components, with corruption / schema-mismatch recovery.
    Components components;
    try {
      components = openComponentsWithRecovery(openReadOnly);
    } catch (RuntimeException e) {
      // Best-effort cleanup of any partial state, then propagate.
      try {
        commitOps.stopCommitTimer();
      } catch (RuntimeException ignored) {
        // best-effort
      }
      throw e;
    }

    // 4. Apply components — wires ops, snapshot, crtrt. Mirrors LLM.applyComponents.
    this.commitMetadataEnabled = components.commitMetadataEnabled();
    this.snapshot =
        new LifecycleSnapshot(
            components.directory(),
            components.writer(),
            components.searcherManager(),
            components.indexPath(),
            components.ephemeralPath(),
            components.indexAnalyzer());
    this.crtrt = components.crtrt();
    if (crtrt != null) {
      routeTerminalWriterFailuresFrom(crtrt);
    }
    this.indexPath = components.indexPath();
    this.softDeleteField = components.softDeleteField();
    this.knnVectorsFormat = components.knnVectorsFormat();
    this.resolvedConfig = components.resolvedConfig();
    this.nrtTargetMaxStaleMs = components.nrtTargetMaxStaleMs();
    this.nrtHardMaxStaleMs = components.nrtHardMaxStaleMs();
    this.nrtMode = components.nrtMode() != null ? components.nrtMode() : NrtMode.CONTINUOUS;
    this.nrtOnDemandMaxStaleMs = components.nrtOnDemandMaxStaleMs();
    this.nrtReopenTargetMs = components.reopenTargetMs();
    this.nrtReopenHardMs = components.reopenHardMs();
    Integer explicitEfSearch =
        resolvedConfig != null && resolvedConfig.index() != null
            ? resolvedConfig.index().vectorEfSearch()
            : null;
    this.vectorEfSearchOverrideOrNull =
        (explicitEfSearch != null && explicitEfSearch > 0) ? explicitEfSearch : null;

    SearcherBridge bridge = new SearcherBridge(this);

    // Layer 1: base ops (no inter-ops dependencies).
    this.readPathOps = new ReadPathOps(this, components.idField());
    this.writePathOps = new WritePathOps(this, components.idField(), bridge);
    this.indexCountOps = new IndexCountOps(bridge);
    this.suggestOps = new SuggestOps(bridge);
    this.facetingEngine = new FacetingEngine(bridge, fieldMapper::fieldDef);
    this.folderBrowseEngine = new FolderBrowseEngine(bridge, fieldMapper::fieldDef);

    // Layer 2: ops that depend on Layer 1.
    this.textQueryOps = new TextQueryOps(this, bridge, this.readPathOps);
    this.documentFieldOps =
        new DocumentFieldOps(this, bridge, components.idField(), this.readPathOps);

    // IndexingCoordinator depends on writePathOps (set above) + commitOps. Created
    // after writePathOps is assigned so the supplier reference is safe.
    final WritePathOps writePathRef = this.writePathOps;
    this.indexingCoordinator = new IndexingCoordinator(this, () -> writePathRef);

    this.pruneOps = new PruneOps(this, indexingCoordinator, commitOps);

    // Layer 3: ops that depend on Layer 2.
    this.hybridSearchOps = new HybridSearchOps(this, this.textQueryOps, this.readPathOps);
    this.chunkSearchOps =
        new ChunkSearchOps(this, bridge, this.hybridSearchOps, this.readPathOps, components.idField());

    // 5. Start NRT refresh thread if components produced one (read+write mode).
    if (crtrt != null) {
      crtrt.start();
    }

    // 6. Capture openTimeCommitUserData snapshot. Must happen before any writes.
    if (openTimeCommitUserData == null) {
      this.openTimeCommitUserData = latestCommitUserDataBestEffort();
    }

    // 7. Derive queue depth from writer config + read validation mode.
    ResolvedConfig.Index idx = components.resolvedConfig().index();
    Integer mbd = idx.writerMaxBufferedDocs();
    if (mbd != null && mbd > 0) {
      this.maxQueueDepth = Math.max(maxQueueDepth, mbd * 10L);
    }
    Integer cfgMaxQueue = idx.writerMaxQueueDepth();
    if (cfgMaxQueue != null && cfgMaxQueue > 0) {
      this.maxQueueDepth = cfgMaxQueue;
    }
    String vm = idx.validationMode();
    this.validationMode =
        "warn".equalsIgnoreCase(vm) ? ValidationMode.WARN : ValidationMode.FAIL;

    // 8. Start commit timer for RUNNING mode only. READ_ONLY/DEFERRED have no writer.
    if (mode == Mode.RUNNING) {
      commitOps.startCommitTimer();
    }
  }

  // ==========================================================================
  // Static helper — config resolution (migrated from RuntimeContext)
  // ==========================================================================

  /** Resolves config from ConfigStore, or builds from env registry + YAML as fallback. */
  static ResolvedConfig resolveFromConfigStore() {
    ConfigStore store = ConfigStore.globalOrNull();
    if (store != null) {
      ResolvedConfig cfg = store.get();
      if (cfg != null) return cfg;
    }
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.contributeBaseSources();
    return builder.build();
  }

  // ==========================================================================
  // Recovery wrapper — mirrors LLM.startGuarded
  // ==========================================================================

  private Components openComponentsWithRecovery(boolean readOnly) {
    Path effectivePath = resolveEffectivePath();
    try {
      return openComponents(readOnly);
    } catch (IndexRuntimeIOException e) {
      ResolvedConfig.Index idx = resolvedConfig.index();
      log.debug(
          "IndexRuntimeIOException caught during open - reason={}, autoRecovery={},"
              + " schemaMismatchPolicy={}, indexPath={}",
          e.reason(),
          idx.indexAutoRecovery(),
          idx.schemaMismatchPolicy(),
          effectivePath);

      // True corruption auto-recovery (backup-first).
      if (e.reason() == IndexRuntimeIOException.Reason.CORRUPT_INDEX
          && idx.indexAutoRecovery()) {
        log.warn(
            "Corrupted index detected at {}. Auto-recovery enabled, attempting backup-first"
                + " rebuild...",
            effectivePath,
            e);
        try {
          backupIndexDirectoryForRecovery(effectivePath, "corrupt_index");
          log.info("Corrupted index backed up, rebuilding empty index...");
          // tempdoc 628: the live worker opens the active index read-only first (deferred-writer mode),
          // but a read-only open requires an existing committed index — and the backup just emptied the
          // directory. Materialize a fresh empty commit so the read-only reopen succeeds.
          if (readOnly) {
            materializeEmptyIndex(effectivePath);
          }
          Components rebuilt = openComponents(readOnly);
          // tempdoc 628 Stage B (G3): the empty index is structurally fine but content-empty. Drop a
          // durable marker so the orchestration layer rebuilds from the source files on disk instead
          // of relying on passive re-watch (which would silently miss unchanged files). Written
          // regardless of read-only — the live worker's first open of the active index is read-only
          // (deferred-writer mode), and that is exactly where recovery fires.
          IndexRecoveryMarker.write(effectivePath, "corrupt_index");
          log.info("Index recovered successfully (empty index created; rebuild-from-source pending)");
          return rebuilt;
        } catch (Exception recoveryError) {
          log.error("Index recovery failed", recoveryError);
          throw new IndexRuntimeIOException(
              IndexRuntimeIOException.Reason.CORRUPT_INDEX,
              "Index recovery failed after corruption. Manual fix: Stop the app and"
                  + " remove/rename the index directory at \""
                  + effectivePath
                  + "\" (prefer backup/rename over delete).",
              recoveryError);
        }
      }

      // Schema mismatch recovery is policy-controlled.
      if (e.reason() == IndexRuntimeIOException.Reason.SCHEMA_MISMATCH) {
        String policy = idx.schemaMismatchPolicy();
        if ("REBUILD_BACKUP_FIRST".equalsIgnoreCase(policy)) {
          log.warn(
              "Index schema mismatch detected at {}. Policy={}, attempting backup-first"
                  + " rebuild...",
              effectivePath,
              policy,
              e);
          try {
            backupIndexDirectoryForRecovery(effectivePath, "schema_mismatch");
            log.info("Schema-mismatched index backed up, rebuilding empty index...");
            if (readOnly) {
              materializeEmptyIndex(effectivePath);
            }
            Components rebuilt = openComponents(readOnly);
            log.info("Index rebuilt successfully (empty index created)");
            return rebuilt;
          } catch (Exception recoveryError) {
            log.error("Schema mismatch recovery failed", recoveryError);
            throw new IndexRuntimeIOException(
                IndexRuntimeIOException.Reason.SCHEMA_MISMATCH,
                "Index schema mismatch recovery failed at \""
                    + effectivePath
                    + "\". Manual fix: Stop the app and rebuild the index (backup/rename the"
                    + " old directory).",
                recoveryError);
          }
        }

        log.error(
            "Index schema mismatch at {}. Policy={}, refusing destructive rebuild.",
            effectivePath,
            policy);
        throw e;
      }

      if (e.reason() == IndexRuntimeIOException.Reason.CORRUPT_INDEX) {
        log.error(
            "Index corrupted at {}. Auto-recovery disabled. Manual fix: Stop the app and"
                + " remove/rename the index directory at \"{}\"",
            effectivePath,
            effectivePath);
        throw e;
      }

      throw e;
    }
  }

  private Components openComponents(boolean readOnly) {
    if (prebuiltComponents != null) {
      return prebuiltComponents;
    }
    try {
      return ComponentsFactory.build(
          resolvedConfig,
          fallbackIndexPath,
          indexPath,
          readOnly,
          fieldMapper,
          schema.analyzerRegistry(),
          knnVectorsFormat,
          softDeletesMetrics,
          indexOpenGuard,
          lastRefreshNanos,
          nrtStats,
          nrtTargetMaxStaleMs,
          nrtHardMaxStaleMs);
    } catch (IOException e) {
      throw new IndexRuntimeIOException(
          classifyIOException(e), "Failed to open Lucene components", e);
    }
  }

  // ==========================================================================
  // Close — mirrors LLM.close ordering verbatim
  // ==========================================================================

  @Override
  public void close() {
    retireTerminalWriterFailureNotifications();
    if (closed) return;
    closed = true;

    // Capture snapshot before nulling — close from the captured copy.
    LifecycleSnapshot snap = snapshot;
    snapshot = null; // atomic — all ops fail-fast on subsequent calls

    if (commitOps != null) {
      try {
        commitOps.stopCommitTimer();
      } catch (RuntimeException e) {
        log.warn("commit timer stop error: {}", e.getMessage());
      }
    }
    if (crtrt != null) {
      try {
        crtrt.close();
      } catch (Exception e) {
        log.warn("crtrt close error: {}", e.getMessage());
      }
      crtrt = null;
    }
    if (snap != null) {
      if (snap.searcherManager() != null) {
        try {
          snap.searcherManager().close();
        } catch (IOException e) {
          log.warn("searcherManager close error: {}", e.getMessage());
        }
      }
      if (snap.writer() != null) {
        boolean writerWasTerminal =
            snap.writer().getTragicException() != null || !snap.writer().isOpen();
        boolean writerClosedCleanly = false;
        try {
          snap.writer().close();
          writerClosedCleanly =
              !writerWasTerminal && snap.writer().getTragicException() == null;
        } catch (IOException e) {
          log.warn("writer close error: {}", e.getMessage());
        }
        // tempdoc 628 Gap 1: record a clean shutdown only when the writer committed + closed cleanly
        // and this is a persistent (non-ephemeral) index. An absent marker on the next open means the
        // previous shutdown was unclean (a crash) → escalate to a FULL integrity scan.
        if (writerClosedCleanly && !snap.ephemeralPath() && snap.indexPath() != null) {
          CleanShutdownMarker.write(snap.indexPath());
        }
      }
      if (snap.directory() != null) {
        try {
          snap.directory().close();
        } catch (IOException e) {
          log.warn("directory close error: {}", e.getMessage());
        }
      }
      if (snap.ephemeralPath()
          && snap.indexPath() != null
          && Files.exists(snap.indexPath())) {
        try (var stream = Files.walk(snap.indexPath())) {
          stream
              .sorted(java.util.Comparator.reverseOrder())
              .forEach(
                  p -> {
                    try {
                      Files.deleteIfExists(p);
                    } catch (IOException ex) {
                      log.warn("delete error: {}", ex.getMessage());
                    }
                  });
        } catch (IOException ex) {
          log.debug("walk error during ephemeral cleanup: {}", ex.getMessage());
        }
      }
    }
    // ==========================================================================
    // Field-by-field audit (critical-analysis fix item 3): every per-session field is
    // either explicitly released above or documented below as not requiring release.
    // Adding a new field on RuntimeSession requires editing this comment block too.
    //
    // === Released above ===
    //   snapshot       — nulled (atomic publish; ops fail-fast on subsequent calls)
    //   crtrt          — closed and nulled (NRT thread joined)
    //   commitOps      — timer stopped (executor shutdown)
    //   snap.{searcherManager, writer, directory} — closed
    //   snap.indexPath (when ephemeral) — recursive delete
    //
    // === Not released — owned by IndexSchema ===
    //   schema, fieldMapper, metadataSourceSupplier, metadataValidator
    //
    // === Not released — config knobs (no resources; GC reclaims with the session) ===
    //   resolvedConfig, commitMetadataEnabled, validationMode, maxQueueDepth,
    //   nrtTargetMaxStaleMs, nrtHardMaxStaleMs, knnVectorsFormat,
    //   vectorEfSearchOverrideOrNull, softDeleteField, uidField, hardDeleteField,
    //   indexPath, fallbackIndexPath, prebuiltComponents, indexOpenGuard, draining
    //
    // === Not released — atomic counters (left for post-close inspection) ===
    //   pendingDocs, commitCount, queueDepth, lastRefreshNanos, lastCommitNanos,
    //   lastRefreshTargetMs
    //
    // === Not released — sinks (callers may hold references; no harm post-close) ===
    //   telemetryEvents, softDeletesMetrics, openTimeCommitUserData
    //
    // === Not released — synchronization primitive (final, no resources) ===
    //   writeBarrier (ReentrantReadWriteLock — held only during normal ops)
    //
    // === Not released — ops collaborators ===
    //   readPathOps, writePathOps, hybridSearchOps, textQueryOps, chunkSearchOps,
    //   suggestOps, documentFieldOps, indexCountOps, facetingEngine,
    //   folderBrowseEngine, pruneOps, indexingCoordinator
    //   These all hold a session reference; reads on them after close fail-fast via
    //   the snapshot null-publish (and writers also via the draining flag + writeBarrier).
    //   GC reclaims them when the session is unreferenced.
    // ==========================================================================
  }

  // ==========================================================================
  // Status / observability — mirrors LLM accessors
  // ==========================================================================

  Map<String, String> latestCommitUserDataBestEffort() {
    if (closed) return Map.of();
    LifecycleSnapshot snap = snapshot;
    var d = snap != null ? snap.directory() : null;
    if (d == null) return Map.of();
    try (org.apache.lucene.index.DirectoryReader reader =
        org.apache.lucene.index.DirectoryReader.open(d)) {
      Map<String, String> ud = reader.getIndexCommit().getUserData();
      return ud == null ? Map.of() : Map.copyOf(ud);
    } catch (Exception e) {
      return Map.of();
    }
  }

  Map<String, String> openTimeCommitUserData() {
    Map<String, String> snap = openTimeCommitUserData;
    return snap != null ? snap : Map.of();
  }

  VectorFormatDetector.Summary queryVectorFormatActual() {
    if (closed) return null;
    LifecycleSnapshot snap = snapshot;
    org.apache.lucene.search.SearcherManager mgr = snap != null ? snap.searcherManager() : null;
    if (mgr == null) return null;
    IndexSearcher searcher = null;
    try {
      searcher = mgr.acquire();
      if (searcher.getIndexReader() instanceof org.apache.lucene.index.DirectoryReader dr) {
        return VectorFormatDetector.inspect(dr);
      }
      return null;
    } catch (Exception e) {
      log.debug("Failed to query vector format: {}", e.getMessage());
      return null;
    } finally {
      if (searcher != null) {
        try {
          mgr.release(searcher);
        } catch (Exception e) {
          log.debug("searcher release: {}", e.getMessage(), e);
        }
      }
    }
  }

  ResolvedConfig resolvedConfig() {
    return resolvedConfig != null ? resolvedConfig : resolveFromConfigStore();
  }

  // ==========================================================================
  // Helpers — mirror LLM private methods
  // ==========================================================================

  private Path resolveEffectivePath() {
    Path p = indexPath;
    if (p != null) return p;
    ResolvedConfig rc = resolvedConfig != null ? resolvedConfig : resolveFromConfigStore();
    Path resolved = rc.paths() != null ? rc.paths().indexBasePath() : null;
    if (resolved == null && fallbackIndexPath != null) {
      resolved = fallbackIndexPath;
    }
    return resolved;
  }

  private Path resolvedIndexPathForGuards() {
    return resolveEffectivePath();
  }

  /**
   * Materializes a fresh, committed, empty index at {@code indexPath} (tempdoc 628). After a backup
   * moves the damaged index directory away, the directory is empty; a read-only reopen would then fail
   * because {@code ComponentsFactory}'s read-only path requires {@code DirectoryReader.indexExists}.
   * The live worker opens read-only first (deferred-writer mode), so corruption recovery died here
   * without this step. The minimal empty commit just writes a valid {@code segments_N}; the real
   * runtime reopens it with the full codec/config.
   */
  private static void materializeEmptyIndex(Path indexPath) throws IOException {
    Files.createDirectories(indexPath);
    try (org.apache.lucene.store.Directory dir =
            org.apache.lucene.store.FSDirectory.open(indexPath);
        org.apache.lucene.index.IndexWriter w =
            new org.apache.lucene.index.IndexWriter(
                dir, new org.apache.lucene.index.IndexWriterConfig())) {
      w.commit();
    }
  }

  private void backupIndexDirectoryForRecovery(Path path, String reason) throws IOException {
    if (path == null) return;
    Path target = path.toAbsolutePath().normalize();
    if (!Files.exists(target)) return;

    ResolvedConfig rc = resolvedConfig != null ? resolvedConfig : resolveFromConfigStore();
    Path indexBasePath = rc.paths() != null ? rc.paths().indexBasePath() : null;
    if (indexBasePath == null && fallbackIndexPath != null) {
      indexBasePath = fallbackIndexPath;
    }
    if (indexBasePath == null) {
      throw new IOException("Cannot determine index base path for recovery backup");
    }
    Path allowedRoot = indexBasePath.toAbsolutePath().normalize();

    if (!LuceneRuntimeUtils.looksLikeLuceneIndexDirectory(target)) {
      throw new IOException(
          "Refusing recovery for "
              + reason
              + ": target does not look like a Lucene index directory: "
              + target);
    }

    SafeIndexPathOps.backupDirectory(target, allowedRoot);
  }
}
