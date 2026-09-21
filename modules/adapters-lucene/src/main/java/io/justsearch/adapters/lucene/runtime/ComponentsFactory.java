/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static io.justsearch.adapters.lucene.runtime.LuceneRuntimeUtils.buildSoftDeleteRetentionQuery;

import io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SoftDeletesMetrics;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexing.runtime.IndexOpenGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.MergePolicy;
import org.apache.lucene.index.SoftDeletesDirectoryReaderWrapper;
import org.apache.lucene.index.SoftDeletesRetentionMergePolicy;
import org.apache.lucene.index.TieredMergePolicy;
import org.apache.lucene.search.ControlledRealTimeReopenThread;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static factory that builds the Lucene components (Directory, IndexWriter, SearcherManager, etc.)
 * needed by {@link LuceneLifecycleManager}.
 *
 * <p>Extracted from the former {@code LuceneLifecycleManager.buildComponents()} to keep the facade focused on
 * lifecycle state management and delegation.
 */
final class ComponentsFactory {
  private static final Logger log = LoggerFactory.getLogger(ComponentsFactory.class);

  private ComponentsFactory() {}

  /**
   * Builds the Lucene components for an index runtime.
   *
   * @param resolvedConfig merged config from ConfigStore (primary source for all fields)
   * @param fallbackIndexPath legacy fallback path from RuntimeConfig (used when indexPath and
   *     resolvedConfig paths are both null)
   */
  static Components build(
      ResolvedConfig resolvedConfig,
      Path fallbackIndexPath,
      Path indexPath,
      boolean readOnly,
      FieldMapper fieldMapper,
      SsotAnalyzerRegistry analyzerRegistry,
      KnnVectorsFormat knnVectorsFormatOverride,
      SoftDeletesMetrics softDeletesMetrics,
      IndexOpenGuard indexOpenGuard,
      AtomicLong lastRefreshNanos,
      NrtReopenStats nrtStats,
      long nrtTargetMaxStaleMsDefault,
      long nrtHardMaxStaleMsDefault)
      throws IOException {
    ResolvedConfig rc = resolvedConfig;
    ResolvedConfig.Index idx = rc.index();
    boolean ephemeral = indexPath == null;
    // Path resolution: prefer explicit indexPath, then ResolvedConfig paths, then legacy fallback.
    Path resolvedPath = indexPath;
    if (resolvedPath == null && rc.paths() != null) {
      resolvedPath = rc.paths().indexBasePath();
    }
    if (resolvedPath == null) {
      resolvedPath = fallbackIndexPath;
    }
    if (resolvedPath == null) {
      resolvedPath = Files.createTempDirectory("justsearch-ephemeral-index-");
    } else {
      Files.createDirectories(resolvedPath);
    }

    Directory dir = null;
    IndexWriter w = null;
    DirectoryReader softDeletesReader = null;
    SearcherManager mgr = null;
    ControlledRealTimeReopenThread<IndexSearcher> thread = null;
    try {
      String dirType = idx.directoryType();
      dir =
          switch (dirType != null ? dirType.toUpperCase(java.util.Locale.ROOT) : "MMAP") {
            case "NIOFS" -> new org.apache.lucene.store.NIOFSDirectory(resolvedPath);
            case "SIMPLEFS" -> new org.apache.lucene.store.NIOFSDirectory(resolvedPath);
            default -> new MMapDirectory(resolvedPath);
          };

      if (indexOpenGuard != null && idx.commitMetadataEnabled()) {
        try {
          indexOpenGuard.checkOnOpen();
        } catch (RuntimeException guardFailure) {
          if (!readOnly) {
            throw guardFailure;
          }
          log.warn(
              "Index open guard reported a mismatch at {} but continuing in read-only mode: {}",
              resolvedPath,
              guardFailure.getMessage());
        }
      }

      fieldMapper.validatePrimaryKeySupport();
      fieldMapper.validateRmwPolicies();
      // Guardrail: Detect Lucene field-schema mismatches early (before opening a writer).
      //
      // Without this, schema changes can manifest as "reindex does nothing" in the UI because jobs
      // get enqueued but fail with Lucene field-schema errors like:
      //   cannot change field "mime" from index options=NONE to inconsistent index options=DOCS
      //
      // We classify this as SCHEMA_MISMATCH (not corruption). Recovery policy is handled separately
      // from CORRUPT_INDEX auto-recovery.
      try {
        checkFieldSchemaCompatibility(dir, resolvedPath, fieldMapper);
      } catch (IndexRuntimeIOException e) {
        // In read-only mode (serve-only), schema mismatch should not block startup.
        if (e.reason() == IndexRuntimeIOException.Reason.SCHEMA_MISMATCH && readOnly) {
          log.warn(
              "Index schema mismatch detected at {} but continuing in read-only mode: {}",
              resolvedPath,
              e.getMessage());
        } else {
          throw e;
        }
      }

      // tempdoc 628 G1: bounded open-time integrity verification. Detects silent body/footer
      // corruption (classified CORRUPT_INDEX) so the recovery dispatch can rebuild-from-source
      // instead of serving wrong results. OFF → skip (status is UNVERIFIED, never silently healthy).
      String integrityTier = idx.indexIntegrityCheck();
      if (integrityTier != null && !"OFF".equalsIgnoreCase(integrityTier)) {
        boolean fullScan = "FULL".equalsIgnoreCase(integrityTier);
        // Dirty-open escalation (Gap 1): STRUCTURAL is cheap but misses silent body bit-rot. After an
        // *unclean* shutdown (an absent clean-shutdown marker), escalate to a thorough FULL scan once,
        // so post-crash body corruption is detected without paying FULL on every clean boot. Gated to
        // the read-only/deferred-first open — the deferred writer-upgrade reopens read-write and must
        // not re-pay FULL on a healthy index (the read-only open already verified it).
        //
        // Reads the marker without consuming it (tempdoc 915 O14): this open takes no writer, so it
        // cannot leave the index unclean, and clearing a marker it will never re-write made every
        // subsequent read-only boot report a crash that did not happen. The clear moves to the
        // writer open below.
        if (readOnly && !fullScan) {
          boolean cleanLastShutdown = CleanShutdownMarker.wasClean(resolvedPath);
          if (!cleanLastShutdown) {
            log.info(
                "Unclean previous shutdown detected at {} — running FULL integrity verification.",
                resolvedPath);
            fullScan = true;
          }
        }
        checkIndexIntegrity(dir, resolvedPath, fullScan);
      }

      String idFieldResolved = fieldMapper.idField();
      Map<String, String> fieldAnalyzers = new HashMap<>();
      for (FieldMapper.FieldDef def : fieldMapper.fieldDefs().values()) {
        if (def.analyzerKey != null) {
          fieldAnalyzers.put(def.id, def.analyzerKey);
        }
      }
      Analyzer analyzer = analyzerRegistry.buildPerFieldAnalyzer(fieldAnalyzers);

      int hnswM = idx.effectiveVectorHnswM();
      int efConstruction = idx.effectiveVectorHnswEfConstruction();
      boolean quantEnabled = Boolean.TRUE.equals(idx.vectorQuantizationEnabled());
      // F6: Select vector format based on quantization config
      KnnVectorsFormat kf =
          knnVectorsFormatOverride == null
              ? (quantEnabled
                  ? JustSearchCodec.quantizedFormat(hnswM, efConstruction)
                  : JustSearchCodec.float32Format(hnswM, efConstruction))
              : knnVectorsFormatOverride;
      IndexWriterConfig cfg = new IndexWriterConfig(analyzer);
      cfg.setCodec(new JustSearchCodec(kf));
      // Merge policy knobs (configurable)
      TieredMergePolicy tmp = new TieredMergePolicy();
      Integer segsPerTier = idx.mergeTieredSegsPerTier();
      // Default segs_per_tier=15 (up from Lucene's 10) for +15% fewer merge operations
      tmp.setSegmentsPerTier(segsPerTier != null ? segsPerTier : 15);
      Integer maxMergedMb = idx.mergeTieredMaxMergedSegmentMb();
      if (maxMergedMb != null) tmp.setMaxMergedSegmentMB(maxMergedMb);
      String sdField = idx.softDeletesField();
      String softDeleteFieldResolved = sdField != null ? sdField : "_soft_delete";
      cfg.setSoftDeletesField(softDeleteFieldResolved);
      Supplier<Query> retentionSupplier =
          () ->
              buildSoftDeleteRetentionQuery(
                  softDeleteFieldResolved,
                  idx.softDeletesRetentionDays(),
                  idx.softDeletesRetentionMaxVersions());
      MergePolicy mergePolicy = tmp;
      SoftDeletesMetrics metrics = softDeletesMetrics;
      if (Boolean.TRUE.equals(idx.softDeletesRetentionEnabled())) {
        if (metrics != null) {
          mergePolicy =
              new TelemetrySoftDeletesMergePolicy(
                  softDeleteFieldResolved,
                  retentionSupplier,
                  tmp,
                  metrics::onDocsKept,
                  metrics::onDocsPurged);
        } else {
          mergePolicy =
              new SoftDeletesRetentionMergePolicy(softDeleteFieldResolved, retentionSupplier, tmp);
        }
      } else if (metrics != null) {
        mergePolicy =
            new TelemetrySoftDeletesMergePolicy(
                softDeleteFieldResolved,
                retentionSupplier,
                tmp,
                metrics::onDocsKept,
                metrics::onDocsPurged);
      }
      cfg.setMergePolicy(mergePolicy);
      // Writer memory knobs
      // Default RAM buffer 64MB (up from Lucene's ~16MB) for +20-30% indexing throughput
      Integer rb = idx.writerRamBufferMb();
      cfg.setRAMBufferSizeMB(rb != null ? rb : 64);
      Integer mbd = idx.writerMaxBufferedDocs();
      if (mbd != null) cfg.setMaxBufferedDocs(mbd);
      // Similarity (text)
      String simType = idx.similarityTextType();
      if ("bm25".equalsIgnoreCase(simType)) {
        float k1 =
            idx.similarityTextK1() != null ? idx.similarityTextK1().floatValue() : 0.9f;
        float b = idx.similarityTextB() != null ? idx.similarityTextB().floatValue() : 0.4f;
        cfg.setSimilarity(new org.apache.lucene.search.similarities.BM25Similarity(k1, b));
      }
      // Index-time sort (optional) — reads from ResolvedConfig.Index.sort().
      List<ResolvedConfig.Index.IndexSortItem> sortItems = idx.sort();
      if (!sortItems.isEmpty()) {
        List<SortField> sfs = new ArrayList<>();
        for (ResolvedConfig.Index.IndexSortItem it : sortItems) {
          FieldMapper.FieldDef def = fieldMapper.fieldDef(it.field());
          if (def == null)
            throw new IllegalStateException("index.sort field not in catalog: " + it.field());
          if (!def.docValues)
            throw new IllegalStateException("index.sort field lacks DocValues: " + it.field());
          SortField.Type t;
          switch (def.type) {
            case "keyword" -> t = SortField.Type.STRING;
            case "long", "boolean" -> t = SortField.Type.LONG;
            default ->
                throw new IllegalStateException(
                    "index.sort unsupported field type: " + def.type);
          }
          boolean reverse = Boolean.TRUE.equals(it.reverse());
          sfs.add(new SortField(def.id, t, reverse));
        }
        cfg.setIndexSort(new Sort(sfs.toArray(SortField[]::new)));
      }

      // --- Shared setup for both read-only and read-write paths ---
      int efSearchResolved = idx.vectorEfSearch() != null ? idx.vectorEfSearch() : 100;
      validateVectorDimension(idx.vectorDimension(), fieldMapper);
      Integer cfgTarget = idx.nrtTargetMaxStaleMs();
      Integer cfgHard = idx.nrtHardMaxStaleMs();
      long nrtTargetMs =
          cfgTarget != null && cfgTarget >= 0 ? cfgTarget : nrtTargetMaxStaleMsDefault;
      long nrtHardMs = cfgHard != null && cfgHard >= 0 ? cfgHard : nrtHardMaxStaleMsDefault;
      // Tempdoc 885 item 19. In on_demand mode the background thread is the safety net, not the
      // primary path: it runs at index.nrt.background_reopen_ms on BOTH Lucene bounds, and the
      // foreground refresh in SearcherBridge is what makes a new document visible to a query.
      NrtMode nrtMode = NrtMode.parse(idx.nrtMode());
      long nrtBackgroundMs = Math.max(1L, idx.nrtBackgroundReopenMs());
      long nrtOnDemandMaxStaleMs = Math.max(0L, idx.nrtOnDemandMaxStaleMs());
      long reopenTargetMs = nrtMode == NrtMode.ON_DEMAND ? nrtBackgroundMs : nrtTargetMs;
      long reopenHardMs = nrtMode == NrtMode.ON_DEMAND ? nrtBackgroundMs : nrtHardMs;
      NrtReopenStats stats = nrtStats != null ? nrtStats : new NrtReopenStats();

      if (readOnly) {
        try {
          if (!DirectoryReader.indexExists(dir)) {
            throw new IOException(
                "Read-only open requested but no index exists at " + resolvedPath);
          }
        } catch (IOException e) {
          throw new IndexRuntimeIOException(
              IndexRuntimeIOException.Reason.CORRUPT_INDEX,
              "Failed to determine whether an index exists at " + resolvedPath,
              e);
        }

        softDeletesReader =
            new SoftDeletesDirectoryReaderWrapper(
                DirectoryReader.open(dir), softDeleteFieldResolved);
        mgr = new SearcherManager(softDeletesReader, newNoCacheSearcherFactory());
        stats.install(mgr, lastRefreshNanos, null);

        return new Components(
            idx.commitMetadataEnabled(),
            dir,
            null,
            mgr,
            null,
            resolvedPath,
            ephemeral,
            softDeleteFieldResolved,
            idFieldResolved,
            kf,
            efSearchResolved,
            analyzer,
            rc,
            nrtTargetMs,
            nrtHardMs,
            nrtMode,
            nrtOnDemandMaxStaleMs,
            reopenTargetMs,
            reopenHardMs);
      }

      w = new IndexWriter(dir, cfg);
      // A writer now exists, so this session CAN die mid-commit: invalidate the previous clean
      // shutdown. RuntimeSession.close() re-writes it if this writer closes cleanly. Deliberately
      // outside the integrity-tier block above — whether the next boot scans is a separate question
      // from whether this session could dirty the index.
      CleanShutdownMarker.consume(resolvedPath);
      // A fresh writer has no durable index until its first commit. Publish only after creating a
      // neutral empty commit so crash recovery can reopen this generation read-only. The first real
      // CommitOps commit replaces its metadata; zero-doc parity deliberately ignores that metadata.
      if (!DirectoryReader.indexExists(dir)) {
        w.commit();
      }
      softDeletesReader =
          new SoftDeletesDirectoryReaderWrapper(
              DirectoryReader.open(w, /*applyAllDeletes=*/ true, /*writeAllDeletes=*/ true),
              softDeleteFieldResolved);
      mgr = new SearcherManager(softDeletesReader, newNoCacheSearcherFactory());
      stats.install(mgr, lastRefreshNanos, w);
      thread = NrtReopenThreads.create(w, mgr, reopenTargetMs, reopenHardMs);

      return new Components(
          idx.commitMetadataEnabled(),
          dir,
          w,
          mgr,
          thread,
          resolvedPath,
          ephemeral,
          softDeleteFieldResolved,
          idFieldResolved,
          kf,
          efSearchResolved,
          analyzer,
          rc,
          nrtTargetMs,
          nrtHardMs,
          nrtMode,
          nrtOnDemandMaxStaleMs,
          reopenTargetMs,
          reopenHardMs);
    } catch (Exception e) {
      // Best-effort cleanup to avoid leaking file handles (especially on Windows).
      try {
        if (thread != null) thread.close();
      } catch (Exception ex) {
        log.warn("Cleanup failed: thread.close(): {}", ex.getMessage());
      }
      try {
        if (mgr != null) mgr.close();
      } catch (Exception ex) {
        log.warn("Cleanup failed: mgr.close(): {}", ex.getMessage());
      }
      try {
        if (softDeletesReader != null && mgr == null) softDeletesReader.close();
      } catch (Exception ex) {
        log.warn("Cleanup failed: softDeletesReader.close(): {}", ex.getMessage());
      }
      try {
        if (w != null) w.close();
      } catch (Exception ex) {
        log.warn("Cleanup failed: writer.close(): {}", ex.getMessage());
      }
      try {
        if (dir != null) dir.close();
      } catch (Exception ex) {
        log.warn("Cleanup failed: dir.close(): {}", ex.getMessage());
      }

      if (e instanceof IOException ioe) {
        throw ioe;
      }
      if (e instanceof RuntimeException re) {
        throw re;
      }
      throw new IOException("Failed to build Lucene components", e);
    }
  }

  /**
   * Returns a SearcherFactory that disables the query cache (shared by read-only and read-write
   * paths).
   */
  private static SearcherFactory newNoCacheSearcherFactory() {
    return new SearcherFactory() {
      @Override
      public IndexSearcher newSearcher(IndexReader reader, IndexReader previous) throws IOException {
        IndexSearcher searcher = new IndexSearcher(reader);
        searcher.setQueryCache(null);
        return searcher;
      }
    };
  }

  /** Validates that the configured vector dimension matches the SSOT dimension. */
  private static void validateVectorDimension(
      Integer configuredDimension, FieldMapper fieldMapper) {
    if (configuredDimension != null) {
      Integer ssotDim = fieldMapper.ssotVectorDimensionOrNull();
      if (ssotDim != null && !ssotDim.equals(configuredDimension)) {
        throw new IllegalStateException(
            "Configured vector.dimension="
                + configuredDimension
                + " does not match SSOT dimension="
                + ssotDim);
      }
    }
  }

  /**
   * Best-effort schema compatibility check for on-disk indexes.
   *
   * <p>Lucene enforces that a field's schema (IndexOptions, DocValuesType) is consistent across all
   * segments. If our field mapping changes between runs, indexing will start failing at runtime.
   * This check fails fast so the caller can apply an explicit recovery policy.
   */
  static void checkFieldSchemaCompatibility(
      Directory dir, Path resolvedPath, FieldMapper fieldMapper) {
    if (dir == null || resolvedPath == null) {
      return;
    }

    try {
      if (!DirectoryReader.indexExists(dir)) {
        return;
      }
    } catch (IOException e) {
      throw new IndexRuntimeIOException(
          IndexRuntimeIOException.Reason.CORRUPT_INDEX,
          "Failed to determine whether an index exists at " + resolvedPath,
          e);
    }

    try (DirectoryReader reader = DirectoryReader.open(dir)) {
      FieldInfos infos = FieldInfos.getMergedFieldInfos(reader);
      if (infos == null) {
        return;
      }

      String primaryKey = fieldMapper.idField();
      List<String> mismatches = new ArrayList<>();

      for (FieldMapper.FieldDef def : fieldMapper.fieldDefs().values()) {
        if (def == null) continue;
        if (!"keyword".equals(def.type) || !def.docValues) continue;

        boolean shouldBeIndexed =
            def.id != null
                && (def.id.equals(primaryKey)
                    || (def.roles != null && def.roles.contains("filter")));

        IndexOptions expectedIndexOptions = shouldBeIndexed ? IndexOptions.DOCS : IndexOptions.NONE;
        DocValuesType expectedDocValues =
            def.multiValued ? DocValuesType.SORTED_SET : DocValuesType.SORTED;

        FieldInfo fi = infos.fieldInfo(def.id);
        if (fi == null) {
          continue;
        }

        if (fi.getIndexOptions() != expectedIndexOptions) {
          mismatches.add(
              def.id
                  + "(indexOptions "
                  + fi.getIndexOptions()
                  + " != "
                  + expectedIndexOptions
                  + ")");
        }
        if (fi.getDocValuesType() != expectedDocValues) {
          mismatches.add(
              def.id
                  + "(docValues "
                  + fi.getDocValuesType()
                  + " != "
                  + expectedDocValues
                  + ")");
        }

        // Avoid dumping huge lists if many fields mismatch; a few is enough to diagnose.
        if (mismatches.size() >= 8) {
          break;
        }
      }

      if (!mismatches.isEmpty()) {
        throw new IndexRuntimeIOException(
            IndexRuntimeIOException.Reason.SCHEMA_MISMATCH,
            "Index schema mismatch at "
                + resolvedPath
                + " (examples: "
                + mismatches
                + "). The on-disk index was created with an older field mapping and is not"
                + " writable under the current schema.",
            null);
      }

    } catch (IOException e) {
      throw new IndexRuntimeIOException(
          IndexRuntimeIOException.Reason.CORRUPT_INDEX,
          "Failed to inspect index schema at " + resolvedPath,
          e);
    }
  }

  /**
   * Bounded open-time integrity verification (tempdoc 628 G1).
   *
   * <p>Lucene's {@link DirectoryReader#open} validates segment headers but does not recompute file
   * checksums on a normal open, so silent body bit-rot in a segment that crashed mid-write can be
   * served as wrong results with no signal. This verifies Lucene footer checksums so corruption is
   * <em>detected</em> and classified as {@code CORRUPT_INDEX} (flowing to the recovery dispatch)
   * instead of silently-wrong.
   *
   * <p>{@code fullScan=false} (STRUCTURAL) verifies only the small commit file ({@code segments_N})
   * and per-segment {@code .si} info files — cheap enough to run on every open. {@code fullScan=true}
   * (FULL) additionally verifies every segment data file's checksum, catching body bit-rot at
   * O(index size) cost. Caller gates on the {@code index.integrity_check} tier.
   */
  static void checkIndexIntegrity(Directory dir, Path resolvedPath, boolean fullScan) {
    if (dir == null || resolvedPath == null) {
      return;
    }
    try {
      if (!DirectoryReader.indexExists(dir)) {
        return;
      }
    } catch (IOException e) {
      throw new IndexRuntimeIOException(
          IndexRuntimeIOException.Reason.CORRUPT_INDEX,
          "Failed to determine whether an index exists at " + resolvedPath,
          e);
    }

    try {
      org.apache.lucene.index.SegmentInfos sis =
          org.apache.lucene.index.SegmentInfos.readLatestCommit(dir);
      List<String> toVerify = new ArrayList<>();
      String segmentsFile = sis.getSegmentsFileName();
      if (segmentsFile != null) {
        toVerify.add(segmentsFile);
      }
      // files(false) excludes the segments_N file (added above); STRUCTURAL keeps only the small
      // .si segment-info files, FULL keeps every referenced data/compound file.
      for (String f : sis.files(false)) {
        if (fullScan || f.endsWith(".si")) {
          toVerify.add(f);
        }
      }
      for (String file : toVerify) {
        try (org.apache.lucene.store.IndexInput in =
            dir.openInput(file, org.apache.lucene.store.IOContext.READONCE)) {
          org.apache.lucene.codecs.CodecUtil.checksumEntireFile(in);
        }
      }
    } catch (org.apache.lucene.index.CorruptIndexException
        | org.apache.lucene.index.IndexFormatTooOldException
        | org.apache.lucene.index.IndexFormatTooNewException e) {
      throw new IndexRuntimeIOException(
          IndexRuntimeIOException.Reason.CORRUPT_INDEX,
          "Index integrity verification failed at " + resolvedPath + ": " + e.getMessage(),
          e);
    } catch (IOException e) {
      throw new IndexRuntimeIOException(
          IndexRuntimeIOException.Reason.CORRUPT_INDEX,
          "Failed to verify index integrity at " + resolvedPath,
          e);
    }
  }
}
