/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Wrapper around HuggingFace tokenizer for cross-encoder input preparation.
 *
 * <p>Encodes query-document pairs into token IDs and attention masks suitable for ONNX inference.
 */
public final class RerankerTokenizer implements Closeable {
  private final HuggingFaceTokenizer tokenizer;
  private final int maxLength;
  private final boolean queryPolicyTemplateSupported;

  /**
   * Creates a tokenizer from a local tokenizer.json file.
   *
   * @param tokenizerPath path to tokenizer.json
   * @param maxLength maximum sequence length (truncates longer sequences)
   * @throws UncheckedIOException if the tokenizer file cannot be read
   */
  public RerankerTokenizer(Path tokenizerPath, int maxLength) {
    try {
      Path file = Files.isDirectory(tokenizerPath) ? tokenizerPath.resolve("tokenizer.json") : tokenizerPath;
      byte[] definition = Files.readAllBytes(file);
      // The native owner and policy metadata must come from the same immutable definition.
      this.queryPolicyTemplateSupported = supportsQueryPolicyTemplate(definition);
      this.tokenizer = HuggingFaceTokenizer.newInstance(new ByteArrayInputStream(definition), null);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load tokenizer from " + tokenizerPath, e);
    }
    this.maxLength = maxLength;
  }

  /**
   * Proves the search-only long-query predicate without changing native tokenizer options.
   *
   * <p>Only the attended primary window is counted. A clipped window is a lower bound, sufficient
   * to prove {@code 2*q > B-S}; no exact original length or overflow reconstruction is claimed.
   * Unsupported metadata leaves ordinary reranking unchanged. Native encoding failures propagate.
   */
  public boolean exceedsSearchQueryBudget(String query) {
    if (!queryPolicyTemplateSupported || maxLength <= 0) {
      return false;
    }
    String truncation = tokenizer.getTruncation();
    boolean truncated = "LONGEST_FIRST".equals(truncation);
    if (!truncated && !"DO_NOT_TRUNCATE".equals(truncation)) {
      return false;
    }
    int nativeCap = tokenizer.getMaxLength();
    if (truncated && nativeCap <= 0) {
      return false;
    }
    int budget = truncated ? Math.min(maxLength, nativeCap) : maxLength;
    Encoding emptyPair = tokenizer.encode("", "", true, false);
    int specials = attendedTokens(emptyPair, true);
    if (specials < 0 || specials >= budget) {
      return false;
    }
    // DJL booleans are addSpecialTokens/includeOverflowArrays, not truncation/padding switches.
    int primaryQueryTokens = attendedTokens(tokenizer.encode(query, false, false), false);
    if (primaryQueryTokens < 0 || (truncated && primaryQueryTokens > nativeCap)) {
      return false;
    }
    return 2L * primaryQueryTokens > (long) budget - specials;
  }

  private static int attendedTokens(Encoding encoding, boolean requireSpecials) {
    long[] ids = encoding.getIds();
    long[] attention = encoding.getAttentionMask();
    long[] special = encoding.getSpecialTokenMask();
    if (attention == null || attention.length != ids.length
        || (requireSpecials && (special == null || special.length != ids.length))) {
      return -1;
    }
    int count = 0;
    for (int i = 0; i < attention.length; i++) {
      if (attention[i] != 0 && attention[i] != 1) {
        return -1;
      }
      if (attention[i] == 1) {
        if (requireSpecials && special[i] != 1) {
          return -1;
        }
        count++;
      }
    }
    return count;
  }

  private static boolean supportsQueryPolicyTemplate(byte[] definition) throws IOException {
    try (var reader = new JsonReader(new StringReader(new String(definition, StandardCharsets.UTF_8)))) {
      reader.beginObject();
      while (reader.hasNext()) {
        if ("post_processor".equals(reader.nextName())) {
          return supportsQueryPolicyTemplate(JsonParser.parseReader(reader));
        }
        reader.skipValue();
      }
      return false;
    }
  }

  private static boolean supportsQueryPolicyTemplate(JsonElement element) {
    if (element == null || !element.isJsonObject()) {
      return false;
    }
    var processor = element.getAsJsonObject();
    JsonElement type = processor.get("type");
    if (type == null || !type.isJsonPrimitive() || !type.getAsJsonPrimitive().isString()) {
      return false;
    }
    if ("BertProcessing".equals(type.getAsString()) || "RobertaProcessing".equals(type.getAsString())) {
      return true;
    }
    if (!"TemplateProcessing".equals(type.getAsString())) {
      return false;
    }
    return hasQueryPolicySequences(processor.get("single"), 0)
        && hasQueryPolicySequences(processor.get("pair"), 1);
  }

  private static boolean hasQueryPolicySequences(JsonElement template, int expectedDocuments) {
    if (template == null || !template.isJsonArray()) {
      return false;
    }
    int a = 0;
    int b = 0;
    for (JsonElement item : template.getAsJsonArray()) {
      if (!item.isJsonObject() || item.getAsJsonObject().size() != 1) {
        return false;
      }
      var part = item.getAsJsonObject();
      if (part.has("Sequence") && part.get("Sequence").isJsonObject()) {
        JsonElement id = part.getAsJsonObject("Sequence").get("id");
        if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) {
          return false;
        }
        if ("A".equals(id.getAsString())) {
          a++;
        } else if ("B".equals(id.getAsString())) {
          b++;
        } else {
          return false;
        }
      } else if (!part.has("SpecialToken") || !part.get("SpecialToken").isJsonObject()) {
        return false;
      }
    }
    return a == 1 && b == expectedDocuments;
  }

  /**
   * Encodes query-document pairs for cross-encoder inference.
   *
   * <p>Each document is paired with the query using BERT-style encoding: [CLS] query [SEP] document
   * [SEP]
   *
   * @param query the search query
   * @param documents array of document texts to encode
   * @return encoded batch with input_ids, attention_mask, and token_type_ids arrays
   */
  public EncodedBatch encodePairs(String query, String[] documents) {
    int batchSize = documents.length;
    long[][] rowIds = new long[batchSize][];
    long[][] rowMasks = new long[batchSize][];
    long[][] rowTypeIds = new long[batchSize][];

    for (int i = 0; i < batchSize; i++) {
      // Encode query-document pair using the tokenizer's built-in pair support
      Encoding encoding = tokenizer.encode(query, documents[i]);
      rowIds[i] = encoding.getIds();
      rowMasks[i] = encoding.getAttentionMask();
      rowTypeIds[i] = encoding.getTypeIds();
    }

    return pack(rowIds, rowMasks, rowTypeIds, maxLength);
  }

  /**
   * Encodes pairs and refuses to return a silently truncated batch (tempdoc 836 §1.3(b)).
   *
   * <p>Truncation here is not "a shorter passage": {@link #pack} keeps the FIRST {@code maxLength}
   * tokens, so an overlong pair loses its trailing {@code [SEP]} and its second segment's
   * {@code token_type_ids} are cut mid-run — the model is fed a malformed pair and scores it
   * anyway. Callers that have windowed their passages to fit (citation scoring) use this method so
   * that a window which still overflows is a loud failure rather than an invisible one.
   *
   * @throws PairTooLongException if any pair exceeds the configured maximum sequence length
   */
  public EncodedBatch encodePairsStrict(String query, String[] documents) {
    return requireUntruncated(encodePairs(query, documents), maxLength);
  }

  /**
   * Returns the batch, or raises if any of its pairs was cut. The guard is separate from encoding
   * so the refusal is testable without a tokenizer model on disk.
   *
   * @throws PairTooLongException if any pair exceeded {@code maxLength}
   */
  static EncodedBatch requireUntruncated(EncodedBatch batch, int maxLength) {
    if (batch.truncatedPairs() > 0) {
      throw new PairTooLongException(
          "cross-encoder pair exceeds maxLength="
              + maxLength
              + " in "
              + batch.truncatedPairs()
              + " of "
              + batch.batchSize()
              + " pairs (longest "
              + batch.longestPairTokens()
              + " tokens)");
    }
    return batch;
  }

  /**
   * Truncates each row to {@code maxLength}, pads the remainder, and records how many rows had to
   * be cut. Pure function — the truncation-detection half of §1.3(b) lives here so it is testable
   * without a tokenizer model on disk.
   */
  static EncodedBatch pack(long[][] rowIds, long[][] rowMasks, long[][] rowTypeIds, int maxLength) {
    int batchSize = rowIds.length;
    long[][] inputIds = new long[batchSize][maxLength];
    long[][] attentionMask = new long[batchSize][maxLength];
    long[][] tokenTypeIds = new long[batchSize][maxLength];

    int truncatedPairs = 0;
    int longestPairTokens = 0;
    for (int i = 0; i < batchSize; i++) {
      long[] ids = rowIds[i];
      long[] mask = rowMasks[i];
      long[] typeIds = rowTypeIds[i];

      longestPairTokens = Math.max(longestPairTokens, ids.length);
      if (ids.length > maxLength) {
        truncatedPairs++;
      }

      int copyLen = Math.min(ids.length, maxLength);
      System.arraycopy(ids, 0, inputIds[i], 0, copyLen);
      if (mask != null && mask.length > 0) {
        System.arraycopy(mask, 0, attentionMask[i], 0, Math.min(mask.length, maxLength));
      }
      if (typeIds != null && typeIds.length > 0) {
        System.arraycopy(typeIds, 0, tokenTypeIds[i], 0, Math.min(typeIds.length, maxLength));
      }
      // Remaining positions are already 0 (default array initialization = padding)
    }

    return new EncodedBatch(
        inputIds, attentionMask, tokenTypeIds, batchSize, maxLength, truncatedPairs,
        longestPairTokens);
  }

  /** Returns the configured maximum sequence length. */
  public int maxLength() {
    return maxLength;
  }

  /**
   * Raised when a (query, document) pair does not fit the model's sequence window, so scoring it
   * would mean scoring a malformed pair.
   */
  public static final class PairTooLongException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PairTooLongException(String message) {
      super(message);
    }
  }

  @Override
  public void close() {
    tokenizer.close();
  }

  /**
   * Batch of encoded inputs ready for ONNX inference.
   *
   * @param inputIds token IDs array [batchSize][seqLength]
   * @param attentionMask attention mask array [batchSize][seqLength]
   * @param tokenTypeIds segment IDs array [batchSize][seqLength] (0 for query, 1 for document)
   * @param batchSize number of samples in batch
   * @param seqLength sequence length (with padding)
   * @param truncatedPairs how many pairs did not fit {@code seqLength} and were cut (tempdoc 836
   *     §1.3(b) — the tokenizer's own truncation was previously invisible to every caller)
   * @param longestPairTokens token count of the longest pair before truncation
   */
  public record EncodedBatch(
      long[][] inputIds,
      long[][] attentionMask,
      long[][] tokenTypeIds,
      int batchSize,
      int seqLength,
      int truncatedPairs,
      int longestPairTokens) {}
}
