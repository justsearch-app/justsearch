package io.justsearch.indexerworker.bgem3;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Validates vocabulary loading from a real BGE-M3 tokenizer.json. Skipped if the model files are
 * not present (CI without model downloads).
 */
class BgeM3VocabLoadTest {

  @Test
  void loadRealVocabulary() throws Exception {
    var discovery = io.justsearch.ort.testing.ModelDirTestResolver.discover(
        "models/bge-m3/BAAI-bge-m3", "JUSTSEARCH_BGE_M3_MODEL_PATH", "tokenizer.json");
    assumeTrue(discovery.modelDir() != null, discovery.missDescription());
    Path tokenizerJson = discovery.modelDir().resolve("tokenizer.json");

    String[] vocab = BgeM3Encoder.loadVocabularyFromTokenizerJson(tokenizerJson);

    // XLM-RoBERTa has 250002 tokens
    assertEquals(250002, vocab.length, "vocab size");

    // Special tokens at known IDs
    assertEquals("<s>", vocab[0], "ID 0 = <s> (CLS)");
    assertEquals("<pad>", vocab[1], "ID 1 = <pad>");
    assertEquals("</s>", vocab[2], "ID 2 = </s> (SEP)");
    assertEquals("<unk>", vocab[3], "ID 3 = <unk>");

    // Common tokens exist (punctuation at low IDs)
    assertEquals(",", vocab[4]);

    // No null entries
    for (int i = 0; i < vocab.length; i++) {
      assertNotNull(vocab[i], "vocab[" + i + "] is null");
    }
  }
}
