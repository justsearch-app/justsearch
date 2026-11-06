/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Approved C5 H1/H2/P1/P3: real native tokenizer, with an independently specified vocabulary. */
final class RerankerSearchQueryBudgetTest {
  @TempDir Path temp;

  @Test
  void strictHalfBoundaryUsesEffectiveNativeAndConfiguredCapsAndTemplate() throws Exception {
    for (int specials : new int[] {3, 4}) {
      for (int nativeCap : new int[] {31, 32, 64, 512}) {
        for (int wrapperCap : new int[] {29, 32, 80}) {
          int budget = Math.min(nativeCap, wrapperCap);
          int threshold = (budget - specials) / 2;
          try (var tokenizer = new RerankerTokenizer(fixture(nativeCap, specials, 0), wrapperCap)) {
            var active = active(tokenizer);
            assertEquals("LONGEST_FIRST", active.getTruncation());
            assertEquals(nativeCap, active.getMaxLength());
            assertEquals(specials, java.util.Arrays.stream(active.encode("", "").getAttentionMask()).sum());
            assertFalse(tokenizer.exceedsSearchQueryBudget(words(threshold)));
            assertTrue(tokenizer.exceedsSearchQueryBudget(words(threshold + 1)));
            assertTrue(tokenizer.exceedsSearchQueryBudget(words(nativeCap * 4)),
                "a clipped primary window proves the threshold without reconstructing overflows");
          }
        }
      }
    }
  }

  @Test
  void paddingStrideUnicodeAndWhitespaceDoNotFabricateQueryLengthOrMutatePairs() throws Exception {
    try (var tokenizer = new RerankerTokenizer(fixture(32, 4, 3), 32)) {
      assertEquals("LONGEST", active(tokenizer).getPadding(), "DJL rewrites JSON Fixed to active LONGEST");
      assertEquals(3, active(tokenizer).getStride());
      var before = tokenizer.encodePairs("a", new String[] {words(40)});
      assertFalse(tokenizer.exceedsSearchQueryBudget(" \t\n "));
      assertFalse(tokenizer.exceedsSearchQueryBudget("a"));
      assertFalse(tokenizer.exceedsSearchQueryBudget("Grüße 東京 !"));
      assertTrue(tokenizer.exceedsSearchQueryBudget("Grüße 東京 ! ".repeat(5)));
      assertTrue(tokenizer.exceedsSearchQueryBudget(words(1000)));
      assertFalse(tokenizer.exceedsSearchQueryBudget("a"), "short request after skip stays eligible");
      var after = tokenizer.encodePairs("a", new String[] {words(40)});
      assertArrayEquals(before.inputIds()[0], after.inputIds()[0]);
      assertArrayEquals(before.attentionMask()[0], after.attentionMask()[0]);
      assertArrayEquals(before.tokenTypeIds()[0], after.tokenTypeIds()[0]);
    }
  }

  @Test
  void unsupportedProcessorPreservesOrdinaryPairEncodingAndStrictCitationRefusal() throws Exception {
    Path fixture = fixture(64, 3, 0);
    var json = JsonParser.parseString(Files.readString(fixture)).getAsJsonObject();
    var sequence = new JsonObject();
    sequence.addProperty("type", "Sequence");
    var processors = new com.google.gson.JsonArray();
    processors.add(json.get("post_processor"));
    sequence.add("processors", processors);
    json.add("post_processor", sequence);
    Files.writeString(fixture, json.toString());
    try (var tokenizer = new RerankerTokenizer(fixture, 16)) {
      assertFalse(tokenizer.exceedsSearchQueryBudget(words(50)), "unsupported policy metadata falls back");
      assertEquals(16, tokenizer.encodePairs(words(20), new String[] {"a"}).seqLength());
      assertThrows(RerankerTokenizer.PairTooLongException.class,
          () -> tokenizer.encodePairsStrict(words(20), new String[] {"a"}));
    }
  }

  private static HuggingFaceTokenizer active(RerankerTokenizer tokenizer) throws Exception {
    var field = RerankerTokenizer.class.getDeclaredField("tokenizer");
    field.setAccessible(true);
    return (HuggingFaceTokenizer) field.get(tokenizer);
  }

  private Path fixture(int nativeCap, int specials, int stride) throws Exception {
    String sep = "{\"SpecialToken\":{\"id\":\"[SEP]\",\"type_id\":0}}";
    String query = "{\"Sequence\":{\"id\":\"A\",\"type_id\":0}}";
    String document = "{\"Sequence\":{\"id\":\"B\",\"type_id\":1}}";
    String cls = "{\"SpecialToken\":{\"id\":\"[CLS]\",\"type_id\":0}}";
    String json = "{\"version\":\"1.0\",\"truncation\":{\"direction\":\"Right\",\"max_length\":"
        + nativeCap + ",\"strategy\":\"LongestFirst\",\"stride\":" + stride + "},"
        + "\"padding\":{\"strategy\":{\"Fixed\":" + nativeCap
        + "},\"direction\":\"Right\",\"pad_to_multiple_of\":null,\"pad_id\":0,\"pad_type_id\":0,\"pad_token\":\"[PAD]\"},"
        + "\"added_tokens\":[],\"normalizer\":null,\"pre_tokenizer\":{\"type\":\"WhitespaceSplit\"},"
        + "\"post_processor\":{\"type\":\"TemplateProcessing\",\"single\":[" + cls + "," + query + "," + sep
        + "],\"pair\":[" + cls + "," + query + "," + sep + "," + (specials == 4 ? sep + "," : "")
        + document + "," + sep + "],\"special_tokens\":{"
        + "\"[CLS]\":{\"id\":\"[CLS]\",\"ids\":[1],\"tokens\":[\"[CLS]\"]},"
        + "\"[SEP]\":{\"id\":\"[SEP]\",\"ids\":[2],\"tokens\":[\"[SEP]\"]}}},"
        + "\"decoder\":null,\"model\":{\"type\":\"WordLevel\",\"vocab\":{\"[PAD]\":0,\"[CLS]\":1,\"[SEP]\":2,\"[UNK]\":3,\"a\":4},\"unk_token\":\"[UNK]\"}}";
    return Files.writeString(temp.resolve("tokenizer-" + nativeCap + "-" + specials + "-" + stride + ".json"), json);
  }

  private static String words(int count) {
    return "a ".repeat(count);
  }
}
