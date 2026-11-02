/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.splade;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

final class SpladeTruncationEvidence {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int DEFAULT_FLUSH_EVERY_DOCUMENTS = 500;

  private final int maxSequenceLength;
  private final int windowOverlapTokens;
  private final int flushEveryDocuments;
  private final LongAdder documentsEncoded = new LongAdder();
  private final LongAdder totalObservedTokens = new LongAdder();
  private final AtomicInteger maxObservedTokens = new AtomicInteger();
  private final AtomicLong lastFlushedDocumentCount = new AtomicLong();
  private final ConcurrentHashMap<Integer, LongAdder> windowHistogram = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, LongAdder> tokenHistogram = new ConcurrentHashMap<>();

  SpladeTruncationEvidence(int maxSequenceLength, int windowOverlapTokens) {
    this(maxSequenceLength, windowOverlapTokens, DEFAULT_FLUSH_EVERY_DOCUMENTS);
  }

  SpladeTruncationEvidence(int maxSequenceLength, int windowOverlapTokens, int flushEveryDocuments) {
    this.maxSequenceLength = maxSequenceLength;
    this.windowOverlapTokens = windowOverlapTokens;
    this.flushEveryDocuments = Math.max(1, flushEveryDocuments);
  }

  void record(int observedTokenCount) {
    int safeCount = Math.max(0, observedTokenCount);
    documentsEncoded.increment();
    totalObservedTokens.add(safeCount);
    updateMaxObservedTokens(safeCount);

    int windows = deriveWindowCount(safeCount, maxSequenceLength, windowOverlapTokens);
    windowHistogram.computeIfAbsent(windows, ignored -> new LongAdder()).increment();
    tokenHistogram.computeIfAbsent(tokenBucket(safeCount, maxSequenceLength), ignored -> new LongAdder())
        .increment();
  }

  boolean hasEvidence() {
    return documentsEncoded.sum() > 0;
  }

  Map<String, Object> snapshot(Path modelPath) {
    long docs = documentsEncoded.sum();
    long truncated = 0L;
    long observedTokens = totalObservedTokens.sum();

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("capturedAt", Instant.now().toString());
    summary.put("modelPath", modelPath == null ? null : modelPath.toAbsolutePath().toString());
    summary.put("maxSequenceLength", maxSequenceLength);
    summary.put("derivedWindowOverlapTokens", windowOverlapTokens);
    summary.put("documentsEncoded", docs);
    summary.put("documentsTruncated", truncated);
    summary.put("truncationRate", docs == 0 ? 0.0d : ((double) truncated) / docs);
    summary.put("maxObservedTokens", maxObservedTokens.get());
    summary.put("meanObservedTokens", docs == 0 ? 0.0d : ((double) observedTokens) / docs);

    Map<String, Long> windowCounts = new TreeMap<>();
    windowHistogram.forEach((windowCount, counter) -> windowCounts.put(String.valueOf(windowCount), counter.sum()));
    summary.put("derivedWindowCountHistogram", windowCounts);

    Map<String, Long> tokenBuckets = new LinkedHashMap<>();
    tokenHistogram.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> tokenBuckets.put(entry.getKey(), entry.getValue().sum()));
    summary.put("tokenCountBuckets", tokenBuckets);
    return summary;
  }

  void write(Path outputPath, Path modelPath) {
    if (outputPath == null || !hasEvidence()) {
      return;
    }
    try {
      Path parent = outputPath.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Path tempPath = parent == null
          ? outputPath.resolveSibling(outputPath.getFileName() + ".tmp")
          : Files.createTempFile(parent, outputPath.getFileName().toString(), ".tmp");
      JSON.writerWithDefaultPrettyPrinter().writeValue(tempPath.toFile(), snapshot(modelPath));
      Files.move(tempPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
      lastFlushedDocumentCount.set(documentsEncoded.sum());
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write SPLADE truncation evidence to " + outputPath, e);
    }
  }

  void flushIfNeeded(Path outputPath, Path modelPath) {
    if (outputPath == null || !hasEvidence()) {
      return;
    }
    long docs = documentsEncoded.sum();
    if ((docs - lastFlushedDocumentCount.get()) < flushEveryDocuments) {
      return;
    }
    synchronized (this) {
      docs = documentsEncoded.sum();
      if ((docs - lastFlushedDocumentCount.get()) < flushEveryDocuments) {
        return;
      }
      write(outputPath, modelPath);
    }
  }

  static int deriveWindowCount(int tokenCount, int maxSequenceLength, int windowOverlapTokens) {
    int safeTokenCount = Math.max(0, tokenCount);
    int safeMaxSeqLen = Math.max(1, maxSequenceLength);
    if (safeTokenCount <= safeMaxSeqLen) {
      return 1;
    }
    return io.justsearch.indexerworker.embed.onnx.TokenWindows.count(
        safeTokenCount, safeMaxSeqLen, Math.max(0, windowOverlapTokens), safeMaxSeqLen);
  }

  private void updateMaxObservedTokens(int observedTokenCount) {
    int current;
    do {
      current = maxObservedTokens.get();
      if (observedTokenCount <= current) {
        return;
      }
    } while (!maxObservedTokens.compareAndSet(current, observedTokenCount));
  }

  private static String tokenBucket(int tokenCount, int maxSequenceLength) {
    int safeTokenCount = Math.max(0, tokenCount);
    if (safeTokenCount <= maxSequenceLength) {
      return "le_max_seq_len";
    }
    if (safeTokenCount <= maxSequenceLength * 2) {
      return "max_seq_len_to_2x";
    }
    if (safeTokenCount <= maxSequenceLength * 4) {
      return "2x_to_4x";
    }
    return "gt_4x";
  }
}
