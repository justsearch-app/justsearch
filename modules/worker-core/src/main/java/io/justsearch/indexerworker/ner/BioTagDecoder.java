/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decodes BIO-tagged token sequences from NER model logits into entity spans.
 *
 * <p>Label mapping is loaded from the model's config.json id2label field. Each model may use a
 * different label index ordering (e.g., dslim/bert-base-NER: 1=B-MISC,
 * Davlan/distilbert-multilingual-ner-hrl: 1=B-DATE). The mapping is resolved dynamically at
 * model init time.
 *
 * <p>All methods are stateless. Only PER/ORG/LOC entities are extracted; MISC and DATE are
 * filtered out.
 */
public final class BioTagDecoder {
  private BioTagDecoder() {}

  /** Decoded entity with surface text, type, and aggregated confidence. */
  public record Entity(String text, String type, float confidence) {}

  /**
   * Maps label indices to BIO tag names. Loaded from config.json id2label at model init time. Each
   * NER model may use a different index ordering (e.g., dslim/bert-base-NER: 1=B-MISC,
   * Davlan/distilbert-multilingual-ner-hrl: 1=B-DATE).
   */
  public record LabelMapping(String[] id2label) {

    /** Creates mapping from config.json id2label map (e.g., {"0":"O","1":"B-PER",...}). */
    public static LabelMapping fromId2Label(Map<String, String> id2label) {
      int maxId = id2label.keySet().stream().mapToInt(Integer::parseInt).max().orElse(0);
      String[] labels = new String[maxId + 1];
      for (var entry : id2label.entrySet()) {
        labels[Integer.parseInt(entry.getKey())] = entry.getValue();
      }
      return new LabelMapping(labels);
    }

    /** Legacy default mapping matching dslim/bert-base-NER label ordering (backward compat). */
    public static LabelMapping bertBaseNer() {
      return new LabelMapping(
          new String[] {
            "O", "B-MISC", "I-MISC", "B-PER", "I-PER", "B-ORG", "I-ORG", "B-LOC", "I-LOC"
          });
    }
  }

  // Cached default for backward compatibility
  private static final LabelMapping DEFAULT_MAPPING = LabelMapping.bertBaseNer();

  /** Decodes logits using default bert-base-NER label mapping. */
  public static List<Entity> decode(float[][] logits, String[] tokens, long[] wordIds) {
    return decode(logits, tokens, wordIds, DEFAULT_MAPPING);
  }

  /**
   * Decodes logits into entity spans using the given label mapping.
   *
   * @param logits per-token logits, shape [seqLen][numLabels]
   * @param tokens token strings from the tokenizer (includes [CLS], [SEP], ##subwords)
   * @param wordIds per-token word index (maps subword tokens to original word positions; -1 for
   *     special tokens like [CLS] and [SEP])
   * @param mapping label index to BIO tag mapping (from config.json id2label)
   * @return list of decoded entities (MISC filtered, subwords merged)
   */
  public static List<Entity> decode(
      float[][] logits, String[] tokens, long[] wordIds, LabelMapping mapping) {
    if (logits == null || tokens == null || wordIds == null) {
      return List.of();
    }
    int len = Math.min(logits.length, Math.min(tokens.length, wordIds.length));
    String[] labels = mapping.id2label();

    List<Entity> entities = new ArrayList<>();
    StringBuilder currentText = new StringBuilder();
    String currentTag = "O";
    float confidenceSum = 0f;
    int confidenceCount = 0;

    long prevWordId = -1;
    for (int i = 0; i < len; i++) {
      if (wordIds[i] < 0) continue;

      // 326: Subword aggregation — continuation subwords (same wordId as previous token)
      // inherit the previous token's BIO decision. Only the first subword of each word
      // determines the entity tag. This prevents the model's inconsistent subword-level
      // predictions (e.g., B-ORG on "##ER" in "F##ER##C") from fragmenting entities.
      boolean isSubwordContinuation = wordIds[i] == prevWordId;
      prevWordId = wordIds[i];

      if (isSubwordContinuation) {
        // Continuation subword: always append text, inherit current entity state
        if (!"O".equals(currentTag)) {
          appendToken(currentText, tokens[i]);
          float conf = softmaxMax(logits[i], argmax(logits[i]));
          confidenceSum += conf;
          confidenceCount++;
        }
        continue;
      }

      // First subword of a new word: use the model's prediction
      int predicted = argmax(logits[i]);
      float conf = softmaxMax(logits[i], predicted);
      String tag = predicted < labels.length && labels[predicted] != null ? labels[predicted] : "O";

      boolean isBegin = tag.startsWith("B-");
      boolean isContinuation =
          tag.startsWith("I-") && sameEntityType(tag, currentTag);

      if (isContinuation && !"O".equals(currentTag)) {
        appendToken(currentText, tokens[i]);
        confidenceSum += conf;
        confidenceCount++;
      } else {
        if (!"O".equals(currentTag) && currentText.length() > 0) {
          Entity entity = buildEntity(currentText, currentTag, confidenceSum, confidenceCount);
          if (entity != null) {
            entities.add(entity);
          }
        }

        currentText.setLength(0);
        if (isBegin || tag.startsWith("I-")) {
          currentTag = tag;
          appendToken(currentText, tokens[i]);
          confidenceSum = conf;
          confidenceCount = 1;
        } else {
          currentTag = "O";
          confidenceSum = 0f;
          confidenceCount = 0;
        }
      }
    }

    if (!"O".equals(currentTag) && currentText.length() > 0) {
      Entity entity = buildEntity(currentText, currentTag, confidenceSum, confidenceCount);
      if (entity != null) {
        entities.add(entity);
      }
    }

    return entities;
  }

  static int argmax(float[] logits) {
    if (logits == null || logits.length == 0) return 0;
    int best = 0;
    for (int i = 1; i < logits.length; i++) {
      if (logits[i] > logits[best]) best = i;
    }
    return best;
  }

  /** Computes softmax probability for the given index. */
  static float softmaxMax(float[] logits, int index) {
    if (logits == null || logits.length == 0) return 0f;
    float max = logits[0];
    for (int i = 1; i < logits.length; i++) {
      if (logits[i] > max) max = logits[i];
    }
    float sum = 0f;
    for (float logit : logits) {
      sum += (float) Math.exp(logit - max);
    }
    return (float) Math.exp(logits[index] - max) / sum;
  }

  /** Checks if two tags share the same entity type (e.g., B-PER and I-PER). */
  private static boolean sameEntityType(String a, String b) {
    String typeA = a.length() > 2 ? a.substring(2) : "";
    String typeB = b.length() > 2 ? b.substring(2) : "";
    return !typeA.isEmpty() && typeA.equals(typeB);
  }

  /** Maps a BIO tag string to user-facing entity type, or null for filtered types. */
  static String entityType(String tag) {
    if (tag == null || tag.length() < 3) return null;
    String suffix = tag.substring(2);
    return switch (suffix) {
      case "PER" -> "person";
      case "ORG" -> "organization";
      case "LOC" -> "location";
      default -> null; // O and MISC filtered
    };
  }

  private static void appendToken(StringBuilder sb, String token) {
    if (token == null) return;
    if (token.startsWith("##")) {
      // WordPiece subword — append without space
      sb.append(token.substring(2));
    } else {
      if (sb.length() > 0) sb.append(' ');
      sb.append(token);
    }
  }

  /** A single ideograph can be a complete name; confidence filtering belongs to the caller. */
  static final int MIN_ENTITY_LENGTH = 1;

  /** ALL-CAPS words that are scientific section headers, not organization names. */
  static final Set<String> SECTION_HEADER_WORDS =
      Set.of(
          "ABSTRACT",
          "AIM",
          "AIMS",
          "AUTHORS",
          "BACKGROUND",
          "CONCLUSION",
          "CONCLUSIONS",
          "CONTEXT",
          "DATA",
          "DESIGN",
          "DISCUSSION",
          "FINDINGS",
          "FUNDING",
          "INTRODUCTION",
          "INTERVENTIONS",
          "LIMITATIONS",
          "MATERIALS",
          "MEASUREMENTS",
          "METHODS",
          "OBJECTIVE",
          "OBJECTIVES",
          "OUTCOMES",
          "PARTICIPANTS",
          "PURPOSE",
          "REFERENCES",
          "RESULTS",
          "SETTING",
          "SIGNIFICANCE",
          "SOURCES",
          "SUMMARY");

  /** Stopwords ignored when checking if an entity is entirely composed of header words. */
  private static final Set<String> HEADER_STOPWORDS =
      Set.of("A", "AN", "AND", "FOR", "FROM", "IN", "OF", "OR", "THE", "TO", "WITH");

  private static Entity buildEntity(StringBuilder text, String tag, float confSum, int confCount) {
    String type = entityType(tag);
    if (type == null) return null; // MISC filtered
    String entityText = text.toString().strip();
    if (entityText.length() < MIN_ENTITY_LENGTH) return null;
    // Filter punctuation-only entities (e.g., "&", ".", ",")
    if (entityText.codePoints().noneMatch(Character::isLetterOrDigit)) return null;
    if (entityText.codePoints().filter(Character::isLetter).count() < 3
        && !entityText.codePoints().allMatch(c -> Character.isLetter(c) || Character.isWhitespace(c))) return null;
    // Strip leading section-header words from entity text. Handles both pure headers
    // ("METHODS AND FINDINGS" → rejected) and contaminated spans
    // ("DATA SOURCES Medline Embase" → "Medline Embase").
    if (entityText.codePointCount(0, entityText.length()) >= 3) {
      entityText = stripLeadingHeaderWords(entityText);
    }
    if (entityText == null) return null;
    float avgConf = confCount > 0 ? confSum / confCount : 0f;
    return new Entity(entityText, type, avgConf);
  }

  /**
   * Strips leading section-header words (and stopwords) from entity text. Returns null if the
   * entire entity is composed of header words/stopwords.
   */
  static String stripLeadingHeaderWords(String entityText) {
    String[] words = entityText.split("\\s+");
    int firstNonHeader = -1;
    for (int i = 0; i < words.length; i++) {
      String upper = words[i].toUpperCase(java.util.Locale.ROOT);
      // Skip punctuation-only tokens ("&", ".", ",") in the header prefix
      boolean punctOnly = upper.codePoints().noneMatch(Character::isLetterOrDigit);
      if (!punctOnly
          && !SECTION_HEADER_WORDS.contains(upper)
          && !HEADER_STOPWORDS.contains(upper)) {
        firstNonHeader = i;
        break;
      }
    }
    if (firstNonHeader == -1) return null; // entirely header words
    if (firstNonHeader == 0) return entityText; // no header prefix
    // Rebuild from the first non-header word onward
    var sb = new StringBuilder();
    for (int i = firstNonHeader; i < words.length; i++) {
      if (sb.length() > 0) sb.append(' ');
      sb.append(words[i]);
    }
    String stripped = sb.toString().strip();
    return stripped.length() < MIN_ENTITY_LENGTH ? null : stripped;
  }
}
