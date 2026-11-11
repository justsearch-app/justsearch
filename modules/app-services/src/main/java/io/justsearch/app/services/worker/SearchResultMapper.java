/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.ipc.SearchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tempdoc 556 (F-C4.2): pure mapping from proto {@link SearchResult} to app-api
 * {@link KnowledgeSearchResponse.Hit}, including descriptor-based forward-compat extraction of
 * match-spans and excerpt-regions, freshness decay (309 §9), and the rerank-snippet helpers used
 * before the cross-encoder call. Extracted from {@code KnowledgeHttpApiAdapter} so the adapter stays
 * a thin orchestrator. Behaviour-preserving verbatim moves.
 */
final class SearchResultMapper {

  /** Internal record for match-span info extracted from proto (rerank snippet extraction). */
  record MatchSpanInfo(String field, int startChar, int endChar, String term) {}

  private SearchResultMapper() {}

  /**
   * Maps the (possibly reranked) proto results to app-api hits. Per-hit trace composition is
   * delegated to {@link SearchTraceMapper}. Freshness decay is applied multiplicatively when
   * {@code freshnessEnabled} is true.
   */
  static List<KnowledgeSearchResponse.Hit> toHits(
      List<SearchResult> results,
      Map<String, Float> ceScoresByDocId,
      boolean freshnessEnabled) {
    List<KnowledgeSearchResponse.Hit> hits = new ArrayList<>();
    for (SearchResult sr : results) {
      // Match spans are additive in the proto. Use descriptor-based access so the Head can remain
      // tolerant to older Workers (or IDEs) that don't yet expose the generated accessor.
      List<KnowledgeSearchResponse.MatchSpan> matchSpans = List.of();
      FieldDescriptor spansFd = SearchResult.getDescriptor().findFieldByName("match_spans");
      if (spansFd != null) {
        Object raw = sr.getField(spansFd);
        if (raw instanceof List<?> list && !list.isEmpty()) {
          List<KnowledgeSearchResponse.MatchSpan> out = new ArrayList<>();
          for (Object o : list) {
            if (!(o instanceof Message msg)) continue;
            var desc = msg.getDescriptorForType();
            FieldDescriptor fieldFd = desc.findFieldByName("field");
            FieldDescriptor startFd = desc.findFieldByName("start_char");
            FieldDescriptor endFd = desc.findFieldByName("end_char");
            FieldDescriptor termFd = desc.findFieldByName("term");

            String field = fieldFd == null ? "" : String.valueOf(msg.getField(fieldFd));
            int start =
                startFd == null || !(msg.getField(startFd) instanceof Integer i) ? 0 : i.intValue();
            int end = endFd == null || !(msg.getField(endFd) instanceof Integer i) ? 0 : i.intValue();
            String term = termFd == null ? "" : String.valueOf(msg.getField(termFd));

            out.add(new KnowledgeSearchResponse.MatchSpan(field, start, end, term));
          }
          matchSpans = List.copyOf(out);
        }
      }
      // Excerpt regions: descriptor-based access for forward compatibility.
      List<KnowledgeSearchResponse.ExcerptRegion> excerptRegions = List.of();
      FieldDescriptor excerptFd = SearchResult.getDescriptor().findFieldByName("excerpt_regions");
      if (excerptFd != null) {
        Object rawExcerpts = sr.getField(excerptFd);
        if (rawExcerpts instanceof List<?> excerptList && !excerptList.isEmpty()) {
          List<KnowledgeSearchResponse.ExcerptRegion> excerptOut = new ArrayList<>();
          for (Object eo : excerptList) {
            if (!(eo instanceof Message emsg)) continue;
            var edesc = emsg.getDescriptorForType();
            FieldDescriptor textFd = edesc.findFieldByName("text");
            FieldDescriptor eStartFd = edesc.findFieldByName("start_char");
            FieldDescriptor eEndFd = edesc.findFieldByName("end_char");
            FieldDescriptor lineFd = edesc.findFieldByName("approx_line");
            FieldDescriptor eSpansFd = edesc.findFieldByName("match_spans");

            String text = textFd == null ? "" : String.valueOf(emsg.getField(textFd));
            int eStart =
                eStartFd == null || !(emsg.getField(eStartFd) instanceof Integer ei)
                    ? 0
                    : ei.intValue();
            int eEnd =
                eEndFd == null || !(emsg.getField(eEndFd) instanceof Integer ei)
                    ? 0
                    : ei.intValue();
            int line =
                lineFd == null || !(emsg.getField(lineFd) instanceof Integer li)
                    ? 1
                    : li.intValue();

            // Extract nested match spans within the excerpt.
            List<KnowledgeSearchResponse.MatchSpan> eSpans = List.of();
            if (eSpansFd != null) {
              Object rawESpans = emsg.getField(eSpansFd);
              if (rawESpans instanceof List<?> eSpanList && !eSpanList.isEmpty()) {
                List<KnowledgeSearchResponse.MatchSpan> esOut = new ArrayList<>();
                for (Object so : eSpanList) {
                  if (!(so instanceof Message smsg)) continue;
                  var sdesc = smsg.getDescriptorForType();
                  FieldDescriptor sfFd = sdesc.findFieldByName("field");
                  FieldDescriptor ssFd = sdesc.findFieldByName("start_char");
                  FieldDescriptor seFd = sdesc.findFieldByName("end_char");
                  FieldDescriptor stFd = sdesc.findFieldByName("term");
                  String sf = sfFd == null ? "" : String.valueOf(smsg.getField(sfFd));
                  int ss =
                      ssFd == null || !(smsg.getField(ssFd) instanceof Integer si)
                          ? 0
                          : si.intValue();
                  int se =
                      seFd == null || !(smsg.getField(seFd) instanceof Integer si)
                          ? 0
                          : si.intValue();
                  String st = stFd == null ? "" : String.valueOf(smsg.getField(stFd));
                  esOut.add(new KnowledgeSearchResponse.MatchSpan(sf, ss, se, st));
                }
                eSpans = List.copyOf(esOut);
              }
            }

            excerptOut.add(new KnowledgeSearchResponse.ExcerptRegion(text, eStart, eEnd, line, eSpans));
          }
          excerptRegions = List.copyOf(excerptOut);
        }
      }

      // Freshness decay (309 §9): multiplicative, applied after CE reranking.
      // Off by default for eval pipelines; on for interactive search via expandPreset().
      double finalScore = sr.getScore();
      if (freshnessEnabled) {
        finalScore = applyFreshnessDecay(finalScore, sr.getFieldsMap().get("modified_at"));
      }

      hits.add(
          new KnowledgeSearchResponse.Hit(
              sr.getId(),
              finalScore,
              withoutWireOnlyFields(sr.getFieldsMap()),
              sr.getMatchedFieldsList(),
              matchSpans,
              excerptRegions,
              SearchTraceMapper.mapHitStages(
                  sr, SearchTraceMapper.ceScoreFor(ceScoresByDocId, sr.getId()))));
    }
    return hits;
  }

  /** The hit's fields without the Worker's cross-encoder-only input (never shown to callers). */
  static Map<String, String> withoutWireOnlyFields(Map<String, String> fields) {
    if (!fields.containsKey(KnowledgeSearchEngine.PARENT_OPENING_FIELD)) return fields;
    Map<String, String> out = new java.util.LinkedHashMap<>(fields);
    out.remove(KnowledgeSearchEngine.PARENT_OPENING_FIELD);
    return out;
  }

  /** Extracts match spans from a SearchResult proto (best-effort, handles older Workers). */
  static List<MatchSpanInfo> extractMatchSpans(SearchResult sr) {
    List<MatchSpanInfo> spans = new ArrayList<>();
    FieldDescriptor spansFd = SearchResult.getDescriptor().findFieldByName("match_spans");
    if (spansFd == null) {
      return spans;
    }
    Object raw = sr.getField(spansFd);
    if (!(raw instanceof List<?> list) || list.isEmpty()) {
      return spans;
    }
    for (Object o : list) {
      if (!(o instanceof Message msg)) continue;
      var desc = msg.getDescriptorForType();
      FieldDescriptor fieldFd = desc.findFieldByName("field");
      FieldDescriptor startFd = desc.findFieldByName("start_char");
      FieldDescriptor endFd = desc.findFieldByName("end_char");
      FieldDescriptor termFd = desc.findFieldByName("term");

      String field = fieldFd == null ? "" : String.valueOf(msg.getField(fieldFd));
      int start = startFd == null || !(msg.getField(startFd) instanceof Integer i) ? 0 : i.intValue();
      int end = endFd == null || !(msg.getField(endFd) instanceof Integer i) ? 0 : i.intValue();
      String term = termFd == null ? "" : String.valueOf(msg.getField(termFd));
      spans.add(new MatchSpanInfo(field, start, end, term));
    }
    return spans;
  }

  /**
   * Extracts a query-focused snippet from content_preview using match span positions. Centers the
   * snippet on the first query match, giving the reranker more relevant context. Falls back to
   * preview start if no matches.
   */
  static String extractQueryFocusedSnippet(
      String contentPreview, List<MatchSpanInfo> spans, int maxLength) {
    if (contentPreview == null || contentPreview.isBlank()) {
      return "";
    }
    int matchPos = -1;
    for (MatchSpanInfo span : spans) {
      if (("content_preview".equals(span.field()) || "content".equals(span.field()))
          && span.startChar() >= 0
          && span.startChar() < contentPreview.length()) {
        matchPos = span.startChar();
        break;
      }
    }
    if (matchPos < 0) {
      return contentPreview.substring(0, Math.min(contentPreview.length(), maxLength));
    }
    return snippetAround(contentPreview, matchPos, maxLength);
  }

  /**
   * A window of about {@code maxLength} chars centered on {@code matchPos}, snapped to word
   * boundaries; {@code matchPos} 0 gives the text's opening.
   */
  static String snippetAround(String contentPreview, int matchPos, int maxLength) {
    int halfWindow = maxLength / 2;
    int start = Math.max(0, matchPos - halfWindow);
    int end = Math.min(contentPreview.length(), start + maxLength);
    if (end == contentPreview.length() && end - start < maxLength) {
      start = Math.max(0, end - maxLength);
    }
    if (start > 0) {
      int wordStart = contentPreview.lastIndexOf(' ', start);
      if (wordStart > start - 50 && wordStart >= 0) {
        start = wordStart + 1;
      }
    }
    if (end < contentPreview.length()) {
      int wordEnd = contentPreview.indexOf(' ', end);
      if (wordEnd > 0 && wordEnd < end + 50) {
        end = wordEnd;
      }
    }
    return contentPreview.substring(start, end);
  }

  /**
   * Freshness decay (309 §9): multiplicative, applied after CE reranking. Half-life ~30 days; the
   * first 7 days have no decay; returns rawScore unchanged when {@code modifiedAtStr} is missing or
   * unparseable. Pure — no instance state.
   *
   * @param rawScore the original retrieval/reranking score
   * @param modifiedAtStr epoch-millis string from the {@code modified_at} stored field, or null
   */
  static double applyFreshnessDecay(double rawScore, String modifiedAtStr) {
    if (modifiedAtStr == null || modifiedAtStr.isBlank()) return rawScore;
    try {
      long modifiedMs = Long.parseLong(modifiedAtStr);
      double ageDays = (System.currentTimeMillis() - modifiedMs) / 86_400_000.0;
      if (ageDays <= 7.0) return rawScore; // offset period — no decay
      double freshness = Math.pow(0.5, (ageDays - 7.0) / 30.0);
      return rawScore * (1.0 - 0.05 * (1.0 - freshness));
    } catch (NumberFormatException e) {
      return rawScore;
    }
  }
}
