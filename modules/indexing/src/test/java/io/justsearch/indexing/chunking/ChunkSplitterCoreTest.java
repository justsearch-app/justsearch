package io.justsearch.indexing.chunking;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Core unit tests for ChunkSplitter focusing on high-value test cases.
 *
 * <p>Tests focus on:
 * <ul>
 *   <li>Overlap mechanism verification (chunks actually share content)</li>
 *   <li>Boundary preference hierarchy (paragraph > sentence > word)</li>
 *   <li>Markdown mode edge cases (~~~ fences, unterminated fences)</li>
 *   <li>Chunk metadata accuracy (offsets, indices)</li>
 *   <li>Content coverage (no content lost between chunks)</li>
 * </ul>
 *
 * <p>Related: tempdoc-73-chunksplitter-test-analysis.md
 */
@DisplayName("ChunkSplitter Core")
class ChunkSplitterCoreTest {

  @Test
  void supplementaryCharactersKeepWholeCodePointsInEveryMode() {
    for (var mode : ChunkSplitter.Mode.values()) {
      for (String symbol : List.of("\uD83D\uDE00", "\uD840\uDC00")) {
        for (String prefix : List.of("", "a")) {
          for (int target : new int[] {100, 500, 501}) {
            assertSafeChunks(prefix + symbol.repeat(3000), target, 50, mode);
            assertSafeChunks(prefix + symbol.repeat(3000), target, 0, mode);
          }
          for (int[] budget : new int[][] {
              {-1, 0}, {0, 0}, {1, 0}, {1, 1}, {1, Integer.MAX_VALUE},
              {2, 50}, {3, 0}, {99, 100}, {500, Integer.MAX_VALUE},
              {Integer.MAX_VALUE, Integer.MAX_VALUE}}) {
            assertSafeChunks(prefix + symbol.repeat(31), budget[0], budget[1], mode);
          }
        }
      }
    }
  }

  private static void assertSafeChunks(String source, int target, int overlap, ChunkSplitter.Mode mode) {
    var chunks = ChunkSplitter.splitWithMetadata(source, target, overlap, mode);
    assertFalse(chunks.isEmpty());
    assertTrue(chunks.size() <= source.codePointCount(0, source.length()));
    int previousStart = -1;
    int previousEnd = 0;
    for (var chunk : chunks) {
      assertEquals(source.substring(chunk.startChar(), chunk.endChar()), chunk.content());
      assertSafeBoundary(source, chunk.startChar());
      assertSafeBoundary(source, chunk.endChar());
      assertTrue(chunk.startChar() > previousStart, "windows must advance");
      assertTrue(chunk.startChar() <= previousEnd, "alignment must not open a gap");
      assertTrue(chunk.endChar() > previousEnd, "no contained suffix");
      previousStart = chunk.startChar();
      previousEnd = chunk.endChar();
    }
    assertEquals(source.length(), previousEnd);
    var plain = ChunkSplitter.split(source, target, overlap, mode);
    assertEquals(chunks.stream().map(ChunkSplitter.Chunk::content).toList(), plain);
    for (String chunk : plain) {
      assertFalse(Character.isLowSurrogate(chunk.charAt(0)));
      assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)));
    }
  }

  private static void assertSafeBoundary(String source, int offset) {
    assertFalse(offset > 0 && offset < source.length()
        && Character.isHighSurrogate(source.charAt(offset - 1))
        && Character.isLowSurrogate(source.charAt(offset)), "split surrogate pair at " + offset);
  }

  @Test
  void malformedSourceRemainsUnmodifiedAndTrimDefinitionsStayDistinct() {
    String source = "\u0000\u2003x\uD800y\uDC00z\u2003\u0000";
    for (var mode : ChunkSplitter.Mode.values()) {
      assertEquals(List.of(source.trim()), ChunkSplitter.split(source, 500, 50, mode));
      var chunks = ChunkSplitter.splitWithMetadata(source, 500, 50, mode);
      assertEquals(1, chunks.size());
      assertEquals(source.strip(), chunks.get(0).content());
      assertEquals(source.substring(chunks.get(0).startChar(), chunks.get(0).endChar()),
          chunks.get(0).content());
      assertEquals(List.of("x"), ChunkSplitter.split("\u0000x\u0000", 500, 50, mode));
      assertEquals("\u0000x\u0000", ChunkSplitter.splitWithMetadata("\u0000x\u0000", 500, 50, mode)
          .get(0).content());
      assertEquals(List.of("\u2003x\u2003"), ChunkSplitter.split("\u2003x\u2003", 500, 50, mode));
      assertEquals("x", ChunkSplitter.splitWithMetadata("\u2003x\u2003", 500, 50, mode).get(0).content());
    }
  }

  @Test
  void asciiAndBmpHardCutOffsetsRemainInUtf16Units() {
    for (String symbol : List.of("a", "é", "漢")) {
      String source = symbol.repeat(6000);
      int target = symbol.equals("漢") ? 500 : 1923;
      int overlap = symbol.equals("漢") ? 50 : 192;
      for (var mode : ChunkSplitter.Mode.values()) {
        var chunks = ChunkSplitter.splitWithMetadata(source, 500, 50, mode);
        int start = 0;
        for (var chunk : chunks) {
          int end = Math.min(start + target, source.length());
          assertEquals(start, chunk.startChar());
          assertEquals(end, chunk.endChar());
          assertEquals(source.substring(start, end), chunk.content());
          start = end - overlap;
        }
        assertEquals(source.length(), chunks.get(chunks.size() - 1).endChar());
      }
    }
  }

  @Nested
  @DisplayName("Overlap Mechanism")
  class OverlapMechanismTests {

    @Test
    @DisplayName("overlap mechanism produces chunks with shared content")
    void overlapMechanismProducesActualOverlap() {
      // Use small targetTokens to ensure minChars doesn't dominate
      // targetTokens=30 → targetChars ≈ 115 chars
      // overlapTokens=10 → overlapChars ≈ 38 chars
      // minChars = min(385, 115) = 115 → advance = max(endPos-38, 115)

      // Create content with clear sentence boundaries for predictable chunking
      String content =
          "Alpha sentence one ends here. Bravo sentence two ends here. "
              + "Charlie sentence three ends here. Delta sentence four ends here. "
              + "Echo sentence five ends here. Foxtrot sentence six ends here. "
              + "Golf sentence seven ends here. Hotel sentence eight ends here.";
      // Total ~240 chars, should produce 2+ chunks with overlap

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 30, 10, ChunkSplitter.Mode.DEFAULT);

      assertTrue(chunks.size() >= 2, "Should produce multiple chunks");

      // STRONG ASSERTION: Verify actual content overlap
      for (int i = 1; i < chunks.size(); i++) {
        String prevChunk = chunks.get(i - 1).content();
        String currChunk = chunks.get(i).content();

        // Find overlapping content: end of prev should appear at start of curr
        // Try progressively smaller suffixes of prevChunk
        boolean foundOverlap = false;
        for (int len = Math.min(50, prevChunk.length()); len >= 5; len--) {
          String suffix = prevChunk.substring(prevChunk.length() - len);
          if (currChunk.startsWith(suffix)) {
            foundOverlap = true;
            break;
          }
        }

        assertTrue(
            foundOverlap,
            "Chunk "
                + i
                + " should share content with chunk "
                + (i - 1)
                + "\nPrev ends with: '"
                + prevChunk.substring(Math.max(0, prevChunk.length() - 40))
                + "'"
                + "\nCurr starts with: '"
                + currChunk.substring(0, Math.min(40, currChunk.length()))
                + "'");
      }
    }
  }

  @Nested
  @DisplayName("Boundary Preference Hierarchy")
  class BoundaryPreferenceTests {

    @Test
    @DisplayName("paragraph boundary chosen over sentence when both in search window")
    void boundaryPreferenceParagraphOverSentence() {
      // Key constraint: paragraph boundary must be AFTER minEnd to be valid
      // With targetTokens=30 → targetChars ≈ 115 chars → minChars = min(385, 115) = 115
      // So paragraph break must be at position > 115 to be valid
      //
      // Content structure:
      // - ~120 chars of first paragraph with multiple sentence breaks
      // - Paragraph boundary (\n\n) at ~120 chars
      // - Second paragraph with sentence breaks
      //
      // Both paragraph (~120) and sentence (~100, ~140) boundaries will be in search window
      // Algorithm checks paragraph FIRST → should return paragraph position

      String content =
          "Alpha sentence in first paragraph ends here. Bravo sentence continues on. "
              + "Charlie sentence follows suit. Delta sentence is here.\n\n"
              + "Echo sentence starts second paragraph. Foxtrot sentence ends here.";
      // First paragraph: ~130 chars, paragraph break at ~132
      // Sentence breaks at: ~45, ~75, ~100, ~128, ~165, ~195

      // Use targetTokens=40 → targetChars ≈ 154 chars
      // minChars = min(385, 154) = 154
      // Wait, that's still larger than paragraph position...

      // Let me recalculate: need paragraph position > minEnd
      // With targetTokens=50 → targetChars ≈ 192 chars → minChars = min(385, 192) = 192
      // Still too large. Need smaller targetTokens.
      //
      // Actually: minChars = min(MIN_CHUNK_TOKENS/1.3*5, targetChars)
      //         = min(385, targetChars)
      // For minChars < 130, we need targetChars < 130
      // targetChars = targetTokens / 1.3 * 5 = targetTokens * 3.85
      // For targetChars = 120: targetTokens = 120 / 3.85 ≈ 31

      // Let's use targetTokens=35 → targetChars ≈ 135
      // minChars = min(385, 135) = 135
      // Paragraph at ~132 is still < 135... need to increase content

      // Simpler approach: use very small targetTokens so minChars is small
      // targetTokens=25 → targetChars ≈ 96, minChars = 96
      // Paragraph at ~132 > 96 ✓

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 25, 5, ChunkSplitter.Mode.DEFAULT);

      assertTrue(chunks.size() >= 2, "Should produce at least 2 chunks, got: " + chunks.size());

      ChunkSplitter.Chunk firstChunk = chunks.get(0);
      String firstContent = firstChunk.content();
      int firstEndChar = firstChunk.endChar();

      // STRONG ASSERTION: First chunk should end at paragraph boundary
      // Content after first chunk should start with "Echo" (second paragraph)
      String afterFirstChunk = content.substring(firstEndChar).stripLeading();

      assertTrue(
          afterFirstChunk.startsWith("Echo"),
          "First chunk should end at paragraph boundary (before 'Echo')"
              + "\nFirst chunk content: '"
              + firstContent
              + "'"
              + "\nContent after chunk: '"
              + afterFirstChunk.substring(0, Math.min(40, afterFirstChunk.length()))
              + "'"
              + "\nfirstEndChar: "
              + firstEndChar);

      // Also verify it didn't end at an earlier sentence boundary within first paragraph
      assertFalse(
          firstContent.endsWith("Alpha sentence in first paragraph ends here"),
          "Should NOT have ended at first sentence - paragraph preference should win");
    }

    @Test
    @DisplayName("sentence boundary chosen over word when no paragraph available")
    void boundaryPreferenceSentenceOverWord() {
      // Content with sentences but NO paragraph breaks (no \n\n)
      String content =
          "First sentence ends here. Second sentence continues on. "
              + "Third sentence follows suit. Fourth sentence is here. "
              + "Fifth sentence appears now. Sixth sentence wraps up.";

      // Target to force split mid-content
      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 35, 5, ChunkSplitter.Mode.DEFAULT);

      assertTrue(chunks.size() >= 2, "Should produce multiple chunks");

      // STRONG ASSERTION: All non-final chunks should end at sentence boundary
      for (int i = 0; i < chunks.size() - 1; i++) {
        String chunk = chunks.get(i).content();
        assertTrue(
            chunk.endsWith(".") || chunk.endsWith("!") || chunk.endsWith("?"),
            "Chunk " + i + " should end at sentence boundary: '" + chunk + "'");
      }
    }

    @Test
    @DisplayName("word boundary used as fallback when no sentence markers")
    void boundaryFallbackToWordWhenNoSentence() {
      // Content with NO sentence-ending punctuation, only spaces
      String content =
          "alpha bravo charlie delta echo foxtrot golf hotel india juliet "
              + "kilo lima mike november oscar papa quebec romeo sierra tango "
              + "uniform victor whiskey xray yankee zulu";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 25, 5, ChunkSplitter.Mode.DEFAULT);

      assertTrue(chunks.size() >= 2, "Should produce multiple chunks");

      // STRONG ASSERTION: Chunks should end at word boundaries (complete words)
      // Last character should be a letter (end of word) or whitespace
      for (int i = 0; i < chunks.size() - 1; i++) {
        String chunk = chunks.get(i).content().trim();
        char lastChar = chunk.charAt(chunk.length() - 1);
        assertTrue(
            Character.isLetterOrDigit(lastChar),
            "Chunk " + i + " should end with complete word, not mid-word: '" + chunk + "'");
      }
    }
  }

  @Nested
  @DisplayName("Content Coverage")
  class ContentCoverageTests {

    @Test
    @DisplayName("chunks together cover start and end of original content")
    void chunksTogetherCoverEntireContent() {
      String content =
          "The quick brown fox jumps over the lazy dog. "
              + "Pack my box with five dozen liquor jugs. "
              + "How quickly daft zebras jump high.";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 30, 5, ChunkSplitter.Mode.DEFAULT);

      assertTrue(chunks.size() >= 1, "Should produce at least one chunk");

      // STRONG ASSERTION: First chunk starts at beginning, last chunk reaches end
      ChunkSplitter.Chunk firstChunk = chunks.get(0);
      ChunkSplitter.Chunk lastChunk = chunks.get(chunks.size() - 1);

      assertEquals(0, firstChunk.startChar(), "First chunk should start at beginning");
      assertTrue(
          lastChunk.endChar() >= content.trim().length() - 10,
          "Last chunk should reach near end of content. "
              + "Content length: " + content.trim().length() + ", lastChunk.endChar: " + lastChunk.endChar());

      // Also verify first and last words appear
      assertTrue(
          firstChunk.content().toLowerCase(java.util.Locale.ROOT).contains("quick"),
          "First chunk should contain 'quick'");
      assertTrue(
          lastChunk.content().toLowerCase(java.util.Locale.ROOT).contains("high"),
          "Last chunk should contain 'high'");
    }

    @Test
    @DisplayName("boundary search respects search window limits")
    void searchWindowRespected() {
      // Create content with a paragraph break far from target position
      String farParagraph = "Far away paragraph.\n\n";
      String nearContent = "x ".repeat(100); // ~200 chars of repeated content
      String content = farParagraph + nearContent + "End here.";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 50, 10, ChunkSplitter.Mode.DEFAULT);

      assertTrue(chunks.size() >= 1, "Should produce chunks");

      // First chunk should be reasonable size, not extending to far boundaries
      int firstChunkLength = chunks.get(0).content().length();
      assertTrue(
          firstChunkLength < 500,
          "First chunk should be bounded, not extend to distant paragraph: " + firstChunkLength);
    }
  }

  @Nested
  @DisplayName("Markdown Mode Variations")
  class MarkdownModeVariationsTests {

    @Test
    @DisplayName("detects ~~~ fence markers")
    void detectsTildeFenceMarkers() {
      String content =
          "Intro text with some content.\n\n"
              + "~~~python\n"
              + "def hello():\n"
              + "    print('hello')\n"
              + "~~~\n\n"
              + "After the fence with more content that goes on and on.";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 30, 5, ChunkSplitter.Mode.MARKDOWN);

      // Verify no chunk splits inside the fence
      for (ChunkSplitter.Chunk chunk : chunks) {
        String chunkContent = chunk.content();
        int tildeCount = countOccurrences(chunkContent, "~~~");
        assertNotEquals(
            1, tildeCount, "Chunk should not contain exactly one ~~~ marker: " + chunkContent);
      }
    }

    @Test
    @DisplayName("handles unterminated fence (open until end)")
    void handlesUnterminatedFence() {
      String content =
          "Before fence.\n\n"
              + "```js\n"
              + "// This code block is never closed\n"
              + "function test() {\n"
              + "  return true;\n"
              + "}\n"
              + "// More code here\n"
              + "// And even more code";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 30, 5, ChunkSplitter.Mode.MARKDOWN);

      // Should not crash and should produce valid chunks
      assertFalse(chunks.isEmpty(), "Should produce chunks");

      // Verify all chunks have valid content
      for (ChunkSplitter.Chunk chunk : chunks) {
        assertFalse(chunk.content().isBlank(), "Chunk should have content");
      }
    }

    @Test
    @DisplayName("handles indented fences (up to 3 spaces)")
    void handlesIndentedFences() {
      String content =
          "Normal text here.\n\n"
              + "   ```\n" // 3 spaces indent
              + "   indented code\n"
              + "   ```\n\n"
              + "After the indented fence.";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 30, 5, ChunkSplitter.Mode.MARKDOWN);

      assertFalse(chunks.isEmpty(), "Should produce chunks");

      // Verify fence markers are paired
      int totalFences = 0;
      for (ChunkSplitter.Chunk chunk : chunks) {
        totalFences += countOccurrences(chunk.content(), "```");
      }
      assertEquals(2, totalFences, "Should have exactly 2 fence markers total");
    }

    @Test
    @DisplayName("mismatched fence markers (``` followed by ~~~) treated as separate")
    void mismatchedFencesAreSeparate() {
      String content =
          "Before.\n\n"
              + "```\n"
              + "code block\n"
              + "~~~\n" // Wrong closing marker
              + "```\n" // Correct closing marker
              + "After.";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 100, 10, ChunkSplitter.Mode.MARKDOWN);

      assertFalse(chunks.isEmpty(), "Should produce chunks");
      // Should not crash - mismatched markers should be handled gracefully
    }
  }

  @Nested
  @DisplayName("Chunk Metadata Accuracy")
  class ChunkMetadataTests {

    @Test
    @DisplayName("chunk offsets match original content positions")
    void chunkOffsetsMatchOriginal() {
      String content = "First part. Second part. Third part. Fourth part.";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 15, 3, ChunkSplitter.Mode.DEFAULT);

      String trimmed = content.trim();
      for (ChunkSplitter.Chunk chunk : chunks) {
        assertTrue(chunk.startChar() >= 0, "Start should be non-negative");
        assertTrue(chunk.endChar() <= trimmed.length(), "End should not exceed content length");
        assertTrue(chunk.endChar() > chunk.startChar(), "End should be after start");

        // Verify the chunk content matches the slice from original
        String expectedSlice = trimmed.substring(chunk.startChar(), chunk.endChar()).trim();
        assertEquals(expectedSlice, chunk.content(), "Chunk content should match slice");
      }
    }

    @Test
    @DisplayName("chunk indices are sequential starting from 0")
    void chunkIndicesSequential() {
      String content = "Word ".repeat(100);

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 30, 5, ChunkSplitter.Mode.DEFAULT);

      assertTrue(chunks.size() > 1, "Should produce multiple chunks");

      for (int i = 0; i < chunks.size(); i++) {
        assertEquals(i, chunks.get(i).index(), "Chunk index should be sequential");
      }
    }

    @Test
    @DisplayName("Chunk.of() correctly calculates estimated tokens")
    void chunkOfCalculatesTokens() {
      ChunkSplitter.Chunk chunk = ChunkSplitter.Chunk.of(0, "Hello world test content", 0, 24);

      assertEquals(0, chunk.index());
      assertEquals("Hello world test content", chunk.content());
      assertEquals(0, chunk.startChar());
      assertEquals(24, chunk.endChar());
      assertTrue(chunk.estimatedTokens() > 0, "Should estimate non-zero tokens");
    }
  }

  @Nested
  @DisplayName("Edge Cases")
  class EdgeCaseTests {

    @Test
    @DisplayName("small content returns single chunk")
    void smallContentSingleChunk() {
      String content = "Short content.";

      List<String> chunks = ChunkSplitter.split(content);

      assertEquals(1, chunks.size(), "Should return single chunk for small content");
      assertEquals("Short content.", chunks.get(0));
    }

    @Test
    @DisplayName("split with Mode parameter works correctly")
    void splitWithModeWorks() {
      String content = "Line one.\nLine two.\nLine three.";

      List<String> defaultChunks = ChunkSplitter.split(content, ChunkSplitter.Mode.DEFAULT);
      List<String> codeChunks = ChunkSplitter.split(content, ChunkSplitter.Mode.CODE);

      assertFalse(defaultChunks.isEmpty(), "Should produce chunks in DEFAULT mode");
      assertFalse(codeChunks.isEmpty(), "Should produce chunks in CODE mode");
    }

    @Test
    @DisplayName("zero overlap still produces valid chunks")
    void zeroOverlapProducesValidChunks() {
      String content = "First. Second. Third. Fourth. Fifth. Sixth. Seventh. Eighth.";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 20, 0, ChunkSplitter.Mode.DEFAULT);

      assertTrue(chunks.size() >= 1, "Should produce chunks");

      for (ChunkSplitter.Chunk chunk : chunks) {
        assertFalse(chunk.content().isBlank(), "Chunk should have content");
        assertTrue(chunk.startChar() >= 0, "Start offset should be valid");
        assertTrue(chunk.endChar() > chunk.startChar(), "End should be after start");
      }
    }

    @Test
    @DisplayName("very small targetTokens still produces valid chunks")
    void verySmallTargetTokensValid() {
      String content = "Hello world this is a test of small chunks.";

      // Very small target - tests the minChars recalculation
      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 5, 1, ChunkSplitter.Mode.DEFAULT);

      assertFalse(chunks.isEmpty(), "Should produce chunks");

      for (ChunkSplitter.Chunk chunk : chunks) {
        assertFalse(chunk.content().isBlank(), "Each chunk should have content");
        assertTrue(chunk.startChar() >= 0, "Start offset should be valid");
        assertTrue(chunk.endChar() > chunk.startChar(), "End should be after start");
      }
    }

    @Test
    @DisplayName("offsets match content exactly without needing trim")
    void offsetsMatchContentWithoutTrim() {
      // Content with leading/trailing whitespace that could be trimmed
      String content = "  Word one.   Word two.  \n\n  Word three.  Word four.  ";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 15, 3, ChunkSplitter.Mode.DEFAULT);

      for (ChunkSplitter.Chunk chunk : chunks) {
        // Extract substring using offsets - NO TRIM
        String fromOffsets = content.substring(chunk.startChar(), chunk.endChar());

        // BUG: If this fails, offsets don't account for trim()
        assertEquals(
            chunk.content(),
            fromOffsets,
            "Offset extraction should match content WITHOUT needing trim. "
                + "Offsets: ["
                + chunk.startChar()
                + ", "
                + chunk.endChar()
                + "], "
                + "fromOffsets: '"
                + fromOffsets
                + "', "
                + "content: '"
                + chunk.content()
                + "'");
      }
    }

    @Test
    @DisplayName("nested same-type fences handled correctly")
    void nestedSameTypeFencesHandledCorrectly() {
      // Outer fence contains inner fence markers
      String content =
          "Before.\n\n"
              + "```outer\n"
              + "Some code here\n"
              + "```inner\n" // This should NOT close outer
              + "More code\n"
              + "```\n" // This closes inner
              + "Still in outer\n"
              + "```\n" // This closes outer
              + "After the fence.";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 30, 5, ChunkSplitter.Mode.MARKDOWN);

      // If bug exists: chunk boundary will be inside "outer" fence incorrectly
      for (ChunkSplitter.Chunk chunk : chunks) {
        String c = chunk.content();
        int openCount = countOccurrences(c, "```");

        // If chunk contains fence markers, they should be balanced (0 or 2 or 4)
        // Odd count means we split inside a fence
        if (openCount > 0) {
          assertEquals(
              0, openCount % 2, "Chunk should not split inside fence. Content: " + c);
        }
      }
    }

    @Test
    @DisplayName("extreme targetTokens values handled safely")
    void extremeTargetTokensHandledSafely() {
      String content = "Test content for overflow checking.";

      // Very large targetTokens - should not overflow or crash
      assertDoesNotThrow(
          () -> {
            List<String> chunks =
                ChunkSplitter.split(content, Integer.MAX_VALUE / 10, 100);
            assertFalse(chunks.isEmpty());
          });

      // Negative targetTokens - should either throw or handle gracefully
      try {
        List<String> chunks = ChunkSplitter.split(content, -100, 50);
        // If no exception, verify chunks are sensible
        assertFalse(chunks.isEmpty(), "Negative tokens should still produce chunks or throw");
      } catch (IllegalArgumentException e) {
        // This is acceptable behavior
      }
    }

    @Test
    @DisplayName("CJK content produces reasonable chunk sizes")
    void cjkContentProducesReasonableChunks() {
      // CJK: approximately 1 token per character, not 1.3 tokens per 5 chars
      String cjk =
          "你好世界。这是测试内容。我们需要验证中文分块是否正确。"
              + "更多的中文内容在这里。这个句子应该被正确处理。"
              + "最后一个句子结束这段测试内容。";
      // ~70 CJK chars = ~70 tokens

      // Request 30 tokens per chunk - should split CJK content
      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(cjk, 30, 5, ChunkSplitter.Mode.DEFAULT);

      // BUG: If ratio is wrong, this produces 1 giant chunk instead of multiple
      assertTrue(
          chunks.size() >= 2,
          "CJK content should split into multiple chunks. "
              + "Got "
              + chunks.size()
              + " chunks for "
              + cjk.length()
              + " chars. "
              + "First chunk: "
              + chunks.get(0).content().length()
              + " chars");

      // Each chunk should be reasonable size (not giant)
      for (ChunkSplitter.Chunk chunk : chunks) {
        assertTrue(
            chunk.content().length() < cjk.length() * 0.8,
            "Each chunk should be smaller than 80% of total. "
                + "Chunk size: "
                + chunk.content().length());
      }
    }

    @Test
    @DisplayName("emoji surrogate pairs not split")
    void emojiSurrogatePairsNotSplit() {
      // Emoji (surrogate pairs in Java UTF-16)
      String emoji = "Hello 😀 World 🎉 Test 🚀 Content 💡 Here 🔥 More";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(emoji, 10, 2, ChunkSplitter.Mode.DEFAULT);

      for (ChunkSplitter.Chunk chunk : chunks) {
        String c = chunk.content();
        // Check for malformed Unicode (unpaired surrogates)
        for (int i = 0; i < c.length(); i++) {
          char ch = c.charAt(i);
          if (Character.isHighSurrogate(ch)) {
            assertTrue(
                i + 1 < c.length() && Character.isLowSurrogate(c.charAt(i + 1)),
                "High surrogate at "
                    + i
                    + " should be followed by low surrogate. "
                    + "Chunk: '"
                    + c
                    + "'");
          }
          if (Character.isLowSurrogate(ch)) {
            assertTrue(
                i > 0 && Character.isHighSurrogate(c.charAt(i - 1)),
                "Low surrogate at "
                    + i
                    + " should be preceded by high surrogate. "
                    + "Chunk: '"
                    + c
                    + "'");
          }
        }
      }
    }

    @Test
    @DisplayName("overlap >= target produces finite reasonable chunks")
    void extremeOverlapProducesFiniteChunks() {
      String content =
          "Sentence one. Sentence two. Sentence three. Sentence four. "
              + "Sentence five. Sentence six. Sentence seven. Sentence eight.";

      // Overlap equal to target - edge case
      List<ChunkSplitter.Chunk> equalChunks =
          ChunkSplitter.splitWithMetadata(content, 20, 20, ChunkSplitter.Mode.DEFAULT);

      assertTrue(
          equalChunks.size() < 50,
          "Equal overlap/target should not explode chunk count. Got: " + equalChunks.size());

      // Overlap greater than target
      List<ChunkSplitter.Chunk> largerChunks =
          ChunkSplitter.splitWithMetadata(content, 20, 30, ChunkSplitter.Mode.DEFAULT);

      assertTrue(
          largerChunks.size() < 50,
          "Larger overlap should not explode chunk count. Got: " + largerChunks.size());

      // Verify chunks still make forward progress
      if (equalChunks.size() > 1) {
        for (int i = 1; i < equalChunks.size(); i++) {
          assertTrue(
              equalChunks.get(i).startChar() > equalChunks.get(i - 1).startChar(),
              "Chunks should make forward progress");
        }
      }
    }

    @Test
    @DisplayName("large code fence not split internally")
    void largeCodeFenceNotSplitInternally() {
      // Large fence that exceeds typical chunk size
      String largeFence =
          "Before fence.\n\n```java\n"
              + "// Large code block\n".repeat(100) // ~2000 chars
              + "```\n\nAfter fence.";

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(largeFence, 50, 10, ChunkSplitter.Mode.MARKDOWN);

      // Find chunk(s) containing the fence markers
      boolean foundFenceStart = false;
      boolean foundClosingMarker = false;
      boolean foundAfterFence = false;

      for (ChunkSplitter.Chunk chunk : chunks) {
        if (chunk.content().contains("```java")) foundFenceStart = true;
        // Check for closing marker (``` not followed by language specifier)
        if (chunk.content().contains("```\n") || chunk.content().endsWith("```"))
          foundClosingMarker = true;
        if (chunk.content().contains("After fence")) foundAfterFence = true;

        // No chunk should contain exactly one ``` (would mean split inside fence)
        // This is the key assertion: fences should be kept whole
        int fenceCount = countOccurrences(chunk.content(), "```");
        assertNotEquals(
            1, fenceCount, "Chunk should not contain exactly one fence marker: " + chunk.content());
      }

      assertTrue(foundFenceStart, "Should find fence start (```java)");
      assertTrue(foundClosingMarker, "Should find closing fence marker (```)");
      assertTrue(foundAfterFence, "Should find content after fence");
    }
  }

  @Nested
  @DisplayName("End-of-text overlap-tail regression (tempdoc 749)")
  class EndOfTextOverlapTailTests {

    @Test
    @DisplayName("plain text within [minChars, targetChars] band yields exactly one chunk")
    void singleChunkAcrossMinToTargetBand() {
      int[] lengths = {385, 500, 1000, 1900, 1923};
      for (int length : lengths) {
        String text = plainText(length);

        List<String> chunks = ChunkSplitter.split(text);
        assertEquals(
            1,
            chunks.size(),
            "split() should produce exactly 1 chunk for length " + length + ", got " + chunks.size());
        assertEquals(
            text, chunks.get(0), "single chunk should equal the full text for length " + length);

        List<ChunkSplitter.Chunk> metaChunks =
            ChunkSplitter.splitWithMetadata(
                text,
                ChunkSplitter.DEFAULT_CHUNK_TOKENS,
                ChunkSplitter.DEFAULT_OVERLAP_TOKENS,
                ChunkSplitter.Mode.DEFAULT);
        assertEquals(
            1,
            metaChunks.size(),
            "splitWithMetadata() should produce exactly 1 chunk for length "
                + length
                + ", got "
                + metaChunks.size());
        ChunkSplitter.Chunk chunk = metaChunks.get(0);
        assertEquals(0, chunk.startChar(), "startChar should be 0 for length " + length);
        assertEquals(length, chunk.endChar(), "endChar should cover full text for length " + length);
      }
    }

    @Test
    @DisplayName("no chunk span is fully contained within its predecessor's span")
    void noChunkSpanContainedInPredecessor() {
      String text = plainText(4000);

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(
              text,
              ChunkSplitter.DEFAULT_CHUNK_TOKENS,
              ChunkSplitter.DEFAULT_OVERLAP_TOKENS,
              ChunkSplitter.Mode.DEFAULT);

      assertTrue(chunks.size() >= 2, "Should produce multiple chunks for a 4000-char document");

      for (int i = 1; i < chunks.size(); i++) {
        ChunkSplitter.Chunk prev = chunks.get(i - 1);
        ChunkSplitter.Chunk curr = chunks.get(i);
        boolean contained = curr.startChar() >= prev.startChar() && curr.endChar() <= prev.endChar();
        assertFalse(
            contained,
            "Chunk "
                + i
                + " ["
                + curr.startChar()
                + ","
                + curr.endChar()
                + ") is fully contained within predecessor "
                + (i - 1)
                + " ["
                + prev.startChar()
                + ","
                + prev.endChar()
                + ")");
      }
    }

    @Test
    @DisplayName("trimmed all-whitespace-tail window does not emit a contained chunk (CJK counterexample)")
    void trimmedWhitespaceTailWindowDoesNotEmitContainedChunk() {
      // Deterministic reconstruction of the jqwik-shrunk counterexample from
      // ChunkTilingPropertyTest ("chunk 1 [33,34) is fully contained within predecessor [0,34)").
      // The property found it with CJK content in JSON mode; rebuilt here in the same regime —
      // CJK-dominant content has chars-per-token = 1.0, so targetChars == targetTokens — using a
      // DEFAULT-mode sentence boundary to extend the first window one past its target end.
      //
      // Layout (0-based indices): 0..10 = 11 CJK; 11 = '.'; 12..20 = 9 spaces; 21..35 = 15 CJK
      // (length 36). With targetTokens=10, overlapTokens=10 (targetChars = minChars = overlapChars
      // = 10) the PRE-FIX loop trace is:
      //   window0: pos=0, targetEnd=10; the sentence boundary after '.'+9 spaces returns endPos=21;
      //            raw [0,21) trims its trailing 9 spaces -> chunk0 span [0,12) ("<11 CJK>.").
      //   advance = max(21-0-10, 10) = 11 -> pos=11.
      //   window1: pos=11, targetEnd=21, endPos=21; raw [11,21) = '.'+9 spaces trims to "." ->
      //            span [11,12), which is fully contained in chunk0 [0,12). (This is the bug.)
      // The '.' at index 11 is already inside chunk0, so dropping the contained window loses no
      // coverage. POST-FIX chunks are [0,12), [21,31), [31,36).
      String cjk = "中"; // 中 (CJK Unified Ideograph)
      String content = cjk.repeat(11) + "." + " ".repeat(9) + cjk.repeat(15);

      List<ChunkSplitter.Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, 10, 10, ChunkSplitter.Mode.DEFAULT);

      assertTrue(
          chunks.size() >= 2,
          "Scenario must produce multiple chunks so the containment invariant is exercised; got "
              + chunks.size());

      // Invariant under test: no chunk's span may be contained in its predecessor's.
      for (int i = 1; i < chunks.size(); i++) {
        ChunkSplitter.Chunk prev = chunks.get(i - 1);
        ChunkSplitter.Chunk curr = chunks.get(i);
        boolean contained =
            curr.startChar() >= prev.startChar() && curr.endChar() <= prev.endChar();
        assertFalse(
            contained,
            "Chunk "
                + i
                + " ["
                + curr.startChar()
                + ","
                + curr.endChar()
                + ") is fully contained within predecessor "
                + (i - 1)
                + " ["
                + prev.startChar()
                + ","
                + prev.endChar()
                + ")");
      }

      // The offset law must still hold for every surviving chunk.
      for (ChunkSplitter.Chunk chunk : chunks) {
        assertEquals(
            chunk.content(),
            content.substring(chunk.startChar(), chunk.endChar()),
            "chunk offsets must reconstruct content");
      }

      // Full content coverage: every non-whitespace char falls inside some chunk span.
      for (int i = 0; i < content.length(); i++) {
        if (Character.isWhitespace(content.charAt(i))) {
          continue;
        }
        final int idx = i;
        boolean covered = chunks.stream().anyMatch(c -> idx >= c.startChar() && idx < c.endChar());
        assertTrue(
            covered,
            "non-whitespace char at index "
                + idx
                + " ('"
                + content.charAt(idx)
                + "') must be covered by some chunk span");
      }
    }
  }

  /**
   * Deterministic plain-text string of exactly {@code length} chars: words separated by single
   * spaces, no leading or trailing whitespace.
   */
  private static String plainText(int length) {
    String[] words = {
      "alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel",
      "india", "juliet", "kilo", "lima", "mike", "november", "oscar", "papa"
    };
    StringBuilder sb = new StringBuilder(length);
    int wordIndex = 0;
    while (sb.length() < length) {
      if (sb.length() > 0) {
        sb.append(' ');
      }
      sb.append(words[wordIndex % words.length]);
      wordIndex++;
    }
    String result = sb.substring(0, length);
    if (Character.isWhitespace(result.charAt(length - 1))) {
      result = result.substring(0, length - 1) + "x";
    }
    return result;
  }

  private static int countOccurrences(String text, String needle) {
    if (text == null || text.isEmpty() || needle == null || needle.isEmpty()) {
      return 0;
    }
    int count = 0;
    int idx = 0;
    while (true) {
      int next = text.indexOf(needle, idx);
      if (next < 0) break;
      count++;
      idx = next + needle.length();
    }
    return count;
  }
}
