/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.adapters.lucene.runtime.IndexRuntimeConfiguration;
import io.justsearch.adapters.lucene.runtime.LuceneRuntime;
import io.justsearch.configuration.AppliedConfigurationVersion;
import io.justsearch.configuration.ConfigKey;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.extract.ExtractionConfiguration;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/** Projects the exact captured inputs held by the index runtime and its service owners. */
final class IndexConfigurationProjection {
  private IndexConfigurationProjection() {}

  private record Inputs(
      ResolvedConfig snapshot,
      WorkerConfig worker,
      Path basePath,
      DefaultWorkerAppServices services,
      IndexingPacing pacing,
      long deletionGraceMs,
      String appliedTracingSampler) {
    ExtractionConfiguration extraction() { return services.extractionConfiguration(); }
  }

  // One mapping owns both dependency membership and projection; it does not resolve defaults.
  private static final Map<String, Function<Inputs, Object>> LOCAL = Map.ofEntries(
      env(EnvRegistry.DATA_DIR, i -> path(i.worker().dataDir())),
      env(EnvRegistry.MODELS_DIR, i -> path(i.snapshot().paths().modelsDir())),
      env(EnvRegistry.REPO_ROOT, i -> path(i.snapshot().paths().repoRoot())),
      env(EnvRegistry.RERANK_MODEL_PATH, i -> discoveredModel(i.services(), "reranker")),
      env(EnvRegistry.INDEX_BASE_PATH, i -> path(i.basePath())),
      env(EnvRegistry.TELEMETRY_FLUSH_MS, i -> i.worker().telemetryFlushMs()),
      env(EnvRegistry.INDEXER_WORKER_VERSION, i -> i.worker().serviceVersion()),
      env(EnvRegistry.SPARSE_MODEL, i -> i.snapshot().ai().sparseModel()),
      env(EnvRegistry.INDEX_SCHEMA_MISMATCH_POLICY, i -> i.snapshot().index().schemaMismatchPolicy()),
      env(EnvRegistry.INDEX_MIGRATION_CUTOVER_MAX_FAILED_JOBS,
          i -> i.snapshot().index().migrationCutoverMaxFailedJobs()),
      env(EnvRegistry.INDEX_IDENTITY_DELETION_GRACE_MS, Inputs::deletionGraceMs),
      env(EnvRegistry.DEV_HOTRELOAD, i -> i.snapshot().ai().devHotReload()),
      env(EnvRegistry.INDEX_TRACING_LEVEL, IndexConfigurationProjection::tracing),
      env(EnvRegistry.INDEXING_FOREGROUND_DUTY_PCT, i -> i.pacing().dutyPct()),
      env(EnvRegistry.INDEXING_FOREGROUND_COOLDOWN_MS, i -> i.pacing().cooldownMs()),
      key(ConfigKey.INDEX_AUTO_RECOVERY, i -> i.snapshot().index().indexAutoRecovery()),
      key(ConfigKey.INDEX_INTEGRITY_CHECK, i -> i.snapshot().index().indexIntegrityCheck()),
      key(ConfigKey.INDEX_RECOVERY_POLICY, i -> i.snapshot().index().indexRecoveryPolicy()),
      key(ConfigKey.INDEX_COLLECTIONS, i -> i.snapshot().collections().items().stream()
          .flatMap(c -> c.roots().stream()).map(IndexConfigurationProjection::path).distinct().toList()),
      env(EnvRegistry.BACKFILL_POLL_BATCH_SIZE, i -> i.snapshot().ai().backfillPacing().pollBatchSize()),
      env(EnvRegistry.BACKFILL_EMBEDDING_BATCH_SIZE,
          i -> i.snapshot().ai().backfillPacing().embeddingBackfillBatchSize()),
      env(EnvRegistry.BACKFILL_NER_BATCH_SIZE, i -> i.snapshot().ai().backfillPacing().nerBackfillBatchSize()),
      env(EnvRegistry.BACKFILL_DISAMBIGUATION_BATCH_SIZE,
          i -> i.snapshot().ai().backfillPacing().disambiguationBackfillBatchSize()),
      env(EnvRegistry.BACKFILL_SPLADE_BATCH_SIZE,
          i -> i.snapshot().ai().backfillPacing().spladeBackfillBatchSize()),
      env(EnvRegistry.BACKFILL_SPLADE_INTERLEAVE_BATCH_SIZE,
          i -> i.snapshot().ai().backfillPacing().spladeInterleaveBatchSize()),
      env(EnvRegistry.BACKFILL_SPLADE_INTERLEAVE_INTERVAL_MS,
          i -> i.snapshot().ai().backfillPacing().spladeInterleaveIntervalMs()),
      env(EnvRegistry.BACKFILL_COMMIT_INTERVAL_MS,
          i -> i.snapshot().ai().backfillPacing().commitIntervalMs()),
      env(EnvRegistry.BACKFILL_MAX_DOCS_BEFORE_COMMIT,
          i -> i.snapshot().ai().backfillPacing().maxDocsBeforeCommit()),
      env(EnvRegistry.BACKFILL_CHUNK_SLOTS_PER_BATCH,
          i -> i.snapshot().ai().backfillPacing().chunkSlotsPerBatch()),
      env(EnvRegistry.BACKFILL_BGE_M3_BATCH_SIZE,
          i -> i.snapshot().ai().backfillPacing().bgeM3BackfillBatchSize()),
      env(EnvRegistry.BACKFILL_BGE_M3_INTERLEAVE_BATCH_SIZE,
          i -> i.snapshot().ai().backfillPacing().bgeM3InterleaveBatchSize()),
      env(EnvRegistry.RAG_CHUNK_VECTORS_ENABLED, i -> i.snapshot().rag().chunkVectorsEnabled()),
      env(EnvRegistry.RAG_CHUNK_SPLADE_ENABLED, i -> i.snapshot().rag().chunkSpladeEnabled()),
      env(EnvRegistry.EMBED_LATE_CHUNKING_ENABLED, i -> i.snapshot().ai().embedding().lateChunkingEnabled()),
      key(ConfigKey.INDEX_OCR_ENABLED, i -> i.extraction().ocr().enabled()),
      key(ConfigKey.INDEX_OCR_LANGUAGES, i -> i.extraction().ocr().languages()),
      key(ConfigKey.INDEX_OCR_PER_FILE_TIMEOUT_MS, i -> i.extraction().ocr().perFileTimeoutMs()),
      key(ConfigKey.INDEX_OCR_MAX_PAGES, i -> i.extraction().ocr().maxPages()),
      key(ConfigKey.INDEX_OCR_MAX_IMAGE_DIMENSION, i -> i.extraction().ocr().maxImageDimension()),
      key(ConfigKey.INDEX_OCR_MAX_IMAGE_PIXELS, i -> i.extraction().ocr().maxImagePixels()),
      key(ConfigKey.INDEX_OCR_RENDER_DPI, i -> i.extraction().ocr().effectiveRenderDpi()),
      key(ConfigKey.INDEX_OCR_WORKERS, i -> i.extraction().ocr().effectiveOcrWorkers()),
      env(EnvRegistry.WORKER_MAX_CONTENT_LENGTH, i -> i.extraction().tikaPolicy().maxExtractedChars()),
      env(EnvRegistry.WORKER_MAX_FILE_SIZE, i -> i.extraction().tikaPolicy().maxInputBytes()),
      env(EnvRegistry.EXTRACTION_SANDBOX_MODE, i -> i.extraction().sandboxMode().name()),
      env(EnvRegistry.EXTRACTION_SANDBOX_COMMAND, i -> command(i.extraction())),
      env(EnvRegistry.EXTRACTION_SANDBOX_HEAP, i -> i.extraction().sandboxHeap()),
      env(EnvRegistry.EXTRACTION_SANDBOX_POOL, i -> i.extraction().sandboxPool() == null
          ? null : i.extraction().sandboxPool().poolSize()),
      env(EnvRegistry.EXTRACTION_SANDBOX_MAX_REQUESTS, i -> i.extraction().sandboxPool() == null
          ? null : i.extraction().sandboxPool().maxRequestsPerChild()),
      env(EnvRegistry.INGESTION_SKIP_PATTERNS,
          i -> new TreeSet<>(i.extraction().ingestionSkipPolicy().skipPatterns())),
      env(EnvRegistry.INGESTION_SKIP_EXTENSIONS,
          i -> new TreeSet<>(i.extraction().ingestionSkipPolicy().skipExtensions())),
      env(EnvRegistry.INGESTION_SKIP_DIRECTORY_NAMES,
          i -> new TreeSet<>(i.extraction().ingestionSkipPolicy().skipDirectoryNames())),
      env(EnvRegistry.RERANK_CHUNKS_ENABLED, i -> i.services().chunkRerankerConfig().enabled()),
      env(EnvRegistry.RERANK_CHUNKS_MODEL_PATH, i -> path(i.services().chunkRerankerConfig().modelPath())),
      env(EnvRegistry.RERANK_CHUNKS_TOP_K, i -> i.services().chunkRerankerConfig().topK()),
      env(EnvRegistry.RERANK_CHUNKS_MAX_GPU_CANDIDATES,
          i -> i.services().chunkRerankerConfig().maxGpuCandidates()),
      env(EnvRegistry.RERANK_CHUNKS_DEADLINE_MS,
          i -> i.services().chunkRerankerConfig().deadlineBudgetMs()),
      env(EnvRegistry.RERANK_CHUNKS_MIN_HITS,
          i -> i.services().chunkRerankerConfig().minHitsThreshold()),
      env(EnvRegistry.RERANK_CHUNKS_MAX_SEQ_LEN,
          i -> i.services().chunkRerankerConfig().maxSequenceLength()),
      env(EnvRegistry.RERANK_CHUNKS_GPU_ENABLED, i -> i.services().chunkRerankerConfig().gpuEnabled()),
      env(EnvRegistry.RERANK_CHUNKS_GPU_DEVICE_ID, i -> i.services().chunkRerankerConfig().gpuDeviceId()),
      env(EnvRegistry.RERANK_CHUNKS_ORDER, i -> i.services().chunkRerankerConfig().order().name()),
      env(EnvRegistry.CITATION_SCORER_ENABLED, i -> i.services().citationScorerConfig().enabled()),
      env(EnvRegistry.CITATION_SCORER_MODEL_PATH, i -> path(i.services().citationScorerConfig().modelPath())),
      env(EnvRegistry.CITATION_SCORER_THRESHOLD, i -> i.services().citationScorerConfig().threshold()),
      env(EnvRegistry.CITATION_SCORER_MAX_SEQ_LEN,
          i -> i.services().citationScorerConfig().maxSequenceLength()),
      env(EnvRegistry.CITATION_SCORER_DEADLINE_MS,
          i -> i.services().citationScorerConfig().deadlineBudgetMs()));

  static Set<String> dependencies() {
    var dependencies = new TreeSet<>(IndexRuntimeConfiguration.dependencies());
    dependencies.addAll(LOCAL.keySet());
    return Set.copyOf(dependencies);
  }

  static String digest(
      ResolvedConfig snapshot, WorkerConfig worker, Path basePath,
      LuceneRuntime runtime, DefaultWorkerAppServices services,
      IndexingPacing pacing, long deletionGraceMs, String appliedTracingSampler) {
    var inputs = new Inputs(snapshot, worker, basePath, services, pacing, deletionGraceMs,
        appliedTracingSampler);
    var values = new TreeMap<String, Object>(runtime.appliedConfigurationValues());
    LOCAL.forEach((key, read) -> {
      if (values.containsKey(key)) {
        throw new IllegalStateException("Two index owners project the same configuration key: " + key);
      }
      values.put(key, read.apply(inputs));
    });
    if (!values.keySet().equals(dependencies())) {
      throw new IllegalStateException("Index configuration projection differs from its dependency set");
    }
    return AppliedConfigurationVersion.digest(dependencies(), values);
  }

  private static Map<String, Object> tracing(Inputs inputs) {
    var result = new TreeMap<String, Object>();
    result.put("ownedSampler", inputs.appliedTracingSampler());
    result.put("detailed", inputs.services().detailedTracing());
    return result;
  }

  private static Map<String, Object> discoveredModel(
      DefaultWorkerAppServices services, String modelName) {
    var model = services.healthService().discoveredModels().stream()
        .filter(candidate -> modelName.equals(candidate.modelName())).findFirst().orElseThrow();
    var result = new TreeMap<String, Object>();
    result.put("found", model.found());
    result.put("path", model.path() == null ? null : path(Path.of(model.path())));
    result.put("autoDiscovered", model.autoDiscovered());
    return result;
  }

  private static Object command(ExtractionConfiguration extraction) {
    return switch (extraction.sandboxCommandOrigin()) {
      case NONE -> null;
      case BUILT_IN -> "BUILT_IN";
      case OPERATOR -> extraction.sandboxCommand();
    };
  }

  private static String path(Path value) {
    return value == null ? null : value.toAbsolutePath().normalize().toString();
  }

  private static Map.Entry<String, Function<Inputs, Object>> env(
      EnvRegistry key, Function<Inputs, Object> read) {
    return Map.entry(key.configKey(), read);
  }

  private static Map.Entry<String, Function<Inputs, Object>> key(
      ConfigKey key, Function<Inputs, Object> read) {
    return Map.entry(key.configKey(), read);
  }
}
