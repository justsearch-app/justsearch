/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.fixtures;

import io.justsearch.indexerworker.fixtures.FormatCapabilityFixtureFactory.FormatId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned expected-state oracle for the owned format-capability fixtures. */
public final class FormatCapabilityExpectedState {

  public static final String ORACLE_VERSION = "format-capability-v1";

  public enum Classification {
    PASS,
    KNOWN_GAP
  }

  public enum EmbeddedIdentityExpectation {
    NOT_APPLICABLE,
    CONTENT_ONLY,
    RENDERED_LABELS_ONLY
  }

  public enum FailureClass {
    NONE,
    MIME_OR_PARSER_UNSUPPORTED,
    EMBEDDED_CONTENT_OR_IDENTITY_LOSS,
    STRUCTURE_FLATTENED
  }

  public record StructuredCounts(int tables, int headings, int lists) {}

  public record ExpectedState(
      String recipeId,
      String sha256,
      String mimeType,
      String policyId,
      String parserAdapterId,
      List<String> requiredMarkers,
      List<String> expectedAbsentMarkers,
      StructuredCounts structuredCounts,
      int embeddedResourceCount,
      int maxEmbeddedDepth,
      EmbeddedIdentityExpectation embeddedIdentity,
      Classification classification,
      Set<FailureClass> failureClasses,
      String exactAnnotatedText) {}

  private static final String DEFAULT_POLICY = "tika-default-v1";
  private static final String STRUCTURED_ADAPTER = "tika-policy-structured";

  private static final Map<FormatId, ExpectedState> EXPECTED =
      Map.of(
          FormatId.EML,
          state(
              "eml-multipart-v1",
              "c6592df794f22f39b7222cecd4383348227df0c9ce28fd5cce0d49dd9b77626f",
              "message/rfc822",
              List.of(
                  "JUSTSEARCH_EML_SUBJECT_MARKER",
                  "JUSTSEARCH_EML_BODY_MARKER",
                  "JUSTSEARCH_EML_ATTACHMENT_MARKER"),
              counts(0, 0, 0),
              1,
              1,
              EmbeddedIdentityExpectation.CONTENT_ONLY,
              Classification.KNOWN_GAP,
              FailureClass.EMBEDDED_CONTENT_OR_IDENTITY_LOSS,
              "JUSTSEARCH_EML_SUBJECT_MARKER\n\n"
                  + "JUSTSEARCH_EML_BODY_MARKER\r\nOwned message body.\n\n"
                  + "JUSTSEARCH_EML_ATTACHMENT_MARKER\n"
                  + "Owned attachment content from the deterministic EML recipe."),
          FormatId.MBOX,
          stateWithAbsentMarkers(
              "mbox-two-messages-v1",
              "43c0e5733584d8bc720cfcb2014a30b9acfaf5b52d5fb39fa7d4aa2bb81857ab",
              "application/mbox",
              List.of(
                  "JUSTSEARCH_MBOX_FIRST_BODY",
                  "JUSTSEARCH_MBOX_SECOND_BODY",
                  "JUSTSEARCH_MBOX_ATTACHMENT_MARKER"),
              List.of("JUSTSEARCH_MBOX_FIRST_SUBJECT", "JUSTSEARCH_MBOX_SECOND_SUBJECT"),
              counts(0, 0, 0),
              3,
              2,
              EmbeddedIdentityExpectation.CONTENT_ONLY,
              Classification.KNOWN_GAP,
              Set.of(
                  FailureClass.MIME_OR_PARSER_UNSUPPORTED,
                  FailureClass.EMBEDDED_CONTENT_OR_IDENTITY_LOSS),
              "JUSTSEARCH_MBOX_FIRST_BODY\nOwned first message.\n\n"
                  + "JUSTSEARCH_MBOX_SECOND_BODY\nOwned second message.\n\n"
                  + "JUSTSEARCH_MBOX_ATTACHMENT_MARKER\nOwned MBOX attachment."),
          FormatId.RTF,
          state(
              "rtf-ascii-v1",
              "4958481edeaf11bc831c03eaa6e93615047d39f1de66c0a1ac015da64a22613c",
              "application/rtf",
              List.of("JUSTSEARCH_RTF_SMOKE_MARKER", "Deterministic rich text fixture."),
              counts(0, 0, 0),
              0,
              0,
              EmbeddedIdentityExpectation.NOT_APPLICABLE,
              Classification.PASS,
              FailureClass.NONE,
              "JUSTSEARCH_RTF_SMOKE_MARKER\n\nDeterministic rich text fixture."),
          FormatId.EPUB,
          state(
              "epub3-chapter-v1",
              "f7dca209b6c75abcf7a0413192201d2fc4c87d2a8f15cc4766079d4a1214069a",
              "application/epub+zip",
              List.of("JUSTSEARCH_EPUB_HEADING_MARKER", "JUSTSEARCH_EPUB_LIST_MARKER"),
              counts(0, 2, 2),
              1,
              1,
              EmbeddedIdentityExpectation.NOT_APPLICABLE,
              Classification.PASS,
              FailureClass.NONE,
              "Owned EPUB capability\n\n# JUSTSEARCH_EPUB_HEADING_MARKER\n\n"
                  + "Owned deterministic EPUB chapter.\n\n"
                  + "- JUSTSEARCH_EPUB_LIST_MARKER\n- Second owned item\n\n"
                  + "# OEBPS/nav.xhtml\n\n1. Capability chapter"),
          FormatId.ODT,
          state(
              "odt-structure-v1",
              "ce3413652b4aa9f98b133743fbb5ba34e3a5d6f98593e5b4d6292705eeea00ca",
              "application/vnd.oasis.opendocument.text",
              List.of(
                  "JUSTSEARCH_ODT_HEADING_MARKER",
                  "JUSTSEARCH_ODT_LIST_MARKER",
                  "JUSTSEARCH_ODT_TABLE_MARKER"),
              counts(1, 1, 1),
              0,
              0,
              EmbeddedIdentityExpectation.NOT_APPLICABLE,
              Classification.PASS,
              FailureClass.NONE,
              "# JUSTSEARCH_ODT_HEADING_MARKER\n\n"
                  + "Owned deterministic OpenDocument text.\n\n"
                  + "- JUSTSEARCH_ODT_LIST_MARKER\n- Second owned list item\n\n"
                  + "Key = Owned row\nOwned row, Value = JUSTSEARCH_ODT_TABLE_MARKER"),
          FormatId.XLSX,
          state(
              "xlsx-inline-strings-v1",
              "fbd6124bd3975708b6706f9b54eb8e1ca37d5a327ef0ab842a2c406a51ce939d",
              "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
              List.of("Format breadth", "JUSTSEARCH_XLSX_SMOKE_MARKER"),
              counts(1, 1, 0),
              0,
              0,
              EmbeddedIdentityExpectation.NOT_APPLICABLE,
              Classification.PASS,
              FailureClass.NONE,
              "# Capability\n\n"
                  + "Fixture = Format breadth\n"
                  + "Format breadth, Evidence = JUSTSEARCH_XLSX_SMOKE_MARKER"),
          FormatId.XLSX_MERGED_HEADERS,
          state(
              "xlsx-merged-multirow-v1",
              "c04610b48347bdded0cd65baf1c9ccfe8e3751bb1633f21887fad40656003f1f",
              "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
              List.of("Quarterly revenue", "Q1", "JUSTSEARCH_XLSX_MERGED_MARKER"),
              counts(1, 1, 0),
              0,
              0,
              EmbeddedIdentityExpectation.NOT_APPLICABLE,
              Classification.KNOWN_GAP,
              FailureClass.STRUCTURE_FLATTENED,
              "# Merged headers\n\n"
                  + "Quarterly revenue = Q1\n"
                  + "Region = North\n"
                  + "North, Quarterly revenue = JUSTSEARCH_XLSX_MERGED_MARKER"),
          FormatId.XLSX_TYPED_CELLS,
          state(
              "xlsx-numeric-date-formula-v1",
              "ec45039685a0489eb340099752a398e802363c7192967954e4e986153e821ed0",
              "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
              List.of("42", "2024-01-01", "JUSTSEARCH_XLSX_FORMULA_MARKER", "84"),
              counts(1, 1, 0),
              0,
              0,
              EmbeddedIdentityExpectation.NOT_APPLICABLE,
              Classification.KNOWN_GAP,
              FailureClass.STRUCTURE_FLATTENED,
              "# Typed cells\n\n"
                  + "Field = Typed number\nTyped number, Value = 42\n"
                  + "Field = Typed date\nTyped date, Value = 2024-01-01\n"
                  + "Field = JUSTSEARCH_XLSX_FORMULA_MARKER\n"
                  + "JUSTSEARCH_XLSX_FORMULA_MARKER, Value = 84"),
          FormatId.PPTX_WITH_NOTES,
          state(
              "pptx-speaker-notes-poi-v1",
              "36b0eb0b1768e684b8b0128bf35c6441c71106307852740330548c7c4710985e",
              "application/vnd.openxmlformats-officedocument.presentationml.presentation",
              List.of(
                  "JUSTSEARCH_PPTX_SLIDE_MARKER",
                  "JUSTSEARCH_PPTX_SPEAKER_NOTES_MARKER"),
              counts(0, 0, 0),
              // S5 (b74f2aa83) records the package's embedded parser callback and depth.
              1,
              1,
              EmbeddedIdentityExpectation.NOT_APPLICABLE,
              Classification.PASS,
              FailureClass.NONE,
              "JUSTSEARCH_PPTX_SLIDE_MARKER\n\n"
                  + "1.7.2013\n\n"
                  + "Click to edit Master text styles\n\n"
                  + "Second level\n\nThird level\n\nFourth level\n\nFifth level\n\n"
                  + "‹#›\n\n"
                  + "JUSTSEARCH_PPTX_SPEAKER_NOTES_MARKER"),
          FormatId.ZIP_WITH_XLSX,
          state(
              "zip-text-plus-xlsx-v1",
              "b5f01a3310da14c437588af3cba42b4da68ff057816b2e83a6e326b64e51807a",
              "application/zip",
              List.of("JUSTSEARCH_ZIP_TEXT_MARKER", "JUSTSEARCH_XLSX_SMOKE_MARKER"),
              counts(1, 3, 0),
              2,
              1,
              EmbeddedIdentityExpectation.RENDERED_LABELS_ONLY,
              Classification.KNOWN_GAP,
              FailureClass.EMBEDDED_CONTENT_OR_IDENTITY_LOSS,
              "# documents/format-capability.xlsx\n\n"
                  + "# Capability\n\n"
                  + "Fixture = Format breadth\n"
                  + "Format breadth, Evidence = JUSTSEARCH_XLSX_SMOKE_MARKER\n\n"
                  + "# readme.txt\n\n"
                  + "JUSTSEARCH_ZIP_TEXT_MARKER\n"
                  + "The sibling workbook marker must also be extracted."));

  private FormatCapabilityExpectedState() {}

  public static ExpectedState forFormat(FormatId id) {
    return EXPECTED.get(id);
  }

  private static StructuredCounts counts(int tables, int headings, int lists) {
    return new StructuredCounts(tables, headings, lists);
  }

  private static ExpectedState state(
      String recipeId,
      String sha256,
      String mimeType,
      List<String> markers,
      StructuredCounts counts,
      int embeddedResourceCount,
      int maxEmbeddedDepth,
      EmbeddedIdentityExpectation embeddedIdentity,
      Classification classification,
      FailureClass failureClass,
      String exactAnnotatedText) {
    return stateWithAbsentMarkers(
        recipeId,
        sha256,
        mimeType,
        markers,
        List.of(),
        counts,
        embeddedResourceCount,
        maxEmbeddedDepth,
        embeddedIdentity,
        classification,
        Set.of(failureClass),
        exactAnnotatedText);
  }

  private static ExpectedState stateWithAbsentMarkers(
      String recipeId,
      String sha256,
      String mimeType,
      List<String> markers,
      List<String> absentMarkers,
      StructuredCounts counts,
      int embeddedResourceCount,
      int maxEmbeddedDepth,
      EmbeddedIdentityExpectation embeddedIdentity,
      Classification classification,
      Set<FailureClass> failureClasses,
      String exactAnnotatedText) {
    return new ExpectedState(
        recipeId,
        sha256,
        mimeType,
        DEFAULT_POLICY,
        STRUCTURED_ADAPTER,
        markers,
        absentMarkers,
        counts,
        embeddedResourceCount,
        maxEmbeddedDepth,
        embeddedIdentity,
        classification,
        failureClasses,
        exactAnnotatedText);
  }
}
