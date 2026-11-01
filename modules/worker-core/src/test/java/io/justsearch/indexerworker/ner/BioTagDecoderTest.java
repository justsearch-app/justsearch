package io.justsearch.indexerworker.ner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("BioTagDecoder")
class BioTagDecoderTest {

  // Label indices matching the legacy dslim/bert-base-NER default mapping (used in tests)
  static final int O = 0;
  static final int B_MISC = 1;
  static final int I_MISC = 2;
  static final int B_PER = 3;
  static final int I_PER = 4;
  static final int B_ORG = 5;
  static final int I_ORG = 6;
  static final int B_LOC = 7;
  static final int I_LOC = 8;
  static final int NUM_LABELS = 9;

  // Helper: create logits where the given label index has highest score
  private static float[] logitsFor(int label) {
    float[] logits = new float[NUM_LABELS];
    for (int i = 0; i < logits.length; i++) {
      logits[i] = (i == label) ? 5.0f : -1.0f;
    }
    return logits;
  }

  @Test
  void highConfidenceShortNamesSurvive() {
    for (String name : List.of("Li", "Bo", "An", "吴", "京")) {
      for (int label : new int[] {B_PER, B_LOC}) {
        var entities = BioTagDecoder.decode(new float[][] {logitsFor(label)},
            new String[] {name}, new long[] {0});
        assertEquals(1, entities.size(), name);
        assertEquals(name, entities.get(0).text());
      }
    }
    var cjk = BioTagDecoder.decode(new float[][] {logitsFor(B_PER), logitsFor(I_PER)},
        new String[] {"吴", "京"}, new long[] {0, 1});
    assertEquals(1, cjk.size());
    assertEquals("吴 京", cjk.get(0).text());
  }

  @Nested
  @DisplayName("Basic entity extraction")
  class BasicExtraction {

    @Test
    @DisplayName("single-word person entity")
    void singleWordPerson() {
      float[][] logits = {
          logitsFor(O),     // [CLS]
          logitsFor(B_PER), // John
          logitsFor(O),     // works
          logitsFor(O),     // [SEP]
      };
      String[] tokens = {"[CLS]", "John", "works", "[SEP]"};
      long[] wordIds = {-1, 0, 1, -1};

      List<BioTagDecoder.Entity> entities = BioTagDecoder.decode(logits, tokens, wordIds);

      assertEquals(1, entities.size());
      assertEquals("John", entities.get(0).text());
      assertEquals("person", entities.get(0).type());
      assertTrue(entities.get(0).confidence() > 0.9f);
    }

    @Test
    @DisplayName("multi-word person entity")
    void multiWordPerson() {
      float[][] logits = {
          logitsFor(O),     // [CLS]
          logitsFor(B_PER), // John
          logitsFor(I_PER), // Smith
          logitsFor(O),     // [SEP]
      };
      String[] tokens = {"[CLS]", "John", "Smith", "[SEP]"};
      long[] wordIds = {-1, 0, 1, -1};

      List<BioTagDecoder.Entity> entities = BioTagDecoder.decode(logits, tokens, wordIds);

      assertEquals(1, entities.size());
      assertEquals("John Smith", entities.get(0).text());
      assertEquals("person", entities.get(0).type());
    }

    @Test
    @DisplayName("organization entity")
    void organizationEntity() {
      float[][] logits = {
          logitsFor(O),     // [CLS]
          logitsFor(B_ORG), // Acme
          logitsFor(I_ORG), // Corp
          logitsFor(O),     // [SEP]
      };
      String[] tokens = {"[CLS]", "Acme", "Corp", "[SEP]"};
      long[] wordIds = {-1, 0, 1, -1};

      List<BioTagDecoder.Entity> entities = BioTagDecoder.decode(logits, tokens, wordIds);

      assertEquals(1, entities.size());
      assertEquals("Acme Corp", entities.get(0).text());
      assertEquals("organization", entities.get(0).type());
    }

    @Test
    @DisplayName("location entity")
    void locationEntity() {
      float[][] logits = {
          logitsFor(O),     // [CLS]
          logitsFor(B_LOC), // New
          logitsFor(I_LOC), // York
          logitsFor(O),     // [SEP]
      };
      String[] tokens = {"[CLS]", "New", "York", "[SEP]"};
      long[] wordIds = {-1, 0, 1, -1};

      List<BioTagDecoder.Entity> entities = BioTagDecoder.decode(logits, tokens, wordIds);

      assertEquals(1, entities.size());
      assertEquals("New York", entities.get(0).text());
      assertEquals("location", entities.get(0).type());
    }
  }

  @Nested
  @DisplayName("Multiple and adjacent entities")
  class MultipleEntities {

    @Test
    @DisplayName("two adjacent entities of different types")
    void adjacentDifferentTypes() {
      float[][] logits = {
          logitsFor(O),     // [CLS]
          logitsFor(B_PER), // John
          logitsFor(B_ORG), // Google
          logitsFor(O),     // [SEP]
      };
      String[] tokens = {"[CLS]", "John", "Google", "[SEP]"};
      long[] wordIds = {-1, 0, 1, -1};

      List<BioTagDecoder.Entity> entities = BioTagDecoder.decode(logits, tokens, wordIds);

      assertEquals(2, entities.size());
      assertEquals("John", entities.get(0).text());
      assertEquals("person", entities.get(0).type());
      assertEquals("Google", entities.get(1).text());
      assertEquals("organization", entities.get(1).type());
    }

    @Test
    @DisplayName("two adjacent entities of same type")
    void adjacentSameType() {
      float[][] logits = {
          logitsFor(O),     // [CLS]
          logitsFor(B_PER), // Alice
          logitsFor(B_PER), // Bob
          logitsFor(O),     // [SEP]
      };
      String[] tokens = {"[CLS]", "Alice", "Bob", "[SEP]"};
      long[] wordIds = {-1, 0, 1, -1};

      List<BioTagDecoder.Entity> entities = BioTagDecoder.decode(logits, tokens, wordIds);

      assertEquals(2, entities.size());
      assertEquals("Alice", entities.get(0).text());
      assertEquals("Bob", entities.get(1).text());
    }

    @Test
    @DisplayName("entities with O tokens between them")
    void entitiesWithGap() {
      float[][] logits = {
          logitsFor(O),     // [CLS]
          logitsFor(B_PER), // Alice
          logitsFor(O),     // works
          logitsFor(O),     // at
          logitsFor(B_ORG), // Google
          logitsFor(O),     // [SEP]
      };
      String[] tokens = {"[CLS]", "Alice", "works", "at", "Google", "[SEP]"};
      long[] wordIds = {-1, 0, 1, 2, 3, -1};

      List<BioTagDecoder.Entity> entities = BioTagDecoder.decode(logits, tokens, wordIds);

      assertEquals(2, entities.size());
      assertEquals("Alice", entities.get(0).text());
      assertEquals("person", entities.get(0).type());
      assertEquals("Google", entities.get(1).text());
      assertEquals("organization", entities.get(1).type());
    }
  }

  @Nested
  @DisplayName("Edge cases")
  class EdgeCases {

    @Test
    @DisplayName("all O tags produces empty result")
    void allOTags() {
      float[][] logits = {
          logitsFor(O),
          logitsFor(O),
          logitsFor(O),
      };
      String[] tokens = {"[CLS]", "hello", "[SEP]"};
      long[] wordIds = {-1, 0, -1};

      List<BioTagDecoder.Entity> entities = BioTagDecoder.decode(logits, tokens, wordIds);
      assertTrue(entities.isEmpty());
    }

    @Test
    @DisplayName("null inputs return empty list")
    void nullInputs() {
      assertTrue(BioTagDecoder.decode(null, null, null).isEmpty());
    }

    @Test
    @DisplayName("orphan I-tag without preceding B-tag treated as B-tag")
    void orphanITag() {
      float[][] logits = {
          logitsFor(O),     // [CLS]
          logitsFor(I_PER), // John (orphan I-tag)
          logitsFor(O),     // [SEP]
      };
      String[] tokens = {"[CLS]", "John", "[SEP]"};
      long[] wordIds = {-1, 0, -1};

      List<BioTagDecoder.Entity> entities = BioTagDecoder.decode(logits, tokens, wordIds);

      assertEquals(1, entities.size());
      assertEquals("John", entities.get(0).text());
      assertEquals("person", entities.get(0).type());
    }

    @Test
    @DisplayName("MISC entities are filtered out")
    void miscFiltered() {
      float[][] logits = {
          logitsFor(O),      // [CLS]
          logitsFor(B_MISC), // English
          logitsFor(B_PER),  // John
          logitsFor(O),      // [SEP]
      };
      String[] tokens = {"[CLS]", "English", "John", "[SEP]"};
      long[] wordIds = {-1, 0, 1, -1};

      List<BioTagDecoder.Entity> entities = BioTagDecoder.decode(logits, tokens, wordIds);

      assertEquals(1, entities.size());
      assertEquals("John", entities.get(0).text());
      assertEquals("person", entities.get(0).type());
    }

    @Test
    @DisplayName("entity at end of sequence (before [SEP])")
    void entityAtEnd() {
      float[][] logits = {
          logitsFor(O),     // [CLS]
          logitsFor(O),     // works
          logitsFor(B_PER), // John
          logitsFor(I_PER), // Smith
          logitsFor(O),     // [SEP]
      };
      String[] tokens = {"[CLS]", "works", "John", "Smith", "[SEP]"};
      long[] wordIds = {-1, 0, 1, 2, -1};

      List<BioTagDecoder.Entity> entities = BioTagDecoder.decode(logits, tokens, wordIds);

      assertEquals(1, entities.size());
      assertEquals("John Smith", entities.get(0).text());
    }
  }

  @Nested
  @DisplayName("WordPiece subword handling")
  class WordPieceHandling {

    @Test
    @DisplayName("WordPiece ## subword tokens merged into entity")
    void subwordMerging() {
      float[][] logits = {
          logitsFor(O),     // [CLS]
          logitsFor(B_PER), // John
          logitsFor(I_PER), // ##son
          logitsFor(O),     // [SEP]
      };
      String[] tokens = {"[CLS]", "John", "##son", "[SEP]"};
      long[] wordIds = {-1, 0, 0, -1}; // Both map to word 0

      List<BioTagDecoder.Entity> entities = BioTagDecoder.decode(logits, tokens, wordIds);

      assertEquals(1, entities.size());
      assertEquals("Johnson", entities.get(0).text());
      assertEquals("person", entities.get(0).type());
    }

    @Test
    @DisplayName("multi-word entity with subwords")
    void multiWordWithSubwords() {
      float[][] logits = {
          logitsFor(O),     // [CLS]
          logitsFor(B_ORG), // Micro
          logitsFor(I_ORG), // ##soft
          logitsFor(I_ORG), // Corp
          logitsFor(O),     // [SEP]
      };
      String[] tokens = {"[CLS]", "Micro", "##soft", "Corp", "[SEP]"};
      long[] wordIds = {-1, 0, 0, 1, -1};

      List<BioTagDecoder.Entity> entities = BioTagDecoder.decode(logits, tokens, wordIds);

      assertEquals(1, entities.size());
      assertEquals("Microsoft Corp", entities.get(0).text());
      assertEquals("organization", entities.get(0).type());
    }
  }

  @Nested
  @DisplayName("Utility methods")
  class UtilityMethods {

    @Test
    @DisplayName("argmax returns correct index")
    void argmaxCorrect() {
      assertEquals(2, BioTagDecoder.argmax(new float[]{-1f, 0f, 5f, 3f}));
      assertEquals(0, BioTagDecoder.argmax(new float[]{10f, -1f, -2f}));
    }

    @Test
    @DisplayName("argmax on empty returns 0")
    void argmaxEmpty() {
      assertEquals(0, BioTagDecoder.argmax(new float[]{}));
    }

    @Test
    @DisplayName("softmaxMax produces valid probability")
    void softmaxMaxValid() {
      float[] logits = {5.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f};
      float prob = BioTagDecoder.softmaxMax(logits, 0);
      assertTrue(prob > 0.9f, "Expected high probability for dominant logit, got " + prob);
      assertTrue(prob <= 1.0f);
    }

    @Test
    @DisplayName("entityType maps labels correctly")
    void entityTypeMapping() {
      assertEquals("person", BioTagDecoder.entityType("B-PER"));
      assertEquals("person", BioTagDecoder.entityType("I-PER"));
      assertEquals("organization", BioTagDecoder.entityType("B-ORG"));
      assertEquals("location", BioTagDecoder.entityType("B-LOC"));
      assertEquals(null, BioTagDecoder.entityType("O"));
      assertEquals(null, BioTagDecoder.entityType("B-MISC"));
    }
  }

  @Nested
  @DisplayName("I-tag type mismatch")
  class ITagMismatch {

    @Test
    @DisplayName("I-ORG after B-PER starts new entity")
    void continuationTypeMismatch() {
      float[][] logits = {
          logitsFor(O),     // [CLS]
          logitsFor(B_PER), // Alice
          logitsFor(I_ORG), // Corp (type mismatch — should NOT continue PER)
          logitsFor(O),     // [SEP]
      };
      String[] tokens = {"[CLS]", "Alice", "Corp", "[SEP]"};
      long[] wordIds = {-1, 0, 1, -1};

      List<BioTagDecoder.Entity> entities = BioTagDecoder.decode(logits, tokens, wordIds);

      // Alice (person) flushed, then Corp treated as orphan I-ORG → new org entity
      assertEquals(2, entities.size());
      assertEquals("Alice", entities.get(0).text());
      assertEquals("person", entities.get(0).type());
      assertEquals("Corp", entities.get(1).text());
      assertEquals("organization", entities.get(1).type());
    }
  }

  @Nested
  @DisplayName("Custom label mapping (distilbert-NER)")
  class DistilBertMapping {

    // distilbert-NER: 0=O, 1=B-PER, 2=I-PER, 3=B-ORG, 4=I-ORG, 5=B-LOC, 6=I-LOC, 7=B-MISC,
    // 8=I-MISC
    static final int DB_B_PER = 1;
    static final int DB_I_PER = 2;
    static final int DB_B_ORG = 3;

    static final BioTagDecoder.LabelMapping DISTILBERT_MAPPING =
        BioTagDecoder.LabelMapping.fromId2Label(
            java.util.Map.of(
                "0", "O", "1", "B-PER", "2", "I-PER", "3", "B-ORG", "4", "I-ORG", "5", "B-LOC",
                "6", "I-LOC", "7", "B-MISC", "8", "I-MISC"));

    private static float[] dbLogits(int label) {
      float[] logits = new float[NUM_LABELS];
      for (int i = 0; i < logits.length; i++) {
        logits[i] = (i == label) ? 5.0f : -1.0f;
      }
      return logits;
    }

    @Test
    @DisplayName("distilbert-NER mapping decodes PER at index 1 (not MISC)")
    void distilbertPersonAtIndex1() {
      float[][] logits = {
        dbLogits(0), // [CLS] = O
        dbLogits(DB_B_PER), // John = B-PER (index 1 in distilbert, B-MISC in bert-base)
        dbLogits(DB_I_PER), // Smith = I-PER
        dbLogits(0), // [SEP] = O
      };
      String[] tokens = {"[CLS]", "John", "Smith", "[SEP]"};
      long[] wordIds = {-1, 0, 1, -1};

      List<BioTagDecoder.Entity> entities =
          BioTagDecoder.decode(logits, tokens, wordIds, DISTILBERT_MAPPING);

      assertEquals(1, entities.size());
      assertEquals("John Smith", entities.get(0).text());
      assertEquals("person", entities.get(0).type()); // B-PER, not B-MISC
    }

    @Test
    @DisplayName("same logits decode differently with bert-base vs distilbert mapping")
    void mappingAffectsDecoding() {
      // Index 1 is B-MISC in bert-base, B-PER in distilbert
      float[][] logits = {
        dbLogits(0), dbLogits(1), dbLogits(0),
      };
      String[] tokens = {"[CLS]", "Test", "[SEP]"};
      long[] wordIds = {-1, 0, -1};

      // With bert-base mapping: index 1 = B-MISC → filtered out
      List<BioTagDecoder.Entity> bertEntities = BioTagDecoder.decode(logits, tokens, wordIds);
      assertEquals(0, bertEntities.size(), "bert-base: index 1 is B-MISC, should be filtered");

      // With distilbert mapping: index 1 = B-PER → person entity
      List<BioTagDecoder.Entity> distilEntities =
          BioTagDecoder.decode(logits, tokens, wordIds, DISTILBERT_MAPPING);
      assertEquals(1, distilEntities.size(), "distilbert: index 1 is B-PER, should be person");
      assertEquals("person", distilEntities.get(0).type());
    }
  }
}
