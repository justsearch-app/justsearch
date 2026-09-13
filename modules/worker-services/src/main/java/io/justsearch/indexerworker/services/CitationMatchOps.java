/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.ReadPathOps;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.util.ParseUtils;
import io.justsearch.indexerworker.util.VectorUtils;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.CitationMatchEntry;
import io.justsearch.ipc.MatchCitationsResponse;
import io.justsearch.reranker.CitationScorer;
import io.justsearch.reranker.CitationScorerConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.lucene.search.TermQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Citation matching logic extracted from {@link WorkerSearchService}.
 *
 * <p>Manages the lazy-initialized {@link CitationScorer} (CPU-only ONNX cross-encoder)
 * and provides an embedding-based cosine similarity fallback path.
 */
final class CitationMatchOps {
  private static final Logger log = LoggerFactory.getLogger(CitationMatchOps.class);

  static final double DEFAULT_SIMILARITY_THRESHOLD = 0.5;

  /** Scoring budget used when no {@link CitationScorerConfig} has been wired. */
  static final long DEFAULT_DEADLINE_MS = 2000;

  // Tempdoc 836 §4 — the provenance vocabulary carried on the wire. Two producers write one
  // `similarity` field on measurably incomparable scales; these say which one did.
  static final String SCORER_CROSS_ENCODER = "CROSS_ENCODER";
  static final String SCORER_EMBEDDING_COSINE = "EMBEDDING_COSINE";
  static final String SCORER_NONE = "NONE";
  static final String TEXT_SOURCE_SUPPLIED = "SUPPLIED";
  static final String TEXT_SOURCE_CHUNK_LOOKUP = "CHUNK_LOOKUP";

  private final ReadPathOps readPathOps;
  private final CommitOps commitOps;
  private volatile EmbeddingProvider embeddingProvider;

  // Citation scoring (CPU-only ONNX cross-encoder). Tempdoc 397 §14.26 T2-E1: the composition
  // root builds the scorer eagerly; this class is a pure consumer. No lazy construction path.
  private volatile CitationScorerConfig citationScorerConfig;
  private volatile CitationScorer citationScorer;
  private volatile CrossEncoderProducer crossEncoderProducer;

  /**
   * The cross-encoder producer as a function of its inputs.
   *
   * <p>Seam, not indirection for its own sake: {@code worker-services} deliberately excludes the
   * ONNX runtime from its classpath, so a {@link CitationScorer} cannot be constructed in this
   * module's tests and the cross-encoder branch would otherwise be unreachable by any test here.
   * Production still wires a real scorer through {@link #setCitationScorer}; tests install a
   * deterministic producer through {@link #setCrossEncoderProducer} and exercise the same branch.
   */
  @FunctionalInterface
  interface CrossEncoderProducer {
    CitationScorer.ScoringResult scoreAll(
        List<String> sentences,
        List<String> passages,
        List<String> passageDocIds,
        double threshold,
        long deadlineMs);
  }

  CitationMatchOps(ReadPathOps readPathOps, CommitOps commitOps, EmbeddingProvider embeddingProvider) {
    this.readPathOps = readPathOps;
    this.commitOps = commitOps;
    this.embeddingProvider = embeddingProvider;
  }

  void setEmbeddingProvider(EmbeddingProvider embeddingProvider) {
    this.embeddingProvider = embeddingProvider;
  }

  /**
   * Sets the citation scorer configuration.
   *
   * <p>When config is ready (enabled + model path set), the scorer will be lazily initialized
   * on first use. The scorer runs on CPU only, avoiding GPU contention with the LLM.
   *
   * @param config the citation scorer configuration
   */
  /** Returns true if the citation scorer is initialized and ready for inference. */
  boolean isCitationScorerActive() {
    CitationScorer scorer = citationScorer;
    return scorer != null && scorer.isAvailable();
  }

  /**
   * Sets the eagerly-constructed citation scorer built by the composition root
   * (tempdoc 397 §14.26 T2-E1). Replaces the pre-T2-E1 pair
   * {@code setCitationScorerSessions(SessionHandle)} + lazy {@link #getCitationScorer} init.
   * When null, citation scoring falls back to embedding-based cosine similarity.
   */
  void setCitationScorer(CitationScorer scorer) {
    this.citationScorer = scorer;
    setCrossEncoderProducer(scorer == null ? null : scorer::scoreAll);
    if (scorer != null && citationScorerConfig != null) {
      var config = citationScorerConfig;
      // Tempdoc 374 sandbox round 4 issue H: resolve via ModelManifest so the
      // fingerprint identifies whichever variant Install AI placed on disk.
      Path modelOnnx =
          io.justsearch.ort.ModelManifest.loadOrDefault(config.modelPath())
              .resolveExistingModelFile(config.modelPath());
      String fingerprint = computeModelSha256(modelOnnx);
      if (fingerprint != null) {
        log.info(
            "Citation scorer wired: model={}, sha256={}",
            modelOnnx.getFileName(),
            fingerprint.substring(0, 16) + "...");
      } else {
        log.info(
            "Citation scorer wired: model={} (fingerprint unavailable)", config.modelPath());
      }
    }
  }

  void setCitationScorerConfig(CitationScorerConfig config) {
    this.citationScorerConfig = config;
    if (config != null && config.isReady()) {
      log.info("Citation scorer enabled: threshold={}, maxSeqLen={}, deadline={}ms, modelPath={}",
          config.threshold(), config.maxSequenceLength(), config.deadlineBudgetMs(),
          config.modelPath());
    }
  }

  /**
   * Returns the wired citation scorer, or {@code null} if the composition root did not wire one
   * (dev mode without contract, model absent, or tokenizer load failure). Pure getter —
   * tempdoc 397 §14.26 T2-E1 deleted the former lazy-init path.
   */
  private CitationScorer getCitationScorer() {
    return citationScorer;
  }

  /**
   * Installs the producer the cross-encoder branch calls. Production reaches this through {@link
   * #setCitationScorer}; tests install a deterministic producer directly. See {@link
   * CrossEncoderProducer} for why the branch is expressed as a function.
   */
  void setCrossEncoderProducer(CrossEncoderProducer producer) {
    this.crossEncoderProducer = producer;
  }

  /**
   * The cross-encoder producer, or {@code null} when there is none to run.
   *
   * <p>Tempdoc 836 §4 corrects a common misreading: {@code isAvailable()} is merely {@code
   * !closed}, so it is true for any live scorer. The real triggers for the cosine fallback are a
   * scorer that was never wired and the catch-all around the scoring call.
   */
  private CrossEncoderProducer availableCrossEncoder() {
    CitationScorer scorer = getCitationScorer();
    if (scorer != null && !scorer.isAvailable()) {
      return null;
    }
    return crossEncoderProducer;
  }

  /**
   * Executes citation matching: maps answer sentences to sources, verifying against literal
   * passage text where the caller supplied it (tempdoc 836 §1).
   *
   * <p>Text resolution, windowing and admission control happen ONCE, before the producer branch,
   * so the cross-encoder path and the cosine fallback verify the same text. That shared
   * preparation is the structural half of the fix: a change that pointed only one branch at
   * supplied text would leave the other silently re-fetching chunks.
   *
   * @param passageTexts literal text per source — either empty, or exactly as long as {@code
   *     chunkDocIds} (validated at the gRPC boundary); a blank entry means "look this one up"
   * @return a fully-built MatchCitationsResponse for all paths (success, fallback, error)
   */
  MatchCitationsResponse execute(
      String answerText,
      List<String> chunkDocIds,
      List<Integer> chunkIndices,
      List<String> passageTexts,
      double threshold) {
    long startTime = System.currentTimeMillis();

    log.debug("MatchCitations request: answerLen={}, chunks={}, supplied={}, threshold={}",
        answerText.length(), chunkDocIds.size(), countSupplied(passageTexts), threshold);

    if (answerText.isBlank() || chunkDocIds.isEmpty()) {
      return emptyResponse(startTime, "");
    }

    List<String> sentenceList = AnswerSegmentation.splitSentences(answerText.trim());
    var csConfig = citationScorerConfig;
    long deadlineMs = csConfig != null ? csConfig.deadlineBudgetMs() : DEFAULT_DEADLINE_MS;

    CrossEncoderProducer crossEncoder = availableCrossEncoder();
    if (crossEncoder == null && !embeddingProvider.isAvailable()) {
      // Neither producer exists: nothing is scored, and the response says so rather than
      // reporting an empty result that reads like "nothing was grounded".
      return emptyResponse(startTime, "EMBEDDING_UNAVAILABLE");
    }

    PassageWindows.Prepared prepared;
    try {
      prepared =
          prepareWindows(
              chunkDocIds, chunkIndices, passageTexts, sentenceList.size(), deadlineMs, answerText);
    } catch (Exception e) {
      log.warn("MatchCitations passage preparation failed", e);
      return errorResponse(startTime, e);
    }

    if (prepared.admissionTruncated()) {
      // Tempdoc 836 §3.4 — refusing work the deadline cannot pay for, up front, instead of
      // producing a result the Head has already abandoned.
      log.info(
          "Citation admission control: scoring {} of {} windows ({} sentences, {}ms budget)",
          prepared.windowTexts().size(),
          prepared.windowsConsidered(),
          sentenceList.size(),
          deadlineMs);
    }

    if (crossEncoder != null) {
      try {
        CitationScorer.ScoringResult result =
            crossEncoder.scoreAll(
                sentenceList,
                prepared.windowTexts(),
                prepared.windowDocIds(),
                threshold,
                deadlineMs);

        List<CitationMatchEntry> matches = new ArrayList<>(result.matches().size());
        for (CitationScorer.ScoredMatch match : result.matches()) {
          matches.add(
              entry(
                  prepared,
                  chunkDocIds,
                  match.sentenceIndex(),
                  match.sentenceText(),
                  match.chunkIndex(),
                  match.score()));
        }

        return MatchCitationsResponse.newBuilder()
            .addAllMatches(matches)
            .setSentencesTotal(result.sentencesTotal())
            .setSentencesMatched(result.sentencesMatched())
            .setSentencesScored(result.sentencesScored())
            .setScorer(SCORER_CROSS_ENCODER)
            .addAllSourceCoverage(sourceCoverage(prepared, true))
            .setTookMs(System.currentTimeMillis() - startTime)
            .build();

      } catch (Exception e) {
        log.warn("CitationScorer failed, falling back to embedding path: {}", e.getMessage());
        log.debug("CitationScorer failed (stack trace)", e);
      }
    }

    // Fallback: embedding-based cosine similarity
    if (!embeddingProvider.isAvailable()) {
      // Windows were prepared but nothing scored them: every source reports scored == 0, which is
      // the "never examined" state (tempdoc 836 S2S3-A.1), not "examined and unsupported".
      return emptyResponse(startTime, "EMBEDDING_UNAVAILABLE", sourceCoverage(prepared, false));
    }

    try {
      List<float[]> sentenceVectors = new ArrayList<>(sentenceList.size());
      for (String sentence : sentenceList) {
        sentenceVectors.add(embeddingProvider.embedQuery(sentence));
      }

      List<String> windows = prepared.windowTexts();
      List<float[]> windowVectors = new ArrayList<>(windows.size());
      for (String window : windows) {
        windowVectors.add(embeddingProvider.embedDocument(window));
      }

      List<CitationMatchEntry> matches = new ArrayList<>();
      int sentencesMatched = 0;
      int sentencesScored = 0;
      for (int si = 0; si < sentenceList.size(); si++) {
        float[] sentenceVec = sentenceVectors.get(si);
        if (sentenceVec == null || sentenceVec.length == 0) {
          continue;
        }
        sentencesScored++;
        double bestSim = 0.0;
        int bestWindow = -1;
        for (int wi = 0; wi < windowVectors.size(); wi++) {
          float[] windowVec = windowVectors.get(wi);
          if (windowVec == null || windowVec.length == 0) {
            continue;
          }
          double sim = VectorUtils.cosine(sentenceVec, windowVec);
          if (sim > bestSim) {
            bestSim = sim;
            bestWindow = wi;
          }
        }
        if (bestWindow >= 0 && bestSim >= threshold) {
          sentencesMatched++;
          matches.add(
              entry(prepared, chunkDocIds, si, sentenceList.get(si), bestWindow, bestSim));
        }
      }

      return MatchCitationsResponse.newBuilder()
          .addAllMatches(matches)
          .setSentencesTotal(sentenceList.size())
          .setSentencesMatched(sentencesMatched)
          .setSentencesScored(sentencesScored)
          .setScorer(SCORER_EMBEDDING_COSINE)
          .addAllSourceCoverage(sourceCoverage(prepared, true))
          .setTookMs(System.currentTimeMillis() - startTime)
          .build();

    } catch (Exception e) {
      log.warn("MatchCitations failed", e);
      return errorResponse(startTime, e, sourceCoverage(prepared, false));
    }
  }

  /**
   * The per-source examination facts (tempdoc 836 S2S3-A.1).
   *
   * <p>{@code considered} comes from preparation, {@code scored} from the ADMITTED back-map — so
   * {@code considered > 0 && scored == 0} says "this source's text existed and the budget gave it
   * no window", which a caller must not report as "nothing here supports the claim". When the pass
   * that would have scored the admitted windows did not run (no producer, or it threw), {@code
   * scored} is reported as 0 for every source rather than as the admitted count: nothing was
   * examined, and the response says so.
   */
  private static List<io.justsearch.ipc.SourceCoverage> sourceCoverage(
      PassageWindows.Prepared prepared, boolean scored) {
    List<io.justsearch.ipc.SourceCoverage> out = new ArrayList<>(prepared.sourceCount());
    for (int i = 0; i < prepared.sourceCount(); i++) {
      out.add(
          io.justsearch.ipc.SourceCoverage.newBuilder()
              .setSourceIndex(i)
              .setWindowsConsidered(prepared.windowsConsideredAt(i))
              .setWindowsScored(scored ? prepared.windowsScoredAt(i) : 0)
              .build());
    }
    return out;
  }

  /**
   * Builds a match entry, mapping the scored WINDOW ordinal back to the SOURCE position it must be
   * reported as (tempdoc 836 §1.3). {@code parentDocId} is read from the request array at that
   * source position rather than echoed from the scorer, so {@code parent_doc_id ==
   * chunk_doc_ids[source_index]} holds by construction.
   */
  private static CitationMatchEntry entry(
      PassageWindows.Prepared prepared,
      List<String> chunkDocIds,
      int sentenceIndex,
      String sentenceText,
      int windowOrdinal,
      double score) {
    int sourceIndex = prepared.sourceOf(windowOrdinal);
    return CitationMatchEntry.newBuilder()
        .setSentenceIndex(sentenceIndex)
        .setSentenceText(sentenceText)
        .setSourceIndex(sourceIndex)
        .setSimilarity(score)
        .setParentDocId(chunkDocIds.get(sourceIndex))
        .setTextSource(
            prepared.suppliedAt(sourceIndex) ? TEXT_SOURCE_SUPPLIED : TEXT_SOURCE_CHUNK_LOOKUP)
        .build();
  }

  private PassageWindows.Prepared prepareWindows(
      List<String> chunkDocIds,
      List<Integer> chunkIndices,
      List<String> passageTexts,
      int sentenceCount,
      long deadlineMs,
      String answerText) {
    boolean anyLookupNeeded = false;
    int sourceCount = Math.min(chunkDocIds.size(), chunkIndices.size());
    for (int i = 0; i < sourceCount; i++) {
      String supplied = i < passageTexts.size() ? passageTexts.get(i) : "";
      if (supplied == null || supplied.isBlank()) {
        anyLookupNeeded = true;
        break;
      }
    }
    if (anyLookupNeeded) {
      // Only a request that will actually read the index pays for a reader refresh.
      commitOps.maybeRefresh();
    }
    return PassageWindows.prepare(
        chunkDocIds,
        chunkIndices,
        passageTexts,
        // Tempdoc 836 §8.4 — a NEGATIVE chunk index is the document-level sentinel (the absence of
        // a chunk ordinal, mirroring AgentSession.DOC_LEVEL_SENTINEL), not an ordinal to look up.
        // A source that supplies no text and has no ordinal is unverifiable; searching for chunk
        // "-1" would return nothing anyway, and asking is what makes a fabricated 0 tempting.
        i -> chunkIndices.get(i) < 0 ? null : lookupChunkContent(chunkDocIds.get(i), chunkIndices.get(i)),
        sentenceCount,
        deadlineMs,
        answerText);
  }

  private static int countSupplied(List<String> passageTexts) {
    int n = 0;
    for (String t : passageTexts) {
      if (t != null && !t.isBlank()) {
        n++;
      }
    }
    return n;
  }

  /**
   * An early return taken BEFORE preparation ran: the coverage list is empty because nothing is
   * known about the sources yet — which is a different statement from an entry reporting zero
   * (tempdoc 836 S2S3-A.1).
   */
  private MatchCitationsResponse emptyResponse(long startTime, String error) {
    return emptyResponse(startTime, error, List.of());
  }

  private MatchCitationsResponse emptyResponse(
      long startTime, String error, List<io.justsearch.ipc.SourceCoverage> coverage) {
    return MatchCitationsResponse.newBuilder()
        .setSentencesTotal(0)
        .setSentencesMatched(0)
        .setSentencesScored(0)
        .setScorer(SCORER_NONE)
        .addAllSourceCoverage(coverage)
        .setTookMs(System.currentTimeMillis() - startTime)
        .setError(error)
        .build();
  }

  private MatchCitationsResponse errorResponse(long startTime, Exception e) {
    return errorResponse(startTime, e, List.of());
  }

  private MatchCitationsResponse errorResponse(
      long startTime, Exception e, List<io.justsearch.ipc.SourceCoverage> coverage) {
    return MatchCitationsResponse.newBuilder()
        .setSentencesTotal(0)
        .setSentencesMatched(0)
        .setSentencesScored(0)
        .setScorer(SCORER_NONE)
        .addAllSourceCoverage(coverage)
        .setTookMs(System.currentTimeMillis() - startTime)
        .setError(e.getMessage() == null ? "UNKNOWN" : e.getMessage())
        .build();
  }

  /**
   * Looks up chunk content by parent doc ID and chunk index from the Lucene index.
   *
   * <p>Queries by parent_doc_id (keyword field, term-indexed) and then filters
   * by chunk_index in Java. chunk_index is a long/DocValues field without an
   * inverted index, so it cannot be queried via TermQuery or LongPoint.
   *
   * @return chunk content text, or null if not found
   */
  /** Computes SHA-256 of a model file for fingerprint comparison. */
  private static String computeModelSha256(Path modelFile) {
    if (modelFile == null || !Files.exists(modelFile)) {
      return null;
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8 * 1024 * 1024]; // 8 MB
      try (InputStream in = Files.newInputStream(modelFile)) {
        int read;
        while ((read = in.read(buffer)) != -1) {
          digest.update(buffer, 0, read);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 not available", e);
    } catch (IOException e) {
      log.warn("Failed to compute SHA-256 for {}", modelFile.getFileName(), e);
      return null;
    }
  }

  private String lookupChunkContent(String parentDocId, int chunkIndex) {
    try {
      // Query by parent_doc_id only (term-indexed keyword), fetch enough to find the right chunk
      TermQuery query =
          new TermQuery(
              new org.apache.lucene.index.Term(SchemaFields.PARENT_DOC_ID, parentDocId));
      LuceneRuntimeTypes.SearchResult result = readPathOps.search(query, 500,
          Set.of(SchemaFields.CHUNK_CONTENT, SchemaFields.CHUNK_INDEX), null, null);
      for (var hit : result.hits()) {
        int idx = ParseUtils.parseIntSafe(hit.fields().get(SchemaFields.CHUNK_INDEX), -1);
        if (idx == chunkIndex) {
          return hit.fields().get(SchemaFields.CHUNK_CONTENT);
        }
      }
      return null;
    } catch (Exception e) {
      log.debug("Failed to lookup chunk {}:{}: {}", parentDocId, chunkIndex, e.getMessage());
      return null;
    }
  }
}
