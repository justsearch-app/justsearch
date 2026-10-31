package io.justsearch.indexerworker.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.indexing.chunking.ChunkSplitter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for F8 Tier 2: Line number calculation and heading extraction in ChunkDocumentWriter.
 */
class ChunkDocumentWriterLineFieldsTest {

  @Test
  void D42PublishedMetadataKeepsPrefixLawsIncludingInsideHeadings() {
    var samples = new java.util.ArrayList<>(java.util.List.of("  leading\n## Heading words  \nbody\n### Next\nmore",
        "# Title\r\n\r\n## Section\r\ntext", "#\ncontinued heading\nbody", "plain\ntext", "## Page 1\nPDF text\n## Sheet 2\nOffice text"));
    var random = new java.util.Random(917);
    String[] pieces = {"#", "##", " ", "\t", "\n", "\r\n", "body", "heading", "### ", "\f", "\r"};
    for (int document = 0; document < 1000; document++) {
      var text = new StringBuilder();
      for (int piece = 0; piece < 12; piece++) text.append(pieces[random.nextInt(pieces.length)]);
      samples.add(text.toString());
    }
    for (String content : samples) {
      var chunks = new java.util.ArrayList<ChunkSplitter.Chunk>();
      for (int offset = 0; offset < content.length(); offset++) {
        int end = offset + 1;
        while (end < content.length() && content.substring(offset, end).isBlank()) end++;
        if (!content.substring(offset, end).isBlank()) {
          chunks.add(ChunkSplitter.Chunk.of(chunks.size(), content.substring(offset, end), offset, end));
        }
      }
      var writer = org.mockito.Mockito.mock(IndexingCoordinator.class);
      org.mockito.Mockito.doAnswer(invocation -> {
        var fields = invocation.getArgument(0, IndexDocument.class).fields();
        int start = Integer.parseInt((String) fields.get(SchemaFields.CHUNK_START_CHAR));
        int end = Integer.parseInt((String) fields.get(SchemaFields.CHUNK_END_CHAR));
        assertEquals(String.valueOf(ChunkOffsetMath.calculateLineNumber(content, start)),
            fields.get(SchemaFields.CHUNK_START_LINE));
        assertEquals(String.valueOf(ChunkOffsetMath.calculateLineNumber(content, end - 1)),
            fields.get(SchemaFields.CHUNK_END_LINE));
        var heading = ChunkOffsetMath.findPrecedingHeading(content, start);
        assertEquals(heading.text(), fields.get(SchemaFields.CHUNK_HEADING_TEXT),
            "offset " + start + " in " + content);
        assertEquals(String.valueOf(heading.level()), fields.get(SchemaFields.CHUNK_HEADING_LEVEL));
        return null;
      }).when(writer).indexSingle(org.mockito.ArgumentMatchers.any(IndexDocument.class));
      var plan = new ChunkDocumentWriter.ChunkPlan(ChunkParentRevision.sha256Hex(content), chunks);
      for (String kind : java.util.List.of("markdown", "pdf", "office")) {
        assertEquals(chunks.size(), ChunkDocumentWriter.replaceDeclaredChunks(writer, "/parent", content,
            new ChunkDocumentWriter.ParentChunkMetadata(null, null, kind, null, null, null, "uid"),
            plan, false));
      }
    }
  }

  @Nested
  @DisplayName("calculateLineNumber")
  class CalculateLineNumberTests {

    @Test
    @DisplayName("empty content returns line 1")
    void emptyContent() {
      assertEquals(1, ChunkOffsetMath.calculateLineNumber("", 0));
      assertEquals(1, ChunkOffsetMath.calculateLineNumber(null, 0));
    }

    @Test
    @DisplayName("single line content returns line 1")
    void singleLine() {
      assertEquals(1, ChunkOffsetMath.calculateLineNumber("hello world", 0));
      assertEquals(1, ChunkOffsetMath.calculateLineNumber("hello world", 5));
      assertEquals(1, ChunkOffsetMath.calculateLineNumber("hello world", 11));
    }

    @Test
    @DisplayName("multi-line content returns correct line numbers")
    void multiLine() {
      String content = "line1\nline2\nline3";
      // "line1" is 5 chars, newline at index 5
      // "line2" is 5 chars, newline at index 11
      // "line3" starts at index 12

      assertEquals(1, ChunkOffsetMath.calculateLineNumber(content, 0));  // start of line1
      assertEquals(1, ChunkOffsetMath.calculateLineNumber(content, 4));  // end of "line1"
      assertEquals(1, ChunkOffsetMath.calculateLineNumber(content, 5));  // at newline (before crossing)
      assertEquals(2, ChunkOffsetMath.calculateLineNumber(content, 6));  // start of line2
      assertEquals(2, ChunkOffsetMath.calculateLineNumber(content, 11)); // at second newline
      assertEquals(3, ChunkOffsetMath.calculateLineNumber(content, 12)); // start of line3
      assertEquals(3, ChunkOffsetMath.calculateLineNumber(content, 17)); // end of line3
    }

    @Test
    @DisplayName("offset beyond content length is clamped")
    void offsetBeyondLength() {
      String content = "line1\nline2";
      // Should not throw; should count lines up to content length
      assertEquals(2, ChunkOffsetMath.calculateLineNumber(content, 1000));
    }

    @Test
    @DisplayName("negative offset returns line 1")
    void negativeOffset() {
      assertEquals(1, ChunkOffsetMath.calculateLineNumber("hello\nworld", -5));
    }

    @Test
    @DisplayName("consecutive newlines are counted correctly")
    void consecutiveNewlines() {
      String content = "a\n\n\nb";
      // a at 0, newline at 1, newline at 2, newline at 3, b at 4
      assertEquals(1, ChunkOffsetMath.calculateLineNumber(content, 0)); // a
      assertEquals(1, ChunkOffsetMath.calculateLineNumber(content, 1)); // first newline
      assertEquals(2, ChunkOffsetMath.calculateLineNumber(content, 2)); // second newline (after first)
      assertEquals(3, ChunkOffsetMath.calculateLineNumber(content, 3)); // third newline (after second)
      assertEquals(4, ChunkOffsetMath.calculateLineNumber(content, 4)); // b
    }
  }

  @Nested
  @DisplayName("findPrecedingHeading")
  class FindPrecedingHeadingTests {

    @Test
    @DisplayName("empty content returns NONE")
    void emptyContent() {
      var result = ChunkOffsetMath.findPrecedingHeading("", 0);
      assertEquals(ChunkOffsetMath.HeadingInfo.NONE, result);

      result = ChunkOffsetMath.findPrecedingHeading(null, 0);
      assertEquals(ChunkOffsetMath.HeadingInfo.NONE, result);
    }

    @Test
    @DisplayName("no headings returns NONE")
    void noHeadings() {
      String content = "Just some plain text\nwithout any headings.";
      var result = ChunkOffsetMath.findPrecedingHeading(content, 40);
      assertEquals(ChunkOffsetMath.HeadingInfo.NONE, result);
    }

    @Test
    @DisplayName("single heading before offset is found")
    void singleHeadingBefore() {
      String content = "# Introduction\n\nSome content here.";
      var result = ChunkOffsetMath.findPrecedingHeading(content, 30);
      assertEquals(1, result.level());
      assertEquals("Introduction", result.text());
    }

    @Test
    @DisplayName("heading after offset is not found")
    void headingAfterOffset() {
      String content = "Some preamble\n\n# Introduction\n\nMore content.";
      var result = ChunkOffsetMath.findPrecedingHeading(content, 10);
      assertEquals(ChunkOffsetMath.HeadingInfo.NONE, result);
    }

    @Test
    @DisplayName("multiple headings returns nearest preceding")
    void multipleHeadings() {
      String content = "# Title\n\n## Section 1\n\nContent in section 1.\n\n## Section 2\n\nContent in section 2.";

      // Position in "Section 1" content
      var result = ChunkOffsetMath.findPrecedingHeading(content, 35);
      assertEquals(2, result.level());
      assertEquals("Section 1", result.text());

      // Position in "Section 2" content
      result = ChunkOffsetMath.findPrecedingHeading(content, 70);
      assertEquals(2, result.level());
      assertEquals("Section 2", result.text());
    }

    @Test
    @DisplayName("heading levels 1-6 are detected")
    void headingLevels() {
      assertHeadingLevel("# H1", 1);
      assertHeadingLevel("## H2", 2);
      assertHeadingLevel("### H3", 3);
      assertHeadingLevel("#### H4", 4);
      assertHeadingLevel("##### H5", 5);
      assertHeadingLevel("###### H6", 6);
    }

    private void assertHeadingLevel(String markdown, int expectedLevel) {
      String content = markdown + "\n\nSome content after.";
      var result = ChunkOffsetMath.findPrecedingHeading(content, content.length());
      assertEquals(expectedLevel, result.level());
    }

    @Test
    @DisplayName("heading without space after # is not detected")
    void headingNeedsSpace() {
      String content = "#NotAHeading\n\nSome content.";
      var result = ChunkOffsetMath.findPrecedingHeading(content, content.length());
      assertEquals(ChunkOffsetMath.HeadingInfo.NONE, result);
    }

    @Test
    @DisplayName("heading with trailing whitespace is trimmed")
    void headingTrimmed() {
      String content = "## My Heading   \n\nContent.";
      var result = ChunkOffsetMath.findPrecedingHeading(content, content.length());
      assertEquals("My Heading", result.text());
    }

    @Test
    @DisplayName("mid-line # is not detected as heading")
    void midLineHash() {
      String content = "Some text with #hash tag\n\nMore content.";
      var result = ChunkOffsetMath.findPrecedingHeading(content, content.length());
      assertEquals(ChunkOffsetMath.HeadingInfo.NONE, result);
    }

    @Test
    @DisplayName("zero offset returns NONE")
    void zeroOffset() {
      String content = "# Heading\n\nContent.";
      var result = ChunkOffsetMath.findPrecedingHeading(content, 0);
      assertEquals(ChunkOffsetMath.HeadingInfo.NONE, result);
    }
  }
}
