/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.app.api.DocumentService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 565 §3.A — resolves an agent answer's per-sentence inline citations by reusing the RAG
 * answer↔source matcher ({@link DocumentService#matchCitations}). The matcher keys sources by
 * indexed-chunk identity ({@code parentDocId}+{@code chunkIndex}), which the agent search hits now
 * carry (565 §2a), so the SAME authoritative matcher the RAG path uses runs over the agent answer —
 * no second matching authority.
 *
 * <p>This is the inline-mark ENRICHMENT layer on top of the always-attached grounding sources: it
 * degrades to source-only (no inline marks) on any failure/timeout/empty rather than silent-zeroing
 * into a dead feature (the 565 §10 guard). The answer always cites verifiable local passages; the
 * matcher only adds which sentence cites which source.
 */
final class AgentCitationResolver {

  private static final Logger LOG = LoggerFactory.getLogger(AgentCitationResolver.class);
  /**
   * Cosine-similarity floor for an answer sentence to count as grounded by a source chunk — the ONE
   * shared cutoff (tempdoc 565 §15.A), read from the matcher API contract so the agent and RAG paths
   * cite identically (was a divergent local 0.45).
   */
  private static final double DEFAULT_SIMILARITY_THRESHOLD =
      DocumentService.DEFAULT_CITATION_SIMILARITY_THRESHOLD;
  /** The agent loop blocks at most this long on the matcher before citing sources without marks. */
  private static final long MATCH_TIMEOUT_MS = 4000L;

  private final DocumentService documentService;

  /**
   * The cosine floor actually used. Injected so the agent path and the RAG path read the SAME
   * configured value (tempdoc 565 15.A made this one shared cutoff; tempdoc 799 N.2 wired it to
   * {@code justsearch.citation.match_threshold}). Wiring only one path would recreate the 0.45/0.5
   * divergence 565 removed.
   */
  private final double similarityThreshold;

  AgentCitationResolver(DocumentService documentService) {
    this(documentService, DEFAULT_SIMILARITY_THRESHOLD);
  }

  AgentCitationResolver(DocumentService documentService, double similarityThreshold) {
    this.documentService = documentService;
    // Tempdoc 799 Q: the ONE normaliser, shared with the RAG path (see DocumentService).
    this.similarityThreshold = DocumentService.effectiveCitationThreshold(similarityThreshold);
  }

  /**
   * Tempdoc 859 §4 — the cites AND the producer that scored them.
   *
   * <p>Returning only the cites is the root defect this record removes: {@code
   * DocumentService.CitationMatchResult} reports {@link DocumentService.ScorerKind}, this resolver
   * dropped it at the return, and the FE's 836 §4 producer gate was therefore ungateable on the
   * agent plane — a cosine-fallback similarity reached {@code Citation.similarity}, which every
   * downstream tier reads as a cross-encoder probability. A gate's real boundary is the wire shape,
   * not the function (859 Reach 2).
   *
   * @param cites the inline-citation links (sentence → source index); empty when nothing matched
   * @param scorer which producer wrote the similarities; {@code NONE} whenever no matcher ran
   */
  record Resolved(List<AgentEvent.AgentSentenceCite> cites, DocumentService.ScorerKind scorer) {
    static Resolved none() {
      return new Resolved(List.of(), DocumentService.ScorerKind.NONE);
    }
  }

  /**
   * Match the answer's sentences back to the grounding sources. Returns the inline-citation links
   * (sentence → source index) with the producer that scored them, or {@link Resolved#none()} when
   * matching is unavailable/failed/empty.
   */
  Resolved resolve(String answer, List<AgentEvent.AgentSource> sources) {
    if (documentService == null || answer == null || answer.isBlank() || sources.isEmpty()) {
      return Resolved.none();
    }
    List<DocumentService.ContextCitation> citations = new ArrayList<>(sources.size());
    for (AgentEvent.AgentSource s : sources) {
      // Only parentDocId + chunkIndex drive the authoritative match (it re-fetches the chunk from
      // the index); the other fields are display metadata, defaulted here.
      citations.add(
          new DocumentService.ContextCitation(
              s.parentDocId(), s.chunkIndex(), 1, 0, 0, 0f, s.excerpt(),
              s.startLine(), s.endLine(), s.headingText(), 0,
              DocumentService.ContextInclusion.ABSENT));
    }
    try {
      DocumentService.CitationMatchResult result =
          documentService
              .matchCitations(answer, citations, similarityThreshold)
              .toCompletableFuture()
              .get(MATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      List<AgentEvent.AgentSentenceCite> out = new ArrayList<>();
      for (DocumentService.CitationMatchEntry m : result.matches()) {
        // Tempdoc 822 §3b — the match's `sourceIndex` IS the position in the list handed to the
        // matcher, and that list is built 1:1 from `sources` just above, so it indexes `sources`
        // directly. The old (parentDocId, chunkIndex) re-derivation compared a positional index
        // against a DOCUMENT-relative ordinal and fell back to "first source of the same document"
        // — the wrong-target link this slice removes. Out of range ⇒ no mark, never a fallback.
        int sourceIndex = m.sourceIndex();
        if (sourceIndex >= 0 && sourceIndex < sources.size()) {
          out.add(new AgentEvent.AgentSentenceCite(m.sentenceText(), sourceIndex, m.similarity()));
        }
      }
      // The scorer travels WITH the cites it scored, from the one matcher result. Reporting it
      // separately (or defaulting it at the emitter) would let the two disagree about which
      // producer wrote the similarities the marks are coloured by.
      return new Resolved(List.copyOf(out), result.scorer());
    } catch (Exception e) {
      // 565 §10 guard: never silent-zero into a dead feature — log + degrade to source-only.
      LOG.warn(
          "Answer↔source citation match failed/timed out ({}); citing sources without inline marks",
          e.toString());
      return Resolved.none();
    }
  }
}
