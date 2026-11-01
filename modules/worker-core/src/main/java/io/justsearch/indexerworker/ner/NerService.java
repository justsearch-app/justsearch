/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ner;

import ai.onnxruntime.OrtException;
import io.justsearch.indexing.chunking.ChunkSplitter;
import io.justsearch.ort.OrtCudaStatus;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates NER extraction: chunks text, runs ONNX inference, deduplicates entities.
 *
 * <p>Tempdoc 397 §14.24 FD-NER: constructor takes a pre-built {@link NerAssembly} — tokenizer
 * and label mapping are loaded eagerly by the composition root, not lazily on first use.
 * Metadata-load failures surface at {@code InferenceCompositionRoot.composeNer} time rather
 * than at first {@link #extractEntities} call. Thread-safe.
 */
public final class NerService implements Closeable {

  private static final Logger log = LoggerFactory.getLogger(NerService.class);

  /**
   * Target tokens per chunk for NER inference. Smaller than the default ChunkSplitter target (500)
   * to leave headroom for WordPiece expansion under the 512 token limit.
   */
  private static final int NER_CHUNK_TOKENS = 400;

  private static final int NER_OVERLAP_TOKENS = 50;

  private final NerAssembly ownedAssembly;
  private boolean cleanupComplete;
  private final NerConfig config;
  private final BertNerInference inference; // null when config is DISABLED / no assembly
  private final BioTagDecoder.LabelMapping labelMapping; // null when inference == null
  private final AtomicBoolean closed = new AtomicBoolean(false);

  /**
   * Primary constructor (tempdoc 397 §14.24 FD-NER). Creates a NerService with a pre-built
   * {@link NerAssembly} produced by {@code InferenceCompositionRoot.composeNerAssembly(...)} or by
   * {@link BertNerInference#buildAssembly} for dev-mode fallback paths.
   */
  public NerService(NerAssembly assembly, NerConfig config) {
    this(assembly, config, true);
  }

  /** Borrows an assembly retained by the server surface. */
  public static NerService borrowing(NerAssembly assembly, NerConfig config) {
    return new NerService(assembly, config, false);
  }

  private NerService(NerAssembly assembly, NerConfig config, boolean ownsAssembly) {
    this.ownedAssembly = ownsAssembly ? assembly : null;
    this.config = config;
    this.inference =
        new BertNerInference(assembly);
    this.labelMapping = assembly.labelMapping();
  }

  /**
   * No-op constructor for disabled configurations. When {@code config.isReady() == false} there
   * is no model to load; {@link #isAvailable} returns false and {@link #extractEntities}
   * short-circuits to {@link NerResult#EMPTY}. Used by test harnesses that exercise the disabled
   * path without needing a real model on disk.
   */
  public NerService(NerConfig config) {
    this.ownedAssembly = null;
    this.config = config;
    this.inference = null;
    this.labelMapping = null;
  }



  /** Returns true if NER is configured, the inference pipeline is built, and not closed. */
  public boolean isAvailable() {
    return config.isReady() && inference != null && !closed.get();
  }

  /**
   * Whether NER bulk work runs on the GPU (Spec E step E3): the bulk hold defers backfill only when
   * a bulk encoder is on the GPU. False when NER is not built.
   */
  public boolean isGpuAvailable() {
    return inference != null && inference.isGpuAvailable();
  }

  /**
   * Tempdoc 422: exposes the underlying ONNX session's OrtCuda status so the {@code
   * /api/inference/encoders} explainer can report runtime accelerator state for the NER encoder.
   * Returns {@code null} when NER is disabled (no inference pipeline built).
   */
  public OrtCudaStatus getOrtCudaStatus() {
    return inference != null ? inference.getOrtCudaStatus() : null;
  }

  /**
   * Extracts named entities from document content.
   *
   * @param content full document text
   * @return extracted entities (persons, organizations, locations)
   */
  public NerResult extractEntities(String content) {
    if (content == null || content.isBlank() || !isAvailable()) {
      return NerResult.EMPTY;
    }

    List<String> chunks = ChunkSplitter.split(content, NER_CHUNK_TOKENS, NER_OVERLAP_TOKENS);
    if (chunks.isEmpty()) {
      return NerResult.EMPTY;
    }

    LinkedHashSet<String> persons = new LinkedHashSet<>();
    LinkedHashSet<String> organizations = new LinkedHashSet<>();
    LinkedHashSet<String> locations = new LinkedHashSet<>();

    for (String chunk : chunks) {
      try {
        BertNerInference.InferenceOutput output = inference.infer(chunk);
        List<BioTagDecoder.Entity> entities =
            BioTagDecoder.decode(output.logits(), output.tokens(), output.wordIds(), labelMapping);

        for (BioTagDecoder.Entity entity : entities) {
          if (entity.confidence() < config.confidenceThreshold()) {
            continue;
          }
          switch (entity.type()) {
            case "person" -> persons.add(entity.text());
            case "organization" -> organizations.add(entity.text());
            case "location" -> locations.add(entity.text());
            default -> {
              /* filtered */
            }
          }
        }
      } catch (OrtException e) {
        throw new IllegalStateException("NER inference failed for chunk (length=" + chunk.length() + ")", e);
      }
    }

    return new NerResult(
        new ArrayList<>(persons), new ArrayList<>(organizations), new ArrayList<>(locations));
  }

  /**
   * Batch NER extraction for multiple documents. Chunks all documents, collects all chunks into a
   * flat list, runs batched GPU inference, then maps results back to per-doc entities.
   *
   * <p>Uses batched inference when GPU is available (reduces per-call overhead from N individual
   * session.run() calls to ceil(N/batchSize) calls). Falls back to per-doc inference on CPU.
   *
   * @param contents list of document contents
   * @return list of NER results, one per input document (same order)
   */
  public List<NerResult> extractEntitiesBatch(List<String> contents) {
    if (contents == null || contents.isEmpty()) {
      return List.of();
    }
    if (!isAvailable()) {
      return contents.stream().map(c -> NerResult.EMPTY).toList();
    }

    // If GPU not available, fall back to per-doc (no batching benefit on CPU)
    if (!inference.isGpuAvailable()) {
      return contents.stream().map(this::extractEntities).toList();
    }

    // Phase 1: Chunk all documents and build a flat list of chunks
    int n = contents.size();
    List<String> allChunks = new ArrayList<>();
    int[] chunkStartPerDoc = new int[n];
    int[] chunkCountPerDoc = new int[n];

    for (int d = 0; d < n; d++) {
      chunkStartPerDoc[d] = allChunks.size();
      String content = contents.get(d);
      if (content == null || content.isBlank()) {
        chunkCountPerDoc[d] = 0;
        continue;
      }
      List<String> chunks = ChunkSplitter.split(content, NER_CHUNK_TOKENS, NER_OVERLAP_TOKENS);
      chunkCountPerDoc[d] = chunks.size();
      allChunks.addAll(chunks);
    }

    if (allChunks.isEmpty()) {
      return contents.stream().map(c -> NerResult.EMPTY).toList();
    }

    // Phase 2: Batch inference on all chunks
    List<BertNerInference.InferenceOutput> allOutputs;
    try {
      allOutputs = inference.inferBatch(allChunks);
    } catch (OrtException e) {
      log.warn("Batched NER inference failed, falling back to per-doc: {}", e.getMessage());
      return contents.stream().map(this::extractEntities).toList();
    }

    // Phase 3: Decode and group results back to per-doc
    List<NerResult> results = new ArrayList<>(n);
    for (int d = 0; d < n; d++) {
      if (chunkCountPerDoc[d] == 0) {
        results.add(NerResult.EMPTY);
        continue;
      }

      LinkedHashSet<String> persons = new LinkedHashSet<>();
      LinkedHashSet<String> organizations = new LinkedHashSet<>();
      LinkedHashSet<String> locations = new LinkedHashSet<>();

      int start = chunkStartPerDoc[d];
      int end = start + chunkCountPerDoc[d];
      for (int c = start; c < end; c++) {
        BertNerInference.InferenceOutput output = allOutputs.get(c);
        List<BioTagDecoder.Entity> entities =
            BioTagDecoder.decode(
                output.logits(), output.tokens(), output.wordIds(), labelMapping);

        for (BioTagDecoder.Entity entity : entities) {
          if (entity.confidence() < config.confidenceThreshold()) {
            continue;
          }
          switch (entity.type()) {
            case "person" -> persons.add(entity.text());
            case "organization" -> organizations.add(entity.text());
            case "location" -> locations.add(entity.text());
            default -> {
              /* filtered */
            }
          }
        }
      }

      results.add(
          new NerResult(
              new ArrayList<>(persons),
              new ArrayList<>(organizations),
              new ArrayList<>(locations)));
    }

    return results;
  }

  @Override
  public synchronized void close() {
    if (cleanupComplete) return;
    closed.set(true);
    try {
      if (ownedAssembly != null && !ownedAssembly.tryClose()) return;
      if (inference != null) inference.close();
      cleanupComplete = true;
    } catch (Exception e) {
      log.debug("NER inference close failed (non-fatal)", e);
    }
  }
}
